# Project State

## Current Version
`0.1.0` — Phase 1 scaffold complete, pushed to GitHub

## Repository
**GitHub:** https://github.com/Suydev/android-input-bridge  
**Branch:** `main`  
**Last commit:** Session 002 — Git push + build system fixes

## Phase Status
| Phase | Name                     | Status      | Complete |
|-------|--------------------------|-------------|----------|
| 1     | Project scaffold         | ✅ Done     | 100%     |
| 2     | USB input wiring         | ⏳ Next     | 0%       |
| 3     | Wi-Fi UDP transport      | 🔒 Blocked  | 0%       |
| 4     | End-to-end integration   | 🔒 Blocked  | 0%       |
| 5     | Reliability + UX polish  | 🔒 Blocked  | 0%       |
| 6     | Bluetooth HID (Path A)   | 🔒 Blocked  | 0%       |
| 7     | Release + distribution   | 🔒 Blocked  | 0%       |

## CI Status
- GitHub Actions workflow: `.github/workflows/ci.yml`
- Triggered on push to `main`
- Builds: `app-bridge` debug APK + `app-receiver` debug APK
- Tests: unit tests in `shared-core` and `protocol`
- APK artifacts retained for 7 days

## Next Task — Phase 2: USB Input Wiring

### Goal
Wire the `UsbInputCapture` into `BridgeService` so real USB HID events
flow from the Portronics Key2 Combo receiver → over UDP → to the OnePlus Pad Go.

### Specific Tasks
1. **BridgeService** — replace stub pipeline:
   - Create coroutine scope (SupervisorJob + Dispatchers.IO)
   - Start `UsbInputCapture`, collect `events` Flow
   - Pipe events through `EventPacketFactory.fromInputEvent()`
   - Send packets via `UdpTransport.send()`
   - Update `DiagnosticsManager` counters on each packet
2. **USB permission flow** — handle `ACTION_USB_DEVICE_ATTACHED`:
   - Register `BroadcastReceiver` for USB attach/detach
   - Call `UsbManager.requestPermission()` if not already granted
   - Permission result intent → open device → start `UsbInputCapture`
3. **ReceiverService** — replace stub:
   - Start `UdpTransport` in receive mode (listen on configurable port)
   - Collect `incomingPackets` Flow
   - Deserialize via `PacketSerializer`
   - Dispatch `InputEvent` to `AccessibilityCommandBus`
4. **DiagnosticsManager wiring** — real counters from both services
5. **Manual test procedure** — see TESTING.md Phase 2 section

### Entry Point
`app-bridge/src/main/kotlin/com/inputbridge/bridge/service/BridgeService.kt`

## Known Issues / Limitations
- No launcher icons created (build warns, doesn't fail for debug APKs)
- `DiagnosticsManager.updateLatency()` not yet called from transport
- `AccessibilityCommandBus` virtual cursor is unconstrained pending real screen dims
- `BluetoothHidTransport` is Phase 6 stub only

## Architecture Quick Reference
```
[Portronics Key2] → USB OTG → [Redmi 9: BridgeService]
                                    ↓ UsbInputCapture
                                    ↓ EventPacketFactory → binary UDP packet
                                    ↓ UdpTransport (DatagramSocket)
                                    ↓ Wi-Fi LAN
                                    ↓
                              [OnePlus Pad Go: ReceiverService]
                                    ↓ UdpTransport (receive)
                                    ↓ PacketSerializer.deserialize()
                                    ↓ AccessibilityCommandBus
                                    ↓ InputBridgeAccessibilityService
                                    ↓ dispatchGesture / performGlobalAction
```
