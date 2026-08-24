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
    [switch]$SelfTestOnly
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

# Guard 9 + "every tree delta must be partitioned; unclassified means BLOCK".
# The only auto-pushable class is an in-place modification of a path that already
# exists on dev. Any addition or deletion is unclassified and blocks.
function Test-Partition([hashtable]$preBlobs, [hashtable]$candBlobs) {
    $f = @()
    $added   = @($candBlobs.Keys | Where-Object { -not $preBlobs.ContainsKey($_) })
    $removed = @($preBlobs.Keys  | Where-Object { -not $candBlobs.ContainsKey($_) })
    foreach ($p in $added)   { $f += "NEW path (unreviewed, blocks pending audit): $p" }
    foreach ($p in $removed) { $f += "REMOVED path (fork-overlay loss, unclassified): $p" }
    return $f
}

# Guard 10: .gitmodules blob unchanged; BoringSSL stays a vendored tree, never a
# submodule commit.
function Test-Gitmodules([string]$candGitmodulesBlob, [string]$boringMode, [string]$boringType, [hashtable]$pins) {
    $f = @()
    if ($candGitmodulesBlob -ne $pins['GITMODULES_BLOB']) { $f += ".gitmodules blob changed: $candGitmodulesBlob" }
    if ($boringMode -ne $pins['BORINGSSL_MODE'] -or $boringType -ne $pins['BORINGSSL_TYPE']) {
        $f += "BoringSSL entry is $boringMode $boringType, expected $($pins['BORINGSSL_MODE']) $($pins['BORINGSSL_TYPE']) (submodule-ified?)"
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

# Guard 14: no upstream commits imported, and no prohibited attribution in the
# metadata of the commits this sync introduces.
function Test-Attribution($newCommits, [string[]]$expectedShas) {
    $f = @()
    $seen = @($newCommits | ForEach-Object { $_.Sha })
    foreach ($s in $seen) {
        if ($s -notin $expectedShas) { $f += "guard14: unexpected commit imported into dev: $s" }
    }
    $tokens = @('co-authored-by', 'generated with', 'generated by copilot', 'signed-off-by: copilot', 'authored-by: copilot')
    foreach ($c in $newCommits) {
        $hay = "$($c.Author) $($c.Committer) $($c.Message)".ToLowerInvariant()
        foreach ($tok in $tokens) {
            if ($hay.Contains($tok)) { $f += "guard14: prohibited attribution token '$tok' in $($c.Sha)" }
        }
    }
    return $f
}

# ---------------------------------------------------------------------------
# Self-test: prove each validator can both fail and pass before trusting it.
# ---------------------------------------------------------------------------

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

    # Guard 9 / partition
    $pre = @{ 'a' = '1'; 'b' = '2' }
    $addC = @{ 'a' = '1'; 'b' = '2'; 'BRANDING.md' = '9' }
    $delC = @{ 'a' = '1' }
    $modC = @{ 'a' = '1'; 'b' = '9' }
    $ok = (Assert-Fails  'Test-Partition(add)' (Test-Partition $pre $addC) ([ref]$log)) -and $ok
    $ok = (Assert-Fails  'Test-Partition(del)' (Test-Partition $pre $delC) ([ref]$log)) -and $ok
    $ok = (Assert-Passes 'Test-Partition(mod)' (Test-Partition $pre $modC) ([ref]$log)) -and $ok

    # Guard 10 gitmodules / boringssl
    $ok = (Assert-Fails  'Test-Gitmodules(blob)'  (Test-Gitmodules 'wrongblob' '040000' 'tree' $pins) ([ref]$log)) -and $ok
    $ok = (Assert-Fails  'Test-Gitmodules(commit)' (Test-Gitmodules $pins['GITMODULES_BLOB'] '160000' 'commit' $pins) ([ref]$log)) -and $ok
    $ok = (Assert-Passes 'Test-Gitmodules'        (Test-Gitmodules $pins['GITMODULES_BLOB'] '040000' 'tree' $pins) ([ref]$log)) -and $ok

    # Guard 11 layer floors
    $ok = (Assert-Fails  'Test-LayerFloors(low)'  (Test-LayerFloors 171 57 599 262 $pins) ([ref]$log)) -and $ok
    $ok = (Assert-Fails  'Test-LayerFloors(rad)'  (Test-LayerFloors 172 56 599 262 $pins) ([ref]$log)) -and $ok
    $ok = (Assert-Passes 'Test-LayerFloors'       (Test-LayerFloors 172 57 599 262 $pins) ([ref]$log)) -and $ok

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

    # Guard 14 attribution
    $good = @([pscustomobject]@{ Sha='SNAP'; Author='bot@x'; Committer='bot@x'; Message='snapshot #infra' })
    $badTok = @([pscustomobject]@{ Sha='SNAP'; Author='bot@x'; Committer='bot@x'; Message='snapshot`nCo-authored-by: X' })
    $badImp = @(
        [pscustomobject]@{ Sha='SNAP';  Author='bot@x'; Committer='bot@x'; Message='snapshot #infra' },
        [pscustomobject]@{ Sha='UPSTR'; Author='u@x';   Committer='u@x';   Message='upstream feature' })
    $ok = (Assert-Fails  'Test-Attribution(token)'    (Test-Attribution $badTok @('SNAP')) ([ref]$log)) -and $ok
    $ok = (Assert-Fails  'Test-Attribution(imported)' (Test-Attribution $badImp @('SNAP')) ([ref]$log)) -and $ok
    $ok = (Assert-Passes 'Test-Attribution'           (Test-Attribution $good   @('SNAP')) ([ref]$log)) -and $ok

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

    # Guard 9 + partition
    $failures += Test-Partition $preBlobs $candBlobs

    # Guard 10 gitmodules / boringssl
    $boring = Get-PathEntry $NewDev $pins['BORINGSSL_PATH']
    $bMode = if ($boring) { $boring.Mode } else { 'MISSING' }
    $bType = if ($boring) { $boring.Type } else { 'MISSING' }
    $gm = if ($candBlobs.ContainsKey('.gitmodules')) { $candBlobs['.gitmodules'] } else { 'MISSING' }
    $failures += Test-Gitmodules $gm $bMode $bType $pins

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

    # Guard 3/4 ancestry
    git merge-base --is-ancestor $pins['ANCHOR_SRC'] $NewSrc; $srcDescends = ($LASTEXITCODE -eq 0)
    $snapParents = @((git rev-list --parents -n 1 $NewSnapshot) -split '\s+' | Select-Object -Skip 1)
    $revMinusOld = @(git rev-list $NewSnapshot "^$OldNbase")
    git merge-base --is-ancestor $NewSrc $NewSnapshot; $srcIsAnc = ($LASTEXITCODE -eq 0)
    $failures += Test-Ancestry $srcDescends $snapParents $OldNbase $revMinusOld $NewSnapshot $srcIsAnc

    # Snapshot tree faithfulness
    $failures += Test-SnapshotTree (git rev-parse "$NewSnapshot^{tree}") (git rev-parse "$NewSrc^{tree}")

    # Guard 14 attribution / no imported commits
    $expected = @($NewSnapshot, $NewDev)
    $newCommits = @()
    foreach ($sha in (git rev-list "$PreRef..$NewDev")) {
        $newCommits += [pscustomobject]@{
            Sha       = $sha
            Author    = (git show -s --format='%ae' $sha)
            Committer = (git show -s --format='%ce' $sha)
            Message   = (git show -s --format='%B' $sha)
        }
    }
    $failures += Test-Attribution $newCommits $expected

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
    'GITMODULES_BLOB', 'BORINGSSL_PATH', 'BORINGSSL_MODE', 'BORINGSSL_TYPE',
    'NEKOMIMI_PATH', 'NEKOMIMI_MIN', 'RADOLYN_PATH', 'RADOLYN_EXACT',
    'STRINGS_NAX_PATH', 'STRINGS_NAX_MIN', 'NACONFIG_PATH', 'NACONFIG_ADDCONFIG_MIN',
    'AYU_DB_PATH', 'AYU_DATA_PATH', 'AYU_VERSION', 'AYU_MIN_VERSION', 'AYU_ENTITIES',
    'SELF_PROTECT'
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
if ($pinProblems.Count) {
    Write-Host 'PINS INCOMPLETE — an unpinned invariant would silently coerce to a passing check:'
    $pinProblems | ForEach-Object { Write-Host "  - $_" }
    Write-Host 'Refusing to run. No push.'
    exit 2
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

if ($SelfTestOnly) {
    Write-Host 'SELF-TEST ONLY: OK'
    exit 0
}

foreach ($p in 'PreRef', 'OldNbase', 'NewSnapshot', 'NewSrc', 'NewDev') {
    if (-not (Get-Variable $p -ValueOnly)) { Write-Host "missing required ref: -$p"; exit 2 }
}

$protectedRows = Read-Tsv $ProtectedFile
$manifestRows  = Read-Tsv $WorkflowManifest

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
