package com.inputbridge.bridge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inputbridge.bridge.ui.theme.*
import com.inputbridge.bridge.viewmodel.BridgeViewModel
import com.inputbridge.core.config.TransportMode

/**
 * Welcome screen: first launch experience.
 * Shows mode selection and a permission status summary before starting.
 *
 * Phase 7: Only the two implemented transport modes (UDP + BT HID) are shown.
 * WIFI_DIRECT and TCP are stubs not yet wired; hiding them prevents confusion.
 *
 * BUG-019/BUG-023 FIX: "Network" status row was hardcoded to true. Now reads
 *   viewModel.isNetworkAvailable which checks real Wi-Fi/Ethernet connectivity.
 *
 * The DisposableEffect lifecycle observer calls viewModel.refreshStatus() on every
 * ON_RESUME so the status row updates when the user returns from system settings.
 */
@Composable
fun WelcomeScreen(
    onContinue: () -> Unit,
    onSettings: () -> Unit,
    onPermissions: () -> Unit,
    viewModel: BridgeViewModel,
) {
    val diagnostics        by viewModel.diagnostics.collectAsStateWithLifecycle()
    val config             by viewModel.config.collectAsStateWithLifecycle()
    val isNetworkAvailable by viewModel.isNetworkAvailable.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshStatus()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Only show implemented transport modes
    val availableModes = listOf(TransportMode.UDP, TransportMode.BLUETOOTH_HID)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BridgeBackground)
            .padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // Header
        Column {
            Spacer(Modifier.height(48.dp))
            Text(
                text = "InputBridge",
                color = BridgePrimary,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = "Offline Input Bridge",
                color = BridgeDim,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
            )
        }

        // Transport mode selector
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "Transport Mode",
                color = BridgeOnSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            availableModes.forEach { mode ->
                val selected = config.transport.mode == mode
                OutlinedButton(
                    onClick = { viewModel.setTransportMode(mode) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (selected) BridgePrimary else BridgeDim,
                    ),
                ) {
                    Column {
                        // BUG-062 FIX: exhaustive when — no else. Compiler now enforces that
                        // every new TransportMode gets a display string (§4.2 invariant).
                        Text(
                            text = when (mode) {
                                TransportMode.UDP           -> "UDP  —  Wi-Fi LAN"
                                TransportMode.BLUETOOTH_HID -> "BT HID  —  Bluetooth Keyboard+Mouse"
                                TransportMode.WIFI_DIRECT   -> "Wi-Fi Direct (coming soon)"
                                TransportMode.TCP           -> "TCP (coming soon)"
                            },
                            fontFamily = FontFamily.Monospace,
                            fontSize   = 13.sp,
                        )
                        if (selected) {
                            Text(
                                text = when (mode) {
                                    TransportMode.UDP           -> "Low latency · Requires receiver app on tablet"
                                    TransportMode.BLUETOOTH_HID -> "Real cursor · Works with any BT device · No receiver app needed"
                                    TransportMode.WIFI_DIRECT,
                                    TransportMode.TCP           -> "Not yet implemented"
                                },
                                color      = BridgeDim,
                                fontSize   = 10.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }

        // Permission / status summary
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusRow("USB Device",           diagnostics.usbDeviceConnected)
            // BUG-019 FIX: was hardcoded true
            StatusRow("Network",              isNetworkAvailable)
            StatusRow("Battery Optimization", diagnostics.batteryOptimizationIgnored)
        }

        // Action buttons
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = BridgePrimary),
            ) {
                Text("Start Bridge", color = BridgeBackground, fontFamily = FontFamily.Monospace)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TextButton(onClick = onPermissions) {
                    val tint = if (!diagnostics.batteryOptimizationIgnored)
                        BridgeWarning else BridgeDim
                    Text("Permissions", color = tint, fontFamily = FontFamily.Monospace)
                }
                TextButton(onClick = onSettings) {
                    Text("Settings", color = BridgeDim, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text  = if (ok) "✓" else "✗",
            color = if (ok) BridgePrimary else BridgeError,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text  = label,
            color = if (ok) BridgeOnSurface else BridgeDim,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}
