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
- [x] ReceiverService: UdpTransport in receive mode (bind to configurable port)
- [x] ReceiverService: collect incomingPackets → PacketToEventConverter → AccessibilityCommandBus
- [x] ReceiverService: DiagnosticsManager.onPacketReceived() per packet
- [x] ReceiverService: 1s periodic DiagnosticsManager.flushCounters()
- [x] ReceiverService: WakeLock (was missing in Phase 1 stub)
- [x] BridgeViewModel: injected BridgePreferences; setTargetIp()/setPort() persist to prefs
- [x] BridgeViewModel: config pre-loaded from prefs on init
- [x] ReceiverViewModel: injected ReceiverPreferences; setListenPort() persists to prefs
- [ ] Manual test with real Portronics Key2 Combo hardware
- [ ] Verify KeyMap covers all keys on the Portronics keyboard
- [ ] Diagnostics screen live-updates during bridging session

---

## Phase 3 — Network Transport + Pairing 🔲

- [ ] BridgeService: PING/PONG on 1s keep-alive timer
- [ ] ReceiverService: respond to PING with PONG; record latency
- [ ] Pairing: shared token generation (16-byte random, SecureRandom)
- [ ] Pairing: PAIR_REQUEST / PAIR_RESPONSE / PAIR_CONFIRM packet flow
- [ ] Pairing: QR code display on receiver, manual entry on bridge
- [ ] Pairing: token persistence (SharedPreferences / DataStore)
- [ ] Pairing: packet source validation (drop packets from un-paired senders)
- [ ] Keep-alive: latency measurement in DiagnosticsManager.recordLatency()
- [ ] Reconnect: exponential backoff, max attempts
- [ ] Reconnect: UI state during reconnect (amber dot + attempt counter)
- [ ] Wi-Fi Direct transport: group formation, peer discovery
- [ ] Diagnostics: latencyMs, packetsSent, packetsReceived, transportConnected all live

---

## Phase 4 — Accessibility Receiver 🔲

- [ ] AccessibilityCommandBus: full InputEvent dispatch (all types)
- [ ] Text injection: clipboard-based paste for TextInput events
- [ ] Key event translation: Android keyCode → accessibility action
- [ ] Scroll: gesture-based smooth scroll
- [ ] Navigation: BACK, HOME, RECENTS, NOTIFICATIONS
- [ ] Screen size detection: update AccessibilityCommandBus.setScreenSize()
- [ ] Visual cursor overlay (optional): show dot at current virtual cursor position
- [ ] Robust error handling: service disconnect, app switch, secure window

---

## Phase 5 — Latency + Reconnect 🔲

- [ ] Latency tracing: timestamp at capture, serialization, send, receive, execution
- [ ] Latency display: rolling average in DiagnosticsScreen and BridgeScreen
- [ ] Reconnect: automatic reconnect on transport error
- [ ] Reconnect: UI state during reconnect (amber dot + attempt counter)
- [ ] Packet loss detection: sequence number gap detection on receiver
- [ ] Hot path audit: profile and optimize any allocations > 1KB/event

---

## Phase 6 — Bluetooth HID 🔲

- [ ] BluetoothHidTransport: BluetoothHidDevice registration
- [ ] BluetoothHidTransport: HID descriptor (keyboard + mouse combo)
- [ ] BluetoothHidTransport: report generation for key events
- [ ] BluetoothHidTransport: report generation for mouse events
- [ ] Feature flag: BLUETOOTH_HID_ENABLED guard
- [ ] Graceful fallback: if HID device API not supported, fall back to UDP
- [ ] UI: mode indicator shows "HID" vs "Accessibility"
- [ ] Testing: verify OnePlus Pad Go shows real hardware cursor

---

## Phase 7 — Polish 🔲

- [ ] Black screen mode: screen brightness → 0, UI fully black
- [ ] Minimum brightness: brightness slider in settings
- [ ] Auto-start settings: UI toggle for AUTO_START_ON_BOOT
- [ ] Emergency stop hotkey: physical button combo to stop bridge
- [ ] Settings persistence: migrate SharedPreferences → DataStore
- [ ] Clipboard sync: CLIPBOARD_SYNC_ENABLED feature
- [ ] Macro recording: MACROS_ENABLED feature (future)
- [ ] Help text: onboarding tooltips
- [ ] Version info: BuildConfig display in About screen
- [ ] Accessibility mode cursor overlay: visible dot at virtual cursor position
- [ ] Sensitivity calibration: live preview in settings
