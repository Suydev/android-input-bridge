package com.inputbridge.bridge.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.annotation.RequiresApi

/**
 * High-performance trackpad view using Android's pointer capture API.
 * 
 * Based on reference app's PointerPathView but optimized for minimal latency:
 * - Direct method calls, no coroutine dispatch overhead
 * - Single onDraw pass with pre-allocated objects
 * - Efficient motion event handling via OnCapturedPointerListener
 * - Works with both Bluetooth HID and WiFi UDP transports
 */
@RequiresApi(Build.VERSION_CODES.O)
class PointerCaptureTrackpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val TAG = "PointerCaptureTrackpad"

    // Transport interface - set by trackpad screen
    var transport: TrackpadTransport? = null

    // Pointer capture listener
    private val capturedPointerListener = OnCapturedPointerListener { view, motionEvent ->
        handleCapturedPointer(motionEvent)
        true
    }

    // Visual feedback
    private var cursorX = 0f
    private var cursorY = 0f
    private var hasValidPosition = false
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val cursorOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val cursorRadius = 8f
    private val density = resources.displayMetrics.density

    // Arrow cursor path (Windows-style)
    private val arrowPath = android.graphics.Path().apply {
        moveTo(0f, 0f)
        lineTo(12f, 18f)
        lineTo(6f, 14f)
        lineTo(18f, 28f)
        lineTo(0f, 20f)
        lineTo(-18f, 28f)
        lineTo(-6f, 14f)
        lineTo(-12f, 18f)
        close()
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x44000000.toInt()
        style = Paint.Style.FILL
    }

    // Click ripple
    private var rippleX = 0f
    private var rippleY = 0f
    private var rippleRadius = 0f
    private var rippleAlpha = 255
    private var isRippleActive = false
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x64FFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val rippleMaxRadiusDp = 20f
    private val rippleDurationMs = 200L
    private val rippleAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
        duration = rippleDurationMs
        addUpdateListener { anim ->
            val progress = anim.animatedValue as Float
            rippleRadius = progress * rippleMaxRadiusDp * density
            rippleAlpha = (255 * (1f - progress)).toInt().coerceIn(0, 255)
            invalidate()
        }
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                isRippleActive = false
                invalidate()
            }
        })
    }

    // Throttling
    private var lastMoveTime = 0L
    private val MOVE_THROTTLE_MS = 15L

    // Sensitivity (user configurable)
    var sensitivity = 1.0f

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Request pointer capture when attached
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requestPointerCapture()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            releasePointerCapture()
        }
    }

    override fun onPointerCaptureChange(hasCapture: Boolean) {
        super.onPointerCaptureChange(hasCapture)
        if (hasCapture) {
            setOnCapturedPointerListener(capturedPointerListener)
            Log.d(TAG, "Pointer capture acquired")
        } else {
            setOnCapturedPointerListener(null)
            Log.d(TAG, "Pointer capture released")
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun handleCapturedPointer(motionEvent: MotionEvent): Boolean {
        // Defensive: guard against null transport to prevent crashes
        val transport = this.transport
        if (transport == null) {
            Log.w(TAG, "Transport is null, dropping pointer event")
            return true
        }

        val action = motionEvent.action
        val x = motionEvent.x
        val y = motionEvent.y

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                cursorX = x
                cursorY = y
                hasValidPosition = true
                safeCall { transport.onCursorMove(x / width, y / height) }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val now = System.currentTimeMillis()
                if (now - lastMoveTime >= 15) {
                    cursorX = x
                    cursorY = y
                    hasValidPosition = true
                    // Apply sensitivity
                    val sx = (x / width) * sensitivity
                    val sy = (y / height) * sensitivity
                    safeCall { transport.onCursorMove(sx.coerceIn(0f, 1f), sy.coerceIn(0f, 1f)) }
                    lastMoveTime = now
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                // Keep position, don't release capture
            }
            MotionEvent.ACTION_BUTTON_PRESS -> {
                val button = motionEvent.actionButton
                when (button) {
                    MotionEvent.BUTTON_PRIMARY -> safeCall { transport.onButtonDown(0) } // Left
                    MotionEvent.BUTTON_SECONDARY -> safeCall { transport.onButtonDown(1) } // Right
                    4 -> safeCall { transport.onButtonDown(2) } // Middle (MotionEvent.BUTTON_MIDDLE = 4)
                    MotionEvent.BUTTON_BACK -> safeCall { transport.onButtonDown(3) } // Back
                    MotionEvent.BUTTON_FORWARD -> safeCall { transport.onButtonDown(4) } // Forward
                }
                triggerClickRipple(x, y)
            }
            MotionEvent.ACTION_BUTTON_RELEASE -> {
                val button = motionEvent.actionButton
                when (button) {
                    MotionEvent.BUTTON_PRIMARY -> safeCall { transport.onButtonUp(0) }
                    MotionEvent.BUTTON_SECONDARY -> safeCall { transport.onButtonUp(1) }
                    4 -> safeCall { transport.onButtonUp(2) } // Middle
                    MotionEvent.BUTTON_BACK -> safeCall { transport.onButtonUp(3) }
                    MotionEvent.BUTTON_FORWARD -> safeCall { transport.onButtonUp(4) }
                }
            }
            MotionEvent.ACTION_SCROLL -> {
                val vScroll = motionEvent.getAxisValue(MotionEvent.AXIS_VSCROLL)
                val hScroll = motionEvent.getAxisValue(MotionEvent.AXIS_HSCROLL)
                if (vScroll != 0f || hScroll != 0f) {
                    safeCall { transport.onScroll(hScroll, -vScroll) }
                }
            }
        }
        return true
    }

    /**
     * Safely call a transport method with exception handling to prevent app crashes.
     */
    private inline fun safeCall(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.e(TAG, "Transport call failed", e)
        }
    }

    private fun triggerClickRipple(x: Float, y: Float) {
        rippleX = x
        rippleY = y
        isRippleActive = true
        rippleAnimator.start()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw cursor if we have a valid position
        if (hasValidPosition) {
            canvas.save()
            canvas.translate(cursorX, cursorY)

            // Shadow
            canvas.drawPath(arrowPath, shadowPaint)

            // White fill
            canvas.drawPath(arrowPath, cursorPaint)

            // Black outline
            canvas.drawPath(arrowPath, cursorOutlinePaint)

            canvas.restore()
        }

        // Draw click ripple
        if (isRippleActive) {
            ripplePaint.alpha = rippleAlpha
            canvas.drawCircle(rippleX, rippleY, rippleRadius, ripplePaint)
        }
    }

    fun releaseCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            releasePointerCapture()
        }
    }

    interface TrackpadTransport {
        fun onCursorMove(x: Float, y: Float)
        fun onButtonDown(button: Int)
        fun onButtonUp(button: Int)
        fun onScroll(x: Float, y: Float)
    }
}