# Project State

## Current Version
`0.6.0` — Phase 6: Bluetooth HID transport wired into BridgeService + Settings UI

## Repository
**GitHub:** https://github.com/Suydev/android-input-bridge  
**Branch:** `main`  
**Last commit:** session 007 — Phase 5 remainder + Phase 4 robust error handling

## CI Status
| Run | SHA | Status | Notes |
|-----|-----|--------|-------|
| TBD | session 009 | ⏳ pending | Phase 6 BT HID wiring |
| TBD | session 008 | ⏳ pending | BUG-011 fix + release.yml |
| #31 | `117eea9` | ❌ failure | BUG-011: ACTION_SELECT_ALL unresolved (all runs #27–#31 failed for same root cause) |
| #30 | `c0bbe0e` | ❌ failure | BUG-011: same root cause |
| #29 | `57fac4e` | ❌ failure | BUG-011: same root cause |
| #28 | `d2c84ea` | ❌ failure | BUG-011: same root cause |
| #27 | `2bc466f` | ❌ failure | BUG-010 + BUG-011 compounded |
| #26 | `75a09b6` | ✅ success | Last green build (before session 005) |
| #25 | `9931cb8` | ✅ success | Both APKs built, unit tests pass |

**CI pipeline:** `.github/workflows/ci.yml`
- Builds: `app-bridge` debug APK + `app-receiver` debug APK
- Tests: unit tests in `shared-core` and `protocol`
- APK artifacts retained for 30 days

## Phase Status
| Phase | Name                     | Status      | Complete |
|-------|--------------------------|-------------|----------|
| 1     | Project scaffold         | ✅ Done     | 100%     |
| 2     | USB input wiring         | ✅ Done     | 100%     |
| 3     | Keep-alive / pairing     | ✅ Done     | 95%      |
| 4     | Accessibility receiver   | ✅ Done     | 90%      |
| 5     | Latency + reconnect      | ✅ Done     | 95%      |
| 6     | Bluetooth HID (Path A)   | ✅ Done     | 95%      |
| 7     | Release + distribution   | 🔒 Blocked  | 0%       |

## What Session 006 Delivers

### BUG-010 Fix (CI blocker)
- `accessibility-receiver/build.gradle.kts` now declares `implementation(project(":diagnostics"))`
- `InputBridgeAccessibilityService.kt` imports `DiagnosticsManager` — this was causing CI failure for commit `2bc466f`

### Phase 3 Remainder — Pairing

**Protocol layer:**
- `EventPacketFactory`: `makePairRequest(pin)`, `makePairResponse(accepted)`, `makePairConfirm()`
- `PacketSerializer`: `buildPairRequestPayload/parsePairRequestPin`, `buildPairResponsePayload/parsePairResponseAccepted`

**Bridge side (`BridgeService`):**
- After UDP transport connects: registers incoming-packet collector FIRST (prevents PAIR_RESPONSE miss race)
- If PIN configured and not yet paired: sends `PAIR_REQUEST`, waits 10 s for `PAIR_RESPONSE`
- On accept: persists `isPaired = true`, sends `PAIR_CONFIRM`, starts normal operation
- On reject/timeout: updates notification "Pairing failed — check PIN in Settings"
- If already paired: skips handshake, logs "Already paired"
- `BridgePreferences`: added `pairingPin`, `isPaired`, `setPinAndClearPairing()`

**Receiver side (`ReceiverService`):**
- Generates 6-digit random PIN on first run (persisted to `ReceiverPreferences`)
- Publishes PIN to `DiagnosticsManager.sessionPin` for UI
- Tracks `pairedBridgeIp` — source validation: drops packets from other IPs (except `PAIR_REQUEST`)
- `PAIR_REQUEST` → validates PIN → sends `PAIR_RESPONSE` → if accepted, records bridge IP
- `PAIR_CONFIRM` → logs; pairing complete
- `DISCONNECT` → clears pairing state
- `ReceiverPreferences`: added `sessionPin`, `pairedBridgeIp`, `isPaired`, `generateNewPin()`

**UI:**
- `ConnectionScreen` (receiver): prominent 6-digit PIN display + "REGENERATE PIN" button, paired/unpaired status
- `SettingsScreen` (bridge): "Pairing" section with PIN entry field, paired status, "Clear" button

**ViewModels:**
- `BridgeViewModel`: `isPaired`, `setPairingPin(pin)`, `clearPairing()`
- `ReceiverViewModel`: `sessionPin`, `isPaired`, `generateNewPin()`

### Phase 5 Partial — Reconnect + Packet Loss

**Reconnect (BridgeService):**
- `startWatchdog()`: after 15 s grace period, checks every 3 s if PONG timeout exceeded (10 s)
- `triggerReconnect()`: exponential backoff (1 → 2 → 4 → 8 → 16 → 30 s, up to 10 attempts)
- `reconnectInProgress` AtomicBoolean guards against concurrent reconnect loops
- On success: re-registers incoming loop, re-pairs if needed, restarts ping + watchdog
- `DiagnosticsData`: `isReconnecting`, `reconnectAttempts`, `lastReconnectAttempt`
- `connectionLabel` in `BridgeViewModel`: shows "Reconnecting… (attempt N)"

**Packet loss detection (ReceiverService):**
- Tracks `lastInputSeqNo` across input event packets
- Gaps in sequence numbers increment `droppedSequencePackets` AtomicLong
- Flushed to `DiagnosticsData.packetsDroppedSequence` every 1 s

**Transport:**
- `UdpTransport.getLastSenderIp()`: exposes last sender's IP for source validation

**DiagnosticsData new fields:**
- `isPaired`, `sessionPin`, `pairedPeerIp`
- `isReconnecting`
- `packetsDroppedSequence`

## Known Issues / Limitations
- No pairing QR code — manual PIN entry only (QR deferred to Phase 7)
- No launcher icons (debug builds warn but don't fail)
- Visual cursor overlay not yet implemented (Phase 7)
- `BluetoothHidTransport` is Phase 6 stub only
- Config persisted to SharedPreferences; DataStore migration in Phase 7
- Manual test on real Portronics Key2 Combo hardware not yet performed
- Wi-Fi Direct transport not yet implemented
- No signing secrets configured yet — release APKs are unsigned debug builds until
  `SIGNING_KEYSTORE_BASE64` / `SIGNING_KEY_ALIAS` / `SIGNING_KEY_PASSWORD` /
  `SIGNING_STORE_PASSWORD` secrets are added to the GitHub repository

## Next Task — Phase 5 Remainder + Phase 4 Completion

### Phase 5 remaining
1. **Latency tracing** — capture → serialize → send → receive → inject timestamps
2. **Rolling latency average** — display in DiagnosticsScreen and BridgeScreen

### Phase 4 remaining
1. **Robust error handling** — accessibility service disconnect, secure window detection
2. **Visual cursor overlay** (optional, Phase 7)

## Manual Test Procedure (current build)

### Receiver (OnePlus Pad Go)
1. Install `app-receiver` APK
2. Enable InputBridge Receiver accessibility service in Settings → Accessibility
3. Open the app → navigate to Connection screen → note the 6-digit PIN

### Bridge (Redmi 9)
1. Install `app-bridge` APK
2. Open app → Settings → enter receiver's IP + the 6-digit PIN shown on receiver → Save
3. Tap "Start Bridge" — should show "Pairing…" then "Paired"
4. Plug in Portronics Key2 Combo receiver via USB OTG
5. Grant the USB permission prompt
6. Diagnostics: USB device connected ✓, capture active ✓, paired ✓

### End-to-end verification
- Type on keyboard → characters appear in focused text field on tablet
- Move mouse → virtual cursor updates (gestures hit at new position)
- Left click → tap at cursor position
- Right click → long press
- Scroll wheel → scroll gesture on tablet
- Unplug bridge's network → should auto-reconnect after 10–60 s

## Architecture Quick Reference
```
[Portronics Key2] → USB OTG → [Redmi 9: BridgeService]
                                    ↓ UsbInputCapture (HID polling)
                                    ↓ EventPacketFactory.fromEvent()
                                    ↓ UdpTransport.send() (DatagramSocket)
                                    ↓ Wi-Fi LAN (UDP, fire-and-forget)
                                    ↓ PING every 1s + PAIR_REQUEST on start
                                    ↓
                              [OnePlus Pad Go: ReceiverService]
                                    ↓ UdpTransport.incomingPackets
                                    ↓ Source IP validation (pairedBridgeIp)
                                    ↓ PAIR_REQUEST → PIN validate → PAIR_RESPONSE
                                    ↓ PING → PONG (latency measurement)
                                    ↓ PacketToEventConverter.toInputEvent()
                                    ↓ AccessibilityCommandBus.post()
                                    ↓ InputBridgeAccessibilityService
                                    ↓ injectKeyCode / injectText / tap / swipe
```
