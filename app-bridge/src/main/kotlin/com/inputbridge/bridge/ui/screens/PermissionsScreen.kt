package com.inputbridge.bridge.ui.screens

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
import com.inputbridge.bridge.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val context        = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // ── Live permission state ─────────────────────────────────────────────────

    var batteryOptIgnored by remember { mutableStateOf(false) }
    var btConnectGranted  by remember { mutableStateOf(false) }
    var nearbyWifiGranted by remember { mutableStateOf(false) }
    var notifGranted      by remember { mutableStateOf(false) }

    fun refreshPermissions() {
        val pm = context.getSystemService(PowerManager::class.java)
        batteryOptIgnored = pm?.isIgnoringBatteryOptimizations(context.packageName) == true

        btConnectGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        } else true   // pre-S: BLUETOOTH_CONNECT doesn't exist, BT uses old permissions

        nearbyWifiGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) ==
                    PackageManager.PERMISSION_GRANTED
        } else true   // pre-T: NEARBY_WIFI_DEVICES doesn't exist

        notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        } else true   // pre-T: no notification permission needed
    }

    // Re-check permissions whenever we resume (user may have granted in Settings)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Initial check
    LaunchedEffect(Unit) { refreshPermissions() }

    // ── Runtime permission launchers ──────────────────────────────────────────

    val btConnectLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> btConnectGranted = granted }

    val nearbyWifiLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> nearbyWifiGranted = granted }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notifGranted = granted }

    // ── UI ────────────────────────────────────────────────────────────────────

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Permissions", fontFamily = FontFamily.Monospace) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = BridgeOnSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BridgeSurface, titleContentColor = BridgeOnSurface)
            )
        },
        containerColor = BridgeBackground,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            // ── USB Host ──────────────────────────────────────────────────────
            PermissionItem(
                title       = "USB Host Access",
                description = "Required to read keyboard and mouse input from the USB receiver. " +
                        "Granted automatically via the USB device intent-filter.",
                granted     = true,
                action      = null,
            )

            // ── Battery Optimization ──────────────────────────────────────────
            PermissionItem(
                title       = "Battery Optimization Exemption",
                description = "Required to keep the bridge alive when the screen is off. " +
                        "Without this, Android may kill the service in the background.",
                granted     = batteryOptIgnored,
                action      = {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                    )
                },
                actionLabel = "Open Battery Settings",
            )

            // ── Bluetooth Connect (API 31+) ────────────────────────────────
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PermissionItem(
                    title       = "Bluetooth Connect",
                    description = "Required only if you enable Bluetooth HID mode. " +
                            "Allows the bridge app to connect to paired BT devices.",
                    granted     = btConnectGranted,
                    action      = {
                        btConnectLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    },
                    actionLabel = "Grant Permission",
                )
            }

            // ── Nearby Wi-Fi Devices (API 33+) ────────────────────────────
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PermissionItem(
                    title       = "Nearby Wi-Fi Devices",
                    description = "Required for Wi-Fi Direct mode (future). Not needed for " +
                            "standard UDP mode on the same LAN or hotspot.",
                    granted     = nearbyWifiGranted,
                    action      = {
                        nearbyWifiLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
                    },
                    actionLabel = "Grant Permission",
                )
            }

            // ── Post Notifications (API 33+) ─────────────────────────────
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PermissionItem(
                    title       = "Post Notifications",
                    description = "Required to show the persistent foreground-service notification " +
                            "that keeps the bridge alive and lets you stop it from the shade.",
                    granted     = notifGranted,
                    action      = {
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                    actionLabel = "Grant Permission",
                )
            }

            HorizontalDivider(color = BridgeDim.copy(alpha = 0.3f))

            // ── MIUI / ColorOS note ───────────────────────────────────────────
            Card(colors = CardDefaults.cardColors(containerColor = BridgeSurface)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "MIUI / ColorOS", color = BridgeWarning, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace, letterSpacing = 2.sp,
                    )
                    Text(
                        "On Xiaomi and Realme devices you must also enable 'Autostart' manually:\n" +
                                "Settings → Apps → InputBridge → Autostart → ON\n\n" +
                                "Without this, the service may be killed when the screen turns off.",
                        color = BridgeDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        lineHeight = 17.sp,
                    )
                    TextButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent().apply {
                                        setClassName(
                                            "com.miui.securitycenter",
                                            "com.miui.permcenter.autostart.AutoStartManagementActivity"
                                        )
                                    }
                                )
                            }.onFailure {
                                // Fall back to app info if MIUI activity not found
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                )
                            }
                        }
                    ) {
                        Text("Open Autostart Settings", color = BridgeWarning,
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun PermissionItem(
    title: String,
    description: String,
    granted: Boolean,
    action: (() -> Unit)?,
    actionLabel: String = "",
) {
    Card(colors = CardDefaults.cardColors(containerColor = BridgeSurface)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector   = if (granted) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint          = if (granted) BridgePrimary else BridgeWarning,
                    modifier      = Modifier.size(18.dp),
                )
                Text(title, color = BridgeOnSurface, fontFamily = FontFamily.Monospace)
            }
            Text(description, color = BridgeDim, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, lineHeight = 17.sp)
            if (action != null && !granted) {
                TextButton(onClick = action) {
                    Text(actionLabel, color = BridgePrimary, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
