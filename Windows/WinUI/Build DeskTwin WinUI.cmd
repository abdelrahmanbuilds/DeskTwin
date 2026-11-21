@echo off
setlocal
cd /d "%~dp0"

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0launchers\build-winui.ps1"
set "BUILD_EXIT_CODE=%ERRORLEVEL%"

echo.
if not "%BUILD_EXIT_CODE%"=="0" (
    echo Build failed. Review the messages above.
) else (
    echo Build completed successfully.
)
echo.
pause
exit /b %BUILD_EXIT_CODE%
