package com.inputbridge

import android.app.Application
import android.os.Build
import com.inputbridge.core.logging.BridgeLogger
import com.inputbridge.diagnostics.DiagnosticsManager
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

/**
 * Unified Application entry point for InputBridge.
 * Supports both Bridge (USB host) and Receiver (input injection) modes.
 *
 * Global crash handler (BUG-044 fix):
 * Uncaught exceptions are written to BridgeLogger and surfaced in DiagnosticsManager
 * before the default handler takes over.
 */
class InputBridgeApplication : Application() {

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
                        receiverServiceRunning = false,
                    )
                }
            } catch (_: Exception) {
                // Never let the crash handler itself crash — swallow and fall through.
            }
            previousCrashHandler?.uncaughtException(thread, throwable)
        }

        startKoin {
            androidContext(this@InputBridgeApplication)
            modules(bridgeModule, receiverModule)
        }
    }
}