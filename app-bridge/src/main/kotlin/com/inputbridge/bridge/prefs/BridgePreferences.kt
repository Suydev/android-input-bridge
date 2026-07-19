package com.inputbridge.bridge.prefs

import android.content.Context

/**
 * Lightweight SharedPreferences wrapper for persisting bridge configuration.
 *
 * Used by [BridgeService] to read the target IP and port at service start,
 * and by [BridgeViewModel] to persist user changes from the Settings screen.
 *
 * Will be migrated to DataStore in Phase 7 when all settings are fully wired.
 */
class BridgePreferences(context: Context) {

    private val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    /** IP address of the receiver device. Empty string = not configured. */
    var targetIp: String
        get() = prefs.getString(KEY_TARGET_IP, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TARGET_IP, value).apply()

    /** UDP port to send input packets to. Defaults to 54321. */
    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    companion object {
        private const val PREF_FILE = "bridge_config"
        private const val KEY_TARGET_IP = "target_ip"
        private const val KEY_PORT = "port"
        const val DEFAULT_PORT = 54321
    }
}
