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

## Session 021 pipeline invariants

- **UDP receiver replies use the observed sender endpoint.** The bridge sender binds an
  ephemeral source port. In receiver mode, send `PAIR_RESPONSE` and `PONG` to the complete
  `InetSocketAddress` captured from its datagram, never to the receiver's configured listen port.
- **Set lifecycle flags before starting guarded loops.** `UdpTransport` readers/writers require
  `isConnected`, and USB HID readers require `isActive`; set each flag before launching the
  coroutines or they can legitimately exit at their first loop condition.
- **Subscribe before emitting a non-replaying flow.** `InputCapture.events` has `replay = 0`.
  `BridgeService` must attach its collector before `UsbInputCapture.start()` launches HID readers.
- **The overlay pointer needs canvas padding.** Keep the logical hotspot aligned when changing its
  visual geometry: inset the shape for its stroke/shadow and compensate with the same layout
  offset.

## Session 022 UDP session invariants

- **A UDP reconnect creates a new session.** Recreate every closed send queue and clear the
  receiver's observed peer endpoint before starting its reader/writer jobs. Never let a new
  session send to the prior bridge's ephemeral port.
- **A transport-wide lifecycle flag is not enough for an old coroutine.** Reader loops must also
  honour their own `coroutineContext.isActive`; otherwise a cancelled reader can resume against a
  closed socket once a fast reconnect publishes `isConnected = true`.
- **A bound socket is not peer connectivity.** The bridge reports UDP connected only after PONG
  or accepted pairing; the receiver does so only after PING or an accepted pairing request.

## Recommended next implementation step

**Phase 5 remainder**: rolling latency average display, latency trace timestamps across pipeline stages.
**Phase 4 remainder**: robust error handling for accessibility service disconnect and secure windows.

## Session 038 auto-discovery invariant (BUG-133)

- **Discovery must be bidirectional.** One-way receiver broadcast is dropped on real Wi-Fi/hotspot
  stacks, so the bridge never connects without manual IP+PIN. The bridge broadcasts `INPUTBRIDGE_QUERY`
  and the receiver listens for it and replies `INPUTBRIDGE_RECEIVER:<port>` directly to the bridge's
  discovery listen port (DISCOVERY_PORT = 54322) — NOT the query's ephemeral source port, or the
  bridge's listener never sees the reply. The receiver also keeps broadcasting its presence.

## Session 045 single-APK invariants (BUG-141 → BUG-154)

- **One role per device, enforced by persisted state, not by package.** Since the merge, the installed
  package no longer implies the role. The user's choice from ModeSelectionActivity is persisted in
  `shared-core/.../config/AppRoleStore.kt` (prefs file `app_role`), and BOTH BootReceivers bail out unless
  the device's role matches. Each mode activity also stops the opposite role's service in onCreate.
  Bridge + receiver services MUST NOT run in the same process: they race for discovery port 54322 and
  the bridge "auto-discovers" its own in-process receiver over loopback.
- **Notification PendingIntents must use the merged app's activities by class name.** The old library
  launcher MainActivities are not in the merged manifest; `Intent...setClassName(this,
  "com.inputbridge.ui.bridge.BridgeModeActivity")` / `"...ui.receiver.ReceiverModeActivity"` opens the
  right screen. Libraries cannot compile against `:app`, so resolve by name.
- **USB permission-grant mid-session must re-enter capture.** The USB poll treats
  "known device, permission now granted, capture inactive" as a re-trigger for `startCapture()`, and
  never re-requests permission for a known device (that is the foreground activity's job, §5.6).
- **transportConnected is only proven by a PONG** (BUG-090); creating a socket proves nothing.
- **A `setTargetIp` keystroke is not a repair trigger.** Re-pair only on empty or full 4-octet IPv4.
- **AutoDiscovery listener loops never break on transient socket errors** — log, `delay(1000)`, keep
  listening (BUG-148).
- **Injection availability = a11y connected OR Shizuku available** (`isInjectionAvailable()`). A gate on
  `isServiceConnected()` alone silently killed the primary Shizuku path (§4.8).
- **Debug and release share `com.inputbridge`.** Debug builds must not use an `applicationIdSuffix` —
  permission state, boot records, and the role store would diverge between test and release installs.

## Session 048 crash-hardening invariants (BUG-157)

- **Android 10 USB access requires `android.permission.USB_PERMISSION` in the manifest.** `UsbManager.requestPermission()` + `claimInterface(iface, true)` (force) is correct, but without the manifest permission the grant is ineffective and `openDevice()` returns null → bridge loops on "USB device not found" on API 29.
- **Discovery `bind(54322)` is guarded** — a BindException (re-pair / role-switch race) must never escape and kill the service; wrap and bail the coroutine instead.
- **`usbManager` (bridge) is nullable** — the merged APK installs on non-USB-host hardware; a null must degrade to network/Bluetooth-only, never throw in onCreate (that skips startForeground and kills the process).
- **`startForeground` is wrapped in try/catch** on both services — API 33+ with POST_NOTIFICATIONS denied throws RemoteServiceException; catching degrades to "running without a notification" instead of a process kill (the 5s deadline is still met).
- **Receiver packet handling is wrapped** — a malformed packet is logged and dropped, never propagated to the service exception handler (which calls stopSelf()).
- **Trackpad divides by size.width/size.height only after a 0-size guard** (multi-window/foldable 0x0 frames).
- **Composable context as Activity / getSystemService as X casts are as? + safe-return**, never unsafe.
- **Heavy a11y injectText runs on Dispatchers.IO**, not the Main commandFlow collector (avoids ANR on deep a11y trees / long paste).
