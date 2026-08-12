package com.inputbridge.receiver.service

import android.app.Service
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import java.util.LinkedList

private const val TAG = "CursorOverlayService"

/**
 * Draws a cursor overlay with vanishing trail at the current virtual cursor position.
 *
 * Features:
 * - Windows-style arrow cursor with trail that fades over time
 * - Trail points stored with timestamps for age-based alpha/stroke-width fadeout
 * - Click ripple animation (circle expanding + fading)
 * - Uses translationX/Y instead of updateViewLayout for zero Binder IPC
 * - Hardware layer for GPU texture caching
 *
 * Based on reference APK's PointerPathView implementation.
 *
 * Requires SYSTEM_ALERT_WINDOW permission (canDrawOverlays()). If absent this service
 * exits immediately rather than crashing.
 */
@RequiresApi(Build.VERSION_CODES.N)
class CursorOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: CursorTrailView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var overlaySizePx = 0
    private val prefs: ReceiverPreferences by inject()

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob() + kotlinx.coroutines.CoroutineExceptionHandler { _, t ->
        if (t !is kotlinx.coroutines.CancellationException) {
            BridgeLogger.e(TAG, "Uncaught exception in CursorOverlayService", t)
        }
    })
    private var trailCleanupJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            BridgeLogger.w(TAG, "canDrawOverlays() is false — stopping overlay service")
            stopSelf()
            return
        }

        windowManager = (getSystemService(WINDOW_SERVICE) as? WindowManager) ?: run {
            BridgeLogger.e(TAG, "WindowManager unavailable")
            stopSelf()
            return
        }

        // Arrow size is user-configurable and persisted for tablet displays.
        val density = resources.displayMetrics.density
        val viewPx = (prefs.cursorSizeDp * density).toInt().coerceAtLeast(1)
        overlaySizePx = viewPx

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
        layoutParams = params

        val view = CursorTrailView(this)
        overlayView = view

        // Enable hardware layer for GPU texture caching
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        runCatching {
            windowManager.addView(view, params)
        }.onFailure { e ->
            BridgeLogger.e(TAG, "Failed to add cursor overlay view: ${e.message}")
            stopSelf()
            return
        }

        BridgeLogger.i(TAG, "Cursor trail overlay created (view=${viewPx}px, size=${prefs.cursorSizeDp}dp)")
        DiagnosticsManager.update { copy(cursorOverlayActive = true) }

        // Observe cursor position and add trail points.
        serviceScope.launch {
            AccessibilityCommandBus.cursorPosition.collect { (x, y) ->
                overlayView?.addTrailPoint(x, y)
                // Schedule trail cleanup after inactivity
                scheduleTrailCleanup()
            }
        }
    }

    /**
     * Schedule trail cleanup after 500ms of inactivity.
     * Cancels any existing cleanup job and starts a new one.
     */
    private fun scheduleTrailCleanup() {
        trailCleanupJob?.cancel()
        trailCleanupJob = serviceScope.launch {
            delay(500L)
            overlayView?.clearTrail()
        }
    }

    override fun onDestroy() {
        trailCleanupJob?.cancel()
        serviceScope.cancel()
        overlayView?.let { v ->
            v.setLayerType(View.LAYER_TYPE_NONE, null)
            runCatching { windowManager.removeView(v) }
        }
        overlayView = null
        DiagnosticsManager.update { copy(cursorOverlayActive = false) }
        BridgeLogger.i(TAG, "Cursor trail overlay removed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.inputbridge.receiver.STOP_CURSOR_OVERLAY"
    }
}

/**
 * Data class for a trail point with timestamp.
 */
private data class TrailPoint(
    val x: Float,
    val y: Float,
    val timestamp: Long,
    val isStart: Boolean = false
)

/**
 * Custom View that draws a cursor with vanishing trail, similar to PointerPathView
 * in the reference APK.
 *
 * Features:
 * - LinkedList of trail points with timestamps
 * - Age-based alpha fadeout on trail lines
 * - Stroke width fades with age
 * - Cursor dot drawn as filled circle
 * - Click ripple animation via ValueAnimator
 * - Uses translationX/Y for zero Binder IPC (no updateViewLayout)
 */
private class CursorTrailView(context: android.content.Context) : View(context) {

    companion object {
        const val TRAIL_FADE_DURATION_MS = 500L // Trail fades over 500ms
        const val TRAIL_STEP_MS = 16L // Minimum time between trail points (~60fps)
        const val CLICK_RIPPLE_DURATION_MS = 200L
        const val CLICK_RIPPLE_MAX_RADIUS_DP = 20f
    }

    // Trail points with timestamps
    private val trailPoints = LinkedList<TrailPoint>()

    // Paints
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val cursorOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(100, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    // Click ripple state
    private var rippleX = 0f
    private var rippleY = 0f
    private var rippleRadius = 0f
    private var rippleAlpha = 255
    private var isRippleActive = false

    // Cursor size
    private val cursorRadius = 8f
    private val density = resources.displayMetrics.density

    // Last trail point time to throttle updates
    private var lastTrailTime = 0L

    /**
     * Add a new trail point at the given position.
     * Called from CursorOverlayService when cursor position updates.
     */
    fun addTrailPoint(x: Float, y: Float) {
        val now = SystemClock.elapsedRealtime()

        // Throttle trail points to ~60fps
        if (now - lastTrailTime < TRAIL_STEP_MS) return
        lastTrailTime = now

        // Add new point
        trailPoints.add(TrailPoint(x, y, now))

        // Limit trail length to prevent memory issues
        while (trailPoints.size > 100) {
            trailPoints.removeFirst()
        }

        invalidate()
    }

    /**
     * Clear the trail (called after inactivity).
     */
    fun clearTrail() {
        if (trailPoints.isEmpty()) return
        trailPoints.clear()
        invalidate()
    }

    /**
     * Trigger a click ripple animation at the given position.
     */
    fun triggerClickRipple(x: Float, y: Float) {
        rippleX = x
        rippleY = y
        rippleRadius = 0f
        rippleAlpha = 255
        isRippleActive = true
        invalidate()

        // Animate the ripple
        postDelayed({
            isRippleActive = false
            invalidate()
        }, CLICK_RIPPLE_DURATION_MS)
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.elapsedRealtime()

        // Remove old points before iterating to avoid concurrent modification
        while (trailPoints.isNotEmpty() && now - trailPoints.first.timestamp > TRAIL_FADE_DURATION_MS) {
            trailPoints.removeFirst()
        }

        // Draw trail lines with fading alpha
        if (trailPoints.size >= 2) {
            var prevPoint = trailPoints.first
            for (i in 1 until trailPoints.size) {
                val currentPoint = trailPoints[i]
                val age = now - currentPoint.timestamp

                // Calculate alpha based on age (255 = new, 0 = old)
                val alpha = ((1f - age.toFloat() / TRAIL_FADE_DURATION_MS) * 255).toInt()
                    .coerceIn(0, 255)

                // Calculate stroke width based on age (thicker = newer)
                val strokeWidth = (1f + (1f - age.toFloat() / TRAIL_FADE_DURATION_MS) * 3f) * density

                trailPaint.alpha = alpha
                trailPaint.strokeWidth = strokeWidth

                canvas.drawLine(prevPoint.x, prevPoint.y, currentPoint.x, currentPoint.y, trailPaint)

                prevPoint = currentPoint
            }
        }

        // Draw cursor dot
        if (trailPoints.isNotEmpty()) {
            val lastPoint = trailPoints.last

            // Drop shadow
            canvas.drawCircle(lastPoint.x + 2f, lastPoint.y + 2f, cursorRadius * density, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(70, 0, 0, 0)
                style = Paint.Style.FILL
            })

            // White fill
            canvas.drawCircle(lastPoint.x, lastPoint.y, cursorRadius * density, cursorPaint)

            // Black outline
            canvas.drawCircle(lastPoint.x, lastPoint.y, cursorRadius * density, cursorOutlinePaint)
        }

        // Draw click ripple
        if (isRippleActive) {
            rippleRadius += (CLICK_RIPPLE_MAX_RADIUS_DP * density) / (CLICK_RIPPLE_DURATION_MS / 16f)
            rippleAlpha = (255 * (1f - rippleRadius / (CLICK_RIPPLE_MAX_RADIUS_DP * density))).toInt()
                .coerceIn(0, 255)

            ripplePaint.alpha = rippleAlpha
            canvas.drawCircle(rippleX, rippleY, rippleRadius, ripplePaint)

            if (rippleAlpha > 0) {
                postInvalidateDelayed(16)
            }
        }
    }
}
