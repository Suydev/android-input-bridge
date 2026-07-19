# Project State

## Current Version
`0.2.1` — Phase 2 complete, CI green

## Repository
**GitHub:** https://github.com/Suydev/android-input-bridge  
**Branch:** `main`  
**Last commit:** `9931cb8` — Fix: 4 more compile errors (BuildConfig, androidContext, ic_menu_receive)

## CI Status ✅
| Run | SHA | Status | Notes |
|-----|-----|--------|-------|
| #25 | `9931cb8` | ✅ **success** | Both APKs built, unit tests pass |
| #24 | `8dbec88` | ❌ failure | androidContext import missing, BuildConfig, ic_menu_receive |
| #23 | `774ba97` | ❌ failure | AAPT: Theme.Material.NoTitleBar.Fullscreen not found |
| #22 | `a93b48e` | ❌ failure | DiagnosticsManager name shadow, 2× companion, isActive(ctx) |
| #21 | `5e9b520` | ❌ failure | Same pre-existing compile errors |

**CI pipeline:** `.github/workflows/ci.yml`
- Builds: `app-bridge` debug APK + `app-receiver` debug APK
- Tests: unit tests in `shared-core` and `protocol`
- APK artifacts retained for 30 days (download from GitHub Actions → run #25)
- Release APKs: built automatically on push to `main` if signing secrets are set

## Phase Status
| Phase | Name                     | Status      | Complete |
|-------|--------------------------|-------------|----------|
| 1     | Project scaffold         | ✅ Done     | 100%     |
| 2     | USB input wiring         | ✅ Done     | 100%     |
| 3     | Pairing + keep-alive     | ⏳ Next     | 0%       |
| 4     | Accessibility receiver   | 🔒 Blocked  | 0%       |
| 5     | Latency + reconnect      | 🔒 Blocked  | 0%       |
| 6     | Bluetooth HID (Path A)   | 🔒 Blocked  | 0%       |
| 7     | Release + distribution   | 🔒 Blocked  | 0%       |

## What Phase 2 Delivers (done)
- `BridgeService`: dynamic USB receiver, permission flow, UsbInputCapture
  lifecycle, hot-path pipeline (InputEvent → EventPacketFactory → UdpTransport)
- `ReceiverService`: UdpTransport bind, incomingPackets → PacketToEventConverter
  → AccessibilityCommandBus dispatch, WakeLock
- `BridgePreferences` / `ReceiverPreferences`: SharedPreferences wrappers
  so target IP and port survive service restarts
- `PacketToEventConverter`: stateless Packet→InputEvent converter in protocol module
- BridgeViewModel and ReceiverViewModel now persist config changes to prefs
- **Service lifecycle hardening**: AtomicBoolean idempotency guard in
  onStartCommand (set before launching coroutine, compareAndSet), and
  NonCancellable teardown in onDestroy to ensure socket/USB cleanup completes
  before scope cancellation.

## What Phase 2 CI Fixes Deliver (done in Session 004)
- `buildFeatures { buildConfig = true }` added to AndroidAppConventionPlugin
- `androidContext()` import fixed in both Koin module files
- DiagnosticsManager lambda name-shadow bug fixed (AtomicLong vs. data class field)
- Duplicate companion object in InputBridgeAccessibilityService merged
- `UsbInputCapture` loop guard fixed (`coroutineContext.isActive` not `isActive(ctx)`)
- Receiver app theme fixed (Material3 not android:Theme.Material)
- `ic_menu_receive` replaced with valid `ic_menu_send` drawable

## Known Issues / Limitations
- No pairing / token validation yet — any sender's packets are accepted (Phase 3)
- No keep-alive / reconnect yet (Phase 3)
- No launcher icons created (build warns, doesn't fail for debug APKs)
- `AccessibilityCommandBus` virtual cursor is unconstrained pending real screen dims
- `BluetoothHidTransport` is Phase 6 stub only
- Config persisted to SharedPreferences; DataStore migration in Phase 7
- Manual test on real Portronics Key2 Combo hardware not yet performed

## Next Task — Phase 3: Network Transport + Pairing

### Goal
Bridge and receiver can pair over local Wi-Fi, exchange keep-alive PINGs,
and reconnect automatically after disconnect.

### Specific Tasks
1. **Pairing** — shared token generation (16-byte random), PAIR_REQUEST /
   PAIR_RESPONSE / PAIR_CONFIRM packet sequence
2. **Pairing UI** — QR code or manual code entry on bridge + receiver
3. **Token persistence** — SharedPreferences (or DataStore) on both sides
4. **Packet source validation** — drop packets from un-paired senders
5. **Keep-alive** — PING/PONG every 1 s; `DiagnosticsManager.recordLatency()`
6. **Reconnect** — exponential backoff, `DiagnosticsManager.recordReconnectAttempt()`
7. **Wi-Fi Direct** — optional stretch goal; group formation after UDP pairing

### Entry Point
`app-bridge/src/main/kotlin/com/inputbridge/bridge/service/BridgeService.kt`
`app-receiver/src/main/kotlin/com/inputbridge/receiver/service/ReceiverService.kt`

## Manual Test Procedure (Phase 2)
1. On the receiver (OnePlus Pad Go):
   - Install `app-receiver` APK
   - Enable InputBridge Receiver accessibility service in Settings → Accessibility
   - Open the app → tap "Start Receiver"
   - Note the displayed IP address

2. On the bridge (Redmi 9):
   - Install `app-bridge` APK
   - Open the app → Settings → enter the receiver's IP address
   - Tap "Start Bridge"
   - Plug in the Portronics Key2 Combo receiver via USB OTG
   - Grant the USB permission prompt that appears
   - Diagnostics screen should show: USB device connected ✓, capture active ✓

3. Press keys on the keyboard:
   - Diagnostics → Packets Sent should increment on the bridge
   - Diagnostics → Packets Received should increment on the receiver
   - Mouse movement / clicks should trigger gestures on the tablet

## Architecture Quick Reference
```
[Portronics Key2] → USB OTG → [Redmi 9: BridgeService]
                                    ↓ UsbInputCapture (HID polling)
                                    ↓ EventPacketFactory.fromEvent()
                                    ↓ UdpTransport.send() (DatagramSocket)
                                    ↓ Wi-Fi LAN (UDP, fire-and-forget)
                                    ↓
                              [OnePlus Pad Go: ReceiverService]
                                    ↓ UdpTransport.incomingPackets
                                    ↓ PacketToEventConverter.toInputEvent()
                                    ↓ AccessibilityCommandBus.post()
                                    ↓ InputBridgeAccessibilityService
                                    ↓ dispatchGesture / performGlobalAction
```
