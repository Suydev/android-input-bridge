package com.inputbridge.ui.bridge

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.inputbridge.bridge.prefs.BridgePreferences
import com.inputbridge.bridge.service.BridgeService
import com.inputbridge.bridge.ui.BridgeRoute
import com.inputbridge.bridge.ui.screens.*
import com.inputbridge.bridge.ui.theme.BridgeTheme
import com.inputbridge.bridge.viewmodel.BridgeViewModel
import com.inputbridge.receiver.service.ReceiverService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Bridge Mode Activity - runs on the phone with USB dongle.
 * Captures USB keyboard/mouse and forwards via Wi-Fi or Bluetooth HID.
 */
class BridgeModeActivity : ComponentActivity() {

    private val viewModel: BridgeViewModel by viewModel()

    private val prefs: BridgePreferences by inject()

    private companion object {
        private const val TAG = "BridgeModeActivity"
        const val EMERGENCY_HOLD_MS = 3_000L
        private const val ACTION_USB_PERMISSION = "com.inputbridge.USB_PERMISSION"
    }

    // ── Emergency stop via Volume Down hold ───────────────────────────────────
    @Volatile private var volumeDownPressedAt = 0L
    private var emergencyStopJob: Job? = null

    // ── Foreground USB permission requester ───────────────────────────────────
    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            } ?: return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted) {
                Log.i(TAG, "USB permission granted (foreground Activity) — starting bridge service")
                startBridgeService()
            } else {
                Log.w(TAG, "USB permission denied (foreground Activity)")
                Toast.makeText(
                    this@BridgeModeActivity,
                    "USB permission denied — cannot capture keyboard/mouse",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyKeepScreenOn()
        handleUsbLaunchIntent(intent)
        // BUG-141 FIX (single-APK merge): only ONE role may run per device. If the receiver
        // service was auto-started on this bridge phone (e.g. by boot or a previous
        // session on the same app), stop it so it can't hold discovery port 54322
        // and make the bridge "discover" its own device over loopback.
        runCatching { stopService(Intent(this, ReceiverService::class.java)) }
            .onFailure { Log.w(TAG, "Failed to stop ReceiverService: ${it.message}") }

        setContent {
            BridgeTheme {
                val navController = rememberNavController()
                NavHost(
                    navController = navController,
                    startDestination = BridgeRoute.WELCOME,
                ) {
                    composable(BridgeRoute.WELCOME) {
                        WelcomeScreen(
                            onContinue = { navController.navigate(BridgeRoute.BRIDGE) {
                                launchSingleTop = true
                            } },
                            onSettings = { navController.navigate(BridgeRoute.SETTINGS) {
                                launchSingleTop = true
                            } },
                            onPermissions = { navController.navigate(BridgeRoute.PERMISSIONS) {
                                launchSingleTop = true
                            } },
                            viewModel = viewModel,
                        )
                    }
                    composable(BridgeRoute.BRIDGE) {
                        BridgeScreen(
                            onSettings = { navController.navigate(BridgeRoute.SETTINGS) {
                                launchSingleTop = true
                            } },
                            onDiagnostics = { navController.navigate(BridgeRoute.DIAGNOSTICS) {
                                launchSingleTop = true
                            } },
                            onMouse = { navController.navigate(BridgeRoute.TRACKPAD) {
                                launchSingleTop = true
                            } },
                            viewModel = viewModel,
                        )
                    }
                    composable(BridgeRoute.TRACKPAD) {
                        BridgeTrackpadScreen(
                            onBack = { navController.popBackStack() },
                            prefs = prefs,
                        )
                    }
                    composable(BridgeRoute.SETTINGS) {
                        SettingsScreen(
                            onBack = { navController.popBackStack() },
                            viewModel = viewModel,
                        )
                    }
                    composable(BridgeRoute.DIAGNOSTICS) {
                        DiagnosticsScreen(
                            onBack = { navController.popBackStack() },
                            viewModel = viewModel,
                        )
                    }
                    composable(BridgeRoute.PERMISSIONS) {
                        PermissionsScreen(onBack = { navController.popBackStack() })
                    }
                    composable(BridgeRoute.ABOUT) {
                        AboutScreen(onBack = { navController.popBackStack() })
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyKeepScreenOn()
        scanUsbAndStartIfNeeded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // BUG-184 FIX: singleTask re-delivery skips onCreate — keep the opposite role stopped
        runCatching { stopService(Intent(this, com.inputbridge.receiver.service.ReceiverService::class.java)) }
        handleUsbLaunchIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbPermissionReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(usbPermissionReceiver) }
    }

    // ── USB permission & service start ────────────────────────────────────────

    private fun startBridgeService() {
        try {
            startForegroundService(Intent(this, BridgeService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start bridge service: ${e.message}")
        }
    }

    private fun requestUsbPermissionFromActivity(device: UsbDevice) {
        val usbManager = getSystemService(USB_SERVICE) as? UsbManager ?: return
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val pi = PendingIntent.getBroadcast(this, 1, Intent(ACTION_USB_PERMISSION), flags)
        usbManager.requestPermission(device, pi)
        Log.i(TAG, "USB permission requested from foreground Activity for ${device.deviceName}")
    }

    private fun handleUsbLaunchIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        } ?: return
        val usbManager = getSystemService(USB_SERVICE) as? UsbManager ?: return
        if (usbManager.hasPermission(device)) startBridgeService()
        else requestUsbPermissionFromActivity(device)
    }

    private fun scanUsbAndStartIfNeeded() {
        val usbManager = getSystemService(USB_SERVICE) as? UsbManager ?: return
        for ((name, device) in usbManager.deviceList) {
            for (i in 0 until device.interfaceCount) {
                if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_HID) {
                    Log.i(TAG, "Activity.onResume: HID device found — $name " +
                        "(vendor=${device.vendorId}, product=${device.productId})")
                    if (usbManager.hasPermission(device)) {
                        startBridgeService()
                    } else {
                        requestUsbPermissionFromActivity(device)
                    }
                    return
                }
            }
        }
    }

    // ── Volume-Down emergency stop ────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && event.repeatCount == 0) {
            volumeDownPressedAt = SystemClock.elapsedRealtime()
            emergencyStopJob?.cancel()
            emergencyStopJob = lifecycleScope.launch {
                delay(EMERGENCY_HOLD_MS)
                viewModel.stopBridge()
                Toast.makeText(
                    this@BridgeModeActivity,
                    "Emergency stop — bridge service stopped",
                    Toast.LENGTH_SHORT
                ).show()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val heldMs = if (volumeDownPressedAt > 0L)
                SystemClock.elapsedRealtime() - volumeDownPressedAt else 0L
            emergencyStopJob?.cancel()
            emergencyStopJob = null
            volumeDownPressedAt = 0L
            if (heldMs < 500L) {
                return false
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun applyKeepScreenOn() {
        if (prefs.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}