package com.inputbridge.receiver.ui.screens

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
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

/**
 * Full-screen trackpad overlay for the receiver tablet.
 *
 * Covers the ENTIRE screen (edge-to-edge, behind system bars) on any device
 * (OnePlus Pad Go, Redmi 9, etc.). Touch coordinates are normalized to 0-1
 * and sent as CursorGoto / MouseMove / MouseButton / Scroll packets to the
 * bridge phone for local cursor injection.
 *
 * Gesture handling uses a SINGLE pointerInput block with custom detection
 * to prevent tap-after-drag (unwanted click at drag release position).
 */
@Composable
fun TrackpadScreen(
    onBack: () -> Unit,
    viewModel: ReceiverViewModel,
) {
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
    val view = LocalView.current

    // useUpdatedState: always read the latest transport value, never capture stale null
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

    // Virtual cursor position (normalized 0-1)
    var cursorX by remember { mutableFloatStateOf(0.5f) }
    var cursorY by remember { mutableFloatStateOf(0.5f) }
    var isTouching by remember { mutableStateOf(false) }

    // Track parent Box size for cursor positioning
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // ── Trackpad touch area (full screen, edge-to-edge) ──────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { boxSize = it }
                // SINGLE pointerInput block — custom gesture detection that
                // distinguishes tap from drag to prevent tap-after-drag.
                .pointerInput(Unit) {
                    awaitPointerAccelerationScope(
                        transportState,
                        { cursorX },
                        { cursorY },
                        { v -> cursorX = v },
                        { v -> cursorY = v },
                        { v -> isTouching = v },
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
                            androidx.compose.foundation.shape.CircleShape,
                        ),
                )
            }
        }

        // ── Top bar (overlay, auto-hides after 3s) ──────────────────────────
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
                "Drag to move · Tap to click · Long press for right click",
                color = ReceiverDim.copy(alpha = 0.5f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .padding(bottom = 16.dp),
            )
        }
    }
}

/**
 * Custom gesture detection in a SINGLE pointerInput block.
 * Tracks total movement to distinguish tap from drag.
 * Tap = short press with < TAP_THRESHOLD_PX movement.
 * Drag = any movement exceeding threshold.
 * Long press = held > 500ms with < threshold movement.
 */
private suspend fun PointerInputScope.awaitPointerAccelerationScope(
    transportState: State<com.inputbridge.transport.wifi.UdpTransport?>,
    getCursorX: () -> Float,
    getCursorY: () -> Float,
    setCursorX: (Float) -> Unit,
    setCursorY: (Float) -> Unit,
    setTouching: (Boolean) -> Unit,
) {
    coroutineScope {
        // Run tap detection and drag detection as parallel coroutines
        // within the same pointerInput scope.
        // Both share the same pointer event stream via the PointerInputScope.
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
        launch {
            // Custom tap/long-press detection with movement threshold
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val downTime = System.currentTimeMillis()
                val startX = down.position.x
                val startY = down.position.y
                var totalMovement = 0f
                var longPressFired = false

                // Track movement until pointer up
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: continue

                    if (change.pressed) {
                        val movX = change.position.x - startX
                        val movY = change.position.y - startY
                        totalMovement = abs(movX) + abs(movY)

                        // Long press detection (500ms hold with minimal movement)
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
                        // Pointer up — fire tap if movement was small
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

// ── Packet helpers (run on IO to avoid blocking the UI thread) ───────────────

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
