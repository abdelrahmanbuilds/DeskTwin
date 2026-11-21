# DeskTwin for Windows

The Windows application combines a WinUI 3 desktop interface with a local Python service. The UI launches a Python bridge as a child process; the bridge controls the service over local stdin/stdout JSON Lines. Android connects to the service on the local network over HTTPS and WebSockets.

## Run from source

Requirements: Windows 10 version 2004 (build 19041) or later, .NET 8 SDK, Python 3.11 or later, and a trusted local network.

From the repository root:

    cd Windows
    py -3 -m venv .venv
    .venv/Scripts/Activate.ps1
    python -m pip install -r Python/dependencies/requirements-winui.txt
    dotnet run --project WinUI/app/DeskTwin.WinUI.csproj

Create a new pairing QR code in the Windows app and scan it with DeskTwin for Android. Both devices need to be on the same network. Select the Windows folders you want to share in the app.

## Build and publish

    dotnet build WinUI/app/DeskTwin.WinUI.csproj --configuration Release

From the repository root, Windows/scripts/Build-Release.ps1 creates a portable ZIP under dist/. The release still requires Python and its listed dependencies on the target PC. See docs/RELEASING.md for GitHub releases.

## Run a downloaded release package

Extract the ZIP and open PowerShell in its Windows directory. Install Python dependencies once, then launch the app:

    py -3.11 -m venv .venv
    .venv/Scripts/python.exe -m pip install -r Python/dependencies/requirements-winui.txt
    $env:DESKTWIN_PYTHON = (Resolve-Path .venv/Scripts/python.exe).Path
    WinUI/app/DeskTwin.WinUI.exe

The .NET 8 Desktop Runtime is required. The packaged WinUI executable expects the Python virtual environment to be selected through DESKTWIN_PYTHON as shown above.

## Dependencies

- WinUI app: .NET 8 and Microsoft.WindowsAppSDK 2.5.1, restored through NuGet
- Local service and bridge: Python requirements in Python/dependencies/
- Python package versions are pinned by the requirements files

## Data and permissions

Windows shares only the folders that the user selects. Local preferences, share configuration, databases, diagnostics, and generated data are created on the local computer and are not included in the repository. The app uses a TLS-protected local API and the Android app pins the certificate fingerprint from the pairing QR code.
