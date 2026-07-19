package com.inputbridge.bridge.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.inputbridge.bridge.service.BridgeService
import com.inputbridge.core.config.FeatureFlags
import com.inputbridge.core.logging.BridgeLogger

/**
 * Restarts the bridge service after device reboot.
 * Only starts if [FeatureFlags.AUTO_START_ON_BOOT] is enabled.
 *
 * Note: on MIUI and some other ROMs, autostart must also be allowed in the
 * system settings. The setup screen guides the user through this.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON") return

        if (!FeatureFlags.AUTO_START_ON_BOOT) {
            BridgeLogger.i("BootReceiver", "Auto-start disabled — skipping")
            return
        }

        BridgeLogger.i("BootReceiver", "Boot completed — starting bridge service")
        context.startForegroundService(Intent(context, BridgeService::class.java))
    }
}
