# Upstream sync topology

This directory holds the machinery that keeps `dev` in step with the upstream
parent, and the pinned facts that machinery is checked against. Read this before
touching `sync-upstream.yml`, `sync-guard.ps1`, or any pin.

## Provenance

- **Current source parent:** `DrKLO/Telegram`, branch `master`.
- **Former source parent:** `NextAlone/Nagram`. It remains an optional
  compatibility/reference source and the intentional target for suitable
  upstream feature proposals, but routine source sync no longer follows it.
- **Original base fork:** `risin42/NagramX` — now **archived**. The app's About
  screen keeps that historical attribution, and the frozen `base` branch on
  `origin` preserves its history.
- **This repo:** `dazewell/Dazegram` (renamed from `dazewell/NagramX`; the old
  name redirects but is not relied on).

The pin names `NAGRAM_REPO` / `NAGRAM_BRANCH` and the internal remote name
`nagram` are temporary legacy identifiers. Their configured values point to
Telegram and are authoritative; neutral renaming is later mechanical work.

## The anchor

Source history is never merged directly into `dev`. There is an **anchor** and
an append-only `nbase` chain. The recorded anchor advances by a reviewed
[`pins.env`](pins.env) edit after a new snapshot lands:

- **`ANCHOR_SRC`** is the configured source commit whose tree the current
  snapshot copies.
- **`OLD_NBASE`** is the current `origin/nbase` (tree `OLD_NBASE_TREE`) — a
  **locally-authored** commit whose tree is byte-identical to that anchor's,
  importing no upstream author, message or committer. It is the tip of the chain,
  and its single parent is the previous snapshot.

The Telegram re-anchor snapshot is
`e5cc4221decd4c1e12f3a0eef3602ec304372c2b`: its single parent is the old
Nagram-backed `nbase` `981806a992a1665b03906aa33e212fb9af3d7f97`, and its
tree `b406defb637ed56d392f1b934507221b8243822c` is byte-identical to
`DrKLO/Telegram` master
`62b56a07ca7e30e39f7fd00a6728d6bbd716ca1c` (12.10.1 / 7038). Anchor merge
`100c3e1142a3a6681e139bfd73a1e7d05e87a249` records that snapshot as the
second parent of `dev` while keeping the reviewed Dazegram tree from first
parent `f47b9da651580811a70f82ee13e5d8da97568db2`.

`-s ours` is permitted only for that bootstrap re-anchor: it records the
separately proven parent relationship and deliberately imports no tree content.
Normal sync never uses it. Every later source delta is carried by a new snapshot
on `nbase` and an ordinary 3-way merge into `dev`; conflicts or guard failures
block for reviewed reconciliation.

## Steady-state sync (`sync-upstream.yml`)

Every routine sync, on `workflow_dispatch` (no inputs):

1. Resolve the configured source tip and tree (`DrKLO/Telegram` `master`).
2. **No-op fast path.** Compare the resolved source tree against the live
   `origin/nbase` tree. If they are equal there is nothing to import, so the run
   skips straight to success — **no snapshot commit, no ref pushed** — but only
   after it also confirms the recorded facts still describe reality: `origin/nbase`
   equals the pinned `OLD_NBASE`, its tree equals `OLD_NBASE_TREE`, and `dev` still
   contains `nbase` as an ancestor. If the trees are equal but any of those is
   stale, it **blocks loudly** and asks for a repin rather than reporting up to date
   (see below). If the trees differ, it falls through to the full path unchanged.
3. Build a new locally-authored snapshot: tree = the source tree, single parent =
   current `nbase`. This is the new `nbase`.
4. 3-way merge the snapshot into `dev`. **Conflict aborts** — never auto-resolved.
5. Run `sync-guard.ps1` from the trusted `dev` checkout.
6. Only on a clean guard, **atomically** push `dev` and `nbase` together.

**Fail-closed by design.** Merge conflicts at step 4 abort the sync before the
guard runs. For syncs that pass the merge (no conflicts), the guard at step 5
runs unattended and may block on unclassified deltas, protected-path violations,
or other gates before reaching the atomic push at step 6. A real upstream bump
almost always trips either the merge-conflict block or the guard's classification
gates, which is the intended behaviour. Reconciliation requires a PC and manual
review rather than auto-pushing. The anchor advances **only** by a reviewed edit
to `pins.env` after a new snapshot has landed — never by the workflow itself.
When `pins.env`'s `ANCHOR_SRC` / `OLD_NBASE` / `OLD_NBASE_TREE` **match** live
`origin/nbase` — the steady state — `sync-guard-check`'s real-candidate fixture
passes rather than blocking on an `origin/nbase … != pinned OLD_NBASE …`
assertion, and there is no transitional red window. That window opens only between
a reconciliation landing on `dev` and its pins PR merging, and `sync-land.yml`
shrinks it to minutes (see below).

## When it blocks: the published snapshot ref

`sync-upstream.yml` never pushes to `dev` or `nbase` on a block — that part
never changes. But a block that reaches the snapshot step (i.e. not a token
failure and not the no-op fast path) publishes that snapshot commit to a
scratch ref, so the PC starts reconciliation from the snapshot instead of
reconstructing it by hand with `git commit-tree`:

```powershell
git fetch origin '+refs/sync/*:refs/sync/*'
git merge refs/sync/snapshot-<srcshort>
```

The exact ref name (`refs/sync/snapshot-<srcshort>`, `<srcshort>` being the
short SHA of the resolved source commit) is named in both the job
summary and the Telegram ping, along with the conflicting-file list (with
per-file conflict-hunk counts) or the guard's classified violation list,
whichever applies — capped to about ten entries in the Telegram message, with
the full list always in the job summary.

`refs/sync/*` is deliberately not a branch. GitHub's `push` event only fires
for `refs/heads/*` and `refs/tags/*`, so a push to this namespace can never
trigger `sync-guard-check.yml` (which would hard-fail — it checks out the
pushed tree looking for `.github/sync/sync-guard.ps1`, and a bare source tree
doesn't have one) or the `pull_request` trigger on `staging.yml` / `ci.yml`. It also
never shows up in the branch list, the branch picker, or any PR head/base
dropdown, so it cannot be mistaken for a reviewed branch or merged by habit.

Only the **snapshot** is ever published, never the merge candidate that the
guard rejected. The candidate is the exact shape `dev` would take if pushed —
the one object in this whole design that could be pushed straight to
`refs/heads/dev` and skip every gate. The snapshot cannot do that, and
re-merging it into `dev` reproduces the same conflicts deterministically, so
the candidate is trivially re-derivable from the snapshot and buys nothing
extra.

Scratch refs older than 30 days are pruned at the **start** of each run (never
the end — pruning after this run's own publish step could delete the ref a PC
is mid-reconciliation on). A stale one simply falls out of the next run's
prune pass; there is nothing to clean up by hand.

## The no-op fast path

Once the attended re-anchor below finishes, `origin/nbase` carries the pinned
Telegram tree. If the resolved Telegram master tree still equals
`OLD_NBASE_TREE`, a `sync-upstream` run has no delta to apply even when master
points to a newer commit with the same tree. Before this fast path existed the
workflow still minted a snapshot and merged it every run, moving both refs for no
reason — which is exactly how the redundant commits `58eaec2f` (a snapshot whose
tree is identical to its parent's) and the `dev` merge `73455ee65e` (`dev`'s tree
unchanged at `ee336875`) came to exist. **Those two commits are harmless historical
no-ops. They are append-only history and are never to be rewritten or
force-pushed** — the cost was one redundant commit on each ref, nothing more, and
`origin/nbase` remains an ancestor of `origin/dev`.

The decision lives in `Test-SyncFastPath` in `sync-guard.ps1` (a pure function, so
it is unit-tested by the self-test) and the workflow calls it via
`sync-guard.ps1 -FastPathOnly` before building any snapshot. It returns one of
three outcomes:

- **up to date** — the source tree equals `origin/nbase`'s tree **and** all three
  preconditions hold (`origin/nbase == OLD_NBASE`, its tree `== OLD_NBASE_TREE`,
  `dev` contains `nbase`). The run exits success with no commit and no push.
- **blocked** — the source tree equals `origin/nbase`'s tree but a precondition is
  stale. This is deliberately **not** a silent success: an equal tree with a stale
  pin is precisely the state that needs a human, and reporting "up to date" there
  would mask the drift forever. Worse, falling through to the full path would build
  yet another redundant snapshot on top of the live `nbase` (the very defect this
  fast path removes). So it fails loudly and asks the operator to repin
  `OLD_NBASE` / `OLD_NBASE_TREE`.
- **proceed** — the source tree differs, so a real delta may exist. Nothing about
  the full snapshot → merge → guard → signer → atomic-push path changes.

The self-test proves all three directions, including the equal-tree-but-stale-pin
regression, so `-SelfTestOnly` (step 1 of every `sync-guard-check` run) fails if
the decision ever stops discriminating them.

## Landing a reconciliation (`sync-land.yml`)

When `sync-upstream` blocks, the reconciliation is finished by hand on the PC and
then **landed in three ordered steps**: (1) a human PR merges the resolved merge
into `dev`; (2) `origin/nbase` is fast-forwarded onto the reconciliation snapshot;
(3) a PR advances the anchor pins in `pins.env`. The manually-dispatched
`sync-land.yml` automates steps 2 and 3 — the operator's job becomes merge the
reconciliation PR, press one button, review and merge one auto-drafted pins PR.

**The three steps still cannot collapse into one, and the automation does not
change that.** `sync-guard-check` unconditionally fetches the live `origin/nbase`
and asserts it equals the `OLD_NBASE` pinned in the candidate's own `pins.env`.
`origin/nbase` cannot move until the reconciliation is on `dev`, and the pins PR
cannot be green until `origin/nbase` has moved, so no single-PR ordering is ever
green. `sync-land` preserves that ordering; it removes only the hand git.

The **anchor still advances only by a reviewed `pins.env` edit** (see The anchor,
above) — `sync-land` drafts that edit, it does not bypass the review. What it adds
is that the review is now backed by machine-verified evidence rather than three
opaque hex strings, and the pins PR is genuinely gated:

Advancing or replacing the parent is not a supported sync-land operation.
sync-land only ever fast-forwards nbase onto a snapshot of a commit that descends
from the pinned ANCHOR_SRC in the pinned upstream repo. A -LandCheckOnly pass
never implies a parent change is permitted; a parent transition is a human,
attended, pre-certified transaction.

- **`sync-guard.ps1 -LandCheckOnly` runs before any ref moves.** The two obvious
  ancestry facts — old `nbase` is an ancestor of the snapshot, and the snapshot is
  reachable from `dev` — both pass for a snapshot whose *tree was hand-edited
  during reconciliation* (conflicts resolved into the snapshot instead of into
  `dev`). Such a snapshot's tree is no longer byte-identical to any upstream
  commit, so every future 3-way merge base would be wrong, silently, forever —
  the one failure mode where careless automation could make a wrong land *easier*
  than the manual dance. So the mode re-derives, **live from `NAGRAM_REPO`**, the
  upstream commit whose tree the snapshot copies, and asserts: exactly one parent
  equal to the pinned `OLD_NBASE`; the snapshot is the only commit `rev-list`ed
  over that pinned old `nbase`; `snapshot^{tree}` equals that upstream commit's
  tree; the upstream commit descends from the pinned `ANCHOR_SRC`; the pinned
  `ANCHOR_SRC` really is `nbase`'s current tree (the anchor-tree identity nothing
  else checks); the snapshot **is an ancestor of `dev`** (proof the reconciliation
  was actually merged before `nbase` advances onto it — else the button pressed
  too early would fast-forward over changes that never landed and drop them
  silently); and the snapshot's author **and** committer are the sync identity
  (`SYNC_IDENTITY_NAME` / `SYNC_IDENTITY_EMAIL`, reusing the same attribution scan
  as the steady-state guard). Every one is keyed to the *pinned* `OLD_NBASE`, not
  to the live ref, so the same facts hold on a first run and on a re-run after the
  fast-forward already landed. These live in `sync-guard.ps1` behind one
  self-tested mode, so there is no second, weaker copy of snapshot-ancestry logic
  in a workflow to drift out of step. The mode writes its evidence to a file that
  the workflow prints into the pins PR body.
- **The pins PR is created with `SYNC_TOKEN`, never `GITHUB_TOKEN`.** A PR opened
  by the built-in token does not trigger `pull_request` workflows, so
  `sync-guard-check` would be *missing* on it — and an absent required check is
  indistinguishable from a passing one. The whole safety argument for the pins PR
  is that `sync-guard-check` still gates it, so `sync-land` polls and asserts that
  check actually reported on the PR head SHA before it declares the PR ready, and
  fails closed if it never reports within the timeout. This anchor-tree identity
  check stays in `sync-land.yml` and is deliberately **not** added to
  `sync-guard-check.yml`, which points its `nagram` remote at an unreachable URL
  to prove it never contacts upstream.
- **Shared `sync-refs` concurrency and a re-lease before the push.** `sync-land`
  and `sync-upstream` share one `concurrency: sync-refs` lane. `sync-land` also
  re-reads `origin/nbase` immediately before pushing: if it already equals the
  snapshot the move is skipped (an already-landed re-run), otherwise it aborts
  unless `nbase` still equals the pinned `OLD_NBASE` the land check proved the
  snapshot chains onto, so a phone tap that mints a snapshot on the old `nbase`
  mid-land fails safe. The push is non-force with an explicit refspec
  (`<snap>:refs/heads/nbase`); `dev` is never a push target.

It is **idempotent**: if `origin/nbase` already equals the snapshot the
fast-forward is skipped (success, not error); if the pins branch or its open PR
already exists they are reused rather than duplicated; `nbase` is never
force-pushed. So the realistic partial failure — fast-forward lands, PR creation
trips — is fixed by pressing the button again. Pass the snapshot explicitly with
`snapshot=<sha>` — the primary contract. The zero-input form reads
`refs/sync/snapshot-<srcshort>` (a non-branch ref namespace that fires no Actions
runs) and is published by `sync-upstream.yml` whenever a run blocks. Prefer the
explicit SHA when you have it: those scratch refs are pruned by age, and a stale
or absent one makes the zero-input form fail. See [SYNC_TOKEN
configuration](#sync_token-configuration) below for the permissions this needs.
`sync-land.yml` is in `SELF_PROTECT`, so an incoming snapshot can never rewrite
the workflow that holds this credential.

## SYNC_TOKEN configuration

Both `sync-upstream.yml` and `sync-land.yml` push refs and open PRs as one identity: a
GitHub fine-grained personal access token, stored as the `SYNC_TOKEN` repository
secret, scoped to `dazewell/Dazegram` only. To recreate it from scratch, grant exactly
these three repository permissions:

| Permission | Why |
| --- | --- |
| Contents: write | push `dev` (steady-state sync) and `nbase` (both workflows fast-forward or merge onto it). |
| Workflows: write | Retained for `manifest` policy, where a snapshot may change `.github/workflows/`. Policy `none` rejects those paths instead. |
| Pull requests: write | `sync-land.yml` opens the pins PR with `SYNC_TOKEN` rather than `GITHUB_TOKEN`, so `sync-guard-check` actually runs on it (see above) — a PR opened by the default token would arrive with that check missing. |

`sync-upstream.yml`'s "Verify SYNC_TOKEN can push dev" step and `sync-land.yml`'s
"Verify SYNC_TOKEN can push" step each prove Contents: write with a non-mutating
dry-run before any ref moves; `sync-land.yml` similarly proves Pull requests:
write before opening a PR. Workflows: write is retained through this parent
transition but is not exercised under policy `none`. Permanently recertifying or
reducing that scope is separate work.

## Snapshot workflow policy

`WORKFLOW_POLICY` in `pins.env` is a reviewed, trusted pin with two allowed values:

- **`manifest`** requires the snapshot's `.github/workflows/*` paths and blobs to
  match every row in `workflow-manifest.tsv` exactly. The manifest must be non-empty.
- **`none`** requires an empty manifest and zero `.github/workflows/*` paths in the
  snapshot. Any workflow path blocks.

Telegram has no workflow tree, so the steady-state policy is `none` and
`workflow-manifest.tsv` contains only its exact `path<TAB>blob` header.
Manifest-policy fixtures remain synthetic and independent of the live parent;
both policies stay tested.

## Attended Telegram re-anchor

This is a one-time parent replacement, not a `sync-land` operation. The
procedure here is the single source of truth for its provenance and ordering;
branch-flow remains the source for ordinary PR merge mechanics.

**State already established:** `dev` was fast-forwarded to exact anchor merge
`100c3e1142a3a6681e139bfd73a1e7d05e87a249` during a human-attended
ruleset transaction, and ruleset `22861936` was restored active afterward.
The four build/sync workflows (`ci`, `staging`, `sync-upstream`, `sync-land`)
remain disabled; `sync-guard-check` and `process-rules` remain active. No app tree
changed, so no release build is expected.

**Pre-move window:** this pins PR is reviewed while live
`origin/nbase` is still
`981806a992a1665b03906aa33e212fb9af3d7f97`. Its candidate pins intentionally
name `e5cc4221decd4c1e12f3a0eef3602ec304372c2b` and policy `none`.
The real-candidate fixture exits at
`.github/workflows/sync-guard-check.yml:155` because live `nbase` does not
equal candidate `OLD_NBASE`; later manifest/none fixtures do not run. Only
#368 is expected red in this window.

**Post-move window:** after `nbase` moves, #368 can pass, but other branches
whose checkouts still pin the old `nbase` go red until they merge updated
`dev` and rerun their checks. Freeze `dev` across the whole attended
transaction: no other PR merges from now until #368 lands and each affected
active branch has incorporated the updated `dev`.

The human-attended remainder is fail-closed:

1. Record the PR's reviewed head SHA and require the live PR head to equal it
   immediately before moving `nbase`.
2. Fast-forward only the exact snapshot with
   `git push origin e5cc4221decd4c1e12f3a0eef3602ec304372c2b:refs/heads/nbase`.
   This is a plain refspec: never force, and never push `dev` from this step.
   The credential must have Workflows: write because this specific move deletes
   `debug.yml`, `pr.yml`, and `release.yml` from `nbase`; do not change its
   configured scope during the transaction.
3. Moving `nbase` does **not** emit a pull-request event and does not rerun this
   PR's check. Rerun the existing pull-request guard run; select the result only
   when workflow identity, `event=pull_request`, exact reviewed head SHA, and PR
   association all match. Abort on zero or multiple candidates. If GitHub needs
   a fresh merge-ref event, reopen the unchanged PR; do not add an empty commit.
4. Require `Guard self-test, wiring, and real fixture` to succeed on that current
   PR candidate, require exact context `Every commit carries a` to succeed, and
   recheck that the live PR head still equals the reviewed SHA.
5. The human merges the `.github/sync/**` PR using branch-flow's SHA-bound squash
   command with `--match-head-commit <reviewed-sha>`. Do not use admin/auto mode,
   override the subject/body, or request branch deletion.
6. Verify live `pins.env` and `origin/nbase` agree, the current Telegram master
   tree equals `OLD_NBASE_TREE`, and current Telegram master descends from
   `ANCHOR_SRC`. Tip SHA equality is not required. Then re-enable the four
   disabled workflows.
7. Immediately before dispatching one verification sync, resolve
   `DrKLO/Telegram` master and its tree again. If the tree still equals
   `b406defb637ed56d392f1b934507221b8243822c`, expect the `uptodate`
   no-op path (no snapshot and no ref push), regardless of the tip commit SHA.
   If the tree differs, expect the full path and treat a guard block as a new
   source-delta result, not a failed re-anchor.

## Files

| File | Purpose |
| --- | --- |
| `pins.env` | Scalar invariants — parent and workflow policy, anchor, keystore blob + cert, gitmodules blob, the vendored-native table (boringssl/libyuv/openh264/tlottie_lib/tlottie), layer floors, Ayu schema. Read from PRE, never from a candidate. |
| `protected-paths.tsv` | The 49 fork-owned paths that must stay byte-identical to `dev` (signing key, Firebase config, branding, README, `.gitmodules`). |
| `workflow-manifest.tsv` | The approved `.github/workflows` set under `manifest` policy. Under `none`, keep the file and its header row but remove every data row. |
| `sync-guard.ps1` | The gate. Self-tests, then classifies every tree delta. Also runs the pre-land snapshot check for `sync-land.yml` (`-LandCheckOnly`). |

## What the guard checks per sync — and what it cannot

**Machine-checked on every run, unattended (blocks the auto-push):**

- Tree partition: no added path, no removed path — every delta must be an in-place
  modification of a pre-existing shared file, or it blocks.
- The 49 protected blobs byte-identical in PRE and candidate.
- The guard and its workflows unchanged by the candidate.
- `.github/workflows` in the snapshot obeys the pinned policy: exact approved
  path/blob rows under `manifest`, or no workflow paths under `none`.
- `.gitmodules` blob unchanged; every vendored native keeps its pinned git object
  shape — boringssl, libyuv, openh264 and tlottie_lib stay `040000 tree`, and the
  tlottie gitlink keeps its pinned `160000 commit`. The table is data in `pins.env`
  (`VENDORED_NATIVES`), so a `040000 tree` silently turning into a `160000 commit`
  submodule (as the 12.10.1 default merge did to libyuv and openh264) blocks.
- Layer floors: `tw/nekomimi` ≥ 172 files, `com/radolyn` = 68, `strings_nax` ≥
  726 entries, `NaConfig` ≥ 262 `addConfig`.
- Ayu schema: 4 entities, `VERSION=27`, `MIN_SUPPORTED_VERSION=21`, migrations
  wired to the current version.
- Signing: keystore + signing-config blobs pinned, **and** the alias resolves to a
  `PrivateKeyEntry` whose certificate exports the pinned SHA-256 and subject CN
  (proven on the runner with `keytool`, password only ever in a child-process env
  var).
- **Executable Gradle build surface** (`build.gradle`, `settings.gradle`,
  `gradle/wrapper/gradle-wrapper.properties`, `gradlew`, `gradlew.bat`,
  `buildSrc/**`, `gradle.properties`) stays in the fork delta, so an upstream-only
  change to it always lands in the double-modified intersection and blocks (see
  `GRADLE_SURFACE`). `gradle.properties` carries `APP_PACKAGE` and the APK version
  inputs and is in neither `protected-paths.tsv` nor a blob pin, so this membership
  is the only thing stopping an upstream-only edit to it from auto-applying.
- Snapshot ancestry: exactly one parent (current `nbase`), source descends from
  the recorded anchor, source not an ancestor of the snapshot.
- No upstream commit imported into `dev`; no prohibited attribution in the
  metadata of the two commits a sync introduces.

**NOT machine-checked here — needs a PC and a human (this is why a real upstream
bump routes to reviewed reconciliation rather than auto-pushing):**

- The **per-hunk semantic reading** of the ~627 shared-and-differing source files:
  whether a fork-sensitive hunk was resolved to the right side.
- **Fork call-edge and declaration survival** — that a retained fork method still
  has its callers and its declaration after the merge.
- Whether an **upstream-only** modification — a file upstream changed but the fork
  did not — is semantically safe to take. The guard blocks the fork ∩ upstream
  *double-modified* intersection (the dangerous silent-revert case), but an
  upstream-only change auto-applies and its per-hunk correctness is not read here.
- On-device behaviour, **and compilation itself**. The guard gates the *push*;
  after the refs move, `ci.yml` compiles the change (fast Java/Kotlin gate) and
  `staging.yml` builds the dual-package APK — both *after* the refs have already
  moved. So a guard-clean sync that breaks a fork call edge lands on the trunk
  first and is caught by a red build afterwards, not held back by the guard.

Do not read the machine gate as "all 627 semantic gates ran" across the
shared-and-differing files. It did not. The typical sync path blocks early:
conflicts at the merge step (step 3) abort before the guard runs, and
unclassified deltas are detected by the guard at step 4. An upstream bump almost
always trips one of these blocking gates, requiring PC reconciliation. The rare
case where the sync passes both the merge and the guard is when the upstream
delta is sufficiently small and scoped to fork-layer-only paths. Even then,
auto-push is justified only because an ordinary 3-way merge preserves `dev`'s
delta when there is no conflict and no unclassified delta — not because the
guard has comprehensively validated semantic correctness across all
shared-and-differing files.

## Signer identity — certificate, subject, and key-entry type

The signer is proven on the runner in three parts, all against the keystore blob
the guard already pinned, with the store password only ever on `-storepass:env`
and keytool forced to English (`JAVA_TOOL_OPTIONS=-Duser.language=en …`) so its
labels are deterministic:

- the alias resolves to a **`PrivateKeyEntry`** (`keytool -list -v`) — an entry
  that actually holds a private key and can sign;
- the exported certificate's DER SHA-256 == `4056b5df…`;
- its subject carries `CN=Dmitriy Babenko`.

An earlier version of this note claimed the SHA-256 + subject check *subsumed* any
alias/entry check and that no third check was needed. **That was wrong**, and the
counter-example is exactly why the entry-type gate exists: an alias can be a
**`trustedCertEntry`** holding precisely the pinned certificate but **no private
key**. `keytool -exportcert` succeeds, the SHA-256 matches, the subject matches —
and that alias cannot sign a thing. Certificate *selection* is not the same as
selecting a signing-capable *key*, so all three checks are load-bearing; none is
redundant.

The cases:

- alias absent → blocked (non-empty `ALIAS_NAME`).
- alias present, entry not `PrivateKeyEntry` (e.g. `trustedCertEntry`) → blocked
  (can't sign).
- alias present, certificate ≠ the pinned SHA-256 → blocked.
- alias present, subject ≠ `CN=Dmitriy Babenko` → blocked.
- alias present, `PrivateKeyEntry`, cert == pin, subject == pin → the expected
  signing key. Push.

No alias **label** is pinned: a label adds no case the three checks above don't
already cover, and pinning it would put a secret-adjacent string in a committed
file. What's constrained is the signing *capability* and *identity*, not the name
pointing at them. Parsing discipline: the `keytool -list` output is captured and
never printed (it carries `Alias name: <alias>`), and the `Entry type:` line is
matched specifically rather than blob-searched for the string.

## Known future block cause: the `generated with` token

The attribution scan (guard 14) matches a small set of tokens against the source
lines a sync adds. One of them targets the `generated with [assistant]` footer
that AI tools append. The phrase `generated with` on its own is **common in
vendored third-party sources** — an unscoped `git grep -i 'generated with'` finds
dozens of files on `dev` (boringssl, sqlite, webrtc, openh264, and similar), and
the strings `generated without` and `regenerated with` appear across those trees
too. So the token is deliberately **narrowed** to fire only when `generated with`
is followed by a markdown link or a known assistant name — not on bare
`generated with protoc`, `generated without warranty`, or `regenerated with
autoconf`. (The self-test asserts both directions: the assistant footer is
caught, the three vendored phrases are not.)

Even so, a future upstream delta touching those vendored trees could carry a real
assistant footer and **block the sync** — that is a *resolve-on-the-PC* event, the
safe direction (it blocks rather than passing a possible violation). If you hit
it, reconcile on the PC; do **not** widen or weaken the token to make the sync
pass — narrowing it further to dodge a real footer would defeat the guard.

## Rejected proposals

Recorded here so they aren't re-proposed from scratch.

- **`.gitattributes` / `merge=union` merge drivers for append-only fork
  surfaces** (`strings_na.xml`, `NaConfig.kt`). Rejected. It buys **zero**
  unattended completions: `Test-Partition` in `sync-guard.ps1` blocks the
  entire fork-intersect-upstream double-modified set independent of whether
  the merge left a visible delta, and a union hunk can only arise where both
  sides changed the file — which puts that file in that set by construction.
  So union converts a *conflict* block into a *guard* block; it does not avoid
  either. Three further reasons: `.gitattributes` applies to local PC merges
  too, where nothing re-checks the union output; the layer floors in
  `pins.env` are MINIMUMS, so a duplicated or misordered key from a union
  merge can only push counts up and is structurally uncatchable; and any
  custom (non-built-in) merge driver is a silent no-op on any machine lacking
  the matching `merge.<name>.driver` git config — a rule that looks like it is
  in force and is not.
- **Relaxing `sync-guard-check.yml`'s real-candidate fixture to accept a
  descendant of the pinned `OLD_NBASE`**, to shrink the red-check window on
  unrelated PRs between a reconciliation's fast-forward (branch-flow step 2)
  and its anchor-pin update (step 3). Rejected. On a real-delta sync the guard
  receives `-OldNbase` from *live* `origin/nbase`, not from the pin; the
  pinned `OLD_NBASE` is consulted only by the fast path, which runs only when
  the trees are already equal. That check is therefore the **sole** detector
  of a stale `OLD_NBASE` when there is a delta. Relaxing it to tolerate a
  descendant would delete a check with no backup anywhere else in the system —
  it would keep passing right through the window where the pin actually is
  stale, which is the one case it exists to catch.
