#requires -Version 7

<#
.SYNOPSIS
    Build a process-conformant branch name from a slug.

.DESCRIPTION
    Prints <today>_<slug> (or <today>-<slug> with -Separator '-'), so nobody has
    to remember today's date or the format. The result is validated with
    test-branch-name.ps1, which is the single source of truth for the format
    process-rules.yml enforces on every PR.

    An existing date prefix on the slug is replaced rather than stacked, so
    passing a whole branch name back in is safe.

.EXAMPLE
    ./.github/scripts/new-branch-name.ps1 -Slug video-cc
    2026-08-05_video-cc

.EXAMPLE
    ./.github/scripts/new-branch-name.ps1 -Slug 'Fix Expand Button' -Separator -
    2026-08-05-fix-expand-button
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory, Position = 0)]
    [string]$Slug,

    [ValidateSet('_', '-')]
    [string]$Separator = '_',

    [datetime]$Date = (Get-Date)
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$Clean = $Slug.Trim().ToLowerInvariant()
$Clean = $Clean -replace '^\d{4}-\d{1,2}-\d{1,2}[-_]', ''
$Clean = $Clean -replace '[^a-z0-9]+', '-'
$Clean = $Clean.Trim('-')

if ([string]::IsNullOrEmpty($Clean)) {
    throw "Slug '$Slug' has no usable characters left after normalization."
}

$Name = '{0}{1}{2}' -f $Date.ToString('yyyy-MM-dd', [Globalization.CultureInfo]::InvariantCulture), $Separator, $Clean

$Validator = Join-Path $PSScriptRoot 'test-branch-name.ps1'
$global:LASTEXITCODE = 0
& $Validator -Branch $Name *> $null
if ($global:LASTEXITCODE -ne 0) {
    throw "Generated branch name '$Name' does not match the required format."
}

Write-Output $Name
