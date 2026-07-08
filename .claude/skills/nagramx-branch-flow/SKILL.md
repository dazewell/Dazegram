---
name: nagramx-branch-flow
description: "Dazewell's git branch / integration / upstream-sync model for the NagramX fork (dazewell/NagramX, base fork risin42/NagramX). Trigger this for anything about *how commits are organized* rather than what the code does: starting a feature branch, the #tag every commit must carry, keeping a change's commits discoverable, proposing a feature upstream, syncing onto the base fork, the single staging build pipeline, avoiding force pushes, or the phone-triggered sync-build-Telegram automation. Companion to the nagramx-workflow skill: that one owns design review / hooks / compile gate / FEATURES.md / commit style; THIS one owns the branch topology and the plumbing around it. Also edit this file when dazewell corrects the flow."
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
  commit**). Upstreams merge *forward* into it. It holds unique history that
  exists nowhere else, so it is **never rebuilt and never force-pushed**.
- `base` — a mirror of the base fork (`source/dev`), **fast-forward only**.
  Merged forward into `dev` on every sync.

**Short-lived per change:**
- `<YYYY-MM-DD>_<slug>` (the date you start it, e.g. `2026-07-07_chatlock`) —
  one branch per change, cut from `dev`, PR'd into `dev`, then **deleted after
  merge**. Keep it alive *only* if you intend to propose that feature upstream
  (then it stays append-only).

**The tag that replaces permanent branches:** every commit carries an inline
`#<slug>` hashtag (e.g. `#chatlock`). That — not a surviving branch — is how you
find *all* commits for a change forever: `git log --grep '#chatlock'`. A fix
found a week later gets the same tag, so it stays tied to the feature even
though the original branch is long gone.

**The force-push rule, by category:**
- `base`, `dev` → **never** force-push (shared history everything depends on).
- feature branches → append-only while alive; the one sanctioned rewrite is the
  throwaway `-pr` copy at upstream-proposal time.

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
  commit id: "upstream"
  branch base
  commit id: "base = source/dev (ff-only)"
  checkout main
  commit id: "dev trunk"
  branch 2026-07-07_chatlock
  commit id: "add chat lock #chatlock"
  checkout main
  merge 2026-07-07_chatlock tag: "PR merge -> staging build"
  commit id: "merge base (upstream sync)"
  commit id: "fix edge case #chatlock"
```

## The #tag convention (discoverability)

Every authored commit carries an **inline `#<slug>` hashtag** somewhere in the
subject or body — e.g. `add chat lock #chatlock`. Rules:

- Feature commits use the **feature slug**; a fix weeks later reuses the *same*
  slug so the whole change is one `git log --grep` away.
- Infra/chore commits use a **category tag**: `#ci`, `#docs`, `#build`.
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
  feature slug isn't catalogued. Category tags (`#ci`, `#docs`, `#build`, …) and
  `*-fix` tags are exempt.

Bypass (`--no-verify`) only in a genuine emergency.

## The topology

Remotes (as configured in this clone):
- `origin` → `dazewell/NagramX` (personal fork)
- `source` → `risin42/NagramX` (base fork)

The base fork remote is named **`source`** locally; every command below uses
that. (`sync-upstream.yml` adds its own `source` remote on a fresh checkout, so
the name matches in CI too.)

- **`dev`** — the trunk / integration branch and the build source. `staging.yml`
  builds the dual APK from every push to `dev`. Never rebuilt, never
  force-pushed.
- **`base`** — mirror of `source/dev`, **fast-forward only**, merged forward
  into `dev` on sync. Never force-pushed.
- **`<YYYY-MM-DD>_<slug>`** — short-lived change branch. Cut from `dev`, PR'd in,
  deleted after merge (kept only for upstream candidates).

## Do I keep feature branches updated? (no — and usually don't keep them at all)

You don't rebase or "catch up" a change branch. It's cut from `dev`, carries its
commits (each `#slug`-tagged), gets merged, and is deleted. Upstream never has
to meet it, because upstream meets `dev` (via `base`), and the branch's commits
are already in `dev` once merged.

If a `dev` merge (an upstream sync) conflicts with something a landed feature
did, resolve it **in the `dev` merge commit** — there's no branch to rebase, and
even if one survives you don't touch it. `dev` is where reconciliation happens.

## Case-by-case procedures (PowerShell)

### Start a change
```powershell
git fetch source dev
git switch base; git merge --ff-only source/dev        # keep the mirror current
git switch dev;  git merge --no-edit base               # bring upstream into the trunk
git switch -c <YYYY-MM-DD>_<slug> dev                   # cut the change branch (date-stamped) from the trunk
git config core.hooksPath .githooks                     # once per clone, if not set
# ...nagramx-workflow steps: design review, hooks, compile, code review...
```
Commit with the `#<slug>` tag inline (the hook enforces it). If the change is
user-visible, its `FEATURES.md` entry goes **in this branch's commits**, next to
the code — it rides in with the feature (see `nagramx-workflow` step 6).

### Test before landing (preview build)
Open a PR from `<YYYY-MM-DD>_<slug>` into `dev` on `origin`. `staging.yml` runs on
the PR, builds the **merge ref** (`dev` + the branch) as the release-signed
dual-package APK, and uploads it to Telegram (labelled a *test* build). Push more
commits to iterate; each push rebuilds. This is the same artifact users get, not
a separate debug build. `commit-tag.yml` also runs and fails the PR if any commit
is missing its tag.

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

### Add a fix to an existing change (the "week later" case)
The original branch is usually gone — that's fine, the tag carries the link.
```powershell
git switch dev; git pull --ff-only origin dev
git switch -c <YYYY-MM-DD>_<slug>-fix dev
# ...implement, compile gate, review...
git commit -m "<what the fix does> #<slug>"   # SAME slug as the feature
```
PR it into `dev`, merge, delete. Now `git log --grep '#<slug>'` shows the feature
and its later fix together. If the fix is user-visible, update the `FEATURES.md`
entry in the same branch.

### Sync onto a new upstream (manual / from PC)
```powershell
git fetch source dev
git switch base; git merge --ff-only source/dev; git push origin base
git switch dev;  git merge --no-edit base                # upstream into the trunk
git push origin dev                                      # triggers staging build
```
If the merge conflicts, it's a landed feature's hook colliding with new upstream
code. Resolve it **in this `dev` merge commit** and push — never rewrite `dev`'s
history. This is the same thing the phone-triggered automation does; it just
bails to the PC when the merge isn't clean.

### Propose a feature to the base fork (the only place rewriting/force happens)
Only for a feature whose `<YYYY-MM-DD>_<slug>` branch you kept alive.
```powershell
git fetch source dev
git switch -c <YYYY-MM-DD>_<slug>-pr <YYYY-MM-DD>_<slug>   # throwaway copy
git rebase --onto source/dev <branch-point> <YYYY-MM-DD>_<slug>-pr   # replay onto pristine upstream
git checkout source/dev -- FEATURES.md                    # drop the fork-only doc hunk
git rebase -i source/dev                                  # squash to one clean commit
git push origin <YYYY-MM-DD>_<slug>-pr
gh pr create --repo risin42/NagramX --base dev --head dazewell:<YYYY-MM-DD>_<slug>-pr
```
Delete the `-pr` branch after the PR merges. The one file to strip is
`FEATURES.md` (dazewell's catalog, which the base fork doesn't have); everything
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
still build). The upload step also posts an **AI commit summary** (GitHub Models
via `GITHUB_TOKEN`, `models: read`) of the commits since the last successful
build on that branch; `Tools/scripts/upload.py` folds it into the Telegram
caption, trimming the commit message before the summary so the summary always
fits Telegram's 1024-char cap.

### Phone-triggered sync → build → Telegram (`sync-upstream.yml`)
Triggerable from the GitHub mobile app ("Run workflow") or a Telegram bot hitting
the `workflow_dispatch` REST API. It:
1. Fast-forwards `base` from `source/dev`.
2. `git merge --no-edit base` into `dev`.
3. **Conflict guard:** if the merge isn't clean → abort, reset `dev` to its
   pre-sync SHA, push nothing, and Telegram-ping `⚠️ sync blocked … needs the
   PC`. Exit non-zero.
4. Clean → `git push origin dev`, which triggers `staging.yml`.

There is no manifest and no topic re-merge loop — `dev` already contains the
landed features, so merging `base` forward is the whole job.

### Telegram → GitHub trigger (optional)
A bot command (or shortcut) that POSTs to
`/repos/dazewell/NagramX/actions/workflows/sync-upstream.yml/dispatches` with a
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
