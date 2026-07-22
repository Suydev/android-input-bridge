# TASKS.md

Complete checklist of all project tasks. Never delete completed tasks. Always append new ones.

---

## Phase 1 — Scaffold ✅

- [x] Repository structure (all modules)
- [x] Gradle build system (version catalog, convention plugins)
- [x] GitHub Actions CI (debug + release APKs, unit tests)
- [x] shared-core: InputEvent sealed hierarchy
- [x] shared-core: ModifierState (bitmask serialization)
- [x] shared-core: AppConfig, FeatureFlags, BridgeLogger
- [x] protocol: PacketType enum (frozen IDs)
- [x] protocol: Packet data class (binary layout)
- [x] protocol: PacketSerializer (round-trip fidelity)
- [x] protocol: EventPacketFactory (atomic sequence numbers)
- [x] input-capture: InputCapture interface
- [x] input-capture: UsbInputCapture scaffold (keyboard + mouse HID parsing)
- [x] input-capture: KeyMap (HID Usage → Android KEYCODE)
- [x] transport-wifi: Transport interface
- [x] transport-wifi: UdpTransport scaffold
- [x] accessibility-receiver: InputBridgeAccessibilityService
- [x] accessibility-receiver: AccessibilityCommandBus (virtual cursor)
- [x] diagnostics: DiagnosticsData, DiagnosticsManager
- [x] app-bridge: BridgeApplication, BridgeModule (Koin)
- [x] app-bridge: BridgeService (foreground, WakeLock)
- [x] app-bridge: BootReceiver
- [x] app-bridge: MainActivity + all screens (Welcome, Bridge, Settings, Diagnostics, Permissions, About)
- [x] app-bridge: AndroidManifest.xml (USB HID filter, all permissions)
- [x] app-receiver: ReceiverApplication, ReceiverModule (Koin)
- [x] app-receiver: ReceiverService (foreground)
- [x] app-receiver: BootReceiver
- [x] app-receiver: MainActivity + all screens (Welcome, Connection, Accessibility, Settings, Diagnostics)
- [x] app-receiver: AndroidManifest.xml (accessibility service, all permissions)
- [x] Unit tests: PacketSerializerTest (all event types, round-trip)
- [x] Unit tests: InputEventTest (ModifierState bitmask, event fields)
- [x] Documentation: README, AI_CONTEXT, PROJECT_STATE, TASKS, ROADMAP, DECISIONS, CHANGELOG, BUGS, SESSION_LOG, TESTING, MODULES, PROTOCOL, BUILD

---

## Phase 2 — USB Input Capture ✅

- [x] protocol: PacketToEventConverter (Packet → InputEvent, used by ReceiverService)
- [x] app-bridge: BridgePreferences (SharedPreferences wrapper for target IP + port)
- [x] app-receiver: ReceiverPreferences (SharedPreferences wrapper for listen port)
- [x] BridgeService: dynamic BroadcastReceiver for USB attach/detach/permission
- [x] BridgeService: request USB permission flow (UsbManager.requestPermission)
- [x] BridgeService: UsbInputCapture.start() on device attach (permission granted)
- [x] BridgeService: UsbInputCapture.stop() on device detach
- [x] BridgeService: collect InputEvents from UsbInputCapture.events
- [x] BridgeService: pipe events through EventPacketFactory.fromEvent() → UdpTransport.send()
- [x] BridgeService: DiagnosticsManager.onPacketSent() / onSendFailed() per packet
- [x] BridgeService: 1s periodic DiagnosticsManager.flushCounters()
- [x] BridgeService: detect pre-attached USB HID device on service start
- [x] BridgeService: AtomicBoolean.compareAndSet idempotency guard in onStartCommand
- [x] BridgeService: NonCancellable teardown in onDestroy (USB + socket released before scope cancel)
- [x] ReceiverService: UdpTransport in receive mode (bind to configurable port)
- [x] ReceiverService: collect incomingPackets → PacketToEventConverter → AccessibilityCommandBus
- [x] ReceiverService: DiagnosticsManager.onPacketReceived() per packet
- [x] ReceiverService: 1s periodic DiagnosticsManager.flushCounters()
- [x] ReceiverService: WakeLock (was missing in Phase 1 stub)
- [x] ReceiverService: AtomicBoolean.compareAndSet idempotency guard in onStartCommand
- [x] ReceiverService: NonCancellable teardown in onDestroy (socket released before scope cancel)
- [x] BridgeViewModel: injected BridgePreferences; setTargetIp()/setPort() persist to prefs
- [x] BridgeViewModel: config pre-loaded from prefs on init
- [x] ReceiverViewModel: injected ReceiverPreferences; setListenPort() persists to prefs
- [x] CI: all compile errors fixed, run #25 = ✅ success (both APKs build)
- [ ] Manual test with real Portronics Key2 Combo hardware
- [ ] Verify KeyMap covers all keys on the Portronics keyboard
- [ ] Diagnostics screen live-updates during bridging session

---

## Phase 2 — CI Build Fixes ✅ (Session 004)

- [x] DiagnosticsManager.flushCounters() — fix AtomicLong name shadow in lambda
- [x] InputBridgeAccessibilityService — merge duplicate companion objects
- [x] UsbInputCapture — fix isActive(coroutineContext) → coroutineContext.isActive
- [x] app-receiver themes.xml — fix AAPT error (Theme.Material → Material3)
- [x] AndroidAppConventionPlugin — add buildFeatures { buildConfig = true }
- [x] BridgeModule / ReceiverModule — add androidContext() import
- [x] ReceiverService — replace non-existent ic_menu_receive drawable

---

## Phase 3 (partial) — Keep-alive / PING-PONG ✅ (Session 005)

- [x] BridgeService: PING loop (every 1s, starts after transport connects)
- [x] BridgeService: PONG response collection → DiagnosticsManager.recordLatency()
- [x] ReceiverService: respond to PING with PONG via packetFactory.makePong()
- [x] ReceiverService: handle KEEP_ALIVE (log, no-op)
- [x] ReceiverService: handle DISCONNECT (update DiagnosticsManager + notification)
- [x] UdpTransport: bidirectional in receiver mode (track lastSenderAddress, reply to it)

---

## Phase 3 Remainder — Pairing + Source Validation ✅ (Session 006)

- [x] BUG-010: accessibility-receiver/build.gradle.kts missing :diagnostics dependency (CI fix)
- [x] Pairing: 6-digit random session PIN on receiver (ReceiverPreferences.generateNewPin)
- [x] Pairing: PAIR_REQUEST / PAIR_RESPONSE / PAIR_CONFIRM packet flow
    - [x] EventPacketFactory: makePairRequest(), makePairResponse(), makePairConfirm()
    - [x] PacketSerializer: buildPairRequestPayload(), parsePairRequestPin(), buildPairResponsePayload(), parsePairResponseAccepted()
- [x] Pairing: PIN display on receiver ConnectionScreen (prominent, regenerate button)
- [x] Pairing: PIN entry on bridge SettingsScreen (6-digit field, clear-pairing button)
- [x] Pairing: token persistence (SharedPreferences — BridgePreferences + ReceiverPreferences)
- [x] Pairing: packet source validation (drop packets from un-paired IP on receiver)
- [x] Pairing: BridgeService initiates PAIR_REQUEST, waits 10s for PAIR_RESPONSE
- [x] Pairing: ReceiverService validates PIN, records bridge IP, sends PAIR_RESPONSE
- [ ] Pairing: QR code display (replaced by manual PIN entry — QR deferred to Phase 7)
- [ ] Wi-Fi Direct transport: group formation, peer discovery (Phase 6 scope)

---

## Phase 4 — Accessibility Receiver ✅ (Session 005)

- [x] InputBridgeAccessibilityService: update DiagnosticsManager on connect/unbind
- [x] InputBridgeAccessibilityService: fetch real screen dimensions (WindowManager/DisplayMetrics)
- [x] InputBridgeAccessibilityService: injectKeyCode() — printable chars, backspace, forward-delete,
      enter, tab, escape, arrow keys (char/word granularity, shift-extend), home/end
- [x] InputBridgeAccessibilityService: Ctrl+A/C/V/X shortcuts (select-all, copy, paste, cut)
- [x] InputBridgeAccessibilityService: injectText() via ACTION_SET_TEXT + clipboard paste fallback
- [x] InputBridgeAccessibilityService: buildMetaState() for ModifierState → Android meta int
- [x] AccessibilityCommandBus: handle KeyDown → injectKeyCode()
- [x] AccessibilityCommandBus: handle TextInput → injectText()
- [x] AccessibilityCommandBus: apply mouseSensitivity to mouse move deltas
- [x] AccessibilityCommandBus: setScreenSize() recentres virtual cursor
- [x] AccessibilityCommandBus: setSensitivity() API (0.1–10 range)
- [x] ReceiverPreferences: mouseSensitivity field (default 1.0)
- [x] ReceiverViewModel: setMouseSensitivity() — persist + apply immediately
- [x] ReceiverSettingsScreen: sensitivity slider fully wired (0.1–5.0, live feedback)
- [ ] Text injection: clipboard-based paste for TextInput on non-editable nodes (done via fallback)
- [ ] Screen size detection: update AccessibilityCommandBus.setScreenSize() ✅
- [ ] Visual cursor overlay (optional): show dot at current virtual cursor position (Phase 7)
- [x] Robust error handling: secure window detection (isSecureWindow flag), try-catch in injectKeyCode/injectText, lastInjectionError in DiagnosticsData

---

## Phase 5 — Latency + Reconnect ✅ (Session 006 + 007)

- [x] Reconnect: automatic reconnect on PONG timeout (10 s without PONG triggers backoff)
- [x] Reconnect: exponential backoff (1s, 2s, 4s, 8s, 16s, 30s… up to 10 attempts)
- [x] Reconnect: re-pairing on reconnect if not already paired
- [x] Reconnect: UI state during reconnect (connectionLabel shows "Reconnecting… attempt N")
- [x] Reconnect: DiagnosticsData.isReconnecting + reconnectAttempts + lastReconnectAttempt
- [x] Packet loss detection: sequence number gap detection on receiver (droppedSequencePackets)
- [x] Latency tracing: captureToSendUs (bridge hot-path µs), receiveToInjectUs (receiver µs)
- [x] Latency display: rolling 10-sample average in DiagnosticsScreen, BridgeScreen, ReceiverDiagnosticsScreen
- [ ] Hot path audit: profile and optimize any allocations > 1KB/event

---

## Phase 6 — Bluetooth HID ✅ (Session 009)

- [x] BluetoothHidTransport: BluetoothHidDevice registration
- [x] BluetoothHidTransport: HID descriptor (keyboard + mouse combo)
- [x] BluetoothHidTransport: report generation for key events
- [x] BluetoothHidTransport: report generation for mouse events
- [x] Feature flag: BLUETOOTH_HID_ENABLED guard
- [x] Graceful fallback: if HID device API not supported, connect() returns false
- [x] UI: transport mode segmented button (UDP / BT HID) in SettingsScreen
- [x] DiagnosticsData: btConnected + btDeviceName fields
- [x] BridgePreferences: transportMode + btTargetDeviceAddress persisted
- [x] BridgeService: mode-aware pipeline dispatcher + startBluetoothHidPipeline()
- [x] BridgeService: startCapture() dispatches events to BT HID or UDP based on active transport
- [x] DiagnosticsScreen: BT Host row
- [ ] Testing: verify OnePlus Pad Go shows real hardware cursor (requires hardware)

---

## Session 014 — Deep Audit Bug Fixes ✅

- [x] BUG-046: Remove dead `else ->` from `AccessibilityCommandBus.handleEvent` — sealed class exhaustiveness now enforced by compiler
- [x] BUG-047: Guard empty `pairedBridgeIp` in `ReceiverService` PING silence-recovery notification
- [x] BUG-048: Track claimed USB interfaces in `UsbInputCapture`; release all before `close()` in `stop()`
- [x] BUG-049: Reset `lastCaptureToSendUs` to 0 in `BridgeService.triggerReconnect()` alongside ping timestamp resets
- [x] BUG-050: Add `KEYCODE_MENU` (HID 0x65) and `KEYCODE_F13`–`F24` (HID 0x68–0x73) to `HidReportBuilder.ANDROID_TO_HID`
- [x] BUG-051: Set `FeatureFlags.WIFI_DIRECT_ENABLED = false` (Wi-Fi Direct is a stub)
- [x] BUG-052: Documented WONTFIX — `ModifierState.numLock` always false due to USB Output report gap
- [x] BUG-053: Fix `DiagnosticsManager.update {}` read-modify-write race — wrap in `synchronized(updateLock)`
- [x] Documentation: BUGS.md, SESSION_LOG.md, PROJECT_STATE.md, TASKS.md, AI_CONTEXT.md, agent memory updated

---

## Session 015 — CI Repair + Second Audit Pass ✅

### Diagnosis
- CI "Build Debug APKs" job has been failing since the BUG-038 commit (Session 013)
- Root cause 1: `KEYCODE_F13`–`KEYCODE_F24` do not exist in `android.view.KeyEvent` — 12 compile errors
- Root cause 2: `continue` in `?: run {}` inline lambda is Kotlin 2.0 experimental feature without opt-in

### Fixes
- [x] BUG-054: Remove `KEYCODE_F13`–`KEYCODE_F24` from `KeyMap.HID_TO_ANDROID` (unresolved references → CI failure)
- [x] BUG-054 (also): Correct `HidReportBuilder.kt` BUG-050 fix — keep only `KEYCODE_MENU`, remove F13–F24
- [x] BUG-055: Fix `?: run { continue }` → `if (endpoint == null) { continue }` in `UsbInputCapture.kt`
- [x] BUG-057: `MainActivity.applyKeepScreenOn()` — inject `BridgePreferences` from Koin instead of `BridgePreferences(this)`
- [x] Push all Session 014 + Session 015 fixes; CI should pass

---

## Phase 7 — Polish ✅ (Session 010)

- [x] Black screen mode: BridgeScreen goes pitch-black, window brightness set to 0.001f
- [x] Screen brightness slider: per-window override in Settings → Display (-1=system, 0–100%)
- [x] Keep Screen On toggle: FLAG_KEEP_SCREEN_ON now controlled by BridgePreferences.keepScreenOn
- [x] Show Latency toggle: latency number visibility on BridgeScreen controlled by pref
- [x] Bridge sensitivity setting: bridge-side mouse sensitivity 0.1–5× wired in SettingsScreen
- [x] Auto-start on Boot toggle: BridgePreferences.autoStartOnBoot + ReceiverPreferences.autoStartOnBoot
- [x] BootReceivers: both now read from user pref instead of FeatureFlags constant
- [x] Emergency stop hotkey (bridge): hold Volume Down 3 s → stopBridge() + toast
- [x] Emergency stop hotkey (receiver): hold Volume Down 3 s → stopReceiver() + toast
- [x] Accessibility mode cursor overlay: CursorOverlayService (SYSTEM_ALERT_WINDOW, floating crosshair dot)
- [x] Cursor overlay toggle: ReceiverSettingsScreen — auto-prompts for overlay permission if absent
- [x] AccessibilityCommandBus: cursorPosition StateFlow for overlay + getCursorX()/getCursorY()
- [x] Live PermissionsScreen: battery opt, BLUETOOTH_CONNECT, NEARBY_WIFI_DEVICES, POST_NOTIFICATIONS
- [x] WelcomeScreen cleanup: WIFI_DIRECT/TCP stubs hidden; only UDP + BT HID shown
- [x] Landscape support: screenOrientation="portrait" removed from receiver manifest
- [x] ReceiverService: starts/stops CursorOverlayService based on pref + canDrawOverlays()
- [x] app-receiver/AndroidManifest: SYSTEM_ALERT_WINDOW, POST_NOTIFICATIONS, CursorOverlayService entry
- [ ] Settings persistence: migrate SharedPreferences → DataStore (deferred — risky without compilation)
- [ ] Clipboard sync: CLIPBOARD_SYNC_ENABLED feature (Phase 8)
- [ ] Macro recording: MACROS_ENABLED feature (Phase 8)
- [ ] Help text: onboarding tooltips (Phase 8)
- [x] Version info: BuildConfig display in About screen (already done in Phase 5)
