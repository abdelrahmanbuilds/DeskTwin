$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$AppRoot = Join-Path $ProjectRoot 'WinUI\app'
$ProjectFile = Join-Path $AppRoot 'DeskTwin.WinUI.csproj'

function Find-DeskTwinExecutable {
    $candidates = @(
        (Join-Path $AppRoot 'bin\x64\Debug\net8.0-windows10.0.19041.0\DeskTwin.WinUI.exe'),
        (Join-Path $AppRoot 'bin\Debug\net8.0-windows10.0.19041.0\DeskTwin.WinUI.exe'),
        (Join-Path $AppRoot 'bin\Release\net8.0-windows10.0.19041.0\DeskTwin.WinUI.exe')
    )
    $latest = $candidates |
        Where-Object { Test-Path -LiteralPath $_ } |
        ForEach-Object { Get-Item -LiteralPath $_ } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($latest) { return $latest.FullName }
    return $null
}

$Executable = Find-DeskTwinExecutable

$sourceFiles = Get-ChildItem -LiteralPath $AppRoot -File -Recurse |
    Where-Object {
        $_.Extension -in '.cs', '.xaml', '.csproj', '.manifest' -and
        $_.FullName -notmatch '\\(bin|obj)\\'
    }
$latestSource = $sourceFiles | Sort-Object LastWriteTime -Descending | Select-Object -First 1
$needsBuild = -not $Executable
if ($Executable -and $latestSource -and (Get-Item -LiteralPath $Executable).LastWriteTime -lt $latestSource.LastWriteTime) {
    $needsBuild = $true
}
if ($needsBuild) {
    Write-Host 'Building DeskTwin WinUI from the current source...'
    & dotnet build $ProjectFile --configuration Debug
    if ($LASTEXITCODE -ne 0) { throw "DeskTwin WinUI build failed with code $LASTEXITCODE." }
    $Executable = Find-DeskTwinExecutable
}
if (-not $Executable) {
    throw "DeskTwin WinUI executable was not produced. Build manually: dotnet build `"$ProjectFile`" --configuration Debug"
}

$env:DESKTWIN_ROOT = $ProjectRoot
Write-Host "Starting DeskTwin WinUI from: $Executable"
& $Executable @args
$exitCode = $LASTEXITCODE
if ($exitCode -ne 0) {
    Write-Host "DeskTwin WinUI exited with code $exitCode." -ForegroundColor Red
}
exit $exitCode
