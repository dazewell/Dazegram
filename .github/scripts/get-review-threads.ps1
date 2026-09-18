#requires -Version 7

[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^[^/]+/[^/]+$')]
    [string]$Repository,
    [Parameter(Mandatory)]
    [ValidateRange(1, [int]::MaxValue)]
    [int]$PullRequest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

($Owner, $Name) = $Repository -split '/', 2
$Query = @'
query($owner:String!,$name:String!,$number:Int!,$endCursor:String) {
  repository(owner:$owner,name:$name) {
    pullRequest(number:$number) {
      reviewThreads(first:100, after:$endCursor) {
        pageInfo { hasNextPage endCursor }
        nodes { id isResolved path line comments(first:1) { nodes { databaseId body } } }
      }
    }
  }
}
'@

$Threads = @()
$Cursor = $null
do {
    $Arguments = @(
        'api', 'graphql',
        '-f', "query=$Query",
        '-F', "owner=$Owner",
        '-F', "name=$Name",
        '-F', "number=$PullRequest"
    )
    if ($null -ne $Cursor) {
        $Arguments += @('-f', "endCursor=$Cursor")
    }
    $Response = & gh @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Could not read review threads for $Repository#$PullRequest."
    }
    $Page = $Response | ConvertFrom-Json
    if ($null -eq $Page.data.repository.pullRequest) {
        throw "Pull request $Repository#$PullRequest was not found."
    }
    $ReviewThreads = $Page.data.repository.pullRequest.reviewThreads
    $Threads += @($ReviewThreads.nodes)
    $NextCursor = $ReviewThreads.pageInfo.endCursor
    if ($ReviewThreads.pageInfo.hasNextPage -and [string]::IsNullOrWhiteSpace($NextCursor)) {
        throw "Review-thread pagination for $Repository#$PullRequest has another page but no cursor."
    }
    if ($ReviewThreads.pageInfo.hasNextPage -and $NextCursor -eq $Cursor) {
        throw "Review-thread pagination cursor did not advance for $Repository#$PullRequest."
    }
    $Cursor = $NextCursor
} while ($ReviewThreads.pageInfo.hasNextPage)

$Threads | ConvertTo-Json -AsArray -Depth 8
