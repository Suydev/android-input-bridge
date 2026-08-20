package com.inputbridge.bridge.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.inputbridge.bridge.prefs.BridgePreferences
import com.inputbridge.bridge.service.BridgeService
import com.inputbridge.core.config.AppRole
import com.inputbridge.core.config.AppRoleStore
import com.inputbridge.core.logging.BridgeLogger

/**
 * Restarts the bridge service after device reboot.
 *
 * Phase 7: auto-start behaviour is now controlled by [BridgePreferences.autoStartOnBoot]
 * (user-toggleable in Settings → System) rather than the compile-time FeatureFlags constant.
 * Defaults to enabled so existing users keep the same behaviour after upgrade.
 *
 * BUG-141 FIX (single-APK merge): the receiver service is registered for BOOT_COMPLETED too, so
 * this receiver only starts BridgeService when the user picked Bridge Mode on this
 * device ([AppRoleStore]). Starting both roles on the same device races them for the
 * shared discovery port 54322 and the bridge "auto-discovers" its own receiver.
 *
 * Note: on MIUI and some other ROMs, autostart must also be allowed in the
 * system settings. The Permissions screen guides the user through this.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON") return

        if (AppRoleStore.get(context) != AppRole.BRIDGE) {
            BridgeLogger.i("BridgeBootReceiver", "Device is not in bridge role — skipping")
            return
        }

        val prefs = BridgePreferences(context)
        if (!prefs.autoStartOnBoot) {
            BridgeLogger.i("BridgeBootReceiver", "Auto-start disabled by user — skipping")
            return
        }

        BridgeLogger.i("BridgeBootReceiver", "Boot completed — starting bridge service")
        runCatching {
            context.startForegroundService(Intent(context, BridgeService::class.java))
        }.onFailure {
            BridgeLogger.e(
                "BridgeBootReceiver",
                "Auto-start rejected by the system; open the app to start manually",
                it,
            )
        }
    }
}
