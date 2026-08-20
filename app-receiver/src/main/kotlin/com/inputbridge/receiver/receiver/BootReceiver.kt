package com.inputbridge.receiver.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.inputbridge.core.config.AppRole
import com.inputbridge.core.config.AppRoleStore
import com.inputbridge.core.logging.BridgeLogger
import com.inputbridge.receiver.prefs.ReceiverPreferences
import com.inputbridge.receiver.service.ReceiverService

/**
 * Restarts the receiver service after device reboot.
 *
 * Phase 7: auto-start behaviour is now controlled by [ReceiverPreferences.autoStartOnBoot]
 * (user-toggleable in Settings → System) rather than the compile-time FeatureFlags constant.
 * Defaults to enabled so existing users keep the same behaviour after upgrade.
 *
 * BUG-141 FIX (single-APK merge): the bridge service is registered for BOOT_COMPLETED too, so this
 * receiver only starts ReceiverService when the user picked Receiver Mode on this device
 * ([AppRoleStore]). Starting both roles on the same device races them for the shared
 * discovery port 54322 and makes the bridge "discover" its own in-process receiver.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        if (AppRoleStore.get(context) != AppRole.RECEIVER) {
            BridgeLogger.i("ReceiverBootReceiver", "Device is not in receiver role — skipping")
            return
        }

        val prefs = ReceiverPreferences(context)
        if (!prefs.autoStartOnBoot) {
            BridgeLogger.i("ReceiverBootReceiver", "Auto-start disabled by user — skipping")
            return
        }

        BridgeLogger.i("ReceiverBootReceiver", "Boot completed — starting receiver service")
        runCatching {
            context.startForegroundService(Intent(context, ReceiverService::class.java))
        }.onFailure {
            BridgeLogger.e(
                "ReceiverBootReceiver",
                "Auto-start rejected by the system; open the app to start manually",
                it,
            )
        }
    }
}
