package com.inputbridge.core.logging

import timber.log.Timber

/**
 * Thin wrapper around Timber for structured logging.
 * Use this instead of raw Timber calls so log tags and behavior can be
 * changed centrally without touching every call site.
 *
 * Hot-path note: do NOT call verbose() on the input capture hot path.
 * Use the PACKET_LOGGING_ENABLED flag to gate any per-event logging.
 */
object BridgeLogger {

    fun init(isDebug: Boolean) {
        if (isDebug) {
            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(ProductionTree())
        }
    }

    fun d(tag: String, message: String) = Timber.tag(tag).d(message)
    fun i(tag: String, message: String) = Timber.tag(tag).i(message)
    fun w(tag: String, message: String, throwable: Throwable? = null) =
        Timber.tag(tag).w(throwable, message)
    fun e(tag: String, message: String, throwable: Throwable? = null) =
        Timber.tag(tag).e(throwable, message)

    /** Verbose — DO NOT call on hot path. */
    fun v(tag: String, message: String) = Timber.tag(tag).v(message)

    /** Log a latency measurement. Never call on the actual hot path — use tracing buffers. */
    fun latency(tag: String, eventId: Long, stageName: String, elapsedNs: Long) {
        Timber.tag("Latency/$tag").d(
            "event=$eventId stage=$stageName elapsed=${elapsedNs / 1_000}µs"
        )
    }
}

/**
 * Production logging tree: only logs warnings and errors, never verbose.
 */
private class ProductionTree : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (priority < android.util.Log.WARN) return
        val resolvedTag = tag ?: "InputBridge"
        // BUG-XXX FIX: original code called println() then Log.e() for throwables,
        // causing every warning+with-throwable to appear twice in logcat.
        if (t != null) {
            android.util.Log.e(resolvedTag, message, t)
        } else {
            android.util.Log.println(priority, resolvedTag, message)
        }
    }
}
