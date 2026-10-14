@echo off
cd /d "%~dp0"
powershell.exe -NoProfile -File "%~dp0scripts\publish-android-release.ps1" -Version 0.6.4
if errorlevel 1 echo A publicacao nao foi confirmada. Confira a mensagem acima.
pause
