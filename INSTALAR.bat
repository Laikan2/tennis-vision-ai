@echo off
cd /d "%~dp0"
title Instalar Tennis Vision AI
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\scripts\setup.ps1"
if errorlevel 1 (
  echo.
  echo A instalacao encontrou um erro. Leia a mensagem acima.
  pause
  exit /b 1
)
echo.
echo Instalacao concluida. Agora abra ABRIR.bat.
pause
