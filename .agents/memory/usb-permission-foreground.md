---
name: USB host permission foreground activity
description: On Android 10/MIUI, request UsbManager permission from a foreground Activity, not a background Service
---

# USB Host Permission Must Come From a Foreground Activity

**Rule:** Call `UsbManager.requestPermission()` from a foreground `Activity`; requesting it from a
background `Service` (even a foreground service) silently drops the dialog on Android 10 / MIUI.
**Why:** Redmi 9 (API 29) drops the USB-permission system dialog when no foreground Activity is
present, so `hasPermission()` stays false and `openDevice()` returns null — the app never gets
access to the connected USB keyboard/mouse (BTmouse/jdx USB-host projects hit the same wall).
**How to apply:** `MainActivity` requests permission and only starts `BridgeService` after the
`ACTION_USB_PERMISSION` broadcast reports `EXTRA_PERMISSION_GRANTED`. The `PendingIntent` needs
`FLAG_MUTABLE` (API 31+) / flag 0 (API < 31) so the system can write the granted extra into it.
