---
name: nagramx-session-handoff
description: "Dazewell's protocol for abandoning a NagramX session mid-change so a fresh one can continue it on the same branch. Trigger it when dazewell says hand off, wrap up, start fresh, this session is stuck / confused / going in circles, or asks for a handoff; when you have corrected the same problem twice and the context is now full of failed approaches; and before any deliberate session restart on an unfinished change. Covers: what to commit and push before abandoning, opening the PR if none exists, writing the handoff into the PR body under a stable marker so it is the first thing anyone sees, the dead-ends section that is the only part a fresh session cannot reconstruct, updating an existing handoff rather than stacking a second one, and stopping work once the state is stamped. The incoming side is nagramx-session-pickup."
---

# Handing a change to a fresh session

A change lives on its **branch**, not in a session. A session can be abandoned
at any point without losing work, provided it leaves state somewhere durable
first. This file is the outgoing half; `nagramx-session-pickup` is the incoming
half.

**Why this exists:** once you have corrected the same problem twice, the context
is full of failed approaches and every new attempt inherits all of them. A fresh
session's entire advantage is *not* carrying that. So the handoff carries the
**conclusions** — above all, what already failed — and never the transcript that
produced them. A handoff that replays the thread rebuilds the exact problem it
was meant to escape.

## When to hand off

- The same problem has been corrected **twice** and a third attempt is starting.
- Dazewell says so — "hand off", "start fresh", "you're going in circles".
- The session has drifted off the agreed plan and re-explaining costs more than
  restating the problem.

**Do not** hand off merely because a change is long; length is fine, repeated
failure in one region is not. And do not hand off to dodge a finding — an
unfixed Critical is still unfixed in the next session, and now nobody is
watching it.

## The four steps

Do all four, even if the work is ugly.

### 1. Commit and push what works

Commit whatever is coherent on its own, with the change's `#<slug>` tag, and
push. Append-only — a new commit, never an amend.

If nothing compiles, commit it anyway — say so in the subject in plain words
(`stash partial <what>, does not compile yet #<slug>`), keeping the normal
lowercase imperative form with no type prefix, per `AGENTS.md`. A pushed commit
survives a worktree removal; a dirty tree does not. Never discard work to make
the branch look tidy.

### 2. Make sure a PR exists

If there is none, open one now — draft is fine. **The PR is where the handoff
lives**: it is the one surface that outlives the session, reads from any
machine, and is already bound to the branch.

### 3. Write the handoff into the PR *body*

Not a comment. A comment sinks under review threads and `gh pr view` hides it
behind `--comments`; the body is the first thing both dazewell and the next
session see. Append this block to the end of the body, under its marker:

```markdown
<!-- handoff -->
## Handoff — <YYYY-MM-DD>

**State:** `<branch>` @ `<short-sha>`. Compiles: yes / no / not tried.

**Done.** <Finished and review-clean. One line each.>

**Half-done.** <Started but not right, and what specifically is wrong — the
observed symptom, not the suspicion.>

**Not started.** <What the agreed plan still owes.>

**Dead ends — do not retry.**
- <Approach that failed> — <why, concretely>.

**Open questions for dazewell.** <Blocking only. Omit if none.>
```

Read the body first and **update an existing `<!-- handoff -->` block rather
than appending a second one.** Two handoffs on one PR means the next session has
to work out which is current, which is exactly the ambiguity this removes. Merge
the dead ends — they accumulate across handoffs and are the most valuable thing
on the page. Refresh the date and the sha.

Use `gh pr edit <n> --body-file` with the full body, or the workspace's
PR-editing tool. Never hand-retype the existing body: read it, replace or append
the block, write it back.

Optionally add a one-line comment pointing at it (`Handed off — see the handoff
block in the description`) so watchers get a notification. The body stays
authoritative.

**No AI or tooling mention anywhere in it.** The hard line covers PR bodies and
comments like everything else.

### 4. Stamp it and stop

The dead-ends section is **the only part that matters**. Everything above it a
fresh session reconstructs from the diff in a minute; a dead end it cannot
reconstruct at all and will walk straight into. Give each a concrete reason —
"thrashes on every re-bind", "stripped from release by `-assumenosideeffects`" —
never "didn't work". A reasonless dead end reads as an untested idea and gets
retried.

Keep it short. If half-done needs more than a few lines, the real problem is
that the change is too big — say that instead.

Then tell dazewell the branch, the PR number and one line on what to open next.
**Stop working.** Further commits invalidate the state you just stamped.

## What dazewell has to say

Nothing precise. "Hand off" or "start fresh" is enough. He should never need a
form of words, and a handoff must never depend on him having described the state
correctly — you read the state yourself.

## Keeping this current

Edit here when the protocol changes. Bias to subtraction: this file is read at
the moment a session is already in trouble, so length is a real cost.
