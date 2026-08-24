# Upstream sync topology

This directory holds the machinery that keeps `dev` in step with the upstream
parent, and the pinned facts that machinery is checked against. Read this before
touching `sync-upstream.yml`, `sync-guard.ps1`, or any pin.

## Provenance

- **Original base fork:** `risin42/NagramX` — now **archived**. It is still the
  `source` remote and the acknowledgment in the About screen credits it, but it
  is no longer synced from. Those references are history, not live URLs.
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

The anchor advances **only** by a reviewed edit to `pins.env` after a new
snapshot has landed — never by the workflow itself. The pins here still record
`e09f49fa` / `c21ee8ac`; the first steady-state run is **expected to block**,
because Nagram's current tip adds a new `BRANDING.md` (an unreviewed path). That
block is the guard working.

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
- Signing: keystore + signing-config blobs pinned, **and** the alias exports the
  pinned certificate SHA-256 (proven on the runner with `keytool`, password only
  ever in a child-process env var).
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
- Whether a **modified** shared file quietly lost fork behaviour while staying a
  "clean" 3-way merge. The guard confirms sensitive *pinned* files are untouched;
  it does not read the semantics of every ordinary modified file.
- On-device behaviour. Nothing here proves the build runs on a phone.

Do not read the machine gate as "all 549 semantic gates ran." It did not. Auto
push is justified only because an ordinary 3-way merge preserves `dev`'s delta
when there is no conflict and no unclassified delta — and a real upstream bump
almost always trips the new-path or conflict guard first, which is the point.
