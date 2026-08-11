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
import com.inputbridge.receiver.prefs.ReceiverPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

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
    private var overlaySizePx = 0
    private val prefs: ReceiverPreferences by inject()

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            BridgeLogger.w(TAG, "canDrawOverlays() is false — stopping overlay service")
            stopSelf()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // BUG-098 fix: Arrow size is user-configurable and persisted for tablet displays.
        // The view still includes padding for a complete outline and shadow.
        val density = resources.displayMetrics.density
        val viewPx = (prefs.cursorSizeDp * density).toInt().coerceAtLeast(1)
        overlaySizePx = viewPx

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

        BridgeLogger.i(TAG, "Cursor arrow overlay created (view=${viewPx}px, size=${prefs.cursorSizeDp}dp)")
        DiagnosticsManager.update { copy(cursorOverlayActive = true) }

        // Observe cursor position and move the overlay view.
        serviceScope.launch {
            AccessibilityCommandBus.cursorPosition.collect { (x, y) ->
                updatePosition(x, y)
            }
        }
    }

    /**
     * Move the overlay so the arrow TIP lands exactly on (x, y), except at the
     * right/bottom screen edges where the complete pointer must remain visible.
     *
     * The arrow tip is inset inside the view so its outline and shadow are not clipped.
     * Offset the containing view by the same inset to keep the logical hotspot exact.
     */
    private fun updatePosition(x: Float, y: Float) {
        val params = layoutParams ?: return
        val view   = overlayView  ?: return
        val insetPx = (CursorArrowView.HOTSPOT_INSET_DP * resources.displayMetrics.density).toInt()
        // BUG-098 fix: clamp both edges using the actual configured overlay size.
        // The old top/left-only clamp allowed the view to disappear off right/bottom.
        val maxX = (resources.displayMetrics.widthPixels - overlaySizePx).coerceAtLeast(0)
        val maxY = (resources.displayMetrics.heightPixels - overlaySizePx).coerceAtLeast(0)
        params.x = (x.toInt() - insetPx).coerceIn(0, maxX)
        params.y = (y.toInt() - insetPx).coerceIn(0, maxY)
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
 * Custom View that draws a clean crosshair cursor overlay.
 *
 * Design: a + shaped crosshair with:
 *   - Two thin lines crossing at center (the hotspot)
 *   - Small gap at center for precision
 *   - Drop shadow for visibility on any background
 *
 * Much more visible than a tiny arrow on high-DPI tablet screens.
 */
private class CursorArrowView(context: android.content.Context) : View(context) {

    companion object {
        /** Inset from canvas edge to the crosshair center. */
        const val HOTSPOT_INSET_DP = 2
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        strokeCap = Paint.Cap.ROUND
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 0, 0, 0)
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }
    private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val arm = w * 0.42f  // arm length from center
        val gap = w * 0.06f  // gap at center

        val density = resources.displayMetrics.density
        linePaint.strokeWidth = (1.5f * density).coerceAtLeast(1.5f)
        outlinePaint.strokeWidth = (2.5f * density).coerceAtLeast(2.5f)
        shadowPaint.strokeWidth = (3f * density).coerceAtLeast(3f)

        // Shadow (offset)
        val shD = density
        canvas.drawLine(cx - arm + shD, cy + shD, cx - gap + shD, cy + shD, shadowPaint)
        canvas.drawLine(cx + gap + shD, cy + shD, cx + arm + shD, cy + shD, shadowPaint)
        canvas.drawLine(cx + shD, cy - arm + shD, cx + shD, cy - gap + shD, shadowPaint)
        canvas.drawLine(cx + shD, cy + gap + shD, cx + shD, cy + arm + shD, shadowPaint)

        // Black outline
        canvas.drawLine(cx - arm, cy, cx - gap, cy, outlinePaint)
        canvas.drawLine(cx + gap, cy, cx + arm, cy, outlinePaint)
        canvas.drawLine(cx, cy - arm, cx, cy - gap, outlinePaint)
        canvas.drawLine(cx, cy + gap, cx, cy + arm, outlinePaint)

        // White fill
        canvas.drawLine(cx - arm, cy, cx - gap, cy, linePaint)
        canvas.drawLine(cx + gap, cy, cx + arm, cy, linePaint)
        canvas.drawLine(cx, cy - arm, cx, cy - gap, linePaint)
        canvas.drawLine(cx, cy + gap, cx, cy + arm, linePaint)

        // Center dot for precision
        canvas.drawCircle(cx, cy, (1.5f * density).coerceAtLeast(1.5f), centerDotPaint)
    }
}
