package com.inputbridge.bridge

import android.app.Application
import com.inputbridge.core.logging.BridgeLogger
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

/**
 * Application entry point for the bridge app.
 * Initialises logging, dependency injection, and any global state.
 */
class BridgeApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        BridgeLogger.init(isDebug = BuildConfig.DEBUG)
        startKoin {
            androidContext(this@BridgeApplication)
            modules(bridgeModule)
        }
    }
}
