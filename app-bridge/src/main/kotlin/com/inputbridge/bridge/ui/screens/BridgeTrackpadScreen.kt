package com.inputbridge.bridge.ui.screens

import android.app.Activity
import android.os.Build
import android.view.View
import android.widget.Button
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.inputbridge.bridge.prefs.BridgePreferences
import com.inputbridge.bridge.ui.PointerCaptureTrackpadView
import com.inputbridge.bridge.ui.UnifiedTrackpadTransport
import com.inputbridge.bridge.ui.theme.*
import com.inputbridge.core.config.TransportConfig
import com.inputbridge.protocol.EventPacketFactory
import com.inputbridge.transport.bt.BluetoothHidTransport
import com.inputbridge.transport.wifi.UdpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Bridge-side trackpad using Android's pointer capture API.
 * 
 * Features:
 * - Absolute cursor positioning (1:1 mapping, reach every corner)
 * - Pointer capture API for external mouse support
 * - Direct transport calls (sendDirect) for minimal latency
 * - Supports both Bluetooth HID and WiFi UDP transports
 * - Visual cursor with click ripple feedback
 */
@Composable
fun BridgeTrackpadScreen(
    onBack: () -> Unit,
    prefs: BridgePreferences,
) {
    val view = LocalView.current

    // Hide system bars for immersive trackpad
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

    // Transport state - supports both WiFi and Bluetooth HID
    var isConnected by remember { mutableStateOf(false) }
    var useHidMode by remember { mutableStateOf(false) }

    val transportState = remember { mutableStateOf<UdpTransport?>(null) }
    val hidTransportState = remember { mutableStateOf<BluetoothHidTransport?>(null) }
    val packetFactory = remember { EventPacketFactory() }
    val unifiedTransport = remember { 
        UnifiedTrackpadTransport(
            hidTransport = hidTransportState.value,
            udpTransport = transportState.value,
            packetFactory = packetFactory
        )
    }

    // Sync unified transport with current transports
    DisposableEffect(transportState.value, hidTransportState.value) {
        unifiedTransport.hidTransport = hidTransportState.value
        unifiedTransport.udpTransport = transportState.value
    }

    // Connect WiFi transport on mount
    DisposableEffect(Unit) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val job = scope.launch {
            while (true) {
                val ip = prefs.targetIp
                if (ip.isNotBlank()) {
                    val config = TransportConfig(targetIp = ip, port = prefs.port)
                    val transport = UdpTransport(config, isSender = true)
                    if (transport.connect()) {
                        transportState.value = transport
                        unifiedTransport.udpTransport = transport
                        isConnected = true
                        break
                    }
                }
                kotlinx.coroutines.delay(1500)
            }
        }
        onDispose {
            job.cancel()
            val transport = transportState.value
            if (transport != null) {
                CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                    runCatching { transport.disconnect() }
                }
                transportState.value = null
            }
            scope.cancel()
        }
    }

    // Bluetooth HID transport connection
    val btService = inject<BluetoothHidTransport>()
    DisposableEffect(Unit) {
        // Check if BT HID is available and connect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && btService.isFeatureSupported) {
            scope.launch {
                btService.connect()
                hidTransportState.value = btService
                unifiedTransport.hidTransport = btService
            }
        }
    }

    // Switch between HID and WiFi mode
    val modeSwitchText = if (useHidMode) "HID Mode" else "WiFi Mode"

    var showBar by remember { mutableStateOf(true) }
    val density = LocalDensity.current

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // ── Pointer Capture Trackpad View ─────────────────────────────────────
        AndroidView(
            factory = { context ->
                PointerCaptureTrackpadView(context).apply {
                    transport = unifiedTransport
                    sensitivity = prefs.trackpadSensitivity
                    // Request pointer capture for external mouse support
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        requestPointerCapture()
                    }
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    // Trackpad size available here if needed
                }
        )

        // ── Top bar ──────────────────────────────────────────────────────────
        var showBar by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(3000)
            showBar = false
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
                    "TRACKPAD",
                    color = BridgeDim, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace, letterSpacing = 3.sp,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (isConnected || hidTransportState.value?.isConnected == true) "Connected" else "Connecting…",
                        color = if (isConnected || hidTransportState.value?.isConnected == true) BridgePrimary else BridgeDim,
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    )
                    // Mode switch button
                    Button(
                        onClick = { useHidMode = !useHidMode; unifiedTransport.setMode(useHidMode) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (useHidMode) BridgePrimary else BridgeDim.copy(alpha = 0.3f),
                            contentColor = if (useHidMode) Color.White else BridgeDim
                        )
                    ) {
                        Text(modeSwitchText, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        // ── Bottom hint ──────────────────────────────────────────────────────
        if (showBar) {
            Text(
                "1 finger: move · Tap: left click\n2-finger tap: right click · 3-finger: middle\n2 fingers drag: scroll\nExternal mouse: auto-captures",
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