package com.inputbridge.receiver.ui.screens

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
import com.inputbridge.receiver.ui.theme.*
import com.inputbridge.receiver.viewmodel.ReceiverViewModel

/**
 * Bug fixes applied here:
 *
 * BUG-017 FIX: "Boot Auto-start" status row was reading diagnostics.batteryOptimizationIgnored
 *   (a completely unrelated field). Now reads config.display.autoStartOnBoot — the actual
 *   boot-auto-start preference value.
 *
 * BUG-019/BUG-023 FIX: "Network Ready" was hardcoded true. Now reads
 *   viewModel.isNetworkAvailable which reflects real Wi-Fi/Ethernet connectivity.
 *
 * BUG-020 FIX: Added "Permissions" button linking to ReceiverPermissionsScreen. Also added
 *   an orange warning badge when critical permissions are missing (battery opt not exempted).
 *
 * The DisposableEffect lifecycle observer calls viewModel.refreshStatus() on every ON_RESUME,
 * so status rows update when the user returns from system settings.
 */
@Composable
fun WelcomeScreen(
    onStart: () -> Unit,
    onAccessibility: () -> Unit,
    onSettings: () -> Unit,
    onPermissions: () -> Unit,
    viewModel: ReceiverViewModel,
) {
    val diagnostics         by viewModel.diagnostics.collectAsStateWithLifecycle()
    val config              by viewModel.config.collectAsStateWithLifecycle()
    val isAccessibilityEnabled by viewModel.isAccessibilityEnabled.collectAsStateWithLifecycle()
    val isNetworkAvailable  by viewModel.isNetworkAvailable.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshStatus()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ReceiverBackground)
            .padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // ── Header ────────────────────────────────────────────────────────────
        Column {
            Spacer(Modifier.height(48.dp))
            Text(
                "InputBridge",
                color = ReceiverPrimary, fontSize = 32.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
            )
            Text(
                "Receiver",
                color = ReceiverDim, fontSize = 14.sp, fontFamily = FontFamily.Monospace,
            )
        }

        // ── Status rows ───────────────────────────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusItem("Accessibility Service", isAccessibilityEnabled)
            // BUG-019 FIX: was hardcoded true
            StatusItem("Network Ready", isNetworkAvailable)
            // BUG-017 FIX: was diagnostics.batteryOptimizationIgnored (wrong field)
            StatusItem("Boot Auto-start", config.display.autoStartOnBoot)
            // NEW: battery opt status (critical for background survival)
            StatusItem("Battery Opt Exempt", diagnostics.batteryOptimizationIgnored)
        }

        // ── Action buttons ────────────────────────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!isAccessibilityEnabled) {
                OutlinedButton(
                    onClick = onAccessibility,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ReceiverWarning),
                ) {
                    Text("Enable Accessibility Service", fontFamily = FontFamily.Monospace)
                }
            }
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = ReceiverPrimary),
            ) {
                Text(
                    "Start Receiver",
                    color = ReceiverBackground, fontFamily = FontFamily.Monospace,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TextButton(onClick = onPermissions) {
                    // Amber tint if battery opt not yet exempted — draws user attention.
                    val tint = if (!diagnostics.batteryOptimizationIgnored)
                        ReceiverWarning else ReceiverDim
                    Text(
                        "Permissions",
                        color = tint, fontFamily = FontFamily.Monospace,
                    )
                }
                TextButton(onClick = onSettings) {
                    Text("Settings", color = ReceiverDim, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun StatusItem(label: String, ok: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text  = if (ok) "✓" else "✗",
            color = if (ok) ReceiverPrimary else ReceiverError,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            label,
            color = if (ok) ReceiverOnSurface else ReceiverDim,
            fontSize = 14.sp, fontFamily = FontFamily.Monospace,
        )
    }
}
