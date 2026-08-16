package com.inputbridge.accessibility

import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.annotation.RequiresApi
import com.inputbridge.core.logging.BridgeLogger
import com.inputbridge.core.model.*
import com.inputbridge.diagnostics.DiagnosticsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
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
 * - Mouse moves update this virtual position. Scaling is owned by the bridge
 *   capture side so UDP, hotspot, and Bluetooth HID paths use one multiplier.
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
 */
@RequiresApi(Build.VERSION_CODES.N)
object AccessibilityCommandBus {

    // BUG-078 FIX: a bare scope with SupervisorJob but no CoroutineExceptionHandler lets uncaught
    // exceptions from handleEvent() reach the Main thread's UncaughtExceptionHandler, which kills
    // the process silently on MIUI/OxygenOS — exactly the reported "silent crash" symptom.
    private val scope = CoroutineScope(
        Dispatchers.Main + SupervisorJob() + CoroutineExceptionHandler { _, throwable ->
            if (throwable !is CancellationException) {
                BridgeLogger.e(TAG, "Uncaught exception in accessibility command handler", throwable)
                DiagnosticsManager.update {
                    copy(lastInjectionError = "Accessibility handler crash: ${throwable.javaClass.simpleName}")
                }
            }
        }
    )
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
    @Volatile private var lastCursorX = 0f
    @Volatile private var lastCursorY = 0f
    @Volatile private var isDragging = false

    /**
     * BUG-070 FIX — @Volatile on screen dimensions.
     *
     * screenWidth/screenHeight are written from the accessibility-service thread
     * (effectively Main) via [setScreenSize], and read from Dispatchers.IO in
     * [post] for bounds clamping. Without @Volatile the JVM can cache the default
     * values (1080×2400) in a CPU register, so the IO thread never sees the real
     * screen dimensions after a rotation or first-connect.
     *
     * Symptom: cursor clamps to the wrong edge of the screen (default 1080px wide
     * on a 1600px tablet, or vice-versa) until the next JVM memory barrier.
     */
    @Volatile private var screenWidth  = 1080f
    @Volatile private var screenHeight = 2400f

    /**
     * BUG-072 FIX — explicit initialization flag.
     *
     * The previous guard `cursorX == 0f && cursorY == 0f` incorrectly re-centred
     * the cursor when the user had legitimately moved it to the top-left corner.
     * Use a dedicated flag so the center-on-first-connect logic is robust.
     */
    @Volatile private var cursorInitialized = false

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

    /**
     * BUG-XXX FIX: safety timeout for isDragging. If MouseButtonUp is dropped from
     * commandFlow (buffer full), isDragging stays true forever. Reset after 30 seconds
     * of continuous dragging as a safety measure.
     */
    @Volatile private var dragStartTime = 0L
    private const val MAX_DRAG_DURATION_MS = 30_000L

    // ── Service attachment ────────────────────────────────────────────────────

    fun setService(svc: InputBridgeAccessibilityService) {
        service = svc
        BridgeLogger.i(TAG, "Service attached")

        // Initialize Shizuku input injection if available (1-5ms vs 10-30ms dispatchGesture)
        try {
            ShizukuInputInjector.registerListeners()
            ShizukuInputInjector.init()
            if (ShizukuInputInjector.isAvailable) {
                BridgeLogger.i(TAG, "Shizuku available — using InputManager (1-5ms)")
                DiagnosticsManager.update {
                    copy(injectionMode = "Shizuku/InputManager")
                }
            } else {
                BridgeLogger.i(TAG, "Shizuku not available — using dispatchGesture (10-30ms)")
                DiagnosticsManager.update {
                    copy(injectionMode = "Accessibility/dispatchGesture")
                }
            }
        } catch (t: Throwable) {
            BridgeLogger.e(TAG, "Failed to initialize Shizuku", t)
            DiagnosticsManager.update {
                copy(injectionMode = "Accessibility/dispatchGesture")
            }
        }
    }

    fun clearService() {
        service = null
        ShizukuInputInjector.destroy()
        BridgeLogger.i(TAG, "Service detached, Shizuku cleaned up")
    }

    /** Check whether the accessibility service is currently connected. */
    fun isServiceConnected(): Boolean = service != null

    fun setScreenSize(width: Int, height: Int) {
        screenWidth  = width.toFloat()
        screenHeight = height.toFloat()
        // First connect: centre the cursor so it starts visible mid-screen.
        // Subsequent calls (accessibility reconnect, rotation): clamp to new bounds
        // but preserve position so the cursor doesn't jump to centre unexpectedly.
        if (!cursorInitialized) {
            cursorInitialized = true
            cursorX = screenWidth / 2f
            cursorY = screenHeight / 2f
        } else {
            cursorX = cursorX.coerceIn(0f, screenWidth - 1f)
            cursorY = cursorY.coerceIn(0f, screenHeight - 1f)
        }
        _cursorPosition.value = Pair(cursorX, cursorY)
        BridgeLogger.i(TAG, "Screen size updated: ${width}×${height}, cursor preserved")
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Enqueue an input event for injection. Non-blocking; drops if buffer is full.
     *
     * BUG-042 fix — mouse-move hot path:
     * [InputEvent.MouseMove] only needs to update two floats and a StateFlow —
     * there is no need to dispatch through the coroutine queue. Doing it inline,
     * on the calling IO thread, removes the coroutine-dispatch latency (~1–2 ms)
     * that would otherwise accumulate at 125 Hz USB polling. MutableStateFlow.value
     * is thread-safe, so the overlay collector on Main will pick up the new position
     * on its next frame without any explicit Main-thread dispatch here.
     *
     * All other events continue to go through the coroutine queue so that ordering
     * with respect to clicks, scrolls, and key events is preserved.
     */
    fun post(event: InputEvent) {
        // BUG-104 FIX: CursorGoto treated exactly like MouseMove — inline, no
        // coroutine dispatch. Trackpad ACTION_DOWN sends a CursorGoto; hopping it
        // through commandFlow added one Main-thread dispatch hop to the initial
        // positioning latency. It only updates two floats + a thread-safe StateFlow.
        when (event) {
            is InputEvent.CursorGoto -> {
                val newX = (event.x * screenWidth).coerceIn(0f, screenWidth - 1f)
                val newY = (event.y * screenHeight).coerceIn(0f, screenHeight - 1f)
                cursorX = newX
                cursorY = newY
                _cursorPosition.value = Pair(cursorX, cursorY)
            }
            is InputEvent.MouseMove -> {
                // BUG-XXX: safety timeout — if isDragging is stuck, force-reset
                if (isDragging && System.currentTimeMillis() - dragStartTime > MAX_DRAG_DURATION_MS) {
                    isDragging = false
                    scope.launch(Dispatchers.Main) { service?.endStroke() }
                }

                val newX = (cursorX + event.dx).coerceIn(0f, screenWidth - 1f)
                val newY = (cursorY + event.dy).coerceIn(0f, screenHeight - 1f)

                // If dragging, send continueStroke to keep the gesture alive
                if (isDragging) {
                    // BUG-XXX FIX: capture start coords at launch time, not at
                    // dispatch time on Main. Between launch and execution, another
                    // MouseMove could update lastCursorX/Y, causing stale coords.
                    val startX = lastCursorX
                    val startY = lastCursorY
                    scope.launch(Dispatchers.Main) {
                        service?.continueStroke(startX, startY, newX, newY, true)
                    }
                    lastCursorX = newX
                    lastCursorY = newY
                }

                cursorX = newX
                cursorY = newY
                _cursorPosition.value = Pair(cursorX, cursorY)
            }
            // All other events (KeyDown/KeyUp, buttons, scroll, text, nav, modifiers)
            // go through the coroutine queue to preserve ordering with clicks/scrolls.
            // Explicit branches — no `else ->` so Kotlin enforces exhaustiveness if a
            // new InputEvent subtype is added (AGENTS.md §4.2).
            is InputEvent.KeyDown,
            is InputEvent.KeyUp,
            is InputEvent.MouseButtonDown,
            is InputEvent.MouseButtonUp,
            is InputEvent.Scroll,
            is InputEvent.TextInput,
            is InputEvent.ModifierStateChanged,
            is InputEvent.NavigationAction -> {
                if (!commandFlow.tryEmit(event)) {
                    BridgeLogger.w(TAG, "CommandFlow full — dropped ${event::class.simpleName}")
                    DiagnosticsManager.update {
                        copy(lastInjectionError = "Event buffer full — dropped ${event::class.simpleName}")
                    }
                }
            }
        }
    }

    // ── Internal dispatch loop ────────────────────────────────────────────────

    init {
        scope.launch {
            commandFlow.collect { event ->
                val t0 = System.nanoTime()
                // BUG-078 FIX: defense-in-depth — catch any exception thrown by handleEvent so it
                // cannot propagate out of the collect lambda even if the scope handler is bypassed.
                try {
                    handleEvent(event)
                } catch (t: Throwable) {
                    if (t !is CancellationException) {
                        BridgeLogger.e(TAG, "handleEvent threw for ${event::class.simpleName}", t)
                        DiagnosticsManager.update {
                            copy(lastInjectionError = "handleEvent crash (${event::class.simpleName}): ${t.javaClass.simpleName}")
                        }
                    }
                }
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
            // Handled immediately in post() on the calling IO thread for minimum latency.
            // If somehow a MouseMove reaches here (e.g. old code path), it is a no-op.
            is InputEvent.MouseMove -> Unit

            // ── Mouse clicks ──────────────────────────────────────────────────
            is InputEvent.MouseButtonDown -> {
                BridgeLogger.d(TAG, "Tap/longPress at (${cursorX.toInt()}, ${cursorY.toInt()}) " +
                    "button=${event.button}")
                lastCursorX = cursorX
                lastCursorY = cursorY
                when (event.button) {
                    MouseButton.LEFT    -> {
                        if (ShizukuInputInjector.checkAvailability()) {
                            ShizukuInputInjector.tap(cursorX, cursorY)
                        } else {
                            svc.tap(cursorX, cursorY)
                        }
                        isDragging = true
                        dragStartTime = System.currentTimeMillis()
                    }
                    MouseButton.RIGHT   -> {
                        if (ShizukuInputInjector.checkAvailability()) {
                            scope.launch(Dispatchers.IO) { ShizukuInputInjector.longPress(cursorX, cursorY) }
                        } else {
                            svc.longPress(cursorX, cursorY)
                        }
                    }
                    MouseButton.MIDDLE  -> Unit // no accessibility equivalent
                    MouseButton.BACK    -> svc.goBack()
                    MouseButton.FORWARD -> Unit
                }
            }

            // MouseButtonUp: end any active drag gesture.
            is InputEvent.MouseButtonUp -> {
                if (isDragging && event.button == MouseButton.LEFT) {
                    isDragging = false
                    svc.endStroke()
                }
            }

            // ── Scroll ────────────────────────────────────────────────────────
            is InputEvent.Scroll -> {
                val scrollDx = event.dx * SCROLL_PIXEL_MULTIPLIER
                val scrollDy = event.dy * SCROLL_PIXEL_MULTIPLIER
                BridgeLogger.d(TAG, "Scroll: dx=${event.dx} dy=${event.dy} " +
                    "→ swipe(${cursorX.toInt()},${cursorY.toInt()} → " +
                    "${(cursorX - scrollDx).toInt()},${(cursorY - scrollDy).toInt()})")
                if (ShizukuInputInjector.checkAvailability()) {
                    scope.launch(Dispatchers.IO) {
                        ShizukuInputInjector.swipe(
                            x1 = cursorX,
                            y1 = cursorY,
                            x2 = (cursorX - scrollDx).coerceIn(0f, screenWidth - 1f),
                            y2 = (cursorY - scrollDy).coerceIn(0f, screenHeight - 1f),
                            durationMs = SCROLL_DURATION_MS,
                        )
                    }
                } else {
                    svc.swipe(
                        x1 = cursorX,
                        y1 = cursorY,
                        x2 = (cursorX - scrollDx).coerceIn(0f, screenWidth - 1f),
                        y2 = (cursorY - scrollDy).coerceIn(0f, screenHeight - 1f),
                        durationMs = SCROLL_DURATION_MS,
                    )
                }
            }

            // ── Keyboard ──────────────────────────────────────────────────────
            is InputEvent.KeyDown -> {
                BridgeLogger.d(TAG, "KeyDown: keyCode=${event.keyCode} " +
                    "(${KeyEvent.keyCodeToString(event.keyCode)})")
                if (ShizukuInputInjector.checkAvailability()) {
                    val metaState = buildMetaState(event.modifiers)
                    val now = SystemClock.uptimeMillis()
                    val keyDown = KeyEvent(now, now, KeyEvent.ACTION_DOWN, event.keyCode, 0, metaState)
                    ShizukuInputInjector.injectKeyEvent(keyDown)
                } else {
                    svc.injectKeyCode(event.keyCode, event.modifiers)
                }
            }

            // KeyUp: no action needed — injection is complete on KeyDown.
            is InputEvent.KeyUp -> Unit

            // ── Text injection ────────────────────────────────────────────────
            is InputEvent.TextInput -> {
                BridgeLogger.d(TAG, "TextInput: ${event.text.take(20)}…")
                svc.injectText(event.text)
            }

            // ── Navigation ────────────────────────────────────────────────────
            is InputEvent.NavigationAction -> {
                BridgeLogger.d(TAG, "Navigation: ${event.action}")
                when (event.action) {
                    AndroidNavAction.BACK          -> svc.goBack()
                    AndroidNavAction.HOME          -> svc.goHome()
                    AndroidNavAction.RECENTS       -> svc.goRecents()
                    AndroidNavAction.NOTIFICATIONS -> svc.openNotifications()
                    AndroidNavAction.POWER,
                    AndroidNavAction.VOLUME_UP,
                    AndroidNavAction.VOLUME_DOWN,
                    AndroidNavAction.SCREENSHOT    -> Unit // Require system-level privileges
                }
            }

            // Modifier state change: modifiers are embedded in subsequent KeyDown events.
            is InputEvent.ModifierStateChanged -> Unit

            // Absolute cursor positioning — sets cursor to exact screen coordinates.
            // Used by trackpad mode: touch position on phone maps to tablet screen position.
            // Coordinates are normalized (0–1); receiver maps to its own screen dimensions.
            is InputEvent.CursorGoto -> {
                cursorX = (event.x * screenWidth).coerceIn(0f, screenWidth - 1f)
                cursorY = (event.y * screenHeight).coerceIn(0f, screenHeight - 1f)
                _cursorPosition.value = Pair(cursorX, cursorY)
            }

            // BUG-046 fix: removed dead `else` branch.
            // The `when` above is exhaustive over the sealed InputEvent hierarchy (all 9 subtypes
            // are listed). Keeping `else` here would suppress Kotlin's compile-time exhaustiveness
            // check, silently dropping any new InputEvent subtype added in future. No `else` means
            // the compiler will issue an error if a new subtype is added without updating this handler.
        }
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    /** Pixels to swipe per unit of scroll delta. Tune for comfortable feel. */
    private const val SCROLL_PIXEL_MULTIPLIER = 120f

    /** Duration of simulated scroll swipe in ms. Shorter = faster/snappier. */
    private const val SCROLL_DURATION_MS = 80L

    /**
     * Build an Android meta-state integer from our [ModifierState].
     * Used to create KeyEvent with proper modifiers for Shizuku injection.
     */
    private fun buildMetaState(modifiers: ModifierState): Int {
        var meta = 0
        if (modifiers.shift || modifiers.capsLock) {
            meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        }
        if (modifiers.ctrl)  meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (modifiers.alt)   meta = meta or KeyEvent.META_ALT_ON  or KeyEvent.META_ALT_LEFT_ON
        if (modifiers.meta)  meta = meta or KeyEvent.META_META_ON
        return meta
    }
}
