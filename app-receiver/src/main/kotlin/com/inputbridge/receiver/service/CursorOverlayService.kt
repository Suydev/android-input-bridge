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
 * Custom View that draws a Windows-style arrow cursor overlay.
 *
 * Design: classic top-left arrow pointer with:
 *   - White fill with black outline (like Windows default)
 *   - Semi-transparent for less visual obstruction
 *   - Drop shadow for visibility on any background
 *   - Hotspot at the arrow tip (top-left corner)
 */
private class CursorArrowView(context: android.content.Context) : View(context) {

    companion object {
        /** Inset from canvas edge to the arrow tip (hotspot). */
        const val HOTSPOT_INSET_DP = 2
    }

    private val path = android.graphics.Path()
    private val density = resources.displayMetrics.density

    // Semi-transparent white fill (alpha 200/255 ≈ 78%)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        style = Paint.Style.FILL
    }

    // Black outline
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 0, 0, 0)
        style = Paint.Style.STROKE
        strokeWidth = (1.2f * density).coerceAtLeast(1f)
        strokeJoin = Paint.Join.ROUND
    }

    // Drop shadow
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 0, 0, 0)
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // Windows arrow cursor proportions (normalized to view size)
        // Arrow body occupies ~75% of view, tail at bottom-right
        val s = w / 32f  // scale factor

        // Arrow tip (hotspot) near top-left with inset
        val tipX = HOTSPOT_INSET_DP * density
        val tipY = HOTSPOT_INSET_DP * density

        // Build arrow path — classic Windows arrow shape
        path.reset()
        path.moveTo(tipX, tipY)                          // tip
        path.lineTo(tipX + s * 2, tipY + s * 22)        // left edge going down
        path.lineTo(tipX + s * 7, tipY + s * 17)        // notch
        path.lineTo(tipX + s * 12, tipY + s * 27)       // bottom-left
        path.lineTo(tipX + s * 16, tipY + s * 24)       // bottom notch
        path.lineTo(tipX + s * 11, tipY + s * 14)       // right edge going up
        path.lineTo(tipX + s * 20, tipY + s * 14)       // right tail
        path.close()

        // Shadow (offset by 1-2px)
        val sh = (1.5f * density).coerceAtLeast(1.5f)
        path.offset(sh, sh)
        canvas.drawPath(path, shadowPaint)
        path.offset(-sh, -sh)

        // White fill
        canvas.drawPath(path, fillPaint)

        // Black outline
        canvas.drawPath(path, outlinePaint)
    }
}
