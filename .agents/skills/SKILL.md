# tBridge Agent Workflow Skill

> This skill teaches Replit agents the exact operational procedure for working on the
> InputBridge Android project. It is a Replit-environment-specific companion to `AGENTS.md`
> (the project-level universal protocol document in the repo root).
>
> **Always read `AGENTS.md` before using this skill.** This skill handles the "how to use
> Replit tools for InputBridge" — `AGENTS.md` handles the "what to do and what not to break."

---

## 1. Onboarding Sequence (First Action Every Session)

Before writing any code, run this exact sequence of reads in a single parallel batch:

```javascript
// In CodeExecution — batch all reads at session start
const [agentsMd, aiCtx, projState, tasksMd] = await Promise.all([
  // These 4 tell you everything before you open a single .kt file
  readFile("AGENTS.md"),
  readFile("AI_CONTEXT.md"),
  readFile("PROJECT_STATE.md"),
  readFile("TASKS.md"),
]);
```

Then read the tail of SESSION_LOG.md and the MEMORY index:

```
ReadFile: SESSION_LOG.md  (start_line: 1, end_line: 80)
ReadFile: .agents/memory/MEMORY.md
ReadFile: BUGS.md  (strategy: "tail", ~100 lines)   ← only need recent bugs
```

**Only after all reads:** check CI status (§4), then plan this session's work.

---

## 2. File Exploration

Use `ShellExec` with `find` and `rg` before opening files:

```bash
# Locate all Kotlin files in a module
find /home/runner/workspace/input-capture -name "*.kt" | sort

# Find all usages of a pattern across the project
rg "claimedInterfaces\|releaseInterface" --type kotlin /home/runner/workspace

# Find all sealed `when` blocks (audit target)
rg "when \(.*\)" --type kotlin /home/runner/workspace -l

# Check what's staged
git -C /home/runner/workspace status --short
```

For broad "understand how X works across the codebase" questions, dispatch an explore subagent
**before** reading files one by one:

```javascript
const report = await subagent({
  name: "inputbridge-explorer",
  task: "Explain how UdpTransport handles the PONG path. Include file paths and line numbers.",
  config: { $kind: "explore" }
});
console.log(report.text);
```

---

## 3. CI Monitoring — Exact Calls

Always wrap GitHub API calls in `"use impure"`:

### 3.1 Check last 5 runs

```javascript
const status = await (async function() {
  "use impure";
  const pat = process.env.GITHUB_PAT;
  const res = await fetch(
    "https://api.github.com/repos/Suydev/android-input-bridge/actions/runs?per_page=5",
    { headers: { Authorization: `Bearer ${pat}`, Accept: "application/vnd.github+json" } }
  );
  const { workflow_runs } = await res.json();
  return workflow_runs.map(r =>
    `[${r.id}] ${r.name} | ${r.status} | ${r.conclusion} | ${r.head_sha.slice(0,8)} | ${r.created_at}`
  ).join("\n");
})();
console.log(status);
```

### 3.2 Diagnose a failed run (get job names + failed steps)

```javascript
const diagnosis = await (async function() {
  "use impure";
  const pat = process.env.GITHUB_PAT;
  const runId = "REPLACE_WITH_RUN_ID";
  const res = await fetch(
    `https://api.github.com/repos/Suydev/android-input-bridge/actions/runs/${runId}/jobs`,
    { headers: { Authorization: `Bearer ${pat}`, Accept: "application/vnd.github+json" } }
  );
  const { jobs } = await res.json();
  return jobs.map(j =>
    `[${j.conclusion}] ${j.name} (id:${j.id})\n` +
    j.steps.filter(s => s.conclusion === "failure").map(s => `  ✗ ${s.name}`).join("\n")
  ).join("\n");
})();
console.log(diagnosis);
```

### 3.3 Download job log and find the error

```javascript
const log = await (async function() {
  "use impure";
  const pat = process.env.GITHUB_PAT;
  const jobId = "REPLACE_WITH_JOB_ID";
  const res = await fetch(
    `https://api.github.com/repos/Suydev/android-input-bridge/actions/jobs/${jobId}/logs`,
    { headers: { Authorization: `Bearer ${pat}`, Accept: "application/vnd.github+json" } }
  );
  const text = await res.text();
  const lines = text.split("\n");
  const errIdx = lines.findIndex(l => /FAILED|error:|BUILD FAILED/.test(l));
  const start = errIdx > 30 ? errIdx - 30 : 0;
  return lines.slice(start, errIdx + 50).join("\n");
})();
console.log(log);
```

### 3.4 Poll CI until green (after a push)

```javascript
// Run in a loop after push — poll every 30s, max 15 minutes
const result = await (async function() {
  "use impure";
  const pat = process.env.GITHUB_PAT;
  const sha = require("node:child_process")
    .execSync("git -C /home/runner/workspace rev-parse HEAD")
    .toString().trim();

  for (let i = 0; i < 30; i++) {
    await new Promise(r => setTimeout(r, 30000));
    const res = await fetch(
      `https://api.github.com/repos/Suydev/android-input-bridge/actions/runs?per_page=10`,
      { headers: { Authorization: `Bearer ${pat}`, Accept: "application/vnd.github+json" } }
    );
    const { workflow_runs } = await res.json();
    const run = workflow_runs.find(r => r.head_sha === sha && r.name === "Android CI");
    if (!run) continue;
    if (run.status === "completed") return `${run.conclusion} (run ${run.id})`;
    console.log(`  Still ${run.status}...`);
  }
  return "timed out";
})();
console.log("CI result:", result);
```

---

## 4. Git Push — Exact Procedure

```javascript
// Push using GITHUB_PAT
await (async function() {
  "use impure";
  const pat = process.env.GITHUB_PAT;
  const { execSync } = await import("node:child_process");
  execSync(
    `git -C /home/runner/workspace push "https://${pat}@github.com/Suydev/android-input-bridge.git" main`,
    { stdio: "pipe" }
  );
  console.log("pushed");
})();
```

Always run `git status --short` via ShellExec to verify what's staged before committing.

Commit message format (from `AGENTS.md §8.1`):
```
fix: Session NNN — <short title> (BUG-XXX → BUG-YYY)
```

---

## 5. Audit Protocol — Systematic Bug Hunt

Use an explore subagent for the broad sweep, then read flagged files yourself:

```javascript
// Dispatch parallel explorers for independent module groups
const [uiReport, transportReport, coreReport] = await Promise.all([
  subagent({
    name: "audit-ui",
    task: `Read every ViewModel and Activity in app-bridge and app-receiver.
    Report: (1) Context leaks, (2) bypassed Koin DI, (3) StateFlow races,
    (4) missing null checks on sealed-class when expressions.
    Include file paths and line numbers for every finding.`,
    config: { $kind: "explore" }
  }),
  subagent({
    name: "audit-transport",
    task: `Read UdpTransport.kt, BluetoothHidTransport.kt, HidReportBuilder.kt, KeyMap.kt.
    Report: (1) missing ANDROID_TO_HID entries vs HID_TO_ANDROID, (2) unresolved Android
    constants (KEYCODE_F13-F24 don't exist), (3) resource leaks.`,
    config: { $kind: "explore" }
  }),
  subagent({
    name: "audit-core",
    task: `Read UsbInputCapture.kt, BridgeService.kt, ReceiverService.kt, DiagnosticsManager.kt.
    Report: (1) AtomicLong read-modify-write races, (2) missing releaseInterface before close,
    (3) continue/break inside inline lambdas (Kotlin 2.0 experimental), (4) timestamp resets
    that are missing in triggerReconnect().`,
    config: { $kind: "explore" }
  })
]);
console.log(uiReport.text, "\n---\n", transportReport.text, "\n---\n", coreReport.text);
```

---

## 6. Documentation Update — Exact Order

After fixing bugs, update docs in this exact order to avoid stale cross-references:

```
1. BUGS.md           — mark bugs FIXED with session number
2. SESSION_LOG.md    — prepend new session entry (newer = top of file)
3. TASKS.md          — check off completed, add newly discovered work
4. PROJECT_STATE.md  — bump "Last updated: Session NNN"
5. AI_CONTEXT.md     — add any new invariant discovered
6. AGENTS.md         — add to §6 Known CI Failure Modes table if new CI failure type
7. .agents/memory/   — update MEMORY.md index + write/update topic files
```

Run all doc edits in a single parallel batch (they don't depend on each other):

```javascript
// All 6 doc edits in one batch — no sequential dependencies
await Promise.all([
  edit("BUGS.md", ...),
  edit("SESSION_LOG.md", ...),
  edit("TASKS.md", ...),
  edit("PROJECT_STATE.md", ...),
  edit("AI_CONTEXT.md", ...),
  writeFile(".agents/memory/new-topic.md", ...),
]);
```

---

## 7. Memory System — Write Protocol

Read MEMORY.md at session start. Update it before session end.

### When to write a memory entry

| Trigger | Example |
|---------|---------|
| Fix took >2 attempts | "Android KeyEvent has no KEYCODE_F13-F24 — cost 1 failed CI run to discover" |
| Non-obvious API behaviour | "USB releaseInterface() must precede close() or kernel holds interface" |
| Decision future sessions must repeat | "ANDROID_TO_HID and HID_TO_ANDROID must stay manually in sync" |
| User confirmed approach | "Yes, use synchronized(updateLock) not CopyOnWriteArrayList" |

### Memory update snippet

```javascript
// 1. Read existing index
// 2. Check if an existing entry can be updated (no duplicates)
// 3. Write the topic file first
// 4. Add/update the index line
// Always: strip task numbers, commit SHAs, implementation changelogs
```

---

## 8. Quality Gates Checklist

Run through this before calling `markTaskComplete`:

```
□ BUGS.md — all new bugs documented before any code was touched
□ BUGS.md — all fixed bugs marked ✅ FIXED (Session NNN)
□ SESSION_LOG.md — new entry prepended with complete change list
□ PROJECT_STATE.md — "Last updated" bumped
□ AI_CONTEXT.md — new invariants added if any discovered
□ AGENTS.md — §6 CI failure table updated if new failure mode found
□ .agents/memory/MEMORY.md — updated
□ CI — green on the pushed commit (or deliberately skipped with reason)
□ No `else ->` added to any sealed when block
□ No KEYCODE_F13-F24 references added
□ No continue/break inside inline lambdas added
□ ANDROID_TO_HID and HID_TO_ANDROID still symmetric
□ proposeFollowUpTasks called (max 3)
□ markTaskComplete called
```

---

## 9. Follow-Up Task Proposal

Call `proposeFollowUpTasks` exactly once per assigned task, before `markTaskComplete`.
Maximum 3 tasks. Categories: `incomplete_scope`, `next_steps`, `tech_debt`, `test_gaps`.

The highest-impact follow-ups for this project right now (as of Session 015):
- Zero unit tests in most modules → `test_gaps`
- BT HID mode doesn't handle ModifierStateChanged or NavigationAction → `incomplete_scope`
- ProGuard/R8 release build needs validation on device → `tech_debt`

```javascript
const { proposed } = await proposeFollowUpTasks({
  tasks: [
    {
      title: "Catch silent BT HID key drops before they reach real users",
      category: "incomplete_scope",
      description: `BluetoothHidTransport.sendInputEvent() currently ignores
TextInput, ModifierStateChanged, and NavigationAction events. In BT HID mode
(used when target is a PC or Mac), modifier keys (Shift, Ctrl) and navigation
keys will silently not work. Relevant files:
- transport-bluetooth-hid/.../BluetoothHidTransport.kt
- transport-bluetooth-hid/.../HidReportBuilder.kt
Done looks like: all InputEvent subtypes produce a valid HID report or a
documented intentional no-op in BT HID mode.`
    },
    // ... etc
  ]
});
console.log(proposed);
await markTaskComplete({ commit_message: "fix: Session NNN — ..." });
```

---

## 10. Project-Specific Replit Environment Notes

- **`GITHUB_PAT`** — Replit secret; access ONLY via `process.env.GITHUB_PAT` inside
  `"use impure"` functions. Never log it, never embed it in code files.
- **`SESSION_SECRET`** — Replit secret; not currently used by the build system; do not touch.
- **No workflow configured** — this is a pure code project; there is no running server.
  All verification is through CI (GitHub Actions) and code review.
- **No database** — no Replit database; all persistence is in SharedPreferences on-device.
- **Build tools** — not installed locally; all compilation happens in GitHub Actions CI.
  Use CI logs (§3) to verify builds, not local `./gradlew` (won't work in Replit).

---

## 11. Common Mistakes to Avoid

| Mistake | Consequence | Correct behaviour |
|---------|-------------|-------------------|
| Skipping BUGS.md read | Re-introduce fixed bugs | Always read BUGS.md tail first |
| `KEYCODE_F13` reference | CI compile error | Use `KEYCODE_UNKNOWN` or omit |
| `?: run { continue }` | CI Kotlin 2.0 error | Explicit `if (x == null) { continue }` |
| `else ->` in sealed `when` | Silently drops new event types | Remove; let compiler enforce |
| Updating only `KeyMap` not `HidReportBuilder` | BT HID drops keys | Update both in same commit |
| Forgetting `releaseInterface` before `close()` | Interface lock on replug | Track in `claimedInterfaces` |
| Creating `BridgePreferences(activity)` directly | DI bypass; wrong context | Use Koin `by inject()` |
| Not checking `pairedBridgeIp.isNotEmpty()` | Notification shows `"Paired with ()"` | Always guard |
| Changing `PacketType` ordinals | Breaks packet decoding | Append-only; never reorder |
| Pushing with failing CI knowledge | Breaks `main` for other agents | Fix CI first |
