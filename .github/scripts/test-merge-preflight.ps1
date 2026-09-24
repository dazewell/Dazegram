#requires -Version 7

[CmdletBinding()]
param(
    [string]$Repository,
    [int]$PullRequest,
    [string]$ExpectedHead,
    [string]$ExpectedBranch
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

try {
    $InputErrors = @()
    if ($Repository -notmatch '^[^/]+/[^/]+$') { $InputErrors += "repository '$Repository' must be owner/name" }
    if ($PullRequest -lt 1) { $InputErrors += 'pull request number must be positive' }
    if ($ExpectedHead -notmatch '^[0-9a-f]{40}$') { $InputErrors += "expected head '$ExpectedHead' must be a 40-character lowercase SHA" }
    if ([string]::IsNullOrWhiteSpace($ExpectedBranch)) { $InputErrors += 'expected branch is required' }
    if ($InputErrors.Count -gt 0) {
        foreach ($Error in $InputErrors) {
            Write-Host "::error::$Error"
        }
        exit 2
    }

    $global:LASTEXITCODE = 0
    $BranchOutput = & (Join-Path $PSScriptRoot 'test-branch-name.ps1') -Branch $ExpectedBranch 2>&1
    if ($global:LASTEXITCODE -ne 0) {
        Write-Host "::error::Expected branch '$ExpectedBranch' does not meet the branch-name rule."
        exit 2
    }

    ($Owner, $Name) = $Repository -split '/', 2
    $Query = @'
query($owner:String!,$name:String!,$number:Int!) {
  repository(owner:$owner,name:$name) {
    pullRequest(number:$number) {
      state
      isDraft
      baseRefName
      headRefName
      headRefOid
      mergeStateStatus
    }
  }
}
'@
    $Response = & gh api graphql -f "query=$Query" -F "owner=$Owner" -F "name=$Name" -F "number=$PullRequest"
    if ($LASTEXITCODE -ne 0) {
        throw "Could not read pull request $Repository#$PullRequest."
    }
    $Pull = ($Response | ConvertFrom-Json).data.repository.pullRequest
    if ($null -eq $Pull) {
        throw "Pull request $Repository#$PullRequest was not found."
    }

    $TerminalBlockers = @()
    if ($Pull.state -ne 'OPEN') { $TerminalBlockers += "state is $($Pull.state), not OPEN" }
    if ($Pull.isDraft) { $TerminalBlockers += 'pull request is a draft' }
    if ($Pull.baseRefName -ne 'dev') { $TerminalBlockers += "base branch is $($Pull.baseRefName), not dev" }
    if ($Pull.headRefName -ne $ExpectedBranch) { $TerminalBlockers += "head branch is $($Pull.headRefName), not $ExpectedBranch" }
    if ($Pull.headRefOid -ne $ExpectedHead) { $TerminalBlockers += "head SHA $($Pull.headRefOid) differs from approved SHA $ExpectedHead" }

    $Threads = @(& (Join-Path $PSScriptRoot 'get-review-threads.ps1') -Repository $Repository -PullRequest $PullRequest | ConvertFrom-Json)
    $OpenThreads = @($Threads | Where-Object { -not $_.isResolved })
    if ($OpenThreads.Count -gt 0) { $TerminalBlockers += "$($OpenThreads.Count) unresolved review thread(s)" }

    if ($TerminalBlockers.Count -gt 0) {
        foreach ($Blocker in $TerminalBlockers) {
            Write-Host "::error::$Blocker"
        }
        exit 2
    }
    if ($Pull.mergeStateStatus -ne 'CLEAN') {
        Write-Host "::warning::merge state is $($Pull.mergeStateStatus), not CLEAN"
        exit 1
    }

    Write-Host "Preflight passed for $Repository#$PullRequest at $ExpectedHead."
    exit 0
}
catch {
    Write-Host "::error::$($_.Exception.Message)"
    exit 3
}
