package com.inputbridge.accessibility

import android.os.Build
import androidx.annotation.RequiresApi
import com.inputbridge.core.logging.BridgeLogger
import com.inputbridge.core.model.*
import com.inputbridge.diagnostics.DiagnosticsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "AccessibilityCommandBus"

/**
 * Decoupled command bus between the receiver networking layer and the
 * [InputBridgeAccessibilityService].
 *
 * The networking layer emits [InputEvent] objects; this bus translates them into
 * accessibility actions on the UI thread through the service singleton.
 *
 * Mouse cursor simulation:
 * - Tracks a virtual cursor position in screen coordinates.
 * - Mouse moves update this virtual position (scaled by [mouseSensitivity]).
 * - Left click dispatches a tap gesture at the current virtual position.
 * - Right click dispatches a long-press.
 * - The position starts at the screen centre; clamped to screen bounds.
 * - Current position is exposed via [cursorPosition] StateFlow for the overlay service.
 *
 * Keyboard injection:
 * - KeyDown events are forwarded to [InputBridgeAccessibilityService.injectKeyCode].
 * - KeyUp events are ignored (injection is complete on KeyDown for accessibility).
 * - TextInput events are forwarded to [InputBridgeAccessibilityService.injectText].
 *
 * Call [setService] / [clearService] from the AccessibilityService lifecycle.
 * Call [setSensitivity] from the ReceiverService to apply persisted settings.
 */
@RequiresApi(Build.VERSION_CODES.N)
object AccessibilityCommandBus {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val commandFlow = MutableSharedFlow<InputEvent>(extraBufferCapacity = 256)

    @Volatile private var service: InputBridgeAccessibilityService? = null

    /**
     * Time taken by the most recent [handleEvent] call in microseconds.
     * Written on Dispatchers.Main; read on the counter-flush IO coroutine.
     */
    private val lastInjectUs = AtomicLong(0L)

    /** Expose last inject duration for ReceiverService counter flush. */
    fun getLastInjectUs(): Long = lastInjectUs.get()

    // ── Virtual cursor ────────────────────────────────────────────────────────

    @Volatile private var cursorX = 0f
    @Volatile private var cursorY = 0f
    private var screenWidth  = 1080f
    private var screenHeight = 2400f

    /**
     * Current virtual cursor position in screen pixels.
     * Updated on every [InputEvent.MouseMove].
     * The [CursorOverlayService] collects this flow to reposition the dot overlay.
     */
    private val _cursorPosition = MutableStateFlow(Pair(0f, 0f))
    val cursorPosition: StateFlow<Pair<Float, Float>> = _cursorPosition.asStateFlow()

    /** Snapshot of the current cursor X coordinate (safe to read from any thread). */
    fun getCursorX(): Float = cursorX

    /** Snapshot of the current cursor Y coordinate (safe to read from any thread). */
    fun getCursorY(): Float = cursorY

    // ── Configuration ─────────────────────────────────────────────────────────

    /**
     * Mouse pointer sensitivity multiplier. Applied to every dx/dy before
     * updating the virtual cursor position. Set from [ReceiverService] at startup.
     */
    @Volatile var mouseSensitivity: Float = 1.0f
        private set

    /** Update the pointer sensitivity multiplier (0.1–10). */
    fun setSensitivity(s: Float) {
        mouseSensitivity = s.coerceIn(0.1f, 10f)
        BridgeLogger.d(TAG, "Sensitivity set to $mouseSensitivity")
    }

    // ── Service attachment ────────────────────────────────────────────────────

    fun setService(svc: InputBridgeAccessibilityService) {
        service = svc
        BridgeLogger.i(TAG, "Service attached")
    }

    fun clearService() {
        service = null
        BridgeLogger.i(TAG, "Service detached")
    }

    fun setScreenSize(width: Int, height: Int) {
        screenWidth  = width.toFloat()
        screenHeight = height.toFloat()
        // Re-centre the virtual cursor when screen size is (re-)established.
        cursorX = screenWidth / 2f
        cursorY = screenHeight / 2f
        _cursorPosition.value = Pair(cursorX, cursorY)
        BridgeLogger.i(TAG, "Screen size updated: ${width}×${height}, cursor centred")
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Enqueue an input event for injection. Non-blocking; drops if buffer is full. */
    fun post(event: InputEvent) {
        commandFlow.tryEmit(event)
    }

    // ── Internal dispatch loop ────────────────────────────────────────────────

    init {
        scope.launch {
            commandFlow.collect { event ->
                val t0 = System.nanoTime()
                handleEvent(event)
                lastInjectUs.set((System.nanoTime() - t0) / 1_000L)
            }
        }
    }

    private fun handleEvent(event: InputEvent) {
        val svc = service ?: run {
            BridgeLogger.w(TAG, "Event dropped — accessibility service not connected: $event")
            return
        }

        when (event) {

            // ── Mouse movement ────────────────────────────────────────────────
            is InputEvent.MouseMove -> {
                cursorX = (cursorX + event.dx * mouseSensitivity).coerceIn(0f, screenWidth - 1f)
                cursorY = (cursorY + event.dy * mouseSensitivity).coerceIn(0f, screenHeight - 1f)
                // Publish new position to the cursor overlay (non-blocking).
                _cursorPosition.value = Pair(cursorX, cursorY)
            }

            // ── Mouse clicks ──────────────────────────────────────────────────
            is InputEvent.MouseButtonDown -> {
                when (event.button) {
                    MouseButton.LEFT    -> svc.tap(cursorX, cursorY)
                    MouseButton.RIGHT   -> svc.longPress(cursorX, cursorY)
                    MouseButton.MIDDLE  -> Unit // no accessibility equivalent
                    MouseButton.BACK    -> svc.goBack()
                    MouseButton.FORWARD -> Unit
                }
            }

            // MouseButtonUp: gesture is complete on DOWN — no separate action needed.
            is InputEvent.MouseButtonUp -> Unit

            // ── Scroll ────────────────────────────────────────────────────────
            is InputEvent.Scroll -> {
                val scrollDx = event.dx * SCROLL_PIXEL_MULTIPLIER * mouseSensitivity
                val scrollDy = event.dy * SCROLL_PIXEL_MULTIPLIER * mouseSensitivity
                svc.swipe(
                    x1 = cursorX,
                    y1 = cursorY,
                    x2 = (cursorX - scrollDx).coerceIn(0f, screenWidth - 1f),
                    y2 = (cursorY - scrollDy).coerceIn(0f, screenHeight - 1f),
                    durationMs = SCROLL_DURATION_MS,
                )
            }

            // ── Keyboard ──────────────────────────────────────────────────────
            is InputEvent.KeyDown -> svc.injectKeyCode(event.keyCode, event.modifiers)

            // KeyUp: no action needed — injection is complete on KeyDown.
            is InputEvent.KeyUp -> Unit

            // ── Text injection ────────────────────────────────────────────────
            is InputEvent.TextInput -> svc.injectText(event.text)

            // ── Navigation ────────────────────────────────────────────────────
            is InputEvent.NavigationAction -> when (event.action) {
                AndroidNavAction.BACK          -> svc.goBack()
                AndroidNavAction.HOME          -> svc.goHome()
                AndroidNavAction.RECENTS       -> svc.goRecents()
                AndroidNavAction.NOTIFICATIONS -> svc.openNotifications()
                AndroidNavAction.POWER,
                AndroidNavAction.VOLUME_UP,
                AndroidNavAction.VOLUME_DOWN,
                AndroidNavAction.SCREENSHOT    -> Unit // Require system-level privileges
            }

            // Modifier state change: modifiers are embedded in subsequent KeyDown events.
            is InputEvent.ModifierStateChanged -> Unit

            else -> BridgeLogger.d(TAG, "Unhandled event type: $event")
        }
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    /** Pixels to swipe per unit of scroll delta. Tune for comfortable feel. */
    private const val SCROLL_PIXEL_MULTIPLIER = 120f

    /** Duration of simulated scroll swipe in ms. Shorter = faster/snappier. */
    private const val SCROLL_DURATION_MS = 80L
}
