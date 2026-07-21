package com.inputbridge.receiver.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.inputbridge.core.logging.BridgeLogger
import com.inputbridge.receiver.prefs.ReceiverPreferences
import com.inputbridge.receiver.service.ReceiverService

/**
 * Restarts the receiver service after device reboot.
 *
 * Phase 7: auto-start behaviour is now controlled by [ReceiverPreferences.autoStartOnBoot]
 * (user-toggleable in Settings → System) rather than the compile-time FeatureFlags constant.
 * Defaults to enabled so existing users keep the same behaviour after upgrade.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = ReceiverPreferences(context)
        if (!prefs.autoStartOnBoot) {
            BridgeLogger.i("ReceiverBootReceiver", "Auto-start disabled by user — skipping")
            return
        }

        BridgeLogger.i("ReceiverBootReceiver", "Boot completed — starting receiver service")
        context.startForegroundService(Intent(context, ReceiverService::class.java))
    }
}
