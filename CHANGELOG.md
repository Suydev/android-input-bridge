# CHANGELOG.md

All meaningful changes recorded chronologically.

---

## [0.5.1] — 2026-07-21

**CI unblock: fix Ctrl+A compile error + add automatic GitHub Release workflow.**

### Fixed

- **BUG-011**: `AccessibilityNodeInfo.ACTION_SELECT_ALL` does not exist in the
  Android SDK — caused `Unresolved reference` in CI for all runs #27–#31.
  Fixed by replacing it with `AccessibilityNodeInfo.ACTION_SET_SELECTION` and a
  `Bundle` with `ACTION_ARGUMENT_SELECTION_START_INT = 0` and
  `ACTION_ARGUMENT_SELECTION_END_INT = text.length` (API 18+, stable through API 35).

### Added

- `.github/workflows/release.yml` — automatic GitHub Release workflow:
  - **Tag push** (`v*`): builds debug + optional signed release APKs, creates a
    versioned GitHub Release marked as latest.
  - **CI auto-release**: triggered on every successful `Android CI` run on `main`,
    creates a pre-release tagged `build-{run}-{sha}` with the debug APKs attached.
  - Signing is optional — if `SIGNING_KEYSTORE_BASE64` secret is absent, debug APKs
    are still published. Add signing secrets later to get release APKs.

---

## [0.5.0] — 2026-07-21

**Phase 5 remainder (latency trace + rolling average) + Phase 4 remainder (robust error handling).**

### Added

**Rolling latency average (Phase 5):**
- `DiagnosticsManager.recordLatency()`: maintains a sliding window of the last 10 PONG samples
  and computes a rolling average on each new reading (thread-safe via `synchronized`)
- `DiagnosticsData.latencyAvgMs`: new field, updated by every PONG receipt
- `BridgeScreen`: latency line now shows both last sample and rolling avg (`15ms · avg 12ms`)
- `DiagnosticsScreen` (bridge): two rows — "Latency (last)" and "Latency (avg)"
- `ReceiverDiagnosticsScreen`: same two latency rows

**Latency trace (Phase 5):**
- `DiagnosticsData.captureToSendUs`: bridge hot-path time from InputEvent emission to
  `UdpTransport.send()` return, measured in microseconds, flushed every 1 s
- `DiagnosticsData.receiveToInjectUs`: receiver time inside `AccessibilityCommandBus.handleEvent()`
  (dequeue → accessibility API return), measured in microseconds, flushed every 1 s
- `BridgeService.captureJob`: times hot path with `System.nanoTime()`, stores in `lastCaptureToSendUs`
  `AtomicLong`; flushed to `DiagnosticsData` in the existing 1 s counter-flush loop
- `AccessibilityCommandBus`: times `handleEvent()` in the `commandFlow.collect` lambda, stores in
  `lastInjectUs` AtomicLong; exposed via `getLastInjectUs()`
- `ReceiverService.counterFlushJob`: reads `AccessibilityCommandBus.getLastInjectUs()` every 1 s
  and writes to `DiagnosticsData.receiveToInjectUs`
- `DiagnosticsScreen` (bridge): added "Capture→Send" row (µs)
- `ReceiverDiagnosticsScreen`: added "Recv→Inject" row (µs)

**Robust error handling — accessibility (Phase 4):**
- `InputBridgeAccessibilityService.injectKeyCode()`: checks `rootInActiveWindow == null`
  before injection; sets `DiagnosticsData.isSecureWindow = true` and returns early if blocked
  (lock screen, system dialogs, etc.); wraps injection in try-catch to catch any framework
  exception and report it in `DiagnosticsData.lastInjectionError`
- `InputBridgeAccessibilityService.injectText()`: same secure-window guard + try-catch
- `DiagnosticsData.isSecureWindow`: new field — True while injection is blocked
- `DiagnosticsData.lastInjectionError`: new field — last injection exception message
- `ReceiverDiagnosticsScreen`: "Secure Window" row (OK / BLOCKED), "INJECT ERROR" section
- `AccessibilityCommandBus`: imports `DiagnosticsManager` (`:diagnostics` dependency
  already declared in `accessibility-receiver/build.gradle.kts`)

**Diagnostics screens:**
- Bridge `DiagnosticsScreen`: added rows for Latency Avg, Capture→Send, Paired,
  Reconnects, Reconnecting
- Receiver `ReceiverDiagnosticsScreen`: added rows for Paired (with peer IP), Session PIN,
  Latency Avg, Recv→Inject, Dropped (seq), Secure Window, Inject Error section

### Commits
- Session 007 — Phase 5 remainder + Phase 4 robust error handling

---

## [0.4.0] — 2026-07-21

**Phase 3 remainder (pairing handshake + source validation) + Phase 5 partial (reconnect + packet-loss detection).**

### Fixed
- BUG-010: `accessibility-receiver/build.gradle.kts` was missing
  `implementation(project(":diagnostics"))`, causing CI failure for commit `2bc466f`.

### Added

**Pairing (Phase 3):**
- `PacketSerializer`: `buildPairRequestPayload/parsePairRequestPin`,
  `buildPairResponsePayload/parsePairResponseAccepted`
- `EventPacketFactory`: `makePairRequest(pin)`, `makePairResponse(accepted)`, `makePairConfirm()`
- Receiver generates a 6-digit random session PIN on first run; persisted to `ReceiverPreferences`
- `BridgeService`: sends `PAIR_REQUEST` after connecting; waits 10 s for `PAIR_RESPONSE`;
  sends `PAIR_CONFIRM` on accept; updates notification on reject/timeout
- `ReceiverService`: validates incoming PIN; records `pairedBridgeIp`; drops packets from
  unknown IPs after pairing; clears pairing on `DISCONNECT`
- `BridgePreferences`: `pairingPin`, `isPaired`, `setPinAndClearPairing()`
- `ReceiverPreferences`: `sessionPin`, `pairedBridgeIp`, `isPaired`, `generateNewPin()`
- `ConnectionScreen` (receiver): prominent 6-digit PIN display + "REGENERATE PIN" button
- `SettingsScreen` (bridge): "Pairing" section with PIN entry, paired status, "Clear" button
- `BridgeViewModel`: `isPaired`, `setPairingPin(pin)`, `clearPairing()`
- `ReceiverViewModel`: `sessionPin`, `isPaired`, `generateNewPin()`

**Reconnect (Phase 5 partial):**
- `BridgeService`: `startWatchdog()` — 15 s grace period, checks every 3 s for PONG timeout
- `BridgeService`: `triggerReconnect()` — exponential backoff (1→30 s), up to 10 attempts,
  guarded by `reconnectInProgress` AtomicBoolean; re-pairs if needed on reconnect
- `DiagnosticsData`: `isReconnecting`, `reconnectAttempts`, `lastReconnectAttempt`
- `BridgeViewModel.connectionLabel`: shows "Reconnecting… (attempt N)"

**Packet loss detection (Phase 5 partial):**
- `ReceiverService`: tracks `lastInputSeqNo`; counts sequence-number gaps as
  `droppedSequencePackets` AtomicLong; flushed to `DiagnosticsData.packetsDroppedSequence` every 1 s

**Transport:**
- `UdpTransport.getLastSenderIp()`: exposes last sender's IP address for source validation

**DiagnosticsData new fields:**
- `isPaired`, `sessionPin`, `pairedPeerIp`, `isReconnecting`, `packetsDroppedSequence`

### Commits
- Session 006 — BUG-010 fix + pairing + reconnect + packet loss detection

---

## [0.3.0] — 2026-07-21

**Phase 3+4: PING/PONG keep-alive + full accessibility injection.**

The app was previously "dummy" — settings changes had no effect, keyboard events were
silently dropped, the accessibility service never reported itself as active. This release
makes the full input pipeline functional.

### Fixed
- `UdpTransport` receiver mode: could not send replies (PONG etc.) because `startSendLoop`
  tried to resolve empty `config.targetIp`. Fixed by tracking `lastSenderAddress` from
  each received datagram and using it for replies in receiver mode.
- `InputBridgeAccessibilityService.onServiceConnected()`: never updated `DiagnosticsManager`
  or called `setScreenSize()`. Fixed: now fetches real screen dimensions, calls
  `AccessibilityCommandBus.setScreenSize()`, and sets `accessibilityEnabled = true`.
- `InputBridgeAccessibilityService.onUnbind()`: never cleared `accessibilityEnabled`. Fixed.
- `AccessibilityCommandBus`: all `KeyDown`, `KeyUp`, and `TextInput` events were silently
  dropped (`/* Phase 4 */` stubs). Fixed with full injection implementation.
- `ReceiverService`: `PING` packets were converted via `PacketToEventConverter` which returns
  null for control packets — they were silently dropped. Fixed: check `packet.type` first.
- `ReceiverSettingsScreen` sensitivity slider: `onValueChange = { /* Phase 7 */ }`. Fixed.
- `AccessibilityCommandBus` screen size hardcoded at 1080×2400. Fixed: real dimensions set
  from `onServiceConnected()` via `WindowManager`.

### Added

**PING/PONG keep-alive (Phase 3 partial):**
- `BridgeService`: sends a PING every 1 s once transport is connected
- `BridgeService`: collects `incomingPackets` and records latency from PONG replies
  (`DiagnosticsManager.recordLatency(latencyMs)`)
- `ReceiverService`: responds to PING with PONG via `packetFactory.makePong(seq)`
- `ReceiverService`: handles `KEEP_ALIVE` (log) and `DISCONNECT` (update UI/diagnostics)

**Full keyboard injection (Phase 4):**
- `InputBridgeAccessibilityService.injectKeyCode(keyCode, modifiers)`:
  - Printable characters: resolved via `KeyEvent.unicodeChar` with modifier meta-state
  - Backspace/Forward-delete: selection-aware `ACTION_SET_TEXT`
  - Enter: `ACTION_CLICK` on focused node (form submit) with newline fallback
  - Arrow keys: `ACTION_NEXT/PREVIOUS_AT_MOVEMENT_GRANULARITY` (char or word with Ctrl)
  - Home/End: line granularity movement with optional selection extension (Shift)
  - Escape: `GLOBAL_ACTION_BACK`
  - Tab: focus next accessible element
  - Ctrl+A/C/V/X: `ACTION_SELECT_ALL/COPY/PASTE/CUT`
- `InputBridgeAccessibilityService.injectText(text)`:
  - Primary: `ACTION_SET_TEXT` at cursor position (selection-aware)
  - Fallback: clipboard paste via `ClipboardManager`

**Mouse sensitivity (Phase 4):**
- `ReceiverPreferences.mouseSensitivity` field (default 1.0, persisted)
- `ReceiverViewModel.setMouseSensitivity()`: clamps 0.1–5.0, persists to prefs,
  applies immediately to `AccessibilityCommandBus.setSensitivity()` if service running
- `ReceiverSettingsScreen`: fully wired sensitivity slider with live label
- `AccessibilityCommandBus`: all mouse delta updates scaled by `mouseSensitivity`

### Commits
- `2bc466f` Phase 3+4: PING/PONG keep-alive, full keyboard injection, accessibility
  detection, mouse sensitivity

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
