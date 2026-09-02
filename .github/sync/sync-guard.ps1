#requires -Version 7

<#
    sync-guard.ps1 — the gate on the snapshot-mediated nbase sync.

    This script is the whole reason the sync is allowed to push without a human.
    It is invoked INSIDE the sync job from the trusted PRE (dev) checkout, before
    any ref is written, and it decides — mechanically, on measured facts — whether
    the candidate is a fully-classified, guard-clean merge that may be pushed, or
    anything else, in which case it blocks and the job pushes nothing.

    Two design rules this file exists to honour:

      1. It self-tests before it is trusted. Every comparison validator is run
         against a known-BAD input and a known-GOOD input first; if a bad input
         does not fail, or a good input does not pass, the guard refuses to run.
         This project has shipped six checks that reported success without ever
         meaningfully running — an absent check is indistinguishable from a
         passing one. So here a check proves it can fail before its pass counts.

      2. It never trusts the candidate. Pins, the protected manifest and the
         workflow manifest are read from PRE. The candidate is only ever measured,
         never asked what the rules are.

    Every guard number below maps to the architect-approved guard contract.

    Exit codes:  0 = clean, may push   1 = blocked   2 = guard self-test failed
#>

[CmdletBinding()]
param(
    # The reconciliation before this sync (current dev). Rules are read from here.
    [string]$PreRef,
    # Current origin/nbase — the one parent the new snapshot must have.
    [string]$OldNbase,
    # The synthetic snapshot commit built this run (the new nbase).
    [string]$NewSnapshot,
    # The upstream source commit whose tree the snapshot copies (Nagram/dev tip).
    [string]$NewSrc,
    # The merge candidate: merge(PreRef, NewSnapshot). The proposed new dev.
    [string]$NewDev,
    [string]$PinsFile         = "$PSScriptRoot/pins.env",
    [string]$ProtectedFile    = "$PSScriptRoot/protected-paths.tsv",
    [string]$WorkflowManifest = "$PSScriptRoot/workflow-manifest.tsv",
    # Run only the self-test (used by the always-on required check). No refs needed.
    [switch]$SelfTestOnly,
    # Run only the no-op fast-path decision, from the resolved refs of a sync run,
    # BEFORE any snapshot is built. Reads OLD_NBASE/OLD_NBASE_TREE from the trusted
    # pins and needs -PreRef, -OldNbase and -NewSrc. Exit: 0 = proceed to the full
    # path, 3 = up to date (skip the snapshot/push), 1 = blocked (equal tree but a
    # stale pin — repin needed). No refs are written in this mode.
    [switch]$FastPathOnly,
    # Assert every protected-paths pin matches the blob at this ref (the always-on
    # check passes HEAD, so a protected file changed without repinning fails at PR
    # time instead of blocking every future sync on drift). Works with -SelfTestOnly.
    [string]$CheckPinsAgainst,
    # Anchor to use for the guard-3 ancestry check INSTEAD of the pinned ANCHOR_SRC.
    # Used ONLY by the always-on fixture, so it can build a self-contained candidate
    # from our own clean synthetic anchor (origin/nbase, c21ee8ac) without resolving
    # or fetching the Nagram source commit e09f49fa. The real sync never passes it —
    # sync-guard-check.yml asserts sync-upstream.yml does not — so a production run
    # always uses the pin. It changes which anchor ancestry is proved against, not
    # whether ancestry is proved; it is not a bypass.
    [string]$AnchorOverride,
    # Run only the land-check decision used by sync-land.yml before it fast-forwards
    # origin/nbase onto a reconciliation snapshot. Needs -OldNbase (the pinned OLD_NBASE,
    # so the snapshot's ancestry is proved against the pins and the check holds on a
    # re-run after the fast-forward already landed), -NewSnapshot (the snapshot commit),
    # and -NewDev (the current dev tip — the snapshot must be reachable from it, i.e. the
    # reconciliation is merged). Reads ANCHOR_SRC / OLD_NBASE / OLD_NBASE_TREE /
    # NAGRAM_BRANCH / SYNC_IDENTITY_* from the trusted pins, resolves the upstream commit
    # whose tree the snapshot copies live from the fetched `nagram` remote, and blocks
    # unless every land invariant holds. No ref is written in this mode. Exit: 0 = safe
    # to fast-forward, 1 = blocked, 2 = a resolution error (bad/missing ref, upstream not
    # fetched).
    [switch]$LandCheckOnly,
    # In -LandCheckOnly mode, also write the resolved evidence (the upstream commit,
    # its tree, the snapshot tree, the equality verdict, old/new nbase) as KEY=VALUE
    # lines to this file, so sync-land.yml can quote it verbatim in the pins PR body
    # instead of re-deriving it. Ignored in every other mode.
    [string]$EvidenceOut
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------------------
# Loaders (PRE-side data only)
# ---------------------------------------------------------------------------

function Read-Pins([string]$path) {
    $h = @{}
    foreach ($line in (Get-Content $path)) {
        $t = $line.Trim()
        if (-not $t -or $t.StartsWith('#')) { continue }
        $i = $t.IndexOf('=')
        if ($i -lt 1) { continue }
        $h[$t.Substring(0, $i).Trim()] = $t.Substring($i + 1).Trim()
    }
    return $h
}

function Read-Tsv([string]$path) {
    $rows = @()
    foreach ($line in (Get-Content $path | Select-Object -Skip 1)) {
        if (-not $line.Trim()) { continue }
        $p = $line -split "`t"
        $rows += [pscustomobject]@{ Path = $p[0]; Blob = $p[1] }
    }
    return $rows
}

# ---------------------------------------------------------------------------
# git helpers (only used on the real-candidate path)
# ---------------------------------------------------------------------------

function Get-BlobMap([string]$ref) {
    $h = @{}
    foreach ($line in (git ls-tree -r $ref)) {
        $tab = $line.IndexOf("`t")
        if ($tab -lt 0) { continue }
        $meta = ($line.Substring(0, $tab) -split '\s+')
        $h[$line.Substring($tab + 1)] = $meta[2]
    }
    return $h
}

function Get-PathEntry([string]$ref, [string]$path) {
    $line = git ls-tree $ref -- $path | Select-Object -First 1
    if (-not $line) { return $null }
    $tab = $line.IndexOf("`t")
    $meta = ($line.Substring(0, $tab) -split '\s+')
    return [pscustomobject]@{ Mode = $meta[0]; Type = $meta[1]; Object = $meta[2] }
}

function Get-WorkflowBlobs([string]$ref) {
    $h = @{}
    foreach ($line in (git ls-tree -r $ref -- '.github/workflows/')) {
        $tab = $line.IndexOf("`t")
        if ($tab -lt 0) { continue }
        $meta = ($line.Substring(0, $tab) -split '\s+')
        $h[$line.Substring($tab + 1)] = $meta[2]
    }
    return $h
}

function Show-Blob([string]$ref, [string]$path) {
    return (git show "${ref}:$path" 2>$null)
}

# ---------------------------------------------------------------------------
# Pure comparison validators. Each returns an array of failure strings (empty =
# pass). They take already-extracted values so the SAME code runs for the real
# candidate and for the self-test's synthetic inputs.
# ---------------------------------------------------------------------------

# Guard 8 + structural self-protect: a set of paths must equal a pinned blob in
# BOTH the PRE tree and the candidate tree.
function Test-Pinned([hashtable]$preBlobs, [hashtable]$candBlobs, $rows, [string]$label) {
    $f = @()
    foreach ($r in $rows) {
        if (-not $preBlobs.ContainsKey($r.Path)) { $f += "$label PRE missing: $($r.Path)"; continue }
        if ($preBlobs[$r.Path] -ne $r.Blob)      { $f += "$label PRE drift (pin stale vs dev): $($r.Path)"; continue }
        if (-not $candBlobs.ContainsKey($r.Path)) { $f += "$label candidate deleted: $($r.Path)"; continue }
        if ($candBlobs[$r.Path] -ne $r.Blob)      { $f += "$label candidate wrong content: $($r.Path) -> $($candBlobs[$r.Path])" }
    }
    return $f
}

# Structural self-protect using live PRE blobs (not a pinned list): the guard and
# its workflows must be byte-identical to PRE in the candidate.
function Test-SelfProtect([hashtable]$preBlobs, [hashtable]$candBlobs, [string[]]$paths) {
    $f = @()
    foreach ($p in $paths) {
        if (-not $preBlobs.ContainsKey($p)) { continue }   # not yet on PRE (e.g. bootstrap)
        if (-not $candBlobs.ContainsKey($p)) { $f += "self-protect deleted: $p"; continue }
        if ($candBlobs[$p] -ne $preBlobs[$p]) { $f += "self-protect altered by candidate: $p" }
    }
    return $f
}

# Guard 5: the snapshot's .github/workflows must exactly match the approved
# manifest — no added workflow, no changed blob. Validated against the SNAPSHOT
# tree, because nbase is what carries the executable workflows.
function Test-Workflows([hashtable]$snapWf, $manifestRows) {
    $f = @()
    $approved = @{}
    foreach ($r in $manifestRows) { $approved[$r.Path] = $r.Blob }
    foreach ($p in $snapWf.Keys) {
        if (-not $approved.ContainsKey($p)) { $f += "workflow ADDED in snapshot (manual audit required): $p"; continue }
        if ($snapWf[$p] -ne $approved[$p])  { $f += "workflow blob CHANGED in snapshot (manual audit required): $p" }
    }
    foreach ($p in $approved.Keys) {
        if (-not $snapWf.ContainsKey($p)) { $f += "approved workflow MISSING from snapshot: $p" }
    }
    return $f
}

# Guard 9 + 11 + "every tree delta must be partitioned; unclassified means BLOCK".
# Additions and deletions always block. For in-place modifications the only safe
# class is upstream-only (upstream changed the path, the fork did not): a clean
# 3-way merge of a path BOTH sides changed can silently revert fork behaviour
# (the PoC: an upstream edit reverted ChatMessageCell's timeString chain with no
# conflict). So the whole fork-delta ∩ parent-delta intersection routes to human
# review, and any modification upstream did not make is unclassified and blocks.
function Test-Partition([hashtable]$preBlobs, [hashtable]$candBlobs, [string[]]$forkDelta, [string[]]$parentDelta) {
    $f = @()
    $added   = @($candBlobs.Keys | Where-Object { -not $preBlobs.ContainsKey($_) })
    $removed = @($preBlobs.Keys  | Where-Object { -not $candBlobs.ContainsKey($_) })
    foreach ($p in $added)   { $f += "NEW path (unreviewed, blocks pending audit): $p" }
    foreach ($p in $removed) { $f += "REMOVED path (fork-overlay loss, unclassified): $p" }

    $fork   = @{}; foreach ($p in $forkDelta)   { $fork[$p]   = $true }
    $parent = @{}; foreach ($p in $parentDelta) { $parent[$p] = $true }

    # Block the entire double-modified intersection, whether or not the merge left a
    # visible candidate delta — an upstream change silently dropped is as dangerous
    # as a fork change silently reverted.
    foreach ($p in $forkDelta) {
        if ($parent.ContainsKey($p)) { $f += "DOUBLE-MODIFIED (fork and upstream both touch this — human review): $p" }
    }
    # Every remaining in-place candidate modification must be upstream-only.
    foreach ($p in $candBlobs.Keys) {
        if (-not $preBlobs.ContainsKey($p)) { continue }             # addition, already handled
        if ($preBlobs[$p] -eq $candBlobs[$p]) { continue }           # unchanged
        if ($fork.ContainsKey($p) -and $parent.ContainsKey($p)) { continue }  # double-modified, already reported
        if (-not $parent.ContainsKey($p)) { $f += "UNCLASSIFIED modification (upstream did not change this path): $p" }
    }
    return @($f | Select-Object -Unique)
}

# Guard 10: .gitmodules blob unchanged, and every vendored native keeps its pinned
# git object shape — a vendored tree stays a `040000 tree` and never silently
# becomes a `160000 commit` submodule (the 12.10.1 default merge did exactly that to
# libyuv and openh264), and an intentional gitlink keeps its pinned commit.
#
# Table-driven: the pinned table is read from VENDORED_NATIVES + <N>_PATH/_MODE/_TYPE
# (+ <N>_COMMIT for a gitlink). $observed maps each pinned path to the git tree entry
# actually present in the candidate (a {Mode;Type;Object} object, or $null if the
# path is gone). Adding a component is a pins.env edit, not a code edit here.
# Parse VENDORED_NATIVES once, here, so validation and consumption can never
# disagree about which names are in the table. Trim, drop empties. A raw list
# that is non-empty yet yields zero names (e.g. "," or " , ") comes back empty
# and is rejected up front in the pins-validation loop, before anything indexes
# the table.
function Get-VendoredNames([hashtable]$pins) {
    return @(($pins['VENDORED_NATIVES'] -split ',') | ForEach-Object { $_.Trim() } | Where-Object { $_ })
}

function Get-VendoredPins([hashtable]$pins) {
    $rows = @()
    foreach ($n in (Get-VendoredNames $pins)) {
        $rows += [pscustomobject]@{
            Name   = $n
            Path   = $pins["${n}_PATH"]
            Mode   = $pins["${n}_MODE"]
            Type   = $pins["${n}_TYPE"]
            Commit = $pins["${n}_COMMIT"]   # $null for a vendored tree
        }
    }
    return $rows
}

function Test-Gitmodules([string]$candGitmodulesBlob, [hashtable]$observed, [hashtable]$pins) {
    $f = @()
    if ($candGitmodulesBlob -ne $pins['GITMODULES_BLOB']) { $f += ".gitmodules blob changed: $candGitmodulesBlob" }
    foreach ($row in (Get-VendoredPins $pins)) {
        $obs = $observed[$row.Path]
        if (-not $obs) { $f += "$($row.Name) vendored entry MISSING from candidate: $($row.Path)"; continue }
        if ($obs.Mode -ne $row.Mode -or $obs.Type -ne $row.Type) {
            $f += "$($row.Name) entry is $($obs.Mode) $($obs.Type), expected $($row.Mode) $($row.Type) (submodule-ified?): $($row.Path)"
            continue
        }
        if ($row.Type -eq 'commit' -and $obs.Object -ne $row.Commit) {
            $f += "$($row.Name) gitlink commit $($obs.Object) != pinned $($row.Commit): $($row.Path)"
        }
    }
    return $f
}

# Guard 11: fork layers did not lose files/entries in the merge.
function Test-LayerFloors([int]$nekomimi, [int]$radolyn, [int]$strings, [int]$addConfig, [hashtable]$pins) {
    $f = @()
    if ($nekomimi  -lt [int]$pins['NEKOMIMI_MIN'])         { $f += "tw/nekomimi files $nekomimi < $($pins['NEKOMIMI_MIN'])" }
    if ($radolyn   -ne [int]$pins['RADOLYN_EXACT'])        { $f += "com/radolyn files $radolyn != $($pins['RADOLYN_EXACT'])" }
    if ($strings   -lt [int]$pins['STRINGS_NAX_MIN'])      { $f += "strings_nax entries $strings < $($pins['STRINGS_NAX_MIN'])" }
    if ($addConfig -lt [int]$pins['NACONFIG_ADDCONFIG_MIN']) { $f += "NaConfig addConfig $addConfig < $($pins['NACONFIG_ADDCONFIG_MIN'])" }
    return $f
}

# Guard 12: Ayu schema shape is intact and migrations reach the current version.
function Test-AyuSchema([int]$entities, [int]$version, [int]$minVersion, [bool]$migrationsWired, [hashtable]$pins) {
    $f = @()
    if ($entities   -ne [int]$pins['AYU_ENTITIES'])    { $f += "Ayu entities $entities != $($pins['AYU_ENTITIES'])" }
    if ($version    -ne [int]$pins['AYU_VERSION'])     { $f += "Ayu VERSION $version != $($pins['AYU_VERSION'])" }
    if ($minVersion -ne [int]$pins['AYU_MIN_VERSION']) { $f += "Ayu MIN_SUPPORTED_VERSION $minVersion != $($pins['AYU_MIN_VERSION'])" }
    if (-not $migrationsWired)                         { $f += "Ayu migrations not wired to VERSION $($pins['AYU_VERSION'])" }
    return $f
}

# Guard 13 (blob half): the keystore and the signing-config gradle blob are the
# pinned ones. The certificate-identity half runs on the runner (keytool), not here.
function Test-SignerBlobs([string]$keystoreBlob, [string]$gradleBlob, [hashtable]$pins) {
    $f = @()
    if ($keystoreBlob -ne $pins['KEYSTORE_BLOB'])       { $f += "release.keystore blob changed: $keystoreBlob" }
    if ($gradleBlob   -ne $pins['SIGNING_GRADLE_BLOB']) { $f += "signing-config build.gradle blob changed: $gradleBlob" }
    return $f
}

# Guard 3 + 4: ancestry of the synthetic snapshot.
function Test-Ancestry([bool]$srcDescendsAnchor, [string[]]$snapParents, [string]$oldNbase,
                       [string[]]$revListMinusOld, [string]$newSnapshot, [bool]$srcIsAncestorOfSnapshot) {
    $f = @()
    if (-not $srcDescendsAnchor) { $f += "guard3: source does not descend from recorded anchor" }
    if ($snapParents.Count -ne 1 -or $snapParents[0] -ne $oldNbase) {
        $f += "guard4: snapshot parents [$($snapParents -join ',')] != [single OLD_NBASE $oldNbase]"
    }
    if ($revListMinusOld.Count -ne 1 -or $revListMinusOld[0] -ne $newSnapshot) {
        $f += "guard4: rev-list NEW ^OLD returned [$($revListMinusOld -join ',')], expected only the snapshot"
    }
    if ($srcIsAncestorOfSnapshot) { $f += "guard4: source anchor IS an ancestor of the snapshot (history imported)" }
    return $f
}

# Snapshot faithfully copies the source tree, nothing more.
function Test-SnapshotTree([string]$snapTree, [string]$srcTree) {
    if ($snapTree -ne $srcTree) { return @("snapshot tree $snapTree != source tree $srcTree") }
    return @()
}

# No-op fast path decision. Run BEFORE any synthetic snapshot is built, on the
# resolved facts of a run, and returns one of three decisions:
#
#   proceed  - the source tree differs from origin/nbase's tree, so there may be a
#              real upstream delta. Nothing here changes the full path; the caller
#              builds the snapshot, merges, guards, signs and atomically pushes.
#   uptodate - the source tree equals origin/nbase's tree (nothing to import) AND
#              the pins are current AND dev still contains nbase. The caller creates
#              no commit and pushes no ref: the redundant snapshot is skipped.
#   blocked  - the source tree equals origin/nbase's tree, but a precondition is
#              stale (nbase pin, its tree pin, or the dev-contains-nbase topology).
#
# Why equal-tree-but-stale-pin BLOCKS rather than falling through to the full path:
# an equal tree with a stale pin is exactly the state that needs a human, and it is
# also self-perpetuating if handled silently. Falling through would build one more
# snapshot whose parent is the live nbase and whose tree already equals it — another
# redundant no-op commit, the very defect this fast path exists to stop — and, worse,
# an early "up to date" success there would mask the stale pin forever. So the fast
# path refuses: it fails loudly and tells the operator to repin. A tree with nothing
# to import is only auto-safe when the recorded facts still describe reality.
function Test-SyncFastPath([string]$srcTree, [string]$liveNbase, [string]$liveNbaseTree,
                           [string]$pinnedOldNbase, [string]$pinnedOldNbaseTree, [bool]$devContainsNbase) {
    if ($srcTree -ne $liveNbaseTree) {
        return [pscustomobject]@{ Decision = 'proceed'
            Reason = "source tree $srcTree != nbase tree $liveNbaseTree; a delta may exist, take the full path" }
    }
    # Trees equal: nothing to import. Equality alone is NOT enough to report success.
    $problems = @()
    if ($liveNbase -ne $pinnedOldNbase) {
        $problems += "origin/nbase $liveNbase != pinned OLD_NBASE $pinnedOldNbase"
    }
    if ($liveNbaseTree -ne $pinnedOldNbaseTree) {
        $problems += "origin/nbase tree $liveNbaseTree != pinned OLD_NBASE_TREE $pinnedOldNbaseTree"
    }
    if (-not $devContainsNbase) {
        $problems += "origin/dev does not contain origin/nbase $liveNbase as an ancestor"
    }
    if ($problems.Count) {
        return [pscustomobject]@{ Decision = 'blocked'; Reason = ($problems -join '; ') }
    }
    return [pscustomobject]@{ Decision = 'uptodate'
        Reason = "source tree $srcTree equals nbase tree, pins current, dev contains nbase — nothing to import" }
}

# Land-check (sync-land): the assertions that must hold before origin/nbase is
# fast-forwarded onto a reconciliation snapshot. This is the SAME family of proof
# as Test-Ancestry, but for a snapshot minted by hand on the PC rather than one the
# sync job just built, so it is checked from live facts, not from the guard's own
# in-run outputs. Every failure here is fail-closed: nbase does not move.
#
# Why the two obvious ancestry facts are not enough. "old nbase is an ancestor of
# the snapshot" and "the snapshot is reachable from dev" both stay true for a
# snapshot whose TREE was hand-edited during the reconciliation (conflicts resolved
# into the snapshot instead of into dev). Its tree would then match no real upstream
# commit, and every future 3-way merge would compute the wrong delta, silently,
# forever. So the load-bearing check is the tree one: the snapshot's tree must be
# byte-identical to an actual upstream commit ($srcTree, resolved live from the
# upstream repo), and that upstream commit must descend from the recorded anchor.
#
#   $snapParents          parents of the snapshot (must be exactly [pinned OLD_NBASE])
#   $expectedOldNbase     the pinned OLD_NBASE — the one parent the snapshot may have.
#                         Keyed to the pin, not to live origin/nbase, so the proof holds
#                         identically on an idempotent re-run after nbase already moved.
#   $revMinusOld          rev-list SNAP ^expectedOldNbase (must be exactly [SNAP])
#   $newSnapshot          the snapshot commit
#   $snapTree             SNAP^{tree}
#   $srcTree              tree of the upstream commit the snapshot claims to copy
#   $srcDescendsAnchor    that upstream commit descends from the pinned ANCHOR_SRC
#   $expectedOldNbaseTree pinned OLD_NBASE^{tree}
#   $pinnedAnchorTree     tree of the currently-pinned ANCHOR_SRC (live-resolved)
#   $pinnedOldNbase       pins.env OLD_NBASE
#   $pinnedOldNbaseTree   pins.env OLD_NBASE_TREE
#   $snapDescendsDev      the snapshot is an ancestor of origin/dev — proof the
#                         reconciliation was merged before nbase advances onto it
function Test-LandCheck([string[]]$snapParents, [string]$expectedOldNbase,
                        [string[]]$revMinusOld, [string]$newSnapshot,
                        [string]$snapTree, [string]$srcTree, [bool]$srcDescendsAnchor,
                        [string]$expectedOldNbaseTree, [string]$pinnedAnchorTree,
                        [string]$pinnedOldNbase, [string]$pinnedOldNbaseTree,
                        [bool]$snapDescendsDev) {
    $f = @()
    # Single parent, and it is the pinned old nbase — the snapshot chains onto exactly
    # the ref we are moving from, so the fast-forward is append-only. This check is keyed
    # to the pinned OLD_NBASE, never to wherever origin/nbase points right now, so it
    # proves the snapshot's intrinsic validity identically on a first run and on a re-run
    # after the fast-forward has already landed (where live origin/nbase already equals
    # the snapshot). Whether the live ref is where we expect is the fast-forward step's
    # job, checked immediately before the push, not here.
    if ($snapParents.Count -ne 1 -or $snapParents[0] -ne $expectedOldNbase) {
        $f += "land: snapshot parents [$($snapParents -join ',')] != [single expected old nbase $expectedOldNbase]"
    }
    # Nothing but the snapshot itself is new relative to the pinned old nbase.
    if ($revMinusOld.Count -ne 1 -or $revMinusOld[0] -ne $newSnapshot) {
        $f += "land: rev-list SNAP ^$expectedOldNbase returned [$($revMinusOld -join ',')], expected only the snapshot"
    }
    # The reconciliation must already be merged into dev before nbase advances onto its
    # snapshot. Without this, pressing the button before the reconciliation PR lands
    # fast-forwards nbase over a reconciliation that never merged; every later sync then
    # treats those upstream changes as already applied and drops them silently and
    # permanently. Keyed to the pinned old nbase above, this also rejects a re-run fired
    # with a different snapshot SHA, because such a snapshot is not reachable from dev.
    if (-not $snapDescendsDev) {
        $f += "land: snapshot $newSnapshot is not an ancestor of origin/dev — the reconciliation has not been merged into dev; refusing to advance nbase over an unmerged reconciliation"
    }
    # The one that a hand-edited reconciliation trips: the snapshot's tree must be
    # byte-identical to a real upstream commit, or nbase would carry a tree that
    # exists nowhere upstream and every future merge base would be wrong.
    if ($snapTree -ne $srcTree) {
        $f += "land: snapshot tree $snapTree != upstream source tree $srcTree — the snapshot tree matches no upstream commit (hand-edited during reconciliation?)"
    }
    # The upstream commit the snapshot copies moves the anchor forward, never sideways
    # or backward.
    if (-not $srcDescendsAnchor) {
        $f += "land: the upstream commit the snapshot copies does not descend from the currently-pinned ANCHOR_SRC"
    }
    # The recorded anchor is genuinely the commit whose tree the pinned old nbase carries.
    # Nothing else machine-checks this, so a reviewer reading the auto pins PR has no
    # way to confirm it by hand — assert it here and print it as evidence.
    if ($pinnedAnchorTree -ne $expectedOldNbaseTree) {
        $f += "land: pinned ANCHOR_SRC tree $pinnedAnchorTree != expected old nbase tree $expectedOldNbaseTree — the recorded anchor is not the commit nbase carries"
    }
    # The base the snapshot's ancestry is proved against must be the pinned OLD_NBASE, so
    # a caller that handed this mode the wrong base (e.g. the live ref on a re-run instead
    # of the pin) fails closed rather than proving ancestry against the wrong commit.
    if ($expectedOldNbase -ne $pinnedOldNbase) {
        $f += "land: expected old nbase $expectedOldNbase != pinned OLD_NBASE $pinnedOldNbase"
    }
    if ($expectedOldNbaseTree -ne $pinnedOldNbaseTree) {
        $f += "land: expected old nbase tree $expectedOldNbaseTree != pinned OLD_NBASE_TREE $pinnedOldNbaseTree"
    }
    return $f
}

# Land-check identity half: the snapshot must be locally authored — its author AND
# committer are the sync identity, so a reconciliation snapshot that smuggled in an
# upstream author is rejected before nbase moves. The prohibited-attribution scan is
# done separately by Test-Attribution (reused, not reimplemented); this is the
# positive identity assertion the token scan cannot make.
function Test-SnapshotIdentity([string]$authorName, [string]$authorEmail,
                               [string]$committerName, [string]$committerEmail,
                               [string]$idName, [string]$idEmail) {
    $f = @()
    if ($authorName -ne $idName -or $authorEmail -ne $idEmail) {
        $f += "land: snapshot author '$authorName <$authorEmail>' is not the sync identity '$idName <$idEmail>'"
    }
    if ($committerName -ne $idName -or $committerEmail -ne $idEmail) {
        $f += "land: snapshot committer '$committerName <$committerEmail>' is not the sync identity '$idName <$idEmail>'"
    }
    return $f
}

# Guard 3/14: no upstream commits imported, and no prohibited attribution in the
# full metadata of the commits this sync introduces (subject+body, author AND
# committer name AND email) or in the source lines the sync adds.
#
# Tokens are REGEX, matched case-insensitively against a lowercased haystack. The
# `generated with` form is deliberately narrowed to an attribution neighbour (a
# markdown link or a known assistant name): a plain substring matched the vendored
# third-party phrases `generated without` and `regenerated with` (hundreds of hits
# across boringssl/sqlite/webrtc), which would block a sync on a false positive.
# Narrower, not weaker — it still fails closed on every real assistant footer.
$script:AttributionTokens = @(
    'co-authored-by'
    'generated by copilot'
    'signed-off-by:\s*copilot'
    'authored-by:\s*copilot'
    'generated with\s+(\[|claude|copilot|chatgpt|chat gpt|gpt[- ]|openai|anthropic|gemini|codex|cursor|an ai\b|ai assistant)'
)
function Test-Attribution($newCommits, [string[]]$expectedShas, [string]$addedLines) {
    $f = @()
    $seen = @($newCommits | ForEach-Object { $_.Sha })
    foreach ($s in $seen) {
        if ($s -notin $expectedShas) { $f += "guard14: unexpected commit imported into dev: $s" }
    }
    foreach ($c in $newCommits) {
        $hay = "$($c.AuthorName) $($c.AuthorEmail) $($c.CommitterName) $($c.CommitterEmail) $($c.Message)".ToLowerInvariant()
        foreach ($tok in $script:AttributionTokens) {
            if ($hay -match $tok) { $f += "guard14: prohibited attribution token /$tok/ in $($c.Sha) metadata" }
        }
    }
    if ($addedLines) {
        $hay = $addedLines.ToLowerInvariant()
        foreach ($tok in $script:AttributionTokens) {
            if ($hay -match $tok) { $f += "guard14: prohibited attribution token /$tok/ in added source lines" }
        }
    }
    return $f
}

# Guard 11 extension: the executable Gradle build surface must stay fork-owned.
# buildSrc runs at configuration time and distributionUrl picks the Gradle binary
# the runner downloads, all on a runner holding LOCAL_PROPERTIES and the bot token.
# Only TMessagesProj/build.gradle is blob-pinned; the rest are protected today only
# because they sit in the fork delta, so an upstream-only change lands in the
# double-modified intersection and blocks. That protection is emergent, not
# asserted — if a PC reconciliation ever made one byte-identical to Nagram's it
# would leave the fork delta and become auto-pushable silently. Assert membership:
# every surface path (and every file under a surface prefix ending '/') must be
# present in dev AND in the fork delta. This does not blob-pin, so a fork PR that
# legitimately edits build.gradle is unaffected; it fires exactly when the
# emergent protection evaporates.
function Test-GradleSurface([string[]]$forkDelta, [hashtable]$preBlobs, [string[]]$surface) {
    $f = @()
    $fork = @{}; foreach ($p in $forkDelta) { $fork[$p] = $true }
    foreach ($entry in $surface) {
        if ($entry.EndsWith('/')) {
            $under = @($preBlobs.Keys | Where-Object { $_.StartsWith($entry) })
            if ($under.Count -lt 1) { $f += "gradle surface prefix has no files in dev: $entry"; continue }
            foreach ($p in $under) {
                if (-not $fork.ContainsKey($p)) { $f += "gradle surface path left the fork delta (now auto-pushable): $p" }
            }
        } else {
            if (-not $preBlobs.ContainsKey($entry)) { $f += "gradle surface path missing from dev: $entry"; continue }
            if (-not $fork.ContainsKey($entry)) { $f += "gradle surface path left the fork delta (now auto-pushable): $entry" }
        }
    }
    return $f
}

# Empty results collapse to $null across PowerShell call boundaries, so filter to
# truthy failure strings before counting rather than trusting .Count on a raw @().
function Assert-Fails([string]$name, $result, [ref]$log) {
    $items = @($result | Where-Object { $_ })
    if ($items.Count -ge 1) { $log.Value += "  ok  $name rejects bad input`n"; return $true }
    $log.Value += "  XX  $name FAILED TO REJECT a bad input`n"; return $false
}
function Assert-Passes([string]$name, $result, [ref]$log) {
    $items = @($result | Where-Object { $_ })
    if ($items.Count -eq 0) { $log.Value += "  ok  $name accepts good input`n"; return $true }
    $log.Value += "  XX  $name REJECTED a good input: $($items -join '; ')`n"; return $false
}
function Assert-Decision([string]$name, $result, [string]$expected, [ref]$log) {
    if ($result.Decision -eq $expected) { $log.Value += "  ok  $name -> $expected`n"; return $true }
    $log.Value += "  XX  $name expected $expected but got '$($result.Decision)' ($($result.Reason))`n"; return $false
}

function Invoke-SelfTest([hashtable]$pins) {
    $log = ''
    $ok = $true

    # Guard 8 / pinned
    $rows = @([pscustomobject]@{ Path = 'TMessagesProj/release.keystore'; Blob = $pins['KEYSTORE_BLOB'] })
    $preGood  = @{ 'TMessagesProj/release.keystore' = $pins['KEYSTORE_BLOB'] }
    $candBad  = @{ 'TMessagesProj/release.keystore' = '795a67fe05c4dc68321386011cb992a92f96c4f7' } # Nagram's keystore
    $ok = (Assert-Fails  'Test-Pinned'  (Test-Pinned $preGood $candBad  $rows 'protected') ([ref]$log)) -and $ok
    $ok = (Assert-Passes 'Test-Pinned'  (Test-Pinned $preGood $preGood  $rows 'protected') ([ref]$log)) -and $ok

    # Structural self-protect
    $pg = @{ '.github/sync/sync-guard.ps1' = 'aaa' }
    $cb = @{ '.github/sync/sync-guard.ps1' = 'bbb' }
    $ok = (Assert-Fails  'Test-SelfProtect' (Test-SelfProtect $pg $cb @('.github/sync/sync-guard.ps1')) ([ref]$log)) -and $ok
    $ok = (Assert-Passes 'Test-SelfProtect' (Test-SelfProtect $pg $pg @('.github/sync/sync-guard.ps1')) ([ref]$log)) -and $ok

    # Guard 5 workflows
    $man = @([pscustomobject]@{ Path = '.github/workflows/debug.yml'; Blob = 'fa4b0e5d17bf39a5e054658885c33046ac671b78' })
    $wfBad  = @{ '.github/workflows/debug.yml' = 'fa4b0e5d17bf39a5e054658885c33046ac671b78'; '.github/workflows/evil.yml' = 'deadbeef' }
    $wfGood = @{ '.github/workflows/debug.yml' = 'fa4b0e5d17bf39a5e054658885c33046ac671b78' }
    $ok = (Assert-Fails  'Test-Workflows' (Test-Workflows $wfBad  $man) ([ref]$log)) -and $ok
    $ok = (Assert-Passes 'Test-Workflows' (Test-Workflows $wfGood $man) ([ref]$log)) -and $ok

    # Guard 9 / 11 partition (fork ∩ upstream)
    $pre = @{ 'a' = '1'; 'b' = '2' }
    $addC = @{ 'a' = '1'; 'b' = '2'; 'BRANDING.md' = '9' }
    $delC = @{ 'a' = '1' }
    $modC = @{ 'a' = '1'; 'b' = '9' }
    # upstream-only modification of b is safe; double-modified b blocks; a
    # modification upstream did not make is unclassified and blocks.
    $ok = (Assert-Fails  'Test-Partition(add)'      (Test-Partition $pre $addC @() @('b')) ([ref]$log)) -and $ok
    $ok = (Assert-Fails  'Test-Partition(del)'      (Test-Partition $pre $delC @() @('b')) ([ref]$log)) -and $ok
    $ok = (Assert-Fails  'Test-Partition(double)'   (Test-Partition $pre $modC @('b') @('b')) ([ref]$log)) -and $ok
    $ok = (Assert-Fails  'Test-Partition(unclass)'  (Test-Partition $pre $modC @() @()) ([ref]$log)) -and $ok
    $ok = (Assert-Passes 'Test-Partition(upstream)' (Test-Partition $pre $modC @() @('b')) ([ref]$log)) -and $ok

    # Guard 10 gitmodules / vendored natives. Build the good observed state straight
    # from the pinned table (tree object shas are arbitrary; a gitlink's object is
    # its pinned commit), then mutate one entry per negative case.
    $vend = Get-VendoredPins $pins
    $obsGood = @{}
    foreach ($row in $vend) {
        $obj = if ($row.Type -eq 'commit') { $row.Commit } else { '0' * 40 }
        $obsGood[$row.Path] = [pscustomobject]@{ Mode = $row.Mode; Type = $row.Type; Object = $obj }
    }
    $ok = (Assert-Fails  'Test-Gitmodules(blob)'   (Test-Gitmodules 'wrongblob' $obsGood $pins) ([ref]$log)) -and $ok
    # A 160000 commit presented where a 040000 tree is pinned must be rejected — one
    # negative case per vendored TREE entry (the exact 12.10.1 silent submodule-ify).
    foreach ($row in $vend | Where-Object { $_.Type -eq 'tree' }) {
        $obsBad = @{}; foreach ($k in $obsGood.Keys) { $obsBad[$k] = $obsGood[$k] }
        $obsBad[$row.Path] = [pscustomobject]@{ Mode = '160000'; Type = 'commit'; Object = 'f' * 40 }
        $ok = (Assert-Fails "Test-Gitmodules(submodule:$($row.Name))" (Test-Gitmodules $pins['GITMODULES_BLOB'] $obsBad $pins) ([ref]$log)) -and $ok
    }
    # A wrong gitlink commit must be rejected — one negative case per COMMIT entry.
    foreach ($row in $vend | Where-Object { $_.Type -eq 'commit' }) {
        $obsBad = @{}; foreach ($k in $obsGood.Keys) { $obsBad[$k] = $obsGood[$k] }
        $obsBad[$row.Path] = [pscustomobject]@{ Mode = $row.Mode; Type = $row.Type; Object = 'd' * 40 }
        $ok = (Assert-Fails "Test-Gitmodules(commit:$($row.Name))" (Test-Gitmodules $pins['GITMODULES_BLOB'] $obsBad $pins) ([ref]$log)) -and $ok
    }
    # A pinned entry missing from the candidate must be rejected.
    $obsMissing = @{}; foreach ($k in $obsGood.Keys) { $obsMissing[$k] = $obsGood[$k] }
    $obsMissing.Remove($vend[0].Path)
    $ok = (Assert-Fails  'Test-Gitmodules(missing)' (Test-Gitmodules $pins['GITMODULES_BLOB'] $obsMissing $pins) ([ref]$log)) -and $ok
    $ok = (Assert-Passes 'Test-Gitmodules'         (Test-Gitmodules $pins['GITMODULES_BLOB'] $obsGood $pins) ([ref]$log)) -and $ok

    # Guard 11 layer floors
    $ok = (Assert-Fails  'Test-LayerFloors(low)'  (Test-LayerFloors 171 60 599 262 $pins) ([ref]$log)) -and $ok
    $ok = (Assert-Fails  'Test-LayerFloors(rad)'  (Test-LayerFloors 172 59 599 262 $pins) ([ref]$log)) -and $ok
    $ok = (Assert-Passes 'Test-LayerFloors'       (Test-LayerFloors 172 60 599 262 $pins) ([ref]$log)) -and $ok

    # Guard 12 Ayu schema
    $ok = (Assert-Fails  'Test-AyuSchema(ver)'  (Test-AyuSchema 4 26 21 $true  $pins) ([ref]$log)) -and $ok
    $ok = (Assert-Fails  'Test-AyuSchema(mig)'  (Test-AyuSchema 4 27 21 $false $pins) ([ref]$log)) -and $ok
    $ok = (Assert-Passes 'Test-AyuSchema'       (Test-AyuSchema 4 27 21 $true  $pins) ([ref]$log)) -and $ok

    # Guard 13 signer blobs
    $ok = (Assert-Fails  'Test-SignerBlobs' (Test-SignerBlobs '795a67fe05c4dc68321386011cb992a92f96c4f7' $pins['SIGNING_GRADLE_BLOB'] $pins) ([ref]$log)) -and $ok
    $ok = (Assert-Passes 'Test-SignerBlobs' (Test-SignerBlobs $pins['KEYSTORE_BLOB'] $pins['SIGNING_GRADLE_BLOB'] $pins) ([ref]$log)) -and $ok

    # Guard 3/4 ancestry
    $ok = (Assert-Fails  'Test-Ancestry(anchor)'   (Test-Ancestry $false @('OLD') 'OLD' @('SNAP') 'SNAP' $false) ([ref]$log)) -and $ok
    $ok = (Assert-Fails  'Test-Ancestry(2parents)' (Test-Ancestry $true @('x','y') 'OLD' @('SNAP') 'SNAP' $false) ([ref]$log)) -and $ok
    $ok = (Assert-Fails  'Test-Ancestry(imported)' (Test-Ancestry $true @('OLD') 'OLD' @('SNAP','OTHER') 'SNAP' $false) ([ref]$log)) -and $ok
    $ok = (Assert-Fails  'Test-Ancestry(srcanc)'   (Test-Ancestry $true @('OLD') 'OLD' @('SNAP') 'SNAP' $true) ([ref]$log)) -and $ok
    $ok = (Assert-Passes 'Test-Ancestry'           (Test-Ancestry $true @('OLD') 'OLD' @('SNAP') 'SNAP' $false) ([ref]$log)) -and $ok

    # Snapshot tree
    $ok = (Assert-Fails  'Test-SnapshotTree' (Test-SnapshotTree 'aaa' 'bbb') ([ref]$log)) -and $ok
    $ok = (Assert-Passes 'Test-SnapshotTree' (Test-SnapshotTree 'aaa' 'aaa') ([ref]$log)) -and $ok

    # No-op fast path. Both directions, plus the stale-pin regression that today's
    # redundant-snapshot bug would have skipped straight past. Pure string compares,
    # so synthetic tokens stand in for the real trees/commits.
    #   equal tree + current pins + dev-has-nbase -> uptodate (no commit, no push)
    $ok = (Assert-Decision 'Test-SyncFastPath(uptodate)'    (Test-SyncFastPath 'TREE' 'NB' 'TREE' 'NB' 'TREE' $true)      'uptodate' ([ref]$log)) -and $ok
    #   equal tree + STALE nbase pin -> blocked (the exact regression for this fix)
    $ok = (Assert-Decision 'Test-SyncFastPath(stale-nbase)' (Test-SyncFastPath 'TREE' 'NB' 'TREE' 'NB_OLD' 'TREE' $true)  'blocked'  ([ref]$log)) -and $ok
    #   equal tree + STALE tree pin -> blocked
    $ok = (Assert-Decision 'Test-SyncFastPath(stale-tree)'  (Test-SyncFastPath 'TREE' 'NB' 'TREE' 'NB' 'TREE_OLD' $true)  'blocked'  ([ref]$log)) -and $ok
    #   equal tree + dev no longer contains nbase -> blocked
    $ok = (Assert-Decision 'Test-SyncFastPath(no-ancestor)' (Test-SyncFastPath 'TREE' 'NB' 'TREE' 'NB' 'TREE' $false)     'blocked'  ([ref]$log)) -and $ok
    #   differing tree -> proceed down the full guard/signer/atomic path unchanged
    $ok = (Assert-Decision 'Test-SyncFastPath(proceed)'     (Test-SyncFastPath 'TREE_NEW' 'NB' 'TREE' 'NB' 'TREE' $true)  'proceed'  ([ref]$log)) -and $ok

    # Land-check (sync-land). Pure string compares, so synthetic tokens stand in for
    # the real trees/commits. The GOOD row: single parent = the pinned old nbase, only
    # the snapshot is new, snapshot tree == upstream source tree, source descends the
    # anchor, the snapshot is merged into dev, and -OldNbase equals the pins. Each BAD
    # row violates exactly one invariant so a regression that stops discriminating it
    # turns this red.
    #                                    parents  expOld rev-list snap tree src  desc  oldTree anchTree pinNb  pinNbTree dev
    $ok = (Assert-Passes 'Test-LandCheck'            (Test-LandCheck @('OLD') 'OLD' @('SNAP') 'SNAP' 'T' 'T' $true  'NBT' 'NBT' 'OLD' 'NBT' $true) ([ref]$log)) -and $ok
    # Re-run after the fast-forward already landed: the land check is keyed to the pinned
    # old nbase, not the live ref, so it is handed the exact same facts as a first run
    # and must still pass. This is the newly-reachable path the idempotency fix buys.
    $ok = (Assert-Passes 'Test-LandCheck(rerun)'     (Test-LandCheck @('OLD') 'OLD' @('SNAP') 'SNAP' 'T' 'T' $true  'NBT' 'NBT' 'OLD' 'NBT' $true) ([ref]$log)) -and $ok
    $ok = (Assert-Fails  'Test-LandCheck(2parents)'  (Test-LandCheck @('OLD','X') 'OLD' @('SNAP') 'SNAP' 'T' 'T' $true 'NBT' 'NBT' 'OLD' 'NBT' $true) ([ref]$log)) -and $ok
    $ok = (Assert-Fails  'Test-LandCheck(parent)'    (Test-LandCheck @('Y') 'OLD' @('SNAP') 'SNAP' 'T' 'T' $true 'NBT' 'NBT' 'OLD' 'NBT' $true) ([ref]$log)) -and $ok
    $ok = (Assert-Fails  'Test-LandCheck(revlist)'   (Test-LandCheck @('OLD') 'OLD' @('SNAP','X') 'SNAP' 'T' 'T' $true 'NBT' 'NBT' 'OLD' 'NBT' $true) ([ref]$log)) -and $ok
    # The hand-edited-tree case: snapshot tree matches no upstream commit.
    $ok = (Assert-Fails  'Test-LandCheck(treeedit)'  (Test-LandCheck @('OLD') 'OLD' @('SNAP') 'SNAP' 'T' 'T2' $true 'NBT' 'NBT' 'OLD' 'NBT' $true) ([ref]$log)) -and $ok
    $ok = (Assert-Fails  'Test-LandCheck(nodesc)'    (Test-LandCheck @('OLD') 'OLD' @('SNAP') 'SNAP' 'T' 'T' $false 'NBT' 'NBT' 'OLD' 'NBT' $true) ([ref]$log)) -and $ok
    $ok = (Assert-Fails  'Test-LandCheck(anchtree)'  (Test-LandCheck @('OLD') 'OLD' @('SNAP') 'SNAP' 'T' 'T' $true 'NBT' 'OTHER' 'OLD' 'NBT' $true) ([ref]$log)) -and $ok
    $ok = (Assert-Fails  'Test-LandCheck(pinnb)'     (Test-LandCheck @('OLD') 'OLD' @('SNAP') 'SNAP' 'T' 'T' $true 'NBT' 'NBT' 'OLD2' 'NBT' $true) ([ref]$log)) -and $ok
    $ok = (Assert-Fails  'Test-LandCheck(pinnbtree)' (Test-LandCheck @('OLD') 'OLD' @('SNAP') 'SNAP' 'T' 'T' $true 'NBT' 'NBT' 'OLD' 'NBT2' $true) ([ref]$log)) -and $ok
    # Snapshot not merged into dev: pressing the button before the reconciliation PR
    # landed. Must fail closed, or nbase advances over a reconciliation that never merged.
    $ok = (Assert-Fails  'Test-LandCheck(nodev)'     (Test-LandCheck @('OLD') 'OLD' @('SNAP') 'SNAP' 'T' 'T' $true 'NBT' 'NBT' 'OLD' 'NBT' $false) ([ref]$log)) -and $ok

    # Land-check identity half: both author and committer must be the sync identity.
    $ok = (Assert-Passes 'Test-SnapshotIdentity'          (Test-SnapshotIdentity 'sync' 's@x' 'sync' 's@x' 'sync' 's@x') ([ref]$log)) -and $ok
    $ok = (Assert-Fails  'Test-SnapshotIdentity(author)'  (Test-SnapshotIdentity 'up'   's@x' 'sync' 's@x' 'sync' 's@x') ([ref]$log)) -and $ok
    $ok = (Assert-Fails  'Test-SnapshotIdentity(commit)'  (Test-SnapshotIdentity 'sync' 's@x' 'up'   'u@x' 'sync' 's@x') ([ref]$log)) -and $ok

    # Guard 3/14 attribution — names, emails, message, and added lines
    $good = @([pscustomobject]@{ Sha='SNAP'; AuthorName='bot'; AuthorEmail='bot@x'; CommitterName='bot'; CommitterEmail='bot@x'; Message='snapshot #infra' })
    $badTok = @([pscustomobject]@{ Sha='SNAP'; AuthorName='bot'; AuthorEmail='bot@x'; CommitterName='bot'; CommitterEmail='bot@x'; Message='snapshot`nCo-authored-by: X' })
    $badName = @([pscustomobject]@{ Sha='SNAP'; AuthorName='Generated by Copilot'; AuthorEmail='bot@x'; CommitterName='bot'; CommitterEmail='bot@x'; Message='snapshot #infra' })
    $badImp = @(
        [pscustomobject]@{ Sha='SNAP';  AuthorName='bot'; AuthorEmail='bot@x'; CommitterName='bot'; CommitterEmail='bot@x'; Message='snapshot #infra' },
        [pscustomobject]@{ Sha='UPSTR'; AuthorName='u';   AuthorEmail='u@x';   CommitterName='u';   CommitterEmail='u@x';   Message='upstream feature' })
    $ok = (Assert-Fails  'Test-Attribution(token)'    (Test-Attribution $badTok  @('SNAP') '') ([ref]$log)) -and $ok
    $ok = (Assert-Fails  'Test-Attribution(name)'     (Test-Attribution $badName @('SNAP') '') ([ref]$log)) -and $ok
    $ok = (Assert-Fails  'Test-Attribution(imported)' (Test-Attribution $badImp  @('SNAP') '') ([ref]$log)) -and $ok
    $ok = (Assert-Fails  'Test-Attribution(added)'    (Test-Attribution $good     @('SNAP') "+ // Co-authored-by: X") ([ref]$log)) -and $ok
    # Real assistant footer must still be caught by the narrowed `generated with`.
    $ok = (Assert-Fails  'Test-Attribution(genwith)'  (Test-Attribution $good     @('SNAP') "+ 🤖 Generated with [Claude Code](https://x)") ([ref]$log)) -and $ok
    # ...but the narrowed token must NOT fire on the vendored third-party phrases
    # that a plain substring matched. If a future widening reintroduces that, this
    # accept-good case goes red and catches it.
    $ok = (Assert-Passes 'Test-Attribution'           (Test-Attribution $good     @('SNAP') "+ generated without warranty`n+ regenerated with autoconf`n+ generated with protoc") ([ref]$log)) -and $ok

    # Guard 11 extension: Gradle build surface stays fork-owned (in the fork delta).
    $surface = @('build.gradle', 'buildSrc/')
    $preG = @{ 'build.gradle' = '1'; 'buildSrc/Config.kt' = '2'; 'other' = '3' }
    $forkAll   = @('build.gradle', 'buildSrc/Config.kt')          # both surface paths diverge
    $forkLeft  = @('build.gradle')                                # buildSrc/Config.kt left the delta
    $ok = (Assert-Fails  'Test-GradleSurface(left)'    (Test-GradleSurface $forkLeft $preG $surface) ([ref]$log)) -and $ok
    $ok = (Assert-Fails  'Test-GradleSurface(missing)' (Test-GradleSurface $forkAll @{ 'buildSrc/Config.kt' = '2' } $surface) ([ref]$log)) -and $ok
    $ok = (Assert-Passes 'Test-GradleSurface'          (Test-GradleSurface $forkAll $preG $surface) ([ref]$log)) -and $ok

    return [pscustomobject]@{ Ok = $ok; Log = $log }
}

# ---------------------------------------------------------------------------
# Real-candidate gathering + run
# ---------------------------------------------------------------------------

function Get-AyuFacts([string]$ref, [hashtable]$pins) {
    $db = Show-Blob $ref $pins['AYU_DB_PATH']
    $entities = 0; $version = -1; $minVersion = -1
    if ($db) {
        $txt = ($db -join "`n")
        $m = [regex]::Match($txt, 'entities\s*=\s*\{(.*?)\}', 'Singleline')
        if ($m.Success) { $entities = ([regex]::Matches($m.Groups[1].Value, '\.class')).Count }
        $mv = [regex]::Match($txt, '\bVERSION\s*=\s*(\d+)');            if ($mv.Success) { $version = [int]$mv.Groups[1].Value }
        $mm = [regex]::Match($txt, 'MIN_SUPPORTED_VERSION\s*=\s*(\d+)'); if ($mm.Success) { $minVersion = [int]$mm.Groups[1].Value }
    }
    $data = Show-Blob $ref $pins['AYU_DATA_PATH']
    $wired = $false
    if ($data -and $version -ge $minVersion) {
        $dtxt = ($data -join "`n")
        $wired = $true
        for ($v = $minVersion; $v -lt $version; $v++) {
            $name = "MIGRATION_${v}_$($v + 1)"
            if (($dtxt -notmatch [regex]::Escape($name)) -or ($dtxt -notmatch "addMigrations\([^)]*$([regex]::Escape($name))")) { $wired = $false; break }
        }
    }
    return [pscustomobject]@{ Entities = $entities; Version = $version; MinVersion = $minVersion; Wired = $wired }
}

function Invoke-RealGuard([hashtable]$pins, $protectedRows, $manifestRows) {
    $failures = @()

    $preBlobs  = Get-BlobMap $PreRef
    $candBlobs = Get-BlobMap $NewDev

    # Guard 8 protected + structural self-protect
    $failures += Test-Pinned $preBlobs $candBlobs $protectedRows 'protected'
    $selfPaths = @($pins['SELF_PROTECT'] -split ',')
    $failures += Test-SelfProtect $preBlobs $candBlobs $selfPaths

    # Guard 5 workflows (against the SNAPSHOT tree)
    $failures += Test-Workflows (Get-WorkflowBlobs $NewSnapshot) $manifestRows

    # Guard 9 + 11 partition (fork ∩ upstream). base of the sync merge is OLD_NBASE.
    $forkDelta   = @(git diff --name-only $OldNbase $PreRef)
    $parentDelta = @(git diff --name-only $OldNbase $NewSnapshot)
    $failures += Test-Partition $preBlobs $candBlobs $forkDelta $parentDelta

    # Guard 11 extension: executable Gradle build surface stays in the fork delta.
    $failures += Test-GradleSurface $forkDelta $preBlobs @($pins['GRADLE_SURFACE'] -split ',')

    # Guard 10 gitmodules / vendored natives: observe each pinned path's git entry.
    $observed = @{}
    foreach ($row in (Get-VendoredPins $pins)) { $observed[$row.Path] = Get-PathEntry $NewDev $row.Path }
    $gm = if ($candBlobs.ContainsKey('.gitmodules')) { $candBlobs['.gitmodules'] } else { 'MISSING' }
    $failures += Test-Gitmodules $gm $observed $pins

    # Guard 11 layer floors
    $nek = @(git ls-tree -r --name-only $NewDev -- $pins['NEKOMIMI_PATH']).Count
    $rad = @(git ls-tree -r --name-only $NewDev -- $pins['RADOLYN_PATH']).Count
    $str = ([regex]::Matches((Show-Blob $NewDev $pins['STRINGS_NAX_PATH'] | Out-String), '<string')).Count
    $adc = ([regex]::Matches((Show-Blob $NewDev $pins['NACONFIG_PATH'] | Out-String), 'addConfig\(')).Count
    $failures += Test-LayerFloors $nek $rad $str $adc $pins

    # Guard 12 Ayu schema
    $ayu = Get-AyuFacts $NewDev $pins
    $failures += Test-AyuSchema $ayu.Entities $ayu.Version $ayu.MinVersion $ayu.Wired $pins

    # Guard 13 signer blobs (cert identity is a separate runner step)
    $ks = if ($candBlobs.ContainsKey($pins['KEYSTORE_PATH'])) { $candBlobs[$pins['KEYSTORE_PATH']] } else { 'MISSING' }
    $bg = if ($candBlobs.ContainsKey($pins['SIGNING_GRADLE_PATH'])) { $candBlobs[$pins['SIGNING_GRADLE_PATH']] } else { 'MISSING' }
    $failures += Test-SignerBlobs $ks $bg $pins

    # Guard 3/4 ancestry. The anchor is the pinned ANCHOR_SRC in production; the
    # always-on fixture passes -AnchorOverride so it never resolves the Nagram
    # commit (see the param note).
    $anchor = if ($AnchorOverride) { $AnchorOverride } else { $pins['ANCHOR_SRC'] }
    git merge-base --is-ancestor $anchor $NewSrc; $srcDescends = ($LASTEXITCODE -eq 0)
    $snapParents = @((git rev-list --parents -n 1 $NewSnapshot) -split '\s+' | Select-Object -Skip 1)
    $revMinusOld = @(git rev-list $NewSnapshot "^$OldNbase")
    git merge-base --is-ancestor $NewSrc $NewSnapshot; $srcIsAnc = ($LASTEXITCODE -eq 0)
    $failures += Test-Ancestry $srcDescends $snapParents $OldNbase $revMinusOld $NewSnapshot $srcIsAnc

    # Snapshot tree faithfulness
    $failures += Test-SnapshotTree (git rev-parse "$NewSnapshot^{tree}") (git rev-parse "$NewSrc^{tree}")

    # Guard 3/14 attribution / no imported commits — names, emails, message, added lines
    $expected = @($NewSnapshot, $NewDev)
    $newCommits = @()
    foreach ($sha in (git rev-list "$PreRef..$NewDev")) {
        $newCommits += [pscustomobject]@{
            Sha           = $sha
            AuthorName    = (git show -s --format='%an' $sha)
            AuthorEmail   = (git show -s --format='%ae' $sha)
            CommitterName = (git show -s --format='%cn' $sha)
            CommitterEmail= (git show -s --format='%ce' $sha)
            Message       = (git show -s --format='%B' $sha)
        }
    }
    $addedLines = (git diff --no-color "$PreRef..$NewDev" | Select-String '^\+' | ForEach-Object { $_.Line }) -join "`n"
    $failures += Test-Attribution $newCommits $expected $addedLines

    return @($failures | Where-Object { $_ })
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

$pins = Read-Pins $PinsFile

# A missing or empty pin would silently coerce inside a validator ([int]$null is
# 0, an empty string compares unequal to every real blob), turning a pinned
# invariant into one that quietly passes. Refuse to run unless every pin the sync
# requires is present and non-empty, and every numeric pin is a number. Most of
# these are read by the validators below; a few (NAGRAM_REPO, NAGRAM_BRANCH,
# OLD_NBASE, OLD_NBASE_TREE) are consumed by the sync workflow rather than by this
# script, and are checked here too so a missing key fails at the guard instead of
# part-way through a run. So the list is "pins the sync requires present", not
# "pins this script reads".
$requiredPins = @(
    'ANCHOR_SRC', 'OLD_NBASE', 'OLD_NBASE_TREE', 'NAGRAM_REPO', 'NAGRAM_BRANCH',
    'KEYSTORE_PATH', 'KEYSTORE_BLOB', 'KEYSTORE_CERT_SHA256',
    'SIGNING_GRADLE_PATH', 'SIGNING_GRADLE_BLOB',
    'GITMODULES_BLOB', 'VENDORED_NATIVES',
    'NEKOMIMI_PATH', 'NEKOMIMI_MIN', 'RADOLYN_PATH', 'RADOLYN_EXACT',
    'STRINGS_NAX_PATH', 'STRINGS_NAX_MIN', 'NACONFIG_PATH', 'NACONFIG_ADDCONFIG_MIN',
    'AYU_DB_PATH', 'AYU_DATA_PATH', 'AYU_VERSION', 'AYU_MIN_VERSION', 'AYU_ENTITIES',
    'KEYSTORE_SUBJECT_CN', 'GRADLE_SURFACE', 'SELF_PROTECT',
    'SYNC_IDENTITY_NAME', 'SYNC_IDENTITY_EMAIL'
)
$numericPins = @('NEKOMIMI_MIN', 'RADOLYN_EXACT', 'STRINGS_NAX_MIN', 'NACONFIG_ADDCONFIG_MIN',
    'AYU_VERSION', 'AYU_MIN_VERSION', 'AYU_ENTITIES')
$pinProblems = @()
foreach ($k in $requiredPins) {
    if (-not $pins.ContainsKey($k) -or [string]::IsNullOrWhiteSpace($pins[$k])) { $pinProblems += "missing/empty: $k" }
}
foreach ($k in $numericPins) {
    if ($pins.ContainsKey($k) -and $pins[$k] -notmatch '^\d+$') { $pinProblems += "not numeric: $k = '$($pins[$k])'" }
}
# Vendored-native table (guard 10): the list must parse to at least one name,
# with no duplicates. VENDORED_NATIVES is in $requiredPins so a blank value is
# already caught above; this closes the subtler hole where a non-empty value
# ("," or " , ") parses to zero names — which would turn Guard 10 into a silent
# no-op and later throw when the self-test indexes an empty table. A duplicate
# name silently halves coverage (one typo pins a component twice, another never),
# so reject that too — and reject two entries resolving to the same _PATH for the
# same reason: a same-shape duplicate path (two 040000/tree entries pointing at one
# path, the likely typo since every tree native is 040000/tree) validates that path
# twice while the shadowed one goes unpinned, and the shape check can't catch it
# because both shapes match. Then every listed entry must carry PATH/MODE/TYPE, and
# the shape must be exactly one of the two meaningful ones — a vendored tree
# (040000/tree) or a submodule gitlink (160000/commit). Any other MODE/TYPE would
# make Test-Gitmodules' equality check trivially satisfiable (the path is "pinned"
# in name but never actually constrained — the exact libyuv/openh264 failure mode,
# in table form), and such a row also silently drops out of both negative-test
# loops. A gitlink needs a 40-hex _COMMIT; a tree must NOT carry a stray _COMMIT.
$vendNames = @(Get-VendoredNames $pins)
if ($vendNames.Count -lt 1) {
    $pinProblems += "VENDORED_NATIVES lists no usable entries (got '$($pins['VENDORED_NATIVES'])')"
} else {
    $vendDupes = @($vendNames | Group-Object | Where-Object { $_.Count -gt 1 } | ForEach-Object { $_.Name })
    if ($vendDupes.Count) { $pinProblems += "VENDORED_NATIVES has duplicate name(s): $($vendDupes -join ', ')" }
    $pathOwners = @{}
    foreach ($n in $vendNames) {
        foreach ($suf in 'PATH', 'MODE', 'TYPE') {
            if ([string]::IsNullOrWhiteSpace($pins["${n}_${suf}"])) { $pinProblems += "missing/empty: ${n}_${suf}" }
        }
        $p = $pins["${n}_PATH"]
        if (-not [string]::IsNullOrWhiteSpace($p)) {
            if ($pathOwners.ContainsKey($p)) { $pinProblems += "duplicate _PATH '$p' — ${n} and $($pathOwners[$p]) resolve to the same path (one shadows the other)" }
            else { $pathOwners[$p] = $n }
        }
        $mode = $pins["${n}_MODE"]; $type = $pins["${n}_TYPE"]
        if ($mode -eq '040000' -and $type -eq 'tree') {
            if (-not [string]::IsNullOrWhiteSpace($pins["${n}_COMMIT"])) {
                $pinProblems += "tree ${n} must not carry ${n}_COMMIT (got '$($pins["${n}_COMMIT"])')"
            }
        } elseif ($mode -eq '160000' -and $type -eq 'commit') {
            if ($pins["${n}_COMMIT"] -notmatch '^[0-9a-f]{40}$') {
                $pinProblems += "gitlink ${n} needs a 40-hex ${n}_COMMIT (got '$($pins["${n}_COMMIT"])')"
            }
        } else {
            $pinProblems += "vendored ${n} has shape ${mode}/${type}, must be 040000/tree or 160000/commit"
        }
    }
}
if ($pinProblems.Count) {
    Write-Host 'PINS INCOMPLETE — an unpinned invariant would silently coerce to a passing check:'
    $pinProblems | ForEach-Object { Write-Host "  - $_" }
    Write-Host 'Refusing to run. No push.'
    exit 2
}

# Load and validate BOTH manifests up front, in every mode. The old code read
# them only on the real-candidate path, so -SelfTestOnly could pass while a
# manifest was empty or a pin was stale — which is exactly how the stale README
# pin survived. Reading and sanity-checking them here closes that.
$protectedRows = @(Read-Tsv $ProtectedFile)
$manifestRows  = @(Read-Tsv $WorkflowManifest)
if ($protectedRows.Count -lt 1) { Write-Host '::error::protected-paths.tsv is empty or unreadable'; exit 2 }
if ($manifestRows.Count  -lt 1) { Write-Host '::error::workflow-manifest.tsv is empty or unreadable'; exit 2 }
foreach ($r in $protectedRows) {
    if ([string]::IsNullOrWhiteSpace($r.Path) -or $r.Blob -notmatch '^[0-9a-f]{40}$') {
        Write-Host "::error::protected-paths.tsv malformed row: '$($r.Path)' -> '$($r.Blob)'"; exit 2
    }
}
foreach ($r in $manifestRows) {
    if ([string]::IsNullOrWhiteSpace($r.Path) -or $r.Blob -notmatch '^[0-9a-f]{40}$') {
        Write-Host "::error::workflow-manifest.tsv malformed row: '$($r.Path)' -> '$($r.Blob)'"; exit 2
    }
}

Write-Host '=== guard self-test (must prove it can fail before any pass is trusted) ==='
$st = Invoke-SelfTest $pins
Write-Host $st.Log
if (-not $st.Ok) {
    Write-Host ''
    Write-Host 'SELF-TEST FAILED — a validator could not discriminate good from bad input.'
    Write-Host 'Refusing to trust this guard. No push.'
    exit 2
}
Write-Host 'self-test passed: every validator rejected a bad input and accepted a good one.'

# Protected pins must match the given ref (HEAD from the always-on check). A
# protected file changed without repinning is caught here, at PR time.
if ($CheckPinsAgainst) {
    Write-Host ''
    Write-Host "=== protected pins vs ${CheckPinsAgainst} ==="
    $refBlobs = Get-BlobMap $CheckPinsAgainst
    $drift = @()
    foreach ($r in $protectedRows) {
        if (-not $refBlobs.ContainsKey($r.Path)) { $drift += "missing at ${CheckPinsAgainst}: $($r.Path)" }
        elseif ($refBlobs[$r.Path] -ne $r.Blob) { $drift += "stale pin: $($r.Path) pinned $($r.Blob), tree has $($refBlobs[$r.Path])" }
    }
    if ($drift.Count) {
        Write-Host "PROTECTED PINS STALE against ${CheckPinsAgainst}:"
        $drift | ForEach-Object { Write-Host "  - $_" }
        Write-Host 'Repin protected-paths.tsv in this change. No push.'
        exit 1
    }
    Write-Host "all $($protectedRows.Count) protected pins match ${CheckPinsAgainst}."
}

if ($SelfTestOnly) {
    Write-Host 'SELF-TEST ONLY: OK'
    exit 0
}

# No-op fast-path decision. Runs before any snapshot is built, so it only needs the
# refs the workflow has already resolved: -PreRef (origin/dev), -OldNbase (live
# origin/nbase) and -NewSrc (the resolved Nagram source). Pins are read from the
# trusted PRE above, never from a candidate. The self-test that just passed already
# proved Test-SyncFastPath can tell the three decisions apart.
if ($FastPathOnly) {
    foreach ($p in 'PreRef', 'OldNbase', 'NewSrc') {
        if (-not (Get-Variable $p -ValueOnly)) { Write-Host "::error::fast path: missing required ref -$p"; exit 2 }
    }
    $srcTree = (git rev-parse "$NewSrc^{tree}").Trim()
    if ($LASTEXITCODE -ne 0 -or -not $srcTree) { Write-Host "::error::fast path: cannot resolve source tree of $NewSrc"; exit 2 }
    $liveNbaseTree = (git rev-parse "$OldNbase^{tree}").Trim()
    if ($LASTEXITCODE -ne 0 -or -not $liveNbaseTree) { Write-Host "::error::fast path: cannot resolve nbase tree of $OldNbase"; exit 2 }
    # Resolve PreRef to a commit before asking about ancestry, so an unresolvable
    # dev ref fails as an error rather than folding into the ancestry answer.
    $preCommit = (git rev-parse "$PreRef^{commit}").Trim()
    if ($LASTEXITCODE -ne 0 -or -not $preCommit) { Write-Host "::error::fast path: cannot resolve dev ref $PreRef to a commit"; exit 2 }
    # merge-base --is-ancestor returns 0 (is ancestor), 1 (is not), or something
    # else (128 for a bad/missing ref). Only 0 and 1 are answers; anything else is a
    # resolution error and must fail as one — otherwise a missing object would be
    # misread as a topology-stale block and route the operator to repin, which is
    # the wrong remediation for a fetch/ref problem.
    git merge-base --is-ancestor $OldNbase $preCommit
    $mbExit = $LASTEXITCODE
    if ($mbExit -ne 0 -and $mbExit -ne 1) { Write-Host "::error::fast path: git merge-base --is-ancestor failed (exit $mbExit) resolving $OldNbase vs $PreRef"; exit 2 }
    $devContainsNbase = ($mbExit -eq 0)

    $d = Test-SyncFastPath $srcTree $OldNbase $liveNbaseTree $pins['OLD_NBASE'] $pins['OLD_NBASE_TREE'] $devContainsNbase
    switch ($d.Decision) {
        'proceed' {
            Write-Host "fast path: proceeding to the full sync path — $($d.Reason)."
            exit 0
        }
        'uptodate' {
            Write-Host "fast path: already up to date — $($d.Reason). No snapshot created, no ref pushed."
            exit 3
        }
        'blocked' {
            Write-Host "::error::fast path BLOCKED — $($d.Reason)."
            Write-Host '::error::The source tree matches origin/nbase (nothing to import) but a pinned fact is stale.'
            Write-Host '::error::This needs a human: repin OLD_NBASE / OLD_NBASE_TREE in .github/sync/pins.env to the current origin/nbase.'
            Write-Host '::error::No snapshot created, no ref pushed.'
            exit 1
        }
        default {
            Write-Host "::error::fast path: unexpected decision '$($d.Decision)'"; exit 2
        }
    }
}

# Land-check decision for sync-land.yml. Runs before origin/nbase is fast-forwarded
# onto a reconciliation snapshot minted by hand on the PC. Keyed to the pinned
# OLD_NBASE (-OldNbase), not to wherever origin/nbase points now, so the same facts
# hold on a first run and on an idempotent re-run after the fast-forward has already
# landed; -NewSnapshot is the snapshot, -NewDev is the current dev tip (the snapshot
# must be reachable from it, i.e. the reconciliation is merged). Reads the anchor pins
# and the sync identity from the trusted PRE pins above. The upstream `nagram` remote
# must already be fetched by the caller — this mode resolves, live, the upstream commit
# whose tree the snapshot copies, so it never trusts an operator-supplied source SHA.
# No ref is written here. Whether the live ref is still where we expect is the
# fast-forward step's own re-lease, not this check's. The self-test that already passed
# proved Test-LandCheck and Test-SnapshotIdentity can tell good land facts from bad.
if ($LandCheckOnly) {
    foreach ($p in 'OldNbase', 'NewSnapshot', 'NewDev') {
        if (-not (Get-Variable $p -ValueOnly)) { Write-Host "::error::land check: missing required ref -$p"; exit 2 }
    }

    # Resolve the expected (pinned) old nbase and the snapshot to concrete commits +
    # trees. A bad or unfetched ref must fail as a resolution error (exit 2), not fold
    # into a verdict.
    $expectedOldNbase = (git rev-parse "$OldNbase^{commit}").Trim()
    if ($LASTEXITCODE -ne 0 -or -not $expectedOldNbase) { Write-Host "::error::land check: cannot resolve expected old nbase $OldNbase"; exit 2 }
    $expectedOldNbaseTree = (git rev-parse "$OldNbase^{tree}").Trim()
    if ($LASTEXITCODE -ne 0 -or -not $expectedOldNbaseTree) { Write-Host "::error::land check: cannot resolve old nbase tree of $OldNbase"; exit 2 }
    $snap = (git rev-parse "$NewSnapshot^{commit}").Trim()
    if ($LASTEXITCODE -ne 0 -or -not $snap) { Write-Host "::error::land check: cannot resolve snapshot $NewSnapshot (is it fetched?)"; exit 2 }
    $snapTree = (git rev-parse "$NewSnapshot^{tree}").Trim()
    if ($LASTEXITCODE -ne 0 -or -not $snapTree) { Write-Host "::error::land check: cannot resolve snapshot tree of $NewSnapshot"; exit 2 }
    $newDev = (git rev-parse "$NewDev^{commit}").Trim()
    if ($LASTEXITCODE -ne 0 -or -not $newDev) { Write-Host "::error::land check: cannot resolve dev ref $NewDev to a commit"; exit 2 }

    # Is the snapshot merged into dev? merge-base --is-ancestor answers 0 (yes) or 1
    # (no); anything else (128 for a bad/missing object) is a resolution error, not a
    # verdict — otherwise an unfetched dev would be misread as an unmerged reconciliation
    # and route the operator to the wrong remediation. Mirrors the fast-path discipline.
    git merge-base --is-ancestor $snap $newDev
    $devExit = $LASTEXITCODE
    if ($devExit -ne 0 -and $devExit -ne 1) { Write-Host "::error::land check: git merge-base --is-ancestor failed (exit $devExit) resolving snapshot $snap vs dev $NewDev"; exit 2 }
    $snapDescendsDev = ($devExit -eq 0)

    # Snapshot topology and metadata.
    $snapParents = @((git rev-list --parents -n 1 $snap) -split '\s+' | Select-Object -Skip 1)
    $revMinusOld = @(git rev-list $snap "^$OldNbase")
    $an = (git show -s --format='%an' $snap); $ae = (git show -s --format='%ae' $snap)
    $cn = (git show -s --format='%cn' $snap); $ce = (git show -s --format='%ce' $snap)
    $msg = (git show -s --format='%B' $snap)

    # Discover the upstream commit whose tree the snapshot copies, live from the
    # fetched nagram remote — never from an operator-named SHA. Scan the branch's
    # history for a commit whose tree object equals the snapshot's tree. A snapshot
    # built the sanctioned way (tree = a real upstream tree) matches exactly such a
    # commit; a hand-edited one matches none, which is precisely the failure we want.
    $branch = $pins['NAGRAM_BRANCH']
    $log = @(git log --format='%H %T' "nagram/$branch" 2>$null)
    if ($LASTEXITCODE -ne 0 -or $log.Count -lt 1) {
        Write-Host "::error::land check: cannot read nagram/$branch history — fetch the nagram remote before the land check."
        exit 2
    }
    $matches = @()
    foreach ($line in $log) {
        $sp = $line.Trim().Split(' ')
        if ($sp.Count -ge 2 -and $sp[1] -eq $snapTree) { $matches += $sp[0] }
    }
    # Prefer a match that descends from the recorded anchor. If matches exist but none
    # descends, keep the first for evidence and let Test-LandCheck block on the
    # descends-from-anchor failure, which names the real problem.
    $anchorSrcNew = $null
    foreach ($m in $matches) {
        git merge-base --is-ancestor $pins['ANCHOR_SRC'] $m 2>$null
        if ($LASTEXITCODE -eq 0) { $anchorSrcNew = $m; break }
    }
    if (-not $anchorSrcNew -and $matches.Count -ge 1) { $anchorSrcNew = $matches[0] }

    if ($anchorSrcNew) {
        $srcTree = (git rev-parse "$anchorSrcNew^{tree}").Trim()
        git merge-base --is-ancestor $pins['ANCHOR_SRC'] $anchorSrcNew 2>$null
        $srcDescends = ($LASTEXITCODE -eq 0)
    } else {
        # No upstream commit carries this tree: the snapshot is not a faithful copy of
        # any real upstream commit. Empty srcTree makes the tree-equality check fail.
        $srcTree = ''
        $srcDescends = $false
    }

    # The pinned anchor's own tree, live-resolved. It must be present in the fetched
    # upstream history; if it cannot be resolved that is a fetch/ref error.
    $pinnedAnchorTree = (git rev-parse "$($pins['ANCHOR_SRC'])^{tree}" 2>$null)
    if ($LASTEXITCODE -ne 0 -or -not $pinnedAnchorTree) {
        Write-Host "::error::land check: cannot resolve pinned ANCHOR_SRC $($pins['ANCHOR_SRC']) tree — fetch the nagram remote so the anchor is present."
        exit 2
    }
    $pinnedAnchorTree = $pinnedAnchorTree.Trim()

    $failures = @()
    if (-not $anchorSrcNew) {
        $failures += "land: the snapshot tree $snapTree matches no commit in nagram/$branch — it is not a faithful copy of any upstream commit"
    }
    $failures += Test-LandCheck $snapParents $expectedOldNbase $revMinusOld $snap $snapTree $srcTree $srcDescends `
        $expectedOldNbaseTree $pinnedAnchorTree $pins['OLD_NBASE'] $pins['OLD_NBASE_TREE'] $snapDescendsDev
    $failures += Test-SnapshotIdentity $an $ae $cn $ce $pins['SYNC_IDENTITY_NAME'] $pins['SYNC_IDENTITY_EMAIL']
    # Reuse the existing, self-tested attribution scan: no imported commit, no
    # prohibited attribution token in the snapshot's metadata.
    $snapObj = [pscustomobject]@{ Sha = $snap; AuthorName = $an; AuthorEmail = $ae; CommitterName = $cn; CommitterEmail = $ce; Message = $msg }
    $failures += Test-Attribution @($snapObj) @($snap) ''
    $failures = @($failures | Where-Object { $_ })

    # Evidence, printed and (optionally) written for the pins PR body. A reviewer of
    # the auto-generated pins PR confirms these shown facts rather than rubber-stamping
    # opaque hex.
    $treesMatch = ($srcTree -and $snapTree -eq $srcTree)
    Write-Host ''
    Write-Host '=== land check evidence ==='
    Write-Host "expected old nbase (pin): $expectedOldNbase (tree $expectedOldNbaseTree)"
    Write-Host "snapshot (new nbase)    : $snap (tree $snapTree)"
    Write-Host "dev tip (must contain)  : $newDev (snapshot is ancestor: $snapDescendsDev)"
    Write-Host "pinned ANCHOR_SRC       : $($pins['ANCHOR_SRC']) (tree $pinnedAnchorTree)"
    Write-Host "resolved upstream source: $(if ($anchorSrcNew) { $anchorSrcNew } else { '(none — tree matches no upstream commit)' }) (tree $srcTree)"
    Write-Host "snapshot tree == upstream source tree: $treesMatch"
    if ($EvidenceOut) {
        $ev = @(
            "old_nbase=$expectedOldNbase"
            "old_nbase_tree=$expectedOldNbaseTree"
            "snapshot=$snap"
            "snapshot_tree=$snapTree"
            "dev_tip=$newDev"
            "snapshot_in_dev=$snapDescendsDev"
            "pinned_anchor_src=$($pins['ANCHOR_SRC'])"
            "pinned_anchor_tree=$pinnedAnchorTree"
            "anchor_src_new=$(if ($anchorSrcNew) { $anchorSrcNew } else { '' })"
            "anchor_src_new_tree=$srcTree"
            "trees_match=$treesMatch"
        )
        Set-Content -LiteralPath $EvidenceOut -Value $ev -Encoding utf8
    }

    if ($failures.Count -gt 0) {
        Write-Host ''
        Write-Host "LAND BLOCKED — $($failures.Count) violation(s); origin/nbase must NOT be fast-forwarded:"
        $failures | ForEach-Object { Write-Host "  - $_" }
        Write-Host ''
        Write-Host 'Finish the reconciliation on the PC. No ref written.'
        exit 1
    }
    Write-Host ''
    Write-Host 'LAND CLEAN — the snapshot faithfully copies an upstream commit that descends from the anchor, is locally authored, chains onto the pinned old nbase, and is already merged into dev. Safe to fast-forward.'
    exit 0
}

foreach ($p in 'PreRef', 'OldNbase', 'NewSnapshot', 'NewSrc', 'NewDev') {
    if (-not (Get-Variable $p -ValueOnly)) { Write-Host "missing required ref: -$p"; exit 2 }
}

Write-Host ''
Write-Host '=== guard: real candidate ==='
Write-Host "PRE=$PreRef  OLD_NBASE=$OldNbase  SNAPSHOT=$NewSnapshot  SRC=$NewSrc  CAND=$NewDev"
$failures = @(Invoke-RealGuard $pins $protectedRows $manifestRows)

if ($failures.Count -gt 0) {
    Write-Host ''
    Write-Host "BLOCKED — $($failures.Count) guard violation(s):"
    $failures | ForEach-Object { Write-Host "  - $_" }
    Write-Host ''
    Write-Host 'Push nothing. This routes to reviewed reconciliation on the PC.'
    exit 1
}

Write-Host ''
Write-Host 'GUARD CLEAN — candidate is fully classified and every invariant holds. May push.'
exit 0
