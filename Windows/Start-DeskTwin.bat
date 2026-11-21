@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Start-DeskTwin.ps1"
if errorlevel 1 pause
