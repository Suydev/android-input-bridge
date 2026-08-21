package com.inputbridge.ui.receiver

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.inputbridge.bridge.service.BridgeService
import com.inputbridge.receiver.ui.screens.*
import com.inputbridge.receiver.ui.theme.ReceiverTheme
import com.inputbridge.receiver.viewmodel.ReceiverViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Receiver Mode Activity - runs on the tablet.
 * Receives input events via UDP and injects via Accessibility + Shizuku.
 */
class ReceiverModeActivity : ComponentActivity() {

    private val viewModel: ReceiverViewModel by viewModel()

    // ── Emergency stop via Volume Down hold ───────────────────────────────────

    @Volatile private var volumeDownPressedAt = 0L
    private var emergencyStopJob: Job? = null

    private companion object {
        const val EMERGENCY_HOLD_MS = 3_000L
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // BUG-184 FIX: singleTask re-delivery skips onCreate — keep the opposite role stopped
        runCatching { stopService(Intent(this, com.inputbridge.bridge.service.BridgeService::class.java)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // BUG-188: OEM battery managers kill the foreground service on swipe-away;
        // request the exemption so the receiver survives exiting the app.
        runCatching {
            val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:$packageName"),
                    )
                )
            }
        }
        // BUG-141 FIX (single-APK merge): only ONE role may run per device. If the bridge
        // service was auto-started on this receiver tablet, stop it so it can't
        // race for discovery port 54322 or read this device's USB as the bridge.
        runCatching { stopService(Intent(this, BridgeService::class.java)) }
            .onFailure { android.util.Log.w("ReceiverModeActivity", "Failed to stop BridgeService: ${it.message}") }

        setContent {
            ReceiverTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = ReceiverRoute.WELCOME) {
                    composable(ReceiverRoute.WELCOME) {
                        WelcomeScreen(
                            onStart         = { navController.navigate(ReceiverRoute.CONNECTION) },
                            onAccessibility = { navController.navigate(ReceiverRoute.ACCESSIBILITY) },
                            onSettings      = { navController.navigate(ReceiverRoute.SETTINGS) },
                            onPermissions   = { navController.navigate(ReceiverRoute.PERMISSIONS) },
                            viewModel       = viewModel,
                        )
                    }
                    composable(ReceiverRoute.CONNECTION) {
                        ConnectionScreen(
                            onSettings    = { navController.navigate(ReceiverRoute.SETTINGS) },
                            onDiagnostics = { navController.navigate(ReceiverRoute.DIAGNOSTICS) },
                            onTrackpad    = { navController.navigate(ReceiverRoute.TRACKPAD) },
                            viewModel     = viewModel,
                        )
                    }
                    composable(ReceiverRoute.ACCESSIBILITY) {
                        AccessibilitySetupScreen(onBack = { navController.popBackStack() })
                    }
                    // BUG-020 FIX: receiver now has a dedicated permissions screen.
                    composable(ReceiverRoute.PERMISSIONS) {
                        ReceiverPermissionsScreen(onBack = { navController.popBackStack() })
                    }
                    composable(ReceiverRoute.SETTINGS) {
                        ReceiverSettingsScreen(
                            onBack    = { navController.popBackStack() },
                            viewModel = viewModel,
                        )
                    }
                    composable(ReceiverRoute.TRACKPAD) {
                        TrackpadScreen(
                            onBack    = { navController.popBackStack() },
                            viewModel = viewModel,
                        )
                    }
                    composable(ReceiverRoute.DIAGNOSTICS) {
                        ReceiverDiagnosticsScreen(
                            onBack    = { navController.popBackStack() },
                            viewModel = viewModel,
                        )
                    }
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
                viewModel.stopReceiver()
                Toast.makeText(
                    this@ReceiverModeActivity,
                    "Emergency stop — receiver service stopped",
                    Toast.LENGTH_SHORT,
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
}

object ReceiverRoute {
    const val WELCOME       = "welcome"
    const val CONNECTION    = "connection"
    const val ACCESSIBILITY = "accessibility"
    const val PERMISSIONS   = "permissions"
    const val SETTINGS      = "settings"
    const val TRACKPAD      = "trackpad"
    const val DIAGNOSTICS   = "diagnostics"
}