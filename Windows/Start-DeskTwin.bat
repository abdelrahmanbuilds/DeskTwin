@echo off
setlocal
set "APP=%~dp0Windows\WinUI\app\DeskTwin.WinUI.exe"
if not exist "%APP%" (
    echo DeskTwin files are incomplete. Re-download and extract the Windows ZIP.
    pause
    exit /b 1
)
start "" "%APP%"
