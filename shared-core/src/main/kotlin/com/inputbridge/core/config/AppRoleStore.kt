package com.inputbridge.core.config

import android.content.Context

/**
 * Persists which role this device plays in the unified single-APK build.
 *
 * Before the app modules were merged into one APK, the installed package WAS the
 * role (an APK could only be a bridge or a receiver). Now both roles live in one
 * package, so the OS can no longer tell them apart. This store records the user's
 * choice from ModeSelectionActivity and lets the BootReceivers and mode activities
 * enforce that only ONE role's services run on any given device.
 *
 * Rule: bridge and receiver services MUST NOT run in the same process at the same
 * time — they race for the shared discovery port UDP 54322 and the bridge can
 * "auto-discover" its own in-process receiver over loopback, which produces the
 * classic "connected but nothing works" failure (AGENTS.md §4.5).
 */
enum class AppRole {
    /** Phone with the USB dongle — runs BridgeService. */
    BRIDGE,
    /** Tablet — runs ReceiverService + accessibility injection. */
    RECEIVER,
    /** First launch — the user has not picked a mode yet (no auto-start). */
    NONE,
}

object AppRoleStore {

    private const val PREF_FILE = "app_role"
    private const val KEY_ROLE = "role"

    fun get(context: Context): AppRole {
        val value = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .getString(KEY_ROLE, null)
        return when (value) {
            "bridge" -> AppRole.BRIDGE
            "receiver" -> AppRole.RECEIVER
            else -> AppRole.NONE
        }
    }

    fun set(context: Context, role: AppRole) {
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ROLE, if (role == AppRole.NONE) null else role.name.lowercase())
            .apply()
    }
}