package com.inputbridge.ui.bridge

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.inputbridge.bridge.prefs.BridgePreferences
import com.inputbridge.core.model.InputEvent
import com.inputbridge.core.model.ModifierState
import com.inputbridge.core.model.MouseButton
import com.inputbridge.input.FrameworkInputBus
import com.inputbridge.bridge.service.BridgeService
import com.inputbridge.bridge.ui.BridgeRoute
import com.inputbridge.bridge.ui.screens.*
import com.inputbridge.bridge.ui.theme.BridgeTheme
import com.inputbridge.bridge.viewmodel.BridgeViewModel
import com.inputbridge.receiver.service.ReceiverService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Bridge Mode Activity - runs on the phone with USB dongle.
 * Captures USB keyboard/mouse and forwards via Wi-Fi or Bluetooth HID.
 */
class BridgeModeActivity : ComponentActivity() {

    private val viewModel: BridgeViewModel by viewModel()

    private val prefs: BridgePreferences by inject()

    private companion object {
        private const val TAG = "BridgeModeActivity"
        const val EMERGENCY_HOLD_MS = 3_000L
        private const val ACTION_USB_PERMISSION = "com.inputbridge.USB_PERMISSION"
    }

    // ── Emergency stop via Volume Down hold ───────────────────────────────────
    @Volatile private var volumeDownPressedAt = 0L
    private var emergencyStopJob: Job? = null

    // ── Foreground USB permission requester ───────────────────────────────────
    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            } ?: return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted) {
                Log.i(TAG, "USB permission granted (foreground Activity) — starting bridge service")
                startBridgeService()
            } else {
                Log.w(TAG, "USB permission denied (foreground Activity)")
                Toast.makeText(
                    this@BridgeModeActivity,
                    "USB permission denied — cannot capture keyboard/mouse",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyKeepScreenOn()
        requestBatteryOptimizationExemption()
        handleUsbLaunchIntent(intent)
        // BUG-141 FIX (single-APK merge): only ONE role may run per device. If the receiver
        // service was auto-started on this bridge phone (e.g. by boot or a previous
        // session on the same app), stop it so it can't hold discovery port 54322
        // and make the bridge "discover" its own device over loopback.
        runCatching { stopService(Intent(this, ReceiverService::class.java)) }
            .onFailure { Log.w(TAG, "Failed to stop ReceiverService: ${it.message}") }

        setContent {
            BridgeTheme {
                val navController = rememberNavController()
                NavHost(
                    navController = navController,
                    startDestination = BridgeRoute.WELCOME,
                ) {
                    composable(BridgeRoute.WELCOME) {
                        WelcomeScreen(
                            onContinue = { navController.navigate(BridgeRoute.BRIDGE) {
                                launchSingleTop = true
                            } },
                            onSettings = { navController.navigate(BridgeRoute.SETTINGS) {
                                launchSingleTop = true
                            } },
                            onPermissions = { navController.navigate(BridgeRoute.PERMISSIONS) {
                                launchSingleTop = true
                            } },
                            viewModel = viewModel,
                        )
                    }
                    composable(BridgeRoute.BRIDGE) {
                        BridgeScreen(
                            onSettings = { navController.navigate(BridgeRoute.SETTINGS) {
                                launchSingleTop = true
                            } },
                            onDiagnostics = { navController.navigate(BridgeRoute.DIAGNOSTICS) {
                                launchSingleTop = true
                            } },
                            onMouse = { navController.navigate(BridgeRoute.TRACKPAD) {
                                launchSingleTop = true
                            } },
                            viewModel = viewModel,
                        )
                    }
                    composable(BridgeRoute.TRACKPAD) {
                        BridgeTrackpadScreen(
                            onBack = { navController.popBackStack() },
                            prefs = prefs,
                        )
                    }
                    composable(BridgeRoute.SETTINGS) {
                        SettingsScreen(
                            onBack = { navController.popBackStack() },
                            viewModel = viewModel,
                        )
                    }
                    composable(BridgeRoute.DIAGNOSTICS) {
                        DiagnosticsScreen(
                            onBack = { navController.popBackStack() },
                            viewModel = viewModel,
                        )
                    }
                    composable(BridgeRoute.PERMISSIONS) {
                        PermissionsScreen(onBack = { navController.popBackStack() })
                    }
                    composable(BridgeRoute.ABOUT) {
                        AboutScreen(onBack = { navController.popBackStack() })
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyKeepScreenOn()
        scanUsbAndStartIfNeeded()
        // BUG-187: pointer capture gives relative mouse deltas (no edge-clamping).
        // Harmless when no mouse is attached; finger touch is unaffected.
        runCatching { window.decorView.requestPointerCapture() }
    }

    override fun onPause() {
        runCatching { window.decorView.releasePointerCapture() }
        lastHoverX = Float.NaN; lastHoverY = Float.NaN
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // BUG-193: focus changes (dialogs, trackpad screen releasing capture) drop the
        // decorView capture — re-request whenever we regain focus so the physical mouse
        // keeps sending relative deltas.
        if (hasFocus) runCatching { window.decorView.requestPointerCapture() }
    }

    private var lastHoverX = Float.NaN
    private var lastHoverY = Float.NaN

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // BACK must keep working on the phone; HOME is never delivered anyway.
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        val mods = metaToModifierState(event.metaState)
        when (event.action) {
            KeyEvent.ACTION_DOWN, KeyEvent.ACTION_MULTIPLE ->
                FrameworkInputBus.emit(InputEvent.KeyDown(event.keyCode, event.scanCode, mods))
            KeyEvent.ACTION_UP ->
                FrameworkInputBus.emit(InputEvent.KeyUp(event.keyCode, event.scanCode, mods))
        }
        return true // consume so the phone UI does not also receive it
    }

    private fun isPointerDevice(event: MotionEvent): Boolean =
        event.source and InputDevice.SOURCE_CLASS_POINTER != 0 &&
            event.source and InputDevice.SOURCE_TOUCHSCREEN == 0

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        // BUG-193: some dongles/MIUI builds report SOURCE_TOUCHPAD or vendor sources;
        // accept ANY pointer-class device that is not the built-in touchscreen.
        val isMouse = isPointerDevice(event)
        if (!isMouse) return super.dispatchGenericMotionEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_SCROLL -> {
                // dy negated to match UsbInputCapture's wheel convention
                val dx = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                val dy = -event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (dx != 0f || dy != 0f) FrameworkInputBus.emit(InputEvent.Scroll(dx, dy))
                return true
            }
            MotionEvent.ACTION_HOVER_MOVE -> {
                val relX = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
                val relY = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
                val dx: Float
                val dy: Float
                if (relX != 0f || relY != 0f) {
                    dx = relX; dy = relY
                } else {
                    if (lastHoverX.isNaN()) { lastHoverX = event.x; lastHoverY = event.y; return true }
                    dx = event.x - lastHoverX; dy = event.y - lastHoverY
                    lastHoverX = event.x; lastHoverY = event.y
                }
                if (dx != 0f || dy != 0f) FrameworkInputBus.emit(InputEvent.MouseMove(dx, dy))
                return true
            }
            MotionEvent.ACTION_BUTTON_PRESS   -> { emitButtons(event, true);  return true }
            MotionEvent.ACTION_BUTTON_RELEASE -> { emitButtons(event, false); return true }
            else -> return super.dispatchGenericMotionEvent(event)
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Under pointer capture, mouse clicks arrive as touch events carrying a
        // buttonState. Finger touches (trackpad screen) have buttonState == 0 and
        // pass through untouched so Compose still receives them.
        // BUG-193: same broadened source check as generic-motion above.
        if (event.source and InputDevice.SOURCE_TOUCHSCREEN == 0 &&
            event.source and InputDevice.SOURCE_CLASS_POINTER != 0 &&
            event.buttonState != 0) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> emitButtons(event, true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP     -> emitButtons(event, false)
            }
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    private fun emitButtons(event: MotionEvent, down: Boolean) {
        var bits = if (event.actionButton != 0) event.actionButton else event.buttonState
        var id = 0
        while (bits != 0) {
            if (bits and 1 != 0) {
                val b = MouseButton.fromId(id.toByte())
                if (down) FrameworkInputBus.emit(InputEvent.MouseButtonDown(b))
                else FrameworkInputBus.emit(InputEvent.MouseButtonUp(b))
            }
            bits = bits shr 1; id++
        }
    }

    /**
     * BUG-188: OEM battery managers (OxygenOS/MIUI) kill foreground services when the
     * user swipes the app away. Ask once for the battery-optimization exemption so the
     * receiver/bridge keeps running after exit.
     */
    private fun requestBatteryOptimizationExemption() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:$packageName"),
                    )
                )
            }
        }
    }

    private fun metaToModifierState(meta: Int): ModifierState = ModifierState(
        shift = meta and KeyEvent.META_SHIFT_MASK != 0,
        ctrl  = meta and KeyEvent.META_CTRL_MASK != 0,
        alt   = meta and KeyEvent.META_ALT_MASK != 0,
        meta  = meta and KeyEvent.META_META_MASK != 0,
        capsLock = meta and KeyEvent.META_CAPS_LOCK_ON != 0,
        numLock  = meta and KeyEvent.META_NUM_LOCK_ON != 0,
    )

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // BUG-184 FIX: singleTask re-delivery skips onCreate — keep the opposite role stopped
        runCatching { stopService(Intent(this, com.inputbridge.receiver.service.ReceiverService::class.java)) }
        handleUsbLaunchIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbPermissionReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(usbPermissionReceiver) }
    }

    // ── USB permission & service start ────────────────────────────────────────

    private fun startBridgeService() {
        try {
            startForegroundService(Intent(this, BridgeService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start bridge service: ${e.message}")
        }
    }

    private fun requestUsbPermissionFromActivity(device: UsbDevice) {
        val usbManager = getSystemService(USB_SERVICE) as? UsbManager ?: return
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val pi = PendingIntent.getBroadcast(this, 1, Intent(ACTION_USB_PERMISSION), flags)
        usbManager.requestPermission(device, pi)
        Log.i(TAG, "USB permission requested from foreground Activity for ${device.deviceName}")
    }

    private fun handleUsbLaunchIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        } ?: return
        val usbManager = getSystemService(USB_SERVICE) as? UsbManager ?: return
        if (usbManager.hasPermission(device)) startBridgeService()
        else requestUsbPermissionFromActivity(device)
    }

    private fun scanUsbAndStartIfNeeded() {
        val usbManager = getSystemService(USB_SERVICE) as? UsbManager ?: return
        for ((name, device) in usbManager.deviceList) {
            for (i in 0 until device.interfaceCount) {
                if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_HID) {
                    Log.i(TAG, "Activity.onResume: HID device found — $name " +
                        "(vendor=${device.vendorId}, product=${device.productId})")
                    if (usbManager.hasPermission(device)) {
                        startBridgeService()
                    } else {
                        requestUsbPermissionFromActivity(device)
                    }
                    return
                }
            }
        }
    }

    // ── Volume-Down emergency stop ────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && event.repeatCount == 0) {
            volumeDownPressedAt = SystemClock.elapsedRealtime()
            emergencyStopJob?.cancel()
            emergencyStopJob = lifecycleScope.launch {
                delay(EMERGENCY_HOLD_MS)
                viewModel.stopBridge()
                Toast.makeText(
                    this@BridgeModeActivity,
                    "Emergency stop — bridge service stopped",
                    Toast.LENGTH_SHORT
                ).show()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val heldMs = if (volumeDownPressedAt > 0L)
                SystemClock.elapsedRealtime() - volumeDownPressedAt else 0L
            emergencyStopJob?.cancel()
            emergencyStopJob = null
            volumeDownPressedAt = 0L
            if (heldMs < 500L) {
                return false
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun applyKeepScreenOn() {
        if (prefs.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}