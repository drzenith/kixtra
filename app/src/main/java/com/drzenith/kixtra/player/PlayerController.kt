package com.drzenith.kixtra.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.drzenith.kixtra.data.Channel
import com.drzenith.kixtra.data.Clip
import com.drzenith.kixtra.data.Store
import com.drzenith.kixtra.data.Video
import com.drzenith.kixtra.net.KickApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** What is loaded in the player. */
sealed class Playable {
    abstract val channel: Channel

    data class Live(override val channel: Channel) : Playable()

    /**
     * A VOD. [dvr] = the still-growing VOD of the stream that is live right now
     * ("watch from start"); Kick serves it as a playlist that keeps getting longer.
     */
    data class Vod(override val channel: Channel, val video: Video, val dvr: Boolean = false) : Playable()

    data class ClipItem(override val channel: Channel, val clip: Clip) : Playable()

    /** VODs and clips get the speed control; live streams don't. */
    val seekable: Boolean get() = this !is Live
}

data class Quality(val label: String, val height: Int, val fps: Int, val group: TrackGroup, val index: Int)

/**
 * Owns the single ExoPlayer instance. The UI, PiP and the background MediaSession all share it,
 * so playback carries on when the app is in the background.
 */
class PlayerController(context: Context, private val store: Store) {
    private val tag = "PlayerController"
    private val scope = MainScope()

    // Software-first decoder ordering when enabled (read each time a decoder is picked).
    private val codecSelector = MediaCodecSelector { mime, secure, tunneling ->
        val infos = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
        if (store.softwareDecoding) infos.sortedBy { !it.softwareOnly } else infos
    }

    val player: ExoPlayer = ExoPlayer.Builder(
        context,
        DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(codecSelector),
    )
        // Time-based buffer (not capped by bytes) so high bitrates at 2-4x keep enough runway; 60s max keeps memory sane.
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(30_000, 60_000, 2_000, 3_000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        )
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        .build()

    val current = MutableStateFlow<Playable?>(null)
    val error = MutableStateFlow<String?>(null)
    /** One-off informational message for the player UI. */
    val notice = MutableStateFlow<String?>(null)
    val loading = MutableStateFlow(false)
    val speed = MutableStateFlow(1f)
    val isPlaying = MutableStateFlow(false)
    val qualities = MutableStateFlow<List<Quality>>(emptyList())

    /** "Auto", "Audio only" or a quality label. */
    val selectedQuality = MutableStateFlow("Auto")

    private var loadJob: Job? = null
    private var refreshJob: Job? = null
    private var qualityAppliedForItem = false
    private var audioOnly = store.preferredQuality == -1
    private var inBackground = false
    private var lastRefreshAt = 0L

    init {
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) = rebuildQualities(tracks)

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying.value = playing
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                speed.value = playbackParameters.speed
                if (current.value?.seekable == true) store.vodSpeed = playbackParameters.speed
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) onEnded()
            }

            override fun onPlayerError(e: PlaybackException) {
                Log.w(tag, "player error", e)
                val p = current.value
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
                    return
                }
                val decoderError = e.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                    e.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                    e.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ||
                    e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
                    e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                if (decoderError && !store.softwareDecoding) {
                    // Hardware decoder failed (common on Waydroid/emulators): switch to software and resume.
                    store.softwareDecoding = true
                    notice.value = "Hardware decoder failed, switched to software decoding (change in Settings)"
                    val pos = if (p != null && p.seekable) player.currentPosition else null
                    if (p != null) load(p, pos)
                    return
                }
                // Signed playlist URLs expire; for VODs try once more with a fresh URL.
                if (p != null && !decoderError && System.currentTimeMillis() - lastRefreshAt > 20_000) {
                    reload(keepPosition = true)
                } else {
                    error.value = "Playback error: ${e.errorCodeName}"
                }
            }
        })
        // Save resume positions periodically.
        scope.launch {
            while (isActive) {
                delay(10_000)
                saveProgress()
            }
        }
    }

    fun isVod() = current.value?.seekable == true

    private var resolveJob: Job? = null

    /** Opens a live channel by slug (fetches fresh channel info first). */
    fun playLive(slug: String) {
        resolveJob?.cancel()
        saveProgress()
        loadJob?.cancel()
        player.stop()
        current.value = null
        error.value = null
        loading.value = true
        resolveJob = scope.launch {
            try {
                val ch = KickApi.channel(slug)
                if (ch.live == null) {
                    error.value = "${ch.username} is offline"
                    loading.value = false
                } else {
                    play(Playable.Live(ch))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = e.message ?: "Couldn't load channel"
                loading.value = false
            }
        }
    }

    /** [startAtMs] overrides the saved resume position (VODs only). */
    fun play(p: Playable, startAtMs: Long? = null) {
        saveProgress()
        current.value = p
        error.value = null
        qualities.value = emptyList()
        load(p, startPositionMs = startAtMs)
    }

    /** Plays the VOD a clip was cut from, starting where the clip starts. */
    fun playClipSource(clip: Clip) {
        resolveJob?.cancel()
        saveProgress()
        loadJob?.cancel()
        player.stop()
        current.value = null
        error.value = null
        loading.value = true
        resolveJob = scope.launch {
            try {
                val src = KickApi.clipSource(clip)
                when {
                    src == null -> {
                        error.value = "Couldn't find the VOD this clip is from (it may have been deleted)"
                        loading.value = false
                    }
                    src.dvr -> {
                        val v = com.drzenith.kixtra.ui.Actions.dvrVideo(src.channel)
                        if (v == null) {
                            error.value = "Couldn't find the VOD this clip is from"
                            loading.value = false
                        } else {
                            play(Playable.Vod(src.channel, v, dvr = true), src.offsetMs)
                        }
                    }
                    else -> play(Playable.Vod(src.channel, src.video!!), src.offsetMs)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = e.message ?: "Couldn't load the VOD"
                loading.value = false
            }
        }
    }

    private fun load(p: Playable, startPositionMs: Long?) {
        loadJob?.cancel()
        refreshJob?.cancel()
        loading.value = true
        qualityAppliedForItem = false
        loadJob = scope.launch {
            try {
                val url: String
                val metaTitle: String
                when (p) {
                    is Playable.Live -> {
                        url = p.channel.live?.playbackUrl
                            ?: KickApi.channel(p.channel.slug).live?.playbackUrl
                            ?: throw IllegalStateException("${p.channel.username} is offline")
                        metaTitle = p.channel.live?.title ?: p.channel.username
                    }
                    is Playable.Vod -> {
                        url = if (p.dvr) {
                            KickApi.liveDvrUrl(p.channel)
                                ?: throw IllegalStateException("This stream has no VOD yet")
                        } else {
                            KickApi.vodUrl(p.video)
                        }
                        metaTitle = p.video.title
                    }
                    is Playable.ClipItem -> {
                        url = KickApi.clipUrl(p.clip)
                        metaTitle = p.clip.title
                    }
                }
                lastRefreshAt = System.currentTimeMillis()
                val meta = MediaMetadata.Builder()
                    .setTitle(metaTitle)
                    .setArtist(p.channel.username)
                    .apply { p.channel.avatar?.let { setArtworkUri(Uri.parse(it)) } }
                    .build()
                val builder = MediaItem.Builder()
                    .setUri(url)
                    .setMediaMetadata(meta)
                // Clips can be plain MP4; everything else is HLS.
                if (p !is Playable.ClipItem || url.contains(".m3u8")) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
                if (p is Playable.ClipItem) {
                    player.setMediaItem(builder.build(), startPositionMs ?: 0L)
                    player.setPlaybackSpeed(store.vodSpeed)
                } else if (p is Playable.Vod) {
                    // A growing VOD is a "live" playlist to ExoPlayer. Stop it from nudging the
                    // speed towards the live edge; the user's speed always wins.
                    builder.setLiveConfiguration(
                        MediaItem.LiveConfiguration.Builder()
                            .setMinPlaybackSpeed(1f)
                            .setMaxPlaybackSpeed(1f)
                            .build()
                    )
                    val resume = startPositionMs ?: resumePosition(p)
                    player.setMediaItem(builder.build(), resume)
                    player.setPlaybackSpeed(store.vodSpeed)
                } else {
                    player.setMediaItem(builder.build())
                    player.setPlaybackSpeed(1f)
                }
                applyVideoDisabled()
                player.prepare()
                player.play()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(tag, "load failed", e)
                error.value = e.message ?: "Couldn't load stream"
            } finally {
                loading.value = false
            }
        }
    }

    private fun resumePosition(p: Playable.Vod): Long {
        val saved = store.position(progressKey(p))
        val dur = p.video.durationMs
        return if (dur > 0 && saved > dur - 15_000) 0L else saved
    }

    private fun progressKey(p: Playable.Vod) = if (p.dvr) "dvr_${p.channel.live?.id}" else p.video.key

    fun saveProgress() {
        val p = current.value as? Playable.Vod ?: return
        if (player.mediaItemCount == 0) return
        store.savePosition(progressKey(p), player.currentPosition.coerceAtLeast(0))
    }

    /** Re-resolves the playlist URL and continues where we were (expired URLs, snapshot playlists). */
    fun reload(keepPosition: Boolean) {
        val p = current.value ?: return
        val pos = if (keepPosition && p is Playable.Vod) player.currentPosition else null
        error.value = null
        load(p, pos)
    }

    private fun onEnded() {
        val p = current.value as? Playable.Vod ?: return
        val stillLive = p.dvr || p.video.isLive
        if (!stillLive) return
        // If Kick handed us a fixed snapshot of a VOD that is still growing, fetch the newer
        // playlist and carry on from the same spot.
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val wait = 15_000 - (System.currentTimeMillis() - lastRefreshAt)
            if (wait > 0) delay(wait)
            reload(keepPosition = true)
        }
    }

    fun setSpeed(s: Float) {
        player.setPlaybackSpeed(s)
        if (isVod()) store.vodSpeed = s
    }

    // ---------- Quality ----------

    private fun rebuildQualities(tracks: Tracks) {
        val list = mutableListOf<Quality>()
        for (g in tracks.groups) {
            if (g.type != C.TRACK_TYPE_VIDEO) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val f = g.getTrackFormat(i)
                val h = if (f.height > 0) f.height else 0
                val fps = if (f.frameRate > 0) f.frameRate.roundToInt() else 0
                val label = when {
                    h > 0 -> "${h}p" + if (fps >= 49) fps.toString() else ""
                    f.label != null -> f.label!!
                    else -> "${f.bitrate / 1000} kbps"
                }
                list += Quality(label, h, fps, g.mediaTrackGroup, i)
            }
        }
        val sorted = list.distinctBy { it.label }.sortedWith(compareByDescending<Quality> { it.height }.thenByDescending { it.fps })
        qualities.value = sorted
        if (!qualityAppliedForItem && sorted.isNotEmpty()) {
            qualityAppliedForItem = true
            applyPreferred(sorted)
        }
    }

    private fun applyPreferred(list: List<Quality>) {
        when (val pref = store.preferredQuality) {
            0 -> selectAuto(persist = false)
            -1 -> selectAudioOnly(persist = false)
            else -> {
                val pick = list.filter { it.height in 1..pref }.maxWithOrNull(compareBy<Quality> { it.height }.thenBy { it.fps })
                    ?: list.minByOrNull { abs(it.height - pref) }
                if (pick != null) selectQuality(pick, persist = false) else selectAuto(false)
            }
        }
    }

    fun selectQuality(q: Quality, persist: Boolean = true) {
        audioOnly = false
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(q.group, q.index))
            .build()
        applyVideoDisabled()
        selectedQuality.value = q.label
        if (persist) store.preferredQuality = q.height.coerceAtLeast(1)
    }

    fun selectAuto(persist: Boolean = true) {
        audioOnly = false
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .build()
        applyVideoDisabled()
        selectedQuality.value = "Auto"
        if (persist) store.preferredQuality = 0
    }

    fun selectAudioOnly(persist: Boolean = true) {
        audioOnly = true
        applyVideoDisabled()
        selectedQuality.value = "Audio only"
        if (persist) store.preferredQuality = -1
    }

    private fun applyVideoDisabled() {
        val disable = audioOnly || inBackground
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, disable)
            .build()
    }

    // ---------- App lifecycle ----------

    /** App went to the background (not PiP): keep audio going without downloading video, or pause. */
    fun onBackground() {
        saveProgress()
        if (current.value == null) return
        if (store.backgroundAudio && player.playWhenReady) {
            inBackground = true
            applyVideoDisabled()
        } else {
            player.pause()
        }
    }

    fun onForeground() {
        if (inBackground) {
            inBackground = false
            applyVideoDisabled()
        }
    }

    fun stop() {
        saveProgress()
        resolveJob?.cancel()
        loadJob?.cancel()
        refreshJob?.cancel()
        loading.value = false
        player.stop()
        player.clearMediaItems()
        current.value = null
        error.value = null
        qualities.value = emptyList()
    }
}
