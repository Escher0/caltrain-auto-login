# SPDX-FileCopyrightText: 2026 Brian Pilnick
# SPDX-License-Identifier: GPL-3.0-or-later
# Additional WebView2 permission: see LICENSING.md at the repository root.

param([Parameter(Mandatory=$true)][string]$ExecutablePath)
$ErrorActionPreference = 'Stop'
$ExecutablePath = (Resolve-Path $ExecutablePath).Path
$profile = Join-Path ([IO.Path]::GetTempPath()) ("caltrain-tray-check-" + [Guid]::NewGuid())
$previousProfile = $env:CALTRAIN_PROFILE
function Normalize-Name([string]$name) {
    if ($name -cmatch '^Caltrain_WiFi(?: \d+)?$') { return 'Caltrain_WiFi' }
    return $name
}
try {
    $env:CALTRAIN_PROFILE = $profile
    $process = Start-Process $ExecutablePath -ArgumentList @('--diagnostic-seconds','8') -PassThru
    $null = $process.Handle
    if (-not $process.WaitForExit(20000)) { throw 'Tray app did not exit within 20 seconds.' }
    if ($process.ExitCode -ne 0) { throw "Tray app exited with $($process.ExitCode)." }
    $events = @(Get-Content "$profile\events.jsonl" | ForEach-Object { $_ | ConvertFrom-Json })
    if (@($events | Where-Object event -eq 'network-error').Count -gt 0) {
        throw 'Native monitor reported an error. Inspect the activity log.'
    }
    $snapshot = ($events | Where-Object event -eq 'network' | Select-Object -Last 1).detail
    if (-not $snapshot) { throw 'Native monitor did not produce a snapshot.' }
    $reference = @(Get-NetConnectionProfile)
    $nativeProfiles = @($snapshot.profiles | ForEach-Object {
        (Normalize-Name $_.name) + ':' + $_.internet
    } | Sort-Object)
    $referenceProfiles = @($reference | ForEach-Object {
        (Normalize-Name $_.Name) + ':' + ($_.IPv4Connectivity -eq 'Internet' -or $_.IPv6Connectivity -eq 'Internet')
    } | Sort-Object)
    if (($nativeProfiles -join ',') -cne ($referenceProfiles -join ',')) {
        throw 'Native connected profiles differ from Get-NetConnectionProfile. Saved profiles must not be treated as active.'
    }
    Write-Output "Native profiles and Internet flags match Windows ($($reference.Count) profiles). $($snapshot.status)"
} finally {
    $env:CALTRAIN_PROFILE = $previousProfile
    if ($process -and -not $process.HasExited) { $process.Kill(); $process.WaitForExit() }
    # Retain diagnostics on failure; remove only our temporary profile on success.
    if ($snapshot -and ($nativeProfiles -join ',') -ceq ($referenceProfiles -join ',')) {
        Remove-Item -Recurse -Force $profile
    } else { Write-Output "Tray diagnostics: $profile" }
}
