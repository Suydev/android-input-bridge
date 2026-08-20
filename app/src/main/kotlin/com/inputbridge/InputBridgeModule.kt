package com.inputbridge

import com.inputbridge.bridge.prefs.BridgePreferences
import com.inputbridge.bridge.viewmodel.BridgeViewModel
import com.inputbridge.receiver.prefs.ReceiverPreferences
import com.inputbridge.receiver.viewmodel.ReceiverViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

/**
 * Unified Koin module combining bridge and receiver modules.
 */
val inputBridgeModule = module {
    // Bridge preferences
    single { BridgePreferences(androidContext()) }

    // Receiver preferences
    single { ReceiverPreferences(androidContext()) }

    // ViewModels
    viewModel { BridgeViewModel(androidContext(), get()) }
    viewModel { ReceiverViewModel(androidContext(), get()) }
}