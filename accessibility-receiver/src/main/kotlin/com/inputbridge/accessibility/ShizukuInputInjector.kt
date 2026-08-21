package com.inputbridge.accessibility

import android.os.Build
import android.os.SystemClock
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.annotation.RequiresApi
import com.inputbridge.core.logging.BridgeLogger
import com.inputbridge.diagnostics.DiagnosticsManager
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

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

    @Volatile private var inputManager: Any? = null
    @Volatile private var injectMethod: java.lang.reflect.Method? = null

    @Volatile
    var isAvailable = false
        private set

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        BridgeLogger.i(TAG, "Shizuku binder received — re-initializing")
        init()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        BridgeLogger.w(TAG, "Shizuku binder died — invalidating")
        isAvailable = false
        inputManager = null
        injectMethod = null
    }

    /** BUG-132 FIX: the Shizuku runtime permission must be requested from an Activity.
     *  Without it checkSelfPermission() is always denied and we silently fall back to the
     *  accessibility path, which cannot inject key events at all. */
    private const val PERMISSION_REQUEST_CODE = 1

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != PERMISSION_REQUEST_CODE) return@OnRequestPermissionResultListener
        if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            BridgeLogger.i(TAG, "Shizuku permission granted — re-initializing injection")
            init()
            updateInjectionMode()
        } else {
            BridgeLogger.w(TAG, "Shizuku permission denied by user")
            isAvailable = false
            updateInjectionMode()
        }
    }

    private var listenersRegistered = false

    /** True if the Shizuku app/binder is alive (installed + running). */
    fun isBinderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** True if this app has been granted the Shizuku permission. */
    fun isPermissionGranted(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * Initialize Shizuku input injection.
     * Call once after Shizuku binder is received.
     */
    fun init() {
        try {
            val binder = SystemServiceHelper.getSystemService("input")
                ?: throw IllegalStateException("InputManager service not found")

            val wrappedBinder = ShizukuBinderWrapper(binder)

            inputManager = Class.forName("android.hardware.input.IInputManager\$Stub")
                .getMethod("asInterface", android.os.IBinder::class.java)
                .invoke(null, wrappedBinder)

            injectMethod = inputManager!!::class.java.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.java
            )

            isAvailable = true
            BridgeLogger.i(TAG, "Shizuku input injection initialized")
        } catch (t: Throwable) {
            BridgeLogger.d(TAG, "Shizuku not available: ${t.message}")
            isAvailable = false
        }
    }

    /**
     * Register Shizuku binder lifecycle listeners.
     * Call once from Application.onCreate() or AccessibilityService.onCreate().
     */
    fun registerListeners() {
        if (listenersRegistered) return
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
            listenersRegistered = true
            BridgeLogger.i(TAG, "Shizuku lifecycle listeners registered")
        } catch (t: Throwable) {
            BridgeLogger.d(TAG, "Failed to register Shizuku listeners: ${t.message}")
        }
    }

    /**
     * Unregister Shizuku binder lifecycle listeners.
     */
    fun unregisterListeners() {
        if (!listenersRegistered) return
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
            listenersRegistered = false
        } catch (_: Throwable) { }
    }

    /**
     * BUG-132 FIX: request the Shizuku permission from the foreground Activity if it has
     * not been granted yet. Must be called from an Activity context (Shizuku shows a dialog).
     * No-op if the Shizuku binder is not alive (app not installed/running).
     */
    fun requestPermissionIfNeeded(activity: android.app.Activity) {
        if (!isBinderAlive()) {
            BridgeLogger.w(TAG, "Shizuku binder not alive — cannot request permission (install & start Shizuku)")
            return
        }
        if (isPermissionGranted()) {
            init()
            updateInjectionMode()
            return
        }
        try {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            BridgeLogger.i(TAG, "Shizuku permission request shown")
        } catch (t: Throwable) {
            BridgeLogger.e(TAG, "Shizuku.requestPermission failed", t)
        }
    }

    /**
     * Check if Shizuku is available and permission is granted.
     */
    fun checkAvailability(): Boolean {
        if (!isAvailable) return false
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            isAvailable = false
            false
        }
    }

    /** Reflect the current injection mode into DiagnosticsManager. */
    private fun updateInjectionMode() {
        DiagnosticsManager.update {
            copy(injectionMode = if (checkAvailability()) "Shizuku/InputManager" else "Accessibility/dispatchGesture")
        }
    }

    /**
     * Inject an input event via InputManager.
     * @return true if the event was injected successfully.
     */
    fun injectInputEvent(event: InputEvent): Boolean {
        if (!checkAvailability()) return false
        return try {
            @Suppress("UNCHECKED_CAST")
            injectMethod?.invoke(inputManager, event, 0) as? Boolean ?: false
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
        val downTime = SystemClock.uptimeMillis()
        val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        val result = injectInputEvent(downEvent)
        downEvent.recycle()

        val upTime = SystemClock.uptimeMillis()
        val upEvent = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, x, y, 0)
        val upResult = injectInputEvent(upEvent)
        upEvent.recycle()
        return result && upResult
    }

    /**
     * Inject a long press at the given screen coordinates.
     * Sends ACTION_DOWN, waits [durationMs], then sends ACTION_UP.
     */
    suspend fun longPress(x: Float, y: Float, durationMs: Long = 600L): Boolean {
        val downTime = SystemClock.uptimeMillis()
        val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        val result = injectInputEvent(downEvent)
        downEvent.recycle()

        kotlinx.coroutines.delay(durationMs)

        val upTime = SystemClock.uptimeMillis()
        val upEvent = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, x, y, 0)
        val upResult = injectInputEvent(upEvent)
        upEvent.recycle()
        return result && upResult
    }

    /**
     * Inject a swipe from (x1,y1) to (x2,y2) over [durationMs] milliseconds.
     * Sends ACTION_DOWN, multiple MOVE events, then ACTION_UP.
     */
    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 200L): Boolean {
        val downTime = SystemClock.uptimeMillis()
        val steps = (durationMs / 8).coerceAtLeast(2)
        val stepDelay = durationMs / steps

        val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x1, y1, 0)
        val result = injectInputEvent(downEvent)
        downEvent.recycle()

        for (i in 1..steps) {
            val progress = i.toFloat() / steps
            val x = x1 + (x2 - x1) * progress
            val y = y1 + (y2 - y1) * progress
            val moveTime = downTime + stepDelay * i
            val moveEvent = MotionEvent.obtain(downTime, moveTime, MotionEvent.ACTION_MOVE, x, y, 0)
            injectInputEvent(moveEvent)
            moveEvent.recycle()
            kotlinx.coroutines.delay(stepDelay)
        }

        val upTime = SystemClock.uptimeMillis()
        val upEvent = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, x2, y2, 0)
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
        unregisterListeners()
        inputManager = null
        injectMethod = null
        isAvailable = false
    }
}
