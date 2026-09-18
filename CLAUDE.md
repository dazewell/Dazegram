# CLAUDE.md

**Read [AGENTS.md](AGENTS.md) first.** It is the single source of truth for this
repository's facts and rules, and it is shared by every tool. Nothing in it is
repeated here.

This file covers only what is specific to Claude Code.

## Skills

The process lives in `.claude/skills/`, loaded on demand:
`nagramx-workflow` (what a change looks like), `nagramx-branch-flow` (where
commits live and how they move), `nagramx-code-review` (what the review rounds
check), `nagramx-process-lifecycle` (any process you start, and cleanup),
`nagramx-agent-comms` (the rare case of two sessions running at once),
`nagramx-session-handoff` (abandoning a session mid-change) and
`nagramx-session-pickup` (continuing a branch someone else left).

Invoke the skill for the job rather than working from memory.

## Subagents

`.claude/agents/` mirrors the specialists as thin stubs that read their
`.github/agents/` counterpart and follow it, so there is one source of truth per
role: `nagramx-scout` (read-only recon), `nagramx-ux` (behaviour and placement),
`nagramx-architect` (both review rounds).

Drive a change from the main conversation — it owns the branch and writes the
code — and delegate the read-and-judge work to those subagents. That is the
same shape as the Copilot CLI side, where `nagramx-implementer` is the agent
that owns a change end to end.

There is deliberately no orchestrator stub: a Claude Code subagent cannot create
sessions, so the coordinating role is not expressible here. For the rare
multi-change batch, coordinate from the main conversation.

## Imported audit agents are not part of the pipeline

`.github/agents/` also holds third-party agents vendored from
[awesome-copilot](https://github.com/github/awesome-copilot) — `quality-playbook`
(backed by `.github/skills/quality-playbook/`), `wg-code-sentinel`,
`sast-sca-security-analyzer`, and `tech-debt-remediation-plan`.

**Do not invoke any of them during a change.** They are whole-repo sweeps
dazewell starts by hand. Several declare edit capability and none were written
with this fork's upstream-merge or minimal-footprint constraints in mind, so one
let loose mid-change produces a diff the architect will reject. This includes the
`quality-playbook` *skill*, whose broad triggers (`spec audit`, `Council of
Three`, `fitness-to-purpose`, `coverage theater`) make it auto-discoverable —
never invoke it as a step inside the pipeline.

Leave those files byte-identical to upstream so they can be refreshed by
re-download, and treat their tool-resolution warnings as expected.

## Keep the docs current

`AGENTS.md`, the skills, and the persistent memory (`MEMORY.md` and its
per-feature maps) are the source of truth for how we work. When a rule changes,
update them in the same session the change is decided. If a correction reveals an
existing instruction is wrong or stale, fix it rather than leaving it.

The bias is **subtraction**. These files are read in full on every session, and
an instruction that gets lost in a long file is worse than no instruction. If an
agent keeps breaking a rule that is written down, treat the file's length as the
cause and cut something.
