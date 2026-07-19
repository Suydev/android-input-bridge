package com.inputbridge.bridge

import com.inputbridge.bridge.prefs.BridgePreferences
import com.inputbridge.bridge.viewmodel.BridgeViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin dependency injection module for the bridge app.
 */
val bridgeModule = module {
    // Persistent config — shared between ViewModel (writes) and BridgeService (reads)
    single { BridgePreferences(androidContext()) }

    viewModel { BridgeViewModel(androidContext(), get()) }
}
