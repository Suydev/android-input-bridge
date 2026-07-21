package com.inputbridge.bridge.prefs

import android.content.Context

/**
 * Lightweight SharedPreferences wrapper for persisting bridge configuration.
 *
 * Used by [BridgeService] to read the target IP, port, and pairing state at
 * service start, and by [BridgeViewModel] to persist user changes from the
 * Settings screen.
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

    /**
     * The 6-digit pairing PIN entered by the user (shown on the receiver app).
     * Empty = pairing not configured, bridge will connect without pairing.
     */
    var pairingPin: String
        get() = prefs.getString(KEY_PAIRING_PIN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PAIRING_PIN, value).apply()

    /**
     * Whether the last pairing handshake completed successfully.
     * Reset to false whenever [pairingPin] changes (force re-pair).
     */
    var isPaired: Boolean
        get() = prefs.getBoolean(KEY_IS_PAIRED, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_PAIRED, value).apply()

    /**
     * Set a new PIN and automatically clear the paired state so the next
     * service start re-initiates the pairing handshake.
     */
    fun setPinAndClearPairing(pin: String) {
        prefs.edit()
            .putString(KEY_PAIRING_PIN, pin)
            .putBoolean(KEY_IS_PAIRED, false)
            .apply()
    }

    companion object {
        private const val PREF_FILE    = "bridge_config"
        private const val KEY_TARGET_IP  = "target_ip"
        private const val KEY_PORT       = "port"
        private const val KEY_PAIRING_PIN = "pairing_pin"
        private const val KEY_IS_PAIRED  = "is_paired"
        const val DEFAULT_PORT = 54321
    }
}
