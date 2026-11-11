# SPDX-FileCopyrightText: 2026 Brian Pilnick
# SPDX-License-Identifier: GPL-3.0-or-later
# Additional WebView2 permission: see LICENSING.md at the repository root.

# Destructive only to a disposable GitHub-hosted Windows runner's test install.
param([Parameter(Mandatory)][string]$Installer)
$ErrorActionPreference = 'Stop'
if ($env:GITHUB_ACTIONS -ne 'true' -or $env:RUNNER_ENVIRONMENT -ne 'github-hosted') {
    throw 'Run this installer test only on a disposable GitHub-hosted Windows runner.'
}
$install = Join-Path $env:RUNNER_TEMP 'caltrain-installer-test'
$runKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'
$name = 'Caltrain Wi-Fi'
if (Test-Path $install) { throw 'Test install directory already exists' }
if (Get-ItemProperty -Path $runKey -Name $name -ErrorAction SilentlyContinue) {
    throw 'Refusing to overwrite an existing startup entry'
}

function Install-TestApp {
    $process = Start-Process -FilePath $Installer -ArgumentList @('/S', "/D=$install") -Wait -PassThru
    if ($process.ExitCode -ne 0 -or -not (Test-Path "$install/caltrain-wifi.exe")) {
        throw 'Silent installation failed'
    }
}
function Uninstall-TestApp {
    param([switch]$Updating)
    $arguments = @('/S')
    if ($Updating) { $arguments += '/UPDATE' }
    $arguments += "_?=$install"
    $process = Start-Process -FilePath "$install/uninstall.exe" -ArgumentList $arguments -Wait -PassThru
    if ($process.ExitCode -ne 0 -or (Test-Path "$install/caltrain-wifi.exe")) {
        throw 'Silent uninstallation failed'
    }
}

try {
    Install-TestApp
    New-Item -Path $runKey -Force | Out-Null
    New-ItemProperty -Path $runKey -Name $name -Value "$install\caltrain-wifi.exe --autostart" -PropertyType String -Force | Out-Null
    Uninstall-TestApp -Updating
    if ((Get-ItemPropertyValue -Path $runKey -Name $name) -ne "$install\caltrain-wifi.exe --autostart") {
        throw 'Updater uninstall changed the startup preference'
    }
    Install-TestApp
    Uninstall-TestApp
    if (Get-ItemProperty -Path $runKey -Name $name -ErrorAction SilentlyContinue) {
        throw 'Uninstall left this installation registered for startup'
    }

    Install-TestApp
    $quoted = '"' + "$install\caltrain-wifi.exe" + '" --autostart'
    New-ItemProperty -Path $runKey -Name $name -Value $quoted -PropertyType String -Force | Out-Null
    Uninstall-TestApp
    if (Get-ItemProperty -Path $runKey -Name $name -ErrorAction SilentlyContinue) {
        throw 'Uninstall left a quoted startup command behind'
    }

    # A registration pointing to another installation must survive uninstalling this one.
    Install-TestApp
    $other = 'C:\Another Caltrain Install\caltrain-wifi.exe --autostart'
    New-ItemProperty -Path $runKey -Name $name -Value $other -PropertyType String -Force | Out-Null
    Uninstall-TestApp
    if ((Get-ItemPropertyValue -Path $runKey -Name $name) -ne $other) {
        throw 'Uninstall changed a different installation startup entry'
    }
} finally {
    Remove-ItemProperty -Path $runKey -Name $name -ErrorAction SilentlyContinue
}
Write-Output 'Silent install/uninstall passed; updater preference and another installation preserved, own startup commands removed.'
