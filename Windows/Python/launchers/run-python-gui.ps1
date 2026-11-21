$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$WinUiLauncher = Join-Path $ProjectRoot 'WinUI\launchers\run-winui.ps1'
Write-Host 'The legacy CustomTkinter launcher now opens the official WinUI client.'
& $WinUiLauncher @args
exit $LASTEXITCODE
