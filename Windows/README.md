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

From the repository root, `Windows/scripts/Build-Release.ps1` creates a self-contained Windows ZIP under `dist/`. The build machine needs Python 3.11, the .NET 8 SDK, and Visual Studio Build Tools with the x64 Visual C++ runtime files. These are build-time requirements only; the ZIP bundles the Python host and packages, .NET runtime, WinUI runtime, and Visual C++ runtime for users. See `docs/RELEASING.md` for GitHub releases.

## Run a downloaded testing preview

1. Extract `DeskTwin-Windows-win-x64.zip`.
2. Double-click `Start-DeskTwin.bat`.

The application starts directly from the extracted folder. The testing ZIP does not require users to install Python, .NET Desktop Runtime, or Python packages, and first launch does not download runtime libraries. The app pairs and transfers files over the local network and does not need a cloud account.

## Troubleshooting

- Keep the extracted folder intact; the EXE uses the bundled runtime files next to it.
- If Windows SmartScreen shows an unfamiliar-app notice, verify that the ZIP came from the DeskTwin GitHub testing release before choosing whether to run it.
- If the app opens but pairing fails, put both devices on the same trusted Wi-Fi network and check that Windows Firewall allows DeskTwin on that network. Guest Wi-Fi, VPNs, or Wi-Fi client isolation can block local connections.
- The phone can browse only Windows folders selected for sharing in the app.

## Dependencies

- Development: .NET 8 SDK, Python 3.11+, and packages in `Python/dependencies/`
- Testing ZIP: bundled .NET, WinUI, Python, and application dependencies; no user-side runtime installation
- Python package versions are pinned in the requirements files

## Data and permissions

Windows shares only the folders that the user selects. Local preferences, share configuration, databases, diagnostics, and generated data are created on the local computer and are not included in the repository. The app uses a TLS-protected local API and the Android app pins the certificate fingerprint from the pairing QR code.
