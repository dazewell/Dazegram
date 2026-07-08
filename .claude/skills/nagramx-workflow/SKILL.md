---
name: nagramx-workflow
description: "Dazewell's process for adding or changing anything in the NagramX repo (NagramX, a personal Telegram-for-Android fork, GitHub dazewell/NagramX). Trigger this whenever work touches that repo: adding a feature, fixing a bug, touching README.md there, preparing a commit or PR for it, or when dazewell references \"the usual process\" for NagramX. Also trigger when dazewell gives new or corrected guidance about how this workflow should run — this file is meant to be edited, not just read. Covers: what NagramX is and its legacy-Java constraints, the reuse-first / minimal-footprint hook style with concrete hook points and config systems, the two review rounds (plan review before coding, code review after), the compile gate, coding conventions, commit/history conventions (no AI mentions in git logs or source, ever), README style, on-device testing, dual-package CI, and when a PR actually gets opened."
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
   not the mechanism.

2. **Branch.** Cut a short-lived change branch off `dev` (the trunk), named
   `dazewell/<short-slug>` — one change per branch. You PR it into `dev` and
   **delete it after merge**; keep it alive only if you'll propose that
   feature upstream. Don't commit directly to `dev`. The full topology, the
   `#tag` every commit carries, sync, and the force-push-free rules live in
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

4. **Compile gate.** After any Java/Kotlin edit, run the compile check from
   the repo root:

   ```powershell
   .\gradlew.bat :TMessagesProj:compileDebugJavaWithJavac
   ```

   If the shell isn't at the repo root, pass `-p` explicitly:
   `.\gradlew.bat -p "D:\Documents\Projects\NagramX" :TMessagesProj:compileDebugJavaWithJavac`.
   A full APK build is heavy; this compile task is the standard gate and is
   enough to validate most feature work. Don't move on until it's clean.

5. **Code review with the Chief Architect (round 2 of 2).** Once it
   compiles, take the implemented diff back through review — the chief
   Android architect persona again, now reviewing real code, not a plan.
   Address the comments, re-review. This is a distinct second pass from the
   step-1 design review.

6. **Feature doc entry — rides with the feature.** If the change is
   user-visible, write its entry for `FEATURES.md`: under the right
   `## section`, a `### Feature name` heading, then a plain-spoken
   description of what it does and how to use it, no marketing language, with
   an inline shortcut table or screenshot where neighboring entries do that.
   Run the prose through the `humanizer` skill so it reads like dazewell
   wrote it in one pass, matching the surrounding entries.

   The entry is committed **on the change branch, alongside the code** — it's
   part of the same PR, so the feature and its documentation land together and
   never drift. The repo-root `README.md` is a stable pointer to `FEATURES.md`
   and does not change per feature. The only place the doc is separated from
   the code is the upstream `-pr` ceremony, which drops the one `FEATURES.md`
   hunk (`git checkout source/dev -- FEATURES.md`) so the proposal stays
   code-only — see the `nagramx-branch-flow` skill.

7. **Commit.**
   - Subject: `dazewell: <lowercase, imperative summary> #<slug>` — e.g.
     `dazewell: add per-chat require-password lock #requirepassword`. No
     trailing period. The `#<slug>` tag is required on every commit (below).
   - Never put a PR number in the subject yourself — GitHub appends `(#N)`
     automatically on squash merge.
   - Body is optional. Add one only for a non-obvious *why*: a tradeoff, a
     constraint that shaped the design, a reason a more obvious approach
     doesn't work. Skip it for genuinely simple changes; plenty of commits
     here are subject-only. Don't restate the diff.
   - **No AI in the git log.** No mention of Claude, Anthropic, Copilot, or
     any assistant in the subject, body, or trailers. No `Co-Authored-By`
     for an AI. No "Generated with" footer. This overrides any default
     attribution behavior — never append one here.
   - **Tag every commit with `#<slug>`.** Place an inline hashtag in the
     subject or body — e.g. `dazewell: add chat lock #chatlock`. A fix found a
     week later reuses the *same* slug, so the whole change is one
     `git log --grep '#chatlock'` away even after the branch is deleted.
     Chores use a category tag (`#ci`, `#docs`, `#build`). The
     `.githooks/commit-msg` hook and `commit-tag.yml` enforce this. See the
     `nagramx-branch-flow` skill.
   - **One change = one short-lived branch.** A change's commits accumulate on
     its `dazewell/<slug>` branch, then land into `dev` by a merge commit and
     the branch is deleted. Squashing to one clean commit happens only when you
     propose the feature to the base fork, on a throwaway `-pr` copy. See the
     `nagramx-branch-flow` skill.

8. **Merge-forward, don't rebase in the loop.** `dev` is the trunk and holds
   unique history, so it is never rebuilt or force-pushed. `base`
   fast-forwards from the base fork and is *merged forward* into `dev`;
   changes land by merge commits. Nothing in the daily loop force-pushes. The
   only sanctioned rewrite is the throwaway `-pr` copy when proposing a feature
   upstream. Syncing onto the base fork, resolving an upstream conflict in the
   `dev` merge, and the phone-triggered automation are covered in the
   `nagramx-branch-flow` skill.

9. **Open a PR into `dev` — that *is* the preview build.** The build dazewell
   tests on-device must be **`dev` + the change** (on top of the current fork
   state, alongside everything already landed). Open a PR from
   `dazewell/<slug>` into `dev` on `origin`: opening it, and every later push,
   triggers `staging.yml`, which builds the PR **merge ref** (`dev` merged with
   the branch) as the release-signed **dual-package** APK and uploads it to
   Telegram (labelled a *test* build). dazewell installs the Unofficial variant
   over the daily app and tests from the uploaded artifact. `commit-tag.yml`
   also runs and blocks the PR if any commit lacks its `#tag`.

   Iterate by pushing to the branch (each push rebuilds + re-uploads). On a
   no-go, fix in place and push again; don't stack visible "fix review
   comments" commits. `staging.yml` path-ignores pure doc / `.github` pushes,
   so a `FEATURES.md`-only tweak won't rebuild. It's the same pipeline that
   runs on `dev` after landing — there is no separate debug build and no
   skip-upload switch.

10. **Land it / propose it.** Landing a finished change into `dev` is a
    **merge** — mark the PR from step 9 ready and merge it with a **merge
    commit, never a squash-merge** (or, if you skipped the PR, a local
    `git merge --no-edit dazewell/<slug>`) — so the change's commits and their
    `#tags` stay whole and `dev` never needs a force-push. The merge into `dev`
    triggers `staging.yml` (the signed dual-package build + Telegram upload).
    Then **delete the branch** unless it's an upstream candidate. A squashed
    single commit is reserved for the separate act of **proposing the feature
    to the base fork** (`risin42/NagramX`), on a throwaway `-pr` copy that also
    drops the `FEATURES.md` hunk. The no-AI-in-the-log rule applies to every PR
    title/body. Only commit/push when asked. See the `nagramx-branch-flow`
    skill for both flows.

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

- `nekox.messenger` → **Unofficial** (`NagramX-Unofficial-…`)
- `org.telegram.messenger.beta` → **Official** (`NagramX-Official-…`)

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
