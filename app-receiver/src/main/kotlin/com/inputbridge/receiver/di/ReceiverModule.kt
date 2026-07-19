package com.inputbridge.receiver

import com.inputbridge.receiver.viewmodel.ReceiverViewModel
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val receiverModule = module {
    viewModel { ReceiverViewModel(androidContext()) }
}
