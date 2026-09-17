---
name: nagramx-agent-comms
description: "Dazewell's protocol for the rare case where two NagramX sessions run at once — a nagramx-orchestrator coordinating a batch of independent changes, and the nagramx-implementer sessions it dispatched. Trigger it whenever one session sends an instruction to or receives a report from another, whenever a coordinator must decide whether a dispatched session is working / finished / blocked / dead, before concluding an automated review is clean, and before nursing a slow session instead of restarting it. Covers: observable state as the single authority, state-stamping every message, re-reading your own tree before reporting, one-outstanding-instruction flow control, start-acks so a silent stall surfaces in minutes, terminal-review rules, mechanical liveness with restart-not-nudge, self-contained briefs, and carrying a stalled session's authorized work into its replacement. It exists because on a full day of multi-session work more time was lost to coordination failure than to any bug — which is why the default is now one session per change and this file applies only when that default has been deliberately set aside."
---

# NagramX cross-session communication protocol

## Read this first: you probably don't need this file

**The default on this repo is one change, one branch, one session.** A single
change runs start to finish in one `nagramx-implementer` session that uses
subagents for recon, design and review. No cross-session messaging happens, and
none of the rules below apply.

This protocol exists for the two cases where that default is deliberately set
aside — a `nagramx-orchestrator` running **two or more genuinely independent
changes** concurrently, or landing a batch of approved PRs. If you are reaching
for these rules during an ordinary change, the mistake is upstream: you split
something that should have stayed in one session.

That framing is the main lesson of the incident this file came from. The rules
below are what remains genuinely necessary once the avoidable coordination was
removed.

## Why this exists

One orchestrator coordinated several implementer sessions and review subagents
across a full day. The *code* was fine. The **coordination** was the dominant
cost — more elapsed time went to communication failure than to any bug. These
patterns actually happened:

1. **Silent stalls with authorized work outstanding.** A session finished a turn,
   went idle, and never started work it had been explicitly told to do. Worst
   cases were 90 minutes and 4.5 hours of zero progress, discovered only because
   the coordinator checked `git log` independently. Nothing distinguished
   "working" from "dead".
2. **Crossed messages.** An instruction and a report passed each other in
   flight, at least four times. The worker answered a question already
   superseded, or sat blocked on a decision it had already been given.
3. **Stale self-reports after compaction.** A session reported a state one or two
   commits behind its own tree — flagging a finding it had already fixed,
   reporting head `X` when the tree was at `X+2`.
4. **Premature "clean" conclusions.** A session concluded a review had produced
   no findings while another run was still in flight; seven findings landed two
   minutes later.
5. **Context degradation as root cause.** The sessions that stalled worst were
   the longest-running and most compacted. Handing the work to a *fresh* session
   with a self-contained brief restored normal function immediately, twice.
6. **Long instructions correlated with stalls.** Short, imperative,
   self-contained instructions were acted on more reliably. (Observed, not
   proven — but cheap to act on.)

The honest framing: these are **inherent** to splitting one piece of work across
contexts that cannot see each other. Published practice agrees — Anthropic's own
multi-agent write-up reports the same duplicate work and excessive-update
failures, and states plainly that most coding tasks have fewer truly
parallelisable parts than research, and that agents are not yet good at
delegating to each other in real time. That is why the answer here was to stop
splitting by default, not to write a better protocol.

## The one principle

**Observable state is authoritative; session narrative is not.** Git commits, CI
runs and PR review threads are the shared truth both sides read and write. A
message is a *pointer* into that state — useful for saying "look here now",
useless as a substitute for looking.

The theory is worth knowing, because it bounds what any protocol can do. The
**Two Generals' Problem** proves two parties on an unreliable channel can never
guarantee agreement by messages alone: acknowledgements are lost as easily as
the messages they acknowledge. So **no message either session sends can
guarantee the other is alive and has acted.** A protocol that pretends otherwise
is lying. Liveness here is settled by observing git, and a truly non-responding
session is a restart problem, not a messaging problem.

The rest is borrowed and named honestly: actor-model supervision (a supervisor
restarts a child rather than the child healing itself), liveness-vs-readiness
probes (alive is not the same as progressing), and the circuit breaker (past a
bounded number of failed attempts, change tactic rather than loop). Where we
differ from all of them: they assume a runtime that re-invokes the worker. **An
idle session here has no autonomous wake-up and no enforced timeout.** The
coordinator runs the probe itself; there is no kubelet.

## The rules

### 1. Stamp every message with the state it was written against

Put the head SHA you last observed on every instruction and every report —
`@<short-sha> PR#<n>`. Expand to the full SHA when a check compares it against
GitHub's `commit_id`.

**Honour the stamp you receive.** When a message reaches you stamped behind a
tree you can already see, treat it as possibly superseded: re-read first, and
read an instruction asking for work you have already done as *already satisfied*,
not as a repeat. Compare against the PR head or the head you last observed for
**that session** — never against your own coordinator worktree, which is a
different branch and would make every worker report look stale.

*Binds both sides. Costs one line per message. Catches the crossed-message
failure, which happened at least four times in one day.*

### 2. Re-read your own tree before every report

After a compaction your memory of your own state drifts. Immediately before
reporting, run `git log --oneline -5`, `git rev-parse HEAD`, the CI run pinned to
that SHA, and the unresolved-thread count — and report those. **If a claim would
not survive a reader running the same command, drop it.**

*Binds the worker. Costs four commands.*

### 3. Decide inside your lane; escalate only the enumerated crossings

A decision already implied by an earlier ruling is not a new question — do not
spend a round trip on it. The worker escalates only when a fix would change the
agreed hook point, the config or storage surface, user-visible behaviour that was
specified for it, or would turn one change into two.

*Binds both. Cuts the round trips that dominated the bad day.*

### 4. One outstanding instruction per channel; a new one supersedes

Send the next instruction only once the last is **observably complete**. For a
session doing git work that means an artifact you can see — a commit, a push, a
PR state. For a **subagent**, which produces none of those, the artifact is its
returned report: read it before dispatching the next one, and treat a subagent
that returned nothing usable as failed rather than re-dispatching the same
objective at it.

A new instruction on the same channel **replaces** the previous one; it does not
queue behind it. Say so explicitly when you supersede.

*Binds the coordinator, and anyone running subagents.*

### 5. Acknowledge authorized work on receipt

When you are told to proceed, reply with one line — `starting <thing> @<sha>` —
then start. **Silence after an authorization is indistinguishable from a dead
session**, and that ambiguity cost hours. The ack is what closes it, and it is
what turns a 4.5-hour invisible stall into a five-minute visible one.

*Binds the worker. Costs one line.*

### 6. A review is not "clean" until it is terminal

The automated reviewer re-fires on every push and posts a minute or two after
one, so **an empty result read too early is not a clean review.** Note the review
count as a baseline first, then wait against a deadline, synchronously — a
session-attached process dies when the session goes idle.

Login gotcha: the *reviews* endpoint lists the bot as
`copilot-pull-request-reviewer[bot]`, the *comments* endpoint lists it as
`Copilot`. An exact-match filter on either silently returns zero on the other.
Match case-insensitively on a wildcard.

Bound the loop: act only on **Important or above**, and cap at **two**
review-driven push cycles. If Important-or-above findings remain after the
second, stop and report — more churn usually means the design needs revisiting,
which is a report, not a patch.

*Binds both. This is the circuit breaker.*

### 7. Establish liveness mechanically; restart a non-responding session

Act on notifications, **never a polling loop**. Metadata alone cannot distinguish
a child that is working from one that has died, so resolve every ambiguous state
mechanically:

```powershell
# alive?
get_session <id>
# progressing?
git -C <worktree> status; git -C <worktree> log <base>..HEAD --oneline
```

If it genuinely shows no progress, send **exactly one** status probe. If the next
wake still shows no change, do a single `get_session` plus session-tail read as a
diagnostic. If a second wake still shows nothing, **stop probing and restart the
work in a fresh session** with a self-contained brief.

**Do not nurse a stalled session and do not archive a live-but-unresponsive
one.** No message rescues a session that has stopped responding — the coordinator
verifies externally and replaces it. That is the honest limit of this whole file.

*Binds the coordinator.*

### 8. Prefer a fresh session to a degraded one — before the stall

The sessions that failed worst were the longest-running and most compacted.
Handing the work to a fresh session with a self-contained brief fixed it
immediately, both times. So hand off **before** the stall, not after: when a
session has been corrected twice on the same problem, or has compacted more than
once and started repeating itself, its remaining context is mostly failed
approaches and the next attempt inherits all of them.

*Binds both. A fresh context is cheap; an afternoon is not.*

### 9. Every instruction is self-contained

A dispatched session cannot see the conversation that produced it. Paste
specialist findings **verbatim** — prose gets compressed and the first casualty
is the `file:line` citation that stops an implementer hooking the wrong place.
State the objective, the output format, the tools and sources to use, and the
boundaries. A vague delegation comes back as a summary nobody can act on.

Short and imperative beats long and complete. If the brief has grown past what a
reader will act on, that is a signal the work should not have been split.

*Binds the coordinator.*

### 10. Specify the required property, not the mechanism

State what must be true, not how to achieve it. When an instruction *does* name a
specific mechanism and the call site contradicts it, **contest it before building
it**: reply with the `file:line` and the property the mechanism fails, then
propose one that works. Do not build the wrong thing and report the failure
afterwards.

*Binds both.*

### 11. Authorized work outlives the session authorized to do it

Before you archive or replace a session, **discharge everything it was
authorized to do**. For each item: cite the commit that covers it, record why it
is explicitly declined, supersede it explicitly, **or transfer it** — write it
into the successor's brief and verify it arrived. Transfer discharges *that
session*, not the item; do not report a transfer as completion.

A zero git diff is **not** an empty authorization ledger. A replacement session
holds inherited obligations from the moment it starts, so "it never reported and
its worktree is clean" is not evidence that it owed nothing. Those are the
obligations easiest to drop and least recoverable once the session is archived —
archiving is exactly the moment an undischarged authorization becomes permanent.

**One authorization is never transferable: a merge approval.** It is closed per
PR when the session ends — each PR it merged as `landed`, each it did not as
`superseded` — and never written into a successor's brief. A replacement re-asks
dazewell for any still-unmerged PR rather than inheriting his approval.

*Binds the coordinator.*

## Applying it while you build

If following these rules is taking real effort during an ordinary change, that
is the signal to stop and collapse the work back into one session. The protocol
is a cost you pay for concurrency — pay it only where the concurrency is real.
