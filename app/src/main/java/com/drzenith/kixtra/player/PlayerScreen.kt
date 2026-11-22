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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.media3.ui.PlayerView
import com.drzenith.kixtra.chat.ChatSource
import com.drzenith.kixtra.chat.ChatView
import com.drzenith.kixtra.chat.LiveChat
import com.drzenith.kixtra.chat.ReplayChat
import com.drzenith.kixtra.chat.parseKickTime
import com.drzenith.kixtra.ui.Actions
import com.drzenith.kixtra.ui.Avatar
import com.drzenith.kixtra.ui.Badge
import com.drzenith.kixtra.ui.KickGreen
import com.drzenith.kixtra.ui.LiveRed
import com.drzenith.kixtra.ui.compact
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 3.5f, 4f)

fun speedLabel(s: Float): String = if (s % 1f == 0f) "${s.toInt()}x" else "${s}x"

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun PlayerScreen(actions: Actions, inPip: Boolean) {
    val pc = actions.player
    val current by pc.current.collectAsState()
    val loading by pc.loading.collectAsState()
    val error by pc.error.collectAsState()
    val context = LocalContext.current
    val activity = context.findActivity()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val scope = rememberCoroutineScope()
    var chatInLandscape by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }

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
    DisposableEffect(landscape, inPip) {
        val window = activity?.window
        if (window != null) {
            val ctl = WindowCompat.getInsetsController(window, window.decorView)
            if (landscape || inPip) {
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
        }
    }
    DisposableEffect(chat) {
        chat?.start()
        onDispose { chat?.stop() }
    }

    if (inPip) {
        VideoBox(pc.player, Modifier.fillMaxSize(), useController = false, fullscreen = true, onFullscreen = {}, onControls = {})
        return
    }

    val video: @Composable (Modifier) -> Unit = { m ->
        Box(m.background(Color.Black)) {
            VideoBox(pc.player, Modifier.fillMaxSize(), true, landscape, ::setFullscreen) { controlsVisible = it }
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                error != null -> Column(Modifier.align(Alignment.Center).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error ?: "", color = Color.White)
                    Spacer(Modifier.padding(4.dp))
                    Button(onClick = { pc.reload(keepPosition = true) }) { Text("Retry") }
                }
            }
            if (landscape && controlsVisible) {
                Row(
                    Modifier.align(Alignment.TopEnd).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PlaybackButtons(actions, compactMode = true)
                    if (chat != null) {
                        SmallButton(if (chatInLandscape) "Hide chat" else "Chat") { chatInLandscape = !chatInLandscape }
                    }
                }
            }
        }
    }

    val chatMessages = chat?.messages?.collectAsState()
    val chatStatus = chat?.status?.collectAsState()

    if (landscape) {
        Row(Modifier.fillMaxSize().background(Color.Black)) {
            video(Modifier.weight(1f).fillMaxHeight())
            if (chat != null && chatInLandscape) {
                ChatView(chatMessages?.value ?: emptyList(), chatStatus?.value ?: "", Modifier.width(300.dp).fillMaxHeight())
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
private fun VideoBox(
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
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
                        p is Playable.Vod && p.dvr -> Badge("FROM START", KickGreen, Color.Black)
                        p is Playable.Vod && p.video.isLive -> Badge("LIVE VOD", KickGreen, Color.Black)
                    }
                }
            }
        }
        Row(
            Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlaybackButtons(actions, compactMode = false)
        }
    }
}

/** Quality, speed and live/from-start switches; shared by the portrait bar and the landscape overlay. */
@Composable
private fun PlaybackButtons(actions: Actions, compactMode: Boolean) {
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
    MenuButton(if (compactMode) selectedQ else "Quality: $selectedQ", qOptions, selectedQ)

    if (p is Playable.Vod) {
        MenuButton(
            if (compactMode) speedLabel(speed) else "Speed: ${speedLabel(speed)}",
            SPEEDS.map { s -> speedLabel(s) to { pc.setSpeed(s) } },
            speedLabel(speed),
        )
    }
    when {
        p is Playable.Live && p.channel.live != null ->
            SmallButton("⏮ From start") { actions.watchFromStart(p.channel) }
        p is Playable.Vod && p.dvr ->
            SmallButton("● Go live") { actions.player.playLive(p.channel.slug) }
    }
    if (!compactMode) SmallButton("↻") { pc.reload(keepPosition = true) }
}

@Composable
private fun SmallButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(containerColor = Color(0x99000000)),
    ) { Text(label, fontSize = 13.sp, color = Color.White) }
}

@Composable
private fun MenuButton(label: String, options: List<Pair<String, () -> Unit>>, selected: String?) {
    var open by remember { mutableStateOf(false) }
    Box {
        SmallButton("$label ▾") { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (text, action) ->
                DropdownMenuItem(
                    text = { Text(if (text == selected) "✓  $text" else "    $text") },
                    onClick = { open = false; action() },
                )
            }
        }
    }
}
