---
name: nagramx-workflow
description: "Dazewell's process for adding or changing anything in the NagramX repo (NagramX, a personal Telegram-for-Android fork, GitHub dazewell/Dazegram). Trigger this whenever work touches that repo: adding a feature, fixing a bug, touching README.md there, preparing a commit or PR for it, or when dazewell references \"the usual process\" for NagramX. Also trigger when dazewell gives new or corrected guidance about how this workflow should run — this file is meant to be edited, not just read. Covers: what NagramX is and its legacy-Java constraints, the reuse-first / minimal-footprint hook style with concrete hook points and config systems, the two review rounds (plan review before coding, code review after), the compile gate (`ci.yml`) and its CI fallback when no local toolchain exists, coding conventions, commit/history conventions (no AI mentions in git logs or source, ever), README style, on-device testing, dual-package CI, and when a PR actually gets opened."
---

# NagramX contribution workflow

This is dazewell's fork of **Telegram for Android** (the legacy Java client,
`org.telegram.messenger`), carrying extra features on top of the
AyuGram / NekoX / Nagram lineage. It's small on purpose — a short, clean,
human-looking commit history sitting on top of an upstream base fork. Every
step below exists to protect that. Don't skip steps to save time; the whole
point of this workflow is that it's slower than just editing files, in
exchange for a repo that stays clean.

**The one hard line: no AI mentions in git logs or in the app's source.**
Commit messages, PR titles/bodies, and code (comments included) must never
reference Claude, Anthropic, Copilot, or any assistant — no
`Co-Authored-By`, no "Generated with" footers, no AI-flavored comments.
This workflow doc, `CLAUDE.md`, `README.md`, `FEATURES.md`, and the memory
notes are allowed to talk about the process openly; the app's history and
code are not.

## What NagramX is (and isn't)

- It is **not** a modern Jetpack Compose / MVVM app. Do **not** introduce
  Compose, Hilt, Room, or a multi-module rewrite. Match the existing
  legacy patterns.
- Primary language is **Java**; some fork code is **Kotlin** (e.g.
  `NaConfig.kt`). Kotlin `const val`s surface as Java static fields.
- Source root: `TMessagesProj/src/main/java` (plus some Kotlin under
  `TMessagesProj/src/main/kotlin`).
- dazewell works on **Windows** — give all shell commands in **PowerShell**
  syntax by default.
- **Machine matters: don't assume you can build.** Machines differ, so check
  which one you're on before deciding — `$env:COMPUTERNAME` answers it (it
  reports `ZENBOO`; compare case-insensitively, PowerShell's `-eq` already is).
  - **`ZenBoo` — run the gate.** JDK 21 (`JAVA_HOME` →
    `C:\Program Files\Microsoft\jdk-21.0.12.8-hotspot`) and the Android SDK
    (`ANDROID_HOME` → `C:\Users\dazewell\AppData\Local\Android\Sdk`) are both
    installed and on the environment, so no `local.properties` is needed.
    Measured on the privacy-profiles worktree: **~9 min cold** (fresh daemon,
    no configuration cache), **~15 s after a normal source edit**, ~8 s when
    nothing changed. Run the gate here rather than waiting on CI — after the
    first run of a session it's effectively free. Give a cold run a 10-minute
    budget and don't kill it early. A full `assemble` is still heavy; leave
    artifacts to CI or to dazewell.
  - **Surface Book 2 — don't build at all.** Too slow: even the compile gate
    drags and a full `assemble` runs for an hour-plus. Treat the toolchain as
    unavailable and fall back to CI exactly as step 4 describes.
  - **Anywhere else, or if you can't tell** — ask, or apply step 4's quick
    toolchain check and fall back to CI when it fails. dazewell builds manually
    on a fast machine when he wants an artifact.

## The pipeline, in order

1. **Design review with the Chief Architect (round 1 of 2).** Before
   writing any code, present the proposed change to an agent role-played as
   *the chief architect of Telegram for Android* — someone who knows the
   app's architecture cold and reviews changes to a community fork for
   soundness. Ask it to poke holes: does this fight the existing
   architecture, will it survive the next upstream merge, is there a
   simpler hook point, does it duplicate something that already exists
   elsewhere. **Do not start implementation until you and that reviewer are
   aligned.** Spawn it as a subagent (Agent tool — general-purpose or
   Explore first if you need to see the relevant code, then reason as the
   architect), or reason through it inline in that persona if a subagent is
   overkill for a small change. The point is the adversarial early pass,
   not the mechanism. In Copilot CLI the persona is packaged as the
   `nagramx-architect` custom agent (`.github/agents/`) — dispatch that instead
   of hand-rolling the prompt. The persona, the Android-and-fork checklist, and the
   dispatch/output template live in the `nagramx-code-review` skill — read it
   for *what* this review actually checks.

   **A design gate for stateful or concurrent changes — a real gate, not a
   note.** If recon *or implementation* reveals the change touches a cache,
   asynchronous work, or invalidation — any two of the three, or any one plus
   multi-threading — implementation **stops** until a short
   state-and-interleaving spec goes back through review: what state exists, who
   writes it, on which thread, what clears it, and the interleavings that matter.
   Route it through round 1, or through a **round 1.5** the moment the risky part
   surfaces mid-implementation — because round 1 almost always ran before that
   part existed, and **a design review conducted before the hard part existed has
   not reviewed the hard part.** Say that plainly rather than assuming round 1
   covered it. It costs a paragraph, not a build. (The `#personal-replies`
   reply-counter fix took five implementation rounds and four review passes
   converging on this exact mechanism; a spec up front would have caught it in
   round 1 instead of round 3. The `#infinite-video` rollover fix is the sharper
   case: round 1 reviewed a plan in which the re-arm-on-rollover path did not yet
   exist, so the concurrency bug that dominated the change was never in front of
   a reviewer as a design question — only ever as isolated line fixes, one of
   which shipped.)

   **State the trade-off budget in the brief.** Every change brief should say,
   in one line, what may be spent for correctness — an extra query, an extra
   round trip, some memory, a slower rare path. Without an explicit budget an
   implementer optimizes by default and then defends that optimization through
   several review rounds arguing a false economy.

   **Cost optional slices separately; trigger the disproportionate-slice gate if
   one dominates.** A change with optional, lower-priority, or distinct-risk
   subfeatures must cost each one separately in the brief, naming what
   implementation, review, build, or device-test overhead is unique to it —
   never hide a slice inside the total. Mid-flight, if an optional slice
   reopens design (round-1.5), introduces a new Activity, service, storage,
   cache, concurrency, or lifecycle mechanism solely for itself, accumulates
   repeated Critical/Important findings, causes an extra APK/device cycle, or
   plainly dominates elapsed risk, **stop and report it with evidence**: which
   slice, what unique cost, whether the rest is healthy, and options — keep it at
   stated cost, simplify it, substitute a lower-risk behaviour, or drop it. Do
   not spend another fix/review/build cycle on it without dazewell's decision.
   The implementer must report the trigger to their orchestrator; the orchestrator
   asks dazewell; a child orchestrator sends the question directly to him. This is an allowed exception to
   one-gate-per-feature because cost-assumption invalidation at mid-flight is
   the thing this rule exists to catch.

2. **Branch.** Cut a short-lived change branch off `dev` (the trunk), named
   `<YYYY-MM-DD>_<short-slug>` — **the date prefix is mandatory** (the date you
   start it, e.g. `2026-07-07_require-password`), `_` or `-` between date and
   slug (both fine — tooling normalizes it), hyphens inside the slug. An undated
   name like `require-password` is
   wrong; fix it before the first push. One change per branch. You PR it into
   `dev` and **delete it after merge**; keep it alive only if you'll propose
   that feature upstream. Don't commit directly to `dev`. The full topology,
   branch-naming rules, the
   `#tag` every commit carries, sync, and the no-force-push rule live in
   the `nagramx-branch-flow` skill — read it for where commits live and how
   they move.

3. **Implement with minimum footprint.** The base fork's own files should
   move as little as possible so rebasing onto upstream stays cheap.

   **Reuse before you build.** Search the repo for an existing component,
   helper, flow, or pattern that already does the thing (or something
   close) and reuse it. Grep first. Examples: the passcode screen is
   `org.telegram.ui.Components.PasscodeView` (already embedded standalone in
   `ExternalActionActivity` / `BubbleActivity`) — reuse it rather than
   hand-rolling one. Same for dialogs, menu items, bulletins, biometric
   prompts, config toggles. Only write new code when nothing reusable
   exists — and say so.

   **Analyze the chokepoint.** For anything touching a core flow (opening a
   chat, the chat list, notifications), find the single place all paths
   funnel through and hook there instead of patching many call sites. E.g.
   every way of opening a chat funnels through
   `ChatActivity.onFragmentCreate()` → `presentFragment` /
   `addFragmentToStack`.

   **Design as hooks.** New logic goes in **self-contained feature
   classes**; the base file gets only a few injected lines. Feature code
   typically lives under:
   - `com.radolyn.ayugram.<feature>` (e.g. `hidelastmessage`, `chatlock`)
   - `tw.nekomimi.nekogram.helpers.*`
   - `xyz.nextalone.nagram.*`

   Base-file edits are tiny hooks — usually a few lines, often using
   **fully-qualified names so no imports are added to the base file** — and
   each is marked with a `// NagramX:` comment explaining the non-obvious
   *why*. Mirror an existing feature when adding a similar one; good
   references are the `hidelastmessage` package (+ its `ChatActivity` /
   `DialogCell` hooks) and the `chatlock` package.

   **Common hook points:**
   - `org.telegram.ui.ChatActivity` — in-chat overflow (header) menu items
     via `headerItem.lazilyAddSubItem(...)`; per-feature int ids live with
     the other `nk*` / `nkheaderbtn_*` constants; clicks handled in the big
     `onItemClick` chain. `createView` builds the chat view; `dialog_id` is
     resolved by then.
   - `org.telegram.ui.Cells.DialogCell` — chat-list row rendering
     (last-message preview, etc.).
   - `org.telegram.ui.DialogsActivity` — chat list; `showChatPreview(DialogCell)`
     is the long-press peek.
   - `org.telegram.ui.LaunchActivity` — app entry; intent / notification /
     shortcut / deep-link handling, app-lock (`onPasscodePause`), `instance`
     singleton, overlay-passcode stack.
   - `org.telegram.messenger.SharedConfig` — app passcode (`passcodeHash`,
     `passcodeType`, `checkPasscode`), app-lock state.

   **Config & persistence.** Use the fork's existing settings surfaces for
   any new user-facing setting rather than inventing storage per feature:
   - `xyz.nextalone.nagram.NaConfig` (`NaConfig.kt`) — read like
     `NaConfig.INSTANCE.getX().Bool()`.
   - `tw.nekomimi.nekogram.NekoConfig` — NekoX-era settings.
   - `org.telegram.messenger.SharedConfig` — upstream settings.
   - Local per-account, never-synced state goes in a per-account
     `SharedPreferences` file named `<feature>_<account>` (see
     `HideLastMessageController`, `ChatLockController`).

   **Fork-owned resources, not shared ones.** New strings go in
   `TMessagesProj/src/main/res/values/strings_nax.xml`, not `strings.xml`.
   Reuse existing drawables/strings where one already fits; don't add new
   assets when an upstream one works.

   **Default to falling back, not migrating, when a stored value's range,
   set, or format changes.** A value that is no longer valid gets clamped to
   the nearest valid one, or replaced with a sensible default, at the point
   it is read. No data rewrite, no versioned migration, no per-value
   grandfathering. The one requirement that isn't negotiable: the app must
   not crash on an out-of-range, absent, unparseable, or otherwise
   unexpected stored value, at every place it's read. That's the floor this
   rule doesn't lower — what it lowers is the obligation to preserve the old
   value's *meaning*. Migration is the exception, and it has to be argued
   for: justified only when falling back would lose something the user
   would actually miss and couldn't trivially recreate, and — per the
   `Trade-off budget` field in the change brief (see the trade-off-budget
   note above) — its cost is stated the moment it's proposed, not
   discovered after it ships. Preservation machinery is usually the part
   that breaks: a clamp is one expression at a read site, where preserving
   an out-of-range value means tracking which values are legacy, keeping
   them distinguishable from new ones, and stopping every write path from
   laundering one into the other. A delay slider's cap dropped from 300
   seconds to 30 once got a grandfathering path for triggers already armed
   above the new cap instead of a clamp — six times the code, three separate
   Critical findings across three review rounds, all of it deleted the
   moment the real cost was stated and dazewell said he'd never wanted it
   preserved. The replacement was a clamp at two boundaries: a net deletion
   of about 46 lines.

   Check `git show --stat` on a recent commit for the feature you're about
   to build something similar to — the diffstat is the target: a handful of
   files, most of the diff in new code, only a few lines touching anything
   pre-existing.

   **Temporary diagnostics for a new UI decision point.** When the change adds
   a decision point that determines whether something is shown, or which of
   several code paths ends up rendering the same screen, add temporary
   instrumentation right there logging the operands of the decision — and,
   where more than one path can produce the same screen, which path ran.
   Reading the diff cannot answer "which of these paths actually executed on
   the device"; a log line answers it in seconds. A feature that passed a
   local compile gate, an automated review, and two architect rounds still
   shipped unreachable once, because every one of those checks reasons about
   the diff and none of them can see the device state that decides which
   branch fires — a single log line at that branch would have settled it.

   **Log only what identifies the path, never what identifies the user.**
   "The operands of the decision" means booleans, enum/state names, ids and
   counts — never message text, a contact's name or number, a token, or
   anything else that would leave the device in this release-signed, uploaded-
   to-Telegram artifact. If a decision genuinely can't be logged without one of
   those, log that a branch was taken, not the value that chose it.

   **Use `Log.e`, `Log.i` or `Log.w` — never `Log.v` or `Log.d`.**
   `TMessagesProj/proguard-rules.pro` strips both from the release build via an
   `-assumenosideeffects` block, and the release-signed minified variant is the
   only one that ever reaches a device — the local debug compile gate can't
   catch this, because that rule applies only to the minified variant it never
   builds. Instrumenting with `Log.d` compiles clean, survives the compile
   gate, and vanishes from the APK dazewell installs: a full device test cycle
   was once spent proving only that the measurement didn't exist, and the
   resulting silence in `adb logcat` came within a hair of being read as
   evidence about the feature rather than about the log level.

   **Place diagnostics where you are uncertain, not inside the path you
   expect to be taken.** Instrumentation inside an assumed path can only
   confirm that assumption; when the assumption is wrong it yields silence,
   which is indistinguishable from broken tooling and teaches nothing. When
   the question is "which code path ran," log a stack trace at the observed
   symptom — `Log.e(TAG, "<label>", new Throwable())` where the user actually
   sees something happen — rather than a boolean at the place you believe
   produced it: a stack trace names the real call chain in one device run,
   where a probe inside a guessed path can take several.

   Put the instrumentation in its **own clearly-marked commit** using a
   **single tag literal chosen up front, embedded in the log message text
   itself, and written verbatim in the PR body** (e.g. `NAX_SMOKE_<slug>`) —
   not merely "a distinctive tag" described in prose, and not a tag that lives
   only in a commit message or the PR body. The verifier greps the head tree
   for that string, so if it isn't in the log call it has nothing to find —
   a tag nobody wrote into the actual diagnostic is a check against nothing.
   It comes back out once the smoke build below has answered the
   reachability question — as a new commit, never folded into the feature
   commits — and whoever verifies the branch before it lands confirms the
   literal tag is gone from the tree, not just from the commit history. This
   is proportional to the smoke build below: a change with no user-visible
   surface earns neither.

4. **Compile gate (local when the toolchain is there, CI when it isn't).**
   After any Java/Kotlin edit, run the compile check from the repo root:

   ```powershell
   .\gradlew.bat :TMessagesProj:compileDebugJavaWithJavac
   ```

   If the shell isn't at the repo root, pass `-p` explicitly:
   `.\gradlew.bat -p "D:\Documents\Projects\NagramX" :TMessagesProj:compileDebugJavaWithJavac`.
   A full APK build is heavy; this compile task is the standard gate and is
   enough to validate most feature work. Don't move on until it's clean. Run it
   in the worktree the change lives in, so it compiles the actual branch.

   **On `ZenBoo`, run it — the toolchain is installed and fast enough.** JDK 21
   and the Android SDK are both on the environment there (`JAVA_HOME` /
   `ANDROID_HOME`), so no `local.properties` is needed. Budget ~9 minutes for
   the first run of a session (cold daemon, no configuration cache) and ~15
   seconds for every run after a source edit — cheap enough that there's no
   excuse for shipping a change to CI unverified. Don't kill a cold run early
   because it looks stuck; the Kotlin and resource tasks dominate it.

   **If the toolchain isn't available — or the machine is too slow — skip the
   gate, don't fight it.** In a sandboxed agent environment the toolchain often
   isn't there (no Android SDK, no `local.properties`, no network for the Gradle
   distribution). Spend one quick check, not a setup project: is there a
   `gradlew`/`gradlew.bat` *and* an SDK (`ANDROID_HOME` / `ANDROID_SDK_ROOT` set
   or `local.properties` pointing at one) *and* a JDK? If yes, run the gate. If
   no — or if the first invocation fails on the environment rather than on the
   code (missing SDK, unresolvable dependency, no network) — stop there. Don't
   install an SDK, vendor dependencies, or otherwise build a toolchain just to
   satisfy the gate.

   Same answer on a slow machine even though everything's installed: on the
   Surface Book 2 the compile gate drags and a full `assemble` runs over an
   hour, so **don't start either one there**. Use CI as the gate and leave
   building an artifact to dazewell on a faster machine (see the machine note
   above).

   The fallback is **`ci.yml`**: push the branch and open the PR into `dev`
   (step 9), and `ci.yml` compiles it on every push. That run *is* the compile
   gate in that case — read its result and fix what it reports, same as a local
   failure. Say plainly in the PR body that the change is unverified locally and
   that CI is the gate, so nothing is installed on a phone on the assumption it
   compiled. Everything else in the loop is unchanged: round-2 review still
   happens, and a red `ci.yml` run blocks landing exactly as a red local compile
   would.

5. **Code review with the Chief Architect (round 2 of 2).** Once it
   compiles — or once it's pushed, when `ci.yml` is the gate — take
   the implemented diff back through review — the chief
   Android architect persona again, now reviewing real code, not a plan.
   Address the comments, re-review. This is a distinct second pass from the
   step-1 design review. Same `nagramx-code-review` skill as round 1; that
   skill spells out the fork-fit / Android-correctness / code-quality checks,
   the severity calibration, and the strengths-plus-issues-plus-verdict output
   format the reviewer returns.

   **Then review the whole, on the final state of the code — not the lines, and
   not commit-by-commit.** Round 2 as run above fixes local defects one at a
   time; it never asks whether the *accumulation* of those fixes is a design
   anyone should own, and a smell fixed in isolation can be hiding a design that
   went wrong. So once the branch is otherwise done (green, round-2 findings
   resolved), run two more passes over the *final* code, both defined in the
   `nagramx-code-review` skill: a **whole-feature review** ("would a maintainer
   be happy to own this?", not "is each line correct?") and an independent
   **craftsmanship pass** ("is this good code or a batch of hacks?"). These are
   what catch the bug that a dozen line-by-line passes fixed *around* — read the
   skill for what each checks, why the craftsmanship pass runs at least twice
   from model families different from the implementer, the architect, and each
   other, and
   how a split verdict is adjudicated. Whoever drives the change dispatches them;
   in the Copilot roster that is the nearest owning orchestrator for that unit
   (Phase 4) — under nested orchestrators that is the child orchestrator that
   owns the unit, not necessarily the root.

   **What triggers these is what happened, not just up-front size.**
   They're proportional — a one-line chore doesn't earn them — but reserve them
   for more than "looks substantial," because the initial estimate is exactly
   what misleads: a change scoped as the *simple* half of a split still hit 34
   commits and shipped a bug a dozen line passes missed. Run the full pass when
   the change touches concurrency / a media pipeline / lifecycle, **or** when it
   hit the automated-review round cap, **or** when repeated fixes clustered in one
   region, **or** when scope grew mid-flight after review already ran. A change
   that needed many rounds to stabilise is precisely the one whose whole nobody
   has read.

6. **Feature doc entry, and any codemap fact the change established — both ride
   with the change.** If the change is user-visible, write its entry for
   `FEATURES.md`: under the right `## section`, a `### Feature name` heading,
   then a plain-spoken description of what it does and how to use it, no
   marketing language, with an inline shortcut table or screenshot where
   neighboring entries do that. Run the prose through the `humanizer` skill so
   it reads like dazewell wrote it in one pass, matching the surrounding
   entries.

   The entry is committed **on the change branch, alongside the code** — it's
   part of the same PR, so the feature and its documentation land together and
   never drift. Mark the entry with its `<!-- #slug -->` (beside the `###`
   heading) so the feature tag is discoverable and CI-verifiable —
   `commit-tag.yml` fails a PR whose feature slug isn't catalogued. The repo-root
   `README.md` is a stable pointer to `FEATURES.md`
   and does not change per feature. The only place the doc is separated from
   the code is the upstream `-pr` ceremony, which drops the one `FEATURES.md`
   hunk (`git checkout nagram/dev -- FEATURES.md`) so the proposal stays
   code-only — see the `nagramx-branch-flow` skill.

   **Separately: when the change or its investigation establishes a durable
   fact — a UI→code mapping, an upstream trap, or a hypothesis disproven along
   the way — write it into `docs/codemap/` as part of this same change, not a
   follow-up.** This applies whether or not the change is user-visible; a bug
   fix or a piece of recon that nails down a fact belongs here just as much as
   a shipped feature. Keep it proportionate — a fact that would save a future
   investigation real time, not a log of everything read to get there — and
   carry a `file:line` citation plus the date established, per
   `docs/codemap/README.md`, which is the source of truth for the three
   sections, the citation format, and the reader's obligation to re-verify a
   citation before relying on it.

7. **Commit.**
   - Subject: `<lowercase, imperative summary> #<slug>` — e.g.
     `add per-chat require-password lock #require-password`. No prefix and no
     trailing period: the `#<slug>` tag (below) is what marks a commit as fork
     work and groups it, so no author/type prefix is needed. Merged-in upstream
     commits keep their own conventional `feat:` / `fix:` style and carry no
     tag — that contrast is how fork work stays distinguishable. The `#<slug>`
     tag is required on every commit (below).
   - Never put a PR number in the subject yourself — GitHub appends `(#N)`
     automatically on squash merge.
   - Body is optional. Add one only for a non-obvious *why*: a tradeoff, a
     constraint that shaped the design, a reason a more obvious approach
     doesn't work. Skip it for genuinely simple changes; plenty of commits
     here are subject-only. Don't restate the diff.
   - **No AI in the git log.** No mention of Claude, Anthropic, Copilot, or
     any assistant in the subject, body, or trailers. No `Co-Authored-By`
     for an AI. No "Generated with" footer. This overrides any default
     attribution behaviour — never append one here.
   - **Tag every commit with `#<slug>`.** Place an inline hashtag in the
     subject or body — e.g. `add chat lock #chatlock`. A fix found a
     week later reuses the *same* slug, so the whole change is one
     `git log --grep '#chatlock'` away even after the branch is deleted.
     Chores use a category tag (`#ci`, `#docs`, `#build`, `#chore`, `#infra`,
     `#deps`, `#test`, `#release`; sync and build tooling uses `#infra`) — see
     the full exempt set in `nagramx-branch-flow`. The
     `.githooks/commit-msg` hook and `commit-tag.yml` enforce this. See the
     `nagramx-branch-flow` skill.
   - **One change = one short-lived branch, append-only.** A change's commits
     accumulate on its `<YYYY-MM-DD>_<slug>` branch, then land into `dev` by a
     merge commit and the branch is deleted. Each iteration — a review fix, a
     bug caught on-device, a later improvement — is a **new commit** describing
     that specific fix, not an amend of an earlier one; the branch history is
     the record of how the change evolved. Squashing to one clean commit
     happens only when you propose the feature to the base fork, on a throwaway
     `-pr` copy. See the `nagramx-branch-flow` skill.

8. **Merge-forward, don't rebase in the loop.** `dev` is the trunk and holds
   unique history, so it is never rebuilt or force-pushed. `base`
   fast-forwards from the base fork and is *merged forward* into `dev`;
   changes land by merge commits. Feature branches are **append-only too**: a
   review fix or a follow-up improvement is a **new commit**, not an amend +
   force-push (step 9), so the history shows how the change evolved. The one
   place rewriting happens is the throwaway `-pr` copy, rebased and squashed
   when proposing upstream. Syncing onto the base fork, resolving an upstream
   conflict in the `dev` merge, and the phone-triggered automation are covered
   in the `nagramx-branch-flow` skill.

9. **Open a PR into `dev` — that *is* the preview build (the default for a
   feature).** For a user-visible feature this is a standing step, not
   something to wait to be told: once the change is ready — the compile gate
   passed locally, or (working CI-only) the change is written and ready for
   `ci.yml`'s `pull_request` trigger to gate it — **commit → push → open the
   PR** so dazewell always has a way to get to a test build once one is
   actually warranted. The build
   dazewell tests on-device must be **`dev` + the change** (on top of the
   current fork state, alongside everything already landed). Open a PR from
   `<YYYY-MM-DD>_<slug>` into `dev` on `origin`: opening it, and every later push,
   triggers `ci.yml` (the fast Java/Kotlin validation gate — no APK).
   To get the on-device build, apply the **`build-apk`** label to the PR, which
   builds the PR **merge ref** (`dev` merged with the branch) as the
   release-signed **dual-package** APK and uploads it to Telegram (labelled a
   *test* build). **Prefer the label over dispatching `staging.yml` directly**
   — a dispatch builds the branch head as-is, not the merge ref, so it is a
   fallback with a condition attached, not an equivalent trigger; see
   `nagramx-branch-flow`'s "Test before landing" section for when a dispatch is
   safe and when it isn't. The label is auto-removed
   at the start of the run, so re-applying it requests a fresh build. dazewell
   installs the Unofficial variant over the daily app and tests from the
   uploaded artifact. `commit-tag.yml` also runs and blocks the PR if any
   commit lacks its `#tag`.
   **Request the verification build only once review has actually settled** —
   round-2 architect review clean, and any final-state pass clean, nothing
   Important or above outstanding — never against your own still-under-review
   commit. An APK requested earlier is stale the moment a later round finds a
   Critical, which is exactly what a build-then-review ordering produced
   once: dazewell installed and tested a build that three subsequent Critical
   findings then invalidated. Under the `nagramx-orchestrator` pipeline this
   request is the orchestrator's alone to make, at the point it is about to
   ask dazewell to install something — never the implementer's, and never on
   the strength of its own final head commit. Working solo, without that
   split, hold yourself to the same ordering: don't reach for the label until
   review is actually done.

   **A UI-facing change earns a second, earlier build first — the smoke
   build — and it narrows this rule rather than breaking it.** "One build,
   requested only after review settles" was written against a build meant to
   verify *behaviour*, and it still holds for that build unchanged. But a
   fully-reviewed feature has shipped unreachable before — every review round
   read the diff and confirmed the control renders when its precondition
   holds; none of them could see that the precondition was never true on a
   real device, because that isn't a code-reading question. So for a change
   that adds or alters anything a user can see or tap, once it compiles,
   request a build immediately and ask dazewell exactly **one** question:
   does the control appear, and can you reach it? Not correctness, not edge
   cases, not polish — reachability only, answered in the time it takes to
   press a button. Round-2 architect review and any final-state pass run
   **after** that check comes back positive, not before: there is no point
   reviewing the craftsmanship of code nobody can reach yet.

   The smoke build is disposable by design — expected to be superseded by
   whatever review finds, and never to be described as a build to verify
   behaviour against. The **verification build** above is unaffected: still
   the one build dazewell tests properly, still requested once, still only
   after round-2 and any final-state pass clear. A UI-facing change now costs
   at most two orchestrator-requested builds, one per purpose, never two for
   the same purpose — the implementer still requests neither. A change with
   no user-visible surface (CI, docs, an internal refactor) gets no smoke
   build at all; do not turn this into a build on every change.

   **When the build is up, ask for the test explicitly — never bury it in a
   handback.** A test request is a *blocking* request for dazewell's hands, and
   prose he has to read to the end to discover that is prose he may not get to
   for hours. The moment an APK is uploaded and there is something only he can
   check, issue an **explicit interactive prompt** (the `ask_user` tool, or the
   equivalent question surface for whatever tool you are running in) — the same
   mechanism used for a design question, for the same reason: it interrupts
   deliberately, it is visibly unanswered until he acts, and his reply comes back
   attached to the thing being asked. Mentioning the build in a paragraph does
   none of that and silently stretches the feedback loop.

   Make the prompt **specific and short**: which build to install (variant and
   the commit or PR it came from), the exact thing to try, and what a pass versus
   a fail looks like. "Please test the build" wastes the interruption. "Install
   the Unofficial build from PR #244, arm three videos on one trigger in one
   chat, then send that trigger from another account — all three should send one
   after the other" spends it well. Ask for one round of testing at a time; a
   list of six checks is a task, not a question, and it will stall.

   **This applies to every request that needs dazewell's hands or eyes**, not
   only APK installs: a screenshot for `FEATURES.md`, confirming a device-only
   behaviour, checking something in his own chats, or a decision that blocks the
   pipeline. If you need him, ask him — explicitly, one thing at a time.

   **When ADB is reachable, prove the path instead of inferring it — but never
   let ADB become mandatory.** Every smoke-build and verification-build test
   request is an **interactive choice**, not a bare question: at minimum
   `Ready with ADB` and `Proceed without ADB`. Don't start any `adb`/`logcat`
   process until dazewell picks the ADB option **and** separately confirms
   he's actually ready — picking the option only says the phone is reachable
   over WiFi, not that this is the moment to open a bounded capture window.
   `Proceed without ADB` continues exactly as the single visual question
   described above always has, and is graded `Evidence: visual-only (ADB
   unavailable)` — it must never be read as, or upgraded into, a claim of path
   proof or collateral-log cleanliness.

   **Smoke / `Ready with ADB`.** Before capture starts, predeclare one focused
   scenario and the interpretation of every outcome it can produce: a
   liveness/BEGIN marker absent means tooling or artifact failure, never a
   feature verdict; the expected marker absent while a forbidden/competing
   marker is present means the wrong path fired; the expected marker present
   but the completion marker absent means a partial capture — rerun, don't
   conclude from it; every path marker present proves traversal only, and a
   visual confirmation from dazewell is still required to call the behaviour
   itself correct. Start a bounded, owned logcat client (mechanics below), let
   him run the one scenario, stop capture promptly, and read the bounded log
   against that predeclaration. Record `Evidence: ADB-traced (<scenario/marker
   summary>)`. Once the smoke question is answered, remove the probes exactly
   as the rule above already requires, before round 2 continues — this cycle
   narrows *how* the reachability question gets answered, it does not change
   *when* removal happens.

   **Verification / `Ready with ADB`.** No planted probes exist at this
   point — they came out once the smoke question above was answered, and they
   never ride into this build; the build dazewell verifies behaviour on is
   the build that merges. ADB instead supplies a bounded collateral scan:
   `main,crash` buffers for the scenario window, filtered to the installed
   variant's package and its full PID set (including the `:nagramx` process,
   re-resolved if the app crashes or restarts mid-window). A fatal in that
   package during the window blocks; a non-fatal exception on or adjacent to
   the changed code is an Important finding; anything else is counted and
   non-blocking unless it names changed code or reproduces across two runs —
   don't demand a baseline capture from unmodified `dev` to compare against.
   Combine this scan with dazewell's own behaviour verdict and record both.
   **Verification / `Proceed without ADB`** is visual-only exactly as before,
   and states plainly `no collateral scan performed` rather than leaving that
   silent.

   **A later review or fix that changes the traced decision path gets a new
   cycle, never a reused verdict.** Plant a new, separately-declared
   diagnostics commit, run one new bounded trace cycle, remove it in another
   append-only commit, and only then continue — the same plant → capture →
   remove discipline as the first cycle, never carried forward against code
   that no longer matches what was traced.

   **Marker discipline.** Every temporary probe's message carries the exact
   family prefix `NAX_SMOKE_<slug>` — no ad-hoc NAX diagnostic family for this
   purpose. Declare, before capture, all four marker classes a scenario needs:
   a liveness/BEGIN marker at an unconditionally-reached point, carrying
   non-sensitive build identity (`BuildConfig.BUILD_VERSION_STRING`, which
   already embeds the commit's short SHA via `COMMIT_ID` —
   `TMessagesProj/build.gradle:24-26,125` — plus `BuildConfig.APPLICATION_ID`
   for the variant, the scenario id, and the account index where relevant);
   the expected path marker(s); the forbidden/competing path marker(s); and an
   END/completion marker. State expected counts and order before capture, not
   after reading the log. The logging rules from the diagnostics note in step
   3 apply unchanged: non-sensitive booleans/enums/ids/counts only, never
   message text, a name, a number, a URL, a token, or anything else
   user-identifying; avoid `onDraw`/scroll/per-message hot paths; each probe
   answers one predeclared uncertainty and is one-shot or rate-limited where
   it could otherwise repeat. When several code paths can produce the same
   UI, prefer the existing symptom-stack-trace technique
   (`Log.e(TAG, "<label>", new Throwable())` at the observed symptom, per step
   3) over guessing which path to instrument.

   **ADB mechanics live entirely in `nagramx-process-lifecycle` — this step
   does not restate them.** That skill's contract covers pinning the target
   device's serial, holding the exact process identity, keeping the capture's
   working directory and output under an absolute `$env:TEMP` path outside
   every worktree, a bounded capture window, prompt stop with bounded
   verification on success/failure/cancellation/timeout, never stopping by
   process name, never an unqualified `adb kill-server`, the capture-artifact
   cleanup obligation, and the process-ledger entry. Prefer timestamp-bounded
   reads over `logcat -c`, which destroys the device's existing buffer for
   every other consumer of it; detect WiFi truncation by the END marker's
   absence, not by a byte-count guess.

   **Collateral scope is Dazegram, not the phone.** The host-side filter may
   include a narrow allowlist of system tags that name the package
   (`AndroidRuntime`, `ActivityManager`, `ActivityTaskManager`, ANR, tombstone
   lines) — nothing broader. Raw ambient logcat can carry other apps' and even
   Telegram's own PII-adjacent lines that the planted-probe privacy rule above
   does not govern, which is exactly why the retention rule below exists.

   **Retention: raw captures are private, ephemeral, and never leave the
   session.** They live only under an absolute `$env:TEMP` path — never the
   repo, the worktree, a PR, a commit, `FEATURES.md`, or a codemap entry — and
   are deleted once analysis is done, as part of the same guaranteed cleanup
   that stops the logcat client (`nagramx-process-lifecycle`'s ephemeral
   capture-artifact rule). Never quote a raw ambient line outside the session;
   a report may carry only the declared marker lines plus a sanitized
   exception class and its top frame, payload elided. Record both the capture
   deletion and the process termination as evidence, not as an assumption —
   they are separate obligations and neither implies the other. If deletion
   fails, report it and block archival rather than treating a live,
   undeleted capture as harmless.

   **Cleanup verification is two independent checks, and neither substitutes
   for the other.** Grep the final head tree for both the exact declared
   `NAX_SMOKE_<slug>` literal **and** the bare `NAX_SMOKE_` prefix — a probe
   planted under a mistyped or wrong slug still needs to come out. Separately,
   inspect the final diff for any added `android.util.Log.` call that isn't
   explicitly declared permanent — a probe missing the family prefix entirely
   would pass the grep above and still be a leftover. Source cleanup,
   raw-capture deletion, and process termination are three separate things to
   verify; none of the three is evidence for the other two.

   **Ownership stays split, not duplicated.** This step owns *when, why, and
   how evidence is graded* (`Evidence: ADB-traced` vs `Evidence: visual-only`).
   `nagramx-process-lifecycle` remains the single normative copy for process
   identity, start/stop, and archive safety — this step points to it rather
   than restating its rules. The orchestrator owns prompting, capture,
   analysis, and evidence grading; the implementer owns planting and removing
   the diagnostics commit when instructed, exactly as it already does for the
   smoke build above.

   **Don't request the Copilot review — it is automatic.** The
   `dev no-force no-delete + Copilot review` repository ruleset requests it when
   a non-draft PR targets `dev`, so it arrives on its own. Every hand-request
   route below fails *silently* — these are the traps, **not** things to run:

   - `gh pr edit <n> --add-reviewer @copilot` — no-ops.
   - `gh api -X POST .../requested_reviewers -f "reviewers[]=copilot-pull-request-reviewer[bot]"`
     — returns **HTTP 200 with the reviewer dropped**, so it reads as success.
   - the bot isn't a `suggestedActors` entry, so it can't be discovered that way
     either.

   **Never confirm via `requested_reviewers`** — that endpoint stays empty *even
   after a review has landed and been submitted*, so reading it as "the review was
   never requested" is wrong. Confirm on the *reviews* endpoint, and **filter to
   the bot**: a bare `.[].user.login` also lists human reviewers and prior
   reviews, so it false-positives. Per the login gotcha below, match
   case-insensitively on a wildcard in PowerShell rather than in `--jq`:

   ```powershell
   @(gh api repos/<owner>/<repo>/pulls/<n>/reviews | ConvertFrom-Json) |
     Where-Object { $_.user.login -like '*copilot*' }
   ```

   A draft PR gets no review at all, so check that first
   if nothing arrives. It's a machine reviewer, not a substitute for the
   round-2 architect pass — treat its comments the same way (verify before
   acting). Details in the `nagramx-github-pr-copilot-review` memory note.

   **Wait for the review, then act on it.** Copilot posts a minute or two later,
   so don't move on assuming it's clean. Note the current Copilot review count
   as a baseline, then run a wait loop that blocks until a new one lands and
   prints the review body plus its inline comments — run it **synchronously,
   in the foreground**, not backgrounded: a session-attached process dies when
   the session goes idle, so backgrounding this and ending your turn loses it
   silently instead of notifying you. See the `nagramx-process-lifecycle`
   skill for the general rule this follows. Login gotcha: the *reviews*
   endpoint lists Copilot as
   `copilot-pull-request-reviewer[bot]`, but the inline *comments* endpoint lists
   it as `Copilot` — an exact-match filter on one silently returns 0 on the
   other, so match case-insensitively on a wildcard. Filter in PowerShell, not
   in `--jq`: this shell strips the inner quotes out of a jq string literal, so
   `--jq '...=="Copilot"'` dies with `function not defined: Copilot/0` — and with
   `2>$null` swallowing it, the loop just spins out its full deadline and reports
   no review on a pull request that was reviewed minutes ago.

   ```powershell
   $owner='<owner>'; $repo='<repo>'; $pr=<n>; $base=<baseline count>; $deadline=(Get-Date).AddMinutes(20)
   function Get-CopilotReviews {
     (gh api "repos/$owner/$repo/pulls/$pr/reviews" | ConvertFrom-Json) |
       Where-Object { $_.user.login -like '*copilot*' }
   }
   while ((Get-Date) -lt $deadline) {
     if (@(Get-CopilotReviews).Count -gt $base) { break }
     Start-Sleep -Seconds 20
   }
   Get-CopilotReviews | Select-Object -Last 1 |
     ForEach-Object { $_.state; $_.submitted_at; $_.body }
   (gh api "repos/$owner/$repo/pulls/$pr/comments" | ConvertFrom-Json) |
     Where-Object { $_.user.login -like '*opilot*' } |
     Sort-Object created_at | Select-Object -Last 3 |
     ForEach-Object { "$($_.path):$($_.line)`n$($_.body)`n---" }
   ```

   Then triage its findings like any review — but **the automated reviewer
   re-fires on every push to the PR**, so you cannot hold it to "one pass": each
   fix you push triggers another review and another ~18-minute dual-package
   build, and each review tends to surface a fresh low-severity nitpick, so the
   loop does not terminate on its own. Bound it yourself, unilaterally, without
   waiting for anyone to intervene:

   - **Severity floor.** Triage the first review once, then act **only on
     findings at Important or above** — data loss, a crash, a race with a
     user-visible consequence, or a wrong-behaviour regression. Nitpicks, naming
     preferences, comment suggestions, and speculative defensive guards are
     **not** grounds for another commit; record them and move on. Asking a
     machine to "find problems" manufactures Minor ones indefinitely.
   - **Round cap.** Make **at most two** automated-review-driven push cycles.
     If Important-or-above findings remain after the second, **stop and report**
     rather than fixing again — anything still surfacing after two cycles is
     either churn or a signal the design needs revisiting (the repeated-fix
     trigger in `nagramx-code-review`), not another line fix. Two matches the
     architect re-review cap: the first cycle lets a genuine Important finding
     that only appeared after the first fix still land; a third almost never
     buys correctness, only build cost.

   This is the implementer's own responsibility — it applies the floor and the
   cap without an orchestrator noticing and stepping in. Fixes go in as **new
   commits** — don't amend and force-push, and don't write "address Copilot
   review"; say what the commit actually changes and tag it `#<slug>` (see
   *Follow up with a new commit* in `nagramx-branch-flow`). Do **not** re-request
   the reviewer to "check the fix": the push already re-fired it, and
   re-requesting only adds passes. The round-2 architect review and the
   final-state passes (step 5), not this machine loop, are the real quality gate.

   Iterate by pushing to the branch (each push re-runs `ci.yml`, the fast
   validation gate, and supersedes a run still going on that PR rather than adding
   to it). On a no-go, fix it and push another commit describing that fix.
   `ci.yml` path-ignores pure doc / hook / agent-and-skill
   pushes, so a `FEATURES.md`-only tweak won't run the gate. The release-signed
   APK is a separate, on-request step (the `build-apk` label or a dispatch) — the
   push no longer builds or uploads one.

   **Batch a review round's fixes, and request an APK once per round.** A push
   now only costs a fast `ci.yml` run, so pushing per fix is cheap — but each
   time you apply the `build-apk` label you trigger a dual-package build and a
   Telegram upload, and enough of those in a row trips the bot's flood limit.
   Work through a review round's findings, push them (the gate re-runs and
   supersedes itself via its concurrency group), and request the APK once, at the
   end, so the build dazewell installs reflects the whole round. Batching bounds
   the cost *within* a cycle; the round cap above bounds the *number* of cycles —
   they're two different limits and you want both. If you genuinely need an APK
   mid-round to check something, say so and take it as a deliberate exception.

   **Absorb mid-flight scope growth cheaply — don't re-review after each
   addition.** A change legitimately grows while it's in flight (a hotfix, then
   the same feature extended, then a follow-on tweak) — that's a fine way for
   dazewell to work and isn't to be discouraged. But each addition lands on an
   already-reviewed diff, and re-running review and a build after every one is
   where cost explodes. When a scope addition is still settling, **hold the
   review and the build until it's stable**, then review and build the combined
   state once, rather than paying a full round per increment.

   **Close every GitHub review point.** Each inline comment or review thread
   must get either a code fix or an explicit reply explaining why it will not
   be changed. After replying, resolve the thread. Before declaring the PR
   complete, verify that no review threads remain unresolved; do not leave
   dazewell to infer whether a comment was seen.

10. **Land it / propose it.** Landing a finished change into `dev` is a
    **merge** — mark the PR from step 9 ready and merge it with a **merge
    commit, never a squash-merge** (or, if you skipped the PR, a local
    `git merge --no-edit <YYYY-MM-DD>_<slug>`) — so the change's commits and their
    `#tags` stay whole and `dev` never needs a force-push. The merge into `dev`
    triggers `staging.yml` (the signed dual-package build + Telegram upload).
    Then **delete the branch** unless it's an upstream candidate. A squashed
    single commit is reserved for the separate act of **proposing the feature
    to the base fork** (`risin42/NagramX`), on a throwaway `-pr` copy that also
    drops the `FEATURES.md` hunk. The no-AI-in-the-log rule applies to every PR
    title/body. See the `nagramx-branch-flow` skill for both flows.

### Commit/PR by default vs. ask first

- **A user-visible feature is committed and PR'd by default** — don't wait to
  be told. Finish the pipeline (compile gate — local or `ci.yml`, round-2
  review), then commit, push, and
  open the PR into `dev` with a Copilot review requested (step 9). That's how
  dazewell gets the on-device test build; making it a standing step is the point.
- **CI/workflow tweaks, bug fixes, and small/chore items keep the lighter
  touch** — commit only when asked, and a PR is optional (they can land via a
  local merge into `dev`, or be shortcut entirely). Don't force a full
  branch+PR ceremony on a one-liner unless dazewell wants the on-request APK build.
- Either way, still **only push to shared branches when that's the intent** —
  the default-PR rule is about not re-asking for *feature* work, not about
  pushing chores nobody requested.

## Priorities: protect the irreplaceable thing first

Rank every decision on a change by what it *risks*, not by what it improves.
dazewell's framing settles most of these arguments cheaply: *"if it does not
introduce more risks of losing video — because it can sometimes be unique — I'm
fine."* Some artifacts a change touches are **irreplaceable** — a round-video
message, a draft, a queued send — and losing one is unrecoverable in a way a bug
is not. So the ranking, high to low:

1. **Risk of losing or corrupting the irreplaceable thing** — the recording, the
   message, the user's data. Nothing below outranks it.
2. **Behavioural correctness the user would notice** — right output, no crash,
   no wrong-account leak.
3. **Maintainability and elegance** — lean code, a clean state machine, fewer
   moving parts.

(3) is real and worth doing, but it is **never worth a regression risk on green
code.** Name the irreplaceable thing for the change at hand up front and rank
accordingly. The corollary that's easy to forget: **a cleanup pass on working
code is not free** — it risks introducing the very class of bug it's tidying
around, so a refactor of a load-bearing path clears the same bar as a feature,
not the lighter bar of "just cleanup." The review skill applies the same ranking
when it calibrates severity.

## Verifying a claim about the system

Most of what goes wrong here is not a bad decision — it is a true-sounding
sentence nobody checked. These rules are cheap and they are what catch it.

**When you cannot observe a property directly, do not pick the best single
proxy — pick two whose failure modes are independent.** Searching for the
*best* proxy has no good answer: one proxy is always defeatable in its own
dimension, and optimising it only makes the residual failure rarer and more
surprising. The useful question is "what does this evidence fail to see, and
what sees that." Example: to confirm a fix propagated through a merge, use
**ancestry** (`git merge-base --is-ancestor <sha> HEAD`, which no wording can
defeat) **and** **content** (the guard is present in the file, which no bad
merge resolution can fake). Neither is the property; together they bracket it.

Two checks on the pair, in this order:

- **Are they at different layers?** Topology, text, process, time, external
  observation. Layers are enumerable; failure modes are not, which is why this
  check is safe to rely on. "CI is green" and "the merge commit exists" are
  both *did the pipeline do its thing* — same layer, shared failures by
  construction.
- **Can you name a single failure that defeats both?** If yes, they are not
  independent — pick again. Cheap, and it catches the obvious case.

**Grade a failing check as *insufficient* or *inverted*, because they need
opposite responses.** An insufficient check misses some failures and leaves you
appropriately uncertain — supplement it. An **inverted** check passes
*specifically in the failure case*, because the thing it keys on is the
signature of what is going wrong; it manufactures confidence exactly where
alarm is warranted. **Delete an inverted check**, never supplement it: a
passing check nobody has invalidated will be cited later by someone who was not
here. The same applies to a check with a *guaranteed false positive* — it
trains you to explain rows away, and that habit cannot distinguish the noise
from a real finding.

**Pre-commit to what *both* outcomes would mean, before you look.** This is
routinely done only for the outcome you fear, which is backwards — **the result
you are relaxed about is the one whose interpretation goes unexamined, and it
is the one you will quote later.** A review section dispatched knowing a
negative result would indict the *comment* must not have its positive result
read as vindicating the *design*.

**Check the scope of a claim against the claim, not against what you happened
to be looking at.** A correct trace of one configuration is not a property of
the system; an audit of the file you edited is not an audit of the property.
Both errors have the same cause — the scope came from the work in front of you
rather than from the sentence you are about to write.

**A sufficient explanation is not the cause.** When a symptom is
over-determined, every mechanism you find fully accounts for it, so each one
*feels* complete — nothing is left unexplained. The tell is not weak evidence;
it is that more than one mechanism could produce this symptom. Ask that
question explicitly before concluding, and when two candidates are **in
series**, note that a test of the first is informative in one direction only:
if nothing reaches the second, you have learned nothing about it while
believing you ran the decisive test.

**A contradiction between two sources is information, not noise.** Two
plausible statements that cannot both be true will sit there indefinitely
because letting them coexist costs nothing visible, and resolving one always
looks like a detour. Treat it as work. On this repo it has repeatedly been the
thing that surfaced the real cause — including a user report that contradicted
a decisive code trace, where **both were right**: the code had not changed, and
the behaviour had, because a fresh install reset a setting.

**Verify claims about process infrastructure as rigorously as claims about
code.** They are just as falsifiable and far less scrutinised — a prediction
about how CI, a ruleset, or a tool behaves reads exactly like a report of it
having happened, especially when it comes from a source that has been reliable
all day. Watch it happen, or say you have not.

**Before dispatching an instruction to an agent, check it against the
constraints you are the one enforcing.** A human implementer who holds the
append-only rule reads "amend the pushed commits" and refuses; an agent
complies. The safety property a human executor provides for free is absent, so
the check moves upstream into instruction-writing. **Trigger on the
operation's *effect*, not on whether it names a git verb** — *does this create,
move, delete or rewrite a ref, a worktree, or history?* The most destructive
tools here (`rename_branch`, `archive_session`, `create_session` with
`base_branch`) contain no git verb at all.

## Coding conventions

- **Reuse over reinvention** (see step 3) — the most important one.
- **Keep code lean.** Short, clean, meaningful. Remove unused code,
  constants, and variables. Avoid needless indirection or abstraction.
- **Comments: only the non-obvious.** Don't restate what a signature or
  line plainly does; do explain tricky parts and the context that makes a
  line matter. Write them in dazewell's voice (run through `humanizer`) —
  avoid AI-flavored phrasing (em-dash pile-ups, rule-of-three,
  "ensures" / "seamlessly"). Prefer colons/parentheticals over em-dashes.
  Never reference AI in a comment (source is part of the no-AI line above).
- **Match the surrounding code's** style, naming, and idioms.

## Packaging & CI (staging)

The staging pipeline (`.github/workflows/staging.yml`) builds **two** APKs
from one source via a matrix, overriding the gradle property `APP_PACKAGE`
with `-PAPP_PACKAGE=`:

- `nekox.messenger` → **Unofficial** (`DazegramX-Unofficial-…`)
- `org.telegram.messenger.beta` → **Official** (`Dazegram-Official-…`)

Default/local builds are single-package
(`APP_PACKAGE=nekox.messenger` in `gradle.properties`); the label/filename
split lives in `build.gradle`'s `gramName`. The package name must track
`applicationId` so both apps coexist on one device (manifest contact
mimeTypes, `res/xml` accountType/targetPackage + contacts mimeTypes via
`resValue`, and code via `BuildConfig.APPLICATION_ID`). Gotcha: `resValue`
needs `buildFeatures.resValues = true` (enabled in the root
`build.gradle`); without it configuration fails with "defaultConfig
contains custom resource values, but the feature is disabled." Firebase:
`TMessagesProj/google-services.json` needs a client entry per
`package_name`.

**One publish pipeline, one validation gate.** `ci.yml` runs on every push to
`dev` and every PR into `dev` and compiles Java/Kotlin only — the fast gate that
gives intermediate work a quick signal. `staging.yml` is the sole thing that
signs, builds the native dual-package APK, and uploads to Telegram; it runs
unconditionally on push to `dev` (post-land delivery) and on request for a PR
(the `build-apk` label, or a manual dispatch). There is no separate debug
pipeline and no `[skip upload]`; the old `pr.yml` / `canary.yml` / `release.yml`
workflows are still gone — this split adds a validation gate, it does not restore
a second publish path. The upload step also posts an AI commit summary to
Telegram (GitHub Models via `GITHUB_TOKEN`); set the optional `AI_MODEL` repo
variable to override the default model.

## Keeping this current

This file is the living record of the process, not a one-time snapshot.
When dazewell corrects how something should work — a different convention,
a new step, a step that turns out to be unnecessary — edit this file
directly in the same session, don't just remember it in conversation. Keep
it in sync with the repo's `CLAUDE.md` (a thin pointer to this skill) and
the memory feature maps; the same change should land in all of them at
once. It should always reflect the process as currently practiced, the same
way `FEATURES.md` should always reflect the features currently shipped.

This file is committed in the repo at
`.claude/skills/nagramx-workflow/SKILL.md`, so it's discoverable as a
project skill for anyone working in NagramX. It may describe the process
openly — the only thing that must stay clean of AI is the git log and the
app's source (see the hard line at the top).
