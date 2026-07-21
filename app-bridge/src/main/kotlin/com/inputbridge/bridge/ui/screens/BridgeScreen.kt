package com.inputbridge.bridge.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inputbridge.bridge.ui.theme.*
import com.inputbridge.bridge.viewmodel.BridgeViewModel

/**
 * Active bridge screen — shown while the bridge is running (or about to start).
 *
 * Black-screen mode: when config.display.blackScreenMode == true, the entire
 * composable tree collapses to a pitch-black Box (no icons, no text, no buttons).
 * Window brightness is also clamped to near-zero via DisposableEffect on the
 * WindowManager LayoutParams.
 *
 * UX fix: START button changed from solid bright-green (Button) to OutlinedButton.
 * The solid bright-green button was visually jarring against the dark terminal
 * background (visible in user screenshots). The outlined style is consistent with
 * the app's monochrome terminal aesthetic while remaining clearly tappable.
 */
@Composable
fun BridgeScreen(
    onDiagnostics: () -> Unit,
    onSettings: () -> Unit,
    viewModel: BridgeViewModel,
) {
    val diagnostics    by viewModel.diagnostics.collectAsStateWithLifecycle()
    val config         by viewModel.config.collectAsStateWithLifecycle()
    val connectionLabel by viewModel.connectionLabel.collectAsStateWithLifecycle()
    val isBridgeActive by viewModel.isBridgeActive.collectAsStateWithLifecycle()

    // Apply window brightness override. -1 = system default (no override).
    val view = LocalView.current
    DisposableEffect(config.display.screenBrightness, config.display.blackScreenMode) {
        val window = (view.context as? android.app.Activity)?.window
        if (window != null) {
            val targetBrightness = when {
                config.display.blackScreenMode -> 0.01f
                config.display.screenBrightness < 0f -> android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                else -> config.display.screenBrightness
            }
            window.attributes = window.attributes.also { it.screenBrightness = targetBrightness }
        }
        onDispose {
            val window2 = (view.context as? android.app.Activity)?.window
            window2?.attributes = window2?.attributes?.also {
                it.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            } ?: window2?.attributes
        }
    }

    // Black-screen mode: render nothing except a pitch-black screen.
    if (config.display.blackScreenMode) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black))
        return
    }

    Box(modifier = Modifier.fillMaxSize().background(BridgeBackground)) {
        // ── Top toolbar ───────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .align(Alignment.TopEnd),
            horizontalArrangement = Arrangement.End,
        ) {
            IconButton(onClick = onDiagnostics) {
                Icon(
                    Icons.Default.BugReport, "Diagnostics",
                    tint = BridgeDim, modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onSettings) {
                Icon(
                    Icons.Default.Settings, "Settings",
                    tint = BridgeDim, modifier = Modifier.size(20.dp),
                )
            }
        }

        // ── Centre status cluster ─────────────────────────────────────────────
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Transport mode label
            Text(
                diagnostics.transportMode.ifEmpty { "UDP" },
                color = BridgeDim, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 2.sp,
            )

            // Connection dot + label
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val dotColor = when {
                    diagnostics.transportConnected -> BridgePrimary
                    diagnostics.isReconnecting     -> BridgeWarning
                    diagnostics.bridgeServiceRunning -> BridgeWarning
                    else                           -> BridgeError
                }
                Box(
                    Modifier
                        .size(8.dp)
                        .background(dotColor, androidx.compose.foundation.shape.CircleShape)
                )
                Text(
                    connectionLabel,
                    color = BridgeOnSurface,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }

            // USB status
            Text(
                "USB: ${diagnostics.usbDeviceName.ifEmpty { "No USB device" }}",
                color = if (diagnostics.usbDeviceConnected) BridgeOnSurface else BridgeDim,
                fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            )

            // Latency overlay (optional)
            if (config.display.showLatencyOverlay && diagnostics.transportConnected) {
                Text(
                    "↕ ${diagnostics.latencyMs}ms  avg ${diagnostics.latencyAvgMs}ms",
                    color = BridgeDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                )
            }

            // Last error (shown below status when present)
            diagnostics.lastError?.let { err ->
                Spacer(Modifier.height(8.dp))
                Text(
                    err,
                    color = BridgeError, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        }

        // ── Start button (outlined, terminal-style) ───────────────────────────
        // UX FIX: replaced solid bright-green Button with OutlinedButton.
        // The solid bright-green was visually inconsistent with the dark terminal theme.
        if (!isBridgeActive) {
            OutlinedButton(
                onClick = { viewModel.startBridge() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 80.dp)
                    .fillMaxWidth(0.5f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = BridgePrimary),
                border = BorderStroke(1.dp, BridgePrimary.copy(alpha = 0.8f)),
            ) {
                Text(
                    "START",
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 3.sp,
                )
            }
        }

        // STOP (text button, always red, only shown when active)
        if (isBridgeActive || diagnostics.bridgeServiceRunning) {
            TextButton(
                onClick = { viewModel.stopBridge() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp),
            ) {
                Text(
                    "STOP",
                    color = BridgeError, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, letterSpacing = 3.sp,
                )
            }
        }
    }
}
