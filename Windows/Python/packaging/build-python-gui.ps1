$ErrorActionPreference = "Stop"

# DeskTwin Python GUI packaging script (WinUI has its own launch path).
# Run from PowerShell:
#   Set-ExecutionPolicy -Scope Process Bypass
#   .\build-python-gui.ps1
#
# The end user receives an executable/installer and does not need Python
# or any project libraries installed.

$ProjectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $ProjectRoot

$BuildRoot = Join-Path $ProjectRoot ".build"
$VenvPath = Join-Path $BuildRoot "venv"
$DistPath = Join-Path $ProjectRoot "dist\DeskTwin-PythonGUI"
$DesktopPath = [Environment]::GetFolderPath("Desktop")
$OutputPath = Join-Path $DesktopPath "DeskTwin-PythonGUI-release"
$IssPath = Join-Path $BuildRoot "DeskTwin-PythonGUI.iss"

function Invoke-Checked {
    param([string]$File, [string[]]$Arguments)
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed: $File $($Arguments -join ' ')"
    }
}

if (-not (Get-Command python -ErrorAction SilentlyContinue)) {
    throw "Python is required on the build machine. Install Python and run this script again. End users do not need Python."
}

New-Item -ItemType Directory -Force -Path $BuildRoot, $OutputPath | Out-Null

if (-not (Test-Path (Join-Path $VenvPath "Scripts\python.exe"))) {
    Invoke-Checked "python" @("-m", "venv", $VenvPath)
}

$Py = Join-Path $VenvPath "Scripts\python.exe"
$Pip = Join-Path $VenvPath "Scripts\pip.exe"

Invoke-Checked $Py @("-m", "pip", "install", "--upgrade", "pip")
Invoke-Checked $Pip @("install", "-r", (Join-Path $ProjectRoot "Python\dependencies\requirements-python-gui.txt"))
Invoke-Checked $Pip @("install", "pyinstaller")

if (Test-Path $DistPath) {
    Remove-Item -LiteralPath $DistPath -Recurse -Force
}

$PyInstallerArgs = @(
    "--noconfirm",
    "--clean",
    "--windowed",
    "--onedir",
    "--name", "DeskTwin-PythonGUI",
    "--paths", $ProjectRoot,
    "--paths", (Join-Path $ProjectRoot "Python\service"),
    "--collect-all", "customtkinter",
    "--collect-all", "uvicorn",
    "--collect-all", "zeroconf",
    "--collect-all", "cryptography",
    # QR pairing is imported lazily by the dashboard; collect it explicitly
    # so it is present in the packaged desktop build as well as source runs.
    "--collect-all", "qrcode",
    "--collect-all", "PIL",
    "--collect-all", "imageio_ffmpeg",
    "--add-data", "config;config",
    "--add-data", "Python\ui\gui;gui",
    "--add-data", "Python\service\database\schema.sql;Python\service\database",
    "--hidden-import", "api.server",
    "--hidden-import", "auth.pairing",
    "--hidden-import", "sync.ws_manager",
    "--hidden-import", "storage.trash",
    "--hidden-import", "Python.core.desktwin_service",
    "--hidden-import", "Python.core.diagnostics",
    "Python\ui\gui.py"
)
Invoke-Checked $Py ( @("-m", "PyInstaller") + $PyInstallerArgs )

# Fail before creating a ZIP if files that previously caused runtime errors
# are missing from the collected package.
$RequiredPackagedFiles = @(
    (Join-Path $DistPath "DeskTwin-PythonGUI.exe"),
    (Join-Path $DistPath "_internal\Python\service\database\schema.sql")
)
$Missing = $RequiredPackagedFiles | Where-Object { -not (Test-Path $_) }
if ($Missing) {
    throw "Packaging validation failed. Missing: $($Missing -join ', ')"
}

$PortableZip = Join-Path $OutputPath "DeskTwin-PythonGUI-Portable.zip"
if (Test-Path $PortableZip) {
    Remove-Item -LiteralPath $PortableZip -Force
}
Compress-Archive -Path (Join-Path $DistPath "*") -DestinationPath $PortableZip -CompressionLevel Optimal

# If Inno Setup is installed, create the normal one-file installer too.
$IsccCandidates = @(
    (Join-Path ${env:ProgramFiles(x86)} "Inno Setup 6\ISCC.exe"),
    (Join-Path $env:ProgramFiles "Inno Setup 6\ISCC.exe")
)
$Iscc = $IsccCandidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1

if ($Iscc) {
    @"
[Setup]
AppName=DeskTwin Python GUI
AppVersion=1.0.0
DefaultDirName={autopf}\DeskTwin Python GUI
DefaultGroupName=DeskTwin Python GUI
OutputDir=$OutputPath
OutputBaseFilename=DeskTwin-PythonGUI-Setup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=lowest
DisableProgramGroupPage=yes

[Files]
Source: "$DistPath\*"; DestDir: "{app}"; Flags: recursesubdirs ignoreversion

[Icons]
Name: "{autodesktop}\DeskTwin Python GUI"; Filename: "{app}\DeskTwin-PythonGUI.exe"
Name: "{group}\DeskTwin Python GUI"; Filename: "{app}\DeskTwin-PythonGUI.exe"

[Run]
Filename: "{app}\DeskTwin-PythonGUI.exe"; Description: "Launch DeskTwin Python GUI"; Flags: nowait postinstall skipifsilent
"@ | Set-Content -LiteralPath $IssPath -Encoding UTF8

    Invoke-Checked $Iscc @($IssPath)
    Write-Host "Installer created: $OutputPath\DeskTwin-PythonGUI-Setup.exe" -ForegroundColor Green
} else {
    Write-Warning "Inno Setup is not installed; Portable output was created only."
    Write-Host "Portable package: $PortableZip" -ForegroundColor Yellow
}

Write-Host "Packaging complete. Output: $OutputPath" -ForegroundColor Green


