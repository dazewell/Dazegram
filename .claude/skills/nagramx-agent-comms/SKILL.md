---
name: nagramx-agent-comms
description: "Dazewell's protocol for how the NagramX agent sessions talk to each other — orchestrator to implementer, parent orchestrator to child orchestrator, and any coordinator watching any session it dispatched. Trigger it whenever one session sends an instruction to or receives a report from another, whenever a coordinator has to decide whether a dispatched session is working / finished / blocked / dead, before concluding an automated review is clean, and before nursing a slow session instead of restarting it. Binds nagramx-orchestrator (coordinator side) and nagramx-implementer (worker side); the parent/child-orchestrator control vocabulary in the orchestrator agent file is the richer instance of these same rules. Covers: observable state as the single authority, state-stamping every message, re-reading your own tree before reporting, standing authority so routine decisions don't cost a round trip, one-outstanding-instruction flow control with supersession, start-acks so a silent stall is caught in minutes not hours, terminal-review rules, mechanical liveness with restart-not-nudge, fresh-session handoff before degradation stalls a session, and self-contained instructions that survive context compaction. It exists because on a full day of multi-session work more time was lost to coordination failure than to any bug."
---

# NagramX cross-session communication protocol

This is the **one normative copy** of how sessions in the NagramX workflow
communicate. `CLAUDE.md`, the `nagramx-orchestrator` and `nagramx-implementer`
agent files, and the other skills point here rather than restating the rules. If
you find one of these rules duplicated and drifting in another file, fix it so
only this file states it.

It binds two roles. The **coordinator** — a `nagramx-orchestrator` (root or
child) watching any session it dispatched. The **worker** — a
`nagramx-implementer`, or a child orchestrator seen from its parent. Every rule
below says which side it lands on; several land on both.

## Why this exists

One orchestrator coordinated several implementer sessions and review subagents
across a full day. The *code* was fine. The *coordination* was the dominant cost
— more elapsed time went to communication failure than to any bug. These eight
patterns actually happened; the rules are designed against them, not against
imagined problems:

1. **Silent stalls with authorized work outstanding.** A session finished a
   turn, went idle, and never started work it had been explicitly told to do.
   Worst cases were 90 minutes and 4.5 hours of zero progress, discovered only
   because the coordinator checked `git log` independently. Nothing distinguished
   "working" from "dead."
2. **Crossed messages.** An instruction and a report passed each other in
   flight, repeatedly. The worker answered a question already superseded, or sat
   "blocked" on a decision it had already been given. At least four times.
3. **Stale self-reports after compaction.** A session reported a state one or two
   commits behind its own actual tree — flagging a finding it had already fixed,
   reporting head `X` when the tree was at `X+2`, standing by for a build that had
   already run and passed.
4. **Premature "clean" conclusions.** A session concluded a review had produced
   no findings while another review run was still in flight; seven findings
   landed two minutes later.
5. **Context degradation as root cause.** The sessions that stalled worst were
   the longest-running and most-compacted. Handing the work to a *fresh* session
   with a self-contained brief restored normal function immediately, twice.
6. **Round-trip cost for predictable decisions.** Many escalations asked for
   rulings already implied by earlier ones. A pre-authorized decision taxonomy
   measurably cut this.
7. **Long instructions correlated with stalls.** Short, imperative,
   self-contained instructions were acted on more reliably than long ones.
   (Observed, not proven — but cheap to act on.)
8. **The coordinator had to verify everything mechanically.** Reports were
   unreliable enough that the working rule became *git state is authoritative,
   session chatter is not* — every claim about heads, checks and threads was
   confirmed with `git`/`gh` before it was acted on.

## What established practice says, and where we differ

The problem is old; the literature is worth reading before inventing.

- **The Two Generals' Problem** ([Wikipedia](https://en.wikipedia.org/wiki/Two_Generals%27_Problem))
  proves that two parties on an unreliable channel can *never* reach guaranteed
  agreement by messages alone — acknowledgements can be lost as easily as the
  messages they acknowledge. The honest consequence for us: **no message either
  session sends can guarantee the other is alive and has acted.** A protocol that
  pretends otherwise is lying. This is why liveness here is settled by *observing
  shared state* (git), not by trusting an ack, and why a truly non-responding
  session is a restart problem, not a messaging problem (Rule 7).
- **Actor-model supervision** (Erlang/OTP
  [supervision principles](https://www.erlang.org/doc/system/sup_princ.html),
  the "let it crash" philosophy) puts recovery in a *supervisor* that restarts a
  child rather than in the child healing itself. That maps directly: a
  degraded or stalled session is not coaxed back to health — the coordinator, as
  supervisor, restarts the work in a fresh session (Rules 7 and 8). Where we
  differ: OTP restarts are automatic and instant; ours are a manual dispatch with
  a hand-written brief, so the brief has to be self-contained (Rule 9).
- **Liveness vs. readiness probes** (Kubernetes
  [probes](https://kubernetes.io/docs/concepts/workloads/pods/probes/))
  separate "is it alive" from "is it ready / making progress," and a failed
  liveness probe *restarts* the container. We have two cheap external probes an
  idle notification can't give us on its own: `get_session` (alive?) and git
  state (progressing?). The start-ack in Rule 5 is our readiness signal; git
  progress by the next wake is our liveness signal. Where we differ: an idle NagramX
  session has **no autonomous wake-up** and the runtime enforces no timeout, so
  the coordinator must run the probe itself — there is no kubelet.
- **The Circuit Breaker** (Fowler,
  [CircuitBreaker](https://martinfowler.com/bliki/CircuitBreaker.html), after
  Nygard's *Release It!*) stops calling a failing dependency after a threshold
  instead of hammering it forever. That is exactly the round cap on the review
  loop (Rule 6) and the bounded probe-then-restart path on a stall (Rule 7): past
  a bounded number of failed attempts you stop retrying and change tactic, rather
  than looping.
- **Idempotency, at-least-once delivery, and monotonic sequence stamps** (the
  standard messaging toolkit; Lamport's
  [logical clocks](https://en.wikipedia.org/wiki/Lamport_timestamp) are the
  canonical monotonic stamp) say: assume a message may be delivered late,
  duplicated, or out of order, and make each message carry enough state to detect
  that. Our version is the head SHA carried in every message (Rule 1): a cheap,
  already-monotonic stamp both sides can compare against ground truth. Where we
  differ: we have no delivery runtime doing dedup for us, so the *recipient* does
  the staleness check by hand.
- **Published multi-agent orchestration practice** (Anthropic's
  [*How we built our multi-agent research system*](https://www.anthropic.com/engineering/multi-agent-research-system))
  reports the same failure modes from the other end of the same problem, which is
  the strongest evidence these rules aren't local superstition. Its early agents
  were "distracting each other with excessive updates" and "duplicate[d] work"
  when the lead agent's delegation was vague — which is why it concludes each
  subagent "needs an objective, an output format, guidance on the tools and
  sources to use, and clear task boundaries," and that the best prompts are
  "frameworks for collaboration that define the division of labor … and effort
  budgets." That is Rule 9 and the fixed-block brief, reached independently. It
  also names our root cause outright — "agents are stateful and errors compound …
  minor system failures can be catastrophic" over a long-running process —
  matching #5. Where we differ: their mitigation is *durable resume from
  checkpoints* so a long agent "can't just restart from the beginning"; we have no
  such runtime, so our answer to a degraded session is a **fresh** context with a
  self-contained brief (Rules 7–8), not a resumed one. Two of its other
  mitigations map cleanly onto our choices: its advice to **save results to
  durable external memory rather than pass everything through context** *is* our
  one principle — git is that external memory here, so a result is not real until
  it is committed; and its **cap on concurrent subagents** targets coordinator
  overload we did *not* observe (our failures were liveness and staleness, not too
  many sessions at once), so we note the lever without spending a rule on it —
  adding a rule against an unobserved problem is the over-design the trade-off
  budget forbids.

The one place the literature does **not** save us: none of it makes an idle,
non-responding agent wake itself up or time itself out. Every automated-recovery
pattern above assumes a runtime that re-invokes or restarts the worker. We have
no such runtime for an idle session. That gap is not closed by any rule the two
parties follow — it is closed only by the coordinator externally verifying and
restarting. Rule 7 says exactly that, plainly, rather than pretending a cleverer
handshake would fix it.

## The one principle

**Observable state is authoritative; session narrative is not.** Git commits, CI
runs, and PR review threads are the shared truth both sides read and write. A
message is a *pointer* into that state — useful for saying "look here now,"
useless as a substitute for looking. Every rule below is an application of this
one idea in one direction or the other.

## The rules

Nine rules. Each says which side it binds, how it is checked, and what it costs —
because a rule that makes each exchange safe but triples the number of exchanges
is a net loss, and one that can't be checked can't be enforced.

### 1. Stamp every message with the state it was written against — and honour the stamp you receive

**Both sides.** End every cross-session instruction and every report with the
head SHA it was written against — `@<short-sha>`, plus the PR number where one
exists (`@a1b2c3d PR#207`). For a report it is the tree you are describing
(`git rev-parse --short HEAD` in your worktree at send time); for an instruction
it is the tree the coordinator last observed for that session.

On receipt, compare the stamp to observable state. **If a message stamped `@X`
reaches you while your own tree or the PR is already at `@Y ≠ X`, treat its
factual claims as possibly superseded** — re-read git/PR before acting, and read
an instruction that references work you have already done as *already satisfied*,
not as a request to repeat it.

**The structured parent/child control vocabulary is exempt from the raw SHA
stamp** — `RUNNING` / `WAITING_HUMAN` / `BLOCKED_PARENT` / `HANDBACK_POSTED` /
`CLOSED` / `ABORTED` and the rest in the orchestrator file. Those are not
freeform claims about a tree, and their freshness does not rest on a stamp. The
*terminal* transitions are monotonic — you cannot reach `CLOSED` before
`HANDBACK_POSTED`, or `RUNNING` twice — so an out-of-order one is self-evidently
stale. The *recurring informational* ones (`WAITING_HUMAN`, `BLOCKED_PARENT`) are
**not** monotonic and could otherwise arrive late and be mistaken for current;
what protects them is the rule that the coordinator **resolves every ambiguous
control state mechanically via `get_session` + git before acting on it**, never
from the message alone (the idle-decision table already mandates exactly this).
So a stale recurring control message cannot be acted on as current — the
mechanical re-check catches it — which is why the stamp buys nothing here. Stamp
freeform instructions and reports; let each control message be checked against
the state machine and that mandatory re-verification instead.

- *Checked:* a freeform message either carries a `@<sha>` or it does not; the
  recipient's check is one `git rev-parse HEAD`. A control message is checked
  against its lifecycle order (terminal states) or the mandatory `get_session` +
  git re-verification before acting (recurring states).
- *Cost:* one `git rev-parse` per message. Negligible.
- *Kills:* crossed messages (#2), and gives a stale self-report (#3) a detectable
  signature.

### 2. Re-read your own tree before every report; report only what you just observed

**Worker.** Immediately before you report — and *after* any context compaction —
run the read, don't trust memory: `git log --oneline -5`, `git rev-parse HEAD`,
the CI run pinned to *that* SHA (or its `path-ignored / not applicable` status,
when the diff is doc/agent/skill-only and no run fires — that is a valid report
value, not a missing one), and the unresolved-thread count. Report those values.
**If a claim in your report would not survive a reader running the same
command, it does not go in the report.** "I fixed that finding" is memory; the
commit that fixed it, present at the head you just stamped, is evidence.

- *Checked:* the report's SHA / CI / thread claims must equal what the commands
  return — the coordinator re-runs them anyway (#8), so a divergence is visible;
  a CI value of `path-ignored` is checkable against `ci.yml`'s path filters.
- *Cost:* about four read-only commands per report. Cheap.
- *Kills:* stale self-reports (#3), and premature "clean" on the reporter's side
  (#4).

### 3. Decide inside your lane; escalate only the enumerated crossings

**Worker.** A decision the brief already granted you, or one already implied by
an earlier ruling, is yours — make it, report it, do not spend a round trip
asking. Escalate **only** the crossings the implementer file enumerates: a change
of hook point agreed in round 1, a change to the config or storage surface, a
change to user-visible behaviour that was specified for you, or a split into a
second branch. "Already implied by a prior ruling" is never a new question.

This enumerated list is the **leaf implementer's** authority. A **child
orchestrator** acting as a worker uses its own model instead — the orchestrator
delegation rules plus `BLOCKED_PARENT` for what only its parent can unblock — not
this list; the principle is identical (decide in lane, escalate only the defined
crossings), the crossings differ.

- *Checked:* an escalation should map to one of the enumerated crossings; a round
  trip for anything else is a protocol miss the coordinator can name.
- *Cost:* the risk of a wrong autonomous call, bounded by the short enumerated
  list. Net positive against the round-trip tax.
- *Kills:* round-trip cost for predictable decisions (#6).

### 4. One outstanding instruction per channel; a new one supersedes, it does not queue

**Coordinator.** Send the next instruction to a given session only after the
previous one is acknowledged (Rule 5) or observably acted on (a new commit, push,
or PR state change). If circumstances change before then, do **not** stack a
second live instruction — send one that explicitly supersedes: `supersedes my
@X: <new imperative>`. The recipient drops the older one (Rule 1 makes the older
one detectably behind anyway).

dazewell's instinct was "one outstanding request at a time." Adopt its *intent*
— never two live instructions racing on one channel — but via supersession, not a
hard block, because a hard "block until reply" idles a session that could have
proceeded. This serializes **one pair**, never the fleet: other sessions keep
running in parallel.

- *Checked:* at most one un-acted instruction outstanding per pair at any time.
- *Cost:* a channel can sit briefly idle between an ack and the next send.
  Accepted, because the alternative — a crossed pair — costs far more than the
  idle does.
- *Kills:* crossed messages (#2).

### 5. Acknowledge authorized work on receipt; then it is a commitment

**Worker.** When you are told to proceed with specific work, reply with a
one-line start-ack before you begin — what you are starting and the SHA you start
from: `starting <thing> @<sha>`. Then start. This is the readiness signal the
leaf channel otherwise lacks: it currently reports only on completion, so an
authorization that is silently dropped looks identical to one being worked.

A **child orchestrator's** `RUNNING <unit-slug>`, sent once after its preflight,
is this same readiness signal on the parent channel. Like the other control
messages it carries no SHA (Rule 1's carve-out): its freshness comes from the
lifecycle state, not a stamp. So "start-ack" is `starting … @<sha>` from a leaf
implementer and `RUNNING <unit-slug>` from a child orchestrator — one rule, two
channel-appropriate forms.

**Coordinator.** An authorization with **neither a start-ack nor observable git
progress by the next idle notification** is a suspected stall — go to Rule 7. Do
not wait hours to find out; the whole point is to catch #1 in minutes.

- *Checked:* is there an ack, or is there a commit/push, by the next wake?
- *Cost:* one line from the worker. This is the missing working-vs-dead signal.
- *Kills:* silent stalls with authorized work outstanding (#1).

### 6. A review is not "clean" until it is terminal

**Both sides**, worker first. Never conclude an automated review produced no
findings while it is non-terminal for the current head SHA. **"No findings yet"
is not "no findings."** The terminal check is mechanical and specific: on the
reviews endpoint there is a review **from the Copilot reviewer bot** — match its
login case-insensitively on a wildcard, since it appears as
`copilot-pull-request-reviewer[bot]` on the reviews endpoint and `Copilot` on the
comments endpoint — carrying a `submitted_at`, **and whose `commit_id` equals the
head SHA you stamped**. A review with an older `commit_id`, or from a human or
architect rather than the bot, does **not** satisfy it: an older-commit review
pre-dates your latest push and reintroduces the very staleness this rule exists to
stop. Do **not** test `state == "SUBMITTED"` — the API never returns that; a
submitted review's `state` is `COMMENTED`, `APPROVED`, or `CHANGES_REQUESTED`, so
key off `submitted_at`.

If the bounded wait deadline from `nagramx-workflow` step 9 elapses first,
conclude **pending / no-run — never clean**: an absent or still-running review is
reported as exactly that, so nothing downstream mistakes it for a pass.

- *Checked:* a bot review with `submitted_at` set and `commit_id == headSha`
  exists before "clean" is said; otherwise the report says pending / no-run.
- *Cost:* the bounded wait that step 9 already mandates. No new cost.
- *Kills:* premature "clean" conclusions (#4).

### 7. Establish liveness mechanically; restart a non-responding session, do not nurse it

**Coordinator.** Distinguish working / finished / blocked / dead only from idle
notifications + `get_session` + git state — never from narrative, and never by
inferring from silence. The authority for the exact sequence is the
**idle-decision table** in the orchestrator file; the child-orchestrator control
vocabulary (`RUNNING` / `WAITING_HUMAN` / `BLOCKED_PARENT` / `HANDBACK_POSTED` /
`CLOSED` / `ABORTED` …) is its richer instance for that channel. On a suspected
stall (Rule 5), follow that table's own path — **one** status probe, then a
`get_session` + session-tail diagnostic on the next unchanged wake, then escalate
on a second unchanged wake — do **not** invent a shorter "one probe then dead"
sequence, and **never archive a live-but-unresponsive child**. The table already
forbids both; this rule points at it rather than contradicting it.

When that path ends in a genuinely dead session, **no message either party sends
can rescue it.** That is the Two Generals' consequence, not a gap to patch with a
better handshake. So the coordinator verifies externally via git and hands the
remaining work to a *fresh* session (Rule 8). But not before a **safe ownership
handoff**: stop the old session by exact identity and confirm its worktree is
released, per the process-lifecycle skill's stop-and-verify contract, so a fresh
session and a still-live degraded one can never write the same branch or PR —
that collision duplicates commits and races processes. A stalled session's
*uncommitted* edits that were never pushed are unrecoverable; account for that
loss honestly rather than assuming a new worktree inherits them.

- *Checked:* the probe/diagnostic/escalate counts match the idle-decision table,
  not a private shorter rule; no restart is dispatched until the old session is
  confirmed stopped and its worktree released.
- *Cost:* a discarded session plus one fresh dispatch — trivial against a 90-minute
  or 4.5-hour stall.
- *Kills:* silent stalls (#1) and the mechanical-verification burden (#8), and it
  states the honest limit plainly.

### 8. Prefer a fresh session to a degraded one; hand off *before* the stall

**Coordinator.** Context degradation is the root cause (#5), and a self-contained
brief to a fresh session restored function every time it was tried. Hand the
remaining work to a fresh session — brief rebuilt from the template, everything
restated per Rule 9 — on **either mechanical trigger**: a self-report that git
contradicted (a stale report caught by Rule 2), or a *second* suspected stall on
the same session. (A session visibly re-deriving ground it already covered is a
*softer, judgement-based* hint pointing the same way — act on it if you notice
it, but it is deliberately **not** one of the mechanical triggers, because
spotting it means reading the narrative this protocol otherwise distrusts.) Don't
wait for the full stall; the brief is a template you already hold, so re-dispatch
is cheap.

Before the replacement is dispatched, perform the same **safe ownership handoff**
as Rule 7: stop the old session by exact identity, confirm its worktree is
released (process-lifecycle), and account for any uncommitted diff — pushed if the
old session can still reach it, or recorded as lost if it cannot. A merely
degraded but still-live session can otherwise write the same branch concurrently;
never let the fresh session and the old one both hold it.

- *Checked:* the two mechanical triggers are observable events; the third hint is
  explicitly not one. No restart dispatches until the old session is confirmed
  stopped and its worktree released.
- *Cost:* one re-dispatch, bounded because the brief already exists as a template.
- *Kills:* context degradation as root cause (#5), pre-emptively.

### 9. Every instruction is self-contained

**Coordinator.** Write instructions a session that has lost *all* conversational
history can still execute: the imperative action, the SHA/PR it applies to, and
the authority to do it. If understanding an instruction requires remembering this
conversation, it is void after compaction — rewrite it until it is not. Prefer
short and imperative over long and narrative: long instructions correlated with
stalls (#7), and a fresh-session handoff (Rule 8) is only cheap if the brief was
self-contained to begin with. The Phase-3 brief template in the orchestrator file
is the worked example — a fixed block plus verbatim specialist reports, precisely
so no `file:line` survives only in a memory that compaction will drop.

- *Checked:* would it survive with zero history? If not, it is not done.
- *Cost:* a few restated lines per instruction — far cheaper than the re-explain
  round trip it prevents.
- *Kills:* long-instruction stalls (#7); hardens the fresh-session handoff (#5).

## The honest limit, restated

Rules 1–6 and 9 make each exchange *safe and cheap*. They do not, and cannot,
make an idle session resume on its own — nothing the two parties say to each other
can, per the Two Generals' Problem. The only real answer to a session that has
stopped responding is external: the coordinator sees the absence of git progress,
declares it dead through the bounded probe/diagnostic/escalate path, stops it and
releases its worktree, and restarts the work fresh (Rules 7–8). Build
the protocol so that outcome is *cheap and early* — a start-ack turns a 4.5-hour
silent stall into a few-minute one, and a self-contained brief makes the restart
a re-dispatch rather than a re-investigation. That is the win available. Pretending
a message could have woken the stalled session is the one thing this protocol
refuses to do.

**On session forking specifically** — the app offers a *Fork* on every message,
and it is worth being clear that it does not change this. A fork is *self-service*:
a session forks *itself*; there is no lever to fork a *child* from the coordinator,
least of all a non-responding one, which is exactly the session that would need
it. And a fork *copies* history, so it carries the same degraded, compacted
context forward rather than shedding it — the opposite of the fresh brief that
actually restores function. Fork's honest use is proactive, by a session still
alive: branch off before risky, experimental work, or fork at an *earlier* event
to drop accumulated context before it stalls you — a manual approximation of the
checkpoint-resume the literature assumes, but available only while the session can
still act. It does not close the dead-session gap. Nothing the two parties hold
does.

## Applying it while you build

These rules bind the sessions that *do this work*, this one included. If you are
extending or reviewing the protocol across sessions and find a rule unusable in
practice — a stamp nobody can produce cheaply, a check that fires on healthy work
— that is the most valuable finding you can report, not a detail to paper over.
Report it against the rule number.
