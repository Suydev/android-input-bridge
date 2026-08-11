package com.inputbridge.bridge.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.inputbridge.bridge.prefs.BridgePreferences
import com.inputbridge.core.config.TransportConfig
import com.inputbridge.core.logging.BridgeLogger
import com.inputbridge.core.model.InputEvent
import com.inputbridge.core.model.MouseButton
import com.inputbridge.protocol.EventPacketFactory
import com.inputbridge.transport.wifi.UdpTransport
import kotlinx.coroutines.*
import org.koin.android.ext.android.inject

private const val TAG = "MouseTrackpad"

/**
 * Transparent fullscreen overlay that turns the phone's touchscreen into a trackpad
 * for the remote tablet.
 *
 * Touch mapping:
 * - Single finger drag  → relative mouse movement (MOUSE_MOVE)
 * - Quick tap (down+up < 300ms, < 15px movement) → left click (MOUSE_DOWN / MOUSE_UP)
 * - Long press (down held > 500ms, < 15px movement) → right click
 * - Two-finger vertical drag → scroll (SCROLL)
 * - Close button (top-right X) → finishes activity
 *
 * Opens its own UdpTransport sender connection to the receiver using the
 * same target IP and port from BridgePreferences.
 */
class MouseTrackpadActivity : ComponentActivity() {

    private val prefs: BridgePreferences by inject()
    private val packetFactory = EventPacketFactory()

    private var udpTransport: UdpTransport? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Touch tracking ──────────────────────────────────────────────────────

    /** Raw screen pixel coordinates of the first finger down. */
    private var downX = 0f
    private var downY = 0f
    /** Accumulated pixel movement since down — used to distinguish tap from drag. */
    private var totalMovement = 0f
    /** Timestamp of ACTION_DOWN (uptimeMillis). */
    private var downTime = 0L
    /** Whether we are currently in a "dragging" state (have exceeded the movement threshold). */
    private var isDragging = false
    /** Whether a long-press timer has fired for the current gesture. */
    private var longPressFired = false

    // ── Two-finger scroll tracking ──────────────────────────────────────────
    private var scrollStartY = 0f
    private var isTwoFingerScroll = false
    private var lastScrollY = 0f

    // ── Timing constants ────────────────────────────────────────────────────
    private companion object {
        /** Movement threshold (pixels) to distinguish tap from drag. */
        const val TAP_THRESHOLD_PX = 15f
        /** Maximum duration (ms) for a tap. */
        const val TAP_MAX_DURATION_MS = 300L
        /** Duration (ms) before a stationary press becomes a right-click. */
        const val LONG_PRESS_DURATION_MS = 500L
        /** Pointer lifetime before auto-reset (seconds). */
        const val POINTER_TIMEOUT_MS = 5_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        if (!isDragging && !longPressFired && totalMovement < TAP_THRESHOLD_PX) {
            longPressFired = true
            sendMouseButton(MouseButton.RIGHT)
            vibrateShort()
        }
    }

    // ── UI ──────────────────────────────────────────────────────────────────

    private lateinit var trackpadView: View
    private lateinit var statusText: TextView
    private lateinit var closeButton: TextView

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fullscreen transparent overlay
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }

        // Build the view hierarchy programmatically (no XML layout needed)
        val root = FrameLayout(this).apply {
            setBackgroundColor(0x20000000.toInt()) // semi-transparent dark
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Status text at the bottom
        statusText = TextView(this).apply {
            text = "Trackpad active — drag to move, tap to click, long-press for right-click, two-finger to scroll"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 12f
            setPadding(32, 16, 32, 16)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.BOTTOM
            }
        }
        root.addView(statusText)

        // Close button at top-right
        closeButton = TextView(this).apply {
            text = "✕"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 28f
            setPadding(32, 32, 32, 32)
            setOnClickListener { finish() }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.END
            }
        }
        root.addView(closeButton)

        // Trackpad surface
        trackpadView = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(trackpadView)

        setContentView(root)

        // Touch handling
        trackpadView.setOnTouchListener { _, event -> handleTouch(event); true }

        // Connect UDP transport
        connectTransport()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(longPressRunnable)
        scope.launch {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { udpTransport?.send(packetFactory.makeDisconnect()) }
                delay(50L)
                runCatching { udpTransport?.disconnect() }
            }
        }
        scope.cancel()
    }

    // ── Touch handling ──────────────────────────────────────────────────────

    @SuppressLint("SetTextI18n")
    private fun handleTouch(event: MotionEvent) {
        val transport = udpTransport
        if (transport == null || !transport.isConnected) {
            // Retry connection on next touch
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                connectTransport()
            }
            return
        }

        val pointerCount = event.pointerCount

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.getX(0)
                downY = event.getY(0)
                totalMovement = 0f
                downTime = event.eventTime
                isDragging = false
                longPressFired = false
                isTwoFingerScroll = false

                // Schedule long-press timer
                handler.postDelayed(longPressRunnable, LONG_PRESS_DURATION_MS)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (pointerCount >= 2) {
                    // Two-finger scroll begins
                    isTwoFingerScroll = true
                    scrollStartY = event.getY(1)
                    lastScrollY = scrollStartY
                    handler.removeCallbacks(longPressRunnable)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (isTwoFingerScroll && pointerCount >= 2) {
                    // Two-finger vertical scroll
                    val currentY = event.getY(1)
                    val deltaY = currentY - lastScrollY
                    lastScrollY = currentY
                    if (kotlin.math.abs(deltaY) > 1f) {
                        val scrollDy = deltaY / 10f // scale down for sensitivity
                        sendScroll(0f, scrollDy)
                    }
                } else if (pointerCount == 1) {
                    val x = event.getX(0)
                    val y = event.getY(0)
                    val dx = x - downX
                    val dy = y - downY
                    totalMovement = kotlin.math.sqrt(dx * dx + dy * dy)

                    if (totalMovement > TAP_THRESHOLD_PX && !isDragging) {
                        // Crossed the threshold — enter drag mode
                        isDragging = true
                        handler.removeCallbacks(longPressRunnable)
                        // Reset downX/downY so subsequent deltas are relative to current position
                        downX = x
                        downY = y
                        statusText.text = "Dragging…"
                    }

                    if (isDragging) {
                        // Send relative movement
                        val sensitivity = prefs.bridgeSensitivity
                        val scaledDx = dx * sensitivity
                        val scaledDy = dy * sensitivity
                        if (kotlin.math.abs(scaledDx) > 0.1f || kotlin.math.abs(scaledDy) > 0.1f) {
                            sendMouseMove(scaledDx, scaledDy)
                        }
                        // Update reference point
                        downX = x
                        downY = y
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                val duration = event.eventTime - downTime

                if (!isDragging && !longPressFired && totalMovement < TAP_THRESHOLD_PX) {
                    // This was a tap → left click
                    sendMouseButton(MouseButton.LEFT)
                    vibrateShort()
                }

                isDragging = false
                longPressFired = false
                isTwoFingerScroll = false
                statusText.text = "Trackpad active — drag to move, tap to click, long-press for right-click, two-finger to scroll"
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (pointerCount <= 2) {
                    isTwoFingerScroll = false
                }
            }
        }
    }

    // ── Send helpers ────────────────────────────────────────────────────────

    private fun sendMouseMove(dx: Float, dy: Float) {
        val event = InputEvent.MouseMove(dx = dx, dy = dy)
        val packet = packetFactory.fromEvent(event) ?: return
        scope.launch { udpTransport?.send(packet) }
    }

    private fun sendMouseButton(button: MouseButton) {
        val down = InputEvent.MouseButtonDown(button = button)
        val up = InputEvent.MouseButtonUp(button = button)
        val p1 = packetFactory.fromEvent(down) ?: return
        val p2 = packetFactory.fromEvent(up) ?: return
        scope.launch {
            udpTransport?.send(p1)
            udpTransport?.send(p2)
        }
    }

    private fun sendScroll(dx: Float, dy: Float) {
        val event = InputEvent.Scroll(dx = dx, dy = dy)
        val packet = packetFactory.fromEvent(event) ?: return
        scope.launch { udpTransport?.send(packet) }
    }

    private fun vibrateShort() {
        @Suppress("DEPRECATION")
        val vibrator = getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator
        vibrator?.vibrate(android.os.VibrationEffect.createOneShot(20, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
    }

    // ── Transport ───────────────────────────────────────────────────────────

    @SuppressLint("SetTextI18n")
    private fun connectTransport() {
        val targetIp = prefs.targetIp
        val port = prefs.port

        if (targetIp.isBlank()) {
            Toast.makeText(this, "Set receiver IP in Settings first", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        statusText.text = "Connecting to $targetIp:$port…"

        scope.launch {
            val config = TransportConfig(targetIp = targetIp, port = port)
            val transport = UdpTransport(config, isSender = true)
            val ok = transport.connect()
            if (ok) {
                udpTransport = transport
                BridgeLogger.i(TAG, "UDP transport connected → $targetIp:$port")
                withContext(Dispatchers.Main) {
                    statusText.text = "Trackpad active — drag to move, tap to click, long-press for right-click, two-finger to scroll"
                }
            } else {
                BridgeLogger.e(TAG, "UDP transport failed to connect")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MouseTrackpadActivity, "Cannot connect to receiver", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }
}
