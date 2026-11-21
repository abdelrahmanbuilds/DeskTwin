$ErrorActionPreference = 'Stop'
$windowsRoot = Join-Path $PSScriptRoot 'Windows'
$python = Join-Path $windowsRoot '.venv\Scripts\python.exe'
$requirements = Join-Path $windowsRoot 'Python\dependencies\requirements-winui.txt'
$app = Join-Path $windowsRoot 'WinUI\app\DeskTwin.WinUI.exe'

function Stop-WithHelp([string] $message) {
    Write-Host "`nDeskTwin could not start." -ForegroundColor Red
    Write-Host $message
    Read-Host 'Press Enter to close this window' | Out-Null
    exit 1
}

if (-not (Test-Path -LiteralPath $app)) {
    Stop-WithHelp 'The Windows app files are incomplete. Re-download and extract the DeskTwin Windows ZIP, then double-click Start-DeskTwin.bat.'
}

$dotnet = Get-Command dotnet.exe -ErrorAction SilentlyContinue
if (-not $dotnet) {
    Stop-WithHelp 'Install the .NET 8 Desktop Runtime, then try again: https://dotnet.microsoft.com/download/dotnet/8.0'
}
$runtimeList = (& $dotnet.Source --list-runtimes 2>$null | Out-String)
if ($LASTEXITCODE -ne 0 -or $runtimeList -notmatch 'Microsoft\.WindowsDesktop\.App 8\.') {
    Stop-WithHelp 'Install the .NET 8 Desktop Runtime, then try again: https://dotnet.microsoft.com/download/dotnet/8.0'
}

$pythonLauncher = Get-Command py.exe -ErrorAction SilentlyContinue
if (-not $pythonLauncher) {
    Stop-WithHelp 'Install Python 3.11 for Windows from https://www.python.org/downloads/windows/. During setup, enable the Python launcher, then try again.'
}

if (-not (Test-Path -LiteralPath $python)) {
    Write-Host 'Preparing DeskTwin for its first run. This can take a few minutes...'
    & $pythonLauncher.Source -3.11 -m venv (Join-Path $windowsRoot '.venv')
    if ($LASTEXITCODE -ne 0) {
        Stop-WithHelp 'Python 3.11 was not found. Install Python 3.11 for Windows, then try again.'
    }
}

Write-Host 'Checking DeskTwin components...'
& $python -m pip install --disable-pip-version-check -r $requirements
if ($LASTEXITCODE -ne 0) {
    Stop-WithHelp 'DeskTwin could not prepare its Python components. Check your internet connection and try again.'
}

$env:DESKTWIN_PYTHON = $python
try {
    Start-Process -FilePath $app -WorkingDirectory $windowsRoot
} catch {
    Stop-WithHelp "Windows could not start DeskTwin: $($_.Exception.Message)"
}
