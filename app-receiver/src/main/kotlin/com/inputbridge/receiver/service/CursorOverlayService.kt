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
 *   - Hotspot at the arrow tip, inset inside the overlay canvas to prevent clipping
 *
 * Hotspot correction: the old dot centred itself on the cursor position using an
 * offset of -(width/2, height/2). An arrow cursor's hotspot is at its tip (top-left),
 * so the overlay position is offset by the inset to keep the visual tip on the logical cursor.
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

        // Arrow view includes padding for a complete outline and shadow.
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
     * The arrow tip is inset inside the view so its outline and shadow are not clipped.
     * Offset the containing view by the same inset to keep the logical hotspot exact.
     */
    private fun updatePosition(x: Float, y: Float) {
        val params = layoutParams ?: return
        val view   = overlayView  ?: return
        val insetPx = (CursorArrowView.HOTSPOT_INSET_DP * resources.displayMetrics.density).toInt()
        params.x = (x.toInt() - insetPx).coerceAtLeast(0)
        params.y = (y.toInt() - insetPx).coerceAtLeast(0)
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
 * Shape (hotspot at the inset tip, all coordinates in canvas pixels):
 *
 *   Tip at the inset — overlay placement compensates so this remains the logical cursor hotspot.
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
        /** Size of the complete cursor canvas, including outline and shadow padding. */
        const val CURSOR_DP = 40
        /** Inset from canvas edge to the pointer tip; also the hotspot offset. */
        const val HOTSPOT_INSET_DP = 2
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
     * The shape is inset on every side so the stroke and shadow are never clipped.
     *   Close → back to tip (diagonal edge)
     *
     * The shadow path is the same shape offset by (shadowDx, shadowDy) to give
     * the impression of depth.
     */
    private fun buildPaths(W: Float, H: Float) {
        // BUG-085 FIX: reserve a real margin around the shape. The previous path
        // touched 0 and H, clipping the pointer border, shadow, and handle.
        val strokeW = (resources.displayMetrics.density * 1.5f).coerceAtLeast(1.5f)
        strokePaint.strokeWidth = strokeW
        val inset = (HOTSPOT_INSET_DP * resources.displayMetrics.density).coerceAtLeast(strokeW)
        val shadowOffset = strokeW
        val usableW = W - inset * 2f - shadowOffset
        val usableH = H - inset * 2f - shadowOffset
        val x = inset
        val y = inset

        // Arrow path (hotspot tip at inset,inset; overlay placement compensates).
        arrowPath.reset()
        arrowPath.moveTo(x,                    y)
        arrowPath.lineTo(x,                    y + usableH * 0.70f)
        arrowPath.lineTo(x + usableW * 0.22f,  y + usableH * 0.55f)
        arrowPath.lineTo(x + usableW * 0.22f,  y + usableH)
        arrowPath.lineTo(x + usableW * 0.40f,  y + usableH)
        arrowPath.lineTo(x + usableW * 0.40f,  y + usableH * 0.55f)
        arrowPath.lineTo(x + usableW * 0.75f,  y + usableH * 0.72f)
        arrowPath.close()

        // Shadow path: the complete arrow shifted right/down inside the canvas.
        shadowPath.reset()
        shadowPath.moveTo(x + shadowOffset,                   y + shadowOffset)
        shadowPath.lineTo(x + shadowOffset,                   y + shadowOffset + usableH * 0.70f)
        shadowPath.lineTo(x + shadowOffset + usableW * 0.22f, y + shadowOffset + usableH * 0.55f)
        shadowPath.lineTo(x + shadowOffset + usableW * 0.22f, y + shadowOffset + usableH)
        shadowPath.lineTo(x + shadowOffset + usableW * 0.40f, y + shadowOffset + usableH)
        shadowPath.lineTo(x + shadowOffset + usableW * 0.40f, y + shadowOffset + usableH * 0.55f)
        shadowPath.lineTo(x + shadowOffset + usableW * 0.75f, y + shadowOffset + usableH * 0.72f)
        shadowPath.close()
    }

    override fun onDraw(canvas: Canvas) {
        if (arrowPath.isEmpty) return
        canvas.drawPath(shadowPath, shadowPaint)  // shadow first (behind fill)
        canvas.drawPath(arrowPath,  fillPaint)    // white fill
        canvas.drawPath(arrowPath,  strokePaint)  // black outline
    }
}
