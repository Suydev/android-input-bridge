package com.inputbridge.bridge

import com.inputbridge.bridge.prefs.BridgePreferences
import com.inputbridge.bridge.viewmodel.BridgeViewModel
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin dependency injection module for the bridge app.
 */
val bridgeModule = module {
    // Persistent config — shared between ViewModel (writes) and BridgeService (reads)
    single { BridgePreferences(get()) }

    viewModel { BridgeViewModel(androidContext(), get()) }
}
