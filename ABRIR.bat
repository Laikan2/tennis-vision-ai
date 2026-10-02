@echo off
cd /d "%~dp0"
title Tennis Vision AI
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\scripts\start.ps1"
if errorlevel 1 (
  echo.
  echo Nao foi possivel iniciar. Execute INSTALAR.bat primeiro.
  pause
)
