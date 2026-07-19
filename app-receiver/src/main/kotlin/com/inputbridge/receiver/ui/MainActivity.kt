package com.inputbridge.receiver.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.inputbridge.receiver.ui.screens.*
import com.inputbridge.receiver.ui.theme.ReceiverTheme
import com.inputbridge.receiver.viewmodel.ReceiverViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel

class MainActivity : ComponentActivity() {

    private val viewModel: ReceiverViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ReceiverTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = ReceiverRoute.WELCOME) {
                    composable(ReceiverRoute.WELCOME) {
                        WelcomeScreen(
                            onStart = { navController.navigate(ReceiverRoute.CONNECTION) },
                            onAccessibility = { navController.navigate(ReceiverRoute.ACCESSIBILITY) },
                            onSettings = { navController.navigate(ReceiverRoute.SETTINGS) },
                            viewModel = viewModel,
                        )
                    }
                    composable(ReceiverRoute.CONNECTION) {
                        ConnectionScreen(
                            onSettings = { navController.navigate(ReceiverRoute.SETTINGS) },
                            onDiagnostics = { navController.navigate(ReceiverRoute.DIAGNOSTICS) },
                            viewModel = viewModel,
                        )
                    }
                    composable(ReceiverRoute.ACCESSIBILITY) {
                        AccessibilitySetupScreen(onBack = { navController.popBackStack() })
                    }
                    composable(ReceiverRoute.SETTINGS) {
                        ReceiverSettingsScreen(
                            onBack = { navController.popBackStack() },
                            viewModel = viewModel,
                        )
                    }
                    composable(ReceiverRoute.DIAGNOSTICS) {
                        ReceiverDiagnosticsScreen(
                            onBack = { navController.popBackStack() },
                            viewModel = viewModel,
                        )
                    }
                }
            }
        }
    }
}

object ReceiverRoute {
    const val WELCOME       = "welcome"
    const val CONNECTION    = "connection"
    const val ACCESSIBILITY = "accessibility"
    const val SETTINGS      = "settings"
    const val DIAGNOSTICS   = "diagnostics"
}
