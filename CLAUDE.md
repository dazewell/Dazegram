# CLAUDE.md

Guidance for working in this repository. These instructions override default behavior — follow them. This file is read by multiple AI tools (Claude Code, GitHub Copilot); keep it tool-agnostic so it works the same everywhere.

**Before starting any NagramX task, read [.claude/skills/nagramx-workflow/SKILL.md](.claude/skills/nagramx-workflow/SKILL.md) first** — in Claude Code, invoke it as the `nagramx-workflow` skill; in any other tool, just open and read the file. It's the source of truth for how we work here: what NagramX is, the reuse-first / minimal-footprint hook style (with concrete hook points and config systems), the two review rounds, the compile gate, coding conventions, commit/history rules, README style, on-device testing, and the dual-package CI. Don't duplicate its content back into this file.

**For anything about git topology** — starting/landing feature branches, syncing onto the base fork, avoiding force pushes, tying a later fix back to a feature, proposing a feature upstream, or the phone-triggered sync-build-Telegram automation — read [.claude/skills/nagramx-branch-flow/SKILL.md](.claude/skills/nagramx-branch-flow/SKILL.md) (the `nagramx-branch-flow` skill). `nagramx-workflow` owns *what a change looks like*; `nagramx-branch-flow` owns *where commits live and how they move*.

**The one hard line:** no AI mentions in git logs (commit messages, PR titles/bodies) or in the app's source (comments included) — no `Co-Authored-By`, no "Generated with" footers, no AI-flavored comments. This file, the skill, `README.md`, and the memory notes may describe the process openly; the history and code may not.

## Keep the docs current

The skills, this file, and the persistent memory (`MEMORY.md` + its per-feature maps) are the source of truth for how we work. When a rule, convention, or workflow changes, update all of them in the same session the change is decided, not later. If a correction reveals an existing instruction is wrong or stale, fix it rather than leaving it.
