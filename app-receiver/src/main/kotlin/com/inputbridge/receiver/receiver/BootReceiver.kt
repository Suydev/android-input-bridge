package com.inputbridge.receiver.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.inputbridge.core.config.FeatureFlags
import com.inputbridge.core.logging.BridgeLogger
import com.inputbridge.receiver.service.ReceiverService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!FeatureFlags.AUTO_START_ON_BOOT) return
        BridgeLogger.i("ReceiverBoot", "Boot completed — starting receiver service")
        context.startForegroundService(Intent(context, ReceiverService::class.java))
    }
}
