package com.inputbridge.accessibility

import android.os.Build
import android.os.SystemClock
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.annotation.RequiresApi
import com.inputbridge.core.logging.BridgeLogger
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method

private const val TAG = "ShizukuInputInjector"

/**
 * Injects input events via [InputManager.injectInputEvent()][android.view.InputManager]
 * through Shizuku's shell-privileged Binder.
 *
 * Runs as UID 2000 (adb shell) which has INJECT_EVENTS permission —
 * no root required. Latency: ~1-5ms vs ~10-30ms for dispatchGesture().
 *
 * Falls back to AccessibilityService.dispatchGesture() when Shizuku is unavailable.
 */
@RequiresApi(Build.VERSION_CODES.N)
object ShizukuInputInjector {

    private var inputManager: Any? = null
    private var injectMethod: Method? = null

    @Volatile
    var isAvailable = false
        private set

    /**
     * Initialize Shizuku input injection.
     * Call once after Shizuku binder is received.
     */
    fun init() {
        try {
            val binder = SystemServiceHelper.getSystemService("input")
                ?: throw IllegalStateException("InputManager service not found")

            inputManager = Class.forName("android.hardware.input.IInputManager\$Stub")
                .getMethod("asInterface", android.os.IBinder::class.java)
                .invoke(null, ShizukuBinderWrapper(binder))

            injectMethod = inputManager!!::class.java.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.java
            )

            isAvailable = true
            BridgeLogger.i(TAG, "Shizuku input injection initialized")
        } catch (t: Throwable) {
            BridgeLogger.e(TAG, "Failed to initialize Shizuku input injection", t)
            isAvailable = false
        }
    }

    /**
     * Check if Shizuku is available and permission is granted.
     */
    fun checkAvailability(): Boolean {
        if (!isAvailable) return false
        if (!Shizuku.pingBinder()) {
            isAvailable = false
            return false
        }
        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return true
    }

    /**
     * Inject an input event via InputManager.
     * @return true if the event was injected successfully.
     */
    fun injectInputEvent(event: InputEvent): Boolean {
        if (!checkAvailability()) return false
        return try {
            injectMethod!!.invoke(inputManager, event, 0) as Boolean
        } catch (t: Throwable) {
            BridgeLogger.e(TAG, "injectInputEvent failed", t)
            false
        }
    }

    // ── Convenience methods for gesture injection ──────────────────────────

    /**
     * Inject a tap at the given screen coordinates.
     * Sends ACTION_DOWN followed by ACTION_UP.
     */
    fun tap(x: Float, y: Float): Boolean {
        val now = SystemClock.uptimeMillis()
        val downEvent = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val upEvent = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, x, y, 0)
        val result = injectInputEvent(downEvent) && injectInputEvent(upEvent)
        downEvent.recycle()
        upEvent.recycle()
        return result
    }

    /**
     * Inject a long press at the given screen coordinates.
     * Sends ACTION_DOWN, waits [durationMs], then sends ACTION_UP.
     */
    fun longPress(x: Float, y: Float, durationMs: Long = 600L): Boolean {
        val now = SystemClock.uptimeMillis()
        val downEvent = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val result = injectInputEvent(downEvent)
        downEvent.recycle()

        Thread.sleep(durationMs)

        val upTime = SystemClock.uptimeMillis()
        val upEvent = MotionEvent.obtain(upTime, upTime, MotionEvent.ACTION_UP, x, y, 0)
        val upResult = injectInputEvent(upEvent)
        upEvent.recycle()
        return result && upResult
    }

    /**
     * Inject a swipe from (x1,y1) to (x2,y2) over [durationMs] milliseconds.
     * Sends ACTION_DOWN, multiple MOVE events, then ACTION_UP.
     */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 200L): Boolean {
        val now = SystemClock.uptimeMillis()
        val steps = (durationMs / 8).coerceAtLeast(2)
        val stepDelay = durationMs / steps

        val downEvent = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x1, y1, 0)
        val result = injectInputEvent(downEvent)
        downEvent.recycle()

        for (i in 1..steps) {
            val progress = i.toFloat() / steps
            val x = x1 + (x2 - x1) * progress
            val y = y1 + (y2 - y1) * progress
            val moveTime = now + stepDelay * i
            val moveEvent = MotionEvent.obtain(moveTime, moveTime, MotionEvent.ACTION_MOVE, x, y, 0)
            injectInputEvent(moveEvent)
            moveEvent.recycle()
            Thread.sleep(stepDelay)
        }

        val upTime = SystemClock.uptimeMillis()
        val upEvent = MotionEvent.obtain(upTime, upTime, MotionEvent.ACTION_UP, x2, y2, 0)
        val upResult = injectInputEvent(upEvent)
        upEvent.recycle()
        return result && upResult
    }

    /**
     * Inject a key event.
     */
    fun injectKeyEvent(event: KeyEvent): Boolean {
        return injectInputEvent(event)
    }

    /**
     * Inject a key down + key up pair.
     */
    fun injectKey(keyCode: Int, metaState: Int = 0): Boolean {
        val now = SystemClock.uptimeMillis()
        val downEvent = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState)
        val upEvent = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, metaState)
        val result = injectInputEvent(downEvent) && injectInputEvent(upEvent)
        return result
    }

    /**
     * Clean up resources.
     */
    fun destroy() {
        inputManager = null
        injectMethod = null
        isAvailable = false
    }
}
