# Contributing to DeskTwin

Thanks for taking an interest in DeskTwin. Contributions can improve the app, documentation, accessibility, reliability, and the development workflow. DeskTwin is an early testing project, so focused bug reports and small, well-described pull requests are especially useful.

## Before you start

1. Read the [README](README.md) to choose the user, developer, or issue-reporting path.
2. Read the [architecture and source map](docs/ARCHITECTURE.md) to find the owning module.
3. Check [open issues](https://github.com/KernelWardenHQ/DeskTwin/issues) and pull requests for related work.
4. For a substantial or cross-platform change, open an issue first to discuss the scope and protocol impact.

## Local setup

Follow [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) for Android Studio, JDK/Android SDK, .NET, and Python setup. The release ZIP and APK are for trying the project; make source changes in this repository.

## A practical change workflow

1. Fork the repository (if you do not have write access) and clone your fork.
2. Create a focused branch from the current `main`, for example `fix/android-transfer-progress`.
3. Make the change in the module that owns it. For a protocol change, update Android and the Windows service together.
4. Build the affected platform using the commands in [Development](docs/DEVELOPMENT.md). If you cannot run a device or platform check, say so in the pull request.
5. Check the diff for generated files, local settings, app databases, diagnostics, logs, binaries, signing files, and credentials. Do not commit them.
6. Open a pull request against `main`. Describe the user-visible behavior, implementation area, checks performed, known limitations, and screenshots for UI changes.
7. GitHub Actions runs Android and Windows build workflows. Address failures or explain a reproducible environment limitation before requesting review.

## Reporting bugs and ideas

Use the [issue chooser](https://github.com/KernelWardenHQ/DeskTwin/issues/new/choose). Bug reports should include:

- Windows/Android platform, OS version, and DeskTwin preview version or source commit.
- Expected behavior and what actually happened.
- Small, numbered steps to reproduce the issue.
- Relevant sanitized error text or diagnostic excerpt, if available.
- Whether the issue happens every time and whether it started after an update.

Feature requests should explain the user problem and a concrete example of the desired workflow. Search existing issues first to avoid duplicates.

Remove pairing QR codes, access tokens, passwords, private keys, personal files, usernames, and public IP/network details from any screenshots or logs. Do not report a security vulnerability in a public issue; follow [Security and privacy](docs/SECURITY.md).

## Areas to improve

- Reproducible Windows and Android testing, especially on real devices and varied local networks.
- Accessibility, clear error recovery, and newcomer-friendly documentation.
- Protocol compatibility, transfer recovery, and offline edge cases.
- CI/release clarity, build diagnostics, and artifact verification.
- Translation and consistency between Android, WinUI, and service behavior.

Before broad refactors, explain the user problem and how you will preserve pairing, certificate pinning, and selected-folder/SAF boundaries.

## Current project and licensing status

There is no root application `LICENSE` yet. Until one is added, do not assume the application source is licensed for redistribution or reuse. Third-party fonts have separate notices in `Android/licenses/fonts/`. Please do not add assets or libraries without recording their license and source.

## Preview releases

Only maintainers create version tags. A `v*` tag invokes the release workflow and updates the latest testing preview with a Windows ZIP and Android debug APK. These are not store-ready builds. The Android workflow uses temporary debug signing, which may require users to uninstall an older preview before installing the new APK. See [Releasing](docs/RELEASING.md).
