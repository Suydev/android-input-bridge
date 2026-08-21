package com.inputbridge.receiver.ui.screens

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inputbridge.accessibility.AccessibilityCommandBus
import com.inputbridge.core.model.InputEvent
import com.inputbridge.core.model.MouseButton
import com.inputbridge.receiver.ui.theme.*
import com.inputbridge.receiver.viewmodel.ReceiverViewModel
import kotlin.math.abs
import kotlin.math.sqrt

private const val TAP_THRESHOLD_PX = 10f
private const val SCROLL_MULTIPLIER = 3f
private const val LONG_PRESS_MS = 500L

/**
 * Full-screen trackpad overlay — pure gesture surface, no on-screen buttons.
 *
 * Modeled after btmouse (BLE HID mouse emulator):
 * - Single-finger drag → relative cursor movement (MouseMove deltas)
 * - Single-finger tap → left click
 * - Long press (hold still) → right click
 * - Two-finger vertical drag → scroll
 *
 * No CursorGoto — movement is purely delta-based like a physical trackpad.
 * The AccessibilityCommandBus tracks cursor position internally.
 */
@Composable
fun TrackpadScreen(
    onBack: () -> Unit,
    viewModel: ReceiverViewModel,
) {
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
    val view = LocalView.current

    // Hide system bars for true full-screen (edge-to-edge)
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let {
            WindowCompat.getInsetsController(it, it.decorView)
        }
        controller?.apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window?.let {
            WindowCompat.setDecorFitsSystemWindows(it, false)
            it.statusBarColor = android.graphics.Color.TRANSPARENT
            it.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    var cursorX by remember { mutableFloatStateOf(0.5f) }
    var cursorY by remember { mutableFloatStateOf(0.5f) }
    var isTouching by remember { mutableStateOf(false) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // ── Trackpad touch area (full screen) ────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { boxSize = it }
                .pointerInput(Unit) {
                    awaitTrackpadGestureScope(
                        { cursorX }, { cursorY },
                        { cursorX = it }, { cursorY = it },
                        { isTouching = it },
                    )
                },
        ) {
            // ── Virtual cursor dot ───────────────────────────────────────────
            if (boxSize.width > 0 && boxSize.height > 0) {
                val halfCursorPx = with(density) { 12.dp.toPx() }
                val cursorOffsetXPx = cursorX * boxSize.width - halfCursorPx
                val cursorOffsetYPx = cursorY * boxSize.height - halfCursorPx
                Box(
                    modifier = Modifier
                        .offset(
                            x = with(density) { cursorOffsetXPx.toDp() },
                            y = with(density) { cursorOffsetYPx.toDp() },
                        )
                        .size(24.dp)
                        .background(
                            if (isTouching) ReceiverPrimary else ReceiverPrimary.copy(alpha = 0.6f),
                            CircleShape,
                        ),
                )
            }
        }

        // ── Top bar (auto-hides) ─────────────────────────────────────────────
        var showBar by remember { mutableStateOf(true) }
        LaunchedEffect(isTouching) {
            if (isTouching) {
                showBar = false
            } else {
                kotlinx.coroutines.delay(3000)
                showBar = true
            }
        }
        if (showBar) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .align(Alignment.TopStart),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Default.ArrowBack, "Back",
                        tint = ReceiverDim, modifier = Modifier.size(24.dp),
                    )
                }
                Text(
                    "TRACKPAD",
                    color = ReceiverDim, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace, letterSpacing = 3.sp,
                )
                Text(
                    "${diagnostics.latencyMs}ms",
                    color = ReceiverDim, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        // ── Bottom hint (auto-hides) ─────────────────────────────────────────
        if (showBar) {
            Text(
                "1 finger: move · Tap: click · Hold: right click\n2 fingers: scroll",
                color = ReceiverDim.copy(alpha = 0.5f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .padding(bottom = 16.dp),
                lineHeight = 14.sp,
            )
        }
    }
}

/**
 * Single-coroutine gesture detection — delta-based trackpad (btmouse style).
 *
 * No CursorGoto. Movement is purely relative (MouseMove deltas).
 * The AccessibilityCommandBus tracks cursor position internally.
 *
 * Gestures:
 *   Single-finger drag → MouseMove(dx, dy) — relative cursor movement
 *   Single-finger tap (no movement) → MouseDown/Up(LEFT) — left click
 *   Long press (hold still ≥ 500ms) → MouseDown/Up(RIGHT) — right click
 *   Two-finger vertical drag → Scroll(0, dy) — scroll wheel
 */
private suspend fun PointerInputScope.awaitTrackpadGestureScope(
    getCursorX: () -> Float,
    getCursorY: () -> Float,
    setCursorX: (Float) -> Unit,
    setCursorY: (Float) -> Unit,
    setTouching: (Boolean) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        setTouching(true)
        val downX = down.position.x
        val downY = down.position.y
        var lastX = downX
        var lastY = downY
        val downTime = System.currentTimeMillis()
        var totalMovement = 0f
        var longPressFired = false
        var isTwoFinger = false
        var lastTwoFingerY = 0f

        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }

            // BUG-157 FIX: in multi-window / foldable / freeform the gesture area can be
            // momentarily 0x0; dividing by it would throw ArithmeticException and crash
            // the receiver. Skip the frame until it has a real size.
            if (size.width == 0 || size.height == 0) continue

            // All pointers lifted
            if (pressed.isEmpty()) {
                // Tap: no movement, no long-press → left click
                if (!longPressFired && !isTwoFinger && totalMovement < TAP_THRESHOLD_PX) {
                    AccessibilityCommandBus.post(InputEvent.MouseButtonDown(MouseButton.LEFT))
                    AccessibilityCommandBus.post(InputEvent.MouseButtonUp(MouseButton.LEFT))
                }
                break
            }

            // ── Two-finger scroll ────────────────────────────────────────────
            if (pressed.size >= 2 && !isTwoFinger) {
                isTwoFinger = true
                lastTwoFingerY = (pressed[0].position.y + pressed[1].position.y) / 2f
            }

            if (isTwoFinger) {
                if (pressed.size < 2) {
                    // One finger lifted → end scroll, single finger continues
                    isTwoFinger = false
                    lastX = pressed.first().position.x
                    lastY = pressed.first().position.y
                } else {
                    val avgY = (pressed[0].position.y + pressed[1].position.y) / 2f
                    val scrollDy = (avgY - lastTwoFingerY) / size.height * SCROLL_MULTIPLIER
                    if (abs(scrollDy) > 0.001f) {
                        AccessibilityCommandBus.post(InputEvent.Scroll(0f, scrollDy))
                    }
                    lastTwoFingerY = avgY
                    event.changes.forEach { it.consume() }
                    continue
                }
            }

            // ── Single-finger movement ───────────────────────────────────────
            val change = pressed.first()
            val dx = change.position.x - lastX
            val dy = change.position.y - lastY
            val movement = sqrt(dx * dx + dy * dy)
            totalMovement += abs(dx) + abs(dy)

            if (movement > 0.5f) {
                // Send raw pixel deltas to AccessibilityCommandBus (it tracks cursor in pixels).
                // Keep the visual cursor in 0-1 range for the overlay dot.
                AccessibilityCommandBus.post(InputEvent.MouseMove(dx, dy))
                setCursorX((getCursorX() + dx / size.width).coerceIn(0f, 1f))
                setCursorY((getCursorY() + dy / size.height).coerceIn(0f, 1f))
                lastX = change.position.x
                lastY = change.position.y
            }

            // Long-press detection (hold still ≥ 500ms)
            if (!longPressFired && totalMovement < TAP_THRESHOLD_PX) {
                val elapsed = System.currentTimeMillis() - downTime
                if (elapsed >= LONG_PRESS_MS) {
                    longPressFired = true
                    AccessibilityCommandBus.post(InputEvent.MouseButtonDown(MouseButton.RIGHT))
                    AccessibilityCommandBus.post(InputEvent.MouseButtonUp(MouseButton.RIGHT))
                }
            }
        }

        setTouching(false)
    }
}
