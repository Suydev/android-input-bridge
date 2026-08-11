package com.inputbridge.bridge.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
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
 * for the remote tablet — like the Play Store "Bluetooth Keyboard & Mouse" apps.
 *
 * Touch mapping (absolute positioning):
 * - Touch down at (x,y) → cursor jumps to mapped position on tablet
 * - Finger drag → smooth cursor movement via MOUSE_MOVE deltas
 * - Quick tap → left click at the exact touch position
 * - Long press → right click at the touch position
 * - Two-finger vertical drag → scroll
 *
 * UI:
 * - Close button (top-right)
 * - Status bar (bottom) with connection state and error messages
 * - Sensitivity slider (bottom-left) to adjust mouse speed
 * - Cursor size control hint in status text
 */
class MouseTrackpadActivity : ComponentActivity() {

    private val prefs: BridgePreferences by inject()
    private val packetFactory = EventPacketFactory()

    @Volatile private var udpTransport: UdpTransport? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Screen dimensions ──────────────────────────────────────────────────
    private var phoneWidth = 1080f
    private var phoneHeight = 1920f

    // ── Touch tracking ──────────────────────────────────────────────────────
    private var lastX = 0f
    private var lastY = 0f
    private var totalMovement = 0f
    private var downTime = 0L
    private var isDragging = false
    private var longPressFired = false

    // ── Two-finger scroll tracking ──────────────────────────────────────────
    private var isTwoFingerScroll = false
    private var lastScrollY = 0f

    // ── Timing constants ────────────────────────────────────────────────────
    private companion object {
        const val TAP_THRESHOLD_PX = 15f
        const val LONG_PRESS_DURATION_MS = 500L
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
    private lateinit var errorText: TextView
    private lateinit var sensitivityBar: SeekBar
    private lateinit var sensitivityLabel: TextView

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }

        // Get phone screen size
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        phoneWidth = metrics.widthPixels.toFloat()
        phoneHeight = metrics.heightPixels.toFloat()
        BridgeLogger.i(TAG, "Phone screen: ${phoneWidth.toInt()}x${phoneHeight.toInt()}")

        val density = resources.displayMetrics.density
        val dp = { px: Int -> (px * density).toInt() }

        val root = FrameLayout(this).apply {
            setBackgroundColor(0x30000000.toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // ── Close button (top-right) ───────────────────────────────────────
        val closeButton = TextView(this).apply {
            text = "X"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 20f
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setOnClickListener { finish() }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP or Gravity.END }
        }
        root.addView(closeButton)

        // ── Connection indicator (top-left) ─────────────────────────────────
        val connectionDot = View(this).apply {
            setBackgroundColor(0xFFFF4444.toInt())  // red = disconnected
            layoutParams = FrameLayout.LayoutParams(dp(12), dp(12)).apply {
                gravity = Gravity.TOP or Gravity.START
                topMargin = dp(22)
                marginStart = dp(16)
            }
        }
        root.addView(connectionDot)

        val connectionLabel = TextView(this).apply {
            text = "Disconnected"
            setTextColor(0xAAFFFFFF.toInt())
            textSize = 11f
            setPadding(dp(8), dp(18), 0, 0)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP or Gravity.START; marginStart = dp(34) }
        }
        root.addView(connectionLabel)

        // ── Bottom panel ────────────────────────────────────────────────────
        val bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(12))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM }
        }

        // Error text (red, shows failures)
        errorText = TextView(this).apply {
            setTextColor(0xFFFF6666.toInt())
            textSize = 11f
            visibility = View.GONE
            setPadding(0, 0, 0, dp(4))
        }
        bottomPanel.addView(errorText)

        // Sensitivity slider row
        val sliderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }

        sensitivityLabel = TextView(this).apply {
            text = "Speed: 1.0x"
            setTextColor(0xBBFFFFFF.toInt())
            textSize = 11f
            minWidth = dp(80)
        }
        sliderRow.addView(sensitivityLabel)

        sensitivityBar = SeekBar(this).apply {
            max = 40  // 0.1x to 5.0x in steps of 0.1
            progress = ((prefs.bridgeSensitivity - 0.1f) * 10f).toInt().coerceIn(0, 40)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    val sensitivity = 0.1f + progress * 0.1f
                    sensitivityLabel.text = "Speed: %.1fx".format(sensitivity)
                    if (fromUser) prefs.bridgeSensitivity = sensitivity
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        sliderRow.addView(sensitivityBar)
        bottomPanel.addView(sliderRow)

        // Status text (instructions + connection state)
        statusText = TextView(this).apply {
            text = "Tap=Click | Drag=Move | Hold=RightClick | 2-Finger=Scroll"
            setTextColor(0x99FFFFFF.toInt())
            textSize = 10f
        }
        bottomPanel.addView(statusText)

        root.addView(bottomPanel)

        // ── Trackpad touch area (fills remaining space) ─────────────────────
        trackpadView = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(trackpadView)

        setContentView(root)
        trackpadView.setOnTouchListener { _, event -> handleTouch(event); true }

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

    // ── Error display ──────────────────────────────────────────────────────

    private fun showError(msg: String) {
        errorText.text = msg
        errorText.visibility = View.VISIBLE
        handler.postDelayed({ errorText.visibility = View.GONE }, 5000)
    }

    private fun updateConnectionState(connected: Boolean, label: String) {
        val dot = findViewById<View>(0x7f0a0001) // will be set below
        // Use tag-based approach instead
        statusText.text = label
    }

    // ── Touch handling ──────────────────────────────────────────────────────

    @SuppressLint("SetTextI18n")
    private fun handleTouch(event: MotionEvent) {
        val transport = udpTransport
        if (transport == null || !transport.isConnected) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                showError("Not connected — reconnecting...")
                connectTransport()
            }
            return
        }

        val pointerCount = event.pointerCount

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.getX(0)
                val y = event.getY(0)
                lastX = x
                lastY = y
                totalMovement = 0f
                downTime = event.eventTime
                isDragging = false
                longPressFired = false
                isTwoFingerScroll = false

                // ABSOLUTE POSITIONING: jump cursor to exact touch position on tablet
                sendCursorGoto(x, y)

                handler.postDelayed(longPressRunnable, LONG_PRESS_DURATION_MS)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (pointerCount >= 2) {
                    isTwoFingerScroll = true
                    lastScrollY = event.getY(1)
                    handler.removeCallbacks(longPressRunnable)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (isTwoFingerScroll && pointerCount >= 2) {
                    val currentY = event.getY(1)
                    val deltaY = currentY - lastScrollY
                    lastScrollY = currentY
                    if (kotlin.math.abs(deltaY) > 1f) {
                        sendScroll(deltaY / 10f)
                    }
                } else if (pointerCount == 1) {
                    val x = event.getX(0)
                    val y = event.getY(0)
                    val dx = x - lastX
                    val dy = y - lastY
                    totalMovement += kotlin.math.sqrt(dx * dx + dy * dy)

                    if (totalMovement > TAP_THRESHOLD_PX && !isDragging) {
                        isDragging = true
                        handler.removeCallbacks(longPressRunnable)
                        statusText.text = "Dragging..."
                    }

                    if (isDragging) {
                        val sensitivity = prefs.bridgeSensitivity
                        sendMouseMove(dx * sensitivity, dy * sensitivity)
                    }

                    lastX = x
                    lastY = y
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)

                if (!isDragging && !longPressFired && totalMovement < TAP_THRESHOLD_PX) {
                    // TAP -> left click
                    sendMouseButton(MouseButton.LEFT)
                    vibrateShort()
                }

                isDragging = false
                longPressFired = false
                isTwoFingerScroll = false
                statusText.text = "Tap=Click | Drag=Move | Hold=RightClick | 2-Finger=Scroll"
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (pointerCount <= 2) isTwoFingerScroll = false
            }
        }
    }

    // ── Send helpers ────────────────────────────────────────────────────────

    private fun sendCursorGoto(touchX: Float, touchY: Float) {
        val normX = (touchX / phoneWidth).coerceIn(0f, 1f)
        val normY = (touchY / phoneHeight).coerceIn(0f, 1f)
        val event = InputEvent.CursorGoto(x = normX, y = normY)
        val packet = packetFactory.fromEvent(event) ?: return
        scope.launch {
            val sent = udpTransport?.send(packet) ?: false
            if (!sent && udpTransport == null) {
                withContext(Dispatchers.Main) { showError("Connection lost") }
            }
        }
    }

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
        val transport = udpTransport ?: return
        scope.launch {
            transport.send(p1)
            transport.send(p2)
        }
    }

    private fun sendScroll(dy: Float) {
        val event = InputEvent.Scroll(dx = 0f, dy = dy)
        val packet = packetFactory.fromEvent(event) ?: return
        scope.launch { udpTransport?.send(packet) }
    }

    private fun vibrateShort() {
        try {
            @Suppress("DEPRECATION")
            val vibrator = getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator
            vibrator?.vibrate(android.os.VibrationEffect.createOneShot(20, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: SecurityException) { }
    }

    // ── Transport ───────────────────────────────────────────────────────────

    @SuppressLint("SetTextI18n")
    private fun connectTransport() {
        val targetIp = prefs.targetIp
        val port = prefs.port

        if (targetIp.isBlank()) {
            showError("Set receiver IP in Settings first")
            Toast.makeText(this, "Set receiver IP in Settings first", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        statusText.text = "Connecting to $targetIp:$port..."

        scope.launch {
            val config = TransportConfig(targetIp = targetIp, port = port)
            val transport = UdpTransport(config, isSender = true)
            val ok = transport.connect()
            if (ok) {
                udpTransport = transport
                BridgeLogger.i(TAG, "UDP transport connected -> $targetIp:$port")
                withContext(Dispatchers.Main) {
                    statusText.text = "Tap=Click | Drag=Move | Hold=RightClick | 2-Finger=Scroll"
                }
            } else {
                BridgeLogger.e(TAG, "UDP transport failed to connect")
                withContext(Dispatchers.Main) {
                    showError("Cannot connect to $targetIp:$port — check receiver is running")
                    Toast.makeText(this@MouseTrackpadActivity, "Cannot connect to receiver", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }
}
