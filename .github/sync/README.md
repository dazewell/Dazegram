# Upstream sync topology

This directory holds the machinery that keeps `dev` in step with the upstream
parent, and the pinned facts that machinery is checked against. Read this before
touching `sync-upstream.yml`, `sync-guard.ps1`, or any pin.

## Provenance

- **Original base fork:** `risin42/NagramX` — now **archived**. Historically this
  was the base fork, and the app's About screen retains that attribution. The
  `source` remote and the old sync flow through it are no longer configured; the
  full history is preserved in `origin/base`.
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

Instead there is an **anchor** and an `nbase` chain:

- **Anchor source** `e09f49fa8c2dde…` — the Nagram 12.10.0 commit whose tree we
  anchor on.
- **Snapshot** `c21ee8ac2489…` (`origin/nbase`) — a **locally-authored** commit
  whose tree is byte-identical to the anchor's (`5ecc658245…`), importing no
  upstream author, message or committer. Its single parent is `b206febda45b…`.
- **Anchor merge** — a `-s ours` merge that records `nbase` as a second parent of
  `dev` while keeping `dev`'s tree byte-identical. After it,
  `merge-base(dev, c21ee8ac) = c21ee8ac`.

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
2. Build a new locally-authored snapshot: tree = Nagram's tree, single parent =
   current `nbase`. This is the new `nbase`.
3. 3-way merge the snapshot into `dev`. **Conflict aborts** — never auto-resolved.
4. Run `sync-guard.ps1` from the trusted `dev` checkout.
5. Only on a clean guard, **atomically** push `dev` and `nbase` together.

**Fail-closed by design.** The system blocks at step 3 (merge conflict) before
any classification or guard runs. A real upstream bump almost always trips the
merge conflict guard first, which is the intended behaviour. Reconciliation
requires a PC and manual review rather than auto-pushing. The anchor advances
**only** by a reviewed edit to `pins.env` after a new snapshot has landed —
never by the workflow itself. The pins here still record `e09f49fa` / `c21ee8ac`;
the first steady-state run is **expected to block**, because Nagram's current
tip adds a new `BRANDING.md` (an unreviewed path). That block is the system
working.

## Files

| File | Purpose |
| --- | --- |
| `pins.env` | Scalar invariants — anchor, keystore blob + cert, gitmodules blob, BoringSSL entry, layer floors, Ayu schema. Read from PRE, never from a candidate. |
| `protected-paths.tsv` | The 49 fork-owned paths that must stay byte-identical to `dev` (signing key, Firebase config, branding, README, `.gitmodules`). |
| `workflow-manifest.tsv` | The approved `.github/workflows` set the snapshot may carry (Nagram's `debug`/`pr`/`release`). Any addition or change blocks. |
| `sync-guard.ps1` | The gate. Self-tests, then classifies every tree delta. |

## What the guard checks per sync — and what it cannot

**Machine-checked on every run, unattended (blocks the auto-push):**

- Tree partition: no added path, no removed path — every delta must be an in-place
  modification of a pre-existing shared file, or it blocks.
- The 49 protected blobs byte-identical in PRE and candidate.
- The guard and its workflows unchanged by the candidate.
- `.github/workflows` in the snapshot matches the approved manifest exactly.
- `.gitmodules` blob unchanged; BoringSSL stays a vendored `040000 tree`.
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
  `buildSrc/**`) stays in the fork delta, so an upstream-only change to it always
  lands in the double-modified intersection and blocks (see `GRADLE_SURFACE`).
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

Do not read the machine gate as "all 549 semantic gates ran." It did not. The
typical sync path is **fail-closed at the merge step before the guard runs** —
an upstream bump almost always creates conflicts or unclassified deltas that
abort at step 3, requiring PC reconciliation. The rare case where no conflict
occurs and the guard runs is when the upstream delta is sufficiently small and
scoped to fork-layer-only paths. Even then, auto-push is justified only because
an ordinary 3-way merge preserves `dev`'s delta when there is no conflict and no
unclassified delta — not because the guard has comprehensively validated
semantic correctness across all 627 shared-and-differing files.

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
