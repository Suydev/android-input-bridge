package com.inputbridge.accessibility

import android.os.Build
import android.os.SystemClock
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.annotation.RequiresApi
import com.inputbridge.core.logging.BridgeLogger
import java.lang.reflect.Method

private const val TAG = "ShizukuInputInjector"

/**
 * Injects input events via [InputManager.injectInputEvent()][android.view.InputManager]
 * through Shizuku's shell-privileged Binder.
 *
 * Runs as UID 2000 (adb shell) which has INJECT_EVENTS permission —
 * no root required. Latency: ~1-5ms vs ~10-30ms for dispatchGesture().
 *
 * All Shizuku classes are loaded via reflection so this module compiles
 * without a hard dependency on Shizuku. If Shizuku is not installed or
 * permission is denied, [isAvailable] stays false and callers fall back
 * to AccessibilityService.dispatchGesture().
 */
@RequiresApi(Build.VERSION_CODES.N)
object ShizukuInputInjector {

    private var inputManager: Any? = null
    private var injectMethod: Method? = null
    private var shizukuBinderWrapperClass: Class<*>? = null
    private var systemServiceHelperClass: Class<*>? = null
    private var shizukuClass: Class<*>? = null

    @Volatile
    var isAvailable = false
        private set

    /**
     * Initialize Shizuku input injection.
     * Call once after Shizuku binder is received.
     * Uses reflection so this module compiles without Shizuku on the classpath.
     */
    fun init() {
        try {
            // Load Shizuku classes via reflection
            shizukuBinderWrapperClass = Class.forName("rikka.shizuku.ShizukuBinderWrapper")
            systemServiceHelperClass = Class.forName("rikka.shizuku.SystemServiceHelper")
            shizukuClass = Class.forName("rikka.shizuku.Shizuku")

            // Get the input system service binder
            val getBinderMethod = systemServiceHelperClass!!.getMethod("getSystemService", String::class.java)
            val binder = getBinderMethod.invoke(null, "input") as? android.os.IBinder
                ?: throw IllegalStateException("InputManager service binder not found")

            // Wrap binder with ShizukuBinderWrapper
            val wrapperConstructor = shizukuBinderWrapperClass!!.getConstructor(android.os.IBinder::class.java)
            val wrappedBinder = wrapperConstructor.newInstance(binder)

            // Get IInputManager.Stub.asInterface(wrappedBinder)
            val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asInterfaceMethod = stubClass.getMethod("asInterface", android.os.IBinder::class.java)
            inputManager = asInterfaceMethod.invoke(null, wrappedBinder)

            // Get injectInputEvent method
            injectMethod = inputManager!!::class.java.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.java
            )

            isAvailable = true
            BridgeLogger.i(TAG, "Shizuku input injection initialized via reflection")
        } catch (t: Throwable) {
            BridgeLogger.d(TAG, "Shizuku not available: ${t.message}")
            isAvailable = false
        }
    }

    /**
     * Check if Shizuku is available and permission is granted.
     */
    fun checkAvailability(): Boolean {
        if (!isAvailable) return false
        try {
            val pingMethod = shizukuClass!!.getMethod("pingBinder")
            val alive = pingMethod.invoke(null) as? Boolean ?: false
            if (!alive) {
                isAvailable = false
                return false
            }
            val checkMethod = shizukuClass!!.getMethod("checkSelfPermission")
            val result = checkMethod.invoke(null) as? Int ?: -1
            return result == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            isAvailable = false
            return false
        }
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
     * Clean up resources.
     */
    fun destroy() {
        inputManager = null
        injectMethod = null
        isAvailable = false
    }
}
