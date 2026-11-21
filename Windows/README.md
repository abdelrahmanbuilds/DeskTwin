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

From the repository root, Windows/scripts/Build-Release.ps1 creates a ZIP under dist/. The target PC needs Python 3.11 and the .NET 8 Desktop Runtime; the included launcher prepares the Python dependencies on first run. See docs/RELEASING.md for GitHub releases.

## Run a downloaded testing preview

1. Extract `DeskTwin-Windows-win-x64.zip`.
2. Double-click `Start-DeskTwin.bat`.

The launcher checks for Python 3.11 and the .NET 8 Desktop Runtime, then prepares the Python environment and starts DeskTwin. The first launch downloads the pinned Python packages and can take a few minutes; later launches check for package updates. If a requirement is missing, the launcher prints a setup link. The app itself pairs and transfers files over the local network and does not need a cloud account.

## Troubleshooting

- If the launcher says Python is missing, install Python 3.11 for Windows, enable the Python launcher during setup, then double-click the batch file again.
- If the launcher says the .NET runtime is missing, install the .NET 8 Desktop Runtime and retry.
- Python package setup needs internet access. If it fails, check the connection and launch again.
- If the app opens but pairing fails, put both devices on the same trusted Wi-Fi network and check that Windows Firewall allows DeskTwin on that network. Guest Wi-Fi, VPNs, or Wi-Fi client isolation can block local connections.
- The phone can browse only Windows folders selected for sharing in the app.

## Dependencies

- WinUI app: .NET 8 and Microsoft.WindowsAppSDK 2.5.1, restored through NuGet
- Local service and bridge: Python requirements in Python/dependencies/
- Python package versions are pinned by the requirements files

## Data and permissions

Windows shares only the folders that the user selects. Local preferences, share configuration, databases, diagnostics, and generated data are created on the local computer and are not included in the repository. The app uses a TLS-protected local API and the Android app pins the certificate fingerprint from the pairing QR code.
