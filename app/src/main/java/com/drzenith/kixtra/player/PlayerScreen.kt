package com.drzenith.kixtra.player

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import com.drzenith.kixtra.chat.ChatSource
import com.drzenith.kixtra.chat.ChatView
import com.drzenith.kixtra.chat.LiveChat
import com.drzenith.kixtra.chat.ReplayChat
import com.drzenith.kixtra.chat.parseKickTime
import com.drzenith.kixtra.ui.Actions
import com.drzenith.kixtra.ui.Avatar
import com.drzenith.kixtra.ui.Badge
import com.drzenith.kixtra.ui.AccentCyan
import com.drzenith.kixtra.ui.AccentViolet
import com.drzenith.kixtra.ui.LiveRed
import com.drzenith.kixtra.ui.compact
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Wide, tall-enough windows (tablets, desktop/Waydroid) get the side-by-side player layout. */
fun isWideWindow(cfg: Configuration): Boolean = cfg.screenWidthDp >= 840 && cfg.screenHeightDp >= 480

private val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 3.5f, 4f)

fun speedLabel(s: Float): String = if (s % 1f == 0f) "${s.toInt()}x" else "${s}x"

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun PlayerScreen(actions: Actions) {
    val pc = actions.player
    val current by pc.current.collectAsState()
    val loading by pc.loading.collectAsState()
    val error by pc.error.collectAsState()
    val context = LocalContext.current
    val activity = context.findActivity()
    val cfg = LocalConfiguration.current
    val landscape = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Tablets / desktop-sized windows (e.g. Waydroid): video + chat side by side instead of fullscreen.
    val wide = isWideWindow(cfg)
    val scope = rememberCoroutineScope()
    var chatInLandscape by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var forceFullscreen by remember { mutableStateOf(false) }
    val menuOpen = remember { mutableStateOf(false) }
    val fullscreen = (landscape && !wide) || forceFullscreen
    val notice by pc.notice.collectAsState()
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(6000)
            pc.notice.value = null
        }
    }
    BackHandler(enabled = forceFullscreen) { forceFullscreen = false }

    // Ask once for notification permission so background playback shows its controls.
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && !actions.store.askedNotifications &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            actions.store.askedNotifications = true
            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Immersive in landscape; restore orientation + bars when leaving the player.
    DisposableEffect(fullscreen) {
        val window = activity?.window
        if (window != null) {
            val ctl = WindowCompat.getInsetsController(window, window.decorView)
            if (fullscreen) {
                ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                ctl.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                ctl.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { }
    }
    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.let { w ->
                WindowCompat.getInsetsController(w, w.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    fun setFullscreen(full: Boolean) {
        if (wide) {
            forceFullscreen = full
            return
        }
        val a = activity ?: return
        if (full) {
            a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            scope.launch {
                delay(2500)
                a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    // Chat: live chat for live streams, synced replay for VODs.
    val chat: ChatSource? = remember(current, actions.store.chatEnabled) {
        val p = current
        if (!actions.store.chatEnabled || p == null || p.channel.id == 0L) null
        else when (p) {
            is Playable.Live -> p.channel.chatroomId?.let { LiveChat(p.channel.id, it, scope) }
            is Playable.Vod -> parseKickTime(p.video.startTime)?.let { start ->
                ReplayChat(p.channel.id, start, scope) { pc.player.currentPosition }
            }
            is Playable.ClipItem -> null
        }
    }
    DisposableEffect(chat) {
        chat?.start()
        onDispose { chat?.stop() }
    }

    val video: @Composable (Modifier) -> Unit = { m ->
        Box(m.background(Color.Black).then(if (fullscreen) Modifier else Modifier.swipeDown { actions.minimizePlayer() })) {
            VideoBox(pc.player, Modifier.fillMaxSize(), true, fullscreen, ::setFullscreen) { controlsVisible = it }
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                error != null -> Column(Modifier.align(Alignment.Center).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error ?: "", color = Color.White)
                    Spacer(Modifier.padding(4.dp))
                    Button(onClick = { pc.reload(keepPosition = true) }) { Text("Retry") }
                }
            }
            notice?.let { msg ->
                Text(
                    msg,
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp)
                        .background(Color(0xCC000000)).padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            // Player overlay controls: shown with the player's own controls (or while a menu is open).
            if (controlsVisible || menuOpen.value) {
                Box(Modifier.align(Alignment.TopStart).padding(8.dp)) { ContextAction(actions) }
                Row(
                    Modifier.align(Alignment.TopEnd).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    QualitySpeedButtons(actions) { menuOpen.value = it }
                    Pill("↻") { pc.reload(keepPosition = true) }
                    if (fullscreen && chat != null) {
                        Pill(if (chatInLandscape) "Hide chat" else "Chat") { chatInLandscape = !chatInLandscape }
                    }
                }
            }
        }
    }

    val chatMessages = chat?.messages?.collectAsState()
    val chatStatus = chat?.status?.collectAsState()

    if (fullscreen) {
        Row(Modifier.fillMaxSize().background(Color.Black)) {
            video(Modifier.weight(1f).fillMaxHeight())
            if (chat != null && chatInLandscape) {
                ChatView(chatMessages?.value ?: emptyList(), chatStatus?.value ?: "", Modifier.width(300.dp).fillMaxHeight())
            }
        }
    } else if (wide) {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                video(Modifier.fillMaxWidth().weight(1f))
                InfoBar(actions)
            }
            if (chat != null) {
                ChatView(chatMessages?.value ?: emptyList(), chatStatus?.value ?: "", Modifier.width(340.dp).fillMaxHeight())
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            video(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            InfoBar(actions)
            if (chat != null) {
                ChatView(chatMessages?.value ?: emptyList(), chatStatus?.value ?: "", Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

@Composable
internal fun VideoBox(
    player: Player,
    modifier: Modifier,
    useController: Boolean,
    fullscreen: Boolean,
    onFullscreen: (Boolean) -> Unit,
    onControls: (Boolean) -> Unit,
) {
    val fs by rememberUpdatedState(onFullscreen)
    val ctl by rememberUpdatedState(onControls)
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                keepScreenOn = true
                setShowNextButton(false)
                setShowPreviousButton(false)
                setShowSubtitleButton(false)
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                setFullscreenButtonClickListener { isFull -> fs(isFull) }
                // Media3's settings cog duplicates our speed/quality menus (and caps speed at 2x).
                findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.visibility = View.GONE
                // Brand-colored seek bar.
                findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.apply {
                    setPlayedColor(AccentViolet.toArgb())
                    setScrubberColor(AccentCyan.toArgb())
                    setBufferedColor(0x66FFFFFF)
                }
                setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> ctl(v == View.VISIBLE) })
            }
        },
        update = { v ->
            v.useController = useController
            v.setFullscreenButtonState(fullscreen)
        },
        onRelease = { it.player = null },
    )
}

@Composable
private fun InfoBar(actions: Actions) {
    val pc = actions.player
    val current by pc.current.collectAsState()
    val p = current ?: return
    val ch = p.channel
    val title = when (p) {
        is Playable.Live -> ch.live?.title ?: ""
        is Playable.Vod -> p.video.title
        is Playable.ClipItem -> p.clip.title
    }
    Column(Modifier.fillMaxWidth().swipeDown { actions.minimizePlayer() }.padding(start = 2.dp, end = 10.dp, top = 4.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { actions.minimizePlayer() }) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Minimize player")
            }
            Box(Modifier.clickable { actions.openChannel(ch.slug) }) { Avatar(ch.avatar, 36) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ch.username, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                        modifier = Modifier.clickable { actions.openChannel(ch.slug) })
                    Spacer(Modifier.width(8.dp))
                    when {
                        p is Playable.Live -> Badge("LIVE • ${compact(ch.live?.viewers ?: 0)}", LiveRed)
                        p is Playable.Vod && p.dvr -> Badge("FROM START", AccentCyan, Color(0xFF032B33))
                        p is Playable.Vod && p.video.isLive -> Badge("LIVE VOD", AccentCyan, Color(0xFF032B33))
                        p is Playable.ClipItem -> Badge("CLIP", Color(0xFF3A3D41))
                    }
                }
            }
        }
    }
}

/** Compact quality + speed menus for the player overlay. */
@Composable
private fun QualitySpeedButtons(actions: Actions, onMenuOpen: (Boolean) -> Unit) {
    val pc = actions.player
    val current by pc.current.collectAsState()
    val qualities by pc.qualities.collectAsState()
    val selectedQ by pc.selectedQuality.collectAsState()
    val speed by pc.speed.collectAsState()
    val p = current ?: return

    val qOptions = buildList<Pair<String, () -> Unit>> {
        add("Auto" to { pc.selectAuto() })
        qualities.forEach { q -> add(q.label to { pc.selectQuality(q) }) }
        add("Audio only" to { pc.selectAudioOnly() })
    }
    MenuButton(selectedQ, qOptions, selectedQ, onMenuOpen)
    if (p.seekable) {
        MenuButton(speedLabel(speed), SPEEDS.map { s -> speedLabel(s) to { pc.setSpeed(s) } }, speedLabel(speed), onMenuOpen)
    }
}

/** The one context-dependent jump: live -> from start, from start -> live, clip -> full VOD. */
@Composable
private fun ContextAction(actions: Actions) {
    val current by actions.player.current.collectAsState()
    val p = current ?: return
    when {
        p is Playable.Live && p.channel.live != null -> Pill("⏮ From start") { actions.watchFromStart(p.channel) }
        p is Playable.Vod && p.dvr -> Pill("● Go live") { actions.player.playLive(p.channel.slug) }
        p is Playable.ClipItem -> Pill("▶ Continue in VOD") { actions.continueClipInVod(p.clip) }
    }
}

/** Small translucent button that sits on top of the video. */
@Composable
private fun Pill(label: String, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color.White,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0x99000000))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun MenuButton(
    label: String,
    options: List<Pair<String, () -> Unit>>,
    selected: String?,
    onOpenChange: (Boolean) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    fun setOpen(v: Boolean) {
        open = v
        onOpenChange(v)
    }
    Box {
        Pill("$label ▾") { setOpen(true) }
        DropdownMenu(expanded = open, onDismissRequest = { setOpen(false) }) {
            options.forEach { (text, action) ->
                DropdownMenuItem(
                    text = { Text(if (text == selected) "✓  $text" else "    $text") },
                    onClick = { setOpen(false); action() },
                )
            }
        }
    }
}


/** Fires [onSwipe] on a clear downward swipe; other touches still reach the player. */
fun Modifier.swipeDown(onSwipe: () -> Unit): Modifier = pointerInput(Unit) {
    val threshold = 72.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var dx = 0f
        var dy = 0f
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            val delta = change.positionChange()
            dx += delta.x
            dy += delta.y
            if (dy > threshold && dy > 2 * abs(dx)) {
                change.consume()
                onSwipe()
                break
            }
        }
    }
}

/** Video only, for picture-in-picture. */
@Composable
fun PipVideo(actions: Actions) {
    VideoBox(actions.player.player, Modifier.fillMaxSize(), useController = false, fullscreen = true, onFullscreen = {}, onControls = {})
}

/**
 * Floating mini player shown while browsing. Tap to expand, swipe sideways to close.
 */
@Composable
fun MiniPlayer(actions: Actions, modifier: Modifier = Modifier) {
    val pc = actions.player
    val loading by pc.loading.collectAsState()
    val error by pc.error.collectAsState()
    val playing by pc.isPlaying.collectAsState()
    var dragX by remember { mutableFloatStateOf(0f) }
    val widthPx = with(LocalDensity.current) { 200.dp.toPx() }

    Box(
        modifier
            .offset { IntOffset(dragX.roundToInt(), 0) }
            .graphicsLayer { alpha = 1f - (abs(dragX) / widthPx).coerceIn(0f, 0.8f) }
            .width(200.dp)
            .aspectRatio(16f / 9f)
            .shadow(10.dp, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black)
    ) {
        VideoBox(pc.player, Modifier.fillMaxSize(), useController = false, fullscreen = false, onFullscreen = {}, onControls = {})
        // Gesture layer on top of the video.
        Box(
            Modifier.matchParentSize()
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (abs(dragX) > widthPx * 0.4f) actions.closePlayer()
                            dragX = 0f
                        },
                        onDragCancel = { dragX = 0f },
                    ) { change, amount ->
                        change.consume()
                        dragX += amount
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onTap = { actions.expandPlayer() }) }
        ) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), strokeWidth = 3.dp)
                error != null -> Text(
                    "Playback error",
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            Text(
                if (playing) "❚❚" else "▶",
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier.align(Alignment.BottomStart)
                    .clickable { if (pc.player.isPlaying) pc.player.pause() else pc.player.play() }
                    .background(Color(0x88000000), RoundedCornerShape(topEnd = 8.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
            Icon(
                Icons.Filled.Close,
                contentDescription = "Close player",
                tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd)
                    .clickable { actions.closePlayer() }
                    .background(Color(0x88000000), RoundedCornerShape(bottomStart = 8.dp))
                    .padding(4.dp)
                    .size(18.dp),
            )
        }
    }
}
