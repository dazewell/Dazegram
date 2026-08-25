---
name: nagramx-workflow
description: "Dazewell's process for adding or changing anything in the NagramX repo (NagramX, a personal Telegram-for-Android fork, GitHub dazewell/Dazegram). Trigger this whenever work touches that repo: adding a feature, fixing a bug, touching README.md there, preparing a commit or PR for it, or when dazewell references \"the usual process\" for NagramX. Also trigger when dazewell gives new or corrected guidance about how this workflow should run — this file is meant to be edited, not just read. Covers: what NagramX is and its legacy-Java constraints, the reuse-first / minimal-footprint hook style with concrete hook points and config systems, the two review rounds (plan review before coding, code review after), the compile gate and its staging-build fallback when no local toolchain exists, coding conventions, commit/history conventions (no AI mentions in git logs or source, ever), README style, on-device testing, dual-package CI, and when a PR actually gets opened."
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

   Check `git show --stat` on a recent commit for the feature you're about
   to build something similar to — the diffstat is the target: a handful of
   files, most of the diff in new code, only a few lines touching anything
   pre-existing.

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

   The fallback is the **staging build**: push the branch and open the PR into
   `dev` (step 9), and `staging.yml` compiles it on every push to the PR. That
   run *is* the compile gate in that case — read its result and fix what it
   reports, same as a local failure. Say plainly in the PR body that the change
   is unverified locally and that CI is the gate, so nothing is installed on a
   phone on the assumption it compiled. Everything else in the loop is unchanged:
   round-2 review still happens, and a red staging build blocks landing exactly
   as a red local compile would.

5. **Code review with the Chief Architect (round 2 of 2).** Once it
   compiles — or once it's pushed, when the staging build is the gate — take
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

6. **Feature doc entry — rides with the feature.** If the change is
   user-visible, write its entry for `FEATURES.md`: under the right
   `## section`, a `### Feature name` heading, then a plain-spoken
   description of what it does and how to use it, no marketing language, with
   an inline shortcut table or screenshot where neighboring entries do that.
   Run the prose through the `humanizer` skill so it reads like dazewell
   wrote it in one pass, matching the surrounding entries.

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
     Chores use a category tag (`#ci`, `#docs`, `#build`). The
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
   something to wait to be told: once it passes the compile gate (locally, or
   on CI once pushed) and round-2 review, **commit → push → open the PR** so dazewell always has a test build to
   install. The build dazewell
   tests on-device must be **`dev` + the change** (on top of the current fork
   state, alongside everything already landed). Open a PR from
   `<YYYY-MM-DD>_<slug>` into `dev` on `origin`: opening it, and every later push,
   triggers `staging.yml`, which builds the PR **merge ref** (`dev` merged with
   the branch) as the release-signed **dual-package** APK and uploads it to
   Telegram (labelled a *test* build). dazewell installs the Unofficial variant
   over the daily app and tests from the uploaded artifact. `commit-tag.yml`
   also runs and blocks the PR if any commit lacks its `#tag`.

   **Request a Copilot review when the PR opens.** `gh pr edit <n> --add-reviewer
   @copilot` doesn't resolve the bot (it no-ops), and the bot isn't a
   `suggestedActors` entry. Add it over REST:

   ```powershell
   gh api -X POST repos/<owner>/<repo>/pulls/<n>/requested_reviewers -f "reviewers[]=copilot-pull-request-reviewer[bot]"
   ```

   `gh pr view <n> --json reviewRequests` hides bot reviewers (it'll read `[]`
   even on success); confirm with `gh api repos/<owner>/<repo>/pulls/<n>/requested_reviewers`
   (look for `Copilot`). It's a machine reviewer, not a substitute for the
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

   Iterate by pushing to the branch (each push rebuilds + re-uploads, and
   supersedes a build still running on that PR rather than adding to it). On a
   no-go, fix it and push another commit describing that fix.
   `staging.yml` path-ignores pure doc / `.github`
   pushes,
   so a `FEATURES.md`-only tweak won't rebuild. It's the same pipeline that
   runs on `dev` after landing — there is no separate debug build and no
   skip-upload switch.

   **Batch a review round's fixes into a single push.** Every push triggers a
   dual-package staging build and a Telegram upload; `staging.yml` already
   cancels a superseded run via its concurrency group, so the actual cost comes
   from choosing to push separately rather than from the workflow itself. Work
   through all of one review round's findings locally, then push once — don't
   push a fix, wait for its build, then push the next. Batching bounds the cost
   *within* a cycle; the round cap above bounds the *number* of cycles — they're
   two different limits and you want both. If you genuinely need a build
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
  be told. Finish the pipeline (compile gate — local or staging, round-2
  review), then commit, push, and
  open the PR into `dev` with a Copilot review requested (step 9). That's how
  dazewell gets the on-device test build; making it a standing step is the point.
- **CI/workflow tweaks, bug fixes, and small/chore items keep the lighter
  touch** — commit only when asked, and a PR is optional (they can land via a
  local merge into `dev`, or be shortcut entirely). Don't force a full
  branch+PR ceremony on a one-liner unless dazewell wants the staging build.
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

**One pipeline for everything.** `staging.yml` runs on push to `dev`, on
pull requests into `dev` (building the `dev`+branch merge ref as the on-device
preview), and manually. Both event types produce the same release-signed
dual-package artifact — there is no separate debug pipeline, no `[skip upload]`,
and the old `pr.yml` / `canary.yml` / `release.yml` workflows have been
removed. The upload step also posts an AI commit summary to Telegram (GitHub
Models via `GITHUB_TOKEN`); set the optional `AI_MODEL` repo variable to
override the default model.

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
