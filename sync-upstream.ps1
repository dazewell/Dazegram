# sync-upstream.ps1
# Fetches upstream changes and rebases custom branches on top.
# Usage:
#   .\sync-upstream.ps1                        # update dev + rebase current branch
#   .\sync-upstream.ps1 -Branch feature/foo    # update dev + rebase specific branch
#   .\sync-upstream.ps1 -DevOnly               # only update dev, don't rebase feature branch
#   .\sync-upstream.ps1 -Push                  # also force-push updated branches to origin

param(
    [string]$Branch = "",         # feature branch to rebase; defaults to current branch
    [string]$BaseBranch = "dev",  # local base branch that tracks upstream
    [string]$Upstream = "source/dev", # upstream ref to rebase base branch onto
    [switch]$DevOnly,             # only update base branch
    [switch]$Push                 # push with --force-with-lease after rebase
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Write-Step([string]$msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Write-Ok([string]$msg)   { Write-Host "    $msg" -ForegroundColor Green }
function Write-Warn([string]$msg) { Write-Host "    $msg" -ForegroundColor Yellow }
function Invoke-Git([string[]]$args) {
    git @args
    if ($LASTEXITCODE -ne 0) { throw "git $($args -join ' ') failed (exit $LASTEXITCODE)" }
}

# ── Resolve branches ─────────────────────────────────────────────────────────
$originalBranch = (git rev-parse --abbrev-ref HEAD).Trim()

if (-not $DevOnly) {
    if ($Branch -eq "") {
        $Branch = $originalBranch
    }
    if ($Branch -eq $BaseBranch) {
        Write-Warn "Current branch IS the base branch. Running -DevOnly instead."
        $DevOnly = $true
    }
}

# ── Stash dirty working tree ──────────────────────────────────────────────────
$stashed = $false
$status = (git status --porcelain).Trim()
if ($status -ne "") {
    Write-Step "Stashing uncommitted changes"
    Invoke-Git "stash", "push", "-m", "sync-upstream auto-stash"
    $stashed = $true
    Write-Ok "Stashed."
}

try {
    # ── Fetch upstream ────────────────────────────────────────────────────────
    Write-Step "Fetching upstream ($Upstream)"
    $remote = $Upstream.Split("/")[0]
    Invoke-Git "fetch", $remote
    Write-Ok "Fetched $remote."

    # ── Update base branch ────────────────────────────────────────────────────
    Write-Step "Rebasing $BaseBranch onto $Upstream"
    Invoke-Git "checkout", $BaseBranch
    Invoke-Git "rebase", $Upstream

    $devTip = (git rev-parse --short HEAD).Trim()
    Write-Ok "$BaseBranch is now at $devTip."

    if ($Push) {
        Write-Step "Pushing $BaseBranch to origin"
        Invoke-Git "push", "--force-with-lease", "origin", $BaseBranch
        Write-Ok "Pushed $BaseBranch."
    }

    # ── Rebase feature branch ─────────────────────────────────────────────────
    if (-not $DevOnly) {
        Write-Step "Rebasing $Branch onto $BaseBranch"
        Invoke-Git "checkout", $Branch
        Invoke-Git "rebase", $BaseBranch
        Write-Ok "$Branch rebased onto $BaseBranch."

        if ($Push) {
            Write-Step "Pushing $Branch to origin"
            Invoke-Git "push", "--force-with-lease", "origin", $Branch
            Write-Ok "Pushed $Branch."
        }
    }

    # ── Restore original branch ───────────────────────────────────────────────
    if ($originalBranch -ne (git rev-parse --abbrev-ref HEAD).Trim()) {
        Invoke-Git "checkout", $originalBranch
    }

    Write-Host "`nDone." -ForegroundColor Green

} catch {
    Write-Host "`nERROR: $_" -ForegroundColor Red
    Write-Host "Resolve conflicts, then run:" -ForegroundColor Yellow
    Write-Host "  git rebase --continue   (after fixing conflicts)" -ForegroundColor Yellow
    Write-Host "  git rebase --abort      (to cancel and go back)" -ForegroundColor Yellow
    exit 1
} finally {
    if ($stashed) {
        Write-Step "Restoring stash"
        git stash pop
        if ($LASTEXITCODE -eq 0) { Write-Ok "Stash restored." }
        else { Write-Warn "Stash pop failed — run 'git stash pop' manually." }
    }
}
