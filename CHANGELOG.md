# Changelog

All notable changes to InputBridge are documented here.

---

## [Unreleased] — Session 010 — Phase 7 Polish

### Added — Bridge app
- **Black Screen Mode**: dims window brightness to hardware minimum; BridgeScreen shows pure-black UI with a tiny status dot and STOP button
- **Screen Brightness control**: per-window brightness override slider in Settings (-1 = system default, 0–100%)
- **Keep Screen On toggle**: `FLAG_KEEP_SCREEN_ON` now controlled by user pref (was always-on)
- **Show Latency toggle**: latency figure on BridgeScreen now respects pref
- **Sensitivity slider wired**: bridge-side mouse sensitivity 0.1–5× now persisted and applied (was no-op stub)
- **Auto-start on Boot toggle**: user can disable boot auto-start from Settings → System
- **Emergency stop**: hold Volume Down 3 seconds to stop bridge and toast notification; short press still controls system volume
- **Live Permissions screen**: dynamically checks battery optimization, BLUETOOTH_CONNECT (API 31+), NEARBY_WIFI_DEVICES (API 33+), POST_NOTIFICATIONS (API 33+); each row shows live granted/denied status and action button
- **WelcomeScreen cleanup**: WIFI_DIRECT and TCP stub modes hidden; only UDP and BT HID shown

### Added — Receiver app
- **Cursor dot overlay** (`CursorOverlayService`): floating crosshair drawn at virtual cursor position using `SYSTEM_ALERT_WINDOW`; non-interactive (FLAG_NOT_FOCUSABLE + FLAG_NOT_TOUCHABLE)
- **Cursor overlay toggle** in ReceiverSettingsScreen: auto-prompts for `SYSTEM_ALERT_WINDOW` permission if absent
- **Auto-start on Boot toggle** in ReceiverSettingsScreen
- **Emergency stop**: hold Volume Down 3 seconds to stop receiver service
- **Landscape support**: `screenOrientation="portrait"` removed from receiver manifest; `configChanges` added for smooth rotation

### Added — Shared
- `AccessibilityCommandBus.cursorPosition`: `StateFlow<Pair<Float,Float>>` published on every `InputEvent.MouseMove`; also `getCursorX()` / `getCursorY()` snapshot helpers
- `DisplayConfig` expanded: `blackScreenMode`, `showLatencyOverlay`, `keepScreenOn`, `screenBrightness`, `autoStartOnBoot`, `showCursorOverlay`
- `DiagnosticsData`: `blackScreenMode` and `cursorOverlayActive` fields added
- Both `BootReceiver`s read from user prefs instead of compile-time `FeatureFlags` constant

### Changed
- `BridgePreferences`: 5 new keys — `black_screen_mode`, `keep_screen_on`, `show_latency_overlay`, `auto_start_on_boot`, `bridge_sensitivity`, `screen_brightness`
- `ReceiverPreferences`: 2 new keys — `show_cursor_overlay`, `auto_start_on_boot`
- `BridgeViewModel.config` now initialised from all persisted prefs including display settings
- `ReceiverService`: starts/stops `CursorOverlayService` based on pref + `canDrawOverlays()`
- `app-receiver/AndroidManifest.xml`: added `SYSTEM_ALERT_WINDOW`, `POST_NOTIFICATIONS`, `CursorOverlayService` entry, removed `screenOrientation="portrait"`
- `app-bridge/AndroidManifest.xml`: removed `android:keepScreenOn="true"` from activity (now applied via `FLAG_KEEP_SCREEN_ON` in code)

---

## [Phase 6] — Session 009 — Bluetooth HID Transport

### Added
- `transport-bluetooth-hid` module: full Bluetooth HID device role via `BluetoothHidDevice` profile
- BT HID mode wired into `BridgeService` and transport pipeline
- Settings screen: BT HID / UDP toggle, MAC address input
- `DiagnosticsData.btConnected` / `btDeviceName` fields
- Session PIN validation on BT HID side

---

## [Phase 5] — Session 008 — Complete UI

### Added
- All bridge screens: WelcomeScreen, BridgeScreen, SettingsScreen, DiagnosticsScreen, PermissionsScreen, AboutScreen
- All receiver screens: WelcomeScreen, ConnectionScreen, ReceiverSettingsScreen, AccessibilitySetupScreen
- Single-activity Compose navigation (NavHost)
- Koin DI for ViewModels
- Dark terminal-aesthetic theme (green accent for bridge, blue for receiver)

---

## [Phase 4] — Session 007 — Accessibility Injection

### Added
- `accessibility-receiver` module: `InputBridgeAccessibilityService`, `AccessibilityCommandBus`
- Full keyboard injection: `injectKeyCode` (unicodeChar + buildMetaState), `injectText` (ACTION_SET_TEXT)
- Mouse injection: tap, long-press, swipe (scroll), back navigation
- Screen-size detection via `DisplayMetrics`
- Coerce cursor within screen bounds

---

## [Phase 3] — Session 006 — UDP Transport

### Added
- `transport-udp` module: bidirectional `UdpTransport`
- PING/PONG latency measurement
- Receiver-mode `lastSenderAddress` tracking (for PONG replies)
- Pairing handshake (PAIR_REQUEST / PAIR_RESPONSE)
- Packet serialisation: KeyDown, KeyUp, MouseMove, MouseButton, Scroll, TextInput

---

## [Phase 2] — Sessions 004–005 — USB HID Capture

### Added
- `usb-capture` module: `UsbInputCapture`, USB device enumeration
- `hid-parser` module: HID report descriptor parser, keyboard + mouse HID report decoding
- 8-byte keyboard report, 4-byte mouse report
- `InputEvent` sealed class hierarchy

---

## [Phase 1] — Sessions 001–003 — Scaffold

### Added
- Multi-module Gradle project: 9 modules
- GitHub Actions CI (debug APK build)
- Shared `AppConfig`, `TransportMode`, `FeatureFlags`
- `DiagnosticsManager` singleton
- `BridgeLogger` wrapper
