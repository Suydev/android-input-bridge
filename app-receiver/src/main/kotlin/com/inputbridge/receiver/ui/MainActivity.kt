package com.inputbridge.receiver.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.inputbridge.receiver.ui.screens.*
import com.inputbridge.receiver.ui.theme.ReceiverTheme
import com.inputbridge.receiver.viewmodel.ReceiverViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Single-activity host for the receiver app.
 *
 * Phase 7 additions:
 * - Volume-Down hold (3 seconds) triggers emergency stop of the receiver service.
 * - Portrait lock removed from manifest — the receiver tablet should support both
 *   orientations freely.
 *
 * Bug fixes:
 * - BUG-020: Added ReceiverRoute.PERMISSIONS and corresponding composable to give
 *   the receiver app a first-class permissions guidance screen (battery opt,
 *   POST_NOTIFICATIONS, overlay).
 */
class MainActivity : ComponentActivity() {

    private val viewModel: ReceiverViewModel by viewModel()

    // ── POST_NOTIFICATIONS runtime permission (Android 13+) ───────────────────

    /**
     * Proactively request POST_NOTIFICATIONS so the foreground service notification
     * is visible on Android 13+ (OnePlus Pad Go target). Without this the
     * persistent notification is silently suppressed.
     */
    private val notificationPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    // ── Emergency stop via Volume Down hold ───────────────────────────────────

    @Volatile private var volumeDownPressedAt = 0L
    private var emergencyStopJob: Job? = null

    private companion object {
        const val EMERGENCY_HOLD_MS = 3_000L
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()

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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
                    this@MainActivity,
                    "Emergency stop — receiver service stopped",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            return true  // consume to suppress volume change
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
            if (heldMs < 500L) return false  // short press: pass to system volume handler
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
}

object ReceiverRoute {
    const val WELCOME       = "welcome"
    const val CONNECTION    = "connection"
    const val ACCESSIBILITY = "accessibility"
    const val PERMISSIONS   = "permissions"   // BUG-020 FIX: new route
    const val SETTINGS      = "settings"
    const val DIAGNOSTICS   = "diagnostics"
}
