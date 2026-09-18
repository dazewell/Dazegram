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

```powershell
git --no-pager log --oneline origin/dev..HEAD      # what landed, and its #slug
git --no-pager diff --stat origin/dev...HEAD       # the shape of the change
git status --short                                 # uncommitted work the last session left
gh pr list --head <branch> --state all             # is there a PR, is it open, is it draft
gh pr view <n>                                     # the body — and any <!-- handoff --> block
gh pr view <n> --comments                          # review findings and their dispositions
gh run list --branch <branch> --limit 5            # does it currently compile
git --no-pager diff origin/dev...HEAD              # finally, the change itself
```

**Check `git status` early and deliberately.** A session that died badly may
have left uncommitted work, and that work is invisible to every other command
here. Decide explicitly whether to keep it, and say which you chose.

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
record; `git log --grep '#<slug>'` finds any earlier related work, including
fixes that landed on other branches.

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

**Equally, do not re-review the finished parts.** Resume where the work stopped.
A fresh crop of Minor observations on already-reviewed code is a cost, not
thoroughness.

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
