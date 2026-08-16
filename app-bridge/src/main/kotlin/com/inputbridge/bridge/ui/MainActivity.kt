package com.inputbridge.bridge.ui

import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
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
import com.inputbridge.bridge.ui.screens.*
import com.inputbridge.bridge.ui.theme.BridgeTheme
import com.inputbridge.bridge.viewmodel.BridgeViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Single-activity host. All navigation is in Compose.
 *
 * Phase 7 additions:
 * - keepScreenOn controlled by [BridgePreferences.keepScreenOn] pref.
 * - Volume-Down hold (3 seconds) triggers emergency stop of the bridge service.
 *
 * Routes:
 *   welcome       → first launch, mode selection
 *   bridge        → active bridge screen (mostly black)
 *   settings      → transport config, sensitivity, display settings
 *   diagnostics   → live status, latency, error log
 *   permissions   → guided permission checklist
 *   about         → version, build info
 */
class MainActivity : ComponentActivity() {

    private val viewModel: BridgeViewModel by viewModel()

    // BUG-057 fix: inject the Koin-managed BridgePreferences singleton rather than
    // creating a fresh instance with `BridgePreferences(this)` (Activity context) in
    // applyKeepScreenOn(). Using a fresh instance bypasses DI and uses an Activity context
    // where the Application context is correct; the Koin singleton was already created with
    // androidContext() (Application context) in BridgeModule.
    private val prefs: BridgePreferences by inject()

    // ── Emergency stop via Volume Down hold ───────────────────────────────────

    /** Timestamp when Volume Down was first pressed (elapsedRealtime). 0 = not pressed. */
    @Volatile private var volumeDownPressedAt = 0L

    /** Coroutine that fires the emergency stop after the hold duration elapses. */
    private var emergencyStopJob: Job? = null

    private companion object {
        private const val TAG = "MainActivity"
        /** How long to hold Volume Down to trigger emergency stop (ms). */
        const val EMERGENCY_HOLD_MS = 3_000L
    }

    // ── Activity lifecycle ────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyKeepScreenOn()

        setContent {
            BridgeTheme {
                val navController = rememberNavController()
                NavHost(
                    navController = navController,
                    startDestination = BridgeRoute.WELCOME,
                ) {
                    composable(BridgeRoute.WELCOME) {
                        WelcomeScreen(
                            onContinue    = { navController.navigate(BridgeRoute.BRIDGE) {
                                launchSingleTop = true
                            } },
                            onSettings    = { navController.navigate(BridgeRoute.SETTINGS) {
                                launchSingleTop = true
                            } },
                            onPermissions = { navController.navigate(BridgeRoute.PERMISSIONS) {
                                launchSingleTop = true
                            } },
                            viewModel     = viewModel,
                        )
                    }
                    composable(BridgeRoute.BRIDGE) {
                        BridgeScreen(
                            onSettings    = { navController.navigate(BridgeRoute.SETTINGS) {
                                launchSingleTop = true
                            } },
                            onDiagnostics = { navController.navigate(BridgeRoute.DIAGNOSTICS) {
                                launchSingleTop = true
                            } },
                            onMouse       = { navController.navigate(BridgeRoute.TRACKPAD) {
                                launchSingleTop = true
                            } },
                            viewModel     = viewModel,
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
                            onBack    = { navController.popBackStack() },
                            viewModel = viewModel,
                        )
                    }
                    composable(BridgeRoute.DIAGNOSTICS) {
                        DiagnosticsScreen(
                            onBack    = { navController.popBackStack() },
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

        // BUG-079 FIX: do not launch a runtime permission dialog during activity startup.
        // PermissionsScreen owns POST_NOTIFICATIONS and launches it from the user's tap.
    }

    override fun onResume() {
        super.onResume()
        // Re-apply keep-screen-on in case the pref changed while away.
        applyKeepScreenOn()
        // BUG-101 FIX: scan for USB HID devices when the activity resumes.
        // When the app is already running and a USB device is plugged in, the system
        // calls onResume() on the existing activity — NOT onCreate(). Without this scan,
        // USB detection depends entirely on the BroadcastReceiver which may not fire
        // (device class=0, RECEIVER_NOT_EXPORTED, or device was pre-attached).
        scanUsbAndStartIfNeeded()
    }

    /**
     * BUG-101 FIX: scan UsbManager.deviceList for HID devices and start the bridge
     * service if one is found and the service is not already running.
     *
     * This handles the case where:
     * - User plugs in USB dongle while the app is already in the foreground
     * - The ATTACHED broadcast was missed or blocked
     * - The device was pre-attached before the service started
     */
    private fun scanUsbAndStartIfNeeded() {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        val deviceList = usbManager.deviceList
        for ((name, device) in deviceList) {
            for (i in 0 until device.interfaceCount) {
                if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_HID) {
                    Log.i(TAG, "Activity.onResume: HID device found — $name " +
                        "(vendor=${device.vendorId}, product=${device.productId})")
                    // Start the bridge service — it will handle USB permission and capture.
                    // If the service is already running, startForegroundService is a no-op.
                    try {
                        startForegroundService(Intent(this, BridgeService::class.java))
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start bridge service from onResume: ${e.message}")
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
                    this@MainActivity,
                    "Emergency stop — bridge service stopped",
                    Toast.LENGTH_SHORT
                ).show()
            }
            return true  // consume event (suppress volume change)
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
            // Short press (<500 ms): let the system handle it as a normal volume key
            if (heldMs < 500L) {
                return false  // don't consume — pass to system volume handler
            }
            return true  // medium hold consumed but not long enough for stop
        }
        return super.onKeyUp(keyCode, event)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun applyKeepScreenOn() {
        // prefs is the Koin-injected singleton — see field declaration above (BUG-057 fix).
        if (prefs.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

object BridgeRoute {
    const val WELCOME     = "welcome"
    const val BRIDGE      = "bridge"
    const val SETTINGS    = "settings"
    const val DIAGNOSTICS = "diagnostics"
    const val PERMISSIONS = "permissions"
    const val ABOUT       = "about"
    const val TRACKPAD    = "trackpad"
}
