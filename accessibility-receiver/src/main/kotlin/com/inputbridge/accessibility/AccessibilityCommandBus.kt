package com.inputbridge.accessibility

import android.os.Build
import androidx.annotation.RequiresApi
import com.inputbridge.core.logging.BridgeLogger
import com.inputbridge.core.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

private const val TAG = "AccessibilityCommandBus"

/**
 * Decoupled command bus between the receiver networking layer and the
 * [InputBridgeAccessibilityService].
 *
 * The networking layer emits [InputEvent] objects; this bus translates them into
 * accessibility actions on the UI thread through the service singleton.
 *
 * Mouse cursor simulation:
 * - The receiver tracks a virtual cursor position in screen coordinates.
 * - Mouse moves update this virtual position.
 * - Clicks dispatch a tap gesture at the current virtual position.
 * - The position starts at the screen center; clamped to screen bounds.
 *
 * Call [setService] / [clearService] from the AccessibilityService lifecycle.
 */
@RequiresApi(Build.VERSION_CODES.N)
object AccessibilityCommandBus {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val commandFlow = MutableSharedFlow<InputEvent>(extraBufferCapacity = 256)

    @Volatile private var service: InputBridgeAccessibilityService? = null

    // Virtual cursor position (screen pixels). Updated by mouse move events.
    private var cursorX = 0f
    private var cursorY = 0f
    private var screenWidth = 1080f
    private var screenHeight = 2400f

    fun setService(svc: InputBridgeAccessibilityService) {
        service = svc
        BridgeLogger.i(TAG, "Service attached")
    }

    fun clearService() {
        service = null
        BridgeLogger.i(TAG, "Service detached")
    }

    fun setScreenSize(width: Int, height: Int) {
        screenWidth = width.toFloat()
        screenHeight = height.toFloat()
        cursorX = screenWidth / 2f
        cursorY = screenHeight / 2f
    }

    /** Enqueue an input event for execution. Non-blocking. */
    fun post(event: InputEvent) {
        commandFlow.tryEmit(event)
    }

    init {
        scope.launch {
            commandFlow.collect { event ->
                handleEvent(event)
            }
        }
    }

    private fun handleEvent(event: InputEvent) {
        val svc = service ?: run {
            BridgeLogger.w(TAG, "Event dropped — accessibility service not connected")
            return
        }
        when (event) {
            is InputEvent.MouseMove -> {
                cursorX = (cursorX + event.dx).coerceIn(0f, screenWidth - 1f)
                cursorY = (cursorY + event.dy).coerceIn(0f, screenHeight - 1f)
                // Visual cursor overlay update would go here (Phase 7)
            }
            is InputEvent.MouseButtonDown -> {
                if (event.button == MouseButton.LEFT) svc.tap(cursorX, cursorY)
                if (event.button == MouseButton.RIGHT) svc.longPress(cursorX, cursorY)
            }
            is InputEvent.MouseButtonUp -> Unit // tap is complete on down for accessibility
            is InputEvent.Scroll -> {
                val dx = event.dx * SCROLL_MULTIPLIER
                val dy = event.dy * SCROLL_MULTIPLIER
                svc.swipe(cursorX, cursorY, cursorX + dx, cursorY + dy, 80L)
            }
            is InputEvent.NavigationAction -> when (event.action) {
                AndroidNavAction.BACK          -> svc.goBack()
                AndroidNavAction.HOME          -> svc.goHome()
                AndroidNavAction.RECENTS       -> svc.goRecents()
                AndroidNavAction.NOTIFICATIONS -> svc.openNotifications()
                else -> Unit
            }
            is InputEvent.TextInput -> {
                // Text injection through clipboard paste — Phase 4 implementation
                BridgeLogger.d(TAG, "TextInput received (Phase 4): ${event.text}")
            }
            is InputEvent.KeyDown, is InputEvent.KeyUp -> {
                // Key event translation via AccessibilityInputHandler — Phase 4
            }
            else -> Unit
        }
    }

    private const val SCROLL_MULTIPLIER = 8f
}
