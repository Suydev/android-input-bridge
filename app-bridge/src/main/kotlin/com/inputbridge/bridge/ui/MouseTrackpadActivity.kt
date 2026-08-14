package com.inputbridge.bridge.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.inputbridge.bridge.prefs.BridgePreferences
import com.inputbridge.core.config.TransportConfig
import com.inputbridge.core.logging.BridgeLogger
import com.inputbridge.core.model.InputEvent
import com.inputbridge.core.model.MouseButton
import com.inputbridge.protocol.EventPacketFactory
import com.inputbridge.transport.wifi.UdpTransport
import kotlinx.coroutines.*
import org.koin.android.ext.android.inject
import kotlin.math.PI
import kotlin.math.sqrt

private const val TAG = "MouseTrackpad"

/**
 * Transparent fullscreen overlay that turns the phone's touchscreen into a trackpad
 * for the remote tablet — like the Play Store "Bluetooth Keyboard & Mouse" apps.
 *
 * Latency optimizations (Session 026):
 * - requestUnbufferedDispatch(): eliminates vsync batching (saves 4-8ms)
 * - Historical sample processing: uses ALL touch samples per frame (saves 2-4ms)
 * - 1€ filter: adaptive smoothing (jitter removal without lag)
 * - AOSP acceleration curve: piecewise-linear gain for fast swipes
 * - Direct send: bypasses coroutine dispatch for mouse moves
 * - Kernel timestamps: MotionEvent.eventTimeNanos for accurate timing
 */
class MouseTrackpadActivity : ComponentActivity() {

    private val prefs: BridgePreferences by inject()
    private val packetFactory = EventPacketFactory()

    @Volatile private var udpTransport: UdpTransport? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, t ->
        if (t !is CancellationException) {
            BridgeLogger.e(TAG, "Uncaught exception in MouseTrackpadActivity", t)
        }
    })

    // ── Screen dimensions ──────────────────────────────────────────────────
    private var phoneWidth = 1080f
    private var phoneHeight = 1920f
    private var phoneDpi = 440

    // ── Touch tracking (trackpad area only) ────────────────────────────────
    private var lastX = 0f
    private var lastY = 0f
    private var totalMovement = 0f
    private var downTime = 0L
    private var isDragging = false
    private var longPressFired = false

    // ── Two-finger scroll tracking (trackpad area only) ────────────────────
    private var isTwoFingerScroll = false
    private var lastScrollY = 0f

    // ── Scroll zone tracking ───────────────────────────────────────────────
    private var scrollLastRawY = 0f

    // ── 1€ filter state ────────────────────────────────────────────────────
    private var filterPrevX = 0f
    private var filterPrevFilteredX = 0f
    private var filterPrevY = 0f
    private var filterPrevFilteredY = 0f
    private var filterPrevTimeNs = 0L

    // ── Acceleration state ─────────────────────────────────────────────────
    private var lastMoveTimeNs = 0L
    private var velocityX = 0f
    private var velocityY = 0f

    // ── Connection state ───────────────────────────────────────────────────
    @Volatile private var isConnecting = false

    // ── Timing constants ────────────────────────────────────────────────────
    private companion object {
        const val TAP_THRESHOLD_PX = 15f
        const val LONG_PRESS_DURATION_MS = 500L
        const val SCROLL_DELTA_PX = 10f
        const val BUTTON_HEIGHT_DP = 56
        const val SCROLL_ZONE_WIDTH_DP = 36

        // 1€ filter parameters
        const val FILTER_MIN_CUTOFF = 1.0f   // Hz — lower = less jitter, more lag
        const val FILTER_BETA = 0.007f       // higher = less lag at high speed
        const val FILTER_D_CUTOFF = 1.0f     // Hz — derivative filter cutoff

        // AOSP acceleration segments (piecewise-linear gain)
        val ACCEL_MAX_SPEED = floatArrayOf(8f, 25f, 80f, Float.MAX_VALUE)
        val ACCEL_SLOPE = floatArrayOf(1.0f, 1.8f, 2.5f, 3.0f)
        val ACCEL_INTERCEPT = floatArrayOf(0f, -6.4f, -23.9f, -63.9f)
        const val ACCEL_MIN_GAIN = 0.5f
        const val ACCEL_MAX_GAIN = 4.0f
    }

    private val handler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        if (!isDragging && !longPressFired && totalMovement < TAP_THRESHOLD_PX) {
            longPressFired = true
            sendMouseButton(MouseButton.RIGHT)
            vibrateShort()
        }
    }

    // ── UI references ──────────────────────────────────────────────────────
    private lateinit var trackpadView: View
    private lateinit var leftClickBtn: View
    private lateinit var rightClickBtn: View
    private lateinit var scrollZone: View
    private lateinit var statusText: TextView
    private lateinit var errorText: TextView
    private lateinit var sensitivityBar: SeekBar
    private lateinit var sensitivityLabel: TextView
    private lateinit var connectionDot: View
    private lateinit var connectionLabel: TextView

    // ── Transport check helper ─────────────────────────────────────────────
    private fun ensureConnected(): Boolean {
        val transport = udpTransport
        if (transport == null || !transport.isConnected) {
            if (!isConnecting) {
                showError("Not connected — reconnecting...")
                connectTransport()
            }
            return false
        }
        return true
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 1€ Filter — adaptive jitter removal with low latency
    // ═══════════════════════════════════════════════════════════════════════

    private fun applyFilter(x: Float, y: Float, timestampNs: Long): Pair<Float, Float> {
        if (filterPrevTimeNs == 0L) {
            filterPrevTimeNs = timestampNs
            filterPrevX = x; filterPrevFilteredX = x
            filterPrevY = y; filterPrevFilteredY = y
            return x to y
        }

        val dt = ((timestampNs - filterPrevTimeNs) / 1_000_000_000f).coerceAtLeast(0.001f)
        filterPrevTimeNs = timestampNs

        val dx = x - filterPrevX
        val dy = y - filterPrevY
        val speed = sqrt(dx * dx + dy * dy) / dt

        val cutoff = FILTER_MIN_CUTOFF + FILTER_BETA * speed
        val alpha = 1.0f / (1.0f + 1.0f / (2.0f * PI.toFloat() * cutoff * dt))

        val filteredX = alpha * x + (1f - alpha) * filterPrevFilteredX
        val filteredY = alpha * y + (1f - alpha) * filterPrevFilteredY

        filterPrevX = x; filterPrevFilteredX = filteredX
        filterPrevY = y; filterPrevFilteredY = filteredY

        return filteredX to filteredY
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Acceleration — AOSP piecewise-linear gain curve
    // ═══════════════════════════════════════════════════════════════════════

    private fun computeAcceleration(dx: Float, dy: Float, nowNs: Long): Pair<Float, Float> {
        if (lastMoveTimeNs == 0L) {
            lastMoveTimeNs = nowNs
            return dx to dy
        }
        val dtSec = (nowNs - lastMoveTimeNs) / 1_000_000_000f
        lastMoveTimeNs = nowNs
        if (dtSec <= 0f || dtSec > 0.1f) return dx to dy

        val rawVx = dx / dtSec
        val rawVy = dy / dtSec
        velocityX += (rawVx - velocityX) * 0.3f
        velocityY += (rawVy - velocityY) * 0.3f

        val speed = sqrt(velocityX * velocityX + velocityY * velocityY)

        // Find the segment
        var gain = ACCEL_SLOPE[0]
        var intercept = ACCEL_INTERCEPT[0]
        for (i in ACCEL_MAX_SPEED.indices) {
            if (speed <= ACCEL_MAX_SPEED[i]) {
                gain = ACCEL_SLOPE[i]
                intercept = ACCEL_INTERCEPT[i]
                break
            }
        }

        // gain = slope + intercept / speed (Android AOSP form)
        val computedGain = if (speed > 0.001f) {
            (gain + intercept / speed).coerceIn(ACCEL_MIN_GAIN, ACCEL_MAX_GAIN)
        } else 1.0f

        return dx * computedGain to dy * computedGain
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Layout construction — ConstraintLayout with distinct touch zones
    // ═══════════════════════════════════════════════════════════════════════

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

        val wm = getSystemService(WINDOW_SERVICE) as? WindowManager
            ?: throw IllegalStateException("WindowManager unavailable")
        @Suppress("DEPRECATION")
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        phoneWidth = metrics.widthPixels.toFloat()
        phoneHeight = metrics.heightPixels.toFloat()
        phoneDpi = metrics.densityDpi
        BridgeLogger.i(TAG, "Phone screen: ${phoneWidth.toInt()}x${phoneHeight.toInt()} dpi=$phoneDpi")

        val density = resources.displayMetrics.density
        val dp = { px: Int -> (px * density).toInt() }

        // ── Root: ConstraintLayout ─────────────────────────────────────────
        val root = ConstraintLayout(this).apply {
            setBackgroundColor(0x30000000.toInt())
            layoutParams = ConstraintLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }

        // ── Close button (top-right) ───────────────────────────────────────
        val closeButton = TextView(this).apply {
            id = View.generateViewId()
            text = "✕"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 20f
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setOnClickListener { finish() }
        }
        root.addView(closeButton)

        // ── Connection indicator (top-left) — STORED as class fields ──────
        connectionDot = View(this).apply {
            id = View.generateViewId()
            setBackgroundColor(0xFFFF4444.toInt())
        }
        root.addView(connectionDot)

        connectionLabel = TextView(this).apply {
            id = View.generateViewId()
            text = "Disconnected"
            setTextColor(0xAAFFFFFF.toInt())
            textSize = 11f
            setPadding(dp(8), dp(18), 0, 0)
        }
        root.addView(connectionLabel)

        // ── Bottom panel (error + sensitivity slider + status) ─────────────
        val bottomPanel = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(12))
        }
        root.addView(bottomPanel)

        errorText = TextView(this).apply {
            setTextColor(0xFFFF6666.toInt())
            textSize = 11f
            visibility = View.GONE
            setPadding(0, 0, 0, dp(4))
        }
        bottomPanel.addView(errorText)

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
            max = 40
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

        statusText = TextView(this).apply {
            text = "Drag=Move | Tap=Click | Hold=Right | 2F=Scroll | [L] [R]"
            setTextColor(0x99FFFFFF.toInt())
            textSize = 10f
        }
        bottomPanel.addView(statusText)

        // ── Left click button (bottom-left zone) ───────────────────────────
        leftClickBtn = TextView(this).apply {
            id = View.generateViewId()
            text = "L"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            setBackgroundColor(0x40FFFFFF.toInt())
            setOnTouchListener(::onLeftClickTouch)
        }
        root.addView(leftClickBtn)

        // ── Right click button (bottom-right zone) ─────────────────────────
        rightClickBtn = TextView(this).apply {
            id = View.generateViewId()
            text = "R"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            setBackgroundColor(0x40FFFFFF.toInt())
            setOnTouchListener(::onRightClickTouch)
        }
        root.addView(rightClickBtn)

        // ── Scroll zone (right edge, vertical strip) ───────────────────────
        scrollZone = View(this).apply {
            id = View.generateViewId()
            setBackgroundColor(0x25FFFFFF.toInt())
            setOnTouchListener(::onScrollZoneTouch)
        }
        root.addView(scrollZone)

        // ── Trackpad touch area (center, between button zones) ─────────────
        trackpadView = View(this).apply {
            id = View.generateViewId()
        }
        root.addView(trackpadView)

        // ═══════════════════════════════════════════════════════════════════
        // ConstraintSet
        // ═══════════════════════════════════════════════════════════════════
        val cs = ConstraintSet()
        cs.clone(root)

        cs.connect(closeButton.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, dp(8))
        cs.connect(closeButton.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, dp(8))
        cs.constrainWidth(closeButton.id, WRAP_CONTENT)
        cs.constrainHeight(closeButton.id, WRAP_CONTENT)

        cs.connect(connectionDot.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, dp(16))
        cs.connect(connectionDot.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, dp(22))
        cs.constrainWidth(connectionDot.id, dp(12))
        cs.constrainHeight(connectionDot.id, dp(12))

        cs.connect(connectionLabel.id, ConstraintSet.START, connectionDot.id, ConstraintSet.END, dp(8))
        cs.connect(connectionLabel.id, ConstraintSet.TOP, connectionDot.id, ConstraintSet.TOP, dp(-6))
        cs.constrainWidth(connectionLabel.id, WRAP_CONTENT)
        cs.constrainHeight(connectionLabel.id, WRAP_CONTENT)

        cs.connect(bottomPanel.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
        cs.connect(bottomPanel.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
        cs.connect(bottomPanel.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
        cs.constrainWidth(bottomPanel.id, 0)
        cs.constrainHeight(bottomPanel.id, WRAP_CONTENT)

        val btnH = dp(BUTTON_HEIGHT_DP)
        cs.connect(leftClickBtn.id, ConstraintSet.BOTTOM, bottomPanel.id, ConstraintSet.TOP)
        cs.constrainWidth(leftClickBtn.id, 0)
        cs.constrainHeight(leftClickBtn.id, btnH)

        cs.connect(rightClickBtn.id, ConstraintSet.BOTTOM, bottomPanel.id, ConstraintSet.TOP)
        cs.constrainWidth(rightClickBtn.id, 0)
        cs.constrainHeight(rightClickBtn.id, btnH)

        // Add horizontal constraints before creating the chain
        cs.connect(leftClickBtn.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
        cs.connect(rightClickBtn.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)

        cs.createHorizontalChain(
            ConstraintSet.PARENT_ID, ConstraintSet.START,
            ConstraintSet.PARENT_ID, ConstraintSet.END,
            intArrayOf(leftClickBtn.id, rightClickBtn.id),
            null,
            ConstraintSet.CHAIN_SPREAD
        )

        cs.connect(scrollZone.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
        cs.connect(scrollZone.id, ConstraintSet.TOP, connectionDot.id, ConstraintSet.BOTTOM, dp(16))
        cs.connect(scrollZone.id, ConstraintSet.BOTTOM, rightClickBtn.id, ConstraintSet.TOP)
        cs.constrainWidth(scrollZone.id, dp(SCROLL_ZONE_WIDTH_DP))
        cs.constrainHeight(scrollZone.id, 0)

        cs.connect(trackpadView.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
        cs.connect(trackpadView.id, ConstraintSet.END, scrollZone.id, ConstraintSet.START)
        cs.connect(trackpadView.id, ConstraintSet.TOP, connectionDot.id, ConstraintSet.BOTTOM, dp(16))
        cs.connect(trackpadView.id, ConstraintSet.BOTTOM, leftClickBtn.id, ConstraintSet.TOP)
        cs.constrainWidth(trackpadView.id, 0)
        cs.constrainHeight(trackpadView.id, 0)

        cs.applyTo(root)
        setContentView(root)

        // ── Wire up touch listeners with requestUnbufferedDispatch ─────────
        trackpadView.setOnTouchListener { v, event ->
            v.requestUnbufferedDispatch(event)
            handleTrackpadTouch(event)
            true
        }

        connectTransport()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Touch handlers
    // ═══════════════════════════════════════════════════════════════════════

    @SuppressLint("ClickableViewAccessibility")
    private fun onLeftClickTouch(v: View, event: MotionEvent): Boolean {
        if (!ensureConnected()) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sendMouseButton(MouseButton.LEFT)
                vibrateShort()
                v.setBackgroundColor(0x80FFFFFF.toInt())
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.setBackgroundColor(0x40FFFFFF.toInt())
            }
        }
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun onRightClickTouch(v: View, event: MotionEvent): Boolean {
        if (!ensureConnected()) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sendMouseButton(MouseButton.RIGHT)
                vibrateShort()
                v.setBackgroundColor(0x80FFFFFF.toInt())
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.setBackgroundColor(0x40FFFFFF.toInt())
            }
        }
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun onScrollZoneTouch(v: View, event: MotionEvent): Boolean {
        if (!ensureConnected()) return true
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scrollLastRawY = event.rawY
                v.setBackgroundColor(0x50FFFFFF.toInt())
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = event.rawY - scrollLastRawY
                if (kotlin.math.abs(dy) > SCROLL_DELTA_PX) {
                    sendScroll(-dy / 10f)
                    scrollLastRawY = event.rawY
                }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.setBackgroundColor(0x25FFFFFF.toInt())
                true
            }
            else -> false
        }
    }

    /**
     * Trackpad touch handler — processes ALL historical samples for maximum resolution.
     * Single finger: drag = cursor movement, tap = left click, hold = right click
     * Two fingers: vertical drag = scroll
     */
    @SuppressLint("SetTextI18n")
    private fun handleTrackpadTouch(event: MotionEvent) {
        if (!ensureConnected()) return

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
                lastMoveTimeNs = 0L
                velocityX = 0f; velocityY = 0f
                filterPrevTimeNs = 0L

                // BUG-103 FIX: normalize against the trackpad view's own bounds, not the
                // full phone screen. The trackpad excludes the top status row, the bottom
                // L/R button + slider panel, and the right scroll zone, so using phone
                // dimensions capped the tablet cursor short of the OnePlus Pad Go edges.
                // Touch coords (x, y) are already relative to trackpadView.
                val tw = trackpadView.width.coerceAtLeast(1)
                val th = trackpadView.height.coerceAtLeast(1)
                sendCursorGoto(x / tw, y / th)

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
                    val sensitivity = prefs.bridgeSensitivity

                    // Process ALL historical samples — eliminates 50-75% sample loss
                    val historySize = event.historySize
                    for (h in 0 until historySize) {
                        val hx = event.getHistoricalX(0, h)
                        val hy = event.getHistoricalY(0, h)
                        val hdx = hx - lastX
                        val hdy = hy - lastY
                        totalMovement += sqrt(hdx * hdx + hdy * hdy)

                        if (totalMovement > TAP_THRESHOLD_PX && !isDragging) {
                            isDragging = true
                            handler.removeCallbacks(longPressRunnable)
                            statusText.text = "Dragging..."
                        }
                        if (isDragging) {
                            val ts = event.getHistoricalEventTimeNanos(h)
                            val (accelDx, accelDy) = computeAcceleration(hdx, hdy, ts)
                            val (filtDx, filtDy) = applyFilter(accelDx, accelDy, ts)
                            sendMouseMove(filtDx * sensitivity, filtDy * sensitivity)
                        }
                        lastX = hx; lastY = hy
                    }

                    // Process current sample
                    val x = event.getX(0)
                    val y = event.getY(0)
                    val dx = x - lastX
                    val dy = y - lastY
                    totalMovement += sqrt(dx * dx + dy * dy)

                    if (totalMovement > TAP_THRESHOLD_PX && !isDragging) {
                        isDragging = true
                        handler.removeCallbacks(longPressRunnable)
                        statusText.text = "Dragging..."
                    }
                    if (isDragging) {
                        val ts = event.eventTimeNanos
                        val (accelDx, accelDy) = computeAcceleration(dx, dy, ts)
                        val (filtDx, filtDy) = applyFilter(accelDx, accelDy, ts)
                        sendMouseMove(filtDx * sensitivity, filtDy * sensitivity)
                    }
                    lastX = x; lastY = y
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)

                if (!isDragging && !longPressFired && totalMovement < TAP_THRESHOLD_PX) {
                    sendMouseButton(MouseButton.LEFT)
                    vibrateShort()
                }

                isDragging = false
                longPressFired = false
                isTwoFingerScroll = false
                lastMoveTimeNs = 0L
                statusText.text = "Drag=Move | Tap=Click | Hold=Right | 2F=Scroll | [L] [R]"
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (pointerCount <= 2) isTwoFingerScroll = false
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Send helpers
    // ═══════════════════════════════════════════════════════════════════════

    private fun sendCursorGoto(normX: Float, normY: Float) {
        val event = InputEvent.CursorGoto(x = normX, y = normY)
        val packet = packetFactory.fromEvent(event) ?: return
        val transport = udpTransport ?: return
        // BUG-104: absolute lowest-latency send — serializes and calls socket.send()
        // synchronously on this (Main) thread, no channel + send-loop dispatch hop.
        val sent = runCatching { transport.sendDirect(packet) }.getOrDefault(false)
        if (!sent) {
            showError("Connection lost")
        }
    }

    /**
     * Direct send for mouse moves — no coroutine dispatch overhead.
     * UDP send on localhost WiFi is ~0.01ms, well under 16ms frame budget.
     */
    private fun sendMouseMove(dx: Float, dy: Float) {
        val event = InputEvent.MouseMove(dx = dx, dy = dy)
        val packet = packetFactory.fromEvent(event) ?: return
        val transport = udpTransport ?: return
        // BUG-104: socket.send() straight from the touch thread — no channel hop.
        runCatching { transport.sendDirect(packet) }
    }

    private fun sendMouseButton(button: MouseButton) {
        val down = InputEvent.MouseButtonDown(button = button)
        val up = InputEvent.MouseButtonUp(button = button)
        val p1 = packetFactory.fromEvent(down) ?: return
        val p2 = packetFactory.fromEvent(up) ?: return
        val transport = udpTransport ?: return
        scope.launch {
            runCatching {
                transport.send(p1)
                transport.send(p2)
            }.onFailure { e ->
                BridgeLogger.e(TAG, "sendMouseButton failed: ${e.message}")
                withContext(Dispatchers.Main) { showError("Send failed: ${e.message}") }
            }
        }
    }

    private fun sendScroll(dy: Float) {
        val event = InputEvent.Scroll(dx = 0f, dy = dy)
        val packet = packetFactory.fromEvent(event) ?: return
        val transport = udpTransport ?: return
        scope.launch {
            runCatching { transport.send(packet) }
                .onFailure { BridgeLogger.e(TAG, "sendScroll failed: ${it.message}") }
        }
    }

    private fun vibrateShort() {
        try {
            @Suppress("DEPRECATION")
            val vibrator = getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator
            vibrator?.vibrate(
                android.os.VibrationEffect.createOneShot(
                    20,
                    android.os.VibrationEffect.DEFAULT_AMPLITUDE
                )
            )
        } catch (_: SecurityException) { }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Error display + connection management
    // ═══════════════════════════════════════════════════════════════════════

    private fun showError(msg: String) {
        errorText.text = msg
        errorText.visibility = View.VISIBLE
        handler.postDelayed({ errorText.visibility = View.GONE }, 5000)
    }

    private fun updateConnectionStatus(connected: Boolean, label: String) {
        connectionDot.setBackgroundColor(if (connected) 0xFF44FF44.toInt() else 0xFFFF4444.toInt())
        connectionLabel.text = label
    }

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

        isConnecting = true
        statusText.text = "Connecting to $targetIp:$port..."
        updateConnectionStatus(false, "Connecting...")

        scope.launch {
            val config = TransportConfig(targetIp = targetIp, port = port)
            val transport = UdpTransport(config, isSender = true)
            val ok = transport.connect()
            isConnecting = false
            if (ok) {
                udpTransport = transport
                BridgeLogger.i(TAG, "UDP transport connected -> $targetIp:$port")
                withContext(Dispatchers.Main) {
                    updateConnectionStatus(true, "Connected to $targetIp")
                    statusText.text = "Drag=Move | Tap=Click | Hold=Right | 2F=Scroll | [L] [R]"
                }
            } else {
                BridgeLogger.e(TAG, "UDP transport failed to connect")
                withContext(Dispatchers.Main) {
                    updateConnectionStatus(false, "Disconnected")
                    showError("Cannot connect to $targetIp:$port — check receiver is running")
                    Toast.makeText(
                        this@MouseTrackpadActivity,
                        "Cannot connect to receiver",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            }
        }
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
}
