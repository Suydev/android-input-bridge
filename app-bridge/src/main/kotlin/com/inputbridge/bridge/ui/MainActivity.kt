package com.inputbridge.bridge.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.inputbridge.bridge.ui.screens.*
import com.inputbridge.bridge.ui.theme.BridgeTheme
import com.inputbridge.bridge.viewmodel.BridgeViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Single-activity host. All navigation is in Compose.
 *
 * Routes:
 *   welcome       → first launch, mode selection
 *   bridge        → active bridge screen (mostly black)
 *   settings      → transport config, sensitivity, auto-start
 *   diagnostics   → live status, latency, error log
 *   permissions   → guided permission checklist
 *   about         → version, build info
 */
class MainActivity : ComponentActivity() {

    private val viewModel: BridgeViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Keep screen on while the bridge UI is in foreground
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            BridgeTheme {
                val navController = rememberNavController()
                NavHost(
                    navController = navController,
                    startDestination = BridgeRoute.WELCOME,
                ) {
                    composable(BridgeRoute.WELCOME) {
                        WelcomeScreen(
                            onContinue = { navController.navigate(BridgeRoute.BRIDGE) },
                            onSettings = { navController.navigate(BridgeRoute.SETTINGS) },
                            onPermissions = { navController.navigate(BridgeRoute.PERMISSIONS) },
                            viewModel = viewModel,
                        )
                    }
                    composable(BridgeRoute.BRIDGE) {
                        BridgeScreen(
                            onSettings = { navController.navigate(BridgeRoute.SETTINGS) },
                            onDiagnostics = { navController.navigate(BridgeRoute.DIAGNOSTICS) },
                            viewModel = viewModel,
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
                        PermissionsScreen(
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(BridgeRoute.ABOUT) {
                        AboutScreen(
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
            }
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
}
