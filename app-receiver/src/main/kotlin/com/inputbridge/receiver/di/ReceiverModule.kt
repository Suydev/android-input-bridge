package com.inputbridge.receiver

import com.inputbridge.receiver.prefs.ReceiverPreferences
import com.inputbridge.receiver.viewmodel.ReceiverViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin dependency injection module for the receiver app.
 */
val receiverModule = module {
    // Persistent config — shared between ViewModel (writes) and ReceiverService (reads)
    single { ReceiverPreferences(androidContext()) }

    viewModel { ReceiverViewModel(androidContext(), get()) }
}
