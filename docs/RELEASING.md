# Testing previews

## Windows package

The tag workflow publishes a Windows ZIP and an Android debug APK as the latest GitHub release. GitHub's “latest” marker makes the direct download links in the root README follow the newest preview. The release title and description label both files as testing previews; they are not production releases, and the APK is not a Google Play release.

The Windows ZIP includes `Start-DeskTwin.bat`. After extracting it, users double-click that file. The launcher checks for Python 3.11 and the .NET 8 Desktop Runtime, creates a local Python environment, installs the pinned dependencies, and starts the app. First launch needs internet access to fetch the Python packages. The Windows guide explains first-run requirements and troubleshooting.

For a local package build, run Windows/scripts/Build-Release.ps1 from the repository root. The output is written under dist/.

## Android testing APK

The workflow builds the debug variant, signs it with the temporary debug key generated on the runner, and attaches it as DeskTwin-Android-debug.apk. Debug APKs are not intended for store distribution.

The debug signing key is generated on the GitHub Actions runner and is not reused on later runs. Android may reject a new preview as an update to the previously installed preview; uninstall the old preview before installing the new one. Uninstalling removes the app's local data. If compatible in-place updates are needed for a testing channel, configure and protect a stable test signing key before distributing the next preview.

The workflow assigns versionName from the tag and uses the GitHub Actions run number for versionCode. For local development, the app defaults to versionName 1.0 and versionCode 1.

## Versioning

Use a vMAJOR.MINOR.PATCH tag after the corresponding changes have landed on main, for example v0.1.1. GitHub Actions creates a clearly marked testing preview with the matching Windows and Android artifacts. The release workflow updates the `latest` download links automatically.
