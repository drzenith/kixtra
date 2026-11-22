package com.drzenith.kixtra.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Drives the expanded player's drag-to-minimize motion.
 *
 * As [offset] grows from 0 to [travel], the player layer is scaled and moved so its video lands
 * exactly where the mini player sits; the video's top edge follows the finger 1:1. The rest of
 * the player (title bar, chat, background) fades out on the way.
 */
class PlayerSheet {
    val offset = Animatable(0f)

    /** Off in phone fullscreen, where vertical swipes shouldn't minimize. */
    var enabled = true
    var scope: CoroutineScope? = null
    var onCollapsed: () -> Unit = {}

    /** The full player layer, the drag zone (video + title bar) and the video itself. */
    var parentCoords: LayoutCoordinates? = null
    /** An area inside the zone where drags belong to something else (the pop-out chat). */
    var excludeCoords: LayoutCoordinates? = null
    var zoneCoords: LayoutCoordinates? = null
    var videoCoords: LayoutCoordinates? = null

    /** Mini player box (px), relative to the bottom-right of the player layer. */
    var miniWidthPx = 1f
    var miniHeightPx = 1f
    var miniEndPx = 0f
    var miniBottomPx = 0f

    /** Target transform for a fully collapsed player (origin top-left). */
    class Geometry(val scale: Float, val tx: Float, val ty: Float, val travel: Float)

    var geometry = Geometry(1f, 0f, 0f, 1f)
        private set

    val fraction: Float get() = (offset.value / geometry.travel).coerceIn(0f, 1f)

    /**
     * True while the video is being handed between the full player and the mini player: both are
     * shown (mini underneath) until the new view has drawn a frame, so nothing flashes.
     */
    var handoff by mutableStateOf(false)
        private set
    var awaitFirstFrame: suspend () -> Unit = {}

    /** True while the player is growing out of the mini player (the UI skips its slide-in). */
    var fromMini by mutableStateOf(false)
        private set

    /** Works out where the video has to end up; done at the start of each drag / collapse. */
    private fun computeGeometry() {
        val p = parentCoords ?: return
        val v = videoCoords ?: return
        if (!p.isAttached || !v.isAttached) return
        val vr = runCatching { p.localBoundingBoxOf(v, clipBounds = false) }.getOrNull() ?: return
        if (vr.width <= 0f) return
        val w = p.size.width.toFloat()
        val h = p.size.height.toFloat()
        val miniLeft = w - miniEndPx - miniWidthPx
        val miniTop = h - miniBottomPx - miniHeightPx
        val s = miniWidthPx / vr.width
        geometry = Geometry(
            scale = s,
            tx = miniLeft - vr.left * s,
            ty = miniTop - vr.top * s,
            travel = (miniTop - vr.top).coerceAtLeast(1f),
        )
    }

    fun inZone(pos: Offset): Boolean {
        if (!enabled) return false
        val p = parentCoords ?: return false
        val z = zoneCoords ?: return false
        if (!p.isAttached || !z.isAttached) return false
        val ex = excludeCoords
        if (ex != null && ex.isAttached &&
            runCatching { p.localBoundingBoxOf(ex, clipBounds = false).contains(pos) }.getOrDefault(false)
        ) return false
        return runCatching { p.localBoundingBoxOf(z, clipBounds = false).contains(pos) }.getOrDefault(false)
    }

    fun startDrag() = computeGeometry()

    fun dragBy(dy: Float) {
        val s = scope ?: return
        s.launch { offset.snapTo((offset.value + dy).coerceIn(0f, geometry.travel)) }
    }

    fun release(velocityY: Float) {
        val s = scope ?: return
        s.launch {
            if (offset.value > geometry.travel * 0.25f || velocityY > 1200f) {
                collapseAnimated()
            } else {
                offset.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow))
            }
        }
    }

    /** Animated minimize (minimize button / back). */
    fun collapse() {
        val s = scope
        if (s == null) {
            onCollapsed()
            return
        }
        computeGeometry()
        s.launch { collapseAnimated() }
    }

    /**
     * Called right before the player is shown again. Resetting here (not after collapsing) keeps
     * the collapsed layer in the mini player's spot until it's gone, so it never flashes full-size.
     */
    fun reset() {
        fromMini = false
        val s = scope ?: return
        s.launch(start = CoroutineStart.UNDISPATCHED) { offset.snapTo(0f) }
    }

    /**
     * Reverse of collapsing: start the full player on top of the mini player and grow it back.
     * Falls back to the normal slide-in if we don't know the mini player's geometry yet.
     */
    fun expandFromMini(show: () -> Unit) {
        val s = scope
        val g = geometry
        if (s == null || g.travel <= 1f || g.scale >= 1f) {
            reset()
            show()
            return
        }
        fromMini = true
        handoff = true
        s.launch(start = CoroutineStart.UNDISPATCHED) { offset.snapTo(g.travel) }
        show()
        s.launch {
            awaitFirstFrame()
            handoff = false
        }
        s.launch {
            offset.animateTo(0f, tween(durationMillis = 280, easing = FastOutSlowInEasing))
            fromMini = false
        }
    }

    private suspend fun collapseAnimated() {
        offset.animateTo(geometry.travel, tween(durationMillis = 260, easing = FastOutSlowInEasing))
        // Mount the mini player underneath, let it draw, then remove the full player on top.
        handoff = true
        awaitFirstFrame()
        onCollapsed()
        handoff = false
    }
}

/**
 * Watches for a downward drag that starts inside the sheet's zone (Initial pass, so it sees the
 * gesture before the video view does). Horizontal drags, upward drags and taps are left alone.
 */
suspend fun PointerInputScope.detectSheetDrag(sheet: PlayerSheet) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (!sheet.inZone(down.position)) return@awaitEachGesture
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var dx = 0f
        var dy = 0f
        var dragging = false
        var released = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            tracker.addPosition(change.uptimeMillis, change.position)
            if (!change.pressed) {
                if (dragging) {
                    sheet.release(tracker.calculateVelocity().y)
                    released = true
                }
                break
            }
            val delta = change.positionChange()
            if (dragging) {
                sheet.dragBy(delta.y)
                change.consume()
            } else {
                dx += delta.x
                dy += delta.y
                if (dy > slop && dy > 2 * abs(dx)) {
                    dragging = true
                    change.consume()
                    sheet.startDrag()
                    sheet.dragBy(dy - slop)
                } else if (abs(dx) > slop || dy < -slop) {
                    break
                }
            }
        }
        if (dragging && !released) sheet.release(0f)
    }
}
