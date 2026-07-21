package com.inputbridge.bridge

import android.app.Application
import com.inputbridge.core.logging.BridgeLogger
import com.inputbridge.diagnostics.DiagnosticsManager
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

/**
 * Application entry point for the bridge app.
 * Initialises logging, dependency injection, and the global crash handler.
 *
 * Global crash handler (BUG-044 fix):
 * Uncaught exceptions are written to BridgeLogger and surfaced in DiagnosticsManager
 * before the default handler takes over. This ensures crashes are always visible in
 * the diagnostics screen if the user reopens the app.
 */
class BridgeApplication : Application() {

    private val previousCrashHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun onCreate() {
        super.onCreate()
        BridgeLogger.init(isDebug = BuildConfig.DEBUG)

        // Register global crash handler before Koin starts so DI failures are caught too.
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                BridgeLogger.e(
                    "CRASH",
                    "Uncaught exception on thread '${thread.name}'",
                    throwable,
                )
                DiagnosticsManager.update {
                    copy(
                        lastError = "CRASH [${throwable.javaClass.simpleName}]: " +
                            (throwable.message?.take(120) ?: "no message"),
                        bridgeServiceRunning = false,
                    )
                }
            } catch (_: Exception) {
                // Never let the crash handler itself crash — swallow and fall through.
            }
            previousCrashHandler?.uncaughtException(thread, throwable)
        }

        startKoin {
            androidContext(this@BridgeApplication)
            modules(bridgeModule)
        }
    }
}
