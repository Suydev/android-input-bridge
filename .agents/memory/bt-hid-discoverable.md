---
name: Bluetooth HID works on Redmi 9 — must be discoverable
description: BT HID Device profile works on the Redmi 9; our transport failed because it never entered discoverable mode
---

# Bluetooth HID works on the Redmi 9 (contrary to earlier assumption)

**Rule:** For BT HID Device mode to actually work, after `registerApp()` succeeds the phone MUST
be put into DISCOVERABLE mode (inquiry scan) so the host (tablet/PC) can find and pair it.
**Why:** Earlier sessions assumed Xiaomi/Redmi lacks the HID Device role (from a web compatibility
list). Decompiling "Bluetooth Keyboard Mouse" (a working app) proved classic `BluetoothHidDevice`
works on the user's device — it explicitly calls `REQUEST_DISCOVERABLE` (300s) right after
registration. Our `BluetoothHidTransport` never requested discoverable mode, so the host could
never see/pair the phone → "bt doesn't work". Correct the old assumption.
**How to apply:** When touching BT HID, always call `startActivity(ACTION_REQUEST_DISCOVERABLE)`
from the foreground service (FLAG_ACTIVITY_NEW_TASK) after `onAppStatusChanged(registered=true)`.
The host still must be paired once in Bluetooth settings. Don't trust OEM-compatibility lists
over a working reference APK on the same hardware.
