@echo off
cd /d "%~dp0"
title Liberar camera do celular - Tennis Vision AI
net session >nul 2>&1
if not %errorlevel%==0 (
  powershell.exe -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\scripts\mobile-camera-firewall.ps1"
if errorlevel 1 (
  echo.
  echo Nao foi possivel criar a regra. Confira se o Windows esta em uma rede privada.
)
echo.
pause
