---
name: Single-APK role locking + service survival
description: Merged APK needs persisted role; notifications must open merged activities; PONG-only connected; injection gate must include Shizuku
---

# Single-APK role locking + service survival

**Rule:** One role per device (persisted via `AppRoleStore`), notification PendingIntents resolved to merged activities by class name, `transportConnected` only on PONG, injection gate must be `isInjectionAvailable()` not `isServiceConnected()`.
**Why:** The merge removed the package-as-role distinction. Both services in one process race discovery port 54322 and the bridge self-discovers its own receiver over loopback ("connected but nothing works"). Old `MainActivity`-targeting PendingIntents are unresolvable in the merged manifest (startForeground risk on API 34). Shizuku is the primary injection path and needs no accessibility service, so a service-connected-only gate dropped every packet.
**How to apply:** Always read/write role through `AppRoleStore` (prefs `app_role`); new activities/services must stop the opposite role's service; use `setClassName(this, "com.inputbridge.ui.<role>.XModeActivity")` for PendingIntents from library code; gate injection on a11y-OR-Shizuku; USB poll re-enters capture on grant; no `.debug` applicationIdSuffix (state must carry between debug and release installs).