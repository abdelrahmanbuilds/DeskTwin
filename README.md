# DeskTwin

DeskTwin is an Android and Windows companion project for working with files and selected PC features over a trusted local network. The Windows PC hosts the service; the Android app pairs with it and connects directly on the LAN.

> **Testing preview:** DeskTwin is an early project for hands-on testing and feedback. The Android download is a debug APK. Neither download is a production or Google Play release.

## Choose what you want to do

| I want to… | Start here |
| --- | --- |
| **Try DeskTwin** | Download the ready-to-run Windows ZIP or Android testing APK below. |
| **Understand the project** | Read [what DeskTwin does](#what-desktwin-does), the [architecture map](docs/ARCHITECTURE.md), and [security and privacy](docs/SECURITY.md). |
| **Develop or modify it** | Follow the [developer setup](docs/DEVELOPMENT.md), [architecture map](docs/ARCHITECTURE.md), and [contribution guide](CONTRIBUTING.md). |
| **Report a problem** | Open [Issues](https://github.com/abdelrahmanbuilds/DeskTwin/issues/new/choose) and choose **Bug report**. Include the version, steps, and sanitized diagnostics. |
| **Suggest an improvement** | Open [Issues](https://github.com/abdelrahmanbuilds/DeskTwin/issues/new/choose) and choose **Feature request**. |
| **See how builds and previews are made** | Browse [Build workflow runs](https://github.com/abdelrahmanbuilds/DeskTwin/actions/workflows/ci.yml), [Testing preview runs](https://github.com/abdelrahmanbuilds/DeskTwin/actions/workflows/release.yml), and the [workflow source](.github/workflows/). |

## Download DeskTwin

These direct links always point to the files attached to GitHub's latest testing preview. The release page also shows its version, notes, and both files before you download.

| Windows (64-bit) | Android |
| --- | --- |
| [**Download the Windows ZIP**](https://github.com/abdelrahmanbuilds/DeskTwin/releases/latest/download/DeskTwin-Windows-win-x64.zip) | [**Download the Android debug APK**](https://github.com/abdelrahmanbuilds/DeskTwin/releases/latest/download/DeskTwin-Android-debug.apk) |
| Extract the ZIP, keep its contents together, then double-click `Start-DeskTwin.bat`. The package includes its application runtimes and libraries; installing Python or .NET is not required. | Install on Android 8.0 (API 26) or later. Android may ask you to allow installation from the browser or file manager you used. |

[Open the latest preview page](https://github.com/abdelrahmanbuilds/DeskTwin/releases/latest) · [Browse all preview versions](https://github.com/abdelrahmanbuilds/DeskTwin/releases)

Each platform has one download; language is chosen inside the app, not through separate English and Arabic files. English is the default on a fresh install, and Arabic is available from **Settings → Language**. DeskTwin remembers a language the user has already selected. File names, folder names, paths, and user-entered text stay exactly as stored and are never translated.

### First connection

1. Put the phone and PC on the same trusted local network.
2. Start DeskTwin on Windows and create a pairing QR code.
3. Open DeskTwin on Android and scan the QR code.
4. Choose the PC folders you want to make available.

Guest Wi-Fi, VPN settings, Windows Firewall rules, or access-point isolation can block device-to-device connections. DeskTwin does not need a cloud account for pairing or local file transfers. See [Windows troubleshooting](Windows/README.md#troubleshooting) if the connection fails.

## What DeskTwin does

DeskTwin has two cooperating applications:

- **Windows desktop:** a WinUI 3 interface plus a local Python service. The service exposes the local API, pairing, selected-folder access, transfer, and device features.
- **Android app:** a Kotlin and Jetpack Compose client. It pairs with the Windows service and provides the phone-side screens and operations.

The apps communicate over the local network using HTTPS and WebSockets. Pairing presents a short-lived QR code and pins the Windows TLS certificate fingerprint on Android. The phone can access only the PC folders selected for sharing. Windows can access phone files only after the Android user selects them through Android's Storage Access Framework; that is not unrestricted phone-storage access. See [Security and privacy](docs/SECURITY.md) for the boundaries and caveats.

## For developers

The downloaded ZIP and APK are testing builds for people trying the app. To change code, start from this repository's source instead:

- **Android:** open [`Android/`](Android/) in Android Studio. The Kotlin source is in [`Android/src/main/java/com/desktwin/app/`](Android/src/main/java/com/desktwin/app/); Gradle restores the declared dependencies.
- **Windows:** use [`Windows/`](Windows/) source. The WinUI client starts the Python service bridge. Source development needs the .NET 8 SDK and Python 3.11 or later, with packages installed from the requirements files.
- **Build locally:** follow the step-by-step [Development guide](docs/DEVELOPMENT.md).
- **Understand module ownership and data flow:** read the [Architecture and source map](docs/ARCHITECTURE.md).
- **Suggest or contribute a change:** see [CONTRIBUTING.md](CONTRIBUTING.md). A pull request runs the [Build workflow](.github/workflows/ci.yml), which builds Android debug and Windows preview outputs.

## Report a problem or propose an idea

Use [GitHub Issues](https://github.com/abdelrahmanbuilds/DeskTwin/issues/new/choose) for reproducible bugs and feature ideas. The issue form asks for the affected platform and version so reports arrive with useful context. Before opening one, check [existing issues](https://github.com/abdelrahmanbuilds/DeskTwin/issues) and the [troubleshooting guide](Windows/README.md#troubleshooting).

For a bug report, include what you expected, what happened, and the shortest steps that reproduce it. Attach screenshots or diagnostic excerpts only after checking them for personal information. Never post pairing QR codes, access tokens, passwords, private keys, personal files, or unredacted network addresses. Report security vulnerabilities privately to the repository owner; see [Security and privacy](docs/SECURITY.md).

### Finding diagnostics

- **Windows preview:** in DeskTwin, open **Settings → Diagnostics → Open diagnostics folder**. The current service log is `data/logs/desktwin-diagnostics.jsonl` under the extracted application folder. Keep the ZIP's other files beside the launcher.
- **Android:** open **Settings → Diagnostics log → Share log**. The app shares a snapshot from its private diagnostics log, named `desktwin-diagnostics.log`; the underlying private file is not available through an ordinary file browser. Review the shared text before posting it.
- **Build failure:** open the failed [GitHub Actions run](https://github.com/abdelrahmanbuilds/DeskTwin/actions) and link to the failing job or step. Do not copy secrets from logs.

## Build, debug, and testing preview lifecycle

| GitHub page | What it contains |
| --- | --- |
| [Actions → Build](https://github.com/abdelrahmanbuilds/DeskTwin/actions/workflows/ci.yml) | Android debug build and self-contained Windows package build for pushes and pull requests. |
| [Actions → Testing preview](https://github.com/abdelrahmanbuilds/DeskTwin/actions/workflows/release.yml) | Windows ZIP and Android debug APK build, then upload as assets when a `v*` tag is pushed. |
| [Releases](https://github.com/abdelrahmanbuilds/DeskTwin/releases) | Version notes and the ready-to-download Windows ZIP and Android APK. |
| [Build workflow source](.github/workflows/ci.yml) · [Preview workflow source](.github/workflows/release.yml) | Exact automation definitions, including build commands and artifact names. |

The Windows ZIP is intended to start from its extracted folder without a user-installed Python or .NET runtime. The Android APK is signed with a temporary debug key on the build runner. A later APK can have a different signing identity, so Android may require uninstalling an earlier preview before installing it; uninstalling removes that app's data. These artifacts are for testing and feedback, not a store publishing channel.

## Repository map

| Path | Purpose |
| --- | --- |
| [`Android/`](Android/) | Android Gradle project, build configuration, wrapper, and app resources. |
| [`Android/src/main/java/com/desktwin/app/`](Android/src/main/java/com/desktwin/app/) | Android Kotlin app source. The Gradle app module points here explicitly. |
| [`Windows/WinUI/app/`](Windows/WinUI/app/) | WinUI 3 desktop shell, pages, and bridge client. |
| [`Windows/WinUI/bridge/`](Windows/WinUI/bridge/) | Python host started by the WinUI desktop process. |
| [`Windows/Python/service/`](Windows/Python/service/) | Local API, pairing/security, selected-folder storage, transfer, discovery, and sync modules. |
| [`Windows/Python/ui/`](Windows/Python/ui/) | Legacy Python GUI kept in source; it is separate from the WinUI interface used by the Windows preview package. |
| [`Windows/scripts/Build-Release.ps1`](Windows/scripts/Build-Release.ps1) | Builds and stages the self-contained Windows testing ZIP. |
| [`.github/workflows/`](.github/workflows/) | Continuous build and tag-driven preview automation. |
| [`docs/`](docs/) | Architecture, development, release, and security guides. |

The source tree is intentionally not a line-by-line tutorial. The [Architecture and source map](docs/ARCHITECTURE.md) describes the job and extension point of each major module; start there before editing across platforms.

## Project status and reuse

DeskTwin is an evolving testing project. Device behavior may vary with Windows networking/firewall settings, Android versions, and media support. No root application license has been selected yet. Until a root `LICENSE` is added, do not assume the source is licensed for reuse, redistribution, or modification. Third-party font notices are in [`Android/licenses/fonts/`](Android/licenses/fonts/).
