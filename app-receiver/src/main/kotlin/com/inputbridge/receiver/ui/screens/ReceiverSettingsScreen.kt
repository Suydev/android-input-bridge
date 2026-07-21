package com.inputbridge.receiver.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inputbridge.receiver.ui.theme.*
import com.inputbridge.receiver.viewmodel.ReceiverViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiverSettingsScreen(onBack: () -> Unit, viewModel: ReceiverViewModel) {
    val config         by viewModel.config.collectAsStateWithLifecycle()
    val context        = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var portInput by remember { mutableStateOf(config.transport.port.toString()) }

    // Live check for canDrawOverlays — re-evaluated on each ON_RESUME
    var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canDrawOverlays = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontFamily = FontFamily.Monospace) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = ReceiverOnSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ReceiverSurface, titleContentColor = ReceiverOnSurface)
            )
        },
        containerColor = ReceiverBackground,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            // ── Transport ─────────────────────────────────────────────────────
            SectionHeader("Transport")

            OutlinedTextField(
                value = portInput,
                onValueChange = {
                    portInput = it
                    it.toIntOrNull()?.let { p -> viewModel.setListenPort(p) }
                },
                label = { Text("Listen Port", fontFamily = FontFamily.Monospace) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                colors = receiverTextFieldColors(),
            )
            Text(
                "Must match the port configured in the bridge app. Default: 54321.",
                color = ReceiverDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            )

            HorizontalDivider(color = ReceiverDim.copy(alpha = 0.3f))

            // ── Mouse ─────────────────────────────────────────────────────────
            SectionHeader("Mouse")

            Text(
                "Pointer Sensitivity: %.1f×".format(config.mouse.sensitivity),
                color = ReceiverOnSurface, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
            )
            Slider(
                value = config.mouse.sensitivity,
                onValueChange = { viewModel.setMouseSensitivity(it) },
                valueRange = 0.1f..5.0f,
                steps = 48,
                colors = SliderDefaults.colors(
                    thumbColor = ReceiverPrimary,
                    activeTrackColor = ReceiverPrimary,
                    inactiveTrackColor = ReceiverDim.copy(alpha = 0.3f),
                ),
            )
            Text(
                "Adjust if the pointer feels too fast or too slow. Changes apply immediately.",
                color = ReceiverDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            )

            HorizontalDivider(color = ReceiverDim.copy(alpha = 0.3f))

            // ── Display / cursor overlay ──────────────────────────────────────
            SectionHeader("Display")

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Cursor Dot Overlay",
                            color = ReceiverOnSurface, fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            "Show a floating crosshair at the virtual cursor position.",
                            color = ReceiverDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        )
                    }
                    Switch(
                        checked = config.display.showCursorOverlay && canDrawOverlays,
                        onCheckedChange = { want ->
                            if (want && !canDrawOverlays) {
                                // Permission not granted — open system overlay settings
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}"),
                                    )
                                )
                            } else {
                                viewModel.setCursorOverlayEnabled(want)
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor    = ReceiverPrimary,
                            checkedTrackColor    = ReceiverPrimary.copy(alpha = 0.3f),
                            checkedBorderColor   = ReceiverPrimary,
                            uncheckedThumbColor  = ReceiverDim,
                            uncheckedTrackColor  = ReceiverBackground,
                            uncheckedBorderColor = ReceiverDim,
                        ),
                    )
                }

                if (!canDrawOverlays && config.display.showCursorOverlay) {
                    Text(
                        "⚠  'Display over other apps' permission required. Tap the toggle to open settings.",
                        color = ReceiverWarning, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        lineHeight = 16.sp,
                    )
                } else if (config.display.showCursorOverlay && canDrawOverlays) {
                    Text(
                        "Overlay active — a green dot shows where clicks will land.",
                        color = ReceiverPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    )
                }
            }

            HorizontalDivider(color = ReceiverDim.copy(alpha = 0.3f))

            // ── System ────────────────────────────────────────────────────────
            SectionHeader("System")

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Auto-start on Boot",
                            color = ReceiverOnSurface, fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            "Start the receiver service automatically after the device reboots.",
                            color = ReceiverDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        )
                    }
                    Switch(
                        checked = config.display.autoStartOnBoot,
                        onCheckedChange = { viewModel.setAutoStartOnBoot(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor    = ReceiverPrimary,
                            checkedTrackColor    = ReceiverPrimary.copy(alpha = 0.3f),
                            checkedBorderColor   = ReceiverPrimary,
                            uncheckedThumbColor  = ReceiverDim,
                            uncheckedTrackColor  = ReceiverBackground,
                            uncheckedBorderColor = ReceiverDim,
                        ),
                    )
                }
            }

            Text(
                "Emergency stop: hold Volume Down for 3 seconds to stop the receiver immediately.",
                color = ReceiverDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                lineHeight = 16.sp,
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        color = ReceiverPrimary,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 2.sp,
    )
}

@Composable
private fun receiverTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor   = ReceiverPrimary,
    unfocusedBorderColor = ReceiverDim,
    focusedLabelColor    = ReceiverPrimary,
    unfocusedLabelColor  = ReceiverDim,
    focusedTextColor     = ReceiverOnSurface,
    unfocusedTextColor   = ReceiverOnSurface,
    cursorColor          = ReceiverPrimary,
)
