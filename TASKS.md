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

## Phase 2 — USB Input Capture 🔲

- [ ] BridgeService: USB device attach/detach broadcast receiver
- [ ] BridgeService: request USB permission flow (UsbManager.requestPermission)
- [ ] BridgeService: UsbInputCapture.start() on device attach
- [ ] BridgeService: UsbInputCapture.stop() on device detach
- [ ] BridgeService: collect InputEvents from UsbInputCapture.events
- [ ] Test with real Portronics Key2 Combo hardware
- [ ] Verify KeyMap covers all keys on the Portronics keyboard
- [ ] Diagnostics: update usbDeviceConnected, usbDeviceName, inputCaptureActive
- [ ] Handle Rollkur Error: keyboard and mouse on separate HID interfaces

---

## Phase 3 — Network Transport + Pairing 🔲

- [ ] BridgeService: wire EventPacketFactory → UdpTransport.send()
- [ ] ReceiverService: wire UdpTransport → incoming packets → AccessibilityCommandBus
- [ ] Pairing: shared token generation (16-byte random)
- [ ] Pairing: QR code display on receiver, scan on bridge
- [ ] Pairing: PAIR_REQUEST / PAIR_RESPONSE / PAIR_CONFIRM packet flow
- [ ] Pairing: token persistence (DataStore)
- [ ] Pairing: packet source validation (reject unrecognized senders)
- [ ] Keep-alive: PING/PONG with latency measurement
- [ ] Reconnect: exponential backoff, max attempts
- [ ] Wi-Fi Direct transport: group formation, peer discovery
- [ ] Diagnostics: latencyMs, packetsSent, packetsReceived, transportConnected

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
- [ ] Settings persistence: save all config to DataStore
- [ ] Clipboard sync: CLIPBOARD_SYNC_ENABLED feature
- [ ] Macro recording: MACROS_ENABLED feature (future)
- [ ] Help text: onboarding tooltips
- [ ] Version info: BuildConfig display in About screen
- [ ] Accessibility mode cursor overlay: visible dot at virtual cursor position
- [ ] Sensitivity calibration: live preview in settings
