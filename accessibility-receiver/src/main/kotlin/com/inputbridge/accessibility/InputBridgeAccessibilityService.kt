package com.inputbridge.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import com.inputbridge.core.logging.BridgeLogger

private const val TAG = "AccessibilityService"

/**
 * InputBridge Accessibility Service — the core of the receiver app's input injection.
 *
 * IMPORTANT: This is NOT a hardware cursor. Accessibility services inject synthetic
 * gestures and events, not kernel-level input. The UI must make this distinction clear.
 *
 * Capabilities:
 * - Tap anywhere on screen (dispatchGesture)
 * - Swipe / drag (multi-step gesture)
 * - Scroll (SCROLL_FORWARD / SCROLL_BACKWARD or gesture)
 * - Text input (paste via clipboard or ACTION_SET_TEXT on focused node)
 * - Navigation: BACK, HOME, RECENTS, NOTIFICATIONS
 *
 * Limitations (must be communicated to the user):
 * - No real mouse pointer
 * - Cannot click inside secure windows (e.g. PIN entry on lockscreen)
 * - Some system UI elements may not respond to synthetic gestures
 * - Performance: each gesture takes a frame round-trip
 *
 * The service receives commands through [AccessibilityCommandBus].
 */
@RequiresApi(Build.VERSION_CODES.N)
class InputBridgeAccessibilityService : AccessibilityService() {

    companion object {
        /** Singleton reference — set when service is connected, cleared on unbind. */
        @Volatile
        var instance: InputBridgeAccessibilityService? = null
            private set

        fun isRunning() = instance != null
    }

    override fun onServiceConnected() {
        instance = this
        BridgeLogger.i(TAG, "Accessibility service connected")
        AccessibilityCommandBus.setService(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        AccessibilityCommandBus.clearService()
        BridgeLogger.i(TAG, "Accessibility service unbound")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to observe accessibility events for input injection.
        // If future features (e.g. UI inspection, macro recording) need this, implement here.
    }

    override fun onInterrupt() {
        BridgeLogger.w(TAG, "Accessibility service interrupted")
    }

    // ── Input injection API ───────────────────────────────────────────────────

    /**
     * Dispatch a tap gesture at the given screen coordinates.
     * @param x Screen X in pixels.
     * @param y Screen Y in pixels.
     */
    fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
            .build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * Dispatch a swipe from (x1,y1) to (x2,y2) over [durationMs] milliseconds.
     */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 200L) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * Dispatch a long-press gesture (press and hold).
     */
    fun longPress(x: Float, y: Float, durationMs: Long = 600L) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(gesture, null, null)
    }

    /** Navigate BACK. */
    fun goBack() = performGlobalAction(GLOBAL_ACTION_BACK)

    /** Navigate HOME. */
    fun goHome() = performGlobalAction(GLOBAL_ACTION_HOME)

    /** Open recents. */
    fun goRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)

    /** Pull down notifications. */
    fun openNotifications() = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    companion object {
        private const val TAP_DURATION_MS = 50L
    }
}
