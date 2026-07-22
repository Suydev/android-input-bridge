# AGENTS.md — InputBridge AI Agent Protocol

> **READ THIS FILE BEFORE READING ANY OTHER FILE.**
> It defines the full operating protocol for every AI agent that works on this project.
> Treat it as a contract, not a suggestion.

---

## 0. What This Project Is

InputBridge turns an Android phone (Redmi 9, API 29) into a USB keyboard/mouse bridge for
a tablet (OnePlus Pad Go, API 33+). Two APKs communicate over LAN UDP:

- **`app-bridge`** — captures USB HID input, forwards packets via UDP or Bluetooth HID
- **`app-receiver`** — receives UDP packets, injects input via Accessibility Services

Nine Kotlin modules: `shared-core`, `protocol`, `input-capture`, `transport-udp`,
`transport-bluetooth-hid`, `transport-wifi` (stub), `accessibility-receiver`, `diagnostics`,
`app-bridge`, `app-receiver`.

Full architecture is in `AI_CONTEXT.md`. Hardware specs are in `PROJECT_STATE.md`.

---

## 1. The Non-Negotiable Onboarding Sequence

Every agent MUST read these files in this order before writing a single line of code:

```
1. AGENTS.md              ← you are here
2. AI_CONTEXT.md          ← architecture, constraints, known gotchas
3. PROJECT_STATE.md       ← current phase, CI status, bug audit state
4. BUGS.md                ← all known bugs (never delete entries)
5. TASKS.md               ← pending work, completed phases
6. SESSION_LOG.md         ← last 3 session entries (tail -100 if large)
7. .agents/memory/MEMORY.md  ← agent memory index
8.read the .agents/skills/SKILL.md  the execution and working strategy . edit it as you see any improvement in it needed or changes 
```

**Do not skip steps.** Skipping `BUGS.md` causes you to re-introduce fixed bugs.
Skipping `AI_CONTEXT.md` causes you to violate architectural constraints.
Skipping `SESSION_LOG.md` causes you to repeat work already done.

After reading, ask: *"What was the last thing that went wrong?"*
The answer is always in `SESSION_LOG.md` and `BUGS.md`.

---

## 2. Session Lifecycle — The Full Protocol

Every session follows this exact state machine. Never skip a phase.

```
START
  │
  ▼
[1. ORIENT]
  Read all 7 docs above. Check CI status (§9). Understand current state.
  Output: "I know what's broken, what's done, what's next."
  │
  ▼
[2. AUDIT]
  Read every source file touched or adjacent to this session's work.
  Cross-check sealed classes, atomic ops, lifecycle methods, interface contracts.
  Do NOT fix yet. List ALL findings.
  Output: "I have a complete bug list for this session."
  │
  ▼
[3. DOCUMENT FIRST]
  Append all new bugs to BUGS.md BEFORE touching code.
  One entry per bug. Full description, severity, steps to reproduce.
  This is mandatory — "document first, fix second" is the project's core rule.
  │
  ▼
[4. FIX]
  Fix all bugs, highest severity first.
  Each fix is minimal and targeted — no speculative refactors.
  Add a comment citing the BUG-XXX ID at every fix site.
  │
  ▼
[5. VERIFY]
  Re-read every changed file. Check:
    □ No new unresolved references
    □ No `continue`/`break` inside inline lambdas (Kotlin 2.0 — see §7.3)
    □ No KEYCODE_F13–F24 or other non-existent Android constants (see §7.4)
    □ No new `else ->` in sealed `when` blocks
    □ No new non-atomic read-modify-write on StateFlow
    □ USB interface released before connection closed
    □ `pairedBridgeIp.isNotEmpty()` checked before formatting into strings
  │
  ▼
[6. UPDATE DOCS]
  In this exact order (prevents stale cross-references):
    1. BUGS.md — mark fixed bugs as FIXED with session number
    2. SESSION_LOG.md — prepend new session entry (newer sessions at TOP)
    3. TASKS.md — check off completed tasks, add new ones if found
    4. PROJECT_STATE.md — bump "Last updated"
    5. AI_CONTEXT.md — add any new invariant discovered this session
    6. .agents/memory/MEMORY.md — add/update memory entries
  │
  ▼
[7. PUSH & MONITOR CI]
  git add -A && git commit -m "<conventional commit message>" && git push
  Wait for CI result (§9). Fix any new failures before declaring done.
  │
  ▼
[8. PROPOSE FOLLOW-UPS]
  Call proposeFollowUpTasks (max 3, highest-impact only).
  Call markTaskComplete.
  │
  ▼
END
```

---

## 3. Documentation System

This project maintains 8 active documentation files. Every agent must understand
the PURPOSE and UPDATE RULES for each.

| File | Purpose | Updated when |
|------|---------|--------------|
| `AGENTS.md` | Agent operating protocol (this file) | Protocol changes; new invariants discovered |
| `AI_CONTEXT.md` | Architecture + code-level constraints for agents | Every session that finds a new invariant |
| `PROJECT_STATE.md` | Current phase, CI status, module status, bug audit state | Every session |
| `BUGS.md` | Complete bug registry — NEVER delete entries | Before any fix; after fix confirmed |
| `TASKS.md` | Pending + completed work items | When tasks start or complete |
| `SESSION_LOG.md` | Chronological session record — newest FIRST | Every session |
| `MODULES.md` | Module dependency graph (if present) | Architecture changes only |
| `CHANGELOG.md` | User-facing release notes (if present) | Releases only |

### 3.1 BUGS.md Format

Every entry must include ALL of these fields — no exceptions:

```markdown
## BUG-XXX — <short imperative title>

**Description**: What is wrong, mechanically.
**Steps to reproduce**: Exact steps (e.g. build command, test, device action).
**Expected behavior**: What should happen.
**Actual behavior**: What actually happens.
**Suspected cause**: Root cause analysis.
**Files involved**: File path(s) and line numbers where applicable.
**Priority**: Critical / High / Medium / Low / Very Low  (see §3.2)
**Status**: 🔴 OPEN / ✅ FIXED (Session NNN) / ⚠ WONTFIX
**Fix**: One sentence description of what the fix does. Only filled after fix is verified.
```

Never delete entries. Never renumber. Next bug always gets `max(existing) + 1`.

### 3.2 Bug Severity Scale

| Level | Definition |
|-------|-----------|
| Critical | Blocks CI build; app crashes on launch |
| High | Feature silently broken; data corruption possible |
| Medium | Incorrect behavior under specific conditions |
| Low | Code smell with minor functional impact |
| Very Low | Dead code, cosmetic, protocol wire dead field |

### 3.3 SESSION_LOG.md Format

```markdown
## Session NNN — <title> (BUG-XXX → BUG-YYY)
**Date:** YYYY-MM-DD
**Agent:** <agent name>
**Status:** ✅ Complete / 🔄 In Progress / ❌ Aborted

### Goals
- bullet list

### Bugs Found and Fixed
| ID | Severity | Description | Verdict |

### What Was Changed
- file-by-file bullet list

---
```

Newer sessions prepend ABOVE older ones. The file reads newest→oldest.

---

## 4. Architecture Invariants — NEVER Violate These

These are hard constraints discovered across 15 sessions of development.
Each one has caused at least one bug when violated. Treat them as axiomatic.

### 4.1 Protocol Wire Format
- `Packet.PROTOCOL_VERSION` — **never change** unless coordinating both APKs simultaneously
- `PacketType` enum ordinal values — **never reorder or delete**; use append-only
- `InputEvent` sealed subclass serialization order — **never change field order** in
  `PacketSerializer`; receivers will silently decode garbage

### 4.2 Sealed Class Exhaustiveness
- `when (event: InputEvent)` — **never add `else ->`**
- `when (packet.type: PacketType)` — same rule
- When adding a new sealed subtype, grep for every `when` over that type and update all of them
  before the PR lands. The compiler enforces this only when there is no `else`.

### 4.3 Input Capture Contract
- `InputCapture.events: SharedFlow<InputEvent>` — never buffer events; `replay = 0`, `extraBufferCapacity = 64`
- `UsbInputCapture.start()` — must track every claimed interface in `claimedInterfaces`
- `UsbInputCapture.stop()` — must call `conn.releaseInterface(iface)` for each before `conn.close()`
- `UsbInputCapture.isActive` — check this flag at the top of `start()` before claiming anything

### 4.4 DiagnosticsManager Thread Safety
- `DiagnosticsManager.update {}` — **only** way to write `_state.value`; never bypass the lock
- `synchronized(updateLock)` wraps the read-modify-write — do not remove it
- UI layer only reads via `state.collect {}` on Main — never holds the lock

### 4.5 UDP Transport Bidirectionality
- `UdpTransport` in receiver mode — must track `lastSenderAddress` for PONG replies
- `targetIp` can be empty in receiver mode — always check `targetIp.isNotEmpty()` before send
- UDP port default: 5555 — do not change without updating both apps

### 4.6 BT HID Map Synchronization
- `KeyMap.HID_TO_ANDROID` and `HidReportBuilder.ANDROID_TO_HID` are MANUALLY MAINTAINED INVERSES
- Adding any key to one REQUIRES adding it to the other in the same commit
- There is no automated test for this symmetry — it must be reviewed manually

### 4.7 Feature Flags
- `FeatureFlags.WIFI_DIRECT_ENABLED` — must stay `false`; `WifiDirectTransport` is a stub
- Do not add any code path that enables this without a full transport implementation

---

## 5. Android API Constraints

These are specific Android API pitfalls discovered in this project.

### 5.1 KeyEvent Constants That Don't Exist
`android.view.KeyEvent` only defines **F1–F12** (codes 131–142).
`KEYCODE_F13` through `KEYCODE_F24` **do not exist** at any Android API level.
Any reference to them produces "Unresolved reference" at compile time.
- ✅ Use: `KeyEvent.KEYCODE_F1` … `KeyEvent.KEYCODE_F12`
- ❌ Never: `KeyEvent.KEYCODE_F13` … `KeyEvent.KEYCODE_F24`
- HID scan codes 0x68–0x73 (F13–F24) MUST map to `KeyEvent.KEYCODE_UNKNOWN` or be omitted

### 5.2 Minimum SDK is API 29 (Android 10)
The bridge app targets Redmi 9 at API 29. Any API that was added after 29 requires
`@RequiresApi(XX)` or a version check. Do not use compile-time constants defined in
API 33+ without checking the compileSdk (currently 35) and adding `@SuppressLint("InlinedApi")`
where the constant value is a safe integer fallback.

### 5.3 USB HID Boot Protocol
- Input reports (device→host): only modifier key state (Ctrl/Shift/Alt/GUI). No lock-key state.
- Output reports (host→device): LED state (CapsLock, NumLock, ScrollLock). The bridge does NOT
  read output reports. Therefore `ModifierState.numLock` is always false — this is correct, not a bug.

### 5.4 Accessibility Service Limitations
- `AccessibilityNodeInfo.getFocusedNode()` returns null if no node is focused
- `ACTION_SET_TEXT` works on standard EditText; fails on games, Flutter, some WebViews
- `textSelectionStart` can be -1 — always `coerceIn(0, text.length)` before use

### 5.5 Koin Context Injection
- ViewModels always receive `androidContext()` from Koin — this is the **Application** context
- Holding Application context in a ViewModel is SAFE (it lives as long as the process)
- Never create `BridgePreferences(activityContext)` — always inject via Koin

---

## 6. Kotlin 2.0 Constraints

This project uses `kotlin = "2.0.0"`. These are known behaviour changes.

### 6.1 Break/Continue in Inline Lambdas is Experimental
`break` and `continue` inside any inline lambda (`forEach {}`, `run {}`, `let {}` etc.)
are gated behind an experimental compiler flag not enabled in this project.

- ❌ **Never write:**
  ```kotlin
  val x = maybeNull() ?: run { log(); continue }
  list.forEach { if (bad(it)) continue }
  ```
- ✅ **Always write:**
  ```kotlin
  val x = maybeNull()
  if (x == null) { log(); continue }
  for (item in list) { if (bad(item)) continue }
  ```

### 6.2 Sealed Class Compiler Exhaustiveness (Kotlin 2.0 Improvement)
Kotlin 2.0 is stricter about sealed class exhaustiveness in `when` expressions used as
statements. An `else` branch where all cases are already covered produces a warning in
Kotlin 2.0 and may be promoted to an error in later versions. Remove all dead `else ->` branches.

### 6.3 Compose Compiler Plugin
This project uses `kotlin.plugin.compose = "2.0.0"` (the standalone Compose Compiler Plugin,
separate from the KGP). Do not add `kotlinCompilerExtensionVersion` to Compose BOM config —
the plugin handles this now.

---

## 7. CI System

### 7.1 Workflow File
`.github/workflows/ci.yml` — three jobs:

| Job | Trigger | What it does |
|-----|---------|-------------|
| `Build Debug APKs` | every push | `assembleDebug` for both apps; uploads APK artifacts |
| `Unit Tests` | every push | `:shared-core:test :protocol:test` |
| `Build Release APKs` | `main` push only | `assembleRelease`; skipped if no signing secret |

If `Build Debug APKs` fails, nothing else matters — fix it first.

### 7.2 CI Failure Diagnosis Protocol

1. Fetch the last 5 runs via GitHub API (see §9.1 for exact calls)
2. Find the first failing run; fetch its job list
3. Download logs for the failed job
4. Search for `FAILED`, `error:`, `BUILD FAILED` in the log
5. Look at the 30 lines BEFORE the error marker — that's the actual root cause

### 7.3 Known CI Failure Modes

| Symptom | Root Cause | Fix |
|---------|-----------|-----|
| `Unresolved reference 'KEYCODE_F1X'` | KEYCODE_F13–F24 don't exist in Android KeyEvent | Remove entries; use KEYCODE_UNKNOWN or omit |
| `The feature "break continue in inline lambdas" is experimental` | `continue`/`break` inside inline lambda | Replace with explicit `if (x == null) { continue }` |
| `Only one companion object is allowed` | Two `companion object` blocks in same class | Merge them |
| `Unresolved reference 'get'` in DiagnosticsManager lambda | Name shadowing in extension lambda | Capture values in `val` before entering lambda |
| `Unresolved reference '...'` in Koin module | Wrong package or missing import | Check module-level imports |

---

## 8. Git Workflow

### 8.1 Commit Message Format

```
<type>: <scope> — <short description>

<body: what changed and why, one line per file group>
```

Types: `fix`, `feat`, `docs`, `refactor`, `test`, `ci`

Examples:
```
fix: Session 015 — CI repair (BUG-054, BUG-055, BUG-057)
feat: Session 009 — Phase 6 BT HID transport
docs: Session 014 — deep audit documentation (BUG-046→053)
```

### 8.2 Push Protocol

```bash
git add -A
git status --short          # verify staged files look right
git commit -m "..."
git push
```

Always verify CI after push (§9). Never push a commit that you know will fail CI —
fix the CI failure first.

### 8.3 Branch Policy

- `main` — the only active branch; all work lands here directly
- Do not create feature branches unless explicitly requested
- CI runs on `main`, `develop`, `feature/**`, `phase/**`

---

## 9. GitHub API — CI Monitoring

Use the `GITHUB_PAT` Replit secret. Always access via `process.env.GITHUB_PAT` inside
a `"use impure"` function in CodeExecution.

### 9.1 Fetch Last 5 Runs
```javascript
const pat = process.env.GITHUB_PAT;
const res = await fetch(
  "https://api.github.com/repos/Suydev/android-input-bridge/actions/runs?per_page=5",
  { headers: { Authorization: `Bearer ${pat}`, Accept: "application/vnd.github+json" } }
);
const { workflow_runs } = await res.json();
console.log(workflow_runs.map(r =>
  `[${r.id}] ${r.name} | ${r.status} | ${r.conclusion} | ${r.head_sha.slice(0,8)}`
).join("\n"));
```

### 9.2 Fetch Job List for a Run
```javascript
const jobsRes = await fetch(
  `https://api.github.com/repos/Suydev/android-input-bridge/actions/runs/${runId}/jobs`,
  { headers: { Authorization: `Bearer ${pat}`, Accept: "application/vnd.github+json" } }
);
const { jobs } = await jobsRes.json();
```

### 9.3 Download Job Log
```javascript
const logRes = await fetch(
  `https://api.github.com/repos/Suydev/android-input-bridge/actions/jobs/${jobId}/logs`,
  { headers: { Authorization: `Bearer ${pat}`, Accept: "application/vnd.github+json" } }
);
const log = await logRes.text();
// Search for the error:
const lines = log.split("\n");
const errIdx = lines.findIndex(l => l.includes("FAILED") || l.includes("error:"));
console.log(lines.slice(Math.max(0, errIdx - 30)).join("\n"));
```

### 9.4 CI Status Interpretation

| `status` | `conclusion` | Meaning |
|---------|-------------|---------|
| `completed` | `success` | All jobs passed — green build |
| `completed` | `failure` | At least one job failed |
| `completed` | `skipped` | Job skipped (e.g. release on non-main) |
| `in_progress` | — | Still running; wait or poll |
| `queued` | — | Waiting for a runner |

---

## 10. The "Harder Task" Principle

When instructed to "take it harder" or "go deeper":

1. **Wider audit scope**: extend beyond the files changed in the current task to their callers,
   dependencies, and tests
2. **Cross-cutting analysis**: check every place in the codebase that could violate a new
   invariant discovered this session
3. **CI root-cause chain**: don't just fix the symptom — trace back to the commit that
   introduced it and verify no sibling bugs were introduced in the same change
4. **Documentation completeness**: every finding gets a BUGS.md entry before a single fix
5. **Second-pass audit**: after fixing, re-read all changed files looking for mistakes
   introduced by the fixes themselves
6. **Follow-up scope**: identify what the next session will need to know; pre-write that
   into AI_CONTEXT.md and agent memory before ending

---

## 11. Agent Memory System

Memory lives in `.agents/memory/`. The index is `.agents/memory/MEMORY.md` —
one bullet per topic file, always under ~200 chars.

### 11.1 Write to Memory When
- A fix took more than 2 attempts
- You discovered a non-obvious Android/Kotlin/USB behaviour
- You made a decision future sessions must be consistent with
- The user confirmed a non-obvious approach ("yes, exactly")

### 11.2 Never Write to Memory
- Things derivable from reading the code (file paths, function signatures)
- Implementation changelogs ("we changed X to Y in file Z")
- Conversation-local IDs (task numbers, PR numbers, commit SHAs)
- Secrets, credentials, tokens, PII

### 11.3 Topic File Format
```markdown
---
name: <short title>
description: <one-line description for relevance checking>
---

# <Title>

**Rule:** <the non-obvious constraint or decision>
**Why:** <what went wrong, or what constraint forces this>
**How to apply:** <when/where this kicks in>
```

---

## 12. Quality Gates

A session is NOT complete until ALL of these are true:

```
□ All bugs found in this session are documented in BUGS.md
□ All documented bugs are either FIXED or marked WONTFIX with justification
□ CI is green on the pushed commit (or is known-green on the prior commit)
□ SESSION_LOG.md has a new entry at the top
□ PROJECT_STATE.md "Last updated" line has been bumped
□ AI_CONTEXT.md contains any new invariants discovered
□ .agents/memory/MEMORY.md has been updated if anything durable was learned
□ No new `else ->` in sealed `when` blocks was introduced
□ No KEYCODE_F13-F24 references were introduced
□ No `continue`/`break` inside inline lambdas was introduced
□ `HidReportBuilder.ANDROID_TO_HID` and `KeyMap.HID_TO_ANDROID` remain inverse-consistent
□ proposeFollowUpTasks has been called (max 3, highest-impact)
□ markTaskComplete has been called
```

---

## 13. Module Quick Reference

| Module | Path | Key files |
|--------|------|-----------|
| `shared-core` | `shared-core/src/main/kotlin/com/inputbridge/core/` | `InputEvent.kt`, `FeatureFlags.kt`, `AppConfig.kt` |
| `protocol` | `protocol/src/main/kotlin/com/inputbridge/protocol/` | `Packet.kt`, `PacketSerializer.kt`, `PacketType.kt` |
| `input-capture` | `input-capture/src/main/kotlin/com/inputbridge/input/` | `UsbInputCapture.kt`, `KeyMap.kt` |
| `transport-udp` | `transport-udp/src/main/kotlin/com/inputbridge/transport/` | `UdpTransport.kt` |
| `transport-bluetooth-hid` | `transport-bluetooth-hid/.../bt/` | `BluetoothHidTransport.kt`, `HidReportBuilder.kt`, `HidDescriptor.kt` |
| `accessibility-receiver` | `accessibility-receiver/.../accessibility/` | `InputBridgeAccessibilityService.kt`, `AccessibilityCommandBus.kt` |
| `diagnostics` | `diagnostics/.../diagnostics/` | `DiagnosticsManager.kt`, `DiagnosticsData.kt` |
| `app-bridge` | `app-bridge/src/main/kotlin/com/inputbridge/bridge/` | `BridgeService.kt`, `BridgeViewModel.kt`, `MainActivity.kt` |
| `app-receiver` | `app-receiver/src/main/kotlin/com/inputbridge/receiver/` | `ReceiverService.kt`, `ReceiverViewModel.kt`, `MainActivity.kt` |

---

## 14. Authoritative External References

- HID Usage Tables 1.5: https://usb.org/document-library/hid-usage-tables-15
- Android KeyEvent constants: https://developer.android.com/reference/android/view/KeyEvent
- Android USB Host API: https://developer.android.com/guide/topics/connectivity/usb/host
- Kotlin 2.0 migration guide: https://kotlinlang.org/docs/whatsnew20.html
- Koin Android docs: https://insert-koin.io/docs/reference/koin-android/get-instances

---

*This document was last updated in Session 015. When adding new invariants, append to the
relevant section — do not rewrite history.*
