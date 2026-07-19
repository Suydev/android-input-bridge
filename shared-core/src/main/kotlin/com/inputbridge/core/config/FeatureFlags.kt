package com.inputbridge.core.config

/**
 * Feature flags control optional capabilities.
 * Flags are evaluated at startup; some require restart to take effect.
 *
 * DO NOT CHANGE: Flags marked with this annotation affect protocol compatibility.
 * Changing them may break pairing with a device running a different version.
 */
object FeatureFlags {

    /**
     * Enable Bluetooth HID output mode on the bridge.
     * Requires Android 9+ (API 28) and a device that supports BluetoothHidDevice.
     * This is the only path for a real system-level cursor on the receiver.
     */
    const val BLUETOOTH_HID_ENABLED = true

    /**
     * Enable Wi-Fi Direct transport.
     * Falls back to UDP if Wi-Fi Direct group formation fails.
     */
    const val WIFI_DIRECT_ENABLED = true

    /**
     * Enable per-event latency tracing.
     * Adds timestamps at input capture, serialization, send, receive, and execution.
     * Should be disabled in production builds for performance.
     */
    val LATENCY_TRACING_ENABLED = isDebugBuild()

    /**
     * Enable detailed packet logging.
     * Very noisy — only enable temporarily for protocol debugging.
     */
    const val PACKET_LOGGING_ENABLED = false

    /**
     * Enable auto-start on device reboot.
     * Requires RECEIVE_BOOT_COMPLETED permission.
     */
    const val AUTO_START_ON_BOOT = true

    /**
     * Enable clipboard sync extension (future feature, stubbed).
     */
    const val CLIPBOARD_SYNC_ENABLED = false

    /**
     * Enable macro recording and playback (future feature, stubbed).
     */
    const val MACROS_ENABLED = false

    private fun isDebugBuild(): Boolean {
        return try {
            val buildConfigClass = Class.forName("com.inputbridge.bridge.BuildConfig")
            buildConfigClass.getField("DEBUG").getBoolean(null)
        } catch (_: Exception) {
            try {
                val buildConfigClass = Class.forName("com.inputbridge.receiver.BuildConfig")
                buildConfigClass.getField("DEBUG").getBoolean(null)
            } catch (_: Exception) {
                false
            }
        }
    }
}
