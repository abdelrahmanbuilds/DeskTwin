# Testing previews

## Windows package

The tag workflow publishes a portable Windows ZIP and an Android debug APK as a GitHub prerelease. These builds are for people to try DeskTwin and report issues; they are not Google Play or production releases.

For a local package build, run Windows/scripts/Build-Release.ps1 from the repository root. The output is written under dist/.

## Android testing APK

The workflow builds the debug variant, signs it with the temporary debug key generated on the runner, and attaches it as DeskTwin-Android-debug.apk. Debug APKs are not intended for store distribution.

The debug signing key is generated on the GitHub Actions runner and is not reused on later runs. Android may reject a new preview as an update to the previously installed preview; uninstall the old preview before installing the new one. Uninstalling removes the app's local data. If compatible in-place updates are needed for a testing channel, configure and protect a stable test signing key before distributing the next preview.

The workflow assigns versionName from the tag and uses the GitHub Actions run number for versionCode. For local development, the app defaults to versionName 1.0 and versionCode 1.

## Versioning

Use a vMAJOR.MINOR.PATCH tag after the corresponding changes have landed on main, for example v1.2.0. GitHub Actions creates a prerelease with the matching Windows and Android testing artifacts.
