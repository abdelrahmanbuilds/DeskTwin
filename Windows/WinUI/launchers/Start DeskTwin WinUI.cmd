@echo off
setlocal
cd /d "%~dp0..\.."
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-winui.ps1" %*
set "DESKTWIN_EXIT_CODE=%ERRORLEVEL%"
echo.
if not "%DESKTWIN_EXIT_CODE%"=="0" (
    echo DeskTwin WinUI launcher exited with code %DESKTWIN_EXIT_CODE%. Review the messages above.
) else (
    echo DeskTwin WinUI closed without reporting an error.
)
echo This window will stay open so you can read the output.
pause
exit /b %DESKTWIN_EXIT_CODE%
