# Upstream sync topology

This directory holds the machinery that keeps `dev` in step with the upstream
parent, and the pinned facts that machinery is checked against. Read this before
touching `sync-upstream.yml`, `sync-guard.ps1`, or any pin.

## Provenance

- **Original base fork:** `risin42/NagramX` — now **archived**. Historically this
  was the base fork, and the app's About screen retains that attribution. The
  `source` remote and the old sync flow through it are no longer configured; the
  full history is preserved in the `base` branch on `origin`.
- **Current parent:** `NextAlone/Nagram` (`nagram` remote). This is where
  upstream changes now come from.
- **This repo:** `dazewell/Dazegram` (renamed from `dazewell/NagramX`; the old
  name redirects but is not relied on).

`dev` was already at Telegram **12.10.0**, the same base as Nagram's tip at the
time of the parent move — both are 12.10.0 plus different fork layers. So there
was **no upstream code to import** at the switch: the reconciliation tree is
byte-identical to `dev`.

## The anchor

Direct-merging Nagram into `dev` is unusable: the merge base is `b206febda45b…`
and a dry run reports **503 conflicts**. That does not change, and this mechanism
does not pretend to change it.

Instead there is an **anchor** and an `nbase` chain. The recorded anchor advances
along that chain by a reviewed `pins.env` edit each time a new snapshot lands. The
live values are in [`pins.env`](pins.env), which is the source of truth — read
them there, not here, because this file would go stale the moment the next
snapshot lands:

- **`ANCHOR_SRC`** is the upstream Nagram commit whose tree the current snapshot
  copies.
- **`OLD_NBASE`** is the current `origin/nbase` (tree `OLD_NBASE_TREE`) — a
  **locally-authored** commit whose tree is byte-identical to that anchor's,
  importing no upstream author, message or committer. It is the tip of the chain,
  and its single parent is the snapshot before it, back to the bootstrap below.

The chain was **bootstrapped** at 12.10.0: the first snapshot `c21ee8ac…` (tree
`5ecc658245…`, byte-identical to the 12.10.0 anchor `e09f49fa8c…`) had parent
`b206febda45b…`, and a `-s ours` **anchor merge** recorded it as a second parent of
`dev` while keeping `dev`'s tree byte-identical, so `merge-base(dev, c21ee8ac) =
c21ee8ac`. Each later snapshot chains onto the one before it.

What the anchor buys: a **future snapshot** built on `nbase` (parent = `nbase`,
tree = a newer Nagram tree) 3-way-merges into `dev` against the 12.10.0 base, so
`dev` receives exactly Nagram's real upstream delta. The payoff is only through
`nbase`. The anchor does **not** change the direct `nagram/dev` merge base — that
stays `b206febda45b…` with its 503 conflicts.

Why `-s ours` is the right strategy here, not a shortcut: `dev` and Nagram's tip
are both Telegram 12.10.0 plus different fork layers, so there is nothing upstream
to import. An ordinary 3-way merge of the two conflicts on 503 paths; resolving
every one of those conflicts lands on a tree byte-identical to `dev` — which was
verified against the full-reconciliation branch before that branch was deleted.
`-s ours` records that same result directly. It *asserts* the outcome rather than
re-deriving it, which is only sound because the two trees carry no upstream delta
to merge; do not reuse `-s ours` for a sync that actually has a delta.

## Steady-state sync (`sync-upstream.yml`)

Every routine sync, on `workflow_dispatch` (no inputs):

1. Resolve Nagram/dev's tip and tree.
2. **No-op fast path.** Compare the resolved source tree against the live
   `origin/nbase` tree. If they are equal there is nothing to import, so the run
   skips straight to success — **no snapshot commit, no ref pushed** — but only
   after it also confirms the recorded facts still describe reality: `origin/nbase`
   equals the pinned `OLD_NBASE`, its tree equals `OLD_NBASE_TREE`, and `dev` still
   contains `nbase` as an ancestor. If the trees are equal but any of those is
   stale, it **blocks loudly** and asks for a repin rather than reporting up to date
   (see below). If the trees differ, it falls through to the full path unchanged.
3. Build a new locally-authored snapshot: tree = Nagram's tree, single parent =
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
short SHA of the resolved Nagram source commit) is named in both the job
summary and the Telegram ping, along with the conflicting-file list (with
per-file conflict-hunk counts) or the guard's classified violation list,
whichever applies — capped to about ten entries in the Telegram message, with
the full list always in the job summary.

`refs/sync/*` is deliberately not a branch. GitHub's `push` event only fires
for `refs/heads/*` and `refs/tags/*`, so a push to this namespace can never
trigger `sync-guard-check.yml` (which would hard-fail — it checks out the
pushed tree looking for `.github/sync/sync-guard.ps1`, and a bare Nagram tree
doesn't have one) or `staging.yml`'s unfiltered `pull_request` trigger. It also
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

`origin/nbase` currently carries Nagram's tree with nothing outstanding to import,
so a `sync-upstream` run has no delta to apply. Before this fast path existed the
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
| Workflows: write | the snapshot tree carries `.github/workflows/`, so any push whose diff touches a workflow file needs this even when the ref itself is `dev`/`nbase`. |
| Pull requests: write | `sync-land.yml` opens the pins PR with `SYNC_TOKEN` rather than `GITHUB_TOKEN`, so `sync-guard-check` actually runs on it (see above) — a PR opened by the default token would arrive with that check missing. |

Missing any one of these fails a run — `sync-upstream.yml`'s "Verify SYNC_TOKEN can
push dev" step, and `sync-land.yml`'s "Verify SYNC_TOKEN can push" and "Verify
SYNC_TOKEN can open pull requests" steps, each probe their respective permission
non-mutating and before any ref moves, and name the specific missing permission in the
failure rather than surfacing only a generic auth error after a ref has already moved.

## Files

| File | Purpose |
| --- | --- |
| `pins.env` | Scalar invariants — anchor, keystore blob + cert, gitmodules blob, the vendored-native table (boringssl/libyuv/openh264/tlottie_lib/tlottie), layer floors, Ayu schema. Read from PRE, never from a candidate. |
| `protected-paths.tsv` | The 49 fork-owned paths that must stay byte-identical to `dev` (signing key, Firebase config, branding, README, `.gitmodules`). |
| `workflow-manifest.tsv` | The approved `.github/workflows` set the snapshot may carry (Nagram's `debug`/`pr`/`release`). Any addition or change blocks. |
| `sync-guard.ps1` | The gate. Self-tests, then classifies every tree delta. Also runs the pre-land snapshot check for `sync-land.yml` (`-LandCheckOnly`). |

## What the guard checks per sync — and what it cannot

**Machine-checked on every run, unattended (blocks the auto-push):**

- Tree partition: no added path, no removed path — every delta must be an in-place
  modification of a pre-existing shared file, or it blocks.
- The 49 protected blobs byte-identical in PRE and candidate.
- The guard and its workflows unchanged by the candidate.
- `.github/workflows` in the snapshot matches the approved manifest exactly.
- `.gitmodules` blob unchanged; every vendored native keeps its pinned git object
  shape — boringssl, libyuv, openh264 and tlottie_lib stay `040000 tree`, and the
  tlottie gitlink keeps its pinned `160000 commit`. The table is data in `pins.env`
  (`VENDORED_NATIVES`), so a `040000 tree` silently turning into a `160000 commit`
  submodule (as the 12.10.1 default merge did to libyuv and openh264) blocks.
- Layer floors: `tw/nekomimi` ≥ 172 files, `com/radolyn` = 57, `strings_nax` ≥
  599 entries, `NaConfig` ≥ 262 `addConfig`.
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
  `staging.yml` compiles the dual-package APK ~15 minutes *after* the refs have
  already moved. So a guard-clean sync that breaks a fork call edge lands on the
  trunk first and is caught by a red build afterwards, not held back by the guard.

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
