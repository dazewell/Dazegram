---
name: nagramx-branch-flow
description: "Dazewell's git / integration / upstream-sync model for NagramX (dazewell/Dazegram; parent DrKLO/Telegram; NextAlone/Nagram proposal target; risin42/NagramX historical). Trigger for branches/worktrees, one change = one branch = one session, mandatory #tag, discoverability, upstream proposals, sync, ci.yml/staging.yml gates, <YYYY-MM-DD>_<slug> names, no force-push, follow-up commits, approved batch landing, and phone-triggered sync-build-Telegram automation. Companion to nagramx-workflow."
---

# NagramX branch & integration flow

Current model: **one change = one dated branch = one `nagramx-implementer`
session**. It runs start to finish with subagents. `nagramx-orchestrator` is only
for multiple independent changes or approved batch landing. No child
orchestrators; no coordinator branches.

## Topology

Remotes: `origin` is `dazewell/Dazegram`; old `dazewell/NagramX` redirects but
is not a command target. `nagram` is `.github/sync/pins.env`'s source, now
`DrKLO/Telegram`; `NAGRAM_*` names are legacy labels.

Branches: `dev` is trunk/build source/durable history; features and upstream
snapshots merge forward into it. Never rebuild/delete/force-push it. `nbase` is
append-only snapshot chain and `dev` ancestor; never force-push/delete it. `base`
is frozen risin42 `a6c7d0ae`. Change branches are short-lived
`<YYYY-MM-DD>_<slug>` from `dev`, PR'd to `dev`, auto-deleted at merge, and
recoverable via `refs/pull/<N>/head`; keep no upstream-candidate branch alive.
Full sync: `.github/sync/README.md`.

Removed: `.github/integration-branches.txt`, `register-topic.yml`, `canary.yml`,
`release.yml`, `pr.yml`.

## Branch names

Branch format is **`<YYYY-MM-DD>` + `_` or `-` + `<slug>`**, lowercase,
date first, hyphens in slug:

```
2026-08-05_video-cc          2026-08-05-video-cc          2026-08-06_ci-tag-check
```

Date required; `video-cc`, `fix-expand-button-edit-mode`, and
`composer-select-all` are wrong. `_` and `-` both valid; tooling may flatten
`_` to `-`; do not flag dated hyphen names. No camelCase, spaces, owner
prefix, or `coord-<slug>`. Slug matches:
`2026-08-05_video-cc` -> `#video-cc`.

Wrong name already created? Rename before review history accumulates:

```powershell
git branch -m <old-name> <YYYY-MM-DD>_<slug>
git push origin :<old-name>                              # drop the old remote ref
git push origin -u <YYYY-MM-DD>_<slug>
```

Deleting/re-pushing a branch ref is not history rewrite. Retarget/reopen any
PR. Do not rebase/catch up feature branches; resolve sync conflicts in
`dev`.

## Commit tags

Every authored non-merge commit has inline `#<slug>` in subject/body:
`add chat lock #chatlock`. Later fixes reuse it; `git log --grep '#chatlock'`
survives branch deletion. A line starting with `#` may be stripped.

Feature commits use the feature slug. Exempt category tags are exactly `#ci`,
`#docs`, `#build`, `#chore`, `#infra`, `#deps`, `#test`, `#release`, `#slug`,
`#tag`, `#chatlock`, plus any `*-fix`; sync/build tooling uses `#infra`. Anything
else must appear in `FEATURES.md` as `<!-- #slug -->`.
Merge commits are exempt. `.githooks/commit-msg` enforces locally after
`git config core.hooksPath .githooks`; `.github/workflows/commit-tag.yml`
enforces tags and catalogue. Harvest scans whole `base..head` and full bodies,
so a wrong pushed tag is effectively immutable; a real entry or parked marker are
costly escapes. Pick right first. `--no-verify` only in emergency.

No AI mentions in commit messages, PR titles/bodies, trailers, or app source. No
assistant `Co-Authored-By`.

## Issues and dispatch gates

Issues are for decided, unstarted work. Each records feature tag and unique
branch slug:

```
<!-- tracking -->
**Branch slug:** `eventschedule-edit`  |  **Feature tag:** `#eventschedule`  |  **Status:** deferred

> Blocked until PR #246 is merged and confirmed on-device.
```

Labels: `status:approved` means dazewell approved work; only he applies it.
`status:in-progress` means active session; `status:blocked` names a dependency;
`status:deferred` is parked. No approved label means proposal. Stop labels
outrank approval. Agents have dazewell's token, so they still must not approve.
Revisit if collaborators gain triage.

Mandatory preflight before issue work:

```powershell
$repo = 'dazewell/Dazegram'
$n    = 254              # issue number
$slug = 'eventschedule-bolt-refresh'   # the issue's declared branch slug

# Exact branch match; loose "*$slug*" false-positives and "_" anchoring misses
# names because branch tooling normalises the separator to "-" on push.
$branchRe = "^\d{4}-\d{2}-\d{2}[-_]$([regex]::Escape($slug))$"

$blockers = @()

# 0. APPROVED BY DAZEWELL? No label, no work.
$issue  = gh issue view $n --repo $repo --json state,labels,assignees | ConvertFrom-Json
$labels = @($issue.labels.name)
if ($labels -notcontains 'status:approved') { $blockers += 'NOT APPROVED by dazewell' }

# 1. open, and no stop labels?
if ($issue.state -ne 'OPEN')                 { $blockers += "issue is $($issue.state)" }
if ($labels -contains 'status:in-progress')  { $blockers += 'already claimed (status:in-progress)' }
if ($labels -contains 'status:blocked')      { $blockers += 'blocked (status:blocked)' }
if ($labels -contains 'status:deferred')     { $blockers += 'deferred (status:deferred)' }

# 2. open PR? --limit is mandatory; gh defaults to 30 and can drop the match.
$pr = gh pr list --repo $repo --state open --limit 200 --json number,headRefName |
  ConvertFrom-Json | Where-Object { $_.headRefName -match $branchRe }
if ($pr) { $blockers += "open PR #$($pr.number) on $($pr.headRefName)" }

# 3. remote branch?
$branch = git ls-remote --heads origin |
  ForEach-Object { ($_ -split 'refs/heads/')[-1] } |
  Where-Object { $_ -match $branchRe }
if ($branch) { $blockers += "remote branch $branch" }

if ($blockers) { "DO NOT DISPATCH:`n - " + ($blockers -join "`n - ") } else { 'clear to dispatch' }
```

The block decides: no approval or any blocker means stop. Claim before work:

```powershell
# Hyphen: tooling flattens "_" on push, so "_" would name a branch that never appears.
$claimBranch = "{0:yyyy-MM-dd}-{1}" -f (Get-Date), $slug
gh issue edit $n --repo $repo --add-label "status:in-progress" --remove-label "status:deferred"
gh issue comment $n --repo $repo --body "Started. Branch: ``$claimBranch``"
```

Derive the date. Re-read after claiming; if another claim landed first, back off.
PR body uses `Closes #<n>`. Before reclaiming stale `status:in-progress`, prove
no matching remote branch, open PR, or live session; remove label and explain.

## Start and work

Use sibling worktree `..\NagramX-<slug>` for iterative features; main clone for
one-shot CI/workflow tweaks, small fixes, tuning, or docs. When unsure, use a
worktree. Hooks are shared.

```powershell
git switch dev; git pull --ff-only origin dev          # trunk already carries upstream via the guarded sync
git switch -c <YYYY-MM-DD>_<slug> dev                   # cut the change branch from the trunk; DATE PREFIX REQUIRED (e.g. 2026-08-05_video-cc)
git config core.hooksPath .githooks                     # once per clone, if not set
# ...nagramx-workflow steps: design review, hooks, compile, code review...
```

```powershell
git switch dev; git pull --ff-only origin dev          # trunk already carries upstream via the guarded sync
git worktree add -b <YYYY-MM-DD>_<slug> ..\NagramX-<slug> dev   # sibling folder on a fresh branch cut from dev; DATE PREFIX REQUIRED
cd ..\NagramX-<slug>                                    # work here; the main clone stays on dev
# ...nagramx-workflow steps: design review, hooks, compile, code review...
```

`FEATURES.md` rides in the same branch. Before self-managed worktree removal,
stop/verify processes per `nagramx-process-lifecycle`; app-managed worktrees use
only `archive_session`.

```powershell
cd ..\NagramX
git worktree remove ..\NagramX-<slug>                   # drop the sibling tree
git worktree prune                                      # tidy stale metadata (if the folder was already gone)
```

## PR, builds, and review

User-visible features open non-draft PRs into `dev` by default; CI/bug/chore PRs
are optional.

```powershell
gh pr create --base dev --head <YYYY-MM-DD>_<slug> --title "<title>" --body "<body>"
```

`ci.yml` compiles every PR push and is the no-local-tools gate; say so in the
body. `commit-tag.yml` also runs. Prefer `build-apk`: it builds the PR merge ref
(`dev` + branch), uploads a signed dual test build, and auto-removes itself.
Manual `workflow_dispatch` builds branch head as-is; use only if label fails,
after:

```powershell
git diff --name-only origin/<branch>...origin/dev -- 'TMessagesProj/src'
```

Empty means dispatch is app-source equivalent; non-empty means merge `dev` first.
Report trigger/ref. A workflow that never ran is not a pass.

Request behaviour verification only after review is clean. A UI-facing change
also gets one earlier smoke build after compile, for reachability only. The
implementer requests its builds/tests; orchestrators coordinate only batches.

Copilot review is automatic on non-draft PRs to `dev`. Do not request it:
`gh pr edit <n> --add-reviewer @copilot` no-ops; posting
`reviewers[]=copilot-pull-request-reviewer[bot]` returns HTTP 200 but drops it;
`--json reviewRequests` hides bots. Never use `requested_reviewers`; confirm via
filtered reviews:

```powershell
@(gh api repos/<owner>/<repo>/pulls/<n>/reviews | ConvertFrom-Json) |
  Where-Object { $_.user.login -like '*copilot*' }
```

A draft PR gets no review. Close every review point: fix or reply why not,
resolve, verify none remain.

## Follow-up commits

Review fixes, on-device bugs, and later improvements are new commits, not amends:

```powershell
# ...fix, re-run the compile gate...
git add <files>; git commit -m "<what this fix actually does> #<slug>"
git push origin <YYYY-MM-DD>_<slug>
```

Name the fix, not "address review". Push re-runs `ci.yml`; APK refresh needs
`build-apk`. Rewriting is off by default even on feature branches; use it only on
dazewell's request or to erase a real bad object, then only `--force-with-lease`,
never `--force`. `dev`, `nbase`, and `base` are never force-pushed.

## Landing

Default merge authority is dazewell. A root orchestrator may merge only under
`.github/agents/nagramx-orchestrator.agent.md`'s named approval: approved PRs,
gates re-verified, non-transferable, no `--admin`/`--auto`, `.github/sync/**`
excluded. Implementers never merge.

Squash PRs. Settings on 2026-09-10: `allow_merge_commit: false`,
`allow_squash_merge: true`, `delete_branch_on_merge: true`. GitHub makes one
`dev` commit from PR title plus branch messages; merge triggers `staging.yml`.
Never pass `--delete-branch`; deletion is automatic and safe:

```powershell
git fetch origin refs/pull/<N>/head:<local>
```

Preflight settings live:
`gh api repos/dazewell/Dazegram --jq '{squash:.allow_squash_merge, msg:.squash_merge_commit_message, title:.squash_merge_commit_title}'`.
Stop unless `allow_squash_merge` is `true` and `squash_merge_commit_message` is
`COMMIT_MESSAGES`; otherwise `#slug` tags can vanish with CI green.
`squash_merge_commit_title` is `PR_TITLE`, so PR title is permanent history.

Merge-time gate:
- Proceed only on `mergeStateStatus == CLEAN`, not `mergeable: MERGEABLE`. Poll
  with deadline; `UNKNOWN`, `BEHIND`, `UNSTABLE`, `BLOCKED`, `DIRTY`, other
  non-`CLEAN`, or timeout is stop-and-report. Never update/resolve while landing.
- Re-read `headRefOid` every poll. Under approval it must equal the approved SHA;
  a new commit aborts and reopens non-CI gates.
- Current head needs `ci.yml` `success` for code, or if path-ignored, required
  `Every commit carries a` green. Classify by live `.github/workflows/ci.yml`;
  absence alone is ambiguous.
- Re-verify same head: target `dev`, not draft, attribution greps clean,
  missing-`#slug` query clean, all review threads resolved. Paginate
  `reviewThreads`; `first:100` hides 101+.
- Merge with `gh pr merge <n> --squash --match-head-commit <headRefOid>`.
- Pass neither `--body` nor `--subject`; either can override `COMMIT_MESSAGES` /
  `PR_TITLE` and land an untagged `dev` commit.

### Batch landing

Order: declared blocker (`status:blocked` + `> Blocked until PR #<n> ...`),
stacked PR after base, same base file/hook point, feature before its `*-fix`.
Registry files (`FEATURES.md`, `strings_nax.xml`, `NaConfig.kt`,
`NekoConfig.java`, `docs/codemap/*`) are textual risk unless the hunk changes
behaviour; classify by hunk.

Before first and each later merge: re-read blockers; confirm no
`sync-upstream.yml` / `sync-land.yml` queued/in progress (query separately or
filter client-side; repeated `--status` does not OR); no open pins PR; candidate
`sync-guard-check` completed `success`; live `origin/nbase` matches that head's
`pins.env` baseline. Missing/pending guard is not green.

Merge back-to-back so `staging.yml`'s `staging-dev` `cancel-in-progress: true`
best-effort collapses uploads; cancelled runs are expected. Between merges, wait
for `dev`'s post-merge `ci.yml` on the just-merged commit to finish `success`.
After the batch, classify commits against live `.github/workflows/staging.yml`
`paths-ignore` (illustrative: `**.md`, `.github/**`, `docs/**`, `.githooks/**`,
not `.claude/**` except via `**.md`; not identical to `ci.yml`). Any non-ignored
path requires successful `staging-dev` with `Upload staging` green on final SHA,
or last non-ignored SHA if final merge was ignored. Record "no staging run
expected" only after proving all commits ignored. Explicit `build-apk`/dispatch
requires a matching successful run regardless.

### Rare local landing

Manual chore path only, never approval-based agent landing:

```powershell
git switch dev; git pull --ff-only origin dev
git merge --squash <YYYY-MM-DD>_<slug>      # stage the change, no commit yet
git commit -m "<summary> #<slug>"           # one commit; carry the slug so it stays greppable
git push origin dev                          # -> staging.yml builds + uploads
git branch -d <YYYY-MM-DD>_<slug>            # local only; a never-PR'd branch has no refs/pull recovery
```

A never-PR'd branch has no `refs/pull`; PR it if it might be proposed upstream.
Worktree cleanup still requires process-lifecycle checks.

## Later fix

Use a new dated fix branch and the same feature tag:

```powershell
git switch dev; git pull --ff-only origin dev
git switch -c <YYYY-MM-DD>_<slug>-fix dev
# ...implement, compile gate (local, else `ci.yml` on the PR), review...
git commit -m "<what the fix does> #<slug>"   # SAME slug as the feature
```

PR to `dev`, merge, delete. If user-visible, update the existing `FEATURES.md`.

## Sync from upstream

For new pinned `NAGRAM_REPO` / `NAGRAM_BRANCH`, run:

```powershell
gh workflow run sync-upstream.yml --repo dazewell/Dazegram
```

Replacing parent is attended re-anchor in `.github/sync/README.md`, never
`sync-land`; `-LandCheckOnly` rejects non-`ANCHOR_SRC` descendants. This no-input
workflow snapshots pinned source, merges into `dev`, aborts on conflict, runs
`.github/sync/sync-guard.ps1`, then atomically pushes `dev`+`nbase` only if
clean. Failures push nothing and ping `⚠️ … blocked … Finish on the PC`. Pins
advance only by reviewed `.github/sync/pins.env`. `SYNC_TOKEN` with **Contents:
write + Workflows: write** is required; no `GITHUB_TOKEN` fallback. `without
'workflows' permission` means missing/under-scoped secret.

If blocked, land on PC in three ordered steps. Do not collapse them; advancing
pins before live `origin/nbase` moves guarantees red `sync-guard-check`.

1. PR the resolved merge into `dev`, leaving `.github/sync/pins.env` untouched.
   `.github/sync/protected-paths.tsv` paths stay pure-ours and byte-identical to
   `dev`, even on clean auto-merge. Verify:

       $paths = Get-Content .github\sync\protected-paths.tsv | Select-Object -Skip 1 |
                ForEach-Object { ($_ -split "`t")[0] }
       git diff --name-only origin/dev -- $paths

   Or in Git Bash / WSL:

       git diff --name-only origin/dev -- $(tail -n +2 .github/sync/protected-paths.tsv | cut -f1)

   Restore anything printed with `git checkout origin/dev -- <path>`. Never
   repin to unblock sync; repin only for dazewell's own asset change.
2. and 3. Dispatch:
   ```powershell
   gh workflow run sync-land.yml --repo dazewell/Dazegram
   ```
   Prefer `-f snapshot=<sha>`; zero-input reads `refs/sync/snapshot-<srcshort>`.
   `sync-land` runs `sync-guard.ps1 -LandCheckOnly`: one parent equal to pinned
   `OLD_NBASE`, only commit over that anchor, tree equals live upstream, upstream
   descends from `ANCHOR_SRC`, `ANCHOR_SRC` matches old `nbase` tree, sync
   author/committer, snapshot ancestor of `dev`. It fast-forwards
   `origin/nbase` non-force, never pushes `dev`, then opens/reuses the pins PR
   with `SYNC_TOKEN`; review its body evidence.

`sync-land.yml` is idempotent. Between nbase fast-forward and pins PR merge,
expect red `sync-guard-check` on old-anchor branches; do not phone-sync then.
`sync-land`/`sync-upstream` share `concurrency: sync-refs`; `sync-land` re-leases
`origin/nbase`. Never fast-forward `base` into `dev`.

## Propose upstream

Recover merged PR range from `refs/pull/<N>/head`; propose compatible features to
`NextAlone/Nagram`:

```powershell
git fetch https://github.com/NextAlone/Nagram.git dev:refs/remotes/nagram-reference/dev
git fetch origin refs/pull/<N>/head:<YYYY-MM-DD>_<slug>   # branch auto-deleted at merge; recover the range
git switch -c <YYYY-MM-DD>_<slug>-pr <YYYY-MM-DD>_<slug>   # throwaway copy
git rebase --onto nagram-reference/dev <branch-point> <YYYY-MM-DD>_<slug>-pr   # replay onto pristine proposal target
git checkout nagram-reference/dev -- FEATURES.md          # drop the fork-only doc hunk
git rebase -i nagram-reference/dev                        # squash to one clean commit
git push origin <YYYY-MM-DD>_<slug>-pr
gh pr create --repo NextAlone/Nagram --base dev --head dazewell:<YYYY-MM-DD>_<slug>-pr
```

Delete the `-pr` branch after merge. Strip only `FEATURES.md`.

## Automation notes

`ci.yml` is the fast push/PR-to-`dev` gate:
`:TMessagesProj:compileDebugJavaWithJavac`, `NATIVE_TARGET=SKIP`, two-package
matrix, setup-gradle cache, no native build/signing/APK/Telegram; AGP may still
provision `ndkVersion`. It checks `.class` files; `deep` covers build scripts,
manifest, and `proguard-rules.pro`. Docs/hooks/agent/skill-only are ignored;
workflow edits are not.

`staging.yml` is the only publish pipeline: signed dual APK + Telegram upload on
push to `dev`, `build-apk`, or dispatch. Matrix: `nekox.messenger` ->
**Unofficial** (`DazegramX-Unofficial-…`), `org.telegram.messenger.beta` ->
**Official** (`Dazegram-Official-…`); default local package
`APP_PACKAGE=nekox.messenger`. Package names must track `applicationId` in
manifest contact mimeTypes, `res/xml`, `resValue`, `BuildConfig.APPLICATION_ID`;
`resValue` needs `buildFeatures.resValues = true`; `google-services.json` needs a
client per `package_name`. Docs/`.github`/`staging.yml`-only dev pushes do not
publish; verify pipeline changes by dispatch. Labelled PR builds have no
paths-ignore. Both workflows use `github.head_ref || github.ref_name`
concurrency with `cancel-in-progress`; unrelated labels get a suffix. Upload
captions include GitHub Models summary via `GITHUB_TOKEN` (`models: read`,
optional `AI_MODEL`), trimmed for Telegram's 1024-char cap.

Phone sync: GitHub mobile or bot POST to
`/repos/dazewell/Dazegram/actions/workflows/sync-upstream.yml/dispatches` with
fine-grained PAT (Actions read/write only). `sync-land.yml` needs `SYNC_TOKEN`
with **Contents: write + Workflows: write** and **Pull requests: write**. Policy
`none` rejects workflow paths; `manifest` needs workflow scope.

## Keeping this current

When flow changes, edit this file in the same session and keep `CLAUDE.md`,
`nagramx-workflow`, and memory maps in sync.