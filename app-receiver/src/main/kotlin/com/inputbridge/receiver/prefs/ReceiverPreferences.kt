package com.inputbridge.receiver.prefs

import android.content.Context

/**
 * Lightweight SharedPreferences wrapper for persisting receiver configuration.
 *
 * Used by [ReceiverService] to read the listen port at service start,
 * and by [ReceiverViewModel] to persist user changes from the Settings screen.
 *
 * Will be migrated to DataStore in Phase 7 when all settings are fully wired.
 */
class ReceiverPreferences(context: Context) {

    private val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    /** UDP port to listen on for incoming packets. Defaults to 54321. */
    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    companion object {
        private const val PREF_FILE = "receiver_config"
        private const val KEY_PORT = "port"
        const val DEFAULT_PORT = 54321
    }
}
