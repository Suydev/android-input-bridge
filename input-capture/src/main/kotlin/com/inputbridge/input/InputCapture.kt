package com.inputbridge.input

import com.inputbridge.core.model.InputEvent
import kotlinx.coroutines.flow.Flow

/**
 * Contract for any input capture source.
 *
 * Implementations must:
 * - Emit [InputEvent] objects through [events] on a background thread (not main thread).
 * - Be started/stopped via [start] and [stop].
 * - Not block the main thread at any point.
 * - Not perform disk I/O or network I/O on the hot path.
 *
 * The hot path is: hardware event → [InputEvent] emission on [events].
 * Keep this path as short as possible.
 */
interface InputCapture {

    /** Cold flow of input events. Collect on a background dispatcher. */
    val events: Flow<InputEvent>

    /** Current capture status. */
    val status: Flow<CaptureStatus>

    /**
     * Start capturing input.
     * @return true if started successfully, false if device is unavailable.
     */
    suspend fun start(): Boolean

    /**
     * Stop capturing input and release all resources.
     * Safe to call multiple times.
     */
    suspend fun stop()

    /** Whether the capture source is currently active. */
    val isActive: Boolean
}

/** Status reported by an [InputCapture] implementation. */
sealed class CaptureStatus {
    object Idle : CaptureStatus()
    object Starting : CaptureStatus()
    object Active : CaptureStatus()
    data class Error(val message: String, val recoverable: Boolean) : CaptureStatus()
    object Stopped : CaptureStatus()
}
