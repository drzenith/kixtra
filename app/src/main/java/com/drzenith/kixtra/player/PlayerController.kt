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
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker
import androidx.media3.exoplayer.upstream.ParsingLoadable
import android.graphics.Bitmap
import android.view.TextureView
import com.drzenith.kixtra.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import com.drzenith.kixtra.data.Channel
import com.drzenith.kixtra.data.Clip
import com.drzenith.kixtra.data.Store
import com.drzenith.kixtra.data.Video
import com.drzenith.kixtra.data.liveVideo
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
     * A VOD. [dvr] = the VOD of the stream that is live right now. [startAtLive] = open it at the
     * live point (how live channels are watched), rather than at a saved position.
     */
    data class Vod(
        override val channel: Channel,
        val video: Video,
        val dvr: Boolean = false,
        val startAtLive: Boolean = false,
    ) : Playable() {
        /** Still being recorded: the playlist keeps growing. */
        val growing: Boolean get() = dvr || video.isLive
    }

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

    val player: ExoPlayer = ExoPlayer.Builder(
        context,
        // If a decoder fails to start, try the next one the device has.
        DefaultRenderersFactory(context).setEnableDecoderFallback(true),
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

    /**
     * Kick serves the VOD of a still-running stream as a playlist marked "finished". Stripping
     * that marker makes ExoPlayer treat it as a growing (EVENT) playlist: it re-checks it in the
     * background and new parts are appended without interrupting playback.
     */
    @Volatile private var stripEndList = false

    /** Set if Kick's playlist didn't grow when re-checked; we then fall back to reloading it. */
    private var snapshotMode = false

    private val hlsFactory = HlsMediaSource.Factory(DefaultDataSource.Factory(context))
        .setPlaylistParserFactory(GrowingPlaylistParserFactory { stripEndList })

    /** Automatic speed change (catching up to live): don't save it as the user's speed. */
    private var autoSpeed = false

    /**
     * LIVE is a state, not a distance (like YouTube/Twitch): you're live after Go live, catching up
     * at >1x, or opening a live channel; any rewind or pause takes you out of it. The live point is
     * treated as the end: skipping past it snaps back to it.
     */
    val atLive = MutableStateFlow(false)

    private fun targetDurationMs(): Long =
        ((player.currentManifest as? HlsManifest)?.mediaPlaylist?.targetDurationUs ?: 0L) / 1000

    // How often the live VOD actually grows (Kick adds video in batches, e.g. every ~30s).
    private var growthItem: Playable? = null
    private var lastGrowthDuration = 0L
    private var lastGrowthAt = 0L
    private val growthGaps = ArrayDeque<Long>()

    private fun trackGrowth(p: Playable) {
        if (growthItem != p) {
            growthItem = p
            lastGrowthDuration = 0L
            lastGrowthAt = 0L
            growthGaps.clear()
        }
        val d = player.duration
        if (d > lastGrowthDuration + 500) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (lastGrowthAt > 0 && lastGrowthDuration > 0) {
                growthGaps.addLast(now - lastGrowthAt)
                if (growthGaps.size > 5) growthGaps.removeFirst()
            }
            lastGrowthAt = now
            lastGrowthDuration = d
        }
    }

    /** Longest recent gap between the video growing (falls back to the segment length). */
    private fun growthCadenceMs(): Long = growthGaps.maxOrNull() ?: targetDurationMs()

    /**
     * How far behind the newest part "live" sits: one growth step plus a little slack, so playback
     * never reaches the end before the next batch of video arrives (the player's own default is
     * three segments, ~1 minute).
     */
    /**
     * How far behind the stream's real "now" a position is. The playlist grows in batches, so
     * "time left in the playlist" saw-tooths while you play at 1x; adding the time since it last
     * grew gives a steady number. (At 1x you never get closer to live, so LIVE is only entered by
     * Go live, catching up at >1x, or skipping forward to it, never by just playing.)
     */
    private fun liveLagMs(positionMs: Long): Long {
        val since = if (lastGrowthAt > 0) {
            (android.os.SystemClock.elapsedRealtime() - lastGrowthAt).coerceIn(0, growthCadenceMs())
        } else 0L
        return player.duration + since - positionMs
    }

    /** Largest lag that still counts as live: the live point plus one growth step, plus slack. */
    private fun liveLagLimitMs(): Long = liveEdgeOffsetMs() + growthCadenceMs() + 1_500

    private fun liveEdgeOffsetMs(): Long =
        maxOf(targetDurationMs() + 2_000, growthCadenceMs() + 3_000).coerceIn(4_000, 60_000)

    val current = MutableStateFlow<Playable?>(null)
    val error = MutableStateFlow<String?>(null)
    /** One-off informational message for the player UI. */
    val notice = MutableStateFlow<String?>(null)
    val loading = MutableStateFlow(false)
    val speed = MutableStateFlow(1f)
    val isPlaying = MutableStateFlow(false)

    /** Player volume (0..1, remembered) and mute (not remembered). */
    val volume = MutableStateFlow(store.volume)
    val muted = MutableStateFlow(false)

    fun setVolume(v: Float) {
        val vol = v.coerceIn(0f, 1f)
        volume.value = vol
        muted.value = false
        store.volume = vol
        player.volume = vol
    }

    fun toggleMute() {
        muted.value = !muted.value
        // Unmuting from a slider at zero brings back an audible level.
        if (!muted.value && volume.value == 0f) volume.value = 0.5f
        player.volume = if (muted.value) 0f else volume.value
    }

    /** Where the video is on screen (window px), so PiP can grow out of / shrink into it. */
    val videoRect = MutableStateFlow<android.graphics.Rect?>(null)

    /** Display aspect ratio of the current video (width / height). */
    val videoAspect = MutableStateFlow(16f / 9f)

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
        player.volume = store.volume
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) = rebuildQualities(tracks)

            override fun onRenderedFirstFrame() {
                firstFrame.tryEmit(Unit)
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoAspect.value = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (reason != Player.DISCONTINUITY_REASON_SEEK) return
                val p = current.value as? Playable.Vod ?: return
                if (!p.growing || player.duration <= 0) return
                val edge = liveEdgeOffsetMs()
                val newRemaining = player.duration - newPosition.positionMs
                when {
                    // Past the live point (the buffering zone): snap back to it.
                    newRemaining < edge - 500 -> {
                        player.seekTo((player.duration - edge).coerceAtLeast(0))
                        atLive.value = true
                    }
                    newPosition.positionMs < oldPosition.positionMs -> atLive.value = false
                    // Skipped forward to (about) as close as live gets.
                    liveLagMs(newPosition.positionMs) <= liveLagLimitMs() -> atLive.value = true
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // Pausing means falling behind live.
                if (!playWhenReady) atLive.value = false
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying.value = playing
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                speed.value = playbackParameters.speed
                if (autoSpeed) {
                    autoSpeed = false
                    return
                }
                val cur = current.value
                // Opening a live channel starts at 1x; don't let that overwrite your VOD speed.
                if (cur?.seekable == true && !(cur is Playable.Vod && cur.startAtLive && playbackParameters.speed == 1f)) {
                    store.vodSpeed = playbackParameters.speed
                }
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
                // A live VOD whose stream just ended: Kick retires the recording's playlist, so
                // continue in the finished VOD instead of reloading the dead one.
                if (p is Playable.Vod && p.growing) {
                    if (endJob?.isActive == true) return
                    endJob = scope.launch {
                        if (streamEnded(p)) switchToFinishedVod(p)
                        else if (current.value == p) handleError(e, p)
                    }
                    return
                }
                handleError(e, p)
            }
        })
        initLoops()
    }

    private fun handleError(e: PlaybackException, p: Playable?) {
                // Kick's playlist didn't grow when re-checked: fall back to reloading fresh copies.
                val stuck = generateSequence(e as Throwable) { it.cause }
                    .any { it is HlsPlaylistTracker.PlaylistStuckException }
                if (stuck && p is Playable.Vod && p.growing && !snapshotMode) {
                    Log.i(tag, "Live VOD playlist did not grow; switching to reload mode")
                    snapshotMode = true
                    reload(keepPosition = true)
                    return
                }
                // Signed playlist URLs expire; for VODs try once more with a fresh URL.
                if (p != null && System.currentTimeMillis() - lastRefreshAt > 20_000) {
                    reload(keepPosition = true)
                } else {
                    error.value = "Playback error: ${e.errorCodeName}"
                }
    }

    // ---------- Live VOD -> finished VOD ----------
    // When a stream ends, Kick retires the live recording's playlist (its parts stop loading,
    // even the ones you haven't reached yet) and publishes the finished VOD separately. So once
    // the stream is over we move to the finished VOD at the same spot.

    private var endJob: Job? = null

    /** Is the stream behind this live VOD over? (Offline, or live again with a new stream.) */
    private suspend fun streamEnded(p: Playable.Vod): Boolean {
        val ch = runCatching { KickApi.channel(p.channel.slug) }.getOrNull() ?: return false
        val live = ch.live ?: return true
        return p.video.livestreamId > 0 && live.id != p.video.livestreamId
    }

    /** Waits (up to ~10 min) for Kick to list the finished VOD, then continues in it. */
    private suspend fun switchToFinishedVod(p: Playable.Vod) {
        Log.i(tag, "Stream ended; looking for the finished VOD")
        repeat(20) { attempt ->
            if (current.value != p) return
            val ch = runCatching { KickApi.channel(p.channel.slug) }.getOrNull()
            val vod = runCatching { KickApi.videos(p.channel.slug) }.getOrNull()
                ?.firstOrNull { it.livestreamId == p.video.livestreamId && !it.isLive }
            if (ch != null && vod != null) {
                if (current.value != p) return
                val pos = player.currentPosition.coerceAtLeast(0)
                val wasPlaying = player.playWhenReady
                notice.value = "Stream ended, continuing in the VOD"
                play(Playable.Vod(ch.copy(live = null), vod), startAtMs = pos)
                if (!wasPlaying) player.pause()
                return
            }
            if (attempt == 0) notice.value = "Stream ended, waiting for Kick to publish the VOD…"
            delay(30_000)
        }
        if (current.value == p) error.value = "The stream ended and Kick hasn't published its VOD yet"
    }

    private fun initLoops() {
        // While watching a live VOD: check now and then whether the stream has ended, and right
        // away if playback stalls.
        scope.launch {
            var lastCheck = System.currentTimeMillis()
            var stalledSince = 0L
            while (isActive) {
                delay(5_000)
                val p = current.value as? Playable.Vod
                if (p == null || !p.growing || loading.value) {
                    stalledSince = 0L
                    continue
                }
                val now = System.currentTimeMillis()
                val stalled = player.playWhenReady && player.playbackState == Player.STATE_BUFFERING
                stalledSince = if (stalled) (stalledSince.takeIf { it > 0 } ?: now) else 0L
                val due = now - lastCheck > 90_000 || (stalledSince > 0 && now - stalledSince > 15_000)
                if (due && endJob?.isActive != true) {
                    lastCheck = now
                    stalledSince = 0L
                    endJob = scope.launch { if (streamEnded(p)) switchToFinishedVod(p) }
                }
            }
        }
        // Catching up to live at >1x: drop to 1x right before the live point instead of stalling.
        scope.launch {
            while (isActive) {
                delay(250)
                val p = current.value as? Playable.Vod
                if (p == null || !p.growing || player.duration <= 0) {
                    atLive.value = false
                    continue
                }
                trackGrowth(p)
                val remaining = player.duration - player.currentPosition
                val edge = liveEdgeOffsetMs()
                if (player.playbackParameters.speed > 1f && remaining <= edge) {
                    // Caught up at >1x: back to 1x, and you're live.
                    autoSpeed = true
                    player.setPlaybackSpeed(1f)
                    atLive.value = true
                    notice.value = "Caught up to live, playing at 1x"
                } else if (atLive.value && liveLagMs(player.currentPosition) > liveLagLimitMs() + 10_000) {
                    // Fell well behind (e.g. a long buffer): no longer live.
                    atLive.value = false
                }
            }
        }
        // Save resume positions periodically.
        scope.launch {
            while (isActive) {
                delay(10_000)
                saveProgress()
            }
        }
    }

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
                    playChannel(ch)
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
    /**
     * Watch a live channel. By default this plays the stream's VOD at the live point, so you can
     * rewind anywhere; "Go live" jumps back. Settings can switch to the plain (lower latency)
     * live stream instead.
     */
    fun playChannel(ch: Channel) {
        val dvr = if (store.rewindableLive) ch.liveVideo() else null
        if (dvr == null) {
            play(Playable.Live(ch))
            return
        }
        // Watched this stream before? Pick up where you left off, unless you were at LIVE then.
        val saved = store.positionFor(dvr)
        if (saved > 0 && !store.wasLive(dvr.progressKey)) {
            play(Playable.Vod(ch, dvr, dvr = true), startAtMs = saved)
        } else {
            play(Playable.Vod(ch, dvr, dvr = true, startAtLive = true))
        }
    }

    /** Jump to the newest part of a live VOD (no reload). */
    fun goLive() {
        val p = current.value as? Playable.Vod ?: return
        if (!p.growing) return
        player.seekTo((player.duration - liveEdgeOffsetMs()).coerceAtLeast(0))
        atLive.value = true
        if (player.playbackParameters.speed > 1f) {
            autoSpeed = true
            player.setPlaybackSpeed(1f)
        }
        player.play()
    }

    /** Restart chat for the current video (the refresh button; unsticks a stalled chat). */
    fun reloadChat() {
        chat.value?.stop()
        chat.value = null
        chatKey = null
        syncChat()
    }

    fun play(p: Playable, startAtMs: Long? = null) {
        if (p != current.value) snapshotMode = false
        atLive.value = false
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
                        val v = src.channel.liveVideo()
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
                var p = p
                var dvrUrl: String? = null
                val url: String
                val metaTitle: String
                // Live channel opened at its live point: if Kick has no VOD for it yet, play the
                // plain live stream instead.
                if (p is Playable.Vod && p.dvr && p.startAtLive) {
                    dvrUrl = KickApi.liveDvrUrl(p.channel)
                    if (dvrUrl == null && p.channel.live?.playbackUrl != null) {
                        p = Playable.Live(p.channel)
                        current.value = p
                        syncChat()
                    }
                }
                when (p) {
                    is Playable.Live -> {
                        url = p.channel.live?.playbackUrl
                            ?: KickApi.channel(p.channel.slug).live?.playbackUrl
                            ?: throw IllegalStateException("${p.channel.username} is offline")
                        metaTitle = p.channel.live?.title ?: p.channel.username
                    }
                    is Playable.Vod -> {
                        url = if (p.dvr) {
                            (dvrUrl ?: KickApi.liveDvrUrl(p.channel))
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
                stripEndList = p is Playable.Vod && p.growing && !snapshotMode
                fun source(item: MediaItem) = if (currentIsHls) hlsFactory.createMediaSource(item) else null
                if (p is Playable.ClipItem) {
                    val item = builder.build()
                    source(item)?.let { player.setMediaSource(it, startPositionMs ?: 0L) }
                        ?: player.setMediaItem(item, startPositionMs ?: 0L)
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
                    val src = hlsFactory.createMediaSource(builder.build())
                    if (p.startAtLive && startPositionMs == null) {
                        // Default position of a growing playlist is ~3 segments back; once the
                        // playlist is loaded, move to our closer live point.
                        player.setMediaSource(src)
                        player.setPlaybackSpeed(1f)
                        val target = p
                        scope.launch {
                            if (awaitDuration() > 0 && current.value == target) {
                                if (player.duration - player.currentPosition > liveEdgeOffsetMs() + 2_000) goLive()
                                else atLive.value = true
                            }
                        }
                    } else {
                        player.setMediaSource(src, startPositionMs ?: resumePosition(p))
                        player.setPlaybackSpeed(store.vodSpeed)
                    }
                } else {
                    player.setMediaSource(hlsFactory.createMediaSource(builder.build()))
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
        val saved = store.positionFor(p.video)
        val dur = p.video.durationMs
        return if (dur > 0 && saved > dur - 15_000) 0L else saved
    }

    private fun progressKey(p: Playable.Vod) = p.video.progressKey

    fun saveProgress() {
        val p = current.value as? Playable.Vod ?: return
        if (player.mediaItemCount == 0) return
        store.savePosition(progressKey(p), player.currentPosition.coerceAtLeast(0))
        if (p.growing) store.saveWasLive(progressKey(p), atLive.value)
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
        if (!p.growing) return
        // A growing playlist never "ends" while live; this is only reached in reload mode
        // (or once the stream is over).
        // If Kick handed us a fixed snapshot of a VOD that is still growing, fetch the newer
        // playlist and carry on from the same spot.
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val wait = 15_000 - (System.currentTimeMillis() - lastRefreshAt)
            if (wait > 0) delay(wait)
            // Stream over? Then continue in (or finish on) the published VOD.
            if (streamEnded(p)) switchToFinishedVod(p) else reload(keepPosition = true)
        }
    }

    fun setSpeed(s: Float) {
        // Saved as your VOD speed by the playback-parameters listener.
        player.setPlaybackSpeed(s)
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
        DefaultRenderersFactory(context).setEnableDecoderFallback(true),
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


/**
 * Wraps Media3's HLS playlist parser. While [strip] is true, media playlists lose their
 * "#EXT-X-ENDLIST" (and VOD type becomes EVENT), so a still-growing VOD is played as a growing
 * playlist: refreshed in the background, seekable from the start.
 */
private class GrowingPlaylistParserFactory(private val strip: () -> Boolean) : HlsPlaylistParserFactory {
    private val base = DefaultHlsPlaylistParserFactory()

    override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> =
        wrap(base.createPlaylistParser())

    override fun createPlaylistParser(
        multivariantPlaylist: HlsMultivariantPlaylist,
        previousMediaPlaylist: HlsMediaPlaylist?,
    ): ParsingLoadable.Parser<HlsPlaylist> =
        wrap(base.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist))

    private fun wrap(parser: ParsingLoadable.Parser<HlsPlaylist>) = ParsingLoadable.Parser<HlsPlaylist> { uri, input ->
        if (!strip()) {
            parser.parse(uri, input)
        } else {
            val text = input.readBytes().toString(Charsets.UTF_8)
            if (!text.contains("#EXTINF")) {
                parser.parse(uri, text.byteInputStream())
            } else {
                val fixed = text.lineSequence()
                    .filterNot { it.trim() == "#EXT-X-ENDLIST" }
                    .map { if (it.trim().startsWith("#EXT-X-PLAYLIST-TYPE:VOD")) "#EXT-X-PLAYLIST-TYPE:EVENT" else it }
                    .joinToString("\n")
                parser.parse(uri, fixed.byteInputStream())
            }
        }
    }
}
