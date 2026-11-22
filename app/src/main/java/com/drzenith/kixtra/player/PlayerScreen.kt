package com.drzenith.kixtra.player

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import android.widget.ImageButton
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import com.drzenith.kixtra.R
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar
import com.drzenith.kixtra.chat.ChatSource
import com.drzenith.kixtra.chat.ChatView
import com.drzenith.kixtra.chat.LiveChat
import com.drzenith.kixtra.chat.ReplayChat
import com.drzenith.kixtra.chat.parseKickTime
import com.drzenith.kixtra.ui.AccentCyan
import com.drzenith.kixtra.ui.AccentViolet
import com.drzenith.kixtra.ui.Actions
import com.drzenith.kixtra.ui.Avatar
import com.drzenith.kixtra.ui.Badge
import com.drzenith.kixtra.ui.LiveRed
import com.drzenith.kixtra.ui.compact
import com.drzenith.kixtra.ui.hms
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Wide, tall-enough windows (tablets, desktop/Waydroid) get the side-by-side player layout. */
fun isWideWindow(cfg: Configuration): Boolean = cfg.screenWidthDp >= 840 && cfg.screenHeightDp >= 480

/** Width of the floating mini player (16:9). */
val MINI_WIDTH = 200.dp

private val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 3.5f, 4f)

fun speedLabel(s: Float): String = if (s % 1f == 0f) "${s.toInt()}x" else "${s}x"

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Quality / speed / refresh buttons that live in Media3's own control bar, next to fullscreen. */
class BarSpec(
    val quality: String,
    val speed: String?,
    val onQuality: (View) -> Unit,
    val onSpeed: (View) -> Unit,
    val onRefresh: () -> Unit,
    /** null = hidden; otherwise whether the side chat / pop-out chat is on. */
    val sideChat: Boolean? = null,
    val overlayChat: Boolean? = null,
    val onSideChat: () -> Unit = {},
    val onOverlayChat: () -> Unit = {},
    /** Show "● LIVE" instead of the time readout. */
    val atLive: Boolean = false,
)

private class BarButtons(
    val liveLabel: TextView?,
    val quality: TextView,
    val speed: TextView,
    val refresh: TextView,
    val sideChat: ImageButton,
    val overlayChat: ImageButton,
)

/** Native popup menu anchored to a control-bar button. */
private fun showMenu(anchor: View, options: List<Pair<String, () -> Unit>>, selected: String) {
    val menu = PopupMenu(anchor.context, anchor)
    options.forEachIndexed { i, (label, _) ->
        menu.menu.add(0, i, i, if (label == selected) "✓  $label" else label)
    }
    menu.setOnMenuItemClickListener { item ->
        options.getOrNull(item.itemId)?.second?.invoke()
        true
    }
    menu.show()
}

@Composable
fun PlayerScreen(actions: Actions) {
    val pc = actions.player
    val current by pc.current.collectAsState()
    val loading by pc.loading.collectAsState()
    val error by pc.error.collectAsState()
    val qualities by pc.qualities.collectAsState()
    val selectedQ by pc.selectedQuality.collectAsState()
    val speed by pc.speed.collectAsState()
    val atLive by pc.atLive.collectAsState()
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
    var scrubPos by remember { mutableStateOf<Long?>(null) }
    val fullscreen = (landscape && !wide) || forceFullscreen
    val notice by pc.notice.collectAsState()
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(6000)
            pc.notice.value = null
        }
    }
    BackHandler(enabled = forceFullscreen) { forceFullscreen = false }
    // Drag-to-minimize only outside fullscreen.
    SideEffect { actions.sheet.enabled = !fullscreen }

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

    // Immersive in fullscreen; restore orientation + bars when leaving the player.
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

    // Chat belongs to the current video (kept across minimize/expand); see PlayerController.chat.
    val chat by pc.chat.collectAsState()
    var overlayOn by remember { mutableStateOf(false) }

    // Control-bar buttons.
    val seekable = current?.seekable == true
    val qOptions = buildList<Pair<String, () -> Unit>> {
        add("Auto" to { pc.selectAuto() })
        qualities.forEach { q -> add(q.label to { pc.selectQuality(q) }) }
        add("Audio only" to { pc.selectAudioOnly() })
    }
    val bar = BarSpec(
        quality = if (selectedQ == "Audio only") "Audio" else selectedQ,
        speed = if (seekable) speedLabel(speed) else null,
        onQuality = { v -> showMenu(v, qOptions, selectedQ) },
        onSpeed = { v -> showMenu(v, SPEEDS.map { s -> speedLabel(s) to { pc.setSpeed(s) } }, speedLabel(speed)) },
        onRefresh = {
            pc.reload(keepPosition = true)
            pc.reloadChat()
        },
        sideChat = if (fullscreen && chat != null) chatInLandscape else null,
        overlayChat = if (fullscreen && chat != null) overlayOn else null,
        onSideChat = { chatInLandscape = !chatInLandscape },
        atLive = atLive,
        onOverlayChat = { overlayOn = !overlayOn },
    )
    val onScrub: (Long?) -> Unit = { pos ->
        if (seekable) {
            scrubPos = pos
            if (pos != null) pc.previewScrub(pos) else pc.scrubEnded()
        }
    }

    val sheet = actions.sheet
    // Everything except the video fades while the player is dragged/animated into the mini player.
    val fadeWithSheet = Modifier.graphicsLayer { alpha = 1f - sheet.fraction }

    val video: @Composable (Modifier) -> Unit = { m ->
        Box(m.background(Color.Black).onGloballyPositioned { sheet.videoCoords = it }) {
            // Hidden surface for the background preview player. Nearly fully transparent: it still
            // has to be drawn for Android to give it a surface, and frames are copied out of its
            // buffer (unaffected by the view's alpha).
            if (seekable) {
                AndroidView(
                    factory = { ctx -> TextureView(ctx).also { it.alpha = 0.01f; pc.attachPreview(it) } },
                    modifier = Modifier.align(Alignment.Center).size(32.dp, 18.dp),
                    onRelease = { pc.detachPreview(it) },
                )
            }
            VideoBox(
                pc.player, Modifier.fillMaxSize(), useController = true, fullscreen = fullscreen,
                onFullscreen = ::setFullscreen, onControls = { controlsVisible = it }, bar = bar, onScrub = onScrub,
                // With chat beside the video, sit the picture against the chat (no black gap).
                alignEnd = chat != null && ((fullscreen && chatInLandscape) || (!fullscreen && wide)),
            )
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
            scrubPos?.let { pos -> ScrubPreview(pc, pos, pc.player.duration) }
            val c = chat
            if (fullscreen && overlayOn && c != null) {
                OverlayChat(c, actions) { overlayOn = false }
            }
            if (controlsVisible) {
                Box(Modifier.align(Alignment.TopStart).padding(8.dp)) { ContextAction(actions) }
            }
        }
    }

    // Side chat width (fullscreen + wide layouts), adjustable by dragging the divider.
    var chatWidth by remember { mutableStateOf(actions.store.chatWidthDp.dp) }
    // Width actually shown (can be larger than chatWidth when it fills spare space); drags start here.
    val shownChatWidth = remember { floatArrayOf(0f) }
    val maxChatWidth = (cfg.screenWidthDp * 0.7f).dp
    val sideChat: @Composable () -> Unit = {
        ChatResizeHandle(
            onDrag = { deltaDp ->
                val from = maxOf(chatWidth, shownChatWidth[0].dp)
                chatWidth = (from - deltaDp).coerceIn(150.dp, maxChatWidth.coerceAtLeast(150.dp))
            },
            onDragEnd = { actions.store.chatWidthDp = chatWidth.value },
        )
    }

    val chatMessages = chat?.messages?.collectAsState()
    val chatStatus = chat?.status?.collectAsState()
    val zone = Modifier.onGloballyPositioned { actions.sheet.zoneCoords = it }

    val aspect by pc.videoAspect.collectAsState()

    if (fullscreen) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
        // Chat takes any width the video can't use (no black bars), or the width you dragged it
        // to if that's wider.
        val fill = maxWidth - maxHeight * aspect
        val sideWidth = maxOf(chatWidth, fill).coerceAtMost(maxWidth * 0.7f)
        shownChatWidth[0] = sideWidth.value
        Row(Modifier.fillMaxSize().background(Color.Black)) {
            video(Modifier.weight(1f).fillMaxHeight())
            if (chat != null && chatInLandscape) {
                Box(Modifier.width(sideWidth).fillMaxHeight()) {
                    ChatView(chatMessages?.value ?: emptyList(), chatStatus?.value ?: "", Modifier.fillMaxSize())
                    Box(Modifier.align(Alignment.CenterStart)) { sideChat() }
                }
            }
        }
        }
    } else if (wide) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
        // Same idea; the title bar under the video takes roughly 64dp of height.
        val fill = maxWidth - (maxHeight - 64.dp) * aspect
        val sideWidth = maxOf(chatWidth, fill).coerceAtMost(maxWidth * 0.7f)
        shownChatWidth[0] = sideWidth.value
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight().then(zone)) {
                video(Modifier.fillMaxWidth().weight(1f))
                Box(fadeWithSheet) { InfoBar(actions) }
            }
            if (chat != null) {
                Box(Modifier.width(sideWidth).fillMaxHeight().then(fadeWithSheet)) {
                    ChatView(chatMessages?.value ?: emptyList(), chatStatus?.value ?: "", Modifier.fillMaxSize())
                    Box(Modifier.align(Alignment.CenterStart)) { sideChat() }
                }
            }
        }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().then(zone)) {
                video(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                Box(fadeWithSheet) { InfoBar(actions) }
            }
            if (chat != null) {
                ChatView(chatMessages?.value ?: emptyList(), chatStatus?.value ?: "", Modifier.fillMaxWidth().weight(1f).then(fadeWithSheet))
            }
        }
    }
}

/** Invisible drag strip on the side chat's left edge; drag left/right to resize the chat. */
@Composable
private fun ChatResizeHandle(onDrag: (androidx.compose.ui.unit.Dp) -> Unit, onDragEnd: () -> Unit) {
    val density = LocalDensity.current
    Box(
        Modifier.fillMaxHeight().width(14.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(onDragEnd = onDragEnd, onDragCancel = onDragEnd) { change, amount ->
                    change.consume()
                    onDrag(with(density) { amount.toDp() })
                }
            }
    )
}

/** Small frame preview + timestamp that follows the scrub position along the seek bar. */
@Composable
private fun ScrubPreview(pc: PlayerController, posMs: Long, durationMs: Long) {
    val track by pc.thumbTrack.collectAsState()
    val frameVersion by pc.frameVersion.collectAsState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val frac = if (durationMs > 0) (posMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        val w = 160.dp
        val h = 90.dp
        val x = (maxWidth - w) * frac
        val y = (maxHeight - h - 78.dp).coerceAtLeast(4.dp)
        Column(
            Modifier.offset(x = x, y = y).width(w),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(w, h).clip(RoundedCornerShape(6.dp)).background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                val t = track
                if (t != null) {
                    // Preloaded IVS thumbnail (snapped to the nearest preloaded one).
                    AsyncImage(
                        model = t.url(t.indexFor(posMs)),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    // Exact checkpoint frame if ready, else the nearest one we have.
                    val bmp = remember(frameVersion, posMs) { pc.frameFor(pc.checkpointFor(posMs)) }
                    if (bmp != null) {
                        Image(
                            bmp.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
                    }
                }
            }
            Text(
                hms(posMs),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 2.dp).background(Color(0x99000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}

private fun barButton(ctx: Context, text: String, sizeSp: Float = 13f): TextView = TextView(ctx).apply {
    val density = resources.displayMetrics.density
    this.text = text
    setTextColor(android.graphics.Color.WHITE)
    textSize = sizeSp
    typeface = Typeface.DEFAULT_BOLD
    gravity = Gravity.CENTER
    val pad = (8 * density).toInt()
    setPadding(pad, 0, pad, 0)
    minWidth = (40 * density).toInt()
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (48 * density).toInt()).apply {
        leftMargin = (2 * density).toInt()
        rightMargin = (2 * density).toInt()
        gravity = Gravity.CENTER_VERTICAL
    }
    val tv = TypedValue()
    if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)) {
        setBackgroundResource(tv.resourceId)
    }
    isClickable = true
    isFocusable = true
}

private fun barIcon(ctx: Context, res: Int, label: String): ImageButton = ImageButton(ctx).apply {
    // Same box as Media3's own bottom-bar buttons (ExoStyledControls.Button.Bottom):
    // 48x48dp, 12dp padding (24dp icon area), 2dp side margins, icon scaled to fit.
    val density = resources.displayMetrics.density
    fun dp(v: Int) = (v * density).toInt()
    setImageResource(res)
    contentDescription = label
    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
    setPadding(dp(12), dp(12), dp(12), dp(12))
    layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
        leftMargin = dp(2)
        rightMargin = dp(2)
        gravity = Gravity.CENTER_VERTICAL
    }
    val tv = TypedValue()
    if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)) {
        setBackgroundResource(tv.resourceId)
    } else {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }
}

/**
 * Pop-out chat floating over the video: drag the top bar to move it, drag the corner to resize,
 * tap "BG" to cycle the background (none / light / dark). Position and size are remembered.
 */
@Composable
private fun OverlayChat(chat: ChatSource, actions: Actions, onClose: () -> Unit) {
    val store = actions.store
    val sheet = actions.sheet
    val messages by chat.messages.collectAsState()
    DisposableEffect(Unit) { onDispose { sheet.excludeCoords = null } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxW = maxWidth
        val maxH = maxHeight
        var w by remember { mutableStateOf(store.overlayW.dp) }
        var h by remember { mutableStateOf(store.overlayH.dp) }
        var fx by remember { mutableFloatStateOf(store.overlayX) }
        var fy by remember { mutableFloatStateOf(store.overlayY) }
        var bg by remember { mutableIntStateOf(store.overlayBg) }
        val ww = w.coerceIn(140.dp, maxW)
        val hh = h.coerceIn(100.dp, maxH)
        val size by rememberUpdatedState(Pair(ww, hh))
        val bgColor = when (bg) {
            0 -> Color.Transparent
            1 -> Color(0x55000000)
            else -> Color(0xAA000000)
        }
        // Controls (move bar, BG, close, resize corner) appear on tap and fade after 3s idle.
        var controls by remember { mutableStateOf(false) }
        var touchedAt by remember { mutableLongStateOf(0L) }
        fun touched() {
            controls = true
            touchedAt = System.currentTimeMillis()
        }
        LaunchedEffect(touchedAt) {
            if (touchedAt > 0) {
                delay(3_000)
                controls = false
            }
        }
        Box(
            Modifier
                .offset(x = (maxW - ww) * fx, y = (maxH - hh) * fy)
                .size(ww, hh)
                .clip(RoundedCornerShape(10.dp))
                .background(bgColor)
                .onGloballyPositioned { sheet.excludeCoords = it }
                // Any touch on the overlay reveals the controls (without stealing chat scrolling).
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        touched()
                    }
                }
        ) {
            ChatView(messages, "", Modifier.fillMaxSize(), overlay = true, showStatus = false)
            AnimatedVisibility(
                visible = controls,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                // Move bar.
                Row(
                    Modifier.fillMaxWidth().height(28.dp).background(Color(0x99000000))
                        .pointerInput(maxW, maxH) {
                            detectDragGestures(onDragEnd = { store.overlayX = fx; store.overlayY = fy }) { change, drag ->
                                change.consume()
                                touched()
                                val freeW = (maxW - size.first).toPx().coerceAtLeast(1f)
                                val freeH = (maxH - size.second).toPx().coerceAtLeast(1f)
                                fx = (fx + drag.x / freeW).coerceIn(0f, 1f)
                                fy = (fy + drag.y / freeH).coerceIn(0f, 1f)
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("⠿  Move", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f).padding(start = 8.dp))
                    Text(
                        "BG",
                        color = Color.White.copy(alpha = if (bg == 0) 0.5f else 1f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { touched(); bg = (bg + 1) % 3; store.overlayBg = bg }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    Icon(
                        Icons.Filled.Close, contentDescription = "Close pop-out chat", tint = Color.White,
                        modifier = Modifier.clickable(onClick = onClose).padding(4.dp).size(18.dp),
                    )
                }
            }
            AnimatedVisibility(
                visible = controls,
                modifier = Modifier.align(Alignment.BottomEnd),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                // Corner handle: drag to resize.
                Box(
                    Modifier.size(30.dp).background(Color(0x99000000), RoundedCornerShape(topStart = 8.dp))
                        .pointerInput(maxW, maxH) {
                            detectDragGestures(onDragEnd = { store.overlayW = w.value; store.overlayH = h.value }) { change, drag ->
                                change.consume()
                                touched()
                                w = (w + drag.x.toDp()).coerceIn(140.dp, maxW)
                                h = (h + drag.y.toDp()).coerceIn(100.dp, maxH)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("◢", color = Color.White, fontSize = 14.sp)
                }
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
    bar: BarSpec? = null,
    onScrub: ((Long?) -> Unit)? = null,
    textureView: Boolean = true,
    alignEnd: Boolean = false,
) {
    val fs by rememberUpdatedState(onFullscreen)
    val ctl by rememberUpdatedState(onControls)
    val barRef = rememberUpdatedState(bar)
    val scrubRef = rememberUpdatedState(onScrub)
    val withBar = bar != null
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            // TextureView-backed for the animated full/mini players; plain SurfaceView for PiP.
            val view = if (textureView) {
                LayoutInflater.from(ctx).inflate(R.layout.player_view_texture, null) as PlayerView
            } else {
                PlayerView(ctx)
            }
            view.apply {
                this.player = player
                keepScreenOn = true
                setShowNextButton(false)
                setShowPreviousButton(false)
                setShowSubtitleButton(false)
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                setFullscreenButtonClickListener { isFull -> fs(isFull) }
                // Media3's settings cog duplicates our speed/quality menus (and caps speed at 2x).
                findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.visibility = View.GONE
                // Brand-colored seek bar + scrub preview hooks.
                findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.apply {
                    setPlayedColor(AccentViolet.toArgb())
                    setScrubberColor(AccentCyan.toArgb())
                    setBufferedColor(0x66FFFFFF)
                    addListener(object : TimeBar.OnScrubListener {
                        override fun onScrubStart(timeBar: TimeBar, position: Long) {
                            scrubRef.value?.invoke(position)
                        }

                        override fun onScrubMove(timeBar: TimeBar, position: Long) {
                            scrubRef.value?.invoke(position)
                        }

                        override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                            scrubRef.value?.invoke(null)
                        }
                    })
                }
                // Quality / speed / refresh in the bottom bar, to the left of fullscreen.
                if (withBar) {
                    findViewById<ViewGroup>(androidx.media3.ui.R.id.exo_basic_controls)?.let { basic ->
                        val q = barButton(ctx, "Auto")
                        val s = barButton(ctx, "1x")
                        val r = barButton(ctx, "↻", sizeSp = 18f)
                        val sc = barIcon(ctx, R.drawable.ic_chat, "Side chat")
                        val oc = barIcon(ctx, R.drawable.ic_chat_overlay, "Pop-out chat")
                        q.setOnClickListener { v -> barRef.value?.onQuality?.invoke(v) }
                        s.setOnClickListener { v -> barRef.value?.onSpeed?.invoke(v) }
                        r.setOnClickListener { barRef.value?.onRefresh?.invoke() }
                        sc.setOnClickListener { barRef.value?.onSideChat?.invoke() }
                        oc.setOnClickListener { barRef.value?.onOverlayChat?.invoke() }
                        basic.addView(oc, 0)
                        basic.addView(sc, 0)
                        basic.addView(r, 0)
                        basic.addView(s, 0)
                        basic.addView(q, 0)
                        // "● LIVE" in place of the time readout while at the live point (like kick.com).
                        val live = findViewById<ViewGroup>(androidx.media3.ui.R.id.exo_time)?.let { time ->
                            TextView(ctx).apply {
                                text = "● LIVE"
                                setTextColor(LiveRed.toArgb())
                                textSize = 13f
                                typeface = Typeface.DEFAULT_BOLD
                                visibility = View.GONE
                                time.addView(this)
                            }
                        }
                        tag = BarButtons(live, q, s, r, sc, oc)
                    }
                }
                setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> ctl(v == View.VISIBLE) })
            }
        },
        update = { v ->
            v.useController = useController
            v.setFullscreenButtonState(fullscreen)
            // Letterbox placement: centered normally, against the right edge when chat is beside it.
            v.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame)?.let { frame ->
                val lp = frame.layoutParams as? android.widget.FrameLayout.LayoutParams
                val gravity = if (alignEnd) (Gravity.END or Gravity.CENTER_VERTICAL) else Gravity.CENTER
                if (lp != null && lp.gravity != gravity) {
                    lp.gravity = gravity
                    frame.layoutParams = lp
                }
            }
            val b = v.tag as? BarButtons
            val spec = bar
            if (b != null && spec != null) {
                b.quality.text = spec.quality
                if (spec.speed != null) {
                    b.speed.text = spec.speed
                    b.speed.visibility = View.VISIBLE
                } else {
                    b.speed.visibility = View.GONE
                }
                b.sideChat.visibility = if (spec.sideChat == null) View.GONE else View.VISIBLE
                b.sideChat.alpha = if (spec.sideChat == true) 1f else 0.55f
                b.overlayChat.visibility = if (spec.overlayChat == null) View.GONE else View.VISIBLE
                b.liveLabel?.let { label ->
                    val time = label.parent as? ViewGroup
                    if (time != null) {
                        for (i in 0 until time.childCount) {
                            val child = time.getChildAt(i)
                            child.visibility = when {
                                child === label -> if (spec.atLive) View.VISIBLE else View.GONE
                                else -> if (spec.atLive) View.GONE else View.VISIBLE
                            }
                        }
                    }
                }
                b.overlayChat.alpha = if (spec.overlayChat == true) 1f else 0.55f
            }
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
    Column(Modifier.fillMaxWidth().padding(start = 2.dp, end = 10.dp, top = 4.dp, bottom = 8.dp)) {
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
                        p is Playable.Vod && p.dvr -> Badge("LIVE • ${compact(ch.live?.viewers ?: 0)}", LiveRed)
                        p is Playable.Vod && p.video.isLive -> Badge("LIVE VOD", AccentCyan, Color(0xFF032B33))
                        p is Playable.ClipItem -> Badge("CLIP", Color(0xFF3A3D41))
                    }
                }
            }
        }
    }
}

/** The one context-dependent jump: live -> from start, from start -> live, clip -> full VOD. */
@Composable
private fun ContextAction(actions: Actions) {
    val current by actions.player.current.collectAsState()
    val atLive by actions.player.atLive.collectAsState()
    val p = current ?: return
    when {
        // Plain live stream (rewindable live turned off): offer the VOD from the start.
        p is Playable.Live && p.channel.live != null -> Pill("⏮ From start") { actions.watchFromStart(p.channel) }
        // Any still-running VOD: jump to the newest part.
        p is Playable.Vod && p.growing && !atLive -> Pill("● Go live") { actions.player.goLive() }
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

/** Video only, for picture-in-picture. */
@Composable
fun PipVideo(actions: Actions) {
    VideoBox(
        actions.player.player, Modifier.fillMaxSize(), useController = false, fullscreen = true,
        onFullscreen = {}, onControls = {}, textureView = false,
    )
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
    val widthPx = with(LocalDensity.current) { MINI_WIDTH.toPx() }

    Box(
        modifier
            .offset { IntOffset(dragX.roundToInt(), 0) }
            .graphicsLayer { alpha = 1f - (abs(dragX) / widthPx).coerceIn(0f, 0.8f) }
            .width(MINI_WIDTH)
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
