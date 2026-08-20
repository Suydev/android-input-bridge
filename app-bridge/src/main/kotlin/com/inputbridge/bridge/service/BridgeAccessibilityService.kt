package com.inputbridge.bridge.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.inputbridge.bridge.prefs.BridgePreferences
import com.inputbridge.core.config.TransportConfig
import com.inputbridge.core.logging.BridgeLogger
import com.inputbridge.core.model.InputEvent
import com.inputbridge.core.model.ModifierState
import com.inputbridge.core.model.MouseButton
import com.inputbridge.protocol.EventPacketFactory
import com.inputbridge.transport.wifi.UdpTransport
import kotlinx.coroutines.*
import org.koin.android.ext.android.inject

private const val TAG = "BridgeA11y"

/**
 * AccessibilityService on the bridge phone that:
 * 1. Captures USB keyboard input via onKeyEvent() and forwards it to the receiver over UDP.
 * 2. Receives input events from the receiver (reverse trackpad) and injects them locally
 *    via dispatchGesture().
 *
 * This dual role is possible because the same accessibility service can both intercept
 * key events (FLAG_REQUEST_FILTER_KEY_EVENTS) and dispatch gestures (dispatchGesture).
 *
 * Lifecycle:
 * - onServiceConnected(): sets up flags, opens UDP transport, records screen size
 * - onKeyEvent(): converts KeyEvent → InputEvent → Packet → UDP send (capture direction)
 * - injectInputEvent(): receives InputEvent from reverse trackpad → dispatchGesture (injection)
 * - onUnbind(): disconnects transport
 */
class BridgeAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: BridgeAccessibilityService? = null
            private set

        fun isRunning() = instance != null

        private const val TAP_DURATION_MS = 50L
    }

    private val packetFactory = EventPacketFactory()
    @Volatile private var udpTransport: UdpTransport? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, t ->
        if (t !is CancellationException) {
            BridgeLogger.e(TAG, "Uncaught exception in BridgeAccessibilityService", t)
        }
    })
    private val prefs: BridgePreferences by lazy {
        BridgePreferences(applicationContext)
    }

    // Modifier state tracking — BUG-XXX FIX: track left/right separately so
    // releasing one side while the other is held doesn't clear the modifier.
    @Volatile private var shiftLeftPressed = false
    @Volatile private var shiftRightPressed = false
    @Volatile private var ctrlLeftPressed = false
    @Volatile private var ctrlRightPressed = false
    @Volatile private var altLeftPressed = false
    @Volatile private var altRightPressed = false
    @Volatile private var metaLeftPressed = false
    @Volatile private var metaRightPressed = false

    // Screen dimensions for gesture injection (populated on service connected)
    @Volatile private var screenWidth = 1080
    @Volatile private var screenHeight = 1920

    // Virtual cursor position for reverse trackpad (normalized 0-1)
    @Volatile private var cursorX = 0.5f
    @Volatile private var cursorY = 0.5f

    // ── Lifecycle ───────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        BridgeLogger.i(TAG, "Bridge accessibility service connected")

        // Request key event filtering
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }

        // Record real screen dimensions for gesture injection
        val wm = getSystemService(WINDOW_SERVICE) as? WindowManager
        if (wm != null) {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            BridgeLogger.i(TAG, "Screen size: ${screenWidth}x${screenHeight}")
        }

        connectTransport()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        scope.launch {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { udpTransport?.send(packetFactory.makeDisconnect()) }
                delay(50L)
                runCatching { udpTransport?.disconnect() }
            }
        }
        BridgeLogger.i(TAG, "Bridge accessibility service unbound")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used — we only need onKeyEvent()
    }

    override fun onInterrupt() {
        BridgeLogger.w(TAG, "Bridge accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // ── Key event capture ───────────────────────────────────────────────────

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val transport = udpTransport
        if (transport == null || !transport.isConnected) {
            return false
        }

        when (event.keyCode) {
            KeyEvent.KEYCODE_SHIFT_LEFT ->
                shiftLeftPressed = event.action == KeyEvent.ACTION_DOWN
            KeyEvent.KEYCODE_SHIFT_RIGHT ->
                shiftRightPressed = event.action == KeyEvent.ACTION_DOWN
            KeyEvent.KEYCODE_CTRL_LEFT ->
                ctrlLeftPressed = event.action == KeyEvent.ACTION_DOWN
            KeyEvent.KEYCODE_CTRL_RIGHT ->
                ctrlRightPressed = event.action == KeyEvent.ACTION_DOWN
            KeyEvent.KEYCODE_ALT_LEFT ->
                altLeftPressed = event.action == KeyEvent.ACTION_DOWN
            KeyEvent.KEYCODE_ALT_RIGHT ->
                altRightPressed = event.action == KeyEvent.ACTION_DOWN
            KeyEvent.KEYCODE_META_LEFT ->
                metaLeftPressed = event.action == KeyEvent.ACTION_DOWN
            KeyEvent.KEYCODE_META_RIGHT ->
                metaRightPressed = event.action == KeyEvent.ACTION_DOWN
        }

        val modifiers = ModifierState(
            shift = shiftLeftPressed || shiftRightPressed,
            ctrl = ctrlLeftPressed || ctrlRightPressed,
            alt = altLeftPressed || altRightPressed,
            meta = metaLeftPressed || metaRightPressed,
        )

        val inputEvent = if (event.action == KeyEvent.ACTION_DOWN) {
            InputEvent.KeyDown(event.keyCode, event.scanCode, modifiers)
        } else if (event.action == KeyEvent.ACTION_UP) {
            InputEvent.KeyUp(event.keyCode, event.scanCode, modifiers)
        } else {
            return false
        }

        val packet = packetFactory.fromEvent(inputEvent) ?: return false
        scope.launch { runCatching { transport.send(packet) } }

        BridgeLogger.d(TAG, "Key event: ${KeyEvent.keyCodeToString(event.keyCode)} " +
            "action=${if (event.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"}")

        return true
    }

    // ── Reverse trackpad: input injection ──────────────────────────────────

    /**
     * Inject an input event received from the receiver tablet (reverse trackpad mode).
     * Called from BridgeService's incoming packet loop.
     *
     * Supported event types:
     * - CursorGoto: moves the virtual cursor and dispatches a tap gesture at the target position
     * - MouseButtonDown/Up: dispatches tap/long-press at current virtual cursor position
     * - Scroll: dispatches a swipe gesture for scrolling
     * - MouseMove: updates virtual cursor position (relative delta)
     */
    fun injectInputEvent(event: InputEvent) {
        if (!isRunning()) return
        // BUG-XXX FIX: dispatchGesture must be called from Main thread per API docs.
        // This method is called from BridgeService's IO dispatcher. Wrap all gesture
        // dispatches in withContext(Dispatchers.Main).
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            injectInputEventOnMain(event)
        }
    }

    private fun injectInputEventOnMain(event: InputEvent) {
        when (event) {
            is InputEvent.CursorGoto -> {
                // Absolute position: normalize to screen coordinates
                val x = (event.x * screenWidth).coerceIn(0f, screenWidth.toFloat())
                val y = (event.y * screenHeight).coerceIn(0f, screenHeight.toFloat())
                cursorX = event.x
                cursorY = event.y
                try {
                    dispatchTapGesture(x, y)
                    BridgeLogger.d(TAG, "CursorGoto → tap at (${x.toInt()}, ${y.toInt()})")
                } catch (e: Exception) {
                    BridgeLogger.e(TAG, "Failed to dispatch cursor goto", e)
                }
            }
            is InputEvent.MouseMove -> {
                // Relative delta: update virtual cursor position
                cursorX = (cursorX + event.dx).coerceIn(0f, 1f)
                cursorY = (cursorY + event.dy).coerceIn(0f, 1f)
            }
            is InputEvent.MouseButtonDown -> {
                // Click at current virtual cursor position
                val x = (cursorX * screenWidth).coerceIn(0f, screenWidth.toFloat())
                val y = (cursorY * screenHeight).coerceIn(0f, screenHeight.toFloat())
                try {
                    if (event.button == MouseButton.LEFT) {
                        dispatchTapGesture(x, y)
                    } else {
                        dispatchLongPressGesture(x, y)
                    }
                    BridgeLogger.d(TAG, "MouseButton ${event.button} at (${x.toInt()}, ${y.toInt()})")
                } catch (e: Exception) {
                    BridgeLogger.e(TAG, "Failed to dispatch click gesture", e)
                }
            }
            is InputEvent.MouseButtonUp -> {
                // No-op for now (tap is already dispatched on down)
            }
            is InputEvent.Scroll -> {
                // Vertical scroll: dispatch a small swipe gesture
                val centerX = screenWidth / 2f
                val startY = screenHeight / 2f
                val endY = startY - event.dy * 100f  // scroll direction
                try {
                    dispatchSwipeGesture(centerX, startY, centerX, endY, 100L)
                    BridgeLogger.d(TAG, "Scroll dy=${event.dy}")
                } catch (e: Exception) {
                    BridgeLogger.e(TAG, "Failed to dispatch scroll gesture", e)
                }
            }
            // Unsupported reverse-trackpad events (keyboard, text, navigation)
            is InputEvent.KeyDown,
            is InputEvent.KeyUp,
            is InputEvent.TextInput,
            is InputEvent.ModifierStateChanged,
            is InputEvent.NavigationAction -> {
                BridgeLogger.d(TAG, "Unsupported reverse event: ${event::class.simpleName}")
            }
        }
    }

    private fun dispatchTapGesture(x: Float, y: Float) {
        try {
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            BridgeLogger.e(TAG, "Failed to dispatch tap gesture", e)
        }
    }

    private fun dispatchLongPressGesture(x: Float, y: Float) {
        try {
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 600L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            BridgeLogger.e(TAG, "Failed to dispatch long press gesture", e)
        }
    }

    private fun dispatchSwipeGesture(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long,
    ) {
        try {
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            BridgeLogger.e(TAG, "Failed to dispatch swipe gesture", e)
        }
    }

    // ── Transport ───────────────────────────────────────────────────────────

    private fun connectTransport() {
        val targetIp = prefs.targetIp
        val port = prefs.port

        if (targetIp.isBlank()) {
            BridgeLogger.w(TAG, "Target IP not configured — keyboard forwarding disabled")
            return
        }

        scope.launch {
            val config = TransportConfig(targetIp = targetIp, port = port)
            val transport = UdpTransport(config, isSender = true)
            val ok = transport.connect()
            if (ok) {
                udpTransport = transport
                BridgeLogger.i(TAG, "Keyboard UDP transport connected → $targetIp:$port")
            } else {
                BridgeLogger.e(TAG, "Keyboard UDP transport failed to connect")
            }
        }
    }
}
