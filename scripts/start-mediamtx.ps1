$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$mediaPath = Join-Path $projectRoot 'tools\mediamtx\mediamtx.exe'
$mediaConfig = Join-Path $projectRoot 'tools\mediamtx\mediamtx.yml'
if (-not (Test-Path -LiteralPath $mediaPath)) { throw 'MediaMTX não está instalado. Execute o setup do projeto.' }
Write-Host 'MediaMTX RTSP: rtsp://192.168.15.2:8554/iphone — Ctrl+C para encerrar.'
& $mediaPath $mediaConfig
