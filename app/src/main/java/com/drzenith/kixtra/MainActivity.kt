package com.drzenith.kixtra

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.util.Consumer
import androidx.lifecycle.Lifecycle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.drzenith.kixtra.player.PlaybackService
import com.drzenith.kixtra.player.MiniPlayer
import com.drzenith.kixtra.player.PipVideo
import com.drzenith.kixtra.player.PlayerScreen
import com.drzenith.kixtra.player.detectSheetDrag
import com.drzenith.kixtra.player.isWideWindow
import com.drzenith.kixtra.ui.Actions
import com.drzenith.kixtra.ui.CategoryScreen
import com.drzenith.kixtra.ui.ChannelScreen
import com.drzenith.kixtra.ui.HomeScreen
import com.drzenith.kixtra.ui.KixtraTheme
import com.drzenith.kixtra.ui.Navigator
import com.drzenith.kixtra.ui.Screen
import com.drzenith.kixtra.ui.SearchScreen
import com.drzenith.kixtra.ui.SettingsScreen
import com.google.common.util.concurrent.ListenableFuture

class MainActivity : ComponentActivity() {
    private val app get() = application as App
    private val nav = Navigator()
    private lateinit var actions: Actions
    private val inPip = mutableStateOf(false)
    private var controllerFuture: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actions = Actions(nav, app.playerController, app.store)

        addOnPictureInPictureModeChangedListener(Consumer { info ->
            inPip.value = info.isInPictureInPictureMode
            // PiP window dismissed by the user while the activity is stopped: stop audio too.
            if (!info.isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                app.playerController.player.pause()
            }
        })

        // Binding a controller starts the MediaSessionService (notification + background playback).
        controllerFuture = MediaController.Builder(
            this, SessionToken(this, ComponentName(this, PlaybackService::class.java))
        ).buildAsync()

        handleIntent(intent)
        setContent { KixtraTheme { Root() } }
    }

    @Composable
    private fun Root() {
        val top = nav.top
        val cfg = LocalConfiguration.current
        val landscape = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE
        val phoneFullscreen = landscape && !isWideWindow(cfg)
        val pc = app.playerController
        val current by pc.current.collectAsState()
        val loading by pc.loading.collectAsState()
        val error by pc.error.collectAsState()
        val hasPlayer = current != null || loading || error != null
        val expanded = actions.playerExpanded && hasPlayer
        val sheetScope = rememberCoroutineScope()
        SideEffect { actions.sheet.scope = sheetScope }

        BackHandler(enabled = true) {
            when {
                expanded && phoneFullscreen -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                expanded -> actions.minimizePlayer()
                actions.back() -> {}
                // Leaving the app with something playing: keep it alive (background audio / mini player).
                hasPlayer -> moveTaskToBack(true)
                else -> finish()
            }
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (inPip.value) {
                if (hasPlayer) PipVideo(actions)
                return@Surface
            }
            val fullBleed = expanded && phoneFullscreen
            Box(if (fullBleed) Modifier.fillMaxSize() else Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                when (top) {
                    is Screen.Home -> HomeScreen(top, actions)
                    is Screen.Search -> SearchScreen(top, actions)
                    is Screen.ChannelPage -> ChannelScreen(top, actions)
                    is Screen.CategoryPage -> CategoryScreen(top, actions)
                    is Screen.Settings -> SettingsScreen(actions)
                }
                // Mini player: fades/scales in at the bottom-right when the full player collapses.
                AnimatedVisibility(
                    visible = hasPlayer && !expanded,
                    modifier = Modifier.align(Alignment.BottomEnd)
                        .padding(end = 12.dp, bottom = if (top is Screen.Home) 92.dp else 16.dp),
                    enter = fadeIn(tween(180)) + scaleIn(tween(220), initialScale = 0.7f, transformOrigin = TransformOrigin(1f, 1f)),
                    exit = fadeOut(tween(120)),
                ) {
                    MiniPlayer(actions)
                }
                // Full player: slides up when opened; follows your finger when dragged down and
                // shrinks toward the bottom-right corner (see PlayerSheet).
                AnimatedVisibility(
                    visible = expanded,
                    enter = slideInVertically(tween(260)) { it } + fadeIn(tween(200)),
                    exit = ExitTransition.None,
                ) {
                    val sheet = actions.sheet
                    Box(
                        Modifier.fillMaxSize()
                            .onGloballyPositioned {
                                sheet.parentCoords = it
                                sheet.heightPx = it.size.height.toFloat().coerceAtLeast(1f)
                            }
                            .pointerInput(sheet) { detectSheetDrag(sheet) }
                    ) {
                        Box(
                            Modifier.fillMaxSize()
                                .graphicsLayer {
                                    val f = sheet.fraction
                                    val k = 0.6f
                                    transformOrigin = TransformOrigin(1f, 1f)
                                    scaleX = 1f - k * f
                                    scaleY = 1f - k * f
                                    // Top edge tracks the finger exactly: H*k*f + t == offset.
                                    translationY = sheet.offset.value * (1f - k)
                                    alpha = 1f - 0.5f * f
                                    shape = RoundedCornerShape((24 * f).dp)
                                    clip = f > 0f
                                }
                                .background(MaterialTheme.colorScheme.background)
                                // Swallow taps so nothing underneath reacts.
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                        ) {
                            PlayerScreen(actions)
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** kick.com/{channel} and kick.com/{channel}/videos/{uuid} links. */
    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        val parts = uri.pathSegments ?: return
        if (parts.isEmpty()) return
        val slug = parts[0]
        if (slug in setOf("categories", "category", "search", "browse", "following")) return
        val uuid = if (parts.size >= 3 && parts[1] == "videos") parts[2] else null
        nav.push(Screen.ChannelPage(slug, uuid))
    }

    private fun shouldEnterPip(): Boolean =
        app.store.pipEnabled && app.playerController.current.value != null && app.playerController.player.isPlaying &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (shouldEnterPip()) {
            runCatching {
                enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
            }
        }
    }

    override fun onStart() {
        super.onStart()
        app.playerController.onForeground()
    }

    override fun onStop() {
        super.onStop()
        if (!isInPictureInPictureMode) app.playerController.onBackground()
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }
}
