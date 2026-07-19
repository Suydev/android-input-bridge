package com.inputbridge.receiver

import android.app.Application
import com.inputbridge.core.logging.BridgeLogger
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class ReceiverApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        BridgeLogger.init(isDebug = BuildConfig.DEBUG)
        startKoin {
            androidContext(this@ReceiverApplication)
            modules(receiverModule)
        }
    }
}
