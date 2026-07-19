# MODULES.md — Module Reference

Every module described. Update when responsibilities change.

---

## shared-core

**Package**: `com.inputbridge.core`
**Purpose**: Foundation types used by all modules. No Android dependencies allowed here if avoidable — keep it pure Kotlin where possible.

**Responsibilities**:
- Input event model (`InputEvent` sealed class hierarchy)
- Modifier state (`ModifierState`)
- App configuration data classes (`AppConfig` and nested)
- Feature flags (`FeatureFlags`)
- Logging wrapper (`BridgeLogger`)

**Public APIs**:
- `InputEvent` — use as the lingua franca between all modules
- `ModifierState.toByte()` / `ModifierState.fromByte()` — wire serialization
- `AppConfig` — load from DataStore, pass to modules
- `FeatureFlags.*` — check before activating optional features
- `BridgeLogger.i/d/w/e()` — always prefer over raw Timber

**Dependencies**: Timber, Kotlin coroutines
**Owner**: All phases
**Status**: Complete (Phase 1)

---

## protocol

**Package**: `com.inputbridge.protocol`
**Purpose**: Binary wire protocol. Everything that crosses the network goes through here.

**Responsibilities**:
- Define all packet types (`PacketType` — IDs are frozen)
- Binary packet format (`Packet`)
- Stateless serialization/deserialization (`PacketSerializer`)
- Convert InputEvent → Packet (`EventPacketFactory`)

**Public APIs**:
- `PacketSerializer.serialize(Packet): ByteArray`
- `PacketSerializer.deserialize(ByteArray): Packet?`
- `EventPacketFactory.fromEvent(InputEvent): Packet?`
- `EventPacketFactory.makePing/Pong/KeepAlive/Disconnect()`

**Dependencies**: shared-core
**Owner**: Protocol is stable after Phase 1. Extend only by adding new PacketTypes.
**Status**: Complete (Phase 1)

---

## input-capture

**Package**: `com.inputbridge.input`
**Purpose**: Reads raw input from the USB HID receiver and emits typed InputEvents.

**Responsibilities**:
- Define the `InputCapture` interface
- USB HID interrupt transfer reading (`UsbInputCapture`)
- HID boot protocol keyboard and mouse report parsing
- HID Usage ID → Android KeyEvent mapping (`KeyMap`)

**Public APIs**:
- `InputCapture.events: Flow<InputEvent>` — collect for input stream
- `InputCapture.start(): Boolean` — call before collecting
- `InputCapture.stop()` — call on service destroy
- `UsbInputCapture.findHidDevices(Context): List<UsbDevice>` — enumerate available devices
- `KeyMap.hidToAndroid(Int): Int` — translate HID usage to Android keycode

**Dependencies**: shared-core, protocol
**Owner**: Phase 2 wires this into BridgeService
**Status**: Scaffold complete. Not yet wired to service. (Phase 1)

---

## transport-wifi

**Package**: `com.inputbridge.transport.wifi`
**Purpose**: Local network packet transmission between bridge and receiver.

**Responsibilities**:
- Define the `Transport` interface
- UDP socket-based transport (`UdpTransport`)
- Wi-Fi Direct peer-to-peer transport (`WifiDirectTransport` — stub, Phase 3)
- Connection state management

**Public APIs**:
- `Transport.send(Packet): Boolean`
- `Transport.incomingPackets: Flow<Packet>`
- `Transport.connectionState: Flow<ConnectionState>`
- `Transport.connect(): Boolean`
- `Transport.disconnect()`

**Dependencies**: shared-core, protocol, Ktor network
**Owner**: Phase 3 wires this into both services
**Status**: Scaffold complete. Not yet wired. (Phase 1)

---

## transport-bluetooth-hid

**Package**: `com.inputbridge.transport.bt`
**Purpose**: Bluetooth HID Device mode output — the only path to a real hardware cursor.

**Responsibilities**:
- Register as BluetoothHidDevice
- Build HID reports for keyboard and mouse
- Feature-flagged behind `FeatureFlags.BLUETOOTH_HID_ENABLED`
- Graceful fallback if HID Device API unsupported

**Dependencies**: shared-core, protocol
**Owner**: Phase 6
**Status**: Stub (Phase 1)

---

## accessibility-receiver

**Package**: `com.inputbridge.accessibility`
**Purpose**: Injects input actions on the OnePlus Pad Go via Android Accessibility Service.

**Responsibilities**:
- Accessibility service lifecycle (`InputBridgeAccessibilityService`)
- Command dispatch from network → accessibility API (`AccessibilityCommandBus`)
- Virtual cursor position tracking
- Tap, swipe, long-press, scroll gesture injection
- Global action dispatch (BACK, HOME, RECENTS, NOTIFICATIONS)

**Public APIs**:
- `AccessibilityCommandBus.post(InputEvent)` — enqueue an event for injection
- `AccessibilityCommandBus.setScreenSize(width, height)` — called once at startup
- `InputBridgeAccessibilityService.isRunning(): Boolean` — check if service is active

**Dependencies**: shared-core, protocol
**Owner**: Phase 4 completes the wiring
**Status**: Service registered, virtual cursor tracking done. Not yet receiving real packets. (Phase 1)

---

## diagnostics

**Package**: `com.inputbridge.diagnostics`
**Purpose**: Aggregates status from all modules into a single observable snapshot.

**Responsibilities**:
- Central state snapshot (`DiagnosticsData`)
- Thread-safe update and observation (`DiagnosticsManager`)
- Atomic packet counters (hot path safe)
- Latency recording

**Public APIs**:
- `DiagnosticsManager.state: StateFlow<DiagnosticsData>` — observe from UI
- `DiagnosticsManager.update { }` — update any field
- `DiagnosticsManager.onPacketSent/Received/Failed()` — call from transport hot path
- `DiagnosticsManager.recordLatency(ms)` — call on PONG receipt
- `DiagnosticsManager.flushCounters()` — call periodically (1s interval)

**Dependencies**: shared-core
**Owner**: All phases contribute updates
**Status**: Complete (Phase 1)

---

## app-bridge

**Package**: `com.inputbridge.bridge`
**Purpose**: The bridge app running on the Redmi 9.

**Responsibilities**:
- Main entry point (`BridgeApplication`, `BridgeModule`)
- Foreground service lifecycle (`BridgeService`)
- Auto-start on boot (`BootReceiver`)
- Full Compose UI (6 screens)
- ViewModel observing diagnostics + controlling service

**Dependencies**: All library modules
**Status**: Complete UI + service scaffold. Phase 2 wires capture. (Phase 1)

---

## app-receiver

**Package**: `com.inputbridge.receiver`
**Purpose**: The receiver app running on the OnePlus Pad Go.

**Responsibilities**:
- Main entry point (`ReceiverApplication`, `ReceiverModule`)
- Foreground service lifecycle (`ReceiverService`)
- Auto-start on boot (`BootReceiver`)
- Full Compose UI (5 screens)
- Accessibility service hosting (delegates to accessibility-receiver module)
- ViewModel observing diagnostics + controlling service

**Dependencies**: shared-core, protocol, transport-wifi, accessibility-receiver, diagnostics
**Status**: Complete UI + service scaffold. Phase 3/4 wires transport + injection. (Phase 1)
