#requires -Version 7

[CmdletBinding()]
param(
    [string]$Branch,
    [switch]$SelfTest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$Pattern = '^\d{4}-\d{2}-\d{2}[-_][a-z0-9]+(?:-[a-z0-9]+)*$'

function Test-BranchFormat([string]$Name) {
    $Match = [regex]::Match($Name, $Pattern)
    if (-not $Match.Success) {
        return $false
    }
    $Date = [datetime]::MinValue
    return [datetime]::TryParseExact(
        $Name.Substring(0, 10),
        'yyyy-MM-dd',
        [Globalization.CultureInfo]::InvariantCulture,
        [Globalization.DateTimeStyles]::None,
        [ref]$Date
    )
}

if ($SelfTest) {
    foreach ($Name in @('2026-09-17_ci-process-rules', '2026-09-17-ci-process-rules')) {
        if (-not (Test-BranchFormat $Name)) {
            throw "Known-valid branch name rejected: $Name"
        }
    }
    foreach ($Name in @('ci-process-rules', '2026-9-17_ci-process-rules', '2026-09-31_ci-process-rules', '2026-09-17_ci_process_rules')) {
        if (Test-BranchFormat $Name) {
            throw "Known-invalid branch name accepted: $Name"
        }
    }
    Write-Host 'Branch-name validator self-test passed.'
    exit 0
}

if ([string]::IsNullOrWhiteSpace($Branch)) {
    throw 'Branch is required unless -SelfTest is specified.'
}

if (-not (Test-BranchFormat $Branch)) {
    Write-Host "::error::Branch '$Branch' must match <YYYY-MM-DD>[-_]<lowercase-kebab-slug>."
    exit 1
}

Write-Host "Branch '$Branch' matches the required format."
exit 0
