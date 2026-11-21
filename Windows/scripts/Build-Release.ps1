$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$distRoot = Join-Path $repoRoot 'dist'
$packageRoot = Join-Path $distRoot 'DeskTwin-Windows'
$windowsRoot = Join-Path $packageRoot 'Windows'
$buildRoot = Join-Path $repoRoot 'Windows\.build\release'
$buildVenv = Join-Path $buildRoot 'venv'
$buildPython = Join-Path $buildVenv 'Scripts\python.exe'
$bridgeSource = Join-Path $repoRoot 'Windows\WinUI\bridge\winui_host.py'
$bridgeOutput = Join-Path $windowsRoot 'WinUI\bridge'
$winUiOutput = Join-Path $windowsRoot 'WinUI\app'

$resolvedDist = [IO.Path]::GetFullPath($distRoot)
$resolvedPackage = [IO.Path]::GetFullPath($packageRoot)
if (-not $resolvedPackage.StartsWith($resolvedDist + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to write outside the distribution directory: $resolvedPackage"
}

function Invoke-Checked {
    param([string]$File, [string[]]$Arguments)
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed: $File $($Arguments -join ' ')"
    }
}

if (-not (Get-Command dotnet.exe -ErrorAction SilentlyContinue)) {
    throw 'The .NET 8 SDK is required on the build machine.'
}
if (-not (Test-Path -LiteralPath $buildPython)) {
    New-Item -ItemType Directory -Path $buildRoot -Force | Out-Null
    $pythonCommand = Get-Command python.exe -ErrorAction SilentlyContinue
    $pythonVersion = if ($pythonCommand) { & $pythonCommand.Source -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}")' }
    if ($pythonVersion -eq '3.11') {
        Invoke-Checked $pythonCommand.Source @('-m', 'venv', $buildVenv)
    }
    else {
        $pythonLauncher = Get-Command py.exe -ErrorAction SilentlyContinue
        if (-not $pythonLauncher) { throw 'Python 3.11 is required on the build machine.' }
        Invoke-Checked $pythonLauncher.Source @('-3.11', '-m', 'venv', $buildVenv)
    }
}

Invoke-Checked $buildPython @('-m', 'pip', 'install', '--disable-pip-version-check', '-r', (Join-Path $repoRoot 'Windows\Python\dependencies\requirements-release.txt'))

if (Test-Path -LiteralPath $packageRoot) {
    Remove-Item -LiteralPath $packageRoot -Recurse -Force
}
New-Item -ItemType Directory -Path $winUiOutput, $bridgeOutput -Force | Out-Null

dotnet publish (Join-Path $repoRoot 'Windows\WinUI\app\DeskTwin.WinUI.csproj') `
    --configuration Release `
    --runtime win-x64 `
    --self-contained true `
    -p:WindowsAppSDKSelfContained=true `
    --output $winUiOutput
if ($LASTEXITCODE -ne 0) { throw "Self-contained WinUI publish failed with exit code $LASTEXITCODE" }

$serviceRoot = Join-Path $repoRoot 'Windows\Python\service'
$pythonDistRoot = Join-Path $buildRoot 'pyinstaller-dist'
$pythonWorkRoot = Join-Path $buildRoot 'pyinstaller-work'
foreach ($generatedRoot in @($pythonDistRoot, $pythonWorkRoot)) {
    if (Test-Path -LiteralPath $generatedRoot) {
        Remove-Item -LiteralPath $generatedRoot -Recurse -Force
    }
}
$pythonArguments = @(
    '-m', 'PyInstaller',
    '--noconfirm', '--clean', '--onedir', '--console',
    '--name', 'DeskTwinPythonHost',
    '--distpath', $pythonDistRoot,
    '--workpath', $pythonWorkRoot,
    '--specpath', $buildRoot,
    '--paths', (Join-Path $repoRoot 'Windows'),
    '--paths', $serviceRoot,
    '--add-data', "$(Join-Path $serviceRoot 'database\schema.sql');database",
    '--collect-all', 'cryptography',
    '--collect-all', 'imageio_ffmpeg',
    '--collect-all', 'PIL',
    '--collect-all', 'qrcode',
    '--collect-all', 'uvicorn',
    '--collect-all', 'windows_toasts',
    '--collect-all', 'zeroconf',
    '--hidden-import', 'api.server',
    '--hidden-import', 'config.app_settings',
    '--hidden-import', 'Python.core.diagnostics',
    $bridgeSource
)
Invoke-Checked $buildPython $pythonArguments

$frozenHost = Join-Path $pythonDistRoot 'DeskTwinPythonHost'
$bundledHostOutput = Join-Path $bridgeOutput 'DeskTwinPythonHost'
New-Item -ItemType Directory -Path $bundledHostOutput -Force | Out-Null
Copy-Item -Path (Join-Path $frozenHost '*') -Destination $bundledHostOutput -Recurse
Copy-Item -LiteralPath (Join-Path $repoRoot 'Windows\README.md') (Join-Path $windowsRoot 'README.md')
Copy-Item -LiteralPath (Join-Path $repoRoot 'Windows\Start-DeskTwin.bat') $packageRoot

# WinUI is an unpackaged desktop app; carry the x64 VC runtime beside its EXE.
$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
if (-not (Test-Path -LiteralPath $vswhere)) { throw 'Visual Studio Build Tools are required to stage the x64 VC runtime.' }
$vsInstall = (& $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Redist.14.Latest -property installationPath | Select-Object -First 1)
if (-not $vsInstall) { throw 'The Visual C++ Redistributable files were not found in Visual Studio Build Tools.' }
$redistBase = Join-Path $vsInstall 'VC\Redist\MSVC'
$crtDirectories = Get-ChildItem -Path (Join-Path $redistBase '*\x64\Microsoft.VC143.CRT') -Directory -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending
$crtDirectory = $crtDirectories | Select-Object -First 1
if (-not $crtDirectory) { throw "The x64 VC runtime folder was not found under $redistBase." }
Copy-Item -Path (Join-Path $crtDirectory.FullName '*.dll') -Destination $winUiOutput -Force

$requiredFiles = @(
    (Join-Path $winUiOutput 'DeskTwin.WinUI.exe'),
    (Join-Path $winUiOutput 'coreclr.dll'),
    (Join-Path $winUiOutput 'Microsoft.UI.Xaml.dll'),
    (Join-Path $winUiOutput 'vcruntime140.dll'),
    (Join-Path $bridgeOutput 'DeskTwinPythonHost\DeskTwinPythonHost.exe'),
    (Join-Path $bridgeOutput 'DeskTwinPythonHost\_internal\database\schema.sql'),
    (Join-Path $packageRoot 'Start-DeskTwin.bat')
)
$missingFiles = $requiredFiles | Where-Object { -not (Test-Path -LiteralPath $_) }
if ($missingFiles) { throw "Self-contained package is incomplete. Missing: $($missingFiles -join ', ')" }

$zipPath = Join-Path $distRoot 'DeskTwin-Windows-win-x64.zip'
if (Test-Path -LiteralPath $zipPath) { Remove-Item -LiteralPath $zipPath -Force }
Compress-Archive -Path (Join-Path $packageRoot '*') -DestinationPath $zipPath -CompressionLevel Optimal
Write-Host "Created self-contained Windows package: $zipPath"
