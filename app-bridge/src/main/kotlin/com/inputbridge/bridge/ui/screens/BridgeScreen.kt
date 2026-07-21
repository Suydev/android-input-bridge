package com.inputbridge.bridge.ui.screens

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inputbridge.bridge.ui.theme.*
import com.inputbridge.bridge.viewmodel.BridgeViewModel

/**
 * Active bridge screen — shown while the bridge is running.
 *
 * Intentionally minimal: mostly black with status indicators.
 * Design goal: absolute minimum light emission.
 *
 * Phase 7 additions:
 * - Black-screen mode: when enabled, hides ALL chrome and dims the window to
 *   hardware-minimum brightness. The screen shows only the STOP touch target.
 * - Brightness control: applies [DisplayConfig.screenBrightness] to the window.
 * - Latency visibility: respects [DisplayConfig.showLatencyOverlay].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BridgeScreen(
    onSettings: () -> Unit,
    onDiagnostics: () -> Unit,
    viewModel: BridgeViewModel,
) {
    val diagnostics        by viewModel.diagnostics.collectAsStateWithLifecycle()
    val isBridgeActive     by viewModel.isBridgeActive.collectAsStateWithLifecycle()
    val connectionLabel    by viewModel.connectionLabel.collectAsStateWithLifecycle()
    val config             by viewModel.config.collectAsStateWithLifecycle()

    val blackScreenMode    = config.display.blackScreenMode
    val showLatency        = config.display.showLatencyOverlay
    val screenBrightness   = config.display.screenBrightness

    // ── Window brightness effect ──────────────────────────────────────────────
    val activity = LocalContext.current as? Activity
    DisposableEffect(blackScreenMode, screenBrightness) {
        activity?.window?.let { win ->
            val lp = win.attributes
            lp.screenBrightness = when {
                blackScreenMode         -> 0.001f   // hardware minimum (0 may turn off backlight)
                screenBrightness >= 0f  -> screenBrightness
                else                    -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            win.attributes = lp
        }
        onDispose {
            // Restore to system default when leaving this screen
            activity?.window?.let { win ->
                val lp = win.attributes
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                win.attributes = lp
            }
        }
    }

    // ── Black screen mode: pure black, minimal content ────────────────────────
    if (blackScreenMode) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            // Single tap-anywhere-to-stop concept — show only a faint indicator
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val dotColor = if (diagnostics.transportConnected) BridgePrimary else BridgeError
                Box(
                    Modifier.size(6.dp).background(
                        dotColor, androidx.compose.foundation.shape.CircleShape
                    )
                )
                Text(
                    text = if (diagnostics.transportConnected)
                        if (diagnostics.usbDeviceConnected) "●" else "○"
                    else "×",
                    color = BridgeDim.copy(alpha = 0.4f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }

            TextButton(
                onClick = { viewModel.stopBridge() },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            ) {
                Text(
                    "STOP", color = BridgeError.copy(alpha = 0.5f),
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 3.sp,
                )
            }
        }
        return
    }

    // ── Normal mode ───────────────────────────────────────────────────────────

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
    ) {
        // Top bar — tiny icons only
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.End,
        ) {
            IconButton(onClick = onDiagnostics) {
                Icon(Icons.Default.BugReport, contentDescription = "Diagnostics",
                    tint = BridgeDim, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings",
                    tint = BridgeDim, modifier = Modifier.size(20.dp))
            }
        }

        // Centre status cluster
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Mode badge
            Text(
                text = diagnostics.transportMode,
                color = BridgeDim,
                fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp,
            )

            // Connection status dot + label
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val dotColor = when {
                    diagnostics.transportConnected -> BridgePrimary
                    diagnostics.bridgeServiceRunning -> BridgeWarning
                    else -> BridgeError
                }
                Box(
                    Modifier.size(8.dp).background(
                        dotColor, androidx.compose.foundation.shape.CircleShape
                    )
                )
                Text(
                    text = connectionLabel,
                    color = BridgeOnSurface,
                    fontSize = 14.sp, fontFamily = FontFamily.Monospace,
                )
            }

            // Latency display — only when enabled and data is available
            if (showLatency && diagnostics.transportConnected && diagnostics.latencyMs > 0) {
                val avgSuffix = if (diagnostics.latencyAvgMs > 0 &&
                    diagnostics.latencyAvgMs != diagnostics.latencyMs)
                    " · avg ${diagnostics.latencyAvgMs}ms" else ""
                Text(
                    text = "${diagnostics.latencyMs}ms$avgSuffix",
                    color = when {
                        diagnostics.latencyMs < 10  -> BridgePrimary
                        diagnostics.latencyMs < 30  -> BridgeOnSurface
                        else                         -> BridgeWarning
                    },
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                )
            }

            // USB status
            Text(
                text = if (diagnostics.usbDeviceConnected)
                    "⌨  ${diagnostics.usbDeviceName}" else "No USB device",
                color = if (diagnostics.usbDeviceConnected) BridgePrimary else BridgeDim,
                fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            )

            // Secure window warning
            if (diagnostics.isSecureWindow) {
                Text(
                    "⚠  Secure window — input blocked",
                    color = BridgeWarning, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                )
            }
        }

        // Emergency stop — bottom centre, always reachable
        TextButton(
            onClick = { viewModel.stopBridge() },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = "STOP",
                color = BridgeError, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 3.sp,
            )
        }

        // Start button (only when service is not active)
        if (!isBridgeActive) {
            Button(
                onClick = { viewModel.startBridge() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 80.dp)
                    .fillMaxWidth(0.5f),
                colors = ButtonDefaults.buttonColors(containerColor = BridgePrimary),
            ) {
                Text("START", color = Color.Black, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
