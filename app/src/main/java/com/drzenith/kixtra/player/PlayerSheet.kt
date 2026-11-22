package com.drzenith.kixtra.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Drives the expanded player's drag-to-minimize motion. [offset] is how far (px) the player has
 * been pulled down; the UI shrinks it toward the bottom-right corner as it grows.
 */
class PlayerSheet {
    val offset = Animatable(0f)
    var heightPx = 1f

    /** Off in phone fullscreen, where vertical swipes shouldn't minimize. */
    var enabled = true
    var scope: CoroutineScope? = null
    var onCollapsed: () -> Unit = {}

    /** The full player layer, and the area (video + title bar) a drag may start from. */
    var parentCoords: LayoutCoordinates? = null
    var zoneCoords: LayoutCoordinates? = null

    val fraction: Float get() = (offset.value / heightPx).coerceIn(0f, 1f)

    fun inZone(pos: Offset): Boolean {
        if (!enabled) return false
        val p = parentCoords ?: return false
        val z = zoneCoords ?: return false
        if (!p.isAttached || !z.isAttached) return false
        return runCatching { p.localBoundingBoxOf(z, clipBounds = false).contains(pos) }.getOrDefault(false)
    }

    fun dragBy(dy: Float) {
        val s = scope ?: return
        s.launch { offset.snapTo((offset.value + dy).coerceIn(0f, heightPx)) }
    }

    fun release(velocityY: Float) {
        val s = scope ?: return
        s.launch {
            if (offset.value > heightPx * 0.25f || velocityY > 1200f) collapseAnimated()
            else offset.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
        }
    }

    /** Animated minimize (minimize button / back). */
    fun collapse() {
        val s = scope
        if (s == null) {
            onCollapsed()
            return
        }
        s.launch { collapseAnimated() }
    }

    private suspend fun collapseAnimated() {
        offset.animateTo(heightPx, tween(durationMillis = 240, easing = FastOutSlowInEasing))
        onCollapsed()
        offset.snapTo(0f)
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
                    sheet.dragBy(dy - slop)
                } else if (abs(dx) > slop || dy < -slop) {
                    break
                }
            }
        }
        if (dragging && !released) sheet.release(0f)
    }
}
