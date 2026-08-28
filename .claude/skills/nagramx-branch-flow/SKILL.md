---
name: nagramx-branch-flow
description: "Dazewell's git branch / integration / upstream-sync model for the NagramX fork (dazewell/Dazegram, live upstream parent NextAlone/Nagram, formerly the archived base fork risin42/NagramX). Trigger this for anything about *how commits are organized* rather than what the code does: starting a feature branch, whether to work in a git worktree vs. in-place, the #tag every commit must carry, keeping a change's commits discoverable, proposing a feature upstream, syncing onto the base fork, the single staging build pipeline, the mandatory `<YYYY-MM-DD>_<slug>` branch naming, the no-force-push rule (follow-ups are new commits, not amends), or the phone-triggered sync-build-Telegram automation. Companion to the nagramx-workflow skill: that one owns design review / hooks / compile gate (and its staging-build fallback) / FEATURES.md / commit style; THIS one owns the branch topology and the plumbing around it. Also edit this file when dazewell corrects the flow."
---

# NagramX branch & integration flow

This is the **plumbing** layer for dazewell's fork. The `nagramx-workflow`
skill owns *what a change looks like* (design review, minimal hooks, compile
gate, code review, the `FEATURES.md` entry, commit style, no AI in the log).
This skill owns *where commits live and how they move* — the branch topology,
upstream sync, and the phone-triggered automation.

## Quick refresh (30-second version)

**The mental model:** `dev` is the **trunk** — the real, durable history of the
fork. Features land on it and stay on it. Upstream flows *into* `dev`. Feature
branches are short-lived scaffolding you delete after merging.

**Two long-lived branches:**
- `dev` — the trunk. Everything lands here (via a PR merged with a **merge
  commit**). Upstream merges *forward* into it through a guarded snapshot (see
  The topology and Automation). It holds unique history that exists nowhere
  else, so it is **never rebuilt and never force-pushed**.
- `nbase` — the upstream anchor: a chain of locally-authored snapshot commits
  carrying Nagram's trees, **append-only** and an ancestor of `dev`. Each new
  snapshot is merged forward into `dev` by the guarded sync. Never force-pushed,
  never deleted.

**Short-lived per change:**
- `<YYYY-MM-DD>_<slug>` (the date you start it, e.g. `2026-07-07_chatlock`) —
  one branch per change, cut from `dev`, PR'd into `dev`, then **deleted after
  merge**. Keep it alive *only* if you intend to propose that feature upstream
  (then it stays append-only). **The date prefix is mandatory** — an undated
  name like `video-cc` is wrong; see *Branch naming* below.

**The tag that replaces permanent branches:** every commit carries an inline
`#<slug>` hashtag (e.g. `#chatlock`). That — not a surviving branch — is how you
find *all* commits for a change forever: `git log --grep '#chatlock'`. A fix
found a week later gets the same tag, so it stays tied to the feature even
though the original branch is long gone.

**The force-push rule, by category:**
- `base`, `dev` → **never** force-push (shared history everything depends on).
- feature branches → **append-only by default. Don't amend, don't force-push.**
  A bug found in review, a follow-up improvement, a second iteration — each gets
  its **own new commit** with the same `#slug` tag (*Follow up with a new
  commit* below). The point is a readable history of how the artifact evolved,
  not ten pushes that all show the same commit message. Rewriting a feature
  branch happens only when dazewell explicitly asks for it (or something must
  genuinely be erased, e.g. a leaked secret or a bad blob), and then only with
  `--force-with-lease`.
- the throwaway `-pr` copy → rebased and squashed outright at upstream-proposal
  time. That's a separate branch made for that purpose, so it doesn't conflict
  with the append-only rule above.

**Worktree or in-place?** A change you expect to iterate on (back-and-forth:
multiple review rounds, on-device testing, fixes trickling in over days) gets its
own **git worktree** — a sibling folder (`..\NagramX-<slug>`) so the main clone
stays free to sync `dev` or start another change without stashing. A quick
one-shot (CI tweak, bug fix, little tuning) just cuts a branch in the main clone.
See *Worktree or in-place?* below.

**"Which command do I need?"** → *Case-by-case procedures* below.
**"How do I test before landing?"** → open a PR into `dev`; `staging.yml` builds
the `dev`+branch merge ref (release-signed dual APK) and uploads it to Telegram.
**"I need the whole rationale"** → *Why this model exists*.

## Why this model exists (the problem it solves)

An earlier model pretended `dev` was a disposable branch rebuilt from a manifest
of append-only topic branches. Reality never matched it: features were merged
into `dev` and their branches deleted, `dev` also merges other upstreams
(`NextAlone:dev`, `official/master`), and most shipped features have no
surviving branch. So `dev` *is* the source of truth and cannot be rebuilt
without losing history.

This model accepts that. `dev` is the trunk. The machinery that assumed
otherwise — the `integration-branches.txt` manifest, `register-topic.yml`, the
re-merge loop, and the "keep every topic forever for a clean `base..topic`
range" rule — is gone. Discoverability moved from *a branch per feature* to *a
tag per commit*, which survives branch deletion.

## The one tradeoff

The old model tried to give *every* feature a permanent, upstream-proposable
`base..topic` range. You don't keep branches, so you can't have that for free:

- **A feature you'll propose upstream** → keep its `<YYYY-MM-DD>_<slug>` branch
  alive (append-only). Its clean range stays available for the `-pr` ceremony.
- **Everything else** → delete the branch after merge. The record of "the whole
  change" is `git log --grep '#<slug>'` plus the merged PR. Good enough, because
  you're not proposing it.

You pay the "clean range" cost only for the features that earn it.

```mermaid
gitGraph
  commit id: "12.10.0 base"
  branch nbase
  commit id: "Nagram snapshot"
  checkout main
  commit id: "dev trunk"
  branch 2026-07-07_chatlock
  commit id: "add chat lock #chatlock"
  checkout main
  merge 2026-07-07_chatlock tag: "PR merge -> staging build"
  merge nbase tag: "guarded snapshot sync"
  commit id: "fix edge case #chatlock"
```

## The #tag convention (discoverability)

Every authored commit carries an **inline `#<slug>` hashtag** somewhere in the
subject or body — e.g. `add chat lock #chatlock`. Rules:

- Feature commits use the **feature slug**; a fix weeks later reuses the *same*
  slug so the whole change is one `git log --grep` away.
- Infra/chore commits use a **category tag**. The exempt set is fixed by
  `commit-tag.yml` and is exactly: `#ci`, `#docs`, `#build`, `#chore`, `#infra`,
  `#deps`, `#test`, `#release`, `#slug`, `#tag`, `#chatlock` — plus any tag
  ending `-fix`. Anything outside that set is treated as a *feature* slug and
  demands a `FEATURES.md` entry, so picking a descriptive-sounding tag like
  `#sync-land` for infrastructure work fails CI. **Sync and build tooling uses
  `#infra`** — that is the established convention, not a fallback: of the
  commits touching `.github/sync/` and the sync workflows, 27 use `#infra`,
  16 `#docs`, 3 `#ci`, and none use a feature-style slug.
- Merge commits are exempt (they're auto-generated).
- Put the tag **inline**, not alone at the start of a line — a line beginning
  with `#` can be stripped as a comment by git's editor cleanup.

This is enforced two ways, the same "everywhere" spirit as the no-AI-in-logs
line:
- **Local:** `.githooks/commit-msg` rejects a tagless commit. Enable it once per
  clone: `git config core.hooksPath .githooks` (committed hooks aren't active
  until git is pointed at them).
- **CI:** `.github/workflows/commit-tag.yml` fails a PR if any non-merge commit
  in it lacks a tag.
- **Catalogued:** a *feature* slug must also appear in `FEATURES.md`, marked
  `<!-- #slug -->` beside its entry heading; `commit-tag.yml` fails a PR whose
  feature slug isn't catalogued. Only the category tags listed above and `*-fix`
  tags are exempt. Note the harvest scans the **whole** `base..head` range and
  every commit's full message body, so once a wrong tag is pushed it stays in
  the range. Be precise about what that does and does not mean:

  - You **cannot reword it** — that needs an amend and a force-push, which the
    append-only rule forbids.
  - You **can still make CI pass**, by cataloguing the slug in `FEATURES.md`:
    a real entry if the work genuinely is a user-visible feature, or a bare
    `<!-- #slug -->` in the parked-slug block near the bottom otherwise.
  - But for chore work both remedies are bad. A real entry lies about what
    shipped and corrupts the catalog; a parked marker is honest but leaves
    permanent catalog debt for what was only a typo. If the work has not landed
    yet, a fresh branch is cleaner than either.

  So the tag is effectively immutable once pushed, and every way out costs
  something. Pick it right the first time.

Bypass (`--no-verify`) only in a genuine emergency.

## The topology

Remotes (as configured in this clone):
- `origin` → `dazewell/Dazegram` (personal fork; the repo was renamed from
  `dazewell/NagramX` — the old name still redirects but is not something to build
  on, so every command and URL uses `dazewell/Dazegram`)
- `nagram` → `NextAlone/Nagram` (the **live upstream parent**)

Branches (on `origin`):
- **`dev`** — the trunk / integration branch and the build source. `staging.yml`
  builds the dual APK from every push to `dev`. Never rebuilt, never
  force-pushed.
- **`nbase`** — the upstream anchor. A chain of locally-authored *snapshot*
  commits, each carrying a Nagram tree and parented on the previous snapshot. It
  is an ancestor of `dev` (the anchor merge made it one), **append-only**, never
  force-pushed, never deleted.
- **`base`** — the frozen mirror of the risin42-era fork at commit a6c7d0ae, no
  longer part of the sync, kept for historical reference only.
- **`<YYYY-MM-DD>_<slug>`** — short-lived change branch. Cut from `dev`, PR'd in,
  deleted after merge (kept only for upstream candidates).

Upstream now flows from **`nagram`** through a snapshot into `dev`. The full sync
topology (anchor, snapshot, the `nbase` chain, and the guarded merge strategy)
lives in **`.github/sync/README.md`**.

## Branch naming (mandatory — the date prefix is not optional)

Every change branch is **`<YYYY-MM-DD>` + separator + `<slug>`** — lowercase, the
**date prefix mandatory**, either `_` or `-` between the date and the slug,
hyphens inside the slug. Both forms are valid and equal:

```
2026-08-05_video-cc          2026-08-05-video-cc          2026-08-06_ci-tag-check
```

- **Date first, always.** It's the date you *start* the branch, so the branch
  list reads chronologically and stale scaffolding is obvious at a glance. A
  name with no date (`video-cc`, `fix-expand-button-edit-mode`,
  `composer-select-all`) is **wrong** — that's the most common slip and it has
  to be caught before the first push.
- **Separator between date and slug is flexible — `_` or `-`, both fine.** An
  underscore reads slightly cleaner because it marks the date boundary at a
  glance and lets the slug match the `#tag` verbatim, so it's a mild stylistic
  preference — but it is **not required and not something to flag or "fix"**.
  Branch tooling normalizes names to kebab-case and flattens `_` to `-` (and
  refuses a second rename), so requiring the underscore was unsatisfiable
  through the normal path; a hyphen there is fully valid. Nothing in the stack
  objects to either form — git, GitHub PRs, `staging.yml`, and `commit-tag.yml`
  all handle both. No camelCase, no spaces.
- **Slug matches the commit `#tag`.** Branch `2026-08-05_video-cc` →
  commits tagged `#video-cc`. Same words, so the branch and the permanent
  `git log --grep` handle line up.
- **No owner prefix.** The branch name is exactly `<YYYY-MM-DD>_<slug>` and
  nothing more — no `dazewell-` in front. Older branches carry one
  (`dazewell-2026-08-05-video-cc`) because a tool prepended it automatically;
  that setting is off now, so new branches shouldn't have it. If a tool adds
  one anyway, drop it (rename, below) rather than adopting it.
- **The one thing that must never be dropped is the date.** A session/branch
  generator that insists on kebab-case will render the separator as a hyphen
  (`2026-08-05-video-cc`) — that's a valid name, leave it. **The date is the
  part that must never be lost**; if the generated name lost the date, rename
  it.
- **In an agent worktree session, `create_session` auto-names the branch with no
  date prefix at all** (e.g. `haptic-configuration`), and there `rename_branch`
  is one-shot and raw `git branch -m` is forbidden — so the rename must be the
  child's *first* action, before any commit. The orchestrator writes that into
  the kickoff prompt; see its `create_session` dispatch checklist in
  `.github/agents/nagramx-orchestrator.agent.md`.
- **Wrong name already created?** Rename it before it accumulates review
  history, rather than living with it:
  ```powershell
  git branch -m <old-name> <YYYY-MM-DD>_<slug>
  git push origin :<old-name>                              # drop the old remote ref
  git push origin -u <YYYY-MM-DD>_<slug>
  ```
  (Deleting and re-pushing a *branch ref* isn't a history rewrite — the commits
  are untouched, so this doesn't conflict with the no-force-push rule. If a PR
  is already open on the old name, just retarget/reopen it on the new branch.)

## The `coord-<slug>` coordinator branch (the one exception to the dated form)

A **child orchestrator** — an orchestrator dispatched as a session by another
orchestrator to own a whole unit of work — is not a change branch and does not
follow `<YYYY-MM-DD>_<slug>`. It writes no code and produces no commits of its
own; its session exists only to coordinate specialists and grandchild sessions.
Its branch is named **`coord-<slug>`** and nothing else:

```
coord-orchestrator-hierarchy          coord-chatlock
```

- **The leading `coord-` marker is reserved and must never be dropped or
  truncated.** Unlike the `_`/`-` separator flexibility that change branches
  enjoy, `coord-` is load-bearing: it is the one signal that tells a parent's
  dispatch-verification step (see the "Dispatching a child orchestrator"
  subsection in `.github/agents/nagramx-orchestrator.agent.md`) that the session
  it just created is a coordinator on the branch it expected, not a
  mis-dispatched change session. A coordinator branch missing its `coord-`
  prefix is a dispatch failure, not a name to tidy up.
- **It is ephemeral and local-only.** It is never pushed to `origin`, never
  committed to, and never opened as a PR. It exists purely for the coordinator
  session's own housekeeping so the session has a branch that isn't `dev`. The
  child orchestrator renames its auto-generated session branch to `coord-<slug>`
  as its **first action that changes branch state** (reads of the source-of-truth
  docs come before it; it is the first thing the child *mutates*), exactly as an
  implementer renames to its dated branch first — see the orchestrator agent
  file. It lives only in the
  coordinator's own worktree and is never pushed, so `archive_session` removes
  the worktree — but that does **not** delete the local `coord-<slug>` branch
  ref, which can survive the worktree and must be cleared before the slug is
  reused. Because the child's rename to `coord-<slug>` is one-shot and would
  collide with such a leftover, **clearing a stale `coord-<slug>` ref is the
  dispatching parent's job, done before it calls `create_session`** (see
  "Dispatching a child orchestrator" in
  `.github/agents/nagramx-orchestrator.agent.md`) — the child has no pre-rename
  window to clean up after itself. Deleting a stale one is not a history rewrite
  and does not touch the no-force-push rule.
- **It must never collide with or be mistaken for a `<YYYY-MM-DD>_<slug>` change
  branch.** A `coord-` branch carries no date and no change commits; a dated
  change branch never carries a `coord-` prefix. The two namespaces are
  disjoint on purpose: a name is either one or the other, never ambiguous
  between them. The actual code for a delegated unit still lands on ordinary
  dated `<YYYY-MM-DD>_<slug>` change branches — those are cut by the
  implementer sessions the child orchestrator dispatches, not by the
  coordinator branch itself.

## Do I keep feature branches updated? (no — and usually don't keep them at all)

You don't rebase or "catch up" a change branch. It's cut from `dev`, carries its
commits (each `#slug`-tagged), gets merged, and is deleted. Upstream never has
to meet it, because upstream meets `dev` (via `base`), and the branch's commits
are already in `dev` once merged.

If a `dev` merge (an upstream sync) conflicts with something a landed feature
did, resolve it **in the `dev` merge commit** — there's no branch to rebase, and
even if one survives you don't touch it. `dev` is where reconciliation happens.

## Worktree or in-place? (choosing where to work)

**Default to a dedicated worktree for feature development you expect to iterate
on.** "Back-and-forth" is the signal: a change that will go through both review
rounds, get an on-device test build, and likely take follow-up commits (or a
fix a few days later) earns its own checkout so the work-in-progress tree isn't
competing with `dev` syncs or a second change. Worktrees live as **sibling
folders** next to the main clone — `..\NagramX-<slug>` (e.g. `NagramX-multi-pin`
next to `NagramX`) — each on its own `<YYYY-MM-DD>_<slug>` branch.

**Skip the worktree for simpler stuff** — CI/workflow tweaks, a contained bug
fix, a little tuning, a docs-only touch. Those are quick, one-shot, and don't
benefit from an isolated tree; just cut a branch in the main clone (*Start a
change*, plain variant). When in doubt (is this a one-liner or will it grow?),
a worktree costs almost nothing and keeps the main clone clean, so lean toward
one for anything feature-shaped.

Worktrees share the clone's `.git` config, so `core.hooksPath .githooks` (the
`#tag` hook) applies automatically — no re-config per worktree. Remove the
worktree once its branch has landed and been deleted (*Land a change*).

## Case-by-case procedures (PowerShell)

### Start a change (plain — simpler / one-shot work)
```powershell
git switch dev; git pull --ff-only origin dev          # trunk already carries upstream via the guarded sync
git switch -c <YYYY-MM-DD>_<slug> dev                   # cut the change branch from the trunk; DATE PREFIX REQUIRED (e.g. 2026-08-05_video-cc)
git config core.hooksPath .githooks                     # once per clone, if not set
# ...nagramx-workflow steps: design review, hooks, compile, code review...
```
Commit with the `#<slug>` tag inline (the hook enforces it). If the change is
user-visible, its `FEATURES.md` entry goes **in this branch's commits**, next to
the code — it rides in with the feature (see `nagramx-workflow` step 6).

### Start a change in a worktree (iterative feature work — the default for features)
```powershell
git switch dev; git pull --ff-only origin dev          # trunk already carries upstream via the guarded sync
git worktree add -b <YYYY-MM-DD>_<slug> ..\NagramX-<slug> dev   # sibling folder on a fresh branch cut from dev; DATE PREFIX REQUIRED
cd ..\NagramX-<slug>                                    # work here; the main clone stays on dev
# ...nagramx-workflow steps: design review, hooks, compile, code review...
```
Same tag/commit/`FEATURES.md` rules as the plain variant — the hook and config
carry over from the shared `.git`. Build and PR from the worktree exactly as
normal. After the change lands and the branch is deleted, clean up — but only
once every process that ran in that worktree (Gradle daemon, adb, a dev
server) is stopped and verified gone, per the `nagramx-process-lifecycle`
skill; a directory handle still open there is exactly what turns this
removal into a partial one. **This `git worktree remove`/`prune` pair is the
release path only for a worktree you created and manage yourself outside the
app's session tooling** — a worktree backing an app-managed child session is
released solely through that app's `archive_session` operation (see the
lifecycle skill's orchestrator-side checklist); never run these commands
against a child session's worktree ahead of, or instead of, that call:
```powershell
cd ..\NagramX
git worktree remove ..\NagramX-<slug>                   # drop the sibling tree
git worktree prune                                      # tidy stale metadata (if the folder was already gone)
```

### Test before landing (preview build)
Open a PR from `<YYYY-MM-DD>_<slug>` into `dev` on `origin`. `staging.yml` runs on
the PR, builds the **merge ref** (`dev` + the branch) as the release-signed
dual-package APK, and uploads it to Telegram (labelled a *test* build). Push more
commits to iterate; each push rebuilds. This is the same artifact users get, not
a separate debug build. It doubles as the compile gate whenever the change was
written without a local Android toolchain — see `nagramx-workflow` step 4; in
that case say so in the PR body so no one installs a build that CI hasn't
confirmed yet. `commit-tag.yml` also runs and fails the PR if any commit
is missing its tag.

For a **user-visible feature this PR is opened by default** (don't wait to be
asked — it's how dazewell gets the test build); **CI/bug/chore work stays
optional** and can land straight into `dev` (below). When you open the PR, also
request a Copilot review:
```powershell
gh pr create --base dev --head <YYYY-MM-DD>_<slug> --title "<title>" --body "<body>"
gh api -X POST repos/<owner>/<repo>/pulls/<n>/requested_reviewers -f "reviewers[]=copilot-pull-request-reviewer[bot]"
```
`gh pr edit --add-reviewer @copilot` silently no-ops and `--json reviewRequests`
hides bot reviewers; confirm with
`gh api repos/<owner>/<repo>/pulls/<n>/requested_reviewers` (look for `Copilot`).
See the `nagramx-github-pr-copilot-review` memory note.

Every GitHub review comment must be closed before landing: reply with the fix,
or explain explicitly why it will not be changed, then resolve the thread.
Verify that no review threads remain unresolved.

### Follow up with a new commit (don't amend, don't force-push)
A review fix, a bug found on-device, a follow-up improvement — each lands as a
**new commit on top**, never by amending what's already pushed:
```powershell
# ...fix, re-run the compile gate...
git add <files>; git commit -m "<what this fix actually does> #<slug>"
git push origin <YYYY-MM-DD>_<slug>
```
The history should read as the story of the change: what was tried, what broke,
what was corrected. Ten pushes of the same amended message tell you nothing.
So write each follow-up commit message for what *that* commit does ("clamp pin
count to the free-tier limit #multi-pin"), not a generic "address review" or a
copy of the original subject. Each commit still carries the `#<slug>` tag, so
the whole story stays greppable, and the merge into `dev` keeps all of it.

Every push re-triggers `staging.yml` on the PR and supersedes the build still
running, so you still get one fresh test APK per push rather than a stack.

Rewriting (`--amend`, `rebase -i`, `push --force-with-lease`) is **off by
default**, even on a feature branch. Do it only when dazewell explicitly asks,
or when something must genuinely be erased from history (a committed secret, a
huge stray binary) — and then always `--force-with-lease`, never a bare
`--force`, so the push aborts if the remote moved under you. `dev` and `base`
are never force-pushed under any circumstances.

### Land a change
Mark the PR ready and **merge it with a merge commit (never squash)**, so the
change's commits — and their tags — stay whole in `dev`. The merge to `dev`
triggers `staging.yml` (the dual-package build + Telegram upload). Then **delete
the branch** (unless it's an upstream candidate). Doc-only / `.github`-only
pushes don't build (staging's `paths-ignore`), so a `FEATURES.md`-only follow-up
won't trigger a redundant build.

Landing locally instead of via PR:
```powershell
git switch dev
git merge --no-edit <YYYY-MM-DD>_<slug>     # merge commit -> staging.yml builds + uploads
git push origin dev
git branch -d <YYYY-MM-DD>_<slug>; git push origin --delete <YYYY-MM-DD>_<slug>   # unless upstream candidate
```
If the change lived in a worktree, remove it after the branch is gone —
process-lifecycle checks apply here too (`nagramx-process-lifecycle` skill),
not only when an agent session archives itself. As above, this manual
`git worktree remove` is for a worktree you're managing yourself; a child
session's worktree goes through `archive_session` instead, never this command:
`git worktree remove ..\NagramX-<slug>` (from the main clone).

### Add a fix to an existing change (the "week later" case)
The original branch is usually gone — that's fine, the tag carries the link.
```powershell
git switch dev; git pull --ff-only origin dev
git switch -c <YYYY-MM-DD>_<slug>-fix dev
# ...implement, compile gate (local, else the staging build on the PR), review...
git commit -m "<what the fix does> #<slug>"   # SAME slug as the feature
```
PR it into `dev`, merge, delete. Now `git log --grep '#<slug>'` shows the feature
and its later fix together. If the fix is user-visible, update the `FEATURES.md`
entry in the same branch.

### Sync onto a new upstream (trigger the guarded automation; PC only if it blocks)
The routine path is the guarded workflow, not a manual merge — it builds a
snapshot on `nbase`, 3-way merges it into `dev`, runs `sync-guard.ps1`, and
pushes `dev`+`nbase` atomically only if the guard is clean (see Automation).
Trigger it from the phone, or:
```powershell
gh workflow run sync-upstream.yml --repo dazewell/Dazegram
```
If the guard blocks — a new upstream path, a fork-sensitive double-modified file,
a conflict — it pushes nothing and pings Telegram. Finish that reconciliation on
the PC by hand, then land it in three ordered steps: a PR, a fast-forward, then a
second PR. Folding the anchor advance into the reconciliation PR — or otherwise
advancing `.github/sync/pins.env` before step 2 has actually moved
`origin/nbase` — is a guaranteed red `sync-guard-check`: its fixture
unconditionally fetches the live `origin/nbase` and asserts it equals the
`OLD_NBASE` pinned in the candidate's own `pins.env`; advance that pin first and
the assertion fails immediately, before any guard classification even runs.

1. **Reconcile and merge into `dev`.** PR the resolved merge commit — plus any
   inline fixes and a `FEATURES.md` entry if user-visible — into `dev` on its
   own. Leave `.github/sync/pins.env` untouched in this PR. Merge it.

   **Anything listed in `.github/sync/protected-paths.tsv` ends the merge
   pure-ours — never merged.** Those ~50 entries (the signing key, Firebase
   config, branding, the launcher icons, and the `.attheme` themes including
   `monet_dark`, `monet_light` and `amoled`) are defined by
   `.github/sync/README.md` as fork-owned paths that must stay **byte-identical to
   `dev`**, and the guard blocks on any of them moving.

   **This is not conflict-only guidance — the dangerous case is the one git
   never flags.** A protected file that upstream also touched can be
   *auto-merged cleanly*, with no conflict marker and nothing to resolve, and
   still come out non-identical to `dev`. `.attheme` files are plain `key=value`
   text, so they merge silently and successfully; that is exactly how
   `monet_dark` drifted once. So the check is on the **merge outcome, not the
   conflict list**: after resolving, verify every protected path against `dev`
   and restore any that moved, whether or not git asked you about it.

       git diff --name-only origin/dev -- $(tail -n +2 .github/sync/protected-paths.tsv | cut -f1)

   Anything that prints is a protected file the merge changed; restore it with
   `git checkout origin/dev -- <path>`. An empty result is the only passing
   state.

   Taking even one upstream-only line into such a file makes its pin stale and
   trips `PROTECTED PINS STALE`. **Do not "fix" that by repinning.** The guard's
   suggestion to repin is aimed at a feature branch that deliberately restyles a
   protected asset; inside a sync it would launder upstream content into a
   fork-owned file and defeat the protection. Repin only when dazewell has
   deliberately changed the asset himself, never to unblock a sync.
2. **Fast-forward `origin/nbase`** to the reconciliation's snapshot commit,
   non-force. Verify first that the old `nbase` tip is an ancestor of the
   snapshot *and* the snapshot is reachable from `dev`; only then push. This
   must happen before step 3 — it's what the guard's fixture reads.
3. **Cut a branch from post-merge `dev`** (after step 1 landed, not the
   pre-merge tip) and advance the anchor values in `.github/sync/pins.env`
   there. PR it, confirm `sync-guard-check` is green, merge. Branching from the
   pre-merge tip instead reproduces the reconciliation's own files as
   `UNCLASSIFIED` plus a `guard14: unexpected commit imported`, because that
   branch's `dev` history is missing the reconciliation commit the new anchor
   now expects.

Between step 2 landing and step 3 landing, expect a red `sync-guard-check` on
every branch and PR that still names the old anchor in its `pins.env` — that's
everything except the step-3 branch itself once it makes that edit — because
`dev`'s pins still name the old anchor while `origin/nbase` has already moved
past it. That's the known shape of the gap, not a break — keep the window short
and don't trigger the phone sync while it's open. **Never** fast-forward the
`base` branch into `dev`: that path is retired and bypasses the guard entirely.

### Propose a feature upstream (the only place rewriting/force happens)
Only for a feature whose `<YYYY-MM-DD>_<slug>` branch you kept alive. Upstream is
now `NextAlone/Nagram` (`nagram`), not the archived base fork.
```powershell
git fetch nagram dev
git switch -c <YYYY-MM-DD>_<slug>-pr <YYYY-MM-DD>_<slug>   # throwaway copy
git rebase --onto nagram/dev <branch-point> <YYYY-MM-DD>_<slug>-pr   # replay onto pristine upstream
git checkout nagram/dev -- FEATURES.md                    # drop the fork-only doc hunk
git rebase -i nagram/dev                                  # squash to one clean commit
git push origin <YYYY-MM-DD>_<slug>-pr
gh pr create --repo NextAlone/Nagram --base dev --head dazewell:<YYYY-MM-DD>_<slug>-pr
```
Delete the `-pr` branch after the PR merges. The one file to strip is
`FEATURES.md` (dazewell's catalog, which upstream doesn't have); everything
else in the range is just the feature.

## Automation

### The single build pipeline (`staging.yml`)
One workflow builds and uploads. It runs on:
- **push to `dev`** — the post-land dual-package build (Unofficial + Official),
  uploaded to Telegram as a *staging* build.
- **pull_request into `dev`** — builds the `dev`+branch merge ref (same
  release-signed dual APK) as the on-device *test* preview.
- **manual** (`workflow_dispatch`).

Upload always happens (no skip switch). Doc-only and `.github`-only changes are
skipped via `paths-ignore` (with an exception so edits to `staging.yml` itself
still build). A push onto a branch or PR that already has a build running
**supersedes** it: the `build` job carries a `concurrency` group keyed on the
branch/PR (plus the matrix package, since a job-level group is otherwise shared
across the whole matrix) with `cancel-in-progress`, so several rounds of review
no longer ship a pair of APKs each and trip Telegram's flood limit. Only the
build is cancellable, so a run that already reached the upload step finishes
posting. Nothing is lost from the changelog either: the AI summary spans commits
since the last *successful* run, so a superseded build's commits fold into the
next one. The upload step also posts an **AI commit summary** (GitHub Models
via `GITHUB_TOKEN`, `models: read`) of the commits since the last successful
build on that branch; `Tools/scripts/upload.py` folds it into the Telegram
caption, trimming the commit message before the summary so the summary always
fits Telegram's 1024-char cap.

### Phone-triggered sync → build → Telegram (`sync-upstream.yml`)
Triggerable from the GitHub mobile app ("Run workflow") or a Telegram bot hitting
the `workflow_dispatch` REST API. There are **no inputs** — the source repo
(`NextAlone/Nagram`) and branch (`dev`) are hardcoded, there is no branch
selector and no bypass switch.

It is **snapshot-mediated**, not a direct upstream merge. In outline:
1. Resolve Nagram/dev's tip; record its commit and tree SHA.
2. Build one **locally-authored snapshot** commit whose tree is Nagram's tree and
   whose only parent is the current `nbase` (this becomes the new `nbase`). No
   upstream commit, author or message is imported.
3. 3-way merge that snapshot into `dev`. Because `nbase` is an ancestor of `dev`,
   the merge base is the previous snapshot, so `dev` gets exactly Nagram's
   upstream delta. **A conflict aborts** — never auto-resolved.
4. Run `.github/sync/sync-guard.ps1` from the trusted `dev` checkout. It
   classifies every tree delta and blocks on anything unclassified: a new path, a
   protected-blob change, a signing/workflow/schema/layer change. The guard
   self-tests before it is trusted.
5. Only on a clean guard does it **atomically** push `dev` and `nbase` together
   (`git push --atomic` with a `--force-with-lease` on each), which triggers
   `staging.yml`. Any failure — topology, guard, token, conflict, atomic-push —
   pushes nothing and Telegram-pings `⚠️ … blocked … Finish on the PC`.

The first steady-state run is **expected to block** (Nagram's tip adds a new
`BRANDING.md`, an unreviewed path). That block is the guard working, not a bug.
The anchor only advances by a reviewed edit to `.github/sync/pins.env`, never by
the workflow itself. Full contract: `.github/sync/README.md`.

**Push token — must have Contents: write + Workflows: write, and there is no fallback.** The
snapshot carries `.github/workflows/*` (nbase legitimately holds Nagram's
`debug.yml`/`pr.yml`/`release.yml`), and the built-in `GITHUB_TOKEN` is
*structurally* forbidden from pushing under `.github/workflows/` — and it would
also skip the staging trigger. So the workflow **requires** the `SYNC_TOKEN`
secret (a fine-grained PAT with **Contents: write + Workflows: write** on
`dazewell/Dazegram`) and fails loudly if it is missing, rather than degrading to
`GITHUB_TOKEN`. If a sync fails on the push with `without 'workflows' permission`,
`SYNC_TOKEN` is missing or under-scoped — fix the secret.

### Telegram → GitHub trigger (optional)
A bot command (or shortcut) that POSTs to
`/repos/dazewell/Dazegram/actions/workflows/sync-upstream.yml/dispatches` with a
fine-grained PAT (Actions: read/write on this repo only). The mobile app's "Run
workflow" button already covers this with no extra infra.

## What this model does NOT have (removed)
- `.github/integration-branches.txt` manifest — gone.
- `register-topic.yml` — gone (nothing to register).
- `canary.yml`, `release.yml` — gone (unused; `staging.yml` is the only
  release-artifact pipeline).
- `pr.yml` — gone; PR builds are handled by `staging.yml`'s `pull_request`
  trigger, and they build the same release-level artifact, not a bigger debug one.

## Keeping this current
When the flow changes, edit this file the same session and keep `CLAUDE.md`, the
`nagramx-workflow` skill, and the memory maps in sync. The hard line still
governs: **no AI mentions in git logs (commit messages, PR titles/bodies,
trailers) or in the app's source.** This process doc may describe the flow
openly; the history and code may not.
