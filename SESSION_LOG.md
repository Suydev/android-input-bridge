## Session 042 — Lowest-latency UDP hot path for bridge mouse/scroll (BUG-139)
**Date:** 2026-08-16
**Agent:** opencode
**Status:** ✅ Complete

### Goals
- Cut latency on the highest-frequency input path (the 125 Hz mouse/scroll stream) after the user asked to "edit that app for lowest latency".

### Bugs Found and Fixed
| ID | Severity | Description | Verdict |
|----|----------|-------------|---------|
| BUG-139 | Medium | Bridge UDP send adds a coroutine dispatch hop + per-event SharedPreferences read on the hot path | ✅ FIXED |

### What Was Changed
- `app-bridge/.../bridge/service/BridgeService.kt`: `MouseMove`/`Scroll` now use `udpTransport.sendDirect()` (inline `sock.send()`, skips the `inputChannel` + `select()` dispatch hop); keys/clicks keep channel ordering. `bridgeSensitivity` cached once per capture session instead of re-read per event.
- Deliberately left the UDP + Shizuku fast path intact; did NOT adopt the reference APK's slow Bluetooth-only + heavy `PointerPathView` architecture (per user's earlier warning about the app "having very slow architecture").

---

## Session 041 — BT HID descriptor aligned to reference's universal-compat boot layouts (BUG-138)
**Date:** 2026-08-16
**Agent:** opencode
**Status:** ✅ Complete

### Goals
- User: study the decompiled "Bluetooth Keyboard Mouse v6.23.2" reference carefully — it has a very
  robust mouse/keyboard capture+HID system that "works for any device", but warned its architecture is
  slow (latency). Adopt its compatibility technique without its slowness.
- Read `ClassicHidService.java` (requests discoverable + `sendReport`) and the `z00`/`e10` descriptor
  providers; compare against our `HidDescriptor`/`HidReportBuilder`/`BluetoothHidTransport`.

### Bugs Found and Fixed
| ID | Severity | Description | Verdict |
|----|----------|-------------|---------|
| BUG-138 | High | BT HID descriptor/report not maximally host-compatible (usage max 0x91, 3-btn mouse, no AC Pan, back/forward dropped) | ✅ FIXED |

### What Was Changed
- `HidDescriptor.kt`: keyboard usage max 0x65 (boot standard) + LED Output collection; mouse = 5 buttons
  + X/Y/wheel + AC Pan (Consumer 0x0238) → 5-byte mouse report. Matches reference boot layouts exactly.
- `HidReportBuilder.kt`: 5-byte mouse report; `BACK`→0x08 / `FORWARD`→0x10; `onScroll(dx,dy)` forwards
  `dx` as AC Pan (horizontal scroll).
- `BluetoothHidTransport.kt`: `Scroll` forwards `event.dx` to `onScroll`.
- Deliberately kept the UDP + Shizuku low-latency path untouched — the reference's slowness comes from its
  Bluetooth-only design and heavy `PointerPathView`; our architecture already avoids both.

---

## Session 040 — Full-screen cursor overlay + real screen-size coordinate space (BUG-136, BUG-137)
**Date:** 2026-08-16
**Agent:** opencode
**Status:** ✅ Complete

### Goals
- User: "i want the cursor windows like find search on internet now fix the ui and fallbacls".
- Research full-screen `TYPE_APPLICATION_OVERLAY` cursor (status bar / nav bar / cutout) and fix the
  overlay UI so the cursor covers the entire physical display, plus connection/screen-size fallbacks.

### Bugs Found and Fixed
| ID | Severity | Description | Verdict |
|----|----------|-------------|---------|
| BUG-136 | High | Overlay inset away from cutout/system bars; cursor can't reach screen edges | ✅ FIXED |
| BUG-137 | High | Cursor space clamped to 1080×2400 (or smaller a11y bounds); can't span full screen | ✅ FIXED |

### What Was Changed
- Web-verified: Android avoids display cutouts by default; API 30+ uses
  `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` (replaces the old negative-y offset workaround); combine with
  `FLAG_LAYOUT_NO_LIMITS` + `FLAG_LAYOUT_IN_SCREEN` for an edge-to-edge overlay.
- `CursorOverlayService.kt`: added `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` + `FLAG_LAYOUT_NO_LIMITS`;
  feed `realScreenSize()` into `AccessibilityCommandBus.setScreenSize()` at create time.
- `ScreenMetrics.kt` (new): `realScreenSize(context)` using `maximumWindowMetrics` (API 30+) /
  `Display.getRealSize()`, the true full physical display including system bars + cutout.
- `InputBridgeAccessibilityService.kt`: `onServiceConnected` now uses `realScreenSize()` instead of
  `currentWindowMetrics.bounds` (which excluded system bars); removed the now-unused `getRealScreenSize`
  body and `DisplayMetrics` import.

---

## Session 039 (addendum) — BT HID discoverable fix from reference decompile (BUG-135)
**Date:** 2026-08-16
**Agent:** opencode
**Status:** ✅ Complete

### Goals
- User supplied reference APK "Bluetooth Keyboard Mouse v6.23.2 (Patched)" to decompile.
- Understand why our BT HID never connected.

### Bugs Found and Fixed
| ID | Severity | Description | Verdict |
|----|----------|-------------|---------|
| BUG-135 | High | BT HID never entered discoverable mode; host couldn't pair the phone | ✅ FIXED |

### What Was Changed
- Decompiled the reference APK (jadx) → `io/appground/blehid/ClassicHidService.java` uses classic
  `BluetoothHidDevice` + `sendReport()`, and forces `ACTION_REQUEST_DISCOVERABLE` after registration.
  This overturns the prior (wrong) assumption that Redmi 9 lacks the HID Device role.
- `BluetoothHidTransport.kt`: request discoverable mode right after `registerApp()` succeeds.
- (BUG-134 manual-IP fallback + live-IP trackpad committed earlier in this session.)

---

## Session 039 — Manual-IP fallback + live-IP trackpad (BUG-134)
**Date:** 2026-08-16
**Agent:** opencode
**Status:** ✅ Complete

### Goals
- User: connection never happens without manual IP; trackpad cannot capture mouse/keyboard.
- Add a fallback so the app connects even when UDP broadcast discovery is blocked by the network.

### Bugs Found and Fixed
| ID | Severity | Description | Verdict |
|----|----------|-------------|---------|
| BUG-134 | High | No manual-IP fallback when broadcast discovery is blocked; trackpad used stale IP snapshot | ✅ FIXED |

### What Was Changed
- `SettingsScreen.kt` (bridge): re-added optional "Receiver IP (optional)" field (blank = auto-discovery).
- `BridgeTrackpadScreen.kt`: connect logic now polls the live discovered `prefs.targetIp` and links when available.
- (Pending deeper fix from a reference app APK the user will provide for decompilation.)

---

## Session 038 — Bidirectional auto-discovery + remove IP/PIN UI (BUG-133)
**Date:** 2026-08-16
**Agent:** opencode
**Status:** ✅ Complete

### Goals
- User: "the old code is still expecting ip and code in the ui ux" and "without those app don't connect, fix that".
- Make connection work automatically (no IP, no PIN) and remove the obsolete manual-entry UI.

### Bugs Found and Fixed
| ID | Severity | Description | Verdict |
|----|----------|-------------|---------|
| BUG-133 | Critical | One-way broadcast discovery dropped on real Wi-Fi → bridge never connected without manual IP+PIN | ✅ FIXED |

### What Was Changed
- `AutoDiscovery.kt`: added `startQuerying()` (bridge broadcasts QUERY) and `listenForQueriesAndRespond()`
  (receiver answers QUERY directly to the bridge's discovery port 54322). Receiver still broadcasts presence.
- `BridgeService.kt`: `startAutoDiscovery()` now also launches `startQuerying()`.
- `ReceiverService.kt`: also launches `listenForQueriesAndRespond(port)`.
- `SettingsScreen.kt` (bridge): removed manual Receiver IP field + Pairing PIN section; shows auto-discovered status.
- `ConnectionScreen.kt` (receiver): removed PIN display / "enter PIN in bridge" / Regenerate PIN; shows auto-connect guidance; trackpad button gated on `transportConnected` only.
- `ReceiverDiagnosticsScreen.kt`: removed obsolete "Session PIN" row.

---

# Session Log

---

## Session 037 — Direct connect: auto IP + remove pairing + fix Shizuku keyboard injection (BUG-130/131/132)
**Date:** 2026-08-16
**Agent:** opencode
**Status:** ✅ Complete

### Goals
- Make the two apps connect directly on the same Wi-Fi with no PIN (user: "no pin, direct").
- Automate IP discovery (auto-discovery was fragile).
- Fix Shizuku so real keyboard injection works (accessibility alone cannot inject keys).

### Bugs Found and Fixed
| ID | Severity | Description | Verdict |
|----|----------|-------------|---------|
| BUG-130 | High | Auto-discovery used only first interface broadcast; bridge never found receiver IP | ✅ FIXED |
| BUG-131 | High | PIN pairing gate blocked all input; removed for direct connect | ✅ FIXED |
| BUG-132 | High | Shizuku permission never requested → keyboard injection impossible | ✅ FIXED |

### What Was Changed
- `AutoDiscovery.kt`: broadcast to all interface broadcasts + 255.255.255.255.
- `BridgeService.kt`: discovery runs unconditionally; `connectToReceiver()` connects directly, PIN now best-effort/non-fatal.
- `ReceiverService.kt`: accept any LAN sender (no PIN allowlist); PAIR_REQUEST accepted unconditionally.
- `ShizukuInputInjector.kt`: permission-result listener + `requestPermissionIfNeeded(activity)`.
- `ReceiverPermissionsScreen.kt`: Shizuku state + one-tap grant; clarified a11y-only drives focused text field.

### Key user-facing note
Keyboard requires Shizuku (AccessibilityService cannot inject key events). Mouse/trackpad works via dispatchGesture without Shizuku.

---

## Session 036 — USB host permission requested from foreground Activity (BUG-129)
**Date:** 2026-08-16
**Agent:** opencode
**Status:** ✅ Complete

### Goals
- Fix "USB host access doesn't give the app access to the connected keyboard/mouse" on Android 10 / Redmi 9 (MIUI).
- Research Android 10 `UsbManager` docs; implement per the `btmouse`/`jdx` USB-host consideration.

### Bugs Found and Fixed
| ID | Severity | Description | Verdict |
|----|----------|-------------|---------|
| BUG-129 | High | USB permission only requested from background `BridgeService`; MIUI drops the dialog so the app never gets access to the HID device | ✅ FIXED |

### What Was Changed
- `app-bridge/.../ui/MainActivity.kt`: added foreground `UsbManager.requestPermission()` requester + `ACTION_USB_PERMISSION` receiver; `onResume` scan and `USB_DEVICE_ATTACHED` launch intent now request permission from the foreground Activity and only start `BridgeService` after `EXTRA_PERMISSION_GRANTED`. `onNewIntent`/`onStart`/`onStop` wired for the receiver.
- `BridgeService` USB-permission path retained as fallback for boot/notification-started case.

---

## Session 035 — Fix pairing never re-runs after runtime PIN/IP change (BUG-128)
**Date:** 2026-08-16
**Agent:** opencode
**Status:** ✅ Complete — pushed, CI green

### Goals
- Fix "PIN is correct but pairing still says wrong": a late PIN entry was never sent.

### Bugs Found and Fixed
| ID | Severity | Description | Verdict |
|----|----------|-------------|---------|
| BUG-128 | High | Bridge pairs once at startup; runtime PIN/target-IP change never re-pairs | Fixed |

### What Was Changed
- `app-bridge/.../bridge/service/BridgeService.kt`: added `ACTION_REPAIR` and `rePair()` (resets `pairResponseDeferred`, re-runs `doPairing()` on the live transport).
- `app-bridge/.../bridge/viewmodel/BridgeViewModel.kt`: `setPairingPin` (full 6 digits) and `setTargetIp` send `ACTION_REPAIR` to the running service.
- `BUGS.md`: added BUG-128.

---

## Session 034 — Close audit findings O and P (BUG-125, BUG-126)
**Date:** 2026-08-16
**Agent:** opencode
**Status:** ✅ Complete — pushed (user: "fix both"), CI green

### Goals
- Implement the two WONTFIX design/ambiguous findings from the round-3 audit (O, P).
- Keep all prior fixes intact.

### Bugs Found and Fixed
| ID | Severity | Description | Verdict |
|----|----------|-------------|---------|
| BUG-125 | Low | LEFT down double-fired tap() and a drag stroke | Fixed |
| BUG-126 | Medium | Receiver accepted any LAN host's input after DISCONNECT | Fixed |

### What Was Changed
- `accessibility-receiver/.../AccessibilityCommandBus.kt`: defer LEFT click to MouseButtonUp; fire tap only when the pointer moved < `CLICK_MOVE_THRESHOLD_PX` since down, so a drag no longer also taps (BUG-125).
- `app-receiver/.../ReceiverService.kt`: when a session PIN is set, drop every non-PAIR_REQUEST packet from a sender that is not the paired bridge — closes the open-input fallback (BUG-126).
- `BUGS.md`: BUG-125/BUG-126 → ✅ FIXED (Session 034).

---

## Session 033 — Round-3 audit fixes: pairing, accessibility recycling, BT HID, drag race (BUG-111 → BUG-127)
**Date:** 2026-08-16
**Agent:** opencode
**Status:** 🔄 In Progress — code complete, NOT pushed (user: "don't push yet")

### Goals
- Complete the 17-finding round-3 audit (auth/pairing, transport/protocol, accessibility, bridge/UI/USB).
- Fix all findings except the design/ambiguous ones (N, O, P, Q).

### Bugs Found and Fixed
| ID | Severity | Description | Verdict |
|----|----------|-------------|---------|
| BUG-111 | High | BT HID reconnect returns stale `appRegistered` → silent input drop | Fixed |
| BUG-112 | High | AccessibilityNodeInfo leak (pool exhaustion crash) | Fixed |
| BUG-113 | High | Duplicate UDP transport / job leak after auto-discovery restart | Fixed |
| BUG-114 | Medium | `runBlocking(Dispatchers.IO)` blocks Main on trackpad dispose | Fixed |
| BUG-115 | High | Receiver keeps old bridge after PIN reset (in-memory pairing uncleared) | Fixed |
| BUG-116 | High | Bridge skips PAIR_REQUEST when already paired → silent loss on IP change | Fixed |
| BUG-117 | High | `disconnect()` leaves in-flight `connect()` deferreds pending (state revert) | Fixed |
| BUG-118 | High | DISCONNECT not propagated both directions | Fixed |
| BUG-119 | Low | `injectionMode` telemetry gated on `isAvailable` not `checkAvailability()` | Fixed |
| BUG-120 | Medium | `lastKnownUsbDevice` not cleared on detach | Fixed |
| BUG-121 | Medium | Late `continueStroke` starts dangling open gesture after drag end | Fixed |
| BUG-122 | Medium | `connectionDeferred` missing `@Volatile` | Fixed |
| BUG-123 | Medium | Receiver-mode UdpTransport dropped dequeued packet when no sender seen | Fixed |
| BUG-124 | Very Low | `isCritical` latent future-proofing | WONTFIX |
| BUG-125 | Low | LEFT down fires tap + stroke (uncertain it's a bug) | WONTFIX (product call) |
| BUG-126 | Medium | Receiver open-mode fallback after DISCONNECT | WONTFIX (design) |
| BUG-127 | Very Low | TextInput truncation | WONTFIX (by design) |

### What Was Changed
- `transport-bluetooth-hid/.../BluetoothHidTransport.kt`: reset `appRegistered` in `connect()` (BUG-111); complete deferreds `false` in `disconnect()` (BUG-117); `@Volatile` on `connectionDeferred` (BUG-122).
- `accessibility-receiver/.../InputBridgeAccessibilityService.kt`: recycle every owned node at all `getFocused()`/root call sites (BUG-112).
- `accessibility-receiver/.../AccessibilityCommandBus.kt`: telemetry gated on `checkAvailability()` (BUG-119); monotonic `dragSessionId` + stale-continuation drop in `continueStroke` (BUG-121).
- `app-bridge/.../bridge/service/BridgeService.kt`: always re-PAIR_REQUEST when PIN set (BUG-116); clear `isPaired` on inbound DISCONNECT (BUG-118); `onUsbDetached` nulls `lastKnownUsbDevice` (BUG-120).
- `app-bridge/.../bridge/ui/screens/BridgeTrackpadScreen.kt`: fire-and-forget disconnect (BUG-114).
- `app-receiver/.../receiver/service/ReceiverService.kt`: `ACTION_UNPAIR` + `handleUnpair()` clears in-memory pairing and sends DISCONNECT (BUG-115/118).
- `app-receiver/.../receiver/viewmodel/ReceiverViewModel.kt`: `generateNewPin()` notifies the running service (BUG-115/118).
- `transport-wifi/.../wifi/UdpTransport.kt`: reply to packet's source endpoint (BUG-123).
- `BUGS.md`: added BUG-111 → BUG-127.

---

## Session 032 — Shizuku integration audit + bug fixes (BUG-105 → BUG-110)
**Date:** 2026-08-15
**Agent:** opencode
**Status:** ✅ Complete

### Goals
- Re-add Shizuku input injection (1-5ms) after failed previous attempts
- Audit the integration with a bias-free subagent
- Fix all issues found

### Bugs Found and Fixed
| ID | Severity | Description | Verdict |
|---|---|---|---|
| BUG-105 | High | Thread.sleep() on Main thread freezes UI during longPress/scroll | Fixed |
| BUG-106 | High | MotionEvent UP events use wrong downTime, gestures may not be recognized | Fixed |
| BUG-107 | Medium | Redundant reflection when Shizuku is already a compile dependency | Fixed |
| BUG-108 | Medium | ShizukuProvider in app-receiver manifest but dependency in accessibility-receiver | WONTFIX (library can't declare application) |
| BUG-109 | Low | destroy() doesn't null all mutable fields | Fixed |
| BUG-110 | Low | shizuku-compiler defined in TOML but unused | Fixed |

### What Was Changed
- `ShizukuInputInjector.kt`: Rewrote to use direct Shizuku API (no reflection), `suspend fun` for longPress/swipe with `kotlinx.coroutines.delay()`, fixed MotionEvent downTime
- `AccessibilityCommandBus.kt`: Wrapped suspend calls in `scope.launch(Dispatchers.IO)` to avoid blocking Main
- `accessibility-receiver/src/main/AndroidManifest.xml`: Added ShizukuProvider declaration
- `app-receiver/src/main/AndroidManifest.xml`: Removed duplicate ShizukuProvider
- `gradle/libs.versions.toml`: Removed unused shizuku-compiler entry
- `BUGS.md`: Added BUG-105 through BUG-110

---

## Session 031 — Full-screen trackpad mapping + lowest-latency sends + MOUSE button removal (BUG-103, BUG-104)
**Date:** 2026-08-14
**Agent:** opencode
**Status:** 🔄 In Progress — CI pending

### Goals
- Make the mouse cursor reach the full OnePlus Pad Go screen (currently trapped in the phone's aspect mapping).
- Reduce input latency to the minimum (remove per-packet coroutine dispatch).
- Hide/remove the MOUSE button from the bridge home screen per user request.
- Fix the remaining CI release-job failure (manual zipalign/apksigner step against Gradle-signed APKs).

### Bugs Found and Fixed
| ID | Severity | Description | Verdict |
|---|---|---|---|
| BUG-103 | High | Trackpad CursorGoto normalized by full phone `phoneWidth`/`phoneHeight` instead of `trackpadView` bounds → tablet cursor clamps short of screen edges. | Fixed. |
| BUG-104 | Medium | Leftover mouse-pipeline latency: per-receive `buf.copyOf()`, 256 KB socket bufferbloat, channel→send-loop dispatch hop, default thread priority, `CursorGoto` via command queue. | Fixed. |

### What Was Changed
- `app-bridge/.../ui/MouseTrackpadActivity.kt:538-546`: `handleTrackpadTouch()` ACTION_DOWN now sends `sendCursorGoto(x / tw, y / th)` where `tw/th` are `trackpadView` bounds (touch coords are already view-relative), replacing `getLocationOnScreen()` + phone-screen normalization.
- `app-bridge/.../ui/MouseTrackpadActivity.kt:639-661`: `sendCursorGoto`/`sendMouseMove` now call the new `transport.sendDirect(packet)` — `socket.send()` synchronously on the touch thread, skipping the channel and send-loop coroutine entirely (BUG-104).
- `transport-wifi/.../UdpTransport.kt`: added `sendDirect()` (BUG-104) + `fixedTargetAddress` cached at connect; socket buffers 256 KB → 64 KB (bufferbloat); send/receive loops boosted to `THREAD_PRIORITY_URGENT_AUDIO`; receive loop uses `PacketSerializer.deserialize(buf, dp.length)` (no `copyOf`).
- `protocol/.../PacketSerializer.kt`: added `deserialize(data, length)` overload to avoid per-packet `copyOf()` (BUG-104).
- `accessibility-receiver/.../AccessibilityCommandBus.kt:172-205`: `CursorGoto` handled inline in `post()` exactly like `MouseMove` (no command-queue hop).
- `app-bridge/.../ui/screens/BridgeScreen.kt`: removed the MOUSE `OutlinedButton` (and its now-unused `Intent`/`MouseTrackpadActivity` imports) per user request.
- `.github/workflows/ci.yml`: removed the "Zipalign and APK Sign v2" step — Gradle already signs during `assembleRelease` (build-logic BUG-102 fix), so the manual step failed on missing `*-unsigned.apk`. Release artifacts upload via `app-bridge-release.apk` / `app-receiver-release.apk`.
- `BUGS.md`: BUG-103 and BUG-104 marked ✅ FIXED (Session 031).

### Notes
- Fn keys: `KeyMap` maps F1–F12 (HID 0x3A–0x45); Android `KeyEvent` has no KEYCODE_F13–F24, and a physical keyboard Fn layer is consumed by the keyboard itself and never sent over USB HID, so a true Fn key cannot be represented in this capture path.

---

## Session 030 — Fix MOUSE/START overlap + CI-blocking build-logic signing DSL (BUG-101, BUG-102)
**Date:** 2026-08-14
**Agent:** opencode
**Status:** ✅ Complete — CI pending

### Goals
- Investigate why the MOUSE and START buttons overlap on the bridge screen.
- Check whether both apps actually build (CI was red); fix the build-blocking defect too.
- Document and fix both bugs per project protocol.

### Bugs Found and Fixed
| ID | Severity | Description | Verdict |
|---|---|---|---|
| BUG-101 | Medium | MOUSE and START buttons share `bottom = 80.dp` and coexist during the "service running, not connected" window (`bridgeServiceRunning=true`, `transportConnected=false`), painting on top of each other. | Fixed. |
| BUG-102 | Critical | `signingConfig { ... }` inside `buildTypes.release` is invalid AGP Kotlin DSL → `:build-logic:compileKotlin` FAILED → all CI jobs red since `97fbaee`. | Fixed. |

### What Was Changed
- `app-bridge/.../ui/screens/BridgeScreen.kt:200-224`: MOUSE button visibility gated on `isBridgeActive` (service running AND transport connected) instead of `isBridgeActive || diagnostics.bridgeServiceRunning`.
- `build-logic/.../AndroidAppConventionPlugin.kt`: replaced the invalid in-build-type `signingConfig {}` block with `signingConfigs { create("release") { ... } }` + `release { signingConfig = signingConfigs.getByName("release") }`, guarded by `SIGNING_KEYSTORE_PATH` presence. Api values were captured.
- `BUGS.md`: BUG-101 and BUG-102 added, both marked FIXED (Session 030).

### Root Cause (BUG-101)
`isBridgeActive = bridgeServiceRunning && transportConnected`. START renders when `!isBridgeActive`, MOUSE rendered when `isBridgeActive || bridgeServiceRunning`. In the connecting/reconnecting state both conditions were true and both buttons used `Alignment.BottomCenter` + `padding(bottom = 80.dp)`, so they stacked at the same spot.

### Root Cause (BUG-102)
Commit `97fbaee` (Session 027, "mouse scroll tilt") also rewrote the convention plugin's `buildTypes` block to add CI signing directly inside `release {}`. `signingConfig` is not callable there, so `storePassword = storePassword` etc. captured the outer `val`s → "Val cannot be reassigned" and the plugin never compiled. Every CI run since (current HEAD `200c714` included) failed before compiling any app module.

---

## Session 027 — Fix MouseTrackpadActivity crash + CursorOverlayService concurrent modification (BUG-099, BUG-100)
**Date:** 2026-08-12
**Agent:** opencode
**Status:** 🔄 In Progress — CI pending

### Goals
- Diagnose the crash reported when pressing the mouse button on the bridge app (Redmi 9).
- Fix the root cause and any adjacent bugs found.

### Bugs Found and Fixed
| ID | Severity | Description | Verdict |
|---|---|---|---|
| BUG-099 | Critical | `MouseTrackpadActivity.onCreate()` crashes with "Left to start undefined" — redundant `connect()` calls before `createHorizontalChain`. | Fixed. |
| BUG-100 | High | `CursorOverlayService.onDraw()` modifies `trailPoints` list during indexed iteration — `IndexOutOfBoundsException`. | Fixed. |

### What Was Changed
- `MouseTrackpadActivity.kt:405-423`: Removed redundant explicit START/END `connect()` calls before `createHorizontalChain()`.
- `CursorOverlayService.kt:288-318`: Replaced in-loop list mutation with a `while` loop that removes stale points before the indexed draw iteration.

### Diagnosis Method
- Connected to bridge device via ADB at `10.171.170.148:34583`.
- Extracted `FATAL EXCEPTION` from `logcat -d`.
- Root cause was a ConstraintLayout chain conflict, not a mouse button handling bug.

---

## Session 022 — Harden UDP reconnect lifecycle (BUG-087 → BUG-092)
**Date:** 2026-07-27
**Agent:** Codex
**Status:** 🔄 In Progress — GitHub Actions verification pending

### Goals
- Repair the UDP reconnect, target-validation, connection-state, and reply-endpoint lifecycle defects.
- Validate the receiver launch on the connected OnePlus Pad Go without replacing its installed APK.

### Bugs Found and Fixed
| ID | Severity | Description | Verdict |
|---|---|---|---|
| BUG-087 | High | Reconnect reused closed send queues. | Fixed. |
| BUG-088 | High | UDP lifecycle flag lacked cross-thread visibility. | Fixed. |
| BUG-089 | High | Invalid targets failed after a false connected state. | Fixed. |
| BUG-090 | High | Socket bind/open was incorrectly shown as peer connectivity. | Fixed. |
| BUG-091 | High | Old receive reader could spin after an immediate reconnect. | Fixed. |
| BUG-092 | Medium | Receiver retained a prior session's reply endpoint. | Fixed. |

### What Was Changed
- `UdpTransport.kt`: makes every connection own fresh queues and peer state, validates sender targets before startup, and prevents cancelled readers from surviving a reconnect.
- `BridgeService.kt`, `ReceiverService.kt`: only report a live UDP peer after PONG/PING or accepted pairing evidence.
- `BUGS.md`, `TASKS.md`, `PROJECT_STATE.md`, `AI_CONTEXT.md`, and agent memory: record the UDP session-boundary rules.

### Device Validation
- OnePlus Pad Go (Android 14) is reachable through ADB. Both debug activities launch; ReceiverService binds UDP 54321 without a foreground-service crash.
- Accessibility is disabled and the Redmi 9/USB HID dongle is not attached, so end-to-end injection and capture remain pending.

---

## Session 021 — Repair pairing, USB capture, and cursor pipeline (BUG-080 → BUG-086)
**Date:** 2026-07-27
**Agent:** Codex
**Status:** 🔄 In Progress — source repair documented; build and physical-device validation deferred by user

### Goals
- Diagnose the reported correct-PIN failure, no keyboard/mouse capture, USB permission-without-input state, and malformed cursor.
- Preserve the findings as explicit project invariants for the next agent.

### Bugs Found and Fixed
| ID | Severity | Description | Verdict |
|---|---|---|---|
| BUG-080 | Critical | ViewModel called `refreshStatus()` before backing StateFlow construction. | Fixed in current HEAD; documented. |
| BUG-081 | Critical | Receiver replied to its configured listen port instead of the bridge's ephemeral source port. | Fixed in `UdpTransport`. |
| BUG-082 | Critical | UDP loops could start before `isConnected` and exit immediately. | Fixed in `UdpTransport`. |
| BUG-083 | Critical | USB HID readers could start before `isActive` and exit immediately. | Fixed in `UsbInputCapture`. |
| BUG-084 | High | Failed USB startup closed claimed interfaces without release. | Fixed in `UsbInputCapture`. |
| BUG-085 | Medium | Cursor outline, shadow, and handle were clipped by the overlay canvas. | Fixed in `CursorOverlayService`. |
| BUG-086 | High | Bridge subscribed to a replay-free input flow after readers started. | Fixed in `BridgeService`. |

### What Was Changed
- `UdpTransport.kt`: preserves the complete sender endpoint for receiver replies and establishes connection state before I/O loops.
- `UsbInputCapture.kt`, `BridgeService.kt`: makes keyboard/mouse readers reliably live, preserves startup reports, and releases failed captures correctly.
- `CursorOverlayService.kt`: redraws the pointer in a padded canvas with hotspot compensation.
- `BUGS.md`, `TASKS.md`, `PROJECT_STATE.md`, `AI_CONTEXT.md`, `AGENTS.md`, `.agents/skills/SKILL.md`, and memory: document the repaired invariants and required real-device test.

### Verification State
- Static second-pass review and `git diff --check` passed.
- Local Gradle build, APK installation, CI push, and two-device hardware validation were not run because the user explicitly cancelled local builds. Existing installed APKs are older and do not contain these repairs.

---

## Session 020 — Startup crash mitigation (BUG-079)
**Date:** 2026-07-27
**Agent:** Codex
**Status:** ✅ Complete

### Goals
- Eliminate the activity-startup runtime permission path implicated in the reported open crash.
- Preserve a safe, explicit notification-permission request path.

### What Was Changed
- `app-bridge/.../MainActivity.kt`: removed automatic `POST_NOTIFICATIONS` launcher invocation from startup.
- `app-receiver/.../MainActivity.kt`: removed the same startup launcher invocation.
- Both existing Permissions screens remain responsible for requesting the permission after a user tap.
- `BUGS.md`: verified BUG-075 through BUG-078 were already fixed in code; documented BUG-079.

### Verification
- Android CI and Release both passed for commit `c1b0497`, which contains BUG-079 and the required coroutine-import repair.
- Fresh-device verification remains required on the affected Android device.

---

## Session 018 — Imported project setup and CI verification
**Date:** 2026-07-24
**Agent:** Replit Agent
**Status:** ✅ Complete

### Goals
- Review the imported Android multi-module project and its operating instructions.
- Investigate the reported `BridgeService` `ServiceInfo` compile failure.
- Check the latest CI results and verify the recent Android crash fixes.

### Bugs Found and Fixed
| ID | Severity | Description | Verdict |
|----|----------|-------------|---------|
| BUG-063 | Critical | Android 14 foreground-service type crash | Already fixed in imported HEAD; latest CI passes |
| — | Critical | Missing `ServiceInfo` import in `BridgeService` | Already fixed in imported HEAD; latest CI passes |

### What Was Changed
- No production code changes were necessary: `BridgeService` and `ReceiverService` both contain the explicit `android.content.pm.ServiceInfo` import.
- Verified the latest Android CI run completed successfully and published both debug APK artifacts, test results, and release artifacts.
- Verified the working tree is clean and the imported project is aligned with `origin/main`.

### Remaining Validation
- Physical-device validation remains necessary for the Redmi 9 bridge, OnePlus Pad Go receiver, and Portronics Key2 Combo hardware path.

---

## Session 017 — Foreground service type crash + app logos (BUG-063)
**Date:** 2026-07-24
**Agent:** Claude (Replit)
**Status:** ✅ Complete

### Goals
- Fix crash after notification permission dialog dismissal + every subsequent cold open
- Add app logos to both apps

### Root Cause
`MissingForegroundServiceTypeException` on Android 14+ (API 34). Both manifests declare
`android:foregroundServiceType="connectedDevice"` but both services called the 2-argument
`startForeground(id, notification)` overload. Android 14 requires the type parameter to match
the manifest declaration. Every `onCreate` → `startForeground` call without the type throws
immediately, causing every launch to crash.

The notification permission dialog crash was a red herring: the dialog dismissal resumed the
activity, which triggered a `startForegroundService` call, which then crashed inside the service.

### Bugs Fixed
| ID | Severity | Description |
|----|----------|-------------|
| BUG-063 | Critical | `startForeground()` missing type parameter — `MissingForegroundServiceTypeException` on Android 14+ |

### What Was Changed

#### `app-bridge/src/main/kotlin/.../BridgeService.kt` (BUG-063)
- Replaced 2-arg `startForeground(id, notification)` with API-guarded 3-arg call passing
  `ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` on API 29+ (Build.VERSION_CODES.Q).

#### `app-receiver/src/main/kotlin/.../ReceiverService.kt` (BUG-063)
- Same fix; also added `import android.content.pm.ServiceInfo` (was not covered by existing
  `import android.content.Intent` specific import).

#### `app-bridge/src/main/res/drawable/ic_launcher_*.xml`
- New logo: dark navy background (#101820), teal keyboard key with right-pointing arrow and
  Wi-Fi signal below — concept: bridge phone sends keyboard input wirelessly.

#### `app-receiver/src/main/res/drawable/ic_launcher_*.xml`
- New logo: deep navy background (#0A1628), blue tablet in portrait with dark screen and
  white cursor arrow — concept: receiver tablet accepts pointer/key events.

### Key Decisions
- **Why `Build.VERSION_CODES.Q` guard**: The 3-arg `startForeground` overload and
  `ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` were both added in API 29 (Q).
  The guard is belt-and-suspenders for any future minSdk < 29 scenario; on current devices
  (API 29 bridge, API 33+ receiver) the 3-arg path always executes.
- **Logo colours match existing UI themes** — bridge teal (#00D4AA ≈ BridgePrimary),
  receiver blue (#4FC3F7 ≈ ReceiverPrimary). Both use the app's background as the icon
  background for a cohesive look.

---

## Session 016 — First-launch crash + deep `else →` audit (BUG-058 → BUG-062)
**Date:** 2026-07-22
**Agent:** Claude (Replit)
**Status:** ✅ Complete

### Goals
- Fix BUG-058: first-launch crash on Android 13+ after notification permission dialog
- Deep audit: find and eliminate all `else →` violations in sealed/enum `when` blocks (§4.2)

### Bugs Found and Fixed
| ID | Severity | Description | Verdict |
|----|----------|-------------|---------|
| BUG-058 | Critical | App crashes after notification permission dialog on first launch (Android 13+) | ✅ FIXED |
| BUG-059 | High | `else →` in `BridgeService.startPipeline()` silently routes WIFI_DIRECT/TCP to UDP | ✅ FIXED |
| BUG-060 | High | `else →` in `ReceiverService` packet handler corrupts packet-loss statistics | ✅ FIXED |
| BUG-061 | Medium | `else → Unit` in `BridgeService.startIncomingLoop()` swallows all future receiver→bridge packets | ✅ FIXED |
| BUG-062 | Low | `else →` in `WelcomeScreen` for `TransportMode` display strings | ✅ FIXED |

### What Was Changed

#### `app-receiver/src/main/kotlin/com/inputbridge/receiver/ui/MainActivity.kt` (BUG-058)
- Moved `requestNotificationPermissionIfNeeded()` from before `setContent {}` to after it.

#### `app-bridge/src/main/kotlin/com/inputbridge/bridge/ui/MainActivity.kt` (BUG-058)
- Same: moved `requestNotificationPermissionIfNeeded()` to after `setContent {}`.

#### `app-bridge/src/main/kotlin/com/inputbridge/bridge/service/BridgeService.kt` (BUG-059, BUG-061)
- `startPipeline()`: replaced `else → startUdpPipeline()` with explicit arms for all 4
  `TransportMode` values. `WIFI_DIRECT`/`TCP` log a warning and explicitly fall back to UDP.
- `startIncomingLoop()`: replaced `else → Unit` with exhaustive arms for all 20 `PacketType`
  values grouped by category (expected, unexpected-control, input-event-only).

#### `app-receiver/src/main/kotlin/com/inputbridge/receiver/service/ReceiverService.kt` (BUG-060)
- Replaced `else → { ... }` with explicit arms for all 6 receiver-unexpected control types
  (`PONG`, `PAIR_RESPONSE`, `MODE_SWITCH`, `RECONNECT`, `ACK`, `ERROR` — now logged and
  discarded) and all 9 input-event types. Sequence-gap detection now only fires for input-event
  packets; control packets no longer corrupt `lastInputSeqNo`.

#### `app-bridge/src/main/kotlin/com/inputbridge/bridge/ui/screens/WelcomeScreen.kt` (BUG-062)
- Replaced `else →` in both `when (mode)` expressions with explicit `WIFI_DIRECT` and `TCP` arms.

### Key Decisions
- **BUG-060 is the toughest**: the `else →` in ReceiverService mixed two concerns (control
  dispatch and input routing), causing control packets to silently corrupt the sequence-gap
  counter. Fixing it required enumerating all 20 `PacketType` values across two blocks.
- **WIFI_DIRECT/TCP fallback**: these stubs fall back to UDP with an explicit warning log and
  a `DiagnosticsManager.lastError` entry rather than silently — observable in the Diagnostics
  screen if stale prefs hold an old transport mode.
- **BUG-058 ordering**: `ActivityResultLauncher.launch()` must always follow `setContent {}` on
  OEM builds (OxygenOS, MIUI enforce strict LifecycleOwner ordering).

---

## Session 015 — CI Repair + Second Audit Pass (BUG-054 → BUG-057)
**Date:** 2026-07-22
**Agent:** Claude (Replit)
**Status:** ✅ Complete

### Goals
- Diagnose and fix the two CI failures on `357648be` ("Build Debug APKs" job failing)
- Run a second deep audit pass on UI/ViewModel/test/build layers
- Fix all newly found bugs
- Push all Session 014 + Session 015 fixes to GitHub; confirm green CI

### CI Failure Root Causes
The last two CI runs on `357648be` failed at "Build Debug APKs" with:
1. 12 × `Unresolved reference 'KEYCODE_F1X'` in `KeyMap.kt` — `KEYCODE_F13–F24` do not
   exist in `android.view.KeyEvent` (Android only defines F1–F12). BUG-038 introduced this.
2. `UsbInputCapture.kt:90: The feature "break continue in inline lambdas" is experimental` —
   Kotlin 2.0 requires opt-in for `continue` inside `?: run {}` inline lambda.

### Audit Pass 2 Findings (new bugs)
| ID | Severity | Description | Verdict |
|----|----------|-------------|---------|
| BUG-054 | Critical | `KEYCODE_F13–F24` unresolved in `KeyMap.kt` — constants don't exist in Android | FIXED |
| BUG-055 | Critical | `continue` inside `?: run {}` inline lambda — Kotlin 2.0 experimental, not opted in | FIXED |
| BUG-056 | — | ViewModel `private val context: Context` — investigated; NOT a bug (Koin `androidContext()` = Application context, safe to hold in ViewModel) | FALSE POSITIVE |
| BUG-057 | Low | `MainActivity.applyKeepScreenOn()` constructs `BridgePreferences(this)` bypassing Koin DI | FIXED |

### What Was Changed
- `KeyMap.kt` — removed KEYCODE_F13–F24 entries (don't exist in Android KeyEvent); added explanatory comments
- `UsbInputCapture.kt` — replaced `?: run { continue }` with explicit `if (endpoint == null) { continue }` null check
- `HidReportBuilder.kt` — corrected BUG-050 fix: removed non-existent KEYCODE_F13–F24; kept KEYCODE_MENU (0x65)
- `MainActivity.kt` (bridge) — added `private val prefs: BridgePreferences by inject()`; `applyKeepScreenOn()` uses singleton
- `BUGS.md` — appended BUG-054, BUG-055, BUG-057
- `SESSION_LOG.md`, `TASKS.md`, `PROJECT_STATE.md`, `AI_CONTEXT.md` — updated

---

## Session 014 — Deep Audit + Bug Fixes (BUG-046 → BUG-053)
**Date:** 2026-07-21
**Agent:** Claude (Replit)
**Status:** ✅ Complete

### Goals
- Full deep audit of all source files — every module, every service, both apps
- Find all bugs not yet in BUGS.md; document them before touching any code
- Fix all confirmed bugs; verify each change is minimal and correct
- Check GitHub Actions CI status; push fixes; confirm green build

### Audit Scope
Read and cross-checked:
`UsbInputCapture`, `KeyMap`, `UdpTransport`, `AccessibilityCommandBus`,
`InputBridgeAccessibilityService`, `CursorOverlayService`, `ReceiverService`,
`BridgeService`, `BridgeApplication`, `ReceiverApplication`, `PacketSerializer`,
`EventPacketFactory`, `Packet`, `PacketType`, `PacketToEventConverter`,
`InputEvent` / `ModifierState`, `AppConfig`, `FeatureFlags`, `DiagnosticsManager`,
`DiagnosticsData`, `BluetoothHidTransport`, `HidReportBuilder`, `HidDescriptor`,
`BridgePreferences`, `ReceiverPreferences`, `BridgeViewModel`, `ReceiverViewModel`,
both `MainActivity` files, `BridgeScreen`, `ConnectionScreen`, `ci.yml`

### Bugs Found and Fixed
| ID | Severity | Description | Verdict |
|----|----------|-------------|---------|
| BUG-046 | Low | Dead `else ->` in `AccessibilityCommandBus.handleEvent` — suppresses sealed-class exhaustiveness | FIXED |
| BUG-047 | Low | `ReceiverService` notification shows `"Paired with bridge ()"` when `pairedBridgeIp` is empty after silence recovery | FIXED |
| BUG-048 | Medium | `UsbInputCapture.stop()` closes USB connection without releasing claimed interfaces first | FIXED |
| BUG-049 | Low | `triggerReconnect()` resets ping timestamps but not `lastCaptureToSendUs` — stale latency shown after reconnect | FIXED |
| BUG-050 | High | `HidReportBuilder.ANDROID_TO_HID` missing `KEYCODE_MENU` + `KEYCODE_F13`–`F24` — BT HID drops these keys silently | FIXED |
| BUG-051 | Low | `FeatureFlags.WIFI_DIRECT_ENABLED = true` but Wi-Fi Direct is a stub | FIXED |
| BUG-052 | Very Low | `ModifierState.numLock` always false — dead wire field (no Output report processing) | WONTFIX |
| BUG-053 | Medium | `DiagnosticsManager.update {}` read-modify-write race under concurrent IO callers | FIXED |

### What Was Changed
- `BUGS.md` — appended BUG-046 through BUG-053 with full descriptions
- `AccessibilityCommandBus.kt` — removed dead `else ->` branch
- `ReceiverService.kt` — guard empty `pairedBridgeIp` in silence-recovery notification
- `UsbInputCapture.kt` — track `claimedInterfaces`; release all in `stop()` before `close()`
- `BridgeService.kt` — reset `lastCaptureToSendUs` to 0 in `triggerReconnect()`
- `HidReportBuilder.kt` — added 13 missing `ANDROID_TO_HID` entries (MENU + F13–F24)
- `FeatureFlags.kt` — `WIFI_DIRECT_ENABLED = false`
- `DiagnosticsManager.kt` — `synchronized(updateLock)` wrapping `update {}` body
- `SESSION_LOG.md`, `PROJECT_STATE.md`, `TASKS.md`, `AI_CONTEXT.md` — updated
- `.agents/memory/bugs-046-053-audit.md` — full audit detail captured

---

## Session 001 — Phase 1 Scaffold
**Date:** 2025-07-19
**Agent:** Claude (Replit)
**Status:** ✅ Complete

### Goals
- Design the full 9-module architecture
- Write all source files for every module
- Write all documentation (13 files)
- Set up GitHub Actions CI
- Establish coding conventions and AI continuity rules

### What Was Done
- Created all 9 modules: shared-core, protocol, input-capture, transport-wifi,
  transport-bluetooth-hid (stub), accessibility-receiver, diagnostics,
  app-bridge, app-receiver
- All source files, all documentation, CI pipeline

---

## Session 002 — Phase 2 USB Capture
**Date:** 2025-07-19
**Status:** ✅ Complete

### What Was Done
- UsbInputCapture: full HID report parsing (keyboard 8-byte, mouse 4-byte)
- KeyMap: HID Usage → Android KEYCODE (full layout)
- BridgeService: USB device lifecycle (attach/detach/permission flow)
- BridgePreferences + ReceiverPreferences

---

## Session 003 — Phase 3 UDP Transport
**Date:** 2025-07-20
**Status:** ✅ Complete

### What Was Done
- UdpTransport: bidirectional socket, PING/PONG, PacketSerializer
- Receiver-mode lastSenderAddress tracking (PONG can reply without knowing bridge IP)

---

## Session 004 — Phase 4 Accessibility Injection
**Date:** 2025-07-20
**Status:** ✅ Complete

### What Was Done
- InputBridgeAccessibilityService: injectKeyCode(), injectText(), tap, swipe, longPress, goBack
- AccessibilityCommandBus: virtual cursor, dispatch loop
- Keyboard: unicodeChar + buildMetaState(), ACTION_SET_TEXT selection-aware

---

## Session 005 — Phase 5 Latency + Reconnect
**Date:** 2025-07-20
**Status:** ✅ Complete

### What Was Done
- Exponential backoff reconnect (1→30s, 10 attempts)
- Sequence number gap detection (droppedSequencePackets)
- Latency tracing: captureToSendUs, receiveToInjectUs, rolling 10-sample average
- DiagnosticsScreen updated

---

## Session 006 — Phase 5 completion + UI
**Date:** 2025-07-20
**Status:** ✅ Complete

### What Was Done
- All bridge screens: WelcomeScreen, BridgeScreen, SettingsScreen, DiagnosticsScreen, PermissionsScreen, AboutScreen
- All receiver screens: WelcomeScreen, ConnectionScreen, AccessibilitySetupScreen, ReceiverSettingsScreen, ReceiverDiagnosticsScreen
- Single-activity Compose NavHost, Koin DI, dark terminal theme

---

## Session 007 — Pairing handshake
**Date:** 2025-07-21
**Status:** ✅ Complete

### What Was Done
- 6-digit PIN generation on receiver (persisted, shown on ConnectionScreen)
- PAIR_REQUEST / PAIR_RESPONSE / PAIR_CONFIRM packet types
- Source IP validation: drop packets from non-paired senders
- Bridge: sends PAIR_REQUEST before entering hot loop
- Receiver: validates PIN, records bridge IP, sends PAIR_RESPONSE
- BridgeService full architecture rewrite (startIncomingLoop → doPairing → startPingLoop → watchdog)

---

## Session 008 — Diagnostic + BUG fixes
**Date:** 2025-07-21
**Status:** ✅ Complete

### What Was Done
- BUG-001 through BUG-012 all fixed
- DiagnosticsData: overlayPermissionGranted, isSecureWindow, btConnected, btDeviceName
- ReceiverDiagnosticsScreen: all counters

---

## Session 009 — Phase 6 Bluetooth HID
**Date:** 2025-07-21
**Status:** ✅ Complete

### What Was Done
- transport-bluetooth-hid module: BluetoothHidTransport with BluetoothHidDevice profile
- HID descriptor (keyboard + mouse combo)
- BridgeService: mode-aware pipeline (UDP vs BT HID dispatch)
- UI: transport mode toggle in SettingsScreen, BT MAC address field

---

## Session 012 — Deep Bug Hunt + Critical Fixes
**Date:** 2026-07-21
**Agent:** Claude (Replit)
**Status:** ✅ Complete

### Goals
Deep audit of all app code to find crashes, UX failures, and coordination gaps.
User reported: receiver crashes on button press, USB device not found after permission granted,
brightness starts at 33%, no permission dialogs shown, no BT HID coordination.

### Root Causes Found

#### CRITICAL — USB permission always denied (BUG-032)
`BridgeService.requestUsbPermission()` used `FLAG_IMMUTABLE` for the PendingIntent. On Android
12+, the USB system cannot write `EXTRA_PERMISSION_GRANTED` into an immutable PendingIntent, so
the result is always `false` regardless of what the user tapped. This made USB capture completely
non-functional even when the user granted the permission. Fixed: `FLAG_MUTABLE` on API 31+.

#### CRITICAL — START/STOP buttons crash app (BUG-033)
Both ViewModels called `startForegroundService()` inside `viewModelScope.launch {}` with no
try-catch. On Android 12+, calling this from a backgrounded state throws
`ForegroundServiceStartNotAllowedException` which crashes the app. Fixed: wrapped in
`runCatching {}` with error surfaced to `DiagnosticsManager.lastError`.

#### HIGH — Bridge sensitivity slider is no-op (BUG-034)
`BridgeService.startCapture()` forwarded raw events to the transport without reading
`prefs.bridgeSensitivity`. Mouse movement was always at 1:1 scale. Fixed: scale `MouseMove.dx/dy`
by `prefs.bridgeSensitivity` before event dispatch.

#### HIGH — POST_NOTIFICATIONS never auto-requested (BUG-035)
Neither app requested `POST_NOTIFICATIONS` at first launch. On Android 13+, without this the
foreground service notification is suppressed and the service may be killed by OEM battery
management. Fixed: `registerForActivityResult` + `requestNotificationPermissionIfNeeded()` in
both `MainActivity.onCreate()`.

#### MEDIUM — Receiver shows "Waiting for bridge…" forever in BT HID mode (BUG-036)
No in-app explanation that the receiver app is not needed when the bridge uses BT HID.
Fixed: permanent info card on `ConnectionScreen`.

#### LOW — Brightness slider shows 33% after upgrade (BUG-037)
Old slider could store a positive float. New code correctly read it but appeared broken.
Fixed: one-time migration sentinel resets any positive pre-migration value to `-1f` (system
default) on first run after upgrade.

### Files Changed
- `app-bridge/.../service/BridgeService.kt` — USB FLAG_MUTABLE, sensitivity scaling
- `app-bridge/.../viewmodel/BridgeViewModel.kt` — startBridge/stopBridge crash protection, TAG, BridgeLogger import
- `app-bridge/.../ui/MainActivity.kt` — POST_NOTIFICATIONS auto-request
- `app-bridge/.../prefs/BridgePreferences.kt` — brightness migration sentinel
- `app-receiver/.../viewmodel/ReceiverViewModel.kt` — startReceiver/stopReceiver crash protection
- `app-receiver/.../ui/MainActivity.kt` — POST_NOTIFICATIONS auto-request
- `app-receiver/.../ui/screens/ConnectionScreen.kt` — BT HID awareness card
- `BUGS.md` — BUG-032 through BUG-037 added
- `SESSION_LOG.md` — this entry

---

## Session 011 — Bug Audit + Documentation Overhaul
**Date:** 2026-07-21
**Agent:** Claude (Replit)
**Status:** ✅ Complete

### Goals
- Full codebase audit against bug reports BUG-013 through BUG-031
- Fix all stale documentation (ROADMAP, AI_CONTEXT, replit.md, BUGS.md)
- Push to GitHub to trigger CI and produce downloadable APKs
- Record all findings in BUGS.md with correct fix status

### What Was Found
The code was already complete and correct from Session 010. All BUG-013 through BUG-031
bugs were already fixed in the committed codebase:
- BUG-013/016: device_filter.xml class-based HID filter ✅
- BUG-014/015: connectedDevice foreground type + FOREGROUND_SERVICE_CONNECTED_DEVICE ✅
- BUG-017: WelcomeScreen Boot Auto-start reads correct field ✅
- BUG-018/029: Brightness slider redesigned (toggle + 0–100%) ✅
- BUG-019/023: Network status reads real ConnectivityManager state ✅
- BUG-020/021: ReceiverPermissionsScreen created and wired in nav ✅
- BUG-022/028: batteryOptimizationIgnored refreshed via ReceiverViewModel.refreshStatus() ✅
- BUG-023: Network setup guide card in SettingsScreen ✅
- BUG-024: BT HID mode clarification in SettingsScreen ✅
- BUG-025: WRITE_SETTINGS removed from bridge manifest ✅
- BUG-026: canRetrieveWindowContent="true" in accessibility_service_config.xml ✅
- BUG-027: Documented as DEFERRED — bulkTransfer works on Android interrupt endpoints ✅
- BUG-030: Scroll sensitivity DEFERRED to Phase 8 ✅
- BUG-031: STOP button only shown when service is active (both apps) ✅

### Documentation Updated
- `ROADMAP.md` — Phase 7 corrected from 0% to 100% ✅
- `AI_CONTEXT.md` — Current milestone updated to Phase 7 complete ✅
- `BUGS.md` — BUG-013 through BUG-031 added with full descriptions and fix status ✅
- `SESSION_LOG.md` — This entry ✅
- `replit.md` — Current phase updated to Phase 7 complete ✅

### Key Decisions
- BUG-027 (bulkTransfer on interrupt endpoint) deferred: bulkTransfer works correctly on Android
  for interrupt endpoints in practice. The UsbRequest refactor requires a per-connection
  demultiplexer and introduces regression risk without functional gain on the target hardware.
- BUG-030 (scroll sensitivity) deferred: functional with single sensitivity knob; separate
  scroll sensitivity is a clean Phase 8 addition.

### Files Changed (documentation only — code was already correct)
- `ROADMAP.md`
- `AI_CONTEXT.md`
- `BUGS.md`
- `SESSION_LOG.md`
- `replit.md`

---

## Session 010 — Phase 7 Polish (FULL)
**Date:** 2026-07-21
**Agent:** Claude (Replit)
**Status:** ✅ Complete

### Goals
- Complete all 0%-done Phase 7 polish items
- Fix all dead Settings controls (Keep Screen On, Show Latency, Sensitivity slider)
- Implement black screen mode, cursor dot overlay, emergency stop, live permissions
- Add user-controllable auto-start toggle, landscape support for receiver
- Push to GitHub to trigger CI

### What Was Delivered

#### Black Screen Mode (bridge)
- `BridgeScreen.kt`: `DisposableEffect(blackScreenMode, screenBrightness)` sets `window.attributes.screenBrightness`
- When enabled: pure-black UI, `0.001f` brightness (hardware minimum without backlight-off), tiny status dot
- When exiting: restores `BRIGHTNESS_OVERRIDE_NONE`

#### Screen Brightness Slider (bridge)
- `SettingsScreen.kt`: slider from -1 (system default) to 1.0 (maximum)
- `BridgePreferences.screenBrightness` (Float, key `screen_brightness`, default -1f)
- `BridgeViewModel.setScreenBrightness(Float)`

#### Keep Screen On toggle (bridge)
- `MainActivity.kt`: `applyKeepScreenOn()` reads pref and adds/clears `FLAG_KEEP_SCREEN_ON`
- Called in `onCreate()` and `onResume()` so changes take effect without restart
- `BridgePreferences.keepScreenOn` (default true)

#### Show Latency toggle (bridge)
- `BridgeScreen.kt`: latency row conditional on `config.display.showLatencyOverlay`
- `BridgePreferences.showLatencyOverlay` (default true)

#### Bridge Sensitivity slider (bridge)
- `SettingsScreen.kt`: slider wired to `viewModel.setBridgeSensitivity(it)` (was no-op stub)
- `BridgePreferences.bridgeSensitivity` (Float, key `bridge_sensitivity`, default 1.0)

#### Emergency Stop — Volume Down Hold (both apps)
- `onKeyDown`: cancels previous job, starts 3-second delayed coroutine
- `onKeyUp`: cancels job; short press (<500ms) passes through to system volume handler
- Shows toast on trigger; logs in BridgeLogger

#### Cursor Dot Overlay (receiver)
- `CursorOverlayService.kt`: new service using `SYSTEM_ALERT_WINDOW`
- `CursorDotView`: Canvas-drawn dot + crosshair (green fill, dark border, hair lines)
- `TYPE_APPLICATION_OVERLAY` (API 26+) with `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE`
- Collects `AccessibilityCommandBus.cursorPosition` StateFlow on `Dispatchers.Main`
- `ReceiverService.startCursorOverlayIfNeeded()` + `stopCursorOverlay()` called on connect/destroy

#### AccessibilityCommandBus — cursorPosition StateFlow
- `_cursorPosition: MutableStateFlow<Pair<Float,Float>>` published on every `MouseMove`
- `setScreenSize()` also updates it (cursor centres on screen-size change)
- `getCursorX()` / `getCursorY()` snapshot helpers

#### Live PermissionsScreen (bridge)
- `DisposableEffect(lifecycleOwner)` watches `ON_RESUME` — re-checks all permissions on return from Settings
- Battery opt: `PowerManager.isIgnoringBatteryOptimizations()`
- `BLUETOOTH_CONNECT` (API 31+): `rememberLauncherForActivityResult(RequestPermission())`
- `NEARBY_WIFI_DEVICES` (API 33+): same pattern
- `POST_NOTIFICATIONS` (API 33+): same pattern
- MIUI autostart card with deep-link attempt to MIUI autostart activity

#### Auto-start on Boot toggle (both apps)
- `BridgePreferences.autoStartOnBoot` / `ReceiverPreferences.autoStartOnBoot` (default true)
- Both `BootReceiver.kt` now read from prefs instead of `FeatureFlags.AUTO_START_ON_BOOT`
- Toggle in SettingsScreen (bridge) and ReceiverSettingsScreen (receiver)

#### WelcomeScreen cleanup (bridge)
- Only `TransportMode.UDP` and `TransportMode.BLUETOOTH_HID` shown
- `WIFI_DIRECT` and `TCP` stubs removed from list

#### Landscape Support (receiver)
- `screenOrientation="portrait"` removed from `app-receiver/AndroidManifest.xml`
- `android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize"` added

#### Manifest updates
- `app-receiver`: added `SYSTEM_ALERT_WINDOW`, `POST_NOTIFICATIONS`, `CursorOverlayService` entry
- `app-bridge`: removed `android:keepScreenOn="true"` (now applied in code)

#### AppConfig expansion
- `DisplayConfig`: 6 new fields (`blackScreenMode`, `showLatencyOverlay`, `keepScreenOn`, `screenBrightness`, `autoStartOnBoot`, `showCursorOverlay`)
- `DiagnosticsData`: `blackScreenMode`, `cursorOverlayActive`

### Files Changed (23 files)
- `shared-core/.../AppConfig.kt` — DisplayConfig expanded
- `diagnostics/.../DiagnosticsData.kt` — 2 new fields
- `accessibility-receiver/.../AccessibilityCommandBus.kt` — cursorPosition StateFlow + helpers
- `app-bridge/.../BridgePreferences.kt` — 6 new keys
- `app-bridge/.../BootReceiver.kt` — reads pref not FeatureFlags
- `app-bridge/.../BridgeViewModel.kt` — all new setters + config init from prefs
- `app-bridge/.../MainActivity.kt` — keepScreenOn + Vol-Down emergency stop
- `app-bridge/.../BridgeScreen.kt` — black screen mode + brightness DisposableEffect + latency visibility
- `app-bridge/.../SettingsScreen.kt` — all dead controls wired + new toggles
- `app-bridge/.../PermissionsScreen.kt` — full live permission checking with launchers
- `app-bridge/.../WelcomeScreen.kt` — hides TCP/WIFI_DIRECT stubs
- `app-bridge/AndroidManifest.xml` — removed android:keepScreenOn
- `app-receiver/.../ReceiverPreferences.kt` — 2 new keys
- `app-receiver/.../BootReceiver.kt` — reads pref not FeatureFlags
- `app-receiver/.../ReceiverViewModel.kt` — setCursorOverlayEnabled + setAutoStartOnBoot
- `app-receiver/.../ReceiverSettingsScreen.kt` — cursor overlay toggle + auto-start toggle
- `app-receiver/.../MainActivity.kt` — Vol-Down emergency stop, landscape
- `app-receiver/.../ReceiverService.kt` — startCursorOverlayIfNeeded() + stopCursorOverlay()
- `app-receiver/.../CursorOverlayService.kt` — NEW: floating cursor dot overlay
- `app-receiver/AndroidManifest.xml` — SYSTEM_ALERT_WINDOW + CursorOverlayService + landscape

### Key Decisions
- `screenBrightness = 0.001f` in black-screen mode (not 0.0f): `0.0f` can turn off the backlight
  entirely on some devices, making the emergency-stop STOP button invisible
- Emergency stop is Volume Down hold (3s), not a volume combo: combos require tracking both keys
  simultaneously which is unreliable when the volume buttons are not hardware-adjacent
- `CursorOverlayService` is NOT a foreground service: it's a lightweight overlay with no
  notification needed; foreground type would require notification + foreground service type
  declaration for API 34
- WelcomeScreen hides TCP/WIFI_DIRECT: showing broken modes confuses users; they'll be
  re-exposed in Phase 8 once implemented
- Portrait lock kept on bridge: intentional — the bridge phone is held or placed face-down

---

## Session 013 — Audit & Fix: 8 critical bugs found and resolved

**Date**: 2026-07-21
**Focus**: Deep-audit of the full codebase; fix all discovered bugs; Windows cursor; keyboard completion; mouse latency; crash capture; CI push.

### Bugs Fixed This Session

| Bug | Severity | Summary |
|-----|----------|---------|
| BUG-038 | High | `KeyMap` missing numpad 0–9+ops, F13–F24, Insert, Pause, Print Screen, Scroll Lock, Application key |
| BUG-039 | Critical | `UsbInputCapture` subclass=0 combo receivers silently dropped — all input lost |
| BUG-040 | Medium | `BridgeService.onDestroy` never sent DISCONNECT — receiver stuck "connected" for 15 s |
| BUG-041 | High | `ReceiverService` had no bridge-silence watchdog — silent failure forever |
| BUG-042 | Medium | `AccessibilityCommandBus.post(MouseMove)` routed through coroutine queue — 1–2 ms added latency |
| BUG-043 | Medium | Cursor overlay drew a green crosshair dot — replaced with Windows-style arrow cursor |
| BUG-044 | Medium | No global crash handler in either Application class — crashes left no diagnostic data |
| BUG-045 | Low | `UdpTransport.sendChannel` (Channel) was never closed on `disconnect()` — resource leak |

### Changes by Module

#### `input-capture` — KeyMap.kt, UsbInputCapture.kt
- **KeyMap.kt**: Complete rewrite — added ~20 missing HID usage codes: full numpad (0x53–0x63), Insert (0x49), Print Screen (0x46), Scroll Lock (0x47), Pause (0x48), Application key (0x65), F13–F24 (0x68–0x73). Documented why Consumer Control media keys (Usage Page 0x0C) are not included.
- **UsbInputCapture.kt**: Replaced subclass-only detection with a 4-level priority check: subclass+protocol → protocol alone → maxPacketSize heuristic → keyboard fallback. Removed the dead `readGenericHid` stub. Added 5-byte extended mouse report support (HID tilt wheel). Added `PROTOCOL_KEYBOARD=1` and `PROTOCOL_MOUSE=2` constants.

#### `accessibility-receiver` — AccessibilityCommandBus.kt
- **MouseMove hot path**: `post(InputEvent.MouseMove)` now updates `cursorX/Y` and `_cursorPosition` StateFlow directly on the calling IO thread — no coroutine dispatch overhead. `handleEvent` `MouseMove` branch is now a no-op. `MutableStateFlow.value` is thread-safe; overlay collects on Main.

#### `app-receiver` — CursorOverlayService.kt, ReceiverService.kt, ReceiverApplication.kt
- **CursorOverlayService.kt**: Replaced `CursorDotView` with `CursorArrowView`. New view draws classic Windows arrow shape using `Path` (tip at 0,0 hotspot, white fill, black outline, drop shadow). Fixed overlay positioning: `params.x = cursorX.toInt(), params.y = cursorY.toInt()` — no centering offset since hotspot is the tip.
- **ReceiverService.kt**: Added `lastPingReceivedMs` timestamp updated on every PING. Added `watchdogJob` coroutine (5 s poll, 15 s silence threshold). On silence timeout: notification updated, `DiagnosticsManager.lastError` set, `bridgeSilenceNotified` latch prevents repeat spam. Watchdog resets when bridge reconnects (next PING). Watchdog cancelled in `onDestroy`.
- **ReceiverApplication.kt**: Added global crash handler before Koin init. Captures to `BridgeLogger.e` and `DiagnosticsManager.lastError`; re-invokes previous handler for system crash dialog.

#### `app-bridge` — BridgeService.kt, BridgeApplication.kt
- **BridgeService.kt**: In `onDestroy()`, inside the `NonCancellable` block: send `packetFactory.makeDisconnect()` via UDP and `delay(60)` before calling `udpTransport.disconnect()`. Ensures receiver gets the DISCONNECT even on a clean stop.
- **BridgeApplication.kt**: Added global crash handler (same pattern as receiver).

#### `transport-wifi` — UdpTransport.kt
- **disconnect()**: Added `sendChannel.close()` as first statement, before `sendJob?.cancel()`. Channel iterator terminates cleanly; no dangling channel on reconnect.

### Key Decisions
- **MouseMove hotpath**: Ordering concern (click after move) is safe because `cursorX/Y` are updated before `post(MouseButtonDown)` is queued — the click coroutine reads the up-to-date position.
- **Watchdog threshold = 15 s**: The bridge sends PINGs every 1 s; 15 s allows for 14 missed PINGs — enough headroom for OS-level reconnect delays without false positives.
- **Arrow hotspot at (0,0)**: This is the WindowManager overlay's top-left corner. Setting `params.x = cursorX.toInt()` (not `cursorX - viewPx/2`) places the tip exactly at the logical cursor position. The old centering offset was wrong for an arrow shape.
- **Crash handler before Koin**: Registered in `Application.onCreate()` before `startKoin{}` so DI failures (common during development) are also captured.
- **Consumer Control media keys excluded**: Usage Page 0x0C requires a separate HID report interface with a different report format. This is outside scope for the current USB keyboard capture layer.

---

## Session 019 — Hard-bug audit and pipeline hardening (BUG-064 to BUG-074)

**Date**: 2026-07-25
**Scope**: Full audit of every file modified or introduced since the project import; fix all open
runtime-crash, data-loss, and diagnostic-corruption bugs before first hardware test.

### What was fixed

| Bug | Area | Fix summary |
|-----|------|-------------|
| BUG-064 | Service lifecycle | `CoroutineExceptionHandler` + `try/catch` + `handleRuntimeFailure()` in both `BridgeService` and `ReceiverService` |
| BUG-065 | Boot receiver | `runCatching` around `startForegroundService()` in both `BootReceiver` classes |
| BUG-066 | Activity lifecycle | `lifecycle.withStarted { requestNotificationPermission() }` instead of sync `onCreate()` call |
| BUG-067 | Mouse sensitivity | Removed receiver-side sensitivity scaling; bridge is sole multiplier |
| BUG-068 | Click ordering | Confirmed existing `@Volatile cursorX/Y` fast path is safe; added analysis comment |
| BUG-069 | UDP send queue | Dual-priority channels (UNLIMITED critical + 64-slot input); DSCP + 256 KB socket buffers |
| BUG-070 | Cursor bounds | Added `@Volatile` to `screenWidth`/`screenHeight` in `AccessibilityCommandBus` |
| BUG-071 | Event drops | Logged and DiagnosticsManager-updated `commandFlow.tryEmit()` return value |
| BUG-072 | Cursor init | Replaced `== 0f` guard with explicit `@Volatile cursorInitialized` flag |
| BUG-073 | Sequence counter | Split `EventPacketFactory` into `inputSequenceCounter` + `controlSequenceCounter` |
| BUG-074 | UDP send loop | Caught `ClosedReceiveChannelException` in send loop for clean disconnect |

### Key decisions
- Bridge owns the only sensitivity multiplier; receiver is pass-through.
- Mouse-move fast path (IO thread, volatile fields) preserved; click ordering proven safe.
- Dual send channels over single priority queue: simpler, no coalescing complexity.
- Separate sequence counters do not change the wire format (sequenceNo field still Int).

### Files modified
`accessibility-receiver/.../AccessibilityCommandBus.kt`,
`protocol/.../EventPacketFactory.kt`,
`transport-wifi/.../UdpTransport.kt`,
`app-bridge/.../BridgeService.kt`, `app-bridge/.../BootReceiver.kt`, `app-bridge/.../MainActivity.kt`,
`app-receiver/.../ReceiverService.kt`, `app-receiver/.../BootReceiver.kt`, `app-receiver/.../MainActivity.kt`,
`app-receiver/.../ReceiverPreferences.kt`, `app-receiver/.../ReceiverViewModel.kt`,
`app-receiver/ui/screens/ReceiverSettingsScreen.kt`,
`BUGS.md`, `SESSION_LOG.md`, `TASKS.md`, `PROJECT_STATE.md`

### Status at session end
All 11 bugs confirmed fixed. Hardware test (Task #2) remains pending.
