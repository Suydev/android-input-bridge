# AI_CONTEXT.md — InputBridge

**READ THIS FIRST before writing any code.**

If you are a new AI agent, continue development from PROJECT_STATE.md and TASKS.md before making architectural changes.

---

## Purpose

InputBridge turns a Redmi 9 phone into a keyboard/mouse bridge for a OnePlus Pad Go tablet. The Portronics Key2 Combo USB receiver plugs into the Redmi via OTG. Input events are forwarded locally (Wi-Fi UDP or Bluetooth HID) to the OnePlus.

## Hardware

| Device | Android API | RAM | Notes |
|---|---|---|---|
| Redmi 9 (Carbono) | API 29 (Android 10+) | 4GB | Bridge sender, runs app-bridge |
| OnePlus Pad Go | API 33+ (Android 13+) | 8GB | Receiver, runs app-receiver |
| Portronics Key2 Combo | — | — | USB HID keyboard + mouse receiver (2.4GHz dongle) |
| USB OTG adapter | — | — | USB-A to USB-C, connects receiver to Redmi |

## Project philosophy

- **Offline-first**: no internet required after install
- **Latency-first**: every design decision optimises for lowest input delay
- **Honesty**: the UI never lies about what mode is active or what Android can/cannot do
- **Modularity**: input capture, transport, and injection are separate modules that can evolve independently

## Latency targets

- Input capture → packet send: < 2ms
- Network round-trip (same LAN): < 5ms
- Total end-to-end: < 10ms for keyboard, < 20ms for mouse

## Architecture summary

```
Redmi 9:
  UsbInputCapture → InputEvent → EventPacketFactory → Packet → UdpTransport → UDP/Wi-Fi
                                                                              ↓
OnePlus Pad Go:                                              UdpTransport → Packet
                                                         PacketSerializer → InputEvent
                                                       AccessibilityCommandBus → tap/swipe/text
```

## Bug audit state (as of Session 014)

All bugs through BUG-053 are documented in `BUGS.md`. BUG-046 through BUG-053 were found
in a full deep audit on 2026-07-21. Key non-obvious constraints to preserve:

- **`HidReportBuilder.ANDROID_TO_HID` must be kept in sync with `KeyMap.HID_TO_ANDROID`.**
  Both are manually maintained inverse maps. BUG-050 happened because BUG-038 updated `KeyMap`
  but not `HidReportBuilder`. Any new key added for USB→accessibility must also be added for BT HID.

- **`AccessibilityCommandBus.handleEvent` must NOT use `else ->` in its `when (event)` block.**
  The sealed class exhaustiveness check is the safety net for new `InputEvent` subtypes.

- **`DiagnosticsManager.update {}` is now `synchronized(updateLock)`.** Do not inline
  `_state.value = _state.value.someTransform()` outside of `update`; it would re-introduce
  the race.

- **`FeatureFlags.WIFI_DIRECT_ENABLED` must stay `false`** until `WifiDirectTransport` is
  fully implemented.

- **`pairedBridgeIp` can be empty in open-mode sessions** — never format it directly into
  a user-visible string without an `isNotEmpty()` guard.

- **`KEYCODE_F13`–`KEYCODE_F24` do NOT exist in `android.view.KeyEvent`** (Android only
  defines F1–F12). Any attempt to use them as named constants will produce "Unresolved
  reference" compile errors. HID scan codes 0x68–0x73 must map to `KEYCODE_UNKNOWN` or be
  omitted — they are silently dropped at runtime, which is correct.

- **`continue` / `break` in inline lambdas (`?: run {}`, `forEach {}`)** require an opt-in
  compiler flag in Kotlin 2.0 (`-Xbreak-continue-in-inline-lambdas`). This project does NOT
  opt in. Always use an explicit `if (x == null) { continue }` null check instead of the
  `?: run { continue }` pattern. Same applies to any inline lambda that uses `continue`.

---

## Repository layout

```
app-bridge/              MainActivity + BridgeService + UI screens
app-receiver/            MainActivity + ReceiverService + UI screens
shared-core/             InputEvent model, AppConfig, FeatureFlags, BridgeLogger
protocol/                Packet, PacketType, PacketSerializer, EventPacketFactory
input-capture/           InputCapture interface, UsbInputCapture, KeyMap
transport-wifi/          Transport interface, UdpTransport, WifiDirectTransport (stub)
transport-bluetooth-hid/ BluetoothHidTransport (stub, Phase 6)
accessibility-receiver/  InputBridgeAccessibilityService, AccessibilityCommandBus
diagnostics/             DiagnosticsData, DiagnosticsManager
build-logic/             Convention plugins (AndroidApp, AndroidLibrary, AndroidCompose)
```

## Important modules

### shared-core
- `InputEvent` — sealed class hierarchy for all input (key, mouse, scroll, nav, text)
- `ModifierState` — compact modifier bitmask (serialises to 1 byte)
- `AppConfig` — configuration data classes
- `FeatureFlags` — feature toggles
- `BridgeLogger` — thin Timber wrapper (never log on hot path)

### protocol
- `PacketType` — 1-byte type IDs. **Do NOT change existing IDs — breaks pairing compatibility.**
- `Packet` — wire format: 14-byte header + payload
- `PacketSerializer` — stateless binary serializer, safe to call from multiple threads
- `EventPacketFactory` — converts InputEvent → Packet, generates sequence numbers

### input-capture
- `InputCapture` — interface: emit Flow<InputEvent>, never block main thread
- `UsbInputCapture` — reads USB HID interrupt transfers, parses keyboard (8-byte) and mouse (4-byte) boot protocol reports
- `KeyMap` — HID Usage ID → Android KeyEvent.KEYCODE_* mapping

### transport-wifi
- `Transport` — interface: connect/disconnect/send/incomingPackets/connectionState
- `UdpTransport` — DatagramSocket-based UDP, separate send and receive coroutines

### accessibility-receiver
- `InputBridgeAccessibilityService` — dispatchGesture for tap/swipe, performGlobalAction for nav
- `AccessibilityCommandBus` — singleton command bus between network layer and service
- Virtual cursor: mouse moves update cursorX/cursorY, clicks tap at that position

## Coding conventions

- Kotlin everywhere — no Java
- Jetpack Compose for all UI
- Coroutines + Flow for async (no RxJava, no LiveData)
- Koin for dependency injection
- Timber / BridgeLogger for all logging — never raw `Log.*` calls
- No logging on the hot path in production builds — check `FeatureFlags.LATENCY_TRACING_ENABLED`
- All flows: `MutableStateFlow` for state, `MutableSharedFlow` for events
- Foreground services must hold WakeLock while active

## Naming conventions

- `*Capture` — input capture implementations
- `*Transport` — network transport implementations
- `*Service` — Android services (foreground or accessibility)
- `*ViewModel` — Compose ViewModels
- `*Screen` — top-level Compose screens (stateful, receive ViewModel)
- `*CommandBus` — singleton dispatcher between layers

## Current assumptions

- The Portronics Key2 Combo reports as a standard HID boot protocol device (class 3, subclass 1/2)
- Both devices are on the same local Wi-Fi network or one acts as a hotspot
- Android 10+ (API 29) is the minimum — both devices satisfy this
- Battery optimization exemption must be granted by the user — we cannot grant it programmatically

## Known Android limitations

- **Accessibility services cannot create a real hardware cursor.** They inject synthetic gestures only.
- **Secure windows block accessibility injection.** Lock screen PIN entry will not work via accessibility.
- **MIUI/ColorOS may kill foreground services** despite WakeLock and FOREGROUND_SERVICE permission. User must enable autostart manually.
- **Bluetooth HID Device API** requires Android 9+ (API 28). Only works if the device's Bluetooth stack supports HID Device role (not all devices do).
- **Wi-Fi Direct group formation** is slow (several seconds). Not suitable for initial connection — use UDP for first connect, negotiate Wi-Fi Direct after.

## Important design decisions

See DECISIONS.md for full records. Short summary:
- **Protocol version locked at 1.** Never change existing PacketType IDs.
- **UDP is the default transport** — not TCP. Input events are fire-and-forget; TCP ACK overhead is unacceptable.
- **Binary protocol, not JSON** — packet size is critical on hot path.
- **Accessibility is Path B, not Path A.** Bluetooth HID is the correct path for real cursor support.
- **DiagnosticsManager is a singleton** accessed by all modules — simplest approach for Phase 1.

## Session 016 invariants

### Notification permission ordering (BUG-058)

`requestNotificationPermissionIfNeeded()` (and any other `ActivityResultLauncher.launch()` call)
MUST be called **after** `setContent {}`, never before it. Calling `launch()` before `setContent {}`
means the Compose `LifecycleOwner` and `ActivityResultRegistry` are not yet attached. On stock
Android this fails silently; on OEM builds (OxygenOS, MIUI) the registry throws
`IllegalStateException` when it tries to dispatch the result back to a non-existent LifecycleOwner.

Correct pattern in any `ComponentActivity.onCreate()`:
```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent { /* Compose tree */ }          // ← establishes LifecycleOwner
    requestNotificationPermissionIfNeeded()    // ← safe: LifecycleOwner exists
}
```

### Foreground service type is mandatory on Android 14+ (BUG-063)

Both `BridgeService` and `ReceiverService` declare `android:foregroundServiceType="connectedDevice"`
in their manifests. Every `startForeground()` call MUST use the 3-argument overload:
```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
} else {
    startForeground(id, notification)
}
```
If you add a second `startForeground()` call anywhere (e.g. in `onStartCommand` or after a
notification update), apply the same guard. Calling the 2-arg form on Android 14+ (API 34)
throws `MissingForegroundServiceTypeException` and crashes the entire app.

### PacketType exhaustiveness in service hot loops (BUG-059–062)

`ReceiverService`'s receive loop and `BridgeService`'s incoming-packet loop both use exhaustive
`when (packet.type)` over all `PacketType` values — **never add `else →`**. If a new `PacketType`
is added to the enum, the compiler will fail both `when` blocks, forcing the developer to
explicitly choose how each service handles it.

Likewise, `BridgeService.startPipeline()` and `WelcomeScreen` use exhaustive `when` over
`TransportMode` — adding a new mode must be handled in both.

**Critical correctness rule for ReceiverService (BUG-060):** sequence-gap detection
(`lastInputSeqNo` tracking) must ONLY run inside input-event packet arms, not control-packet
arms. Control packets do not carry input sequence numbers; running the gap detector on them
produces false "dropped packet" log entries and corrupts the Diagnostics counter.

## Do NOT change

- `PacketType` enum values (ID bytes) — changing breaks pairing compatibility
- `Packet.PROTOCOL_VERSION` without a migration plan
- `InputCapture.events` flow contract — must emit on background dispatcher
- `AccessibilityCommandBus` singleton lifecycle (tied to service)

## Safe to change

- UI layouts and colors (Compose)
- `AppConfig` fields (adding new fields is safe; removing is a breaking change)
- `FeatureFlags` constants (except `BLUETOOTH_HID_ENABLED`)
- `DiagnosticsData` fields (additive changes are safe)
- Transport implementations (must implement `Transport` interface)

## Current milestone

**Phase 7 complete (100%)** — All polish items delivered. Black screen mode, cursor dot overlay
(`CursorOverlayService`), emergency stop (Volume Down × 3s), live `PermissionsScreen` for both
apps, brightness slider redesign, network setup guide, auto-start toggle, landscape support for
receiver. See SESSION_LOG.md Session 010 for full change list.

Phase 8 items (Wi-Fi Direct, DataStore migration, clipboard sync, macro recording) are deferred
to future sessions. See ROADMAP.md and TASKS.md.

## Current TODO

Hardware test on real devices (Redmi 9 + OnePlus Pad Go + Portronics Key2 Combo). All code is
complete; remaining work is device-specific validation. See TASKS.md Phase 2 / Phase 6 open items.

## Current blockers

None. All known bugs BUG-001 through BUG-031 are fixed or documented. CI builds on every push to main.
See BUGS.md for full inventory.

## Future roadmap

See ROADMAP.md

## Expected behavior (current)

Both APKs build successfully. Bridge app:
- Connects to receiver via UDP
- Performs PAIR_REQUEST / PAIR_RESPONSE / PAIR_CONFIRM handshake using a 6-digit PIN
- Sends PING every 1 s; computes round-trip latency on PONG
- Reads USB HID keyboard+mouse events and forwards them as binary packets
- Auto-reconnects on PONG timeout (exponential backoff, up to 10 attempts)

Receiver app:
- Displays session PIN on Connection screen
- Validates incoming PAIR_REQUEST, sends PAIR_RESPONSE, records bridge IP
- Drops packets from unknown IPs after pairing
- Injects keyboard/mouse/scroll events via AccessibilityService
- Detects sequence-number gaps for packet-loss estimation

## Current testing status

Unit tests exist for protocol serialization and input event models. All tests pass locally.
Manual hardware test (Portronics Key2 Combo) not yet performed.

## Recommended next implementation step

**Phase 5 remainder**: rolling latency average display, latency trace timestamps across pipeline stages.
**Phase 4 remainder**: robust error handling for accessibility service disconnect and secure windows.
