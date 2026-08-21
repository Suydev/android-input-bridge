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

    /** Click events for visual feedback (ripple) on the cursor overlay. */
    private val clickFlow = MutableSharedFlow<Pair<Float, Float>>(extraBufferCapacity = 16)

    /** Expose click flow for the cursor overlay service. */
    val clicks: kotlinx.coroutines.flow.SharedFlow<Pair<Float, Float>> = clickFlow

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

    // BUG-125 FIX (audit O): cursor position at the moment of LEFT MouseButtonDown,
    // used to decide whether the gesture was a click (no move) or a drag.
    private var dragDownX = 0f
    private var dragDownY = 0f

    // BUG-125 FIX (audit O): a LEFT gesture that moves less than this many screen pixels
    // between down and up is treated as a click, not a drag.
    private const val CLICK_MOVE_THRESHOLD_PX = 10f

    // BUG-121 FIX (audit K): monotonic drag-session token. Bumped on every
    // MouseButtonDown(LEFT) and MouseButtonUp(LEFT). A continueStroke launched on
    // Dispatchers.Main captures the token at launch; if it runs after the drag has
    // ended, the token no longer matches and the stale continuation is dropped
    // instead of starting a new open stroke that is never ended.
    @Volatile internal var dragSessionId = 0L

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
            // BUG-119 FIX (audit I): gate the reported mode on checkAvailability() (which
            // verifies the Shizuku permission), not isAvailable() (binder present only).
            // Without this the diagnostics string claimed Shizuku while the actual injection
            // paths correctly fell back to dispatchGesture.
            if (ShizukuInputInjector.checkAvailability()) {
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

    /**
     * Whether ANY injection path is usable right now.
     *
     * BUG-145 FIX: the receiver previously dropped every input packet unless the
     * accessibility service was connected. Shizuku's InputManager.injectInputEvent
     * needs no accessibility service, so a receiver with Shizuku running + granted
     * injected nothing (AGENTS.md §4.8: Shizuku is the PRIMARY low-latency path and
     * dispatchGesture is the fallback — the gate must not reverse that).
     */
    fun isInjectionAvailable(): Boolean =
        service != null || ShizukuInputInjector.checkAvailability()

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
                    dragSessionId++
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
                    // BUG-121 FIX (audit K): capture the drag-session token so the
                    // continuation can detect it ran after the drag ended.
                    val session = dragSessionId
                    scope.launch(Dispatchers.Main) {
                        service?.continueStroke(startX, startY, newX, newY, true, session)
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
                // BUG-140 FIX: when Shizuku (the 1-5ms fast path) is available, inject key-down
                // inline on this receive thread instead of hopping through commandFlow → Main.
                // [ShizukuInputInjector.injectKeyEvent] is a plain binder call, so this runs with
                // zero coroutine dispatch on the highest-priority thread. Scroll/right-click use
                // Shizuku SUSPEND functions and stay on the commandFlow → Main → IO path; events
                // with no Shizuku equivalent fall back to commandFlow (a11y on Main).
                if (ShizukuInputInjector.checkAvailability() && injectShizukuFastPath(event)) return
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

    // ── BUG-140 FIX: Shizuku fast-path helpers ────────────────────────────────

    /**
     * Inject keyboard via Shizuku inline on the calling (receive) thread when Shizuku is available.
     * Returns true if the event was handled (caller skips commandFlow). [InputEvent.KeyDown] and
     * [InputEvent.KeyUp] are fast-pathed: [ShizukuInputInjector.injectKeyEvent] is a plain binder call
     * (non-suspend), so it runs with zero coroutine dispatch on the highest-priority thread. Both the
     * ACTION_DOWN and the matching ACTION_UP must be injected or the receiver keeps the key stuck down.
     * Scroll/right-click use Shizuku SUSPEND functions and MUST run inside a coroutine, so they stay on
     * the commandFlow → Main → IO path in [handleEvent]; left-button drag/click, text and navigation are
     * excluded entirely (a11y gesture strokes on Main). Written as explicit `is` checks (not a sealed
     * `when`) so it does not need an exhaustive `else ->` (AGENTS.md §4.2).
     */
    private fun injectShizukuFastPath(event: InputEvent): Boolean {
        if (event is InputEvent.KeyDown) { shizukuInjectKeyDown(event); return true }
                // BUG-166: also fast-path KeyUp or Shizuku leaves keys stuck down
if (event is InputEvent.KeyUp) { shizukuInjectKeyUp(event); return true }
        return false
    }

    private fun shizukuInjectKeyDown(event: InputEvent.KeyDown) {
        val metaState = buildMetaState(event.modifiers)
        val now = SystemClock.uptimeMillis()
        val keyDown = KeyEvent(now, now, KeyEvent.ACTION_DOWN, event.keyCode, 0, metaState)
        if (!ShizukuInputInjector.injectKeyEvent(keyDown)) {
            BridgeLogger.w(TAG, "Shizuku key DOWN not injected (keyCode=${event.keyCode})")
            DiagnosticsManager.update {
                copy(lastInjectionError = "Shizuku key down failed (keyCode=${event.keyCode})")
            }
        }
    }

    private fun shizukuInjectKeyUp(event: InputEvent.KeyUp) {
        val metaState = buildMetaState(event.modifiers)
        val now = SystemClock.uptimeMillis()
        val keyUp = KeyEvent(now, now, KeyEvent.ACTION_UP, event.keyCode, 0, metaState)
        if (!ShizukuInputInjector.injectKeyEvent(keyUp)) {
            BridgeLogger.w(TAG, "Shizuku key UP not injected (keyCode=${event.keyCode})")
            DiagnosticsManager.update {
                copy(lastInjectionError = "Shizuku key up failed (keyCode=${event.keyCode})")
            }
        }
    }

    // BUG-140 FIX: Shizuku `swipe`/`longPress` are SUSPEND functions (they must run inside a
    // coroutine), so they are intentionally NOT fast-pathed inline. Scroll and right-click keep
    // the commandFlow → Main → IO path below. Only `injectKeyEvent` (a plain binder call) is
    // fast-pathed, which is the high-frequency keyboard win.

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
                        // BUG-125 FIX (audit O): do NOT tap on down. Tapping here and then
                        // also starting a drag stroke on move means a click+small-drag
                        // double-fires (the down tap plus the gesture). Instead, remember
                        // the down position and only perform the tap on mouse-up if the
                        // pointer did not move enough to be a drag.
                        dragDownX = cursorX
                        dragDownY = cursorY
                        isDragging = true
                        dragStartTime = System.currentTimeMillis()
                        dragSessionId++
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
                    dragSessionId++
                    svc.endStroke()
                    // BUG-125 FIX (audit O): if the pointer barely moved, this was a click,
                    // not a drag — dispatch the tap now (deferred from MouseButtonDown). When
                    // the gesture was a real drag, the stroke already represented it, so we
                    // must NOT also tap.
                    val moved = kotlin.math.hypot(cursorX - dragDownX, cursorY - dragDownY)
                    if (moved < CLICK_MOVE_THRESHOLD_PX) {
                        if (ShizukuInputInjector.checkAvailability()) {
                            ShizukuInputInjector.tap(cursorX, cursorY)
                        } else {
                            svc.tap(cursorX, cursorY)
                        }
                        // Emit click for visual ripple on cursor overlay
                        clickFlow.tryEmit(cursorX to cursorY)
                    }
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
                    shizukuInjectKeyDown(event)
                } else {
                    svc.injectKeyCode(event.keyCode, event.modifiers)
                }
            }

            // KeyUp: no action needed — injection is complete on KeyDown.
            is InputEvent.KeyUp -> Unit

            // ── Text injection ────────────────────────────────────────────────
            is InputEvent.TextInput -> {
                BridgeLogger.d(TAG, "TextInput: ${event.text.take(20)}…")
                // BUG-157 FIX: injectText does a recursive editable-node tree walk plus
                // ACTION_SET_TEXT / clipboard round-trips — heavy work that would block the
                // Main thread (the commandFlow collector runs on Main) and risk an ANR on a
                // deep a11y tree or long paste. Dispatch it to a background dispatcher.
                val svcRef = svc
                scope.launch(Dispatchers.IO) { svcRef.injectText(event.text) }
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
