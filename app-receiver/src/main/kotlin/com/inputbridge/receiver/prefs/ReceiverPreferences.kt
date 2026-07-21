package com.inputbridge.receiver.prefs

import android.content.Context
import kotlin.random.Random

/**
 * Lightweight SharedPreferences wrapper for persisting receiver configuration.
 *
 * Used by [ReceiverService] to read the listen port, mouse sensitivity, and
 * pairing state at service start, and by [ReceiverViewModel] to persist user
 * changes from the Settings and Connection screens.
 *
 * Will be migrated to DataStore in Phase 7 when all settings are fully wired.
 */
class ReceiverPreferences(context: Context) {

    private val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    /** UDP port to listen on for incoming packets. Defaults to 54321. */
    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    /**
     * Mouse pointer sensitivity multiplier applied in [AccessibilityCommandBus].
     * Range 0.1–5.0. Defaults to 1.0 (no scaling).
     */
    var mouseSensitivity: Float
        get() = prefs.getFloat(KEY_MOUSE_SENSITIVITY, DEFAULT_SENSITIVITY)
        set(value) = prefs.edit().putFloat(KEY_MOUSE_SENSITIVITY, value).apply()

    /**
     * 6-digit session PIN displayed to the user so the bridge operator can enter
     * it in the bridge app to complete pairing.
     * Empty until [generateNewPin] is first called.
     */
    var sessionPin: String
        get() = prefs.getString(KEY_SESSION_PIN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SESSION_PIN, value).apply()

    /**
     * IP address of the bridge device that successfully completed pairing.
     * Empty = not yet paired. Cleared when the bridge sends DISCONNECT or
     * when the user regenerates the PIN.
     */
    var pairedBridgeIp: String
        get() = prefs.getString(KEY_PAIRED_BRIDGE_IP, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PAIRED_BRIDGE_IP, value).apply()

    /** Whether the last pairing handshake completed successfully. */
    var isPaired: Boolean
        get() = prefs.getBoolean(KEY_IS_PAIRED, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_PAIRED, value).apply()

    /**
     * Generate a fresh random 6-digit PIN and clear any existing pairing.
     * Returns the new PIN string.
     */
    fun generateNewPin(): String {
        val pin = Random.nextInt(100_000, 1_000_000).toString()
        prefs.edit()
            .putString(KEY_SESSION_PIN, pin)
            .putString(KEY_PAIRED_BRIDGE_IP, "")
            .putBoolean(KEY_IS_PAIRED, false)
            .apply()
        return pin
    }

    companion object {
        private const val PREF_FILE              = "receiver_config"
        private const val KEY_PORT               = "port"
        private const val KEY_MOUSE_SENSITIVITY  = "mouse_sensitivity"
        private const val KEY_SESSION_PIN        = "session_pin"
        private const val KEY_PAIRED_BRIDGE_IP   = "paired_bridge_ip"
        private const val KEY_IS_PAIRED          = "is_paired"
        const val DEFAULT_PORT        = 54321
        const val DEFAULT_SENSITIVITY = 1.0f
    }
}
