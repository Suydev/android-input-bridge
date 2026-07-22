# InputBridge — Project State

> **Last updated:** Session 016 — first-launch crash + deep else→ audit BUG-058→062 (2026-07-22)

---

## Architecture

```
[USB Receiver dongle] ──OTG──► [Bridge phone (app-bridge)]
                                    │  transport
                        ┌───────────┴──────────────┐
                        │  UDP (LAN/hotspot)        │  ← default, <10ms RTT
                        │  Bluetooth HID (system)   │  ← real cursor, no receiver app
                        └───────────┬──────────────-┘
                                    ▼
                      [Tablet (app-receiver)]
                          │  AccessibilityService
                          ├─ keyboard injection (KeyEvent / SET_TEXT)
                          ├─ mouse injection (tap/swipe/long-press gestures)
                          └─ cursor dot overlay (CursorOverlayService)
```

---

## Phase Completion

| Phase | Description                              | Status       |
|-------|------------------------------------------|--------------|
| 1     | Project scaffold & module structure      | ✅ Complete  |
| 2     | USB HID capture (keyboard + mouse)       | ✅ Complete  |
| 3     | UDP transport (bidirectional PING/PONG)  | ✅ Complete  |
| 4     | Accessibility injection (key + gesture)  | ✅ Complete  |
| 5     | UI (bridge + receiver all screens)       | ✅ Complete  |
| 6     | Bluetooth HID transport                  | ✅ Complete  |
| 7     | Polish (all items)                       | ✅ Complete  |
| 8     | Wi-Fi Direct / TCP transport (stubs)     | 🔜 Future   |
| 9     | DataStore migration                      | 🔜 Future   |

---

## Phase 7 — Delivered Features

### Bridge app
| Feature | Detail |
|---------|--------|
| **Black Screen Mode** | Dims window to hardware-minimum brightness; pure-black UI; tiny status dot |
| **Keep Screen On** | `FLAG_KEEP_SCREEN_ON` controlled by pref (was always-on before) |
| **Show Latency** | Toggle latency number on BridgeScreen (was always shown) |
| **Sensitivity slider** | Bridge-side mouse scaling 0.1–5× (was no-op stub) |
| **Screen brightness slider** | Per-window brightness override -1 (system) to 100% |
| **Black Screen Mode toggle** | Settings screen, accent-red to signal its effect |
| **Auto-start on Boot toggle** | Reads `BridgePreferences.autoStartOnBoot` (was FeatureFlags const) |
| **Emergency stop** | Hold Volume Down 3 s → `stopBridge()` + toast |
| **Live PermissionsScreen** | Battery opt, BLUETOOTH_CONNECT, NEARBY_WIFI_DEVICES, POST_NOTIFICATIONS |
| **WelcomeScreen cleanup** | Hides WIFI_DIRECT / TCP stub modes; shows only UDP + BT HID |

### Receiver app
| Feature | Detail |
|---------|--------|
| **Cursor dot overlay** | `CursorOverlayService` — floating crosshair via `SYSTEM_ALERT_WINDOW` |
| **Overlay toggle** | ReceiverSettingsScreen — auto-prompts for `SYSTEM_ALERT_WINDOW` if absent |
| **Auto-start on Boot toggle** | Reads `ReceiverPreferences.autoStartOnBoot` (was FeatureFlags const) |
| **Emergency stop** | Hold Volume Down 3 s → `stopReceiver()` + toast |
| **Landscape support** | `screenOrientation="portrait"` removed from receiver manifest |
| **Scroll sensitivity slider** | ReceiverSettingsScreen (was present but wired; now also labeled clearly) |

### Shared
| Feature | Detail |
|---------|--------|
| **`AccessibilityCommandBus.cursorPosition`** | `StateFlow<Pair<Float,Float>>` published on every MouseMove |
| **`DisplayConfig` expanded** | `blackScreenMode`, `showLatencyOverlay`, `keepScreenOn`, `screenBrightness`, `autoStartOnBoot`, `showCursorOverlay` |
| **`DiagnosticsData` expanded** | `blackScreenMode`, `cursorOverlayActive` fields added |
| **BootReceivers** | Both now read from prefs (user-controllable) instead of FeatureFlags constant |

---

## Known Limitations / Future Work

| Item | Notes |
|------|-------|
| Wi-Fi Direct / TCP | Stubs only — blocked on `WifiP2pManager` complexity and TCP framing |
| DataStore migration | Intentionally deferred — risk without compilation gate |
| Physical multi-button combo stop | Currently: hold Volume Down 3 s (reliable single-button approach) |
| `WRITE_SETTINGS` | In bridge manifest but not actually used — screen brightness via `WindowManager.LayoutParams` |
| Cursor overlay API level | `CursorOverlayService` requires API 24+ (`@RequiresApi(N)`) — guarded by `Build.VERSION.SDK_INT` checks |

---

## Module Map

```
shared-core/              — AppConfig, TransportMode, FeatureFlags, logging
diagnostics/              — DiagnosticsData, DiagnosticsManager
transport-udp/            — UdpTransport (bidirectional, PING/PONG)
transport-bluetooth-hid/  — BluetoothHidTransport (Phase 6)
usb-capture/              — UsbInputCapture, HID descriptor parser
hid-parser/               — KeyEvent mapping, mouse HID report parser
accessibility-receiver/   — AccessibilityCommandBus, InputBridgeAccessibilityService
app-bridge/               — Bridge phone app (capture → transport)
app-receiver/             — Tablet receiver app (transport → inject)
```

---

## CI / Build

- GitHub Actions: `.github/workflows/ci.yml`
- Debug APK artifacts + unit tests on every push to `main`, `develop`, `feature/**`, `phase/**`
- Release APKs built on `main` push (requires `SIGNING_KEYSTORE_BASE64` secret)
- Last green build: Session 015 (BUG-054→057); Session 016 pushed (BUG-058→062)
- APK outputs: `app-bridge/build/outputs/apk/debug/` + `app-receiver/build/outputs/apk/debug/`

---

## Bug Audit Status

All bugs BUG-001 through BUG-053 are tracked in `BUGS.md`.
- BUG-001–BUG-045: all FIXED in Sessions 001–013
- BUG-046–BUG-053: found in Session 014 deep audit
  - BUG-046 FIXED — dead `else` in sealed `when` (AccessibilityCommandBus)
  - BUG-047 FIXED — empty IP in notification after silence recovery (ReceiverService)
  - BUG-048 FIXED — USB interfaces not released before close (UsbInputCapture)
  - BUG-049 FIXED — stale capture latency not reset on reconnect (BridgeService)
  - BUG-050 FIXED — HidReportBuilder missing MENU + F13–F24 (BT HID silent drop)
  - BUG-051 FIXED — WIFI_DIRECT_ENABLED=true with stub transport (FeatureFlags)
  - BUG-052 WONTFIX — numLock always false (architectural; protocol change needed to fix)
  - BUG-053 FIXED — DiagnosticsManager.update race condition (synchronized)
- BUG-054–BUG-057: found in Session 015 audit pass + CI diagnosis
  - BUG-054 FIXED — KEYCODE_F13-F24 unresolved (don't exist in Android KeyEvent; CI failure since Session 013)
  - BUG-055 FIXED — continue in run{} inline lambda — Kotlin 2.0 experimental feature, CI failure
  - BUG-056 FALSE POSITIVE — ViewModel Context: Koin uses androidContext() (Application), safe
  - BUG-057 FIXED — MainActivity.applyKeepScreenOn() bypassed Koin DI for BridgePreferences
- BUG-058–062: found in Session 016
  - BUG-058 FIXED — requestNotificationPermissionIfNeeded() called before setContent{} — moved after
  - BUG-059 FIXED — else→ in BridgeService.startPipeline() silently routed WIFI_DIRECT/TCP to UDP
  - BUG-060 FIXED — else→ in ReceiverService packet handler corrupted packet-loss statistics
  - BUG-061 FIXED — else→Unit in BridgeService.startIncomingLoop() swallowed future rx→bridge packets
  - BUG-062 FIXED — else→ in WelcomeScreen TransportMode display strings
