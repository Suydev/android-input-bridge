package com.inputbridge.core.logging

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists the most recent uncaught crash so it can be shown in the UI after the
 * process is relaunched.
 *
 * Why: the global crash handler (InputBridgeApplication) only writes to BridgeLogger
 * (logcat) and in-memory DiagnosticsManager — both are lost when the process dies.
 * With this file the exact crash text survives a restart, so a user who cannot attach
 * ADB can still report what crashed.
 */
object CrashLog {

    private const val PREFS = "crash_log"
    private const val KEY_LAST = "last_crash"

    fun save(context: Context, throwable: Throwable) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val sb = StringBuilder(time).append('\n')
            .append(throwable.javaClass.name).append(": ")
            .append(throwable.message?.take(160) ?: "(no message)")
        val stack = throwable.stackTrace
        if (stack.isNotEmpty()) {
            sb.append("\n  at ").append(stack.take(3).joinToString("\n  at "))
        }
        store(context, sb.take(600).toString())
    }

    fun saveMessage(context: Context, message: String) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        store(context, "$time\n$message".take(600))
    }

    /** Last recorded crash, or null if none recorded yet. */
    fun last(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST, null)

    private fun store(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST, value)
            .apply()
    }
}