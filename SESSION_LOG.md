# Session Log

---

## Session 001 — Phase 1 Scaffold
**Date:** 2025-07-19
**Agent:** Claude (Replit)
**Status:** ✅ Complete

### Goals
- Design the full 9-module architecture
- Write all source files for every module
- Write all documentation (13 files)
- Set up GitHub Actions CI
- Establish coding conventions and AI continuity rules

### What Was Done
- Created all 9 modules: shared-core, protocol, input-capture, transport-wifi,
  transport-bluetooth-hid (stub), accessibility-receiver, diagnostics,
  app-bridge, app-receiver
- All source files, all documentation, CI pipeline

---

## Session 002 — Phase 2 USB Capture
**Date:** 2025-07-19
**Status:** ✅ Complete

### What Was Done
- UsbInputCapture: full HID report parsing (keyboard 8-byte, mouse 4-byte)
- KeyMap: HID Usage → Android KEYCODE (full layout)
- BridgeService: USB device lifecycle (attach/detach/permission flow)
- BridgePreferences + ReceiverPreferences

---

## Session 003 — Phase 3 UDP Transport
**Date:** 2025-07-20
**Status:** ✅ Complete

### What Was Done
- UdpTransport: bidirectional socket, PING/PONG, PacketSerializer
- Receiver-mode lastSenderAddress tracking (PONG can reply without knowing bridge IP)

---

## Session 004 — Phase 4 Accessibility Injection
**Date:** 2025-07-20
**Status:** ✅ Complete

### What Was Done
- InputBridgeAccessibilityService: injectKeyCode(), injectText(), tap, swipe, longPress, goBack
- AccessibilityCommandBus: virtual cursor, dispatch loop
- Keyboard: unicodeChar + buildMetaState(), ACTION_SET_TEXT selection-aware

---

## Session 005 — Phase 5 Latency + Reconnect
**Date:** 2025-07-20
**Status:** ✅ Complete

### What Was Done
- Exponential backoff reconnect (1→30s, 10 attempts)
- Sequence number gap detection (droppedSequencePackets)
- Latency tracing: captureToSendUs, receiveToInjectUs, rolling 10-sample average
- DiagnosticsScreen updated

---

## Session 006 — Phase 5 completion + UI
**Date:** 2025-07-20
**Status:** ✅ Complete

### What Was Done
- All bridge screens: WelcomeScreen, BridgeScreen, SettingsScreen, DiagnosticsScreen, PermissionsScreen, AboutScreen
- All receiver screens: WelcomeScreen, ConnectionScreen, AccessibilitySetupScreen, ReceiverSettingsScreen, ReceiverDiagnosticsScreen
- Single-activity Compose NavHost, Koin DI, dark terminal theme

---

## Session 007 — Pairing handshake
**Date:** 2025-07-21
**Status:** ✅ Complete

### What Was Done
- 6-digit PIN generation on receiver (persisted, shown on ConnectionScreen)
- PAIR_REQUEST / PAIR_RESPONSE / PAIR_CONFIRM packet types
- Source IP validation: drop packets from non-paired senders
- Bridge: sends PAIR_REQUEST before entering hot loop
- Receiver: validates PIN, records bridge IP, sends PAIR_RESPONSE
- BridgeService full architecture rewrite (startIncomingLoop → doPairing → startPingLoop → watchdog)

---

## Session 008 — Diagnostic + BUG fixes
**Date:** 2025-07-21
**Status:** ✅ Complete

### What Was Done
- BUG-001 through BUG-012 all fixed
- DiagnosticsData: overlayPermissionGranted, isSecureWindow, btConnected, btDeviceName
- ReceiverDiagnosticsScreen: all counters

---

## Session 009 — Phase 6 Bluetooth HID
**Date:** 2025-07-21
**Status:** ✅ Complete

### What Was Done
- transport-bluetooth-hid module: BluetoothHidTransport with BluetoothHidDevice profile
- HID descriptor (keyboard + mouse combo)
- BridgeService: mode-aware pipeline (UDP vs BT HID dispatch)
- UI: transport mode toggle in SettingsScreen, BT MAC address field

---

## Session 010 — Phase 7 Polish (FULL)
**Date:** 2026-07-21
**Agent:** Claude (Replit)
**Status:** ✅ Complete

### Goals
- Complete all 0%-done Phase 7 polish items
- Fix all dead Settings controls (Keep Screen On, Show Latency, Sensitivity slider)
- Implement black screen mode, cursor dot overlay, emergency stop, live permissions
- Add user-controllable auto-start toggle, landscape support for receiver
- Push to GitHub to trigger CI

### What Was Delivered

#### Black Screen Mode (bridge)
- `BridgeScreen.kt`: `DisposableEffect(blackScreenMode, screenBrightness)` sets `window.attributes.screenBrightness`
- When enabled: pure-black UI, `0.001f` brightness (hardware minimum without backlight-off), tiny status dot
- When exiting: restores `BRIGHTNESS_OVERRIDE_NONE`

#### Screen Brightness Slider (bridge)
- `SettingsScreen.kt`: slider from -1 (system default) to 1.0 (maximum)
- `BridgePreferences.screenBrightness` (Float, key `screen_brightness`, default -1f)
- `BridgeViewModel.setScreenBrightness(Float)`

#### Keep Screen On toggle (bridge)
- `MainActivity.kt`: `applyKeepScreenOn()` reads pref and adds/clears `FLAG_KEEP_SCREEN_ON`
- Called in `onCreate()` and `onResume()` so changes take effect without restart
- `BridgePreferences.keepScreenOn` (default true)

#### Show Latency toggle (bridge)
- `BridgeScreen.kt`: latency row conditional on `config.display.showLatencyOverlay`
- `BridgePreferences.showLatencyOverlay` (default true)

#### Bridge Sensitivity slider (bridge)
- `SettingsScreen.kt`: slider wired to `viewModel.setBridgeSensitivity(it)` (was no-op stub)
- `BridgePreferences.bridgeSensitivity` (Float, key `bridge_sensitivity`, default 1.0)

#### Emergency Stop — Volume Down Hold (both apps)
- `onKeyDown`: cancels previous job, starts 3-second delayed coroutine
- `onKeyUp`: cancels job; short press (<500ms) passes through to system volume handler
- Shows toast on trigger; logs in BridgeLogger

#### Cursor Dot Overlay (receiver)
- `CursorOverlayService.kt`: new service using `SYSTEM_ALERT_WINDOW`
- `CursorDotView`: Canvas-drawn dot + crosshair (green fill, dark border, hair lines)
- `TYPE_APPLICATION_OVERLAY` (API 26+) with `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE`
- Collects `AccessibilityCommandBus.cursorPosition` StateFlow on `Dispatchers.Main`
- `ReceiverService.startCursorOverlayIfNeeded()` + `stopCursorOverlay()` called on connect/destroy

#### AccessibilityCommandBus — cursorPosition StateFlow
- `_cursorPosition: MutableStateFlow<Pair<Float,Float>>` published on every `MouseMove`
- `setScreenSize()` also updates it (cursor centres on screen-size change)
- `getCursorX()` / `getCursorY()` snapshot helpers

#### Live PermissionsScreen (bridge)
- `DisposableEffect(lifecycleOwner)` watches `ON_RESUME` — re-checks all permissions on return from Settings
- Battery opt: `PowerManager.isIgnoringBatteryOptimizations()`
- `BLUETOOTH_CONNECT` (API 31+): `rememberLauncherForActivityResult(RequestPermission())`
- `NEARBY_WIFI_DEVICES` (API 33+): same pattern
- `POST_NOTIFICATIONS` (API 33+): same pattern
- MIUI autostart card with deep-link attempt to MIUI autostart activity

#### Auto-start on Boot toggle (both apps)
- `BridgePreferences.autoStartOnBoot` / `ReceiverPreferences.autoStartOnBoot` (default true)
- Both `BootReceiver.kt` now read from prefs instead of `FeatureFlags.AUTO_START_ON_BOOT`
- Toggle in SettingsScreen (bridge) and ReceiverSettingsScreen (receiver)

#### WelcomeScreen cleanup (bridge)
- Only `TransportMode.UDP` and `TransportMode.BLUETOOTH_HID` shown
- `WIFI_DIRECT` and `TCP` stubs removed from list

#### Landscape Support (receiver)
- `screenOrientation="portrait"` removed from `app-receiver/AndroidManifest.xml`
- `android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize"` added

#### Manifest updates
- `app-receiver`: added `SYSTEM_ALERT_WINDOW`, `POST_NOTIFICATIONS`, `CursorOverlayService` entry
- `app-bridge`: removed `android:keepScreenOn="true"` (now applied in code)

#### AppConfig expansion
- `DisplayConfig`: 6 new fields (`blackScreenMode`, `showLatencyOverlay`, `keepScreenOn`, `screenBrightness`, `autoStartOnBoot`, `showCursorOverlay`)
- `DiagnosticsData`: `blackScreenMode`, `cursorOverlayActive`

### Files Changed (23 files)
- `shared-core/.../AppConfig.kt` — DisplayConfig expanded
- `diagnostics/.../DiagnosticsData.kt` — 2 new fields
- `accessibility-receiver/.../AccessibilityCommandBus.kt` — cursorPosition StateFlow + helpers
- `app-bridge/.../BridgePreferences.kt` — 6 new keys
- `app-bridge/.../BootReceiver.kt` — reads pref not FeatureFlags
- `app-bridge/.../BridgeViewModel.kt` — all new setters + config init from prefs
- `app-bridge/.../MainActivity.kt` — keepScreenOn + Vol-Down emergency stop
- `app-bridge/.../BridgeScreen.kt` — black screen mode + brightness DisposableEffect + latency visibility
- `app-bridge/.../SettingsScreen.kt` — all dead controls wired + new toggles
- `app-bridge/.../PermissionsScreen.kt` — full live permission checking with launchers
- `app-bridge/.../WelcomeScreen.kt` — hides TCP/WIFI_DIRECT stubs
- `app-bridge/AndroidManifest.xml` — removed android:keepScreenOn
- `app-receiver/.../ReceiverPreferences.kt` — 2 new keys
- `app-receiver/.../BootReceiver.kt` — reads pref not FeatureFlags
- `app-receiver/.../ReceiverViewModel.kt` — setCursorOverlayEnabled + setAutoStartOnBoot
- `app-receiver/.../ReceiverSettingsScreen.kt` — cursor overlay toggle + auto-start toggle
- `app-receiver/.../MainActivity.kt` — Vol-Down emergency stop, landscape
- `app-receiver/.../ReceiverService.kt` — startCursorOverlayIfNeeded() + stopCursorOverlay()
- `app-receiver/.../CursorOverlayService.kt` — NEW: floating cursor dot overlay
- `app-receiver/AndroidManifest.xml` — SYSTEM_ALERT_WINDOW + CursorOverlayService + landscape

### Key Decisions
- `screenBrightness = 0.001f` in black-screen mode (not 0.0f): `0.0f` can turn off the backlight
  entirely on some devices, making the emergency-stop STOP button invisible
- Emergency stop is Volume Down hold (3s), not a volume combo: combos require tracking both keys
  simultaneously which is unreliable when the volume buttons are not hardware-adjacent
- `CursorOverlayService` is NOT a foreground service: it's a lightweight overlay with no
  notification needed; foreground type would require notification + foreground service type
  declaration for API 34
- WelcomeScreen hides TCP/WIFI_DIRECT: showing broken modes confuses users; they'll be
  re-exposed in Phase 8 once implemented
- Portrait lock kept on bridge: intentional — the bridge phone is held or placed face-down
