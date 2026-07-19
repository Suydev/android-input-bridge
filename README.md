# InputBridge — Offline Android Input Bridge

Turn a **android device ** into a low-latency keyboard and mouse bridge for a **OnePlus Pad Go** using a **Portronics Key2 Combo** USB receiver connected via OTG.

Works entirely offline. No cloud. No accounts. No telemetry.
[![Android CI](https://github.com/Suydev/android-input-bridge/actions/workflows/ci.yml/badge.svg)](https://github.com/Suydev/android-input-bridge/actions/workflows/ci.yml)
---

## What it does

```
[Portronics Key2 Combo] ──USB OTG──► [android 1 (Bridge App)]
                                              │
                                      Wi-Fi UDP / HID
                                              │
                                     [android 2 (Receiver App)]
```

The **Bridge App** reads keyboard and mouse input from the USB receiver and sends it over your local network. The **Receiver App** listens for those packets and injects them as screen input using Android's Accessibility Service (or Bluetooth HID for a real cursor).

---

## Hardware

| Device | Role |
|---|---|
| android 1 | Bridge (sends input) |
| android 2 | Receiver (accepts input) |
| your keyboard | USB HID keyboard + mouse |
| USB OTG adapter | Connects receiver to Redmi 9 |

---

## Quick Start

### 1. Install APKs

Download the latest APKs from [GitHub Actions](../../actions) → most recent successful workflow → Artifacts.

- `bridge-debug-apk-*` → install on **android 1**
- `receiver-debug-apk-*` → install on **android 2**

### 2. First-time setup (Redmi 9 — Bridge)

1. Open **InputBridge** on the Redmi 9
2. Tap **Permissions** and follow all steps
3. Exempt from battery optimization (critical for screen-off use)
4. Enable MIUI Autostart if prompted
5. Plug in the Portronics receiver via OTG
6. Set the **Receiver IP** in Settings (your OnePlus Pad Go's IP)
7. Tap **Start Bridge**

### 3. First-time setup (OnePlus Pad Go — Receiver)

1. Open **InputBridge Receiver**
2. Tap **Enable Accessibility Service** and follow the on-screen steps
3. Tap **Start Receiver**
4. The status dot turns blue when connected

### 4. Finding your tablet's IP address

Settings → Wi-Fi → tap your network → IP Address

---

## Permissions

### Bridge App (android 1)

| Permission | Reason |
|---|---|
| USB Host | Read keyboard/mouse from OTG receiver |
| Foreground Service | Keep bridge alive in background |
| Wake Lock | Continue while screen is off |
| Battery Optimization Exemption | Survive aggressive power management |
| Internet / Wi-Fi | Send input packets to receiver |
| Nearby Wi-Fi Devices | Wi-Fi Direct mode (optional) |
| Bluetooth Connect | Bluetooth HID mode (optional) |
| Receive Boot Completed | Auto-start after reboot |

### Receiver App (android 2)

| Permission | Reason |
|---|---|
| Accessibility Service | Inject taps, gestures, navigation |
| Internet / Wi-Fi | Receive input packets |
| Foreground Service | Keep receiver alive |
| Receive Boot Completed | Auto-start after reboot |

---

## Input modes

### Path A — Bluetooth HID (best cursor)
The Redmi appears as a real Bluetooth keyboard + mouse to the OnePlus. A true system cursor appears. No companion app required for input on the tablet (status app still useful).

### Path B — Local Bridge + Accessibility (default)
UDP packets over Wi-Fi. The receiver injects taps and gestures via Accessibility Service. No real hardware cursor — the app is honest about this.

Both modes share the same input capture layer. Switch between them in Settings.

---

## Building APKs

### Via GitHub Actions (recommended)

Push to any branch. APKs appear under the workflow run as downloadable artifacts.

### Locally

```bash
# Debug APKs
./gradlew :app-bridge:assembleDebug :app-receiver:assembleDebug

# APK locations
app-bridge/build/outputs/apk/debug/app-bridge-debug.apk
app-receiver/build/outputs/apk/debug/app-receiver-debug.apk
```

JDK 17 required. See [BUILD.md](BUILD.md) for release signing setup.

---

## Troubleshooting

**USB device not detected**
- Enable OTG in your phone settings (Settings → Additional Settings → OTG)
- Try a different OTG adapter
- Unplug and replug the receiver
- The device must appear as a HID class device (class 3)

**Bridge keeps getting killed**
- Battery optimization exemption is required — grant it in Permissions screen
- On MIUI: Settings → Apps → Manage Apps → InputBridge → Autostart → Enable
- On MIUI: Battery → App Battery Saver → InputBridge → No Restrictions

**High latency**
- Use the same Wi-Fi network or a phone hotspot
- Wi-Fi Direct gives the lowest latency (no router hop)
- Keep devices within 5 metres

**Accessibility service keeps disabling**
- Some ROMs kill accessibility services aggressively
- Grant battery optimization exemption to the receiver app too
- Some devices require a separate "protected app" setting

**Can't click in the lock screen**
- Expected — Android blocks accessibility injection into secure windows
- Unlock the tablet first, then use remote control

---

## Architecture

See [AI_CONTEXT.md](AI_CONTEXT.md) for a full architecture overview.

```
android-input-bridge/
├── app-bridge/              # Bridge app (runs on Redmi 9)
├── app-receiver/            # Receiver app (runs on OnePlus Pad Go)
├── shared-core/             # Shared models, config, logging
├── protocol/                # Binary packet protocol + serialization
├── input-capture/           # USB HID input reading
├── transport-wifi/          # UDP and Wi-Fi Direct transport
├── transport-bluetooth-hid/ # BT HID output (Path A)
├── accessibility-receiver/  # Accessibility service + gesture injection
├── diagnostics/             # Diagnostics aggregation
└── build-logic/             # Gradle convention plugins
```

---

## Development phases

| Phase | Description | Status |
|---|---|---|
| 1 | Project structure, build system, UI shells | ✅ Done |
| 2 | USB input capture | 🔲 Next |
| 3 | Local transport protocol + pairing | 🔲 |
| 4 | Accessibility receiver | 🔲 |
| 5 | Reconnect logic, latency optimisations | 🔲 |
| 6 | Bluetooth HID mode | 🔲 |
| 7 | Polish: black screen, settings, macros | 🔲 |

---

## License

Private project. All rights reserved.
