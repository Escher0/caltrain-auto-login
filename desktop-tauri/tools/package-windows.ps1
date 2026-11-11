# SPDX-FileCopyrightText: 2026 Brian Pilnick
# SPDX-License-Identifier: GPL-3.0-or-later
# Additional WebView2 permission: see LICENSING.md at the repository root.

# Run from desktop-tauri. Secrets are read from the environment, never arguments.
param(
    [Parameter(Mandatory)][string]$Repository,
    [Parameter(Mandatory)][string]$Version,
    [Parameter(Mandatory)][string]$Output,
    [switch]$Signed
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
python tools/release.py check-version --repository $Repository --version $Version
New-Item -ItemType Directory -Force $Output | Out-Null
$Output = (Resolve-Path $Output).Path

python tools/generate-notices.py --output "$Output/THIRD_PARTY_LICENSES.html"
Copy-Item ../LICENSE, ../LICENSING.md $Output
# Preserve source and upstream notices with the exact release, including crates.
python tools/source-bundle.py --output "$Output/source.zip"

$config = Join-Path $env:RUNNER_TEMP 'caltrain-release.json'
$overlay = @{}
if ($Signed) {
    if (-not $env:TAURI_SIGNING_PRIVATE_KEY -or -not $env:TAURI_UPDATER_PUBLIC_KEY) {
        throw 'Configure the persistent updater signing key secret and public key variable first.'
    }
    $publicKey = Join-Path $env:RUNNER_TEMP 'caltrain.pub'
    [IO.File]::WriteAllText($publicKey, $env:TAURI_UPDATER_PUBLIC_KEY)
    python tools/release.py config --repository $Repository --version $Version --public-key $publicKey --output $config
    $overlay = Get-Content $config -Raw | ConvertFrom-Json -AsHashtable
}
if (-not $overlay.bundle) { $overlay.bundle = @{} }
$overlay.bundle.resources = @{}
foreach ($name in @('LICENSE', 'LICENSING.md', 'THIRD_PARTY_LICENSES.html')) {
    $overlay.bundle.resources[(Join-Path $Output $name)] = "licenses/$name"
}
$overlay | ConvertTo-Json -Depth 10 | Set-Content $config -Encoding utf8
npm exec --yes --package=@tauri-apps/cli@2.12.4 -- tauri build --ci --config $config -- --locked
$installers = @(Get-ChildItem target/release/bundle/nsis/*-setup.exe)
if ($installers.Count -ne 1) { throw 'Expected exactly one NSIS installer' }
$stageArgs = @()
if ($Signed) { $stageArgs += '--signed' }
$staged = python tools/release.py stage --repository $Repository --version $Version --installer $installers[0].FullName --output $Output @stageArgs
if ($Signed) {
    cargo run --locked --example verify-update -- $staged ($staged + '.sig') $publicKey $Version
    python tools/release.py manifest --repository $Repository --version $Version --installer $staged --output "$Output/latest.json"
}
