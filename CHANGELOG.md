# CHANGELOG.md

All meaningful changes recorded chronologically.

---

## [0.2.1] — 2026-07-19

**CI build fixed — both APKs now build cleanly on GitHub Actions (run #25).**

### Fixed
- `DiagnosticsManager.flushCounters()` — inside the `DiagnosticsData.() -> DiagnosticsData`
  lambda, `packetsSent`/`packetsReceived` resolved to the data class fields (Long), not the
  outer `AtomicLong` objects. Fixed by capturing atomic values as locals before the lambda.
- `InputBridgeAccessibilityService` — two `companion object` blocks in one class; Kotlin
  only allows one. Merged `TAP_DURATION_MS` into the first companion object.
- `UsbInputCapture` — all three USB polling loops used `isActive(coroutineContext)` which
  is not a valid API. Fixed to `this@UsbInputCapture.isActive && coroutineContext.isActive`.
- `app-receiver/res/values/themes.xml` — parent `android:Theme.Material.NoTitleBar.Fullscreen`
  is not resolvable via AAPT in this SDK setup. Changed to `Theme.Material3.Dark.NoActionBar`
  (consistent with app-bridge, provided by the declared Material3 dependency).
- `AndroidAppConventionPlugin` — `buildFeatures { buildConfig = true }` was missing,
  preventing `BuildConfig` class generation in both app modules.
- `BridgeModule` / `ReceiverModule` — `org.koin.android.ext.koin.androidContext` import
  was missing, causing `Unresolved reference 'androidContext'` at compile time.
- `ReceiverService` — `android.R.drawable.ic_menu_receive` does not exist in the Android SDK.
  Replaced with `android.R.drawable.ic_menu_send`.

### Commits
- `774ba97` Fix: 3 compile errors (DiagnosticsManager, InputBridgeAccessibilityService, UsbInputCapture)
- `8dbec88` Fix: app-receiver theme parent (Material3 not android:Theme.Material)
- `9931cb8` Fix: 4 more compile errors (BuildConfig, androidContext, ic_menu_receive)

---

## [0.2.0] — 2026-07-19

**Phase 2 USB input wiring complete.**

### Added
- `protocol/PacketToEventConverter.kt` — stateless `Packet → InputEvent` converter;
  returns null for control packets (PING, PONG, PAIR_*); lives in `protocol` module
  so it can be unit-tested independently of Android framework.
- `app-bridge/prefs/BridgePreferences.kt` — SharedPreferences wrapper persisting
  `targetIp` and `port` across process restarts.
- `app-receiver/prefs/ReceiverPreferences.kt` — SharedPreferences wrapper persisting
  `port` (listen port, default 54321).

### Changed
- `BridgeService` — full Phase 2 pipeline:
  - Dynamic `BroadcastReceiver` registered in-service for USB attach/detach/permission
  - `UsbManager.requestPermission()` flow with `PendingIntent` broadcast
  - `UsbInputCapture.start()` on device attach (post permission grant)
  - Hot path: `InputEvent → EventPacketFactory.fromEvent() → UdpTransport.send()`
  - `DiagnosticsManager.onPacketSent()` / `onSendFailed()` per packet
  - 1 s periodic `DiagnosticsManager.flushCounters()` loop
  - Pre-attached HID device detection on service start
  - API 33+ `RECEIVER_NOT_EXPORTED` flag for receiver registration
  - `AtomicBoolean.compareAndSet` idempotency guard — prevents duplicate pipelines
    on rapid/concurrent `onStartCommand` calls
  - `withContext(NonCancellable)` teardown in `onDestroy` — USB + socket resources
    freed before `serviceScope` cancellation
- `ReceiverService` — full Phase 2 listener:
  - `UdpTransport` in receiver mode, binds to configurable port
  - Packet flow: `incomingPackets → PacketToEventConverter → AccessibilityCommandBus.post()`
  - `DiagnosticsManager.onPacketReceived()` per packet
  - 1 s periodic `DiagnosticsManager.flushCounters()` loop
  - `WakeLock` (was missing in Phase 1 stub)
  - Same `AtomicBoolean` idempotency + `NonCancellable` teardown pattern as BridgeService
- `BridgeModule` — `BridgePreferences` singleton added to Koin graph
- `ReceiverModule` — `ReceiverPreferences` singleton added to Koin graph
- `BridgeViewModel` — `BridgePreferences` injected; `setTargetIp()` / `setPort()` persist;
  prefs loaded on init
- `ReceiverViewModel` — `ReceiverPreferences` injected; `setListenPort()` persists;
  port loaded on init

### Commits
- `754fc99` Phase 2 initial implementation
- `5e9b520` Fix: reliable teardown + idempotent start in both services
- `a93b48e` Fix: atomic idempotency guard via AtomicBoolean.compareAndSet

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
