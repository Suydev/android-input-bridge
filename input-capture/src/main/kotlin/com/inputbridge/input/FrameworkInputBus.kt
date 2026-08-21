package com.inputbridge.input

import com.inputbridge.core.model.InputEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Bridge between the Android input framework and the bridge send pipeline.
 *
 * Android 10+ (API 29) processes USB HID keyboards/mice natively and delivers
 * their events through the standard view pipeline (dispatchKeyEvent,
 * dispatchGenericMotionEvent). By capturing events there instead of reading raw
 * HID reports over the USB Host API, NO USB permission dialog is needed at all —
 * the OS has already granted the device to the system input stack.
 *
 * BridgeModeActivity pushes events here; BridgeService collects them alongside
 * (or instead of) raw UsbInputCapture events.
 */
object FrameworkInputBus {
    private val _events = MutableSharedFlow<InputEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<InputEvent> = _events.asSharedFlow()

    /** Non-blocking; safe to call from any thread including the main thread. */
    fun emit(event: InputEvent): Boolean = _events.tryEmit(event)
}
