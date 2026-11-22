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
import androidx.media3.exoplayer.SeekParameters
import android.graphics.Bitmap
import android.view.TextureView
import com.drzenith.kixtra.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import com.drzenith.kixtra.chat.ChatSource
import com.drzenith.kixtra.chat.LiveChat
import com.drzenith.kixtra.chat.ReplayChat
import com.drzenith.kixtra.chat.parseKickTime
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
private const val CHECKPOINTS = 100

class PlayerController(private val context: Context, private val store: Store) {
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

    /** Fires whenever a frame is first drawn on a (new) video surface; used for smooth hand-offs. */
    val firstFrame = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Chat for what's playing. It belongs to the video, not the player screen, so minimizing and
     * expanding reuse what's already loaded. Replaced (and the old one dropped) when the video changes.
     */
    val chat = MutableStateFlow<ChatSource?>(null)
    private var chatKey: Playable? = null

    fun syncChat() {
        val p = current.value
        val want = if (store.chatEnabled && p != null && p.channel.id != 0L && p !is Playable.ClipItem) p else null
        if (want == chatKey && (want == null || chat.value != null)) return
        chat.value?.stop()
        chatKey = want
        chat.value = when (want) {
            is Playable.Live -> want.channel.chatroomId?.let { LiveChat(want.channel.id, it, scope) }
            is Playable.Vod -> parseKickTime(want.video.startTime)?.let { start ->
                ReplayChat(want.channel.id, start, scope) { player.currentPosition }
            }
            else -> null
        }?.also { it.start() }
    }
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

            override fun onRenderedFirstFrame() {
                firstFrame.tryEmit(Unit)
            }

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
        syncChat()
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
        syncChat()
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
        syncChat()
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
                currentUrl = url
                currentIsHls = p !is Playable.ClipItem || url.contains(".m3u8")
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
                if (p.seekable && previewUrl != url) {
                    releasePreview()
                    startPreviews(url, startPositionMs ?: player.currentPosition)
                }
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

    // ---------- Scrub preview ----------
    // Previews are prepared in the background while the VOD plays, so scrubbing is instant:
    //  * Fast path: Kick's VODs are Amazon IVS recordings, which can store a JPEG every N seconds
    //    next to the video (…/media/thumbnails/thumb<n>.jpg). We look for them, work out the
    //    interval, and preload an evenly spaced set (up to ~200 small images).
    //  * Fallback: a hidden, muted, lowest-quality player walks through 100 checkpoints (a ratio of
    //    the VOD length, so 20-minute and 30-hour VODs behave the same) and keeps a small frame of
    //    each. On mobile data this only happens on demand while scrubbing.

    /** IVS thumbnail track: thumb<i>.jpg every [intervalMs]; [step] = spacing of preloaded ones. */
    class ThumbTrack(
        private val base: String,
        private val query: String,
        val intervalMs: Long,
        val count: Int,
        val step: Int,
    ) {
        fun url(index: Int): String = base + index + ".jpg" + (if (query.isEmpty()) "" else "?$query")

        /** Nearest preloaded thumbnail for a position. */
        fun indexFor(positionMs: Long): Int {
            val raw = (positionMs.toDouble() / intervalMs).toInt().coerceAtLeast(0)
            return ((raw + step / 2) / step) * step
        }
    }

    val thumbTrack = MutableStateFlow<ThumbTrack?>(null)
    private var thumbJob: Job? = null
    private var preloadJob: Job? = null

    private suspend fun headOk(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Http.client.newCall(
                Request.Builder().url(url).head().header("User-Agent", Http.BROWSER_UA).build()
            ).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    private suspend fun awaitDuration(): Long {
        var dur = player.duration
        var tries = 0
        while (dur <= 0 && tries < 80) {
            delay(250)
            dur = player.duration
            tries++
        }
        return dur
    }

    /** Looks for IVS thumbnails next to the playlist; returns null if there are none. */
    private suspend fun findThumbs(url: String, dur: Long): ThumbTrack? {
        if (!url.contains("/media/hls/")) return null
        val root = url.substringBefore("/media/hls/") + "/media/thumbnails/"
        val query = url.substringAfter('?', "")
        fun u(path: String) = root + path + (if (query.isEmpty()) "" else "?$query")
        val dir = listOf("", "high/", "1080p/", "720p/", "480p/", "360p/", "160p/", "lowest/")
            .firstOrNull { headOk(u(it + "thumb0.jpg")) }
        if (dir == null) {
            Log.i(tag, "No IVS thumbnails under $root; using video previews")
            return null
        }
        // Find the last thumbnail: exponential probe, then binary search.
        var hi = 1
        while (hi < 262_144 && headOk(u(dir + "thumb$hi.jpg"))) hi *= 2
        var lo = if (hi == 1) 0 else hi / 2
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (headOk(u(dir + "thumb$mid.jpg"))) lo = mid else hi = mid
        }
        val count = lo + 1
        val raw = dur / count
        val interval = listOf(1_000L, 2_000L, 5_000L, 10_000L, 15_000L, 20_000L, 30_000L, 60_000L, 120_000L)
            .minByOrNull { abs(it - raw) }?.takeIf { abs(it - raw) < it / 5 } ?: raw.coerceAtLeast(1_000)
        Log.i(tag, "IVS thumbnails: $count at ${interval}ms under $root$dir")
        return ThumbTrack(root + dir + "thumb", query, interval, count, step = (count / 200).coerceAtLeast(1))
    }

    /** Starts preparing previews for the VOD that just started. */
    private fun startPreviews(url: String, positionMs: Long) {
        thumbJob?.cancel()
        preloadJob?.cancel()
        thumbTrack.value = null
        thumbJob = scope.launch {
            val dur = awaitDuration()
            if (dur <= 0) return@launch
            val track = findThumbs(url, dur)
            if (track != null) {
                thumbTrack.value = track
                // Small images: preload the evenly spaced set on any connection.
                val loader = coil3.SingletonImageLoader.get(context)
                var i = 0
                while (i < track.count) {
                    loader.enqueue(coil3.request.ImageRequest.Builder(context).data(track.url(i)).build())
                    i += track.step
                }
                return@launch
            }
            // Video-frame fallback: warm the preview player; preload all checkpoints on Wi-Fi only.
            previewSeek(positionMs)
            if (!isMetered()) preloadJob = scope.launch { preloadCheckpoints() }
        }
    }

    private fun isMetered(): Boolean = runCatching {
        (context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).isActiveNetworkMetered
    }.getOrDefault(true)

    private var currentUrl: String? = null
    private var currentIsHls = true
    private var preview: ExoPlayer? = null
    private var previewUrl: String? = null
    private var previewView: TextureView? = null

    /** True while the user is dragging the seek bar (background preloading steps aside). */
    @Volatile private var scrubbing = false
    private var pendingCheckpoint = -1
    private var frameWaiter: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    /** One small frame per checkpoint (at most 101 entries, ~15 MB worst case). */
    private val frameCache = HashMap<Int, Bitmap>()

    /** Bumped whenever a new frame is cached, so the preview recomposes. */
    val frameVersion = MutableStateFlow(0)

    private fun checkpointStep(): Long = (player.duration / CHECKPOINTS).coerceAtLeast(1_000)

    fun checkpointFor(positionMs: Long): Int {
        if (player.duration <= 0) return 0
        val step = checkpointStep()
        return ((positionMs + step / 2) / step).toInt().coerceIn(0, CHECKPOINTS)
    }

    /** The cached frame for a checkpoint, or the nearest one we have so far. */
    fun frameFor(checkpoint: Int): Bitmap? {
        frameCache[checkpoint]?.let { return it }
        val nearest = frameCache.keys.minByOrNull { abs(it - checkpoint) } ?: return null
        return frameCache[nearest]
    }

    private fun previewPlayer(): ExoPlayer = preview ?: ExoPlayer.Builder(
        context,
        DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(codecSelector),
    )
        // Only ever needs one frame: keep the buffer tiny so each preview loads fast.
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(1_000, 2_000, 250, 500).build())
        .build().also { pp ->
        pp.volume = 0f
        pp.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        pp.trackSelectionParameters = pp.trackSelectionParameters.buildUpon()
            .setForceLowestBitrate(true)
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()
        pp.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                val cp = pendingCheckpoint
                val view = previewView
                if (cp >= 0 && view != null) {
                    runCatching { view.getBitmap(256, 144) }.getOrNull()?.let { bmp ->
                        frameCache[cp] = bmp
                        frameVersion.value++
                    }
                }
                frameWaiter?.complete(Unit)
            }
        })
        preview = pp
    }

    private suspend fun preloadCheckpoints() {
        if (awaitDuration() <= 0) return
        val start = checkpointFor(player.currentPosition)
        val order = (0..CHECKPOINTS).sortedBy { abs(it - start) }.toMutableList()
        var attempts = 0
        while (order.isNotEmpty() && attempts < CHECKPOINTS * 2) {
            while (scrubbing || previewView == null) delay(300)
            val idx = order.removeAt(0)
            if (frameCache.containsKey(idx)) continue
            attempts++
            val waiter = kotlinx.coroutines.CompletableDeferred<Unit>()
            frameWaiter = waiter
            loadCheckpoint(idx)
            kotlinx.coroutines.withTimeoutOrNull(6_000) { waiter.await() }
            if (!frameCache.containsKey(idx) && attempts < CHECKPOINTS * 2) order.add(idx)
        }
    }

    private fun loadCheckpoint(idx: Int) {
        pendingCheckpoint = idx
        previewSeek((idx * checkpointStep()).coerceAtMost((player.duration - 1).coerceAtLeast(0)))
    }

    /** Called while the seek bar is dragged: loads that checkpoint now if it isn't cached yet. */
    fun previewScrub(positionMs: Long) {
        scrubbing = true
        if (thumbTrack.value != null || player.duration <= 0) return
        val idx = checkpointFor(positionMs)
        if (frameCache.containsKey(idx) || idx == pendingCheckpoint) return
        loadCheckpoint(idx)
    }

    fun scrubEnded() {
        scrubbing = false
    }

    private fun previewSeek(positionMs: Long) {
        val url = currentUrl ?: return
        val pp = previewPlayer()
        if (previewUrl != url) {
            val item = MediaItem.Builder().setUri(url)
                .apply { if (currentIsHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
                .build()
            pp.setMediaItem(item, positionMs)
            pp.prepare()
            previewUrl = url
        } else {
            pp.seekTo(positionMs)
        }
        pp.playWhenReady = false
    }

    /** The hidden view the preview player renders into (frames are copied out of it). */
    fun attachPreview(view: TextureView) {
        previewView = view
        previewPlayer().setVideoTextureView(view)
    }

    fun detachPreview(view: TextureView) {
        if (previewView === view) previewView = null
        preview?.clearVideoTextureView(view)
    }

    private fun releasePreview() {
        thumbJob?.cancel()
        preloadJob?.cancel()
        thumbTrack.value = null
        preview?.release()
        preview = null
        previewUrl = null
        pendingCheckpoint = -1
        frameWaiter = null
        frameCache.clear()
        frameVersion.value++
    }

    fun stop() {
        releasePreview()
        currentUrl = null
        saveProgress()
        resolveJob?.cancel()
        loadJob?.cancel()
        refreshJob?.cancel()
        loading.value = false
        player.stop()
        player.clearMediaItems()
        current.value = null
        syncChat()
        error.value = null
        qualities.value = emptyList()
    }
}
