package com.inputbridge.receiver.ui.screens

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
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
import com.inputbridge.core.model.InputEvent
import com.inputbridge.core.model.MouseButton
import com.inputbridge.receiver.ui.theme.*
import com.inputbridge.receiver.viewmodel.ReceiverViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
    val transportState = rememberUpdatedState(viewModel.trackpadTransport)

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
                        transportState,
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
                    val transport = transportState.value
                    sendMouseButton(transport, MouseButton.MIDDLE, down = true)
                    sendMouseButton(transport, MouseButton.MIDDLE, down = false)
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
 * Custom gesture detection supporting single-finger drag, tap, long-press,
 * and two-finger scroll — all in a SINGLE pointerInput block.
 */
private suspend fun PointerInputScope.awaitTrackpadGestureScope(
    transportState: State<com.inputbridge.transport.wifi.UdpTransport?>,
    getCursorX: () -> Float,
    getCursorY: () -> Float,
    setCursorX: (Float) -> Unit,
    setCursorY: (Float) -> Unit,
    setTouching: (Boolean) -> Unit,
) {
    coroutineScope {
        // Single-finger drag → cursor movement
        launch {
            detectDragGestures(
                onDragStart = { offset ->
                    setTouching(true)
                    val x = offset.x / size.width
                    val y = offset.y / size.height
                    setCursorX(x)
                    setCursorY(y)
                    val transport = transportState.value
                    sendCursorGoto(transport, x, y)
                },
                onDrag = { change, dragAmount ->
                    change.consume()
                    val dx = dragAmount.x / size.width
                    val dy = dragAmount.y / size.height
                    val transport = transportState.value
                    sendMouseMove(transport, dx, dy)
                    setCursorX((getCursorX() + dx).coerceIn(0f, 1f))
                    setCursorY((getCursorY() + dy).coerceIn(0f, 1f))
                },
                onDragEnd = { setTouching(false) },
                onDragCancel = { setTouching(false) },
            )
        }

        // Custom tap / long-press / two-finger scroll detection
        launch {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val downTime = System.currentTimeMillis()
                val startX = down.position.x
                val startY = down.position.y
                var totalMovement = 0f
                var longPressFired = false
                var isTwoFinger = false
                var lastTwoFingerY = 0f

                while (true) {
                    val event = awaitPointerEvent()
                    val pointers = event.changes.filter { it.pressed }

                    // Two-finger detection
                    if (pointers.size >= 2 && !isTwoFinger) {
                        isTwoFinger = true
                        lastTwoFingerY = (pointers[0].position.y + pointers[1].position.y) / 2f
                        setTouching(true)
                    }

                    if (isTwoFinger) {
                        // Two-finger scroll
                        val currentY = (pointers[0].position.y + pointers[1].position.y) / 2f
                        val scrollDy = (currentY - lastTwoFingerY) / size.height * SCROLL_MULTIPLIER
                        if (abs(scrollDy) > 0.001f) {
                            val transport = transportState.value
                            sendScroll(transport, 0f, scrollDy)
                        }
                        lastTwoFingerY = currentY
                        event.changes.forEach { it.consume() }
                    } else {
                        // Single-finger: tap / long-press / drag
                        val change = pointers.firstOrNull() ?: continue

                        if (change.pressed) {
                            val movX = change.position.x - startX
                            val movY = change.position.y - startY
                            totalMovement = abs(movX) + abs(movY)

                            if (!longPressFired && totalMovement < TAP_THRESHOLD_PX) {
                                val elapsed = System.currentTimeMillis() - downTime
                                if (elapsed >= 500) {
                                    longPressFired = true
                                    val x = change.position.x / size.width
                                    val y = change.position.y / size.height
                                    setCursorX(x)
                                    setCursorY(y)
                                    val transport = transportState.value
                                    sendCursorGoto(transport, x, y)
                                    sendMouseButton(transport, MouseButton.RIGHT, down = true)
                                    sendMouseButton(transport, MouseButton.RIGHT, down = false)
                                }
                            }
                        } else {
                            // Pointer up — fire tap if no movement and not long-press
                            if (!longPressFired && totalMovement < TAP_THRESHOLD_PX) {
                                val x = change.position.x / size.width
                                val y = change.position.y / size.height
                                setCursorX(x)
                                setCursorY(y)
                                val transport = transportState.value
                                sendCursorGoto(transport, x, y)
                                sendMouseButton(transport, MouseButton.LEFT, down = true)
                                sendMouseButton(transport, MouseButton.LEFT, down = false)
                            }
                            break
                        }
                    }
                }
            }
        }
    }
}

// ── Packet helpers ───────────────────────────────────────────────────────────

private val packetFactory = com.inputbridge.protocol.EventPacketFactory()
private val sendScope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)

private fun sendCursorGoto(
    transport: com.inputbridge.transport.wifi.UdpTransport?,
    x: Float, y: Float,
) {
    val packet = packetFactory.fromEvent(InputEvent.CursorGoto(x, y)) ?: return
    transport?.let { t ->
        sendScope.launch { runCatching { t.send(packet) } }
    }
}

private fun sendMouseMove(
    transport: com.inputbridge.transport.wifi.UdpTransport?,
    dx: Float, dy: Float,
) {
    val packet = packetFactory.fromEvent(InputEvent.MouseMove(dx, dy)) ?: return
    transport?.let { t ->
        sendScope.launch { runCatching { t.send(packet) } }
    }
}

private fun sendMouseButton(
    transport: com.inputbridge.transport.wifi.UdpTransport?,
    button: MouseButton, down: Boolean,
) {
    val event = if (down) InputEvent.MouseButtonDown(button)
    else InputEvent.MouseButtonUp(button)
    val packet = packetFactory.fromEvent(event) ?: return
    transport?.let { t ->
        sendScope.launch { runCatching { t.send(packet) } }
    }
}

private fun sendScroll(
    transport: com.inputbridge.transport.wifi.UdpTransport?,
    dx: Float, dy: Float,
) {
    val packet = packetFactory.fromEvent(InputEvent.Scroll(dx, dy)) ?: return
    transport?.let { t ->
        sendScope.launch { runCatching { t.send(packet) } }
    }
}
