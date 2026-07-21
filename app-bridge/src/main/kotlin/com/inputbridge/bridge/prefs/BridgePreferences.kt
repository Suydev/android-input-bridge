package com.inputbridge.bridge.prefs

import android.content.Context
import com.inputbridge.core.config.TransportMode

/**
 * Lightweight SharedPreferences wrapper for persisting bridge configuration.
 *
 * Used by [BridgeService] to read the target IP, port, and pairing state at
 * service start, and by [BridgeViewModel] to persist user changes from the
 * Settings screen.
 *
 * Will be migrated to DataStore in a future phase.
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

    fun setPinAndClearPairing(pin: String) {
        prefs.edit()
            .putString(KEY_PAIRING_PIN, pin)
            .putBoolean(KEY_IS_PAIRED, false)
            .apply()
    }

    // ── Phase 6 — Bluetooth HID ───────────────────────────────────────────────

    var transportMode: TransportMode
        get() = TransportMode.fromId(prefs.getInt(KEY_TRANSPORT_MODE, TransportMode.UDP.id))
        set(value) = prefs.edit().putInt(KEY_TRANSPORT_MODE, value.id).apply()

    var btTargetDeviceAddress: String
        get() = prefs.getString(KEY_BT_TARGET_ADDRESS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BT_TARGET_ADDRESS, value).apply()

    // ── Phase 7 — Polish ──────────────────────────────────────────────────────

    /**
     * When true, BridgeScreen goes pitch-black and dims screen brightness to
     * the hardware minimum. Useful for keeping the phone face-down as a silent
     * input relay. Toggle via Settings → Display.
     */
    var blackScreenMode: Boolean
        get() = prefs.getBoolean(KEY_BLACK_SCREEN, false)
        set(value) = prefs.edit().putBoolean(KEY_BLACK_SCREEN, value).apply()

    /**
     * When true, FLAG_KEEP_SCREEN_ON is set on the activity window.
     * Users who want the screen to sleep after timeout can disable this.
     */
    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()

    /** When true, the latency figure is shown on the active bridge screen. */
    var showLatencyOverlay: Boolean
        get() = prefs.getBoolean(KEY_SHOW_LATENCY, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_LATENCY, value).apply()

    /**
     * When true, [BootReceiver] starts BridgeService automatically after reboot.
     * User can disable via Settings → System.
     */
    var autoStartOnBoot: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START_ON_BOOT, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START_ON_BOOT, value).apply()

    /**
     * Bridge-side mouse movement sensitivity multiplier (0.1–5.0).
     * Applied before the packet is serialised and sent. Default 1.0 (no scaling).
     */
    var bridgeSensitivity: Float
        get() = prefs.getFloat(KEY_BRIDGE_SENSITIVITY, DEFAULT_SENSITIVITY)
        set(value) = prefs.edit().putFloat(KEY_BRIDGE_SENSITIVITY, value).apply()

    /**
     * Screen brightness override when not in black-screen mode.
     * -1f = follow system default. 0f–1f = explicit override.
     */
    var screenBrightness: Float
        get() = prefs.getFloat(KEY_SCREEN_BRIGHTNESS, -1f)
        set(value) = prefs.edit().putFloat(KEY_SCREEN_BRIGHTNESS, value).apply()

    companion object {
        private const val PREF_FILE              = "bridge_config"
        private const val KEY_TARGET_IP          = "target_ip"
        private const val KEY_PORT               = "port"
        private const val KEY_PAIRING_PIN        = "pairing_pin"
        private const val KEY_IS_PAIRED          = "is_paired"
        private const val KEY_TRANSPORT_MODE     = "transport_mode"
        private const val KEY_BT_TARGET_ADDRESS  = "bt_target_address"
        // Phase 7
        private const val KEY_BLACK_SCREEN       = "black_screen_mode"
        private const val KEY_KEEP_SCREEN_ON     = "keep_screen_on"
        private const val KEY_SHOW_LATENCY       = "show_latency_overlay"
        private const val KEY_AUTO_START_ON_BOOT = "auto_start_on_boot"
        private const val KEY_BRIDGE_SENSITIVITY = "bridge_sensitivity"
        private const val KEY_SCREEN_BRIGHTNESS  = "screen_brightness"
        const val DEFAULT_PORT        = 54321
        const val DEFAULT_SENSITIVITY = 1.0f
    }
}
