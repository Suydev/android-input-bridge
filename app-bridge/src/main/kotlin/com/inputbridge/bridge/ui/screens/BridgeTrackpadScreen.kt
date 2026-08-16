package com.inputbridge.bridge.ui.screens

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
import com.inputbridge.bridge.prefs.BridgePreferences
import com.inputbridge.bridge.ui.theme.*
import com.inputbridge.core.model.InputEvent
import com.inputbridge.core.model.MouseButton
import com.inputbridge.core.config.TransportConfig
import com.inputbridge.protocol.EventPacketFactory
import com.inputbridge.transport.wifi.UdpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

private const val DEADZONE_DP = 3f
private const val TAP_MAX_MS = 250L
private const val MOVE_THROTTLE_MS = 15L
private const val SCROLL_SENSITIVITY = 0.04f
private const val TWO_FINGER_TAP_MIN_MS = 500L
private const val TWO_FINGER_TAP_MAX_MS = 250L

/**
 * Bridge-side trackpad — sends InputEvents to receiver via UDP.
 *
 * Modeled after btmouse (BLE HID mouse emulator):
 * - Single-finger drag → relative cursor movement (MouseMove deltas)
 * - 1-finger tap → left click
 * - 2-finger tap → right click
 * - 3-finger tap → middle click
 * - 2-finger drag → scroll
 *
 * Creates its own independent UdpTransport to the receiver.
 */
@Composable
fun BridgeTrackpadScreen(
    onBack: () -> Unit,
    prefs: BridgePreferences,
) {
    val view = LocalView.current

    // Hide system bars
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

    // Transport state
    var isConnected by remember { mutableStateOf(false) }
    val transportState = remember { mutableStateOf<UdpTransport?>(null) }
    val packetFactory = remember { EventPacketFactory() }

    // Connect transport on mount
    DisposableEffect(Unit) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val job = scope.launch {
            val config = TransportConfig(
                targetIp = prefs.targetIp,
                port = prefs.port,
            )
            val transport = UdpTransport(config, isSender = true)
            val ok = transport.connect()
            if (ok) {
                transportState.value = transport
                isConnected = true
            }
        }
        onDispose {
            job.cancel()
            // BUG-XXX FIX: disconnect synchronously before cancelling scope.
            // The old code launched a coroutine then immediately cancelled the scope,
            // so the disconnect never executed. Use runBlocking on Dispatchers.IO so
            // the (potentially slow) socket close does not block the Main thread / ANR.
            val transport = transportState.value
            if (transport != null) {
                runCatching { kotlinx.coroutines.runBlocking(Dispatchers.IO) { transport.disconnect() } }
                transportState.value = null
            }
            scope.cancel()
        }
    }

    var isTouching by remember { mutableStateOf(false) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // ── Trackpad touch area ──────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { boxSize = it }
                .pointerInput(Unit) {
                    awaitTrackpadGesture(
                        boxSize = { boxSize },
                        density = density.density,
                        setTouching = { isTouching = it },
                        sendEvent = { event ->
                            val transport = transportState.value ?: return@awaitTrackpadGesture
                            val packet = packetFactory.fromEvent(event) ?: return@awaitTrackpadGesture
                            transport.sendDirect(packet)
                        },
                    )
                },
        )

        // ── Top bar ──────────────────────────────────────────────────────────
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
                        tint = BridgeDim, modifier = Modifier.size(24.dp),
                    )
                }
                Text(
                    "MOUSE",
                    color = BridgeDim, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace, letterSpacing = 3.sp,
                )
                Text(
                    if (isConnected) "Connected" else "Connecting…",
                    color = if (isConnected) BridgePrimary else BridgeDim,
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                )
            }
        }

        // ── Bottom hint ──────────────────────────────────────────────────────
        if (showBar) {
            Text(
                "1 finger: move · Tap: left · 2-finger tap: right\n3-finger tap: middle · 2 fingers: scroll",
                color = BridgeDim.copy(alpha = 0.5f),
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
 * btmouse-style gesture detection.
 *
 * Deadzone: 3dp, 250ms window for tap detection.
 * Finger count: 1 tap = left, 2 tap = right, 3 tap = middle.
 * Two-finger drag = scroll.
 */
private suspend fun PointerInputScope.awaitTrackpadGesture(
    boxSize: () -> IntSize,
    density: Float,
    setTouching: (Boolean) -> Unit,
    sendEvent: (InputEvent) -> Unit,
) {
    val deadzonePx = DEADZONE_DP * density

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        setTouching(true)
        val downX = down.position.x
        val downY = down.position.y
        val downTime = System.currentTimeMillis()
        var lastX = downX
        var lastY = downY
        var maxFingerIndex = 0
        var inDeadzone = true
        var lastMoveTime = 0L
        var isScrolling = false
        var lastScrollY = downY
        var secondFingerDownTime = 0L

        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }

            // All pointers lifted
            if (pressed.isEmpty()) {
                // BUG-XXX FIX: add time validation for multi-finger taps.
                // Without this, placing fingers and holding still could fire a tap
                // after the deadzone timer expired if inDeadzone was never cleared.
                val elapsed = System.currentTimeMillis() - downTime
                if (inDeadzone && elapsed <= TAP_MAX_MS) {
                    when (maxFingerIndex) {
                        0 -> {
                            sendEvent(InputEvent.MouseButtonDown(MouseButton.LEFT))
                            sendEvent(InputEvent.MouseButtonUp(MouseButton.LEFT))
                        }
                        1 -> {
                            sendEvent(InputEvent.MouseButtonDown(MouseButton.RIGHT))
                            sendEvent(InputEvent.MouseButtonUp(MouseButton.RIGHT))
                        }
                        2 -> {
                            sendEvent(InputEvent.MouseButtonDown(MouseButton.MIDDLE))
                            sendEvent(InputEvent.MouseButtonUp(MouseButton.MIDDLE))
                        }
                    }
                }
                break
            }

            // Track max finger index (0-based)
            val currentFingerCount = pressed.size
            val currentMaxIndex = currentFingerCount - 1
            if (currentMaxIndex > maxFingerIndex) {
                maxFingerIndex = currentMaxIndex
            }

            // Two-finger scroll: second finger down activates scroll mode
            if (currentFingerCount >= 2 && !isScrolling) {
                isScrolling = true
                lastScrollY = (pressed[0].position.y + pressed[1].position.y) / 2f
                secondFingerDownTime = System.currentTimeMillis()
            }

            if (isScrolling) {
                if (currentFingerCount < 2) {
                    // Second finger lifted → exit scroll mode
                    isScrolling = false
                    lastX = pressed.first().position.x
                    lastY = pressed.first().position.y
                } else {
                    // Two-finger scroll
                    val avgY = (pressed[0].position.y + pressed[1].position.y) / 2f
                    val rawDelta = (avgY - lastScrollY) / boxSize().height
                    val scrollDelta = rawDelta * SCROLL_SENSITIVITY
                    if (abs(scrollDelta) > 0.001f) {
                        sendEvent(InputEvent.Scroll(0f, scrollDelta))
                    }
                    lastScrollY = avgY
                    event.changes.forEach { it.consume() }
                    continue
                }
            }

            // Single-finger movement
            val change = pressed.first()
            val dx = change.position.x - lastX
            val dy = change.position.y - lastY
            val movement = sqrt(dx * dx + dy * dy)

            // Deadzone check: within 3dp and 250ms = still potential tap
            if (inDeadzone) {
                val totalDx = change.position.x - downX
                val totalDy = change.position.y - downY
                val totalMovement = sqrt(totalDx * totalDx + totalDy * totalDy)
                val elapsed = System.currentTimeMillis() - downTime

                if (totalMovement > deadzonePx || elapsed > TAP_MAX_MS) {
                    // Exited deadzone → now in drag mode
                    inDeadzone = false
                    lastX = change.position.x
                    lastY = change.position.y
                }
            }

            if (!inDeadzone && movement > 0.5f) {
                // Throttle: minimum 15ms between MOVE events
                val now = System.currentTimeMillis()
                if (now - lastMoveTime >= MOVE_THROTTLE_MS) {
                    val ndx = dx / boxSize().width
                    val ndy = dy / boxSize().height
                    sendEvent(InputEvent.MouseMove(ndx, ndy))
                    lastMoveTime = now
                }
                lastX = change.position.x
                lastY = change.position.y
            }
        }

        setTouching(false)
    }
}
