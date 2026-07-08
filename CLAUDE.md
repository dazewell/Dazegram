# CLAUDE.md

Guidance for working in this repository. These instructions override default behavior — follow them. This file is read by multiple AI tools (Claude Code, GitHub Copilot); keep it tool-agnostic so it works the same everywhere.

**Before starting any NagramX task, read [.claude/skills/nagramx-workflow/SKILL.md](.claude/skills/nagramx-workflow/SKILL.md) first** — in Claude Code, invoke it as the `nagramx-workflow` skill; in any other tool, just open and read the file. It's the source of truth for how we work here: what NagramX is, the reuse-first / minimal-footprint hook style (with concrete hook points and config systems), the two review rounds, the compile gate, coding conventions, commit/history rules, the `FEATURES.md` catalog style (the repo-root `README.md` is just a stable pointer to it), on-device testing, and the dual-package CI. Don't duplicate its content back into this file.

**For anything about git topology** — starting/landing feature branches, syncing onto the base fork, avoiding force pushes, tying a later fix back to a change via its `#tag`, proposing a feature upstream, or the phone-triggered sync-build-Telegram automation — read [.claude/skills/nagramx-branch-flow/SKILL.md](.claude/skills/nagramx-branch-flow/SKILL.md) (the `nagramx-branch-flow` skill). `nagramx-workflow` owns *what a change looks like*; `nagramx-branch-flow` owns *where commits live and how they move*.

**For reviewing a change** — the two review rounds `nagramx-workflow` calls out (plan review before coding, code review after it compiles) — read [.claude/skills/nagramx-code-review/SKILL.md](.claude/skills/nagramx-code-review/SKILL.md) (the `nagramx-code-review` skill). It's the Chief Architect persona plus the Android-and-fork checklist (upstream-merge survivability, minimal-footprint hooks, reuse-first, lifecycle/threading/leak traps), severity calibration, and the reviewer's output format. `nagramx-workflow` says *when* to review; `nagramx-code-review` says *what the review checks*.

**The one hard line:** no AI mentions in git logs (commit messages, PR titles/bodies) or in the app's source (comments included) — no `Co-Authored-By`, no "Generated with" footers, no AI-flavored comments. This file, the skill, `README.md`, `FEATURES.md`, and the memory notes may describe the process openly; the history and code may not.

**Every commit carries a `#<slug>` tag** placed inline in the subject or body, so all commits for a change stay greppable (`git log --grep '#chatlock'`) after its short-lived branch is deleted — the feature slug for features, a category tag (`#ci`, `#docs`, `#build`) otherwise. Merge commits are exempt. Enforced by `.githooks/commit-msg` (run `git config core.hooksPath .githooks` once per clone) and the `commit-tag.yml` CI check. Details in the `nagramx-branch-flow` skill.

## Keep the docs current

The skills, this file, and the persistent memory (`MEMORY.md` + its per-feature maps) are the source of truth for how we work. When a rule, convention, or workflow changes, update all of them in the same session the change is decided, not later. If a correction reveals an existing instruction is wrong or stale, fix it rather than leaving it.
