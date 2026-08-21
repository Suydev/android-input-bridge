---
name: Android 10 USB + crash hardening
description: Manifest USB_PERMISSION required for openDevice on API29; crash-safe patterns for merged single-APK
---

# Android 10 USB access + crash hardening (merged single-APK)

**Rule:** On Android 10 (API 29) `UsbManager.openDevice()` returns null after `requestPermission()` unless the app declares `<uses-permission android:name="android.permission.USB_PERMISSION" />` in the manifest. `claimInterface(iface, true)` (force) is also required to take HID devices from the system driver. Without the manifest permission the bridge loops on "USB device not found".
**Why:** The user confirmed this from the Android 10 docs — permission dialog appears but `openDevice()` stays null without the manifest declaration.
**How to apply:** Always keep `android.permission.USB_PERMISSION` in the merged manifest; keep force-claim; and apply the crash-safe patterns from BUG-157: wrap `AutoDiscovery.bind(54322)` in try/catch, make `usbManager` nullable (degrade instead of throwing in onCreate), wrap both services' `startForeground` in try/catch (API33+ POST_NOTIFICATIONS denial must not kill the process), wrap receiver packet parsing, guard TrackpadScreen divides by a 0-size check, and use `as?` for all `context/Service` casts in Compose.
