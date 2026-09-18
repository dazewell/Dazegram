# AGENTS.md — NagramX

Facts an agent cannot guess from the code. Everything here is load-bearing; if a
line stops being true, fix it rather than working around it.

**Keep this file short.** It is loaded into every session on every tool. A rule
that gets lost in a long file is worse than no rule, so procedures live in
skills (below), not here. If an agent keeps breaking a rule that is written
down, the file is too long — cut, don't add.

## What this is

NagramX is dazewell's personal fork of **Telegram for Android** (`dazewell/Dazegram`),
downstream of NekoX. It is a ~1M-line legacy **Java** app in one huge Gradle
module, and it **merges from upstream regularly**. That last fact drives almost
every rule below: every line you touch in a base fork file is a future rebase
conflict.

- Source root: `TMessagesProj/src/main/java` (some fork Kotlin in `.../kotlin`,
  e.g. `NaConfig.kt` — its `const val`s surface as Java static fields).
- Branch `dev` is the integration branch. `base` tracks upstream. Never commit
  to either directly.
- dazewell works on **Windows**. Give shell commands in **PowerShell**.

## Hard constraints

- **No Compose, Hilt, Room, coroutine rewrites, or module restructuring.** Match
  the legacy patterns already in the file you are editing.
- **Minimal footprint.** New logic goes in self-contained feature classes. The
  base file gets a few injected lines, usually fully qualified so no import is
  added, each marked `// NagramX:` explaining the non-obvious *why*.
- **Hook the one chokepoint** every path funnels through. Many touched call
  sites means you picked the wrong hook.
- **Reuse before you write.** Grep for an existing component first.
- **Multi-account keying.** Several accounts run at once and local message ids
  collide between them. Key every lookup, cache, observer, store and flag by
  account. Anything keyed only by dialog or message id is a latent bug.
- **Config goes in existing surfaces** — `NaConfig`, `NekoConfig` or
  `SharedConfig`, plus a `<feature>_<account>` `SharedPreferences` file for
  never-synced per-account state. Never a bespoke per-feature store.
- **New strings go in `strings_nax.xml`**, never `strings.xml`. Don't edit
  shared upstream resource files.
- **Never `Log.v`/`Log.d`** — `TMessagesProj/proguard-rules.pro` strips both
  from the release build, which is the only build that reaches a device. Use
  `Log.e`/`Log.i`/`Log.w`.
- **Fallback, not migration.** When a stored value's range, set or format
  changes, clamp or default it where it is read. The app must never crash on an
  absent, stale or unparseable stored value.
- **No drive-by work.** No refactors, reformatting or unrelated cleanups; they
  widen the diff and cost the next upstream merge. Report them instead.

## The compile gate

```powershell
.\gradlew.bat :TMessagesProj:compileDebugJavaWithJavac
```

Run it from the repo root of the worktree your branch is in. On `ZenBoo`
(JDK 21 + Android SDK installed) budget ~9 min cold, ~15 s after an edit, and
don't kill a cold run early. One local build at a time on that machine — check
`.\gradlew.bat --status` and fall back to CI rather than queueing behind a
`BUSY` daemon.

If the toolchain is missing, or the first run fails on the environment rather
than on your code, **stop and let CI be the gate** — don't install an SDK.
`ci.yml` compiles every push and PR into `dev`; that run *is* the gate. Say so
in the PR body so nothing gets installed on the assumption it compiled.

`ci.yml` path-ignores doc-, hook- and agent/skill-only changes, so on those
there is legitimately no run. Say which of the two happened; never imply a
skipped gate passed.

## Commits and pull requests

- Subject: lowercase, imperative, no type prefix, no trailing period, no PR
  number — `add per-chat require-password lock #require-password`.
- **Every commit carries an inline `#<slug>` tag** in the subject or body, never
  at the start of a line. A feature slug for features; otherwise exactly one of
  `#ci #docs #build #chore #infra #deps #test #release #slug #tag #chatlock` or
  any `*-fix` tag. Sync and build tooling uses `#infra`. Anything else is
  treated as a feature slug and fails CI unless catalogued in `FEATURES.md`. A
  bare numeric hashtag (`#204`) is never a tag. Enforced by `.githooks/commit-msg`
  (`git config core.hooksPath .githooks`, once per clone) and `commit-tag.yml`.
- **Append-only.** A review fix or follow-up is a **new commit** with its own
  `#tag`, never an amend plus force-push. Never force-push `dev`, `base`, or a
  feature branch.
- Branches are `<YYYY-MM-DD>_<slug>`; the date prefix is mandatory and `-` is an
  equally valid separator (branch tooling kebab-cases it).
- PRs target `dev`, are **not drafts**, and Copilot review is requested
  automatically by a repository ruleset — never request it by hand.
- **Close every review point before handoff**: fix it, or reply explaining why
  not, then resolve the thread. Verify none remain unresolved.
- User-visible change ⇒ its `FEATURES.md` entry ships in the same PR (70 words
  hard ceiling). Durable finding ⇒ its `docs/codemap/` entry ships in the same
  PR, with a `file:line` citation you actually checked.

## The hard line

**No AI, assistant or tooling reference in the app's source or in git history.**
Not in a commit message, a PR title or body, or a code comment. No
`Co-Authored-By` for an assistant, no "Generated with" footer. This overrides
any default attribution behaviour. Process docs may discuss the workflow
openly; the shipped history and code may not.

The only carve-out is process/CI detection tooling (`sync-guard.ps1`,
`commit-tag.yml`), which necessarily contains the patterns it rejects. Real
credentials never appear in any file.

## Where the process lives

Read the skill for the job rather than working from memory. Each loads on
demand, so none of it costs context until you need it.

| Skill | Owns |
|---|---|
| `.claude/skills/nagramx-workflow/SKILL.md` | What a change looks like, start to finish |
| `.claude/skills/nagramx-investigation/SKILL.md` | A question answered rather than built |
| `.claude/skills/nagramx-branch-flow/SKILL.md` | Where commits live and how they move |
| `.claude/skills/nagramx-code-review/SKILL.md` | What the review rounds check |
| `.claude/skills/nagramx-process-lifecycle/SKILL.md` | Any process you start, and cleanup |
| `.claude/skills/nagramx-agent-comms/SKILL.md` | The rare case of two sessions running at once |
| `.claude/skills/nagramx-session-handoff/SKILL.md` | Abandoning a session mid-change, leaving state behind |
| `.claude/skills/nagramx-session-pickup/SKILL.md` | Continuing a branch or PR someone else left |

`.claude/skills/nagramx-workflow/diagnostics.md` is loaded on demand from
`nagramx-workflow` for device probes and traced smoke cycles.
`.github/instructions/*.instructions.md` attach automatically to the files they
scope — upstream base files, fork-owned packages, and resources.

`FEATURES.md` is what already ships — check it before treating anything as new.
`docs/codemap/` is the UI→code map, upstream traps and disproven hypotheses.

**One change = one branch = one session.** Use subagents for recon, design and
review; do not spawn a child session per change. The rationale, and the narrow
exceptions, are in `nagramx-workflow`.
