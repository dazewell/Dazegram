#requires -Version 7

# Requests a Copilot code review on a pull request, the only way one happens
# now that no ruleset requests it automatically. Every request is billed, so
# this refuses drafts, heads Copilot already reviewed, a request still in
# flight, and anything past the per-PR budget.
#
# Prints one JSON object. status is one of:
#   requested         the request landed; nothing waited for
#   requested-unconfirmed  the POST succeeded but the timeline has not shown it
#                     yet; do not retry, check again in a few minutes
#   pending           an earlier request is still in flight; nothing new sent
#   already-reviewed  Copilot has already reviewed this head; nothing sent
#   reviewed          a review landed while waiting (review + comments set)
#   timed-out         the request landed but no review arrived in time
# Refusals and API failures throw instead.

[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateRange(1, [int]::MaxValue)]
    [int]$PullRequest,
    [ValidatePattern('^[^/]+/[^/]+$')]
    [string]$Repository,
    [switch]$Wait,
    [ValidateRange(1, 120)]
    [int]$TimeoutMinutes = 15,
    # Past the budget only when dazewell asked for another review by name.
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$Budget = 2
$BotLogin = 'copilot-pull-request-reviewer[bot]'

function Invoke-Gh {
    param([string[]]$Arguments)
    $Output = & gh @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "gh $($Arguments -join ' ') failed with exit code $LASTEXITCODE."
    }
    $Output
}

function Get-Pages {
    param([string]$Path)
    # --slurp wraps every page in one outer array; flatten it back out.
    $Pages = Invoke-Gh @('api', '--paginate', '--slurp', $Path) | ConvertFrom-Json -NoEnumerate
    @($Pages | ForEach-Object { $_ } | ForEach-Object { $_ })
}

# The reviews endpoint names the bot copilot-pull-request-reviewer[bot], the
# timeline and comments endpoints name it Copilot. Match both.
function Test-Copilot {
    param($User)
    $null -ne $User -and $User.login -like '*copilot*'
}

function Get-CopilotState {
    $Reviews = @(Get-Pages "repos/$Repository/pulls/$PullRequest/reviews" |
        Where-Object { Test-Copilot $_.user } |
        Where-Object { $_.PSObject.Properties['submitted_at'] -and $_.submitted_at })
    $Requests = @(Get-Pages "repos/$Repository/issues/$PullRequest/timeline" |
        Where-Object { $_.event -eq 'review_requested' -and $_.PSObject.Properties['requested_reviewer'] } |
        Where-Object { Test-Copilot $_.requested_reviewer })
    # Reviews the old ruleset fired left no review_requested event, so count
    # whichever is larger against the budget. That undercounts one mix, a legacy
    # review plus an unanswered manual request, which only old PRs can have.
    $Used = [Math]::Max($Reviews.Count, $Requests.Count)
    [pscustomobject]@{ Reviews = $Reviews; Requests = $Requests; Used = $Used }
}

function Get-ReviewResult {
    param($Review)
    # The per-review comments endpoint omits line numbers; the PR-wide one has them.
    $Comments = @(Get-Pages "repos/$Repository/pulls/$PullRequest/comments" |
        Where-Object { $_.pull_request_review_id -eq $Review.id } |
        ForEach-Object {
            [pscustomobject]@{
                id   = $_.id
                path = $_.path
                line = if ($null -ne $_.line) { $_.line } else { $_.original_line }
                body = $_.body
            }
        })
    [pscustomobject]@{
        review   = [pscustomobject]@{
            id           = $Review.id
            state        = $Review.state
            commit_id    = $Review.commit_id
            submitted_at = $Review.submitted_at
            body         = $Review.body
        }
        comments = $Comments
    }
}

function Write-Result {
    param([string]$Status, $Review)
    $Result = [ordered]@{
        status         = $Status
        repository     = $Repository
        pull_request   = $PullRequest
        head_sha       = $HeadSha
        reviews_used   = $State.Used
        request_budget = $Budget
        review         = $null
        comments       = @()
    }
    if ($null -ne $Review) {
        $Detail = Get-ReviewResult $Review
        $Result.review = $Detail.review
        $Result.comments = $Detail.comments
    }
    [pscustomobject]$Result | ConvertTo-Json -Depth 8
}

function Wait-ForReview {
    param([int]$Baseline)
    $Deadline = (Get-Date).AddMinutes($TimeoutMinutes)
    while ((Get-Date) -lt $Deadline) {
        Start-Sleep -Seconds 20
        $script:State = Get-CopilotState
        if ($State.Reviews.Count -gt $Baseline) {
            return ($State.Reviews | Sort-Object submitted_at | Select-Object -Last 1)
        }
    }
    $null
}

if ([string]::IsNullOrWhiteSpace($Repository)) {
    $Repository = (Invoke-Gh @('repo', 'view', '--json', 'nameWithOwner', '--jq', '.nameWithOwner')).Trim()
}

$Pr = Invoke-Gh @('api', "repos/$Repository/pulls/$PullRequest") | ConvertFrom-Json
if ($Pr.state -ne 'open') {
    throw "$Repository#$PullRequest is $($Pr.state); only an open pull request can be reviewed."
}
if ($Pr.draft) {
    throw "$Repository#$PullRequest is a draft. Mark it ready (gh pr ready $PullRequest) before requesting a review."
}
$HeadSha = $Pr.head.sha
$State = Get-CopilotState

$HeadReview = $State.Reviews | Where-Object { $_.commit_id -eq $HeadSha } |
    Sort-Object submitted_at | Select-Object -Last 1
if ($null -ne $HeadReview) {
    Write-Result 'already-reviewed' $HeadReview
    return
}

$LastRequest = $State.Requests | Sort-Object created_at | Select-Object -Last 1
$LastReview = $State.Reviews | Sort-Object submitted_at | Select-Object -Last 1
# A request Copilot never answered stops counting as in flight after an hour,
# so one lost request cannot block the PR for good. It still counts as used.
$InFlight = $null -ne $LastRequest -and
    ([datetime]$LastRequest.created_at).ToUniversalTime() -gt (Get-Date).ToUniversalTime().AddMinutes(-60) -and
    ($null -eq $LastReview -or [datetime]$LastRequest.created_at -gt [datetime]$LastReview.submitted_at)
if ($InFlight) {
    if (-not $Wait) {
        Write-Result 'pending' $null
        return
    }
    $Review = Wait-ForReview $State.Reviews.Count
    if ($null -eq $Review) { Write-Result 'timed-out' $null } else { Write-Result 'reviewed' $Review }
    return
}

if ($State.Used -ge $Budget -and -not $Force) {
    throw "$Repository#$PullRequest has used $($State.Used) of $Budget Copilot reviews. Stop and report instead of requesting another."
}

$Baseline = $State
# The POST answers 200 with an empty requested_reviewers list even when the
# request landed: bots are never listed there. The timeline is the proof.
$Body = Join-Path ([System.IO.Path]::GetTempPath()) "copilot-review-$PullRequest-$PID.json"
try {
    [System.IO.File]::WriteAllText($Body, (@{ reviewers = @($BotLogin) } | ConvertTo-Json -Compress),
        [System.Text.UTF8Encoding]::new($false))
    Invoke-Gh @('api', '--method', 'POST', "repos/$Repository/pulls/$PullRequest/requested_reviewers",
        '--input', $Body, '--silent') | Out-Null
} finally {
    Remove-Item -LiteralPath $Body -ErrorAction SilentlyContinue
}

$Landed = $false
foreach ($Attempt in 1..12) {
    Start-Sleep -Seconds 5
    $State = Get-CopilotState
    if ($State.Requests.Count -gt $Baseline.Requests.Count) {
        $Landed = $true
        break
    }
}
if (-not $Landed) {
    # The POST succeeded, so the request most likely landed and the timeline is
    # lagging. Throwing here would read as a failure and invite a second billed
    # request on retry.
    Write-Result 'requested-unconfirmed' $null
    return
}

if (-not $Wait) {
    Write-Result 'requested' $null
    return
}
$Review = Wait-ForReview $Baseline.Reviews.Count
if ($null -eq $Review) { Write-Result 'timed-out' $null } else { Write-Result 'reviewed' $Review }
