# CHANGELOG.md

All meaningful changes recorded chronologically.

---

## [0.1.0] — 2025-07-19

**Phase 1 scaffold complete.**

### Added
- Multi-module Android Gradle project: app-bridge, app-receiver, shared-core, protocol, input-capture, transport-wifi, transport-bluetooth-hid, accessibility-receiver, diagnostics, build-logic
- Gradle version catalog (libs.versions.toml): AGP 8.4.2, Kotlin 2.0.0, Compose BOM 2024.06.00
- Convention plugins: AndroidAppConventionPlugin, AndroidLibraryConventionPlugin, AndroidComposeConventionPlugin
- `shared-core`: InputEvent sealed hierarchy (KeyDown, KeyUp, MouseMove, MouseButtonDown/Up, Scroll, TextInput, ModifierStateChanged, NavigationAction)
- `shared-core`: ModifierState with 1-byte bitmask serialization
- `shared-core`: AppConfig, TransportConfig, MouseConfig, DisplayConfig, SecurityConfig
- `shared-core`: FeatureFlags (BLUETOOTH_HID_ENABLED, WIFI_DIRECT_ENABLED, etc.)
- `shared-core`: BridgeLogger (Timber wrapper, ProductionTree)
- `protocol`: PacketType enum (frozen IDs: 0x00–0x28)
- `protocol`: Packet data class (14-byte header: version + type + seqNo + timestampMs + payload)
- `protocol`: PacketSerializer (stateless binary, big-endian, thread-safe)
- `protocol`: EventPacketFactory (InputEvent → Packet, AtomicInteger sequence counter)
- `input-capture`: InputCapture interface + CaptureStatus sealed class
- `input-capture`: UsbInputCapture (USB HID interrupt transfer reading, keyboard + mouse parsing)
- `input-capture`: KeyMap (HID Usage 0x04–0xE7 → Android KEYCODE)
- `transport-wifi`: Transport interface + ConnectionState sealed class
- `transport-wifi`: UdpTransport (DatagramSocket, separate send/receive coroutines)
- `accessibility-receiver`: InputBridgeAccessibilityService (tap, swipe, long-press, nav actions)
- `accessibility-receiver`: AccessibilityCommandBus (virtual cursor, singleton, command flow)
- `diagnostics`: DiagnosticsData (full state snapshot)
- `diagnostics`: DiagnosticsManager (singleton StateFlow, atomic packet counters)
- Bridge app: BridgeApplication, BridgeModule (Koin), BridgeViewModel
- Bridge app: BridgeService (foreground, WakeLock, START_STICKY)
- Bridge app: BootReceiver (auto-start on BOOT_COMPLETED)
- Bridge app: MainActivity, NavHost (6 routes)
- Bridge app screens: WelcomeScreen, BridgeScreen, SettingsScreen, DiagnosticsScreen, PermissionsScreen, AboutScreen
- Bridge app: Terminal-aesthetic dark theme (black bg, green primary, monospace font)
- Bridge app: AndroidManifest with USB HID device filter
- Receiver app: ReceiverApplication, ReceiverModule, ReceiverViewModel
- Receiver app: ReceiverService (foreground, START_STICKY)
- Receiver app: BootReceiver
- Receiver app: MainActivity, NavHost (5 routes)
- Receiver app screens: WelcomeScreen, ConnectionScreen, AccessibilitySetupScreen, ReceiverSettingsScreen, ReceiverDiagnosticsScreen
- Receiver app: Blue-accent dark theme
- Receiver app: AndroidManifest with AccessibilityService declaration
- Receiver app: accessibility_service_config.xml (canPerformGestures=true)
- GitHub Actions CI: debug APKs, unit tests, optional release APKs
- Unit tests: PacketSerializerTest (13 test cases, all packet types)
- Unit tests: InputEventTest (8 test cases, model correctness)
- Documentation: README, AI_CONTEXT, PROJECT_STATE, TASKS, ROADMAP, DECISIONS, CHANGELOG, BUGS, SESSION_LOG, TESTING, MODULES, PROTOCOL, BUILD

### Architecture decisions recorded
- DEC-001: Binary protocol over JSON
- DEC-002: UDP as default transport
- DEC-003: PacketType IDs are frozen
- DEC-004: Accessibility over overlay
- DEC-005: Bluetooth HID as Path A
- DEC-006: Koin for DI
- DEC-007: DiagnosticsManager as singleton
