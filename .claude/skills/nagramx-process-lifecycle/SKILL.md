---
name: nagramx-process-lifecycle
description: "Dazewell's rule for any process, daemon, or background command an agent starts while working on the NagramX fork (dazewell/NagramX) — adb, logcat, Gradle daemons, dev servers, watchers, emulators, or a detached shell. Trigger this whenever an agent is about to run Start-Process, an async/detached shell, adb, gradlew, an emulator, or any other long-running or background command, and whenever a child session is about to be archived. Covers: recording the exact PID or native tool handle at start time, stopping a process as soon as it is no longer needed (not just at handoff), cleanup that runs on success, failure, cancellation and timeout, exact-PID-only stopping (never by executable name), Windows PID-reuse-safe identity checks, tool-native daemon shutdown (adb kill-server, gradlew --stop, adb emu kill), and the pre-archive verification checklist that gates whether a child session's worktree may be removed. This exists because a session archived while adb still held its worktree open corrupted the worktree; every agent that can start a process, and the orchestrator that archives sessions, must follow it. Edit this file when dazewell corrects the contract."
---

# NagramX process & session lifecycle

This is the **one normative copy** of the process-lifecycle contract for the
NagramX fork's AI workflow. `CLAUDE.md`, `nagramx-workflow`, `nagramx-branch-flow`,
and the `.github/agents/*.agent.md` role files point here rather than restating
the rules — if you find the same rule duplicated in one of them, that's drift,
fix it so only this file states it.

## Why this exists

An agent started `adb logcat` inside a session's worktree, the session was
archived while `adb.exe` still held the worktree directory open, worktree
cleanup partially completed, and the app was left with a session record whose
directory no longer contained `.git`. Every later deletion attempt then failed
with "not a git repository." Nothing in this repo's instructions said a
long-running process must be tracked and stopped before its session goes away
— this file is that missing rule.

## Who this binds

**Any agent that can start a long-running or background process is bound by
the starter-side rules below**, not only the implementer. Today that's
`nagramx-implementer` (Gradle, adb, dev servers) as the main case, but the rule
is generic: `nagramx-scout`, `nagramx-ux`, and `nagramx-architect` are read-only
and don't build or run the app, yet if a future recon step ever shells out to a
long-running command, the same rules apply to it. `nagramx-orchestrator` owns
the pre-archive verification side (below) for every child session it archives.

## The contract

1. **No untracked background process.** Before or immediately after starting
   any long-running or background command — `Start-Process`, an async/detached
   shell, a Gradle daemon, an emulator — record either the tool's native handle
   (a `Process` object, an async shell's session id) or, if no handle exists,
   the exact PID **plus image name and start time**. A process nobody recorded
   cannot be verified gone before archival.
2. **Prefer the handle.** Hold onto the `Process` object (or the tool's shell
   handle) for as long as the process runs. A bare PID is only a fallback for
   when no handle exists.
3. **Stop it as soon as it's no longer needed** — not only at final handoff.
   If a `logcat` tail or a dev server was only needed to check one thing, stop
   it right after, don't let it ride to the end of the session.
4. **Clean up on all four exit paths: success, failure, cancellation, and
   timeout.** Use `try/finally` (or the equivalent guaranteed-cleanup construct
   in whatever tool you're using) so a thrown error or a cancelled turn still
   stops what you started. `try/finally` is **best-effort, not authoritative**
   — see the turn-boundary rule and the orchestrator's pre-archive gate below,
   which are the real backstop for cancellation or a session that dies outright.
5. **Stop by exact identity only.** Use the recorded PID (or handle) to stop
   the *specific* process you started. **Never** stop-by-name — no
   `Stop-Process -Name`, no `taskkill /IM`, nothing that matches an executable
   name rather than a specific PID — because that can kill another session's
   process of the same name.
6. **Verify termination, don't assume it.** After asking a process to stop,
   wait for it with a **bounded** timeout (never an unbounded wait — a stuck
   process must not hang the agent forever) and then check that the exact
   identity is actually gone.
7. **Windows reuses PIDs.** A PID number alone is not proof of identity once
   time has passed since you recorded it. A later check (in a new turn, or by
   a different actor working only from a ledger) must confirm **PID, image
   name, and start time all match** the recorded identity before treating a
   running process as "the one I started." A PID match alone, with a mismatched
   image name or start time, is never a stop target — that's a different
   process that happens to reuse the number.
8. **Shut down the actual daemon, not just its client.** Killing a client
   process does not kill the daemon behind it:
   - If you used `adb`, run `adb kill-server` — killing an `adb logcat` client
     leaves the adb server running.
   - If you ran a Gradle build, run `.\gradlew.bat --stop` before handback or
     archival. This trades away warm-build speed for safe teardown — the next
     build in that worktree pays a cold-start cost. Do it anyway; a leaked
     Gradle daemon is exactly the kind of handle that blocks a worktree
     removal.
   - If you started an emulator, use `adb emu kill`, not just closing the
     window or killing the emulator process tree.
9. **Keep long-running processes' working directory and logs outside the
   session worktree.** Start them with a working directory such as `$env:TEMP`
   and redirect any log output there too, not into the worktree. A process
   rooted in the worktree is exactly what holds a directory handle open and
   blocks its removal.
10. **Tool-managed background commands are stopped through their returned
    handle, never by hunting for a PID.** If you started something with this
    environment's own async/background shell tooling (not a raw OS
    `Start-Process`), stop it using that tool's own stop call against the
    handle/session id it gave you at start time — don't go looking for its PID
    externally.
11. **Nothing long-running should survive a turn boundary unattended.** A
    session-attached process dies when the session goes idle, but an
    OS-level process started via `Start-Process` (or similar) does **not** —
    that gap is exactly what caused the incident this file exists to prevent.
    If a process must outlive one turn, re-verify it (identity-checked, per
    rule 7) at the start of the next turn, or stop it.

## The canonical example

Based on dazewell's requested pattern, corrected for handle retention, bounded
waits, and not masking the original exception from `finally`:

```powershell
$process = Start-Process adb -ArgumentList @('logcat') -PassThru -NoNewWindow -WorkingDirectory $env:TEMP
$identity = @{ Id = $process.Id; Name = $process.ProcessName; StartTime = $process.StartTime }
$stuck = $false
try {
    # Use the process.
}
finally {
    if (-not $process.HasExited) {
        Stop-Process -Id $process.Id -ErrorAction SilentlyContinue
        if (-not $process.WaitForExit(30000)) { $stuck = $true }
    }
}
if ($stuck -or -not $process.HasExited) {
    throw "Process $($identity.Id) ($($identity.Name)) did not exit; session must not be archived."
}
# adb.exe exiting does not stop the adb server behind it — shut that down too:
adb kill-server
```

Notes on this example:
- `-WorkingDirectory $env:TEMP` keeps the process's directory handle off the
  worktree.
- `WaitForExit(30000)` is bounded — never call the parameterless
  `WaitForExit()` here, it can hang forever on a stuck process.
- The `finally` block only stops and waits; it does not `throw`, so a real
  exception from the `try` block propagates instead of being replaced by a
  cleanup failure. The `$stuck`/final check after the `try/finally` is what
  surfaces a teardown problem, without destroying the original error.
- `adb kill-server` is a separate, explicit step — stopping the `adb.exe`
  client in this example does not stop the daemon.

## Starter-side responsibility (the agent that ran the process)

If you started it, you own recording it and stopping it — don't leave it for
the orchestrator to discover. Before you report your work done:

- Every process you started is either stopped and verified gone, or explicitly
  still needed and called out as such in your report.
- Daemon-native shutdown ran where it applies (`adb kill-server`,
  `.\gradlew.bat --stop`, `adb emu kill`).
- Your handback includes a **process ledger** line, always present even when
  empty:

  ```
  Processes: <none>
  ```
  or
  ```
  Processes: <PID>, <image name>, <start time>, <what it was for>, stopped: yes|no
  ```

  One line per process. This ledger is reported in your handback message only
  — **never write it into a repository file.** An omitted ledger means the
  work is unverified for archival purposes, and the orchestrator should treat
  it as if every process might still be running.

## Orchestrator-side responsibility (pre-archive verification)

The orchestrator owns the gate: **a child session is archived only after this
checklist passes**, run from the main clone, not from inside the child's
worktree (a check run from inside the very directory being verified can itself
hold it open).

1. Read the implementer's process ledger from its report. Treat a missing
   ledger as "assume still running."
2. Stop any tool-managed background shell you dispatched for that session
   through its own returned handle/session id — never by searching for a PID.
3. For each ledger entry not already marked `stopped: yes`, run daemon-native
   shutdown first if applicable (`adb kill-server`, `.\gradlew.bat --stop`,
   `adb emu kill`), then stop the specific process.
4. Verify identity before treating anything as a match: PID **and** image name
   **and** start time all agree with the ledger entry (rule 7 — Windows
   reuses PIDs).
5. Do a **scoped residual sweep**, limited to processes whose working
   directory or command line references that session's worktree path — not a
   broad process list, and never use the sweep's output as a kill list beyond
   confirming those specific processes by exact identity.
6. Do not install any new tooling just to perform this check.
7. Only after 1–5 pass clean: archive. If `git worktree remove` (or the
   archival directory deletion) fails with a sharing violation, that is a
   **block, not something to retry harder against** — do not force a recursive
   delete. A worktree left partially removed is reconciled from the main clone
   with `git worktree prune`, never a forced delete.
8. If a process or handle cannot be released, **do not archive.** Report the
   exact `Id`, `Name`, `Path`, `StartTime`, and command line (where available)
   and leave the session intact for manual recovery.

## Coverage

This contract applies to, at minimum: `adb` (client and server — see rule 8),
`logcat`, Gradle daemons (`gradlew.bat`/`gradlew`), Android emulators, dev
servers, file watchers, and any shell command run in this environment's
detached/async mode. If it's long-running or backgrounded, it's in scope.

## Scope note for judgement-only roles

`nagramx-scout`, `nagramx-ux`, and `nagramx-architect` are read-only and don't
normally start long-running processes, but if one of them ever does (a long
search or inspection command), the starter-side rules above bind it the same
way — there's no separate lighter version for those roles.

## Keeping this current

This file is the single normative copy. `CLAUDE.md` points here for the
routing rule, `nagramx-workflow` and `nagramx-branch-flow` point here at their
own process-touching steps (the review-wait watcher, worktree removal) rather
than restating any of this, and the `.github/agents/*.agent.md` role files add
it to required reading and, where relevant, to their action sites — an
orchestrator pre-archive check, an implementer starter-side note. When
dazewell corrects this contract, edit it here first, then check that no other
file has grown a duplicate copy of the rule that just changed.

**Takes effect for new sessions only.** A session already running when this
lands on `dev` was started before the roster/skill list was read and will not
pick it up — restart the session after pulling to get this rule.
