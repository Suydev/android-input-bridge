package com.inputbridge.receiver.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.inputbridge.receiver.ui.theme.*

/**
 * BUG-020 FIX: New dedicated permissions screen for the receiver app.
 *
 * The bridge app has had a PermissionsScreen since Phase 5. The receiver app had no
 * equivalent, leaving users with no guidance for:
 *   - Battery optimization exemption (critical for background survival on MIUI/ColorOS)
 *   - POST_NOTIFICATIONS (Android 13+, required to show the service notification)
 *   - SYSTEM_ALERT_WINDOW (cursor dot overlay)
 *   - Accessibility service (already guided by AccessibilitySetupScreen, linked here too)
 *
 * Each item shows a ✓/⚠ indicator and a direct "Open Settings" action. State refreshes
 * automatically every time the user returns from system settings (ON_RESUME observer).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiverPermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var batteryOptIgnored by remember { mutableStateOf(false) }
    var notifGranted      by remember { mutableStateOf(false) }
    var overlayGranted    by remember { mutableStateOf(false) }

    fun refreshPermissions() {
        val pm = context.getSystemService(PowerManager::class.java)
        batteryOptIgnored = pm?.isIgnoringBatteryOptimizations(context.packageName) == true

        notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            true // Not required below API 33
        }

        overlayGranted = Settings.canDrawOverlays(context)
    }

    // Re-check on every resume so the UI reflects changes made in system settings.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) { refreshPermissions() }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notifGranted = granted }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Permissions", fontFamily = FontFamily.Monospace) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack, "Back",
                            tint = ReceiverOnSurface,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor    = ReceiverSurface,
                    titleContentColor = ReceiverOnSurface,
                ),
            )
        },
        containerColor = ReceiverBackground,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            // ── Accessibility service ─────────────────────────────────────────
            ReceiverPermItem(
                title       = "Accessibility Service",
                description = "REQUIRED. Allows the receiver to inject keyboard and mouse " +
                        "events into any app. Without this, nothing works.\n\n" +
                        "Tap below → find 'InputBridge Input Controller' → enable it.",
                granted     = false, // Always show the button — can't detect state here.
                alwaysShowAction = true,
                action = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
                actionLabel = "Open Accessibility Settings",
            )

            // ── Battery optimization ──────────────────────────────────────────
            ReceiverPermItem(
                title       = "Battery Optimization Exemption",
                description = "CRITICAL. Without this, MIUI/ColorOS/OxygenOS will kill " +
                        "the receiver service within minutes of the screen turning off.\n\n" +
                        "Tap 'Allow' when the system dialog appears.",
                granted     = batteryOptIgnored,
                action = {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                    )
                },
                actionLabel = "Request Exemption",
            )

            // ── POST_NOTIFICATIONS (API 33+) ──────────────────────────────────
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ReceiverPermItem(
                    title       = "Show Notifications (Android 13+)",
                    description = "Required to display the persistent receiver-service notification " +
                            "that keeps the service alive and visible in the notification shade.",
                    granted     = notifGranted,
                    action      = {
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                    actionLabel = "Grant Permission",
                )
            }

            // ── Overlay for cursor dot ────────────────────────────────────────
            ReceiverPermItem(
                title       = "Display Over Other Apps",
                description = "Optional — only needed for the cursor dot overlay. " +
                        "Enables a small crosshair that shows where the next click will land.",
                granted     = overlayGranted,
                action = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        )
                    )
                },
                actionLabel = "Open Overlay Settings",
            )

            HorizontalDivider(color = ReceiverDim.copy(alpha = 0.3f))

            // ── MIUI / OxygenOS extra step ────────────────────────────────────
            Card(colors = CardDefaults.cardColors(containerColor = ReceiverSurface)) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "MIUI / OXYGEN OS / REALME UI",
                        color = ReceiverWarning, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace, letterSpacing = 2.sp,
                    )
                    Text(
                        "These launchers have an additional 'Autostart' toggle that " +
                                "overrides the battery-opt exemption above.\n\n" +
                                "To enable it:\n" +
                                "  • MIUI: Settings → Apps → InputBridge → Other permissions → Autostart → ON\n" +
                                "  • ColorOS: Settings → Battery → App quick-freeze → remove InputBridge\n" +
                                "  • OxygenOS: Settings → Battery → App management → InputBridge → Allow background activity",
                        color    = ReceiverDim,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 17.sp,
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ReceiverPermItem(
    title: String,
    description: String,
    granted: Boolean,
    action: () -> Unit,
    actionLabel: String,
    alwaysShowAction: Boolean = false,
) {
    Card(colors = CardDefaults.cardColors(containerColor = ReceiverSurface)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (granted) ReceiverPrimary else ReceiverWarning,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    title,
                    color = ReceiverOnSurface,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )
            }
            Text(
                description,
                color = ReceiverDim, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, lineHeight = 17.sp,
            )
            if (!granted || alwaysShowAction) {
                TextButton(
                    onClick = action,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                ) {
                    Text(
                        actionLabel,
                        color = ReceiverPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}
