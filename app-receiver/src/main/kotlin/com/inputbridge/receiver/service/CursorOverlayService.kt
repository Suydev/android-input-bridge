package com.inputbridge.receiver.service

import android.app.Service
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.annotation.RequiresApi
import com.inputbridge.accessibility.AccessibilityCommandBus
import com.inputbridge.core.logging.BridgeLogger
import com.inputbridge.diagnostics.DiagnosticsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "CursorOverlayService"
private const val DOT_SIZE_DP = 28

/**
 * Phase 7 — Visual cursor dot overlay.
 *
 * Draws a floating crosshair/dot at the current virtual cursor position so the
 * user can see where the next mouse click will land in Accessibility mode.
 *
 * Requires SYSTEM_ALERT_WINDOW permission (canDrawOverlays()). If absent, this
 * service exits immediately rather than crashing.
 *
 * The dot is non-interactive: FLAG_NOT_FOCUSABLE + FLAG_NOT_TOUCHABLE, so it
 * never interferes with touch input or accessibility injection.
 *
 * Lifecycle:
 * - Started by ReceiverService when showCursorOverlay pref is true.
 * - Stopped by ReceiverService on service destroy.
 * - Self-stops if canDrawOverlays() returns false at start time.
 *
 * Position source: [AccessibilityCommandBus.cursorPosition] StateFlow,
 * which is updated on every MouseMove event in the command bus dispatch loop.
 * Collection runs on Dispatchers.Main so WindowManager updates are always
 * on the correct thread.
 */
@RequiresApi(Build.VERSION_CODES.N)
class CursorOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: CursorDotView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            BridgeLogger.w(TAG, "canDrawOverlays() is false — stopping overlay service")
            stopSelf()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val dotPx = (DOT_SIZE_DP * resources.displayMetrics.density).toInt()

        val params = WindowManager.LayoutParams(
            dotPx, dotPx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        layoutParams = params

        val dot = CursorDotView(this)
        overlayView = dot

        runCatching {
            windowManager.addView(dot, params)
        }.onFailure { e ->
            BridgeLogger.e(TAG, "Failed to add overlay view: ${e.message}")
            stopSelf()
            return
        }

        BridgeLogger.i(TAG, "Cursor overlay created (dot=${dotPx}px)")
        DiagnosticsManager.update { copy(cursorOverlayActive = true) }

        // Observe cursor position updates from the command bus.
        serviceScope.launch {
            AccessibilityCommandBus.cursorPosition.collect { (x, y) ->
                updatePosition(x, y, dotPx)
            }
        }
    }

    private fun updatePosition(x: Float, y: Float, dotPx: Int) {
        val params = layoutParams ?: return
        val view   = overlayView  ?: return
        // Centre the dot on the cursor position.
        params.x = (x - dotPx / 2f).toInt().coerceAtLeast(0)
        params.y = (y - dotPx / 2f).toInt().coerceAtLeast(0)
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        overlayView?.let { v -> runCatching { windowManager.removeView(v) } }
        overlayView = null
        DiagnosticsManager.update { copy(cursorOverlayActive = false) }
        BridgeLogger.i(TAG, "Cursor overlay removed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.inputbridge.receiver.STOP_CURSOR_OVERLAY"
    }
}

/**
 * Simple custom View that draws a semi-transparent green dot with a dark border
 * and short crosshair lines, giving a precise click-target indicator.
 */
private class CursorDotView(context: android.content.Context) : View(context) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC00FF88")   // green, 80% opacity
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99000000")   // dark border, 60% opacity
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val hairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#BB000000")   // crosshair lines
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r  = minOf(cx, cy) * 0.55f     // dot radius = 55% of half-size

        // Dot
        canvas.drawCircle(cx, cy, r, fillPaint)
        canvas.drawCircle(cx, cy, r, borderPaint)

        // Crosshair: lines from outside the dot to the edges
        val gap   = r + 2f
        val reach = minOf(cx, cy) - 2f
        canvas.drawLine(cx - reach, cy, cx - gap, cy, hairPaint)   // left
        canvas.drawLine(cx + gap,   cy, cx + reach, cy, hairPaint) // right
        canvas.drawLine(cx, cy - reach, cx, cy - gap, hairPaint)   // up
        canvas.drawLine(cx, cy + gap,   cx, cy + reach, hairPaint) // down
    }
}
