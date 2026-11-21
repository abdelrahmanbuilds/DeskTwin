# Development

## Prerequisites

- Windows 10 version 2004 (build 19041) or later
- JDK 21 and Android SDK Platform 35
- .NET 8 SDK
- Python 3.11 or later on PATH
- Android Studio is optional; the checked-in Gradle wrapper can build from a terminal

## Android

Open the Android directory in Android Studio, or run this from the repository root:

    cd Android
    ./gradlew.bat :app:assembleDebug

The debug APK is written to Android/app/build/outputs/apk/debug/app-debug.apk. Install it only for development with ./gradlew.bat :app:installDebug.

## Windows

From the repository root:

    cd Windows
    py -3 -m venv .venv
    .venv/Scripts/Activate.ps1
    python -m pip install -r Python/dependencies/requirements-winui.txt
    dotnet build WinUI/app/DeskTwin.WinUI.csproj --configuration Debug
    dotnet run --project WinUI/app/DeskTwin.WinUI.csproj

The WinUI client starts the Python bridge as a local child process. Keep the repository layout intact so it can find Windows/WinUI/bridge and Windows/Python.

## Dependencies

Android dependencies and versions are declared in Android/app/build.gradle.kts; Gradle and Android Gradle Plugin versions are declared in the wrapper and root build files. Windows dependencies are declared in Windows/WinUI/app/DeskTwin.WinUI.csproj and Windows/Python/dependencies/requirements-*.txt. Dependencies are restored by Gradle, NuGet, and pip; binary caches and virtual environments are intentionally not checked in.

## Updating the project

1. Pull the latest main branch and create a feature branch.
2. Make the Android, Windows, or documentation change in its owning directory.
3. Build the changed component using the commands above. Run the relevant service tests with python -m unittest discover -s tests -v from Windows/ when changing Python behavior.
4. Open a pull request. GitHub Actions builds Android and Windows on each pull request.
5. After merging a release-ready change, create and push a version tag such as v1.2.0. The release workflow packages Windows and publishes a GitHub Release. Android APK publication additionally needs the signing setup in RELEASING.md.

## Contribution scope

Keep changes focused, retain the SAF permission boundary on Android, preserve TLS certificate pinning and explicit pairing on the network path, and avoid committing local settings, databases, logs, generated output, signing files, or credentials.
