package com.inputbridge.bridge

import com.inputbridge.bridge.viewmodel.BridgeViewModel
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin dependency injection module for the bridge app.
 * Wire up repositories, services, and ViewModels here.
 */
val bridgeModule = module {
    viewModel { BridgeViewModel(androidContext()) }
}
