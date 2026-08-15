package com.inputbridge.receiver.ui.screens

import android.app.Activity
import androidx.compose.foundation.background
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.inputbridge.core.model.InputEvent
import com.inputbridge.receiver.ui.theme.*
import com.inputbridge.receiver.viewmodel.ReceiverViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Full-screen trackpad overlay for the receiver tablet.
 *
 * Covers the ENTIRE screen (edge-to-edge, behind system bars) on any device
 * (OnePlus Pad Go, Redmi 9, etc.). Touch coordinates are normalized to 0-1
 * and sent as CursorGoto / MouseMove / MouseButton / Scroll packets to the
 * bridge phone for local cursor injection.
 *
 * Touch mapping (per Android docs):
 * - GestureDescription coordinates are in screen pixels (absolute display coords).
 * - We normalize touch to 0-1 so the bridge can map to ANY screen size.
 *
 * Features:
 * - Single-finger drag → MouseMove (relative delta, normalized)
 * - Single-finger tap → CursorGoto + left click
 * - Two-finger vertical drag → Scroll
 * - Long press → right click
 * - Visual cursor dot shows current position
 */
@Composable
fun TrackpadScreen(
    onBack: () -> Unit,
    viewModel: ReceiverViewModel,
) {
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
    val transport = viewModel.trackpadTransport
    val scope = rememberCoroutineScope()
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
        // Make status bar and navigation bar transparent
        window?.let {
            WindowCompat.setDecorFitsSystemWindows(it, false)
            it.statusBarColor = android.graphics.Color.TRANSPARENT
            it.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            window?.let {
                WindowCompat.setDecorFitsSystemWindows(it, true)
            }
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
        // Uses Modifier.fillMaxSize() which fills the entire window including
        // behind system bars when edge-to-edge is enabled.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { boxSize = it }
                // Single-finger tap → click at position
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { offset ->
                            val x = offset.x / size.width
                            val y = offset.y / size.height
                            cursorX = x
                            cursorY = y
                            sendCursorGoto(transport, x, y)
                            sendMouseButton(transport, button = 0, down = true)
                            sendMouseButton(transport, button = 0, down = false)
                        },
                        onLongPress = { offset ->
                            val x = offset.x / size.width
                            val y = offset.y / size.height
                            cursorX = x
                            cursorY = y
                            sendCursorGoto(transport, x, y)
                            sendMouseButton(transport, button = 1, down = true)
                            sendMouseButton(transport, button = 1, down = false)
                        },
                    )
                }
                // Single-finger drag → relative mouse move
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            isTouching = true
                            val x = offset.x / size.width
                            val y = offset.y / size.height
                            cursorX = x
                            cursorY = y
                            sendCursorGoto(transport, x, y)
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            // Normalize delta to 0-1 range relative to screen size
                            val dx = dragAmount.x / size.width
                            val dy = dragAmount.y / size.height
                            sendMouseMove(transport, dx, dy)
                            cursorX = (cursorX + dx).coerceIn(0f, 1f)
                            cursorY = (cursorY + dy).coerceIn(0f, 1f)
                        },
                        onDragEnd = { isTouching = false },
                        onDragCancel = { isTouching = false },
                    )
                },
        ) {
            // ── Virtual cursor dot ───────────────────────────────────────────
            // Position is calculated as percentage of the parent Box size.
            // boxSize tracks the full screen dimensions via onSizeChanged.
            if (boxSize.width > 0 && boxSize.height > 0) {
                Box(
                    modifier = Modifier
                        .offset(
                            x = with(density) {
                                (cursorX * boxSize.width - 12.dp).toDp()
                            },
                            y = with(density) {
                                (cursorY * boxSize.height - 12.dp).toDp()
                            },
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
    button: Int, down: Boolean,
) {
    val event = if (down) InputEvent.MouseButtonDown(button)
    else InputEvent.MouseButtonUp(button)
    val packet = packetFactory.fromEvent(event) ?: return
    transport?.let { t ->
        sendScope.launch { runCatching { t.send(packet) } }
    }
}
