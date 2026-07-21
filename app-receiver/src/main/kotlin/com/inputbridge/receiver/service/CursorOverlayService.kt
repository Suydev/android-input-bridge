package com.inputbridge.receiver.service

import android.app.Service
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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

/**
 * Draws a Windows-style arrow cursor overlay at the current virtual cursor position
 * so the user can see exactly where the next click will land in Accessibility mode.
 *
 * BUG-043 fix: replaced the green crosshair dot with a proper Windows arrow cursor.
 * The cursor is a classic top-left-pointing arrow with:
 *   - White fill with a thin black outline
 *   - Drop shadow for visibility on any background
 *   - Hotspot at the top-left TIP of the arrow (pixel 0,0 of the view)
 *
 * Hotspot correction: the old dot centred itself on the cursor position using an
 * offset of -(width/2, height/2). An arrow cursor's hotspot is at its tip (top-left),
 * so the overlay x/y now maps directly to the logical cursor position — no offset.
 *
 * Requires SYSTEM_ALERT_WINDOW permission (canDrawOverlays()). If absent this service
 * exits immediately rather than crashing.
 *
 * The view is FLAG_NOT_FOCUSABLE + FLAG_NOT_TOUCHABLE — it never interferes with
 * touch input or accessibility injection.
 *
 * Position source: [AccessibilityCommandBus.cursorPosition] StateFlow, which is
 * updated on the calling IO thread the instant a MouseMove packet arrives
 * (hot-path optimisation — no coroutine queue).
 * Collection runs on Dispatchers.Main so WindowManager updates are on the correct thread.
 */
@RequiresApi(Build.VERSION_CODES.N)
class CursorOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: CursorArrowView? = null
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

        // Arrow view: sized to contain the full cursor shape + shadow.
        // CursorArrowView.CURSOR_DP defines the logical width/height in dp.
        val density = resources.displayMetrics.density
        val viewPx = (CursorArrowView.CURSOR_DP * density).toInt()

        val params = WindowManager.LayoutParams(
            viewPx, viewPx,
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

        val view = CursorArrowView(this)
        overlayView = view

        runCatching {
            windowManager.addView(view, params)
        }.onFailure { e ->
            BridgeLogger.e(TAG, "Failed to add cursor overlay view: ${e.message}")
            stopSelf()
            return
        }

        BridgeLogger.i(TAG, "Cursor arrow overlay created (view=${viewPx}px)")
        DiagnosticsManager.update { copy(cursorOverlayActive = true) }

        // Observe cursor position and move the overlay view.
        serviceScope.launch {
            AccessibilityCommandBus.cursorPosition.collect { (x, y) ->
                updatePosition(x, y)
            }
        }
    }

    /**
     * Move the overlay so the arrow TIP lands exactly on (x, y).
     *
     * Because the hotspot is at the top-left corner of the view (the arrow tip is drawn
     * at (0,0) within the canvas), we position the view directly at the cursor coordinates
     * with no centering offset.
     */
    private fun updatePosition(x: Float, y: Float) {
        val params = layoutParams ?: return
        val view   = overlayView  ?: return
        params.x = x.toInt().coerceAtLeast(0)
        params.y = y.toInt().coerceAtLeast(0)
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        overlayView?.let { v -> runCatching { windowManager.removeView(v) } }
        overlayView = null
        DiagnosticsManager.update { copy(cursorOverlayActive = false) }
        BridgeLogger.i(TAG, "Cursor arrow overlay removed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.inputbridge.receiver.STOP_CURSOR_OVERLAY"
    }
}

/**
 * Custom View that draws a classic Windows-style arrow cursor.
 *
 * Shape (hotspot at top-left tip, all coordinates in canvas pixels):
 *
 *   Tip at (0, 0) — this is the logical cursor hotspot.
 *   Arrow goes:
 *     • Down the left edge (0 → tailTop)
 *     • Cuts inward to form the left side of the tail
 *     • Down the left tail wall to the bottom
 *     • Across the tail bottom
 *     • Up the right tail wall to the notch
 *     • Across to the right point of the arrow head
 *     • Diagonal back to tip (the characteristic arrow diagonal)
 *
 * The entire shape is drawn twice:
 *   1. Shadow pass: the path is drawn slightly larger in dark colour, offset 1dp
 *   2. Fill pass: white fill
 *   3. Stroke pass: thin black outline for sharpness on any background
 *
 * View is sized to CURSOR_DP × CURSOR_DP so the arrow fits with room for the shadow.
 */
private class CursorArrowView(context: android.content.Context) : View(context) {

    companion object {
        /** Size of the view in dp — must match what CursorOverlayService allocates. */
        const val CURSOR_DP = 36
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val arrowPath   = Path()
    private val shadowPath  = Path()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildPaths(w.toFloat(), h.toFloat())
    }

    /**
     * Build the arrow and shadow paths for the given canvas size.
     *
     * Normalized shape (width W, height H = same value since view is square):
     *   Tip:                   (0,           0          )
     *   Left edge bottom:      (0,           H * 0.72   )
     *   Tail top-left notch:   (W * 0.22,    H * 0.57   )
     *   Tail bottom-left:      (W * 0.22,    H * 1.00   ) ← extended slightly out of view
     *   Tail bottom-right:     (W * 0.37,    H * 1.00   )
     *   Tail top-right notch:  (W * 0.37,    H * 0.57   )
     *   Arrow right point:     (W * 0.72,    H * 0.72   )
     *   Close → back to tip (diagonal edge)
     *
     * The tail intentionally extends to H * 1.00 so the bottom of the handle is
     * clipped by the view boundary rather than floating in mid-air.
     *
     * The shadow path is the same shape offset by (shadowDx, shadowDy) to give
     * the impression of depth.
     */
    private fun buildPaths(W: Float, H: Float) {
        val strokeW = (resources.displayMetrics.density * 1.5f).coerceAtLeast(1.5f)
        strokePaint.strokeWidth = strokeW

        // Outline the arrow one stroke-width inside the shadow offset so the
        // shadow just peeks out from under the fill+stroke layers.
        val sx = strokeW * 0.5f  // shadow offset X
        val sy = strokeW * 0.5f  // shadow offset Y

        // Arrow path (hotspot tip at 0,0)
        arrowPath.reset()
        arrowPath.moveTo(0f,       0f)           // tip
        arrowPath.lineTo(0f,       H * 0.72f)    // left edge
        arrowPath.lineTo(W * 0.22f, H * 0.57f)  // notch inward
        arrowPath.lineTo(W * 0.22f, H)           // tail left wall
        arrowPath.lineTo(W * 0.37f, H)           // tail bottom
        arrowPath.lineTo(W * 0.37f, H * 0.57f)  // tail right notch
        arrowPath.lineTo(W * 0.72f, H * 0.72f)  // arrow right point
        arrowPath.close()                        // diagonal back to tip

        // Shadow path: same shape shifted right+down
        shadowPath.reset()
        shadowPath.moveTo(sx,             sy)
        shadowPath.lineTo(sx,             sy + H * 0.72f)
        shadowPath.lineTo(sx + W * 0.22f, sy + H * 0.57f)
        shadowPath.lineTo(sx + W * 0.22f, sy + H)
        shadowPath.lineTo(sx + W * 0.37f, sy + H)
        shadowPath.lineTo(sx + W * 0.37f, sy + H * 0.57f)
        shadowPath.lineTo(sx + W * 0.72f, sy + H * 0.72f)
        shadowPath.close()
    }

    override fun onDraw(canvas: Canvas) {
        if (arrowPath.isEmpty) return
        canvas.drawPath(shadowPath, shadowPaint)  // shadow first (behind fill)
        canvas.drawPath(arrowPath,  fillPaint)    // white fill
        canvas.drawPath(arrowPath,  strokePaint)  // black outline
    }
}
