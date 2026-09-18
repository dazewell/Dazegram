---
name: nagramx-session-pickup
description: "Dazewell's protocol for starting work on a NagramX change already in progress — a branch or PR an earlier session left unfinished. Trigger it at the start of any session told it is continuing, resuming, picking up, taking over or finishing an existing branch or PR; whenever the working tree is on a dated feature branch carrying commits this session did not write; and when dazewell points at a branch, a PR number or an old change and asks what is left. It applies whether or not a handoff was ever written — an abandoned branch with no handoff is the harder and more common case. Covers: reconstructing state from git, the PR body, CI and review threads rather than from narration; the order to read them in; treating the diff as authoritative over a stale handoff; honouring recorded dead ends; not re-litigating settled hook points, UX rulings or dispositioned findings; not re-reviewing finished work; and confirming the reconstruction before building. The outgoing side is nagramx-session-handoff."
---

# Picking up a change already in flight

You have been pointed at a branch or a PR you did not write. Everything you need
is on disk and on GitHub; none of it is in your context yet. **Reconstruct
before you build.**

This is the incoming half of `nagramx-session-handoff`, but it applies equally
when nobody wrote a handoff at all — a branch abandoned without ceremony, or one
from weeks ago. That case is the harder one and the more common one.

## 1. Read the artifacts, in this order

Do not skip ahead to the code. The order matters: shape first, then detail.

**Bind the target before you read anything.** Every command below resolves
against `HEAD`, so establish that `HEAD` *is* the change you were pointed at —
otherwise you can reconstruct, and later modify, the wrong branch entirely. In a
worktree sitting on something else, this is the whole failure.

```powershell
git rev-parse --abbrev-ref HEAD                    # what am I actually on
```

If that is not the target branch, resolve it before continuing. Given a branch
name, `git switch <branch>` in this worktree, or work in the worktree that
already holds it. Given only a PR number, `gh pr view <n> --json headRefName`
names the branch; fetch and switch to it. **Never** reconstruct from a `HEAD`
you have not confirmed, and never switch a worktree that has uncommitted work
belonging to a different change — resolve that first or use another worktree.

With `HEAD` confirmed, resolve the name GitHub knows it by — **not** the local
one, and not the tracking ref either. Both can be absent or different: `_` and
`-` are equally valid locally and branch tooling may flatten `_` to `-` on
push, while this repo's own follow-up procedure pushes with
`git push origin <branch>` and no `-u`, which leaves a live remote branch, PR
and CI history behind **no tracking ref at all**. An unset `@{u}` therefore
proves nothing. Ask GitHub instead, and only conclude "never pushed" when it
says so:

```powershell
$local = git rev-parse --abbrev-ref HEAD
$pr = gh pr list --head $local --state all --json number,headRefName,state,mergedAt --jq '.[0]' | ConvertFrom-Json
if (-not $pr) {
  $pr = gh pr list --head ($local -replace '_','-') --state all --json number,headRefName,state,mergedAt --jq '.[0]' | ConvertFrom-Json
}
$branch = if ($pr) { $pr.headRefName }
          elseif ($u = (git rev-parse --abbrev-ref '@{u}' 2>$null)) { $u -replace '^origin/','' }
          else { $local }
git ls-remote --heads origin $branch               # empty means genuinely never pushed
```

**Keep `$pr.number`** — the PR reads further down need it, and if pickup started
from a branch name this lookup is the only place it appears.

**If `ls-remote` came back empty, the remote head is gone — which is not the
same as there being no record.** A PR outlives its branch here: the repo
auto-deletes the head ref on merge, and `refs/pull/<N>/head` keeps the range.
So decide on both signals, and on `$pr.mergedAt` — a deleted ref means merged or
abandoned, and those are opposite situations.

**Merged** (`$pr.mergedAt` set): the change already landed and there is nothing
to pick up. Do not reconstruct it. Say so, point at the PR, and ask dazewell
what he actually wants — a follow-up is a new dated branch off `dev` reusing the
same `#<slug>`, per `nagramx-branch-flow`, not a resumption of this one.

**Closed unmerged, ref deleted**: the change was abandoned, and that is a real
pickup — but the branch is gone and reviving it is a decision, not a default.
Report it and ask, naming the recovery:
`git fetch origin "pull/$($pr.number)/head:<new-dated-branch>"` puts the range
back on a fresh branch, which is where the work would continue.

Only when `$pr` is empty *and* `ls-remote` is empty is the change genuinely
local-only: skip the fetch and the `origin/$branch` reads below — they would
just fail — fetch `dev` alone for a comparison base, and say plainly in your
confirmation that local history was the whole record.

Otherwise bind to the remote head, so you reconstruct the newest state rather
than whatever the abandoned worktree happened to stop at — a session that died
may be several commits behind its own branch:

```powershell
git fetch origin dev $branch
git status --short                                 # FIRST: anything uncommitted here?
git --no-pager log --oneline HEAD..origin/$branch  # commits on GitHub this worktree lacks
```

**Deal with the dirty tree before you move `HEAD`, not after.** Uncommitted work
is the only copy in existence and a fast-forward is the fastest way to lose it —
it will either refuse and leave you improvising, or, worse, tempt a `reset
--hard` that destroys it silently. So read it first (below), decide what to keep,
and commit or stash it. Only then fast-forward, if `HEAD..origin/$branch` was
non-empty. If the two have **diverged**, stop and report rather than quietly
reconstructing a fork of the change.

```powershell
git --no-pager log --oneline origin/dev..HEAD      # what landed, and its #slug
git --no-pager diff --stat origin/dev...HEAD       # the shape of the change
git --no-pager log --all --grep '#<slug>'          # related work on any other branch
git --no-pager diff origin/dev...HEAD              # finally, the change itself
```

**Does it compile?** `gh run list` alone does not answer that — it lists every
workflow, so a green `Commit tag check` reads as a passing build. Filter to the
gate and check it ran on *this* head:

```powershell
gh run list --branch $branch --workflow ci.yml --limit 5 `
  --json headSha,conclusion,createdAt --jq '.[]|"\(.headSha[0:9]) \(.conclusion)"'
git rev-parse --short=9 HEAD
```

A run on an older SHA says nothing about the current tree. **No run at all is
not a pass**: `ci.yml` path-ignores doc, hook, agent and skill changes, so a
doc-only branch legitimately has none. Report that as path-ignored — never as
green, and never as broken.

**Only if a PR was found above**, read it — a branch abandoned without a
handoff often has no PR, and these commands are not runnable without one:

```powershell
gh pr view $pr.number                              # the body, and any <!-- handoff --> block
gh pr view $pr.number --comments                   # review findings and their dispositions
```

`--comments` shows the text but **not** whether a thread was resolved, and an
unresolved finding is the thing you most need to see. Read the resolution state
directly — the same `reviewThreads` query
`.github/agents/nagramx-implementer.agent.md` already uses:

```powershell
$q = 'query($endCursor:String) { repository(owner:"dazewell",name:"Dazegram"){ pullRequest(number:'+$pr.number+'){
  reviewThreads(first:100, after:$endCursor){ pageInfo { hasNextPage endCursor }
    nodes { isResolved path line comments(first:1){ nodes { body } } } } } } }'
gh api graphql --paginate -f query=$q --jq '.data.repository.pullRequest.reviewThreads.nodes[]
  | select(.isResolved==false) | "\(.path):\(.line)"'
```

`--paginate` only follows the cursor when the variable is named **`$endCursor`**
and `pageInfo` is in the selection — any other name and it stops after the first
page, so an unresolved thread at 101 reads as a clean review.

With no PR, the local history and CI are the whole record. Say so in your
confirmation rather than leaving it ambiguous whether you looked.

**Read the uncommitted work — this is the step the fast-forward above waits
on.** A session that died badly may have left work behind, and it is invisible
to every other command here: `git status --short` names the paths but shows
none of the content, and `git diff origin/dev...HEAD` excludes it entirely. It
may be the only copy in existence, so read it before deciding: `git --no-pager
diff` and `git --no-pager diff --cached` for tracked changes, and open each
untracked path. Decide explicitly whether to keep it, commit or stash what you
keep, and say which you chose. Only then move `HEAD`.

Then read `AGENTS.md` and `nagramx-workflow`. You are mid-pipeline, not exempt
from it — the compile gate, the review rounds and the `FEATURES.md` obligation
all still apply to the finished change.

## 2. The diff is authoritative

A handoff block tells you *why* things are as they are. It does not override
what the code says, and it may be several commits stale.

**Where they disagree, the code is right.** Say so plainly rather than quietly
picking one — a contradiction usually means work happened after the handoff was
written, which is itself something dazewell needs to know.

With no handoff at all, the commit messages and their `#<slug>` are your best
record; `git log --all --grep '#<slug>'` finds any earlier related work —
`--all` matters, because without it the search only walks commits reachable
from `HEAD` and misses fixes that live on other branches, which is the whole
point of searching.

## 3. Honour the dead ends

Anything recorded as a dead end is **already tested and rejected**. Do not
re-derive it to check. Re-running a failed approach is the single most expensive
thing you can do here — it burns the entire advantage of being a fresh session.

If you believe one was abandoned wrongly, **say so explicitly with your
reasoning before spending anything on it.** That is a decision to surface, not
to make silently.

With no handoff, treat an abandoned-looking approach in the diff — a half-built
mechanism, a commented-out branch — as a probable dead end and ask, rather than
finishing it on the assumption it was going somewhere.

## 4. Do not re-litigate settled ground

The hook point, the config surface, the UX decisions, and any architect finding
already dispositioned are **settled**. Reopening them turns a resumption into a
redesign, which is how a nearly-finished change becomes a new one.

If something settled looks genuinely wrong, raise it as a finding with
`file:line` evidence and let dazewell rule. Do not just build it differently —
silently shipping a variant of a rejected approach is the failure mode this
whole process exists to prevent.

**Equally, do not re-review the finished parts** *during reconstruction*. A
fresh crop of Minor observations on already-reviewed code is a cost, not
thoroughness. This does not excuse the change from the whole-feature and
craftsmanship passes `nagramx-workflow` step 5 requires on the **final** state —
those read the accumulation, including how the inherited code and your new work
interact, which is exactly what a resumed change is most likely to get wrong.

## 5. Confirm before you build

Reply with four short lines and wait:

- What you believe is **done**.
- What you are **picking up first**.
- Anything in the handoff the **diff contradicts**, or "nothing".
- Any **uncommitted work** you found, and what you did with it.

Get the reconstruction wrong here and you have lost nothing. Get it wrong
silently and you rebuild the same wall the last session hit.

## Then run the normal pipeline

From this point you are an ordinary implementer session on an ordinary change,
with `nagramx-workflow` governing the rest: compile gate, review round 2 on the
**whole final state** rather than your increment, `FEATURES.md` if user-visible,
any codemap fact the work established, and the PR.

One thing is easy to miss on a resumed change: **the final-state review matters
more than usual.** A change that arrived as two sessions' worth of increments
reads fine hunk-by-hunk and can still leave a muddled state machine. Review the
whole, not your part of it.

## Keeping this current

Edit here when the protocol changes. Bias to subtraction — this file is read
before any useful work starts, so every line is paid for up front.
