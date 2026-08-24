---
name: nagramx-process-lifecycle
description: "Dazewell's rule for any process, daemon, or background command an agent starts while working on the NagramX fork (dazewell/NagramX) — adb, logcat, Gradle daemons, dev servers, watchers, emulators, or a detached shell. Trigger this whenever an agent is about to run Start-Process, an async/detached shell, adb, gradlew, an emulator, or any other long-running or background command, and whenever a child session is about to be archived. Covers: recording the exact PID or native tool handle at start time, stopping a process as soon as it is no longer needed (not just at handoff), cleanup that runs on success, failure, cancellation and timeout, exact-PID-only stopping (never by executable name), Windows PID-reuse-safe identity checks, ownership-aware daemon shutdown (never an unqualified adb kill-server / default-GRADLE_USER_HOME gradlew --stop / unscoped adb emu kill — only a session-owned, isolated instance may be stopped), the process ledger format, and the pre-archive verification checklist that gates whether a child session's worktree may be removed. This exists because a session archived while adb still held its worktree open corrupted the worktree; every agent that can start a process, and the orchestrator that archives sessions, must follow it. Edit this file when dazewell corrects the contract."
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
8. **Shut down only what you own — never a shared or default daemon.** Killing
   a client process does not kill the daemon behind it, but a daemon is
   frequently **shared across every session on the machine**, so an
   unconditional shutdown of it is itself a cross-session hazard, not a fix
   for one. Before shutting anything down, decide whether you own it in
   isolation or are merely a guest on the shared instance:
   - **adb.** Never run an unqualified `adb kill-server` — it can drop another
     session's device connection. If your work genuinely needs to own a
     server end-to-end (rare), start your own on a session-specific port
     outside the ambient one, record that port as part of the identity, run
     every client command against that same port, and stop only that owned
     endpoint: `adb -P <recorded-port> kill-server`. The common case is using
     the ambient/shared server through a plain client (`adb logcat`) — there,
     do not stop the server at all; your responsibility is limited to
     stopping your own client process and keeping its working directory and
     logs outside the worktree (rule 9) so it cannot hold the tree open.
   - **Gradle.** Prefer `--no-daemon` for a one-off invocation so there is no
     daemon to leak in the first place. If you need warm-daemon speed for
     iterative builds, point `GRADLE_USER_HOME` at a session-specific
     directory outside the worktree so your daemon is isolated from the
     shared/default registry, and stop only that isolated daemon with
     `.\gradlew.bat --stop` run with that same `GRADLE_USER_HOME` set. **Never**
     run a bare `.\gradlew.bat --stop` against the default `GRADLE_USER_HOME` —
     another session may have a live daemon registered there, and stopping it
     trades away someone else's warm build for nothing.
   - **Emulator.** Record the emulator's serial (from `adb devices` right
     after it boots) as part of its identity. Stop only that recorded serial:
     `adb -s <recorded-serial> emu kill`. Never kill an emulator you did not
     start, and never issue an unqualified `adb emu kill` that acts on
     whichever emulator happens to be current.
   - **Kotlin compile daemon.** A Gradle build that compiles Kotlin spawns a
     separate long-lived `KotlinCompileDaemon` (typically
     `--daemon-autoshutdownIdleSeconds=7200`, registered under
     `%LOCALAPPDATA%\kotlin\daemon`). It is shared and it outlives the build
     that started it by design, so it is **not** a leak and **not** yours to
     stop. It survives `gradlew --stop`. Leave it alone; it idles out.
   - **Isolated `GRADLE_USER_HOME` cache directory.** If you started a Gradle
     build with a session-specific `GRADLE_USER_HOME` outside the worktree
     for daemon isolation, the cache and daemon registry inside it are yours
     to remove, but **only after** the session is archived. The cache is not
     safe to delete while the session is running or while `archive_session`
     may still reference it. Deletion happens in the orchestrator; record the
     absolute path in your handback's `Isolated GRADLE_USER_HOME` field so
     the orchestrator can clean it up after archive succeeds (see post-archive
     step 7 for the exact validation and deletion contract). Cleanup applies
     whenever an isolated home was used, regardless of daemon mode
     (`--no-daemon` stops the single-use daemon but the ~2.8 GB cache remains).
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

## Other sessions run concurrently — that is normal, and their processes are not yours

Several sessions, each in its own worktree, routinely run on this machine at the
same time, and more than one of them may be building. **Seeing processes you did
not start is the expected steady state, not evidence of a leak**, and it is never
on its own a reason to clean anything up.

Two rules follow, and they bind every role:

1. **Scope every sweep to your own worktree path.** The residual sweep below is
   deliberately filtered to one worktree. A broad listing — "every `java.exe` on
   the machine", `Get-Process gradle`, anything matched on image name alone — is
   **diagnostic only**. It is never a cleanup list, its rows are not findings,
   and it must not gate an archive.
2. **Attribute before you even consider acting.** A process is yours only if it
   matches a row in your own ledger by PID **and** image name **and** start time
   (rule 7). Anything else is someone else's or ambient. In particular:
   - A command line naming a **different worktree** is another live session's.
     Leave it strictly alone — it may be mid-build, and killing it destroys
     work in another session that has no idea you exist.
   - A **shared daemon** (rule 8) is ambient. Leave it.
   - A process whose `CommandLine`/`ExecutablePath` is **inaccessible**
     (permission boundary, another user or session context) cannot be attributed
     to you, so by default it is **not yours**. Report it; never stop it on
     suspicion.

The discriminator that settles almost every case is the worktree path in the
command line, so check that before anything else:

```powershell
# Diagnostic only. Attribution, not a kill list.
# Windows paths are case-insensitive, so compare that way or a real match is missed.
$mine = (Resolve-Path $myWorktreePath).ProviderPath.TrimEnd('\')
Get-CimInstance Win32_Process |
  Where-Object { $_.Name -match 'java|gradle|adb|node' } |
  Select-Object ProcessId, Name, CreationDate,
    @{n='mine';e={ $_.CommandLine -and $_.CommandLine.IndexOf($mine, [System.StringComparison]::OrdinalIgnoreCase) -ge 0 }},
    @{n='cmd'; e={ if ($_.CommandLine) { $_.CommandLine } else { '(inaccessible)' } }}
```

**When in doubt, leave it running and say so.** A stray process that idles out on
its own costs nothing. Killing another session's build costs someone their work
and is unrecoverable — so the asymmetry always favours leaving it alone. "I found
processes I could not attribute to this session, so I left them and am reporting
them" is a correct, complete outcome, not a failure to finish the job.

## The canonical example

Based on dazewell's requested pattern, corrected for handle retention, bounded
waits, and preserving the original failure instead of masking it in `finally`:

```powershell
$process = Start-Process adb -ArgumentList @('logcat') -PassThru -NoNewWindow -WorkingDirectory $env:TEMP
$identity = @{ Id = $process.Id; Name = $process.ProcessName; StartTime = $process.StartTime }
$primaryError = $null
$cleanupError = $null
try {
    # Use the process.
}
catch {
    $primaryError = $_
}
finally {
    try {
        if (-not $process.HasExited) {
            # Stop through the retained handle's own Id, not a re-looked-up PID.
            Stop-Process -Id $process.Id -ErrorAction Stop
            if (-not $process.WaitForExit(30000)) {
                throw "Process $($identity.Id) ($($identity.Name)) did not exit within the bounded wait."
            }
        }
        # This example only used the ambient/shared adb server through a plain
        # client — nothing daemon-side is stopped here (see rule 8). Add an
        # `adb -P <recorded-port> kill-server` only if this session started
        # and owns that specific port; never an unqualified `adb kill-server`.
    }
    catch {
        $cleanupError = $_
    }
}
if ($primaryError -and $cleanupError) {
    Write-Warning "Cleanup also failed: $cleanupError"
    throw $primaryError
}
elseif ($primaryError) {
    throw $primaryError
}
elseif ($cleanupError) {
    throw "Process $($identity.Id) ($($identity.Name)) did not clean up: $cleanupError; session must not be archived."
}
```

Notes on this example:
- `-WorkingDirectory $env:TEMP` keeps the process's directory handle off the
  worktree.
- `WaitForExit(30000)` is bounded — never call the parameterless
  `WaitForExit()` here, it can hang forever on a stuck process. Verification
  reads `$process.HasExited` on the **retained handle**, never a fresh
  PID-presence lookup (e.g. `Get-Process -Id`) — a fresh lookup by number
  alone is exactly what Windows PID reuse (rule 7) breaks.
- `catch` captures the primary failure from the `try` block; `finally` does
  the stop/wait/cleanup and captures its own failure separately, so a cleanup
  problem never silently replaces or hides a real error from the work itself.
  After `finally`, both are surfaced: if only cleanup failed, that becomes the
  thrown error (session must not be archived); if the original work failed,
  that's what propagates, with any cleanup failure logged alongside it rather
  than swallowed.
- No unconditional `adb kill-server` appears in this example — see rule 8.
  Shutting down a daemon this process merely used, rather than one it started
  and owns in isolation, is a cross-session hazard, not a fix.

`try`/`finally` here is still **best-effort, not authoritative** — see rule 4
and the turn-boundary rule (11): the orchestrator's pre-archive gate below is
the real backstop when a turn is cancelled or the session dies outright before
this block can run at all.

## Process ledger format

One row per background item, whether it is a native OS process, an owned
daemon, or a tool-managed async/background shell. There is no partial credit
for a vague entry — a row missing a required field is treated the same as a
missing ledger (see below).

```
Processes: <none>
```
or, one block per item:
```
- kind:      adb-client | logcat | gradle-daemon | dev-server | watcher |
             emulator | tool-managed-shell | other
  started by: <this session/branch — so a later reader knows who owns it>
  identity:   <tool-native handle or async-shell session id>
              OR <PID>, <image name>, <start time>, <path/cwd>
  owned resource: <daemon-specific resource: adb port, emulator serial, or for
              a gradle-daemon row, the isolated GRADLE_USER_HOME cache path when
              used for daemon isolation> | n/a — used the shared/ambient resource
              and did not stop it (rule 8)
  purpose:    <why it was started>
  stop result: stopped | left running (justified: <why>) | failed to stop
  verified at: <timestamp of the identity-matched termination check> | not
              yet verified
```

**Separate from the process ledger**, include this mandatory cache cleanup field in your handback:

```
Isolated GRADLE_USER_HOME: <absolute child-owned path> | <none>
```

Always include this field: report the absolute cache path if you used an
isolated `GRADLE_USER_HOME` for any build (regardless of daemon mode), or
report `<none>` if you used a shared/default Gradle home or no Gradle build.
This field is distinct from the `owned resource` field in the process ledger for
non-Gradle processes (which records items like adb ports or emulator serials).
When a gradle-daemon process row exists in the ledger **and** you used an
isolated home, the `owned resource` value in that row should record the same
cache path for consistency with this field.


**Tool-managed async/background shells go in this ledger too** — record their
returned handle/session id under `identity` and their `stop result` from
calling the tool's own stop function against that handle (rule 10), not a PID.

## Starter-side responsibility (the agent that ran the process)

If you started it, you own recording it and stopping it — don't leave it for
the orchestrator to discover. Before you report your work done:

- Every process or daemon you started is either stopped and verified gone
  (using the ledger format above), explicitly still needed and called out as
  such, or explicitly a shared/ambient resource you deliberately left running
  because you don't own it (rule 8).
- Daemon-native shutdown ran **only** where you actually own the resource in
  isolation, per rule 8 — never an unqualified `adb kill-server`,
  `.\gradlew.bat --stop` against the default `GRADLE_USER_HOME`, or unscoped
  `adb emu kill`.
- Your handback includes the **process ledger** in the format above, always
  present even when empty (`Processes: <none>`). This ledger is reported in
  your handback message only — **never write it into a repository file.** A
  missing ledger, a malformed row (missing a required field), any row with
  `stop result: failed to stop`, or any row still `not yet verified` is a
  **hard archive block** — the orchestrator must treat it as if every process
  might still be running and must not archive until it's resolved.

## Orchestrator-side responsibility (pre-archive verification)

The orchestrator owns the gate: **a child session is archived only after this
checklist passes**, run from the main clone, not from inside the child's
worktree (a check run from inside the very directory being verified can itself
hold it open).

**Under nested orchestrators this gate is recursive and runs strictly
leaf-to-root.** An orchestrator may itself be a child of another orchestrator,
and it archives **only its own direct children** — never a grandchild.

- **"Direct child" is mechanical, not just semantic:** a direct child is a
  session whose `create_session` (or `open_pr_session` / `open_issue_session` /
  `fork_session`) call **this orchestrator itself** made and whose returned
  session id it recorded. If you can see a grandchild's id or worktree path only
  because it appeared in a child's report, that grandchild is **not** yours to
  archive; its own parent (your direct child) archives it. An orchestrator that
  reaches past a child to archive a grandchild directly is a protocol violation.
- **A direct child is one of two kinds, and they close differently.** A **leaf
  implementer** has no `CLOSED` state: it posts its handback (PR + process
  ledger), the parent runs the checklist below against that ledger, and the
  parent archives it — the ordinary one-level path, unchanged by nesting. A
  **child orchestrator** owns a subtree, so it closes *its* subtree first and
  only then reports `CLOSED` upward. Do not require a `CLOSED` message from a
  leaf implementer — it will never send one, and blocking on it would deadlock
  its archival.
- **Closure propagates upward as control messages, leaf-to-root — for the
  orchestrator layers.** A **child orchestrator** reports `CLOSED` to its parent
  **only** once *every* one of its own direct children is already archived (each
  leaf implementer via the normal handback→verify→archive path above, and each
  child orchestrator only after *it* reported `CLOSED` first) **and** its own
  ledger / residual-sweep contract below passes clean. If it still holds any
  descendant it could not safely archive — a blocked process, an unverified row,
  a grandchild that reported `BLOCKED_ARCHIVE` — it reports **`BLOCKED_ARCHIVE`**
  upward, never `CLOSED`. An ancestor must never archive across (skip past) a
  descendant reporting `BLOCKED_ARCHIVE`, and a `CLOSED` at a higher level must
  never paper over an unresolved `BLOCKED_ARCHIVE` below it.
- **A child orchestrator's `CLOSED` carries its own process ledger** (in the
  ledger format above, empty as `Processes: <none>`) plus its per-direct-child
  archive results, so the parent can run this same checklist against it and
  independently re-verify — exactly as it does against a leaf implementer's
  handback ledger. `CLOSED` is not accepted bare: a `CLOSED` with no ledger is a
  malformed report and a **hard block**, same as a missing implementer ledger.
  This accounting rides in the control message itself — **do not** add a ledger
  `kind:` for it; the ledger stays about OS processes only.
- **The per-direct-child archive results are a structured record, not free
  prose**, so the parent can mechanically match them against the direct-child
  rule rather than reading intent out of a sentence. One record per direct
  child, each stating at minimum:
  - **session id** — the exact id this child recorded when it made the
    `create_session` / `open_*_session` / `fork_session` call. This child made
    that call, so it — not the parent — is what establishes the session is a
    genuine *direct* child and not a grandchild seen second-hand; the id rides up
    as the auditable record of that fact, not as something the parent
    independently re-derives. The parent cannot mechanically prove provenance
    here: it never made the call and has no registry of another session's
    children to check the id against, so it takes this record on the child's
    word — exactly as the whole leaf-to-root contract does. What the parent *does*
    check mechanically is its **own** direct child (the session it created and
    recorded): that this child is accounted for, its ledger is clean, and its
    `CLOSED` — not `BLOCKED_ARCHIVE` — actually arrived. This is the same trust
    the rest of the pipeline places in a leaf implementer's claims: Phase 4's
    "every claim is unverified until you check it" governs the child
    orchestrator's **own** verification of **its** subtree, not a re-verification
    the parent performs on the child's behalf. There is no session-provenance
    registry in this environment to cross-check an id against, so a fabricated or
    stale record is a trust violation the protocol cannot mechanically detect —
    the same class of limitation as trusting any subordinate's report.
  - **kind** — `leaf implementer` or `child orchestrator`, since the two close
    by different paths (a leaf via handback→verify→archive, a child orchestrator
    only after its own `CLOSED`).
  - **archive outcome** — `archived`, or `BLOCKED_ARCHIVE` with the reason (in
    which case this orchestrator owes `BLOCKED_ARCHIVE` upward too, never
    `CLOSED`).
  - **lifecycle-check evidence that passed** — `ledger clean` and `sweep clean`
    for that child, the concrete evidence the pre-archive checklist below
    produced, not a bare assertion that it closed.
  A `CLOSED` whose per-direct-child records omit any of these, or that asserts
  closure without the matching evidence, is malformed and a **hard block**, the
  same as a missing ledger.
- **The residual sweep below is structurally blind to a live grandchild.** The
  worktree-filtered `Get-CimInstance Win32_Process` query matches only processes
  naming *this child's* worktree path; a live grandchild's processes name the
  **grandchild's own** worktree, so they never appear in this child's sweep. A
  clean sweep on a direct child is therefore **not** evidence that its whole
  subtree is closed. Subtree closure is established **only** by that child's own
  `CLOSED` message plus its reported per-direct-child archive results — never by
  the sweep alone. Because the parent can neither see nor re-verify a grandchild
  — it never created it and the sweep is blind to it — grandchild safety is
  **not** the parent's to establish; it is the child's, and the child is barred
  from ever sending `CLOSED` while it still holds an unarchived descendant (it
  owes `BLOCKED_ARCHIVE` instead, which an ancestor must never archive across). A
  truthful `CLOSED` is thus the parent's only evidence a grandchild closed, and
  it is the protocol's honesty rule — the `BLOCKED_ARCHIVE`-not-`CLOSED`
  obligation, not a parent-side registry the tools can't provide — that prevents
  the orphan the sweep-blindness would otherwise allow.

1. Read the direct child's process ledger from its report — a leaf implementer's
   handback ledger, or a child orchestrator's ledger carried in its `CLOSED`
   message. A missing ledger, a malformed row, a `stop result: failed to stop`,
   or an unverified row is a **hard block** — do not archive, and treat every
   such item as "assume still running." This "missing ledger blocks" rule
   applies strictly to a session that **reached `RUNNING`** and so owed a
   self-reported ledger. A session that **never reached `RUNNING`** owes none:
   the pre-`RUNNING` / mis-dispatch cleanup paths in the orchestrator agent file
   establish its ledger from the outside instead — a clean worktree-filtered
   sweep on a zero-diff session that never reported *is* the `Processes: <none>`
   evidence, a valid input to this checklist rather than a violation of this
   rule. The strict block still governs every session that did reach `RUNNING`
   and therefore did owe a ledger of its own.
2. Stop any tool-managed background shell you dispatched for that session
   through its own returned handle/session id — never by searching for a PID.
3. **Re-verify every OS-level identity yourself, including rows the ledger
   already marks stopped.** Don't take the starter's word for it: confirm PID
   + image name + start time no longer match (rule 7), for each ledger row.
4. For a row that is still running: if it's a **shared/ambient daemon**
   (default adb server, default Gradle daemon registry), do **not** stop it —
   per rule 8 that's a cross-session hazard, not a fix, and stopping it here is
   exactly as wrong as it would be for the starter. If it's a
   **child-owned, isolated resource** (a server on a port only that child
   recorded, an emulator serial only that child launched, a
   session-specific `GRADLE_USER_HOME`) and only the child can address it,
   **do not PID-hunt it yourself** — leave the session intact, send it back to
   the starter to stop and re-verify, and re-run this checklist after.
5. Do the **residual sweep**, run from outside the worktree (main clone), a
   report/identity-confirmation step, never a kill list on its own:

   ```powershell
   $worktree = (Resolve-Path $childWorktreePath).ProviderPath.TrimEnd('\')
   Get-CimInstance Win32_Process | Where-Object {
       ($_.ExecutablePath -and $_.ExecutablePath.StartsWith($worktree, [System.StringComparison]::OrdinalIgnoreCase)) -or
       ($_.CommandLine -and $_.CommandLine.IndexOf($worktree, [System.StringComparison]::OrdinalIgnoreCase) -ge 0)
   } | Select-Object ProcessId, Name, CreationDate, ExecutablePath, CommandLine
   ```

   Every row this returns must be explained — matched back to a ledger entry
   and identity-confirmed, or investigated as a leak. **An unexplained result
   blocks archival.** Note the scope carefully, because two different checks
   are easy to conflate:
   - **This query is worktree-filtered**, so a row here genuinely does touch
     the tree you are about to remove. It is explained by matching a ledger
     entry, or by being a **shared/ambient daemon** that merely references this
     path (rule 8) — a daemon is still not yours to stop. Anything else here is
     a leak and blocks the archive.
   - **A broad machine-wide listing** (see "Other sessions run concurrently"
     above) is where another live session's processes actually show up, since
     they name *their* worktree and never match the filter here. That listing
     is **diagnostic only**: its rows carry no blocking weight whatsoever, are
     not leaks, are not yours to stop, and must not be promoted into findings.
     Attribute them, report them, leave them running.

   If anything found by the worktree-filtered query needs stopping, that still
   goes through the exact-identity rule (rule 5) and the ownership rule
   (rule 8) — never a blanket kill off this list. If `handle.exe`/`handle64.exe`
   is already installed on the machine, you may also run it against the exact
   worktree path for open-handle diagnostics; do not install new tooling just
   to perform this check.
6. **The final release step depends on who owns the worktree, and the two
   paths must not be mixed:**
   - **App-managed child session** (the normal case — a session created via
     the app's session tooling, e.g. `create_session`): after 1–5 pass clean,
     call the app's `archive_session` operation **exactly once**, as the
     final operation. **Never manually run `git worktree remove`,
     `git worktree prune`, or delete the directory first** — `archive_session`
     owns stopping the session's CLI process and removing its worktree as one
     unit, and a manual removal ahead of it is exactly the failure mode that
     motivated this file: the app record is left pointing at a directory that
     no longer has `.git`. If `archive_session` fails, or only partially
     removes the worktree, **do not retry it, do not manually repair or prune
     it, and do not force anything through** — report the exact
     failure/process/handle evidence and leave the app session record intact
     for manual recovery.
   - **Manually managed worktree** (not owned by an app session — e.g. one
     you created yourself with `git worktree add` for a throwaway check):
     after 1–5 pass clean, `git worktree remove`, run from outside the
     worktree, **is** the final and authoritative release check. A sharing
     violation or any other failure there blocks cleanup — do not force a
     recursive delete or retry blindly. A worktree left partially removed
     this way is reconciled from the main clone with `git worktree prune`;
     that prune recovery path is for this manually managed case only, never
     a substitute for `archive_session` on an app-managed child.
7. Archive only after 1–6 all pass clean. If a missing ledger, a remaining or
   unverified process, an unexplained residual-sweep/handle result, or a
   removal/`archive_session` failure blocks any step: **do not archive.**
   Report the exact `Id`, `Name`, `Path`, `StartTime`, and command line
   (where available) and leave the session (or worktree) intact for manual
   recovery.
8. **Post-archive cache cleanup for isolated `GRADLE_USER_HOME` only.** After
   `archive_session` succeeds for a child that recorded an isolated
   `GRADLE_USER_HOME` in its handback (the mandatory `Isolated GRADLE_USER_HOME`
   field, separate from the process ledger), and **only then**, clean up that
   session-specific cache directory:
   - Confirm the path from the child's handback is child-owned (recorded by that
     session, not another), outside the removed worktree, and not the
     shared/default `%USERPROFILE%\.gradle` or another active session's home.
   - Run an exact-path process-use check against that directory to confirm no
     live process is reading it (exclude the probe's own process from the match
     to avoid false positives). If any unexplained process references it, **do
     not delete** — report the finding and leave the cache for manual
     recovery.
   - Delete only the resolved literal directory path (the exact path from the
     handback), without wildcards, globs, or broad-root variables. Never use
     `Remove-Item -Recurse` blindly; use a tool that confirms the deletion or
     reports exact-path evidence when it fails.
   - **Do not** stop shared Gradle or Kotlin daemons to make the deletion pass
     — if a daemon blocks it, leave the cache intact and report the block.
   - If cache deletion fails, report the exact path and error; do not retry
     destructively.
   - If the child's handback reads `Isolated GRADLE_USER_HOME: <none>`, no
     cache cleanup is needed.

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
