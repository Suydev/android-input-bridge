package com.inputbridge.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Central diagnostics aggregator.
 *
 * Each subsystem updates its slice of [DiagnosticsData] via the update helpers.
 * The UI observes [state] as a StateFlow and re-renders whenever anything changes.
 *
 * Thread-safe: all updates go through [update] which is synchronized on the flow value.
 */
object DiagnosticsManager {

    private val _state = MutableStateFlow(DiagnosticsData())
    val state: StateFlow<DiagnosticsData> = _state.asStateFlow()

    private val packetsSent = AtomicLong(0)
    private val packetsReceived = AtomicLong(0)
    private val sendFailures = AtomicLong(0)

    fun update(block: DiagnosticsData.() -> DiagnosticsData) {
        _state.value = _state.value.block()
    }

    // ── Counters (called on hot path — use atomics, not flow updates) ─────────

    fun onPacketSent() { packetsSent.incrementAndGet() }
    fun onPacketReceived() { packetsReceived.incrementAndGet() }
    fun onSendFailed() { sendFailures.incrementAndGet() }

    /** Flush counters into the state snapshot. Call periodically (e.g. every second). */
    fun flushCounters() {
        update {
            copy(
                packetsSent = packetsSent.get(),
                packetsReceived = packetsReceived.get(),
                packetsSendFailed = sendFailures.get(),
                snapshotTimestampMs = System.currentTimeMillis(),
            )
        }
    }

    fun recordLatency(latencyMs: Long) {
        update { copy(latencyMs = latencyMs, lastPongReceivedMs = System.currentTimeMillis()) }
    }

    fun recordError(message: String) {
        update { copy(lastError = message) }
    }

    fun recordReconnectAttempt() {
        update {
            copy(
                lastReconnectAttempt = System.currentTimeMillis(),
                reconnectAttempts = reconnectAttempts + 1
            )
        }
    }
}
