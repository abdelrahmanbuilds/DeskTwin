$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$distRoot = Join-Path $repoRoot 'dist'
$packageRoot = Join-Path $distRoot 'DeskTwin-Windows'
$windowsRoot = Join-Path $packageRoot 'Windows'
$resolvedDist = [IO.Path]::GetFullPath($distRoot)
$resolvedPackage = [IO.Path]::GetFullPath($packageRoot)
if (-not $resolvedPackage.StartsWith($resolvedDist + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to write outside the distribution directory: $resolvedPackage"
}

if (Test-Path -LiteralPath $packageRoot) {
    Remove-Item -LiteralPath $packageRoot -Recurse -Force
}
New-Item -ItemType Directory -Path (Join-Path $windowsRoot 'WinUI\app') -Force | Out-Null

dotnet publish (Join-Path $repoRoot 'Windows\WinUI\app\DeskTwin.WinUI.csproj') --configuration Release --runtime win-x64 --self-contained false --output (Join-Path $windowsRoot 'WinUI\app')
if ($LASTEXITCODE -ne 0) { throw "dotnet publish failed with exit code $LASTEXITCODE" }

$pythonPackage = Join-Path $windowsRoot 'Python'
Copy-Item (Join-Path $repoRoot 'Windows\Python') $pythonPackage -Recurse
Copy-Item (Join-Path $repoRoot 'Windows\WinUI\bridge') (Join-Path $windowsRoot 'WinUI\bridge') -Recurse
Get-ChildItem -LiteralPath $windowsRoot -Directory -Filter '__pycache__' -Recurse |
    Remove-Item -Recurse -Force
Copy-Item (Join-Path $repoRoot 'Windows\README.md') (Join-Path $windowsRoot 'README.md')
Copy-Item (Join-Path $repoRoot 'Windows\Start-DeskTwin.bat') $packageRoot
Copy-Item (Join-Path $repoRoot 'Windows\Start-DeskTwin.ps1') $packageRoot

$zipPath = Join-Path $distRoot 'DeskTwin-Windows-win-x64.zip'
if (Test-Path -LiteralPath $zipPath) { Remove-Item -LiteralPath $zipPath -Force }
Compress-Archive -Path (Join-Path $packageRoot '*') -DestinationPath $zipPath -CompressionLevel Optimal
Write-Host "Created $zipPath"
