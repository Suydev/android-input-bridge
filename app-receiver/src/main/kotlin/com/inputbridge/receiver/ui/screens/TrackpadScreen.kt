package com.inputbridge.receiver.ui.screens

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
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

private const val TAP_THRESHOLD_PX = 10f
private const val SCROLL_MULTIPLIER = 3f

/**
 * Full-screen trackpad overlay for the receiver tablet.
 *
 * Covers the ENTIRE screen (edge-to-edge, behind system bars) on any device.
 * Touch coordinates are normalized to 0-1 for cross-device compatibility.
 *
 * Gestures:
 * - Single-finger drag → MouseMove (cursor movement)
 * - Single-finger tap → Left click
 * - Long press → Right click
 * - Two-finger vertical drag → Scroll
 * - Middle-click button → Middle click (three-finger tap alternative)
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

        // ── Middle-click button (floating, bottom-right) ─────────────────────
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 80.dp)
                .size(48.dp)
                .background(ReceiverSurface.copy(alpha = 0.8f), CircleShape)
                .clickable {
                    AccessibilityCommandBus.post(InputEvent.MouseButtonDown(MouseButton.MIDDLE))
                    AccessibilityCommandBus.post(InputEvent.MouseButtonUp(MouseButton.MIDDLE))
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "M",
                color = ReceiverDim,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
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
                "1 finger: move · Tap: click · Hold: right click\n" +
                "2 fingers: scroll · M button: middle click",
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
 * Single-coroutine gesture detection for the trackpad.
 *
 * State machine (no concurrent detectors):
 *   IDLE → pointer-down → COOLDOWN
 *   COOLDOWN + move > TAP_THRESHOLD → DRAGGING
 *   COOLDOWN + hold ≥ 500ms (no movement) → LONG_PRESS → COOLDOWN
 *   COOLDOWN + pointer-up (no movement) → TAP → IDLE
 *   DRAGGING + pointer-up → IDLE
 *   SCROLLING + all-pointers-up → IDLE
 */
private suspend fun PointerInputScope.awaitTrackpadGestureScope(
    getCursorX: () -> Float,
    getCursorY: () -> Float,
    setCursorX: (Float) -> Unit,
    setCursorY: (Float) -> Unit,
    setTouching: (Boolean) -> Unit,
) {
    awaitEachGesture {
        // ── Wait for pointer-down ────────────────────────────────────────────
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

        // ── Pointer-down: set cursor to touch position ───────────────────────
        val cx = (downX / size.width).coerceIn(0f, 1f)
        val cy = (downY / size.height).coerceIn(0f, 1f)
        setCursorX(cx)
        setCursorY(cy)
        AccessibilityCommandBus.post(InputEvent.CursorGoto(cx, cy))

        // ── Process events until all pointers lift ────────────────────────────
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }

            // All pointers lifted → exit (awaitEachGesture restarts on next touch)
            if (pressed.isEmpty()) {
                // Tap: no movement, no long-press → left click
                if (!longPressFired && !isTwoFinger && totalMovement < TAP_THRESHOLD_PX) {
                    AccessibilityCommandBus.post(InputEvent.MouseButtonDown(MouseButton.LEFT))
                    AccessibilityCommandBus.post(InputEvent.MouseButtonUp(MouseButton.LEFT))
                }
                break
            }

            // ── Two-finger detection ─────────────────────────────────────────
            if (pressed.size >= 2 && !isTwoFinger) {
                isTwoFinger = true
                lastTwoFingerY = (pressed[0].position.y + pressed[1].position.y) / 2f
            }

            if (isTwoFinger) {
                // One finger lifted during two-finger scroll → end scroll
                if (pressed.size < 2) {
                    isTwoFinger = false
                    lastX = pressed.first().position.x
                    lastY = pressed.first().position.y
                } else {
                    // Two-finger scroll
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

            // ── Single-finger: track movement ────────────────────────────────
            val change = pressed.first()
            val movX = change.position.x - downX
            val movY = change.position.y - downY
            totalMovement = abs(movX) + abs(movY)

            if (totalMovement >= TAP_THRESHOLD_PX && !longPressFired) {
                // Crossed threshold → drag mode: send incremental MouseMove
                val dx = (change.position.x - lastX) / size.width
                val dy = (change.position.y - lastY) / size.height
                if (dx != 0f || dy != 0f) {
                    AccessibilityCommandBus.post(InputEvent.MouseMove(dx, dy))
                    setCursorX((getCursorX() + dx).coerceIn(0f, 1f))
                    setCursorY((getCursorY() + dy).coerceIn(0f, 1f))
                }
                lastX = change.position.x
                lastY = change.position.y
            } else if (!longPressFired) {
                // Still within threshold → check for long-press
                val elapsed = System.currentTimeMillis() - downTime
                if (elapsed >= 500) {
                    longPressFired = true
                    AccessibilityCommandBus.post(InputEvent.MouseButtonDown(MouseButton.RIGHT))
                    AccessibilityCommandBus.post(InputEvent.MouseButtonUp(MouseButton.RIGHT))
                }
            }
        }

        // ── Gesture ended → reset ────────────────────────────────────────────
        setTouching(false)
    }
}


