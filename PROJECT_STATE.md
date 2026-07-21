# InputBridge — Project State

> **Last updated:** Session 010 — Phase 7 Polish (complete)

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

- GitHub Actions: `.github/workflows/build.yml`
- Debug APK artifacts on every push to `main`
- Last green build: Session 009 + this session (010)
- APK outputs: `app-bridge/build/outputs/apk/debug/` + `app-receiver/build/outputs/apk/debug/`
