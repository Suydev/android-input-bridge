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
 * Will be migrated to DataStore in a future phase.
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
     */
    var sessionPin: String
        get() = prefs.getString(KEY_SESSION_PIN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SESSION_PIN, value).apply()

    /**
     * IP address of the bridge device that successfully completed pairing.
     * Empty = not yet paired.
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

    // ── Phase 7 — Polish ──────────────────────────────────────────────────────

    /**
     * When true, a floating crosshair dot is drawn at the current virtual cursor
     * position using SYSTEM_ALERT_WINDOW overlay.
     * Requires canDrawOverlays() permission — guided by the Settings screen.
     */
    var showCursorOverlay: Boolean
        get() = prefs.getBoolean(KEY_SHOW_CURSOR_OVERLAY, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_CURSOR_OVERLAY, value).apply()

    /**
     * When true, [BootReceiver] starts ReceiverService automatically after reboot.
     * User can disable via Settings → System.
     */
    var autoStartOnBoot: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START_ON_BOOT, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START_ON_BOOT, value).apply()

    companion object {
        private const val PREF_FILE              = "receiver_config"
        private const val KEY_PORT               = "port"
        private const val KEY_MOUSE_SENSITIVITY  = "mouse_sensitivity"
        private const val KEY_SESSION_PIN        = "session_pin"
        private const val KEY_PAIRED_BRIDGE_IP   = "paired_bridge_ip"
        private const val KEY_IS_PAIRED          = "is_paired"
        // Phase 7
        private const val KEY_SHOW_CURSOR_OVERLAY = "show_cursor_overlay"
        private const val KEY_AUTO_START_ON_BOOT  = "auto_start_on_boot"
        const val DEFAULT_PORT        = 54321
        const val DEFAULT_SENSITIVITY = 1.0f
    }
}
