$ErrorActionPreference = 'Stop'

$WinUiRoot = Split-Path -Parent $PSScriptRoot
$ProjectFile = Join-Path $WinUiRoot 'app\DeskTwin.WinUI.csproj'
$AppBin = Join-Path $WinUiRoot 'app\bin'
$OutputDirectory = Join-Path $AppBin 'x64\Debug\net8.0-windows10.0.19041.0'
$OutputFile = Join-Path $OutputDirectory 'DeskTwin.WinUI.exe'
$LegacyOutputDirectory = Join-Path $AppBin 'win-x64'

if (-not (Get-Command dotnet -ErrorAction SilentlyContinue)) {
    throw 'dotnet was not found. Install the .NET 8 SDK and run this script again.'
}
if (-not (Test-Path -LiteralPath $ProjectFile)) {
    throw "Project file was not found: $ProjectFile"
}

# Keep one canonical x64 Debug output. Remove the former standalone publish
# directory only after a successful build, so a failed build remains recoverable.

Set-Location -LiteralPath (Split-Path -Parent $ProjectFile)
Write-Host 'Building DeskTwin WinUI for Windows 10 x64...' -ForegroundColor Cyan
Write-Host "Project: $ProjectFile"
Write-Host ''

& dotnet build $ProjectFile -c Debug -p:Platform=x64
if ($LASTEXITCODE -ne 0) {
    throw "Build failed. Exit code: $LASTEXITCODE"
}

if (-not (Test-Path -LiteralPath $OutputFile)) {
    throw "Build finished but the EXE was not found: $OutputFile"
}

if (Test-Path -LiteralPath $LegacyOutputDirectory) {
    $resolvedLegacyOutput = (Resolve-Path -LiteralPath $LegacyOutputDirectory).Path
    $resolvedAppBin = (Resolve-Path -LiteralPath $AppBin).Path
    if (-not $resolvedLegacyOutput.StartsWith($resolvedAppBin, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove output outside the WinUI bin folder: $resolvedLegacyOutput"
    }
    Remove-Item -LiteralPath $resolvedLegacyOutput -Recurse -Force
    Write-Host "Removed obsolete output: $resolvedLegacyOutput" -ForegroundColor DarkGray
}

Write-Host ''
Write-Host 'Build completed successfully.' -ForegroundColor Green
Write-Host "EXE: $OutputFile" -ForegroundColor Green
Write-Host ''
Write-Host 'Press any key to close...' -ForegroundColor DarkGray
$null = $Host.UI.RawUI.ReadKey('NoEcho,IncludeKeyDown')
