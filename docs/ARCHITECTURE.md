# Architecture and source map

This guide explains the current source layout, runtime boundaries, and the usual place to make a change. It is a map of major modules and files, not a generated description of every line. Read it with the linked source when making code-level changes; implementation details can change faster than this document.

## System shape

```text
Android app (Kotlin / Compose)
  ├── HTTPS API requests ───────────┐
  └── WebSocket connections ────────┤ local network; TLS identity pinned at pairing
                                    ▼
Windows WinUI 3 desktop ◄── JSON Lines over stdin/stdout ── Python bridge host
                                                              │
                                                              ▼
                                                     Python local service
```

The Windows desktop starts the Python bridge as a child process. The bridge forwards desktop commands to the Python service using JSON Lines over standard input/output. The service exposes local HTTPS and WebSocket endpoints for Android. It is a local-network design, not a cloud relay. Pairing establishes device trust and supplies the TLS certificate fingerprint that the Android client pins.

File access is scoped by user choices: Windows exposes only configured shared folders; Android grants access to selected phone folders through the Storage Access Framework (SAF). Preserve those boundaries when extending file operations.

## Repository roots

| Directory or file | Role |
| --- | --- |
| `Android/` | Android Studio/Gradle project configuration, app module, wrapper, and checked-in resources. |
| `Android/src/main/java/com/desktwin/app/` | Kotlin application source; deliberately outside the app module directory and explicitly configured in `Android/app/build.gradle.kts`. |
| `Windows/WinUI/` | Current Windows desktop shell and its bridge host. |
| `Windows/Python/` | Python service, Python dependencies, diagnostics, and a legacy Python GUI. |
| `Windows/scripts/Build-Release.ps1` | Self-contained Windows testing package builder. |
| `.github/workflows/ci.yml` | Builds Android debug and Windows package outputs on pushes and pull requests. |
| `.github/workflows/release.yml` | Builds both test artifacts and attaches them to a release when a `v*` tag is pushed. |
| `docs/` | User, developer, release, architecture, and security documentation. |

## Android source map

All source paths below are relative to `Android/src/main/java/com/desktwin/app/` unless shown otherwise.

| Path | Responsibility | Typical change |
| --- | --- | --- |
| `MainActivity.kt` | Android app entry point and top-level Compose setup. | Startup, top-level navigation setup, app initialization. |
| `data/` | Local app state, preferences, profiles, cache, secure token handling, file tree mirror, and queued operations. | Persistence, offline queue, saved state, local cache behavior. |
| `diagnostics/DeskTwinDiagnostics.kt` | Bounded local diagnostics trail and export support. | Add safe, useful lifecycle/error events; do not log secrets or file contents. |
| `discovery/DeskTwinDiscovery.kt` | Finds DeskTwin Windows services on the local network. | Discovery behavior and candidate handling. |
| `network/` | API, connection state, file/phone-file operations, transfer progress, resumable bodies, and error presentation. | Protocol calls, TLS/pinning, connection lifecycle, upload/download handling. |
| `notifications/` | Transfer and chat notifications. | Notification content or notification lifecycle. |
| `ui/screens/` | Compose screens for pairing, browsing, transfers, chat, settings, and other app features. | Screen-level interaction and presentation. |
| `ui/theme/` | Shared design system, theme presets, typography, and components. | Colors, fonts, shared visual components, theme behavior. |
| `ui/theme/DeskTwinCopy.kt` | Compose language context and the small bilingual `tr(ar, en)` helper. | Add or adjust Android screen copy in both languages. |
| `ui/icons/` | File-type and explorer icon mapping. | Icons and file-kind presentation. |
| `Android/app/build.gradle.kts` | Android plugin configuration, SDK levels, dependencies, source roots, and app version overrides. | Android library/plugin or packaging changes. |
| `Android/src/main/AndroidManifest.xml` | Android app components and declared capabilities. | App-level manifest behavior; review permission and exported-component impact. |
| `Android/app/src/main/res/` | Launcher/theme images, fonts, XML resources. | Visual assets and resource definitions. |
| `Android/licenses/fonts/` | Third-party font license notices. | Keep notices aligned when fonts change. |

The module dependencies are declared in `Android/app/build.gradle.kts`; Gradle and Android Gradle Plugin versions are in `Android/gradle/wrapper/gradle-wrapper.properties` and `Android/build.gradle.kts`. Keep protocol changes compatible with the Windows service or update both clients and service in the same contribution.

## Windows source map

### WinUI desktop

Paths are relative to `Windows/WinUI/`.

| Path | Responsibility | Typical change |
| --- | --- | --- |
| `app/App.xaml` and `app/App.xaml.cs` | WinUI application initialization and lifetime. | Startup, shutdown, app-wide resources. |
| `app/MainWindow.xaml` and `app/MainWindow.xaml.cs` | Main window layout and navigation shell. | Window chrome, navigation destinations, top-level layout. |
| `app/Pages/` | Feature pages: chat, integration health, phone assistant/files, settings, and transfers. | Page-specific UI and interactions. |
| `app/MainWindow.xaml.cs` (`WinUIStrings`) | Shared WinUI language state, English translations, and RTL/LTR direction updates. | Add corresponding English text when adding Arabic UI copy. |
| `app/DesktopBridge.cs` | Starts and communicates with the Python host over JSON Lines. | Desktop-to-service commands, process lifetime, bridge serialization. |
| `app/PhoneVideoStreamProxy.cs` | Desktop-side proxy for phone video streaming scenarios. | Streaming proxy behavior and lifecycle. |
| `app/TaskbarTrayIcon.cs` | Taskbar notification-area integration. | Tray icon and menu behavior. |
| `app/DeskTwin.WinUI.csproj` | .NET target, Windows App SDK, and build properties. | NuGet/runtime/build configuration. |
| `bridge/winui_host.py` | Child-process host joining the WinUI bridge to the Python service. | Command dispatch or host lifecycle; keep stdout protocol clean. |
| `launchers/` | Source launch and build helpers. | Developer-only launching and local packaging. |

The WinUI client uses .NET 8 and Windows App SDK. The Windows preview build packages these runtimes so the end user does not install them separately.

### Python service

Paths are relative to `Windows/Python/`.

| Path | Responsibility |
| --- | --- |
| `launchers/main.py` | Python service/application entry path used by the host and development tooling. |
| `core/desktwin_service.py` | Service composition and lifecycle. |
| `core/diagnostics.py` | Structured, rotating diagnostics and startup preflight checks. |
| `service/api/server.py` | Local HTTP(S) API routes and WebSocket endpoints. |
| `service/auth/pairing.py` | Pairing flow, trust establishment, and authorization checks. |
| `service/security/` | TLS certificate behavior and security monitoring. |
| `service/config/` | Runtime settings and configuration loading. |
| `service/database/` | Local database access and schema. |
| `service/discovery/` | Local-network service discovery. |
| `service/scanner/` | File/folder scanning and special-folder handling. |
| `service/storage/` | Scoped file operations and trash behavior. |
| `service/transfer/` | HTTP range and resumable transfer logic. |
| `service/sync/` | WebSocket management, phone filesystem integration, and manual messages. |
| `service/watcher/` | Filesystem change observation. |
| `service/notifications/` | Service-side notification handling. |
| `dependencies/requirements-core.txt` | Core service runtime dependencies. |
| `dependencies/requirements-winui.txt` | Python dependencies used by the WinUI-hosted service; includes core dependencies. |
| `dependencies/requirements-python-gui.txt` | Additional dependencies for the legacy Python GUI. |
| `dependencies/requirements-release.txt` | Packaging/build dependencies for the Windows release builder. |
| `ui/` | Legacy Python/CustomTkinter GUI, separate from the current WinUI shell. |
| `packaging/` | PyInstaller spec and build script for the legacy Python GUI. |

The Windows release builder is `Windows/scripts/Build-Release.ps1`. It stages the frozen Python service, self-contained .NET/WinUI runtime, required app-local Visual C++ runtime files, and launcher into a ZIP. Packaging changes belong in that script and the workflow that invokes it; the actual source app belongs in `Windows/WinUI/` and `Windows/Python/`.

## Dependency and build ownership

| Platform | Dependency source | Local developer build | CI build |
| --- | --- | --- | --- |
| Android | Gradle files in `Android/` | `cd Android; .\gradlew.bat :app:assembleDebug` | Same task in `.github/workflows/ci.yml` and the release workflow. |
| Windows UI | `Windows/WinUI/app/DeskTwin.WinUI.csproj` | `dotnet run --project Windows/WinUI/app/DeskTwin.WinUI.csproj` from repo root after Python setup. | `Windows/scripts/Build-Release.ps1`. |
| Windows Python service | `Windows/Python/dependencies/requirements-winui.txt` and included core requirements | Install requirements in a virtual environment and run through the WinUI bridge or documented development entry. | Frozen into the Windows ZIP by the release build script. |

The user-facing artifacts and development source have different purposes. Users should download the Windows ZIP/APK from Releases. Developers should modify source, restore development dependencies, and use the CI workflow to produce preview artifacts.

## Safe extension checklist

1. Find the owning module in the maps above and check its page-level README before changing behavior.
2. For API or WebSocket changes, update the Android client and Windows service together; note any protocol compatibility impact.
3. Preserve explicit pairing, TLS certificate pinning, Windows folder selection, and Android SAF grants.
4. Add diagnostics that help identify the stage and error while omitting file contents, tokens, QR payloads, and credentials.
5. Keep runtime data, logs, local settings, signing material, and build output out of commits.
6. Build the changed platform, describe what was verified, and open a pull request. See [Development](DEVELOPMENT.md) and [Contributing](../CONTRIBUTING.md).

## Diagnostics locations

- **Windows:** the current WinUI Settings page has a diagnostics action. The service writes `data/logs/desktwin-diagnostics.jsonl` below the application root; logs rotate and older copies may be retained.
- **Android:** the diagnostics component writes to app-private storage and keeps a bounded in-memory snapshot. An exported log is placed in app cache when the app exposes its export action. These files can contain operational details; review and redact before sharing.

For user reporting steps, see the root README's [Finding diagnostics](../README.md#finding-diagnostics) section. For sensitive vulnerabilities, use the private reporting route described in [Security](SECURITY.md), not a public issue.
