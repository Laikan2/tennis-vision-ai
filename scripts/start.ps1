$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$pythonPath = Join-Path $projectRoot '.venv\Scripts\python.exe'
$runtimePython = Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
$pythonWorks = $false
if (Test-Path -LiteralPath $pythonPath) { & $pythonPath -c "import sys" 2>$null; $pythonWorks = ($LASTEXITCODE -eq 0) }
if (-not $pythonWorks) {
    if (-not (Test-Path -LiteralPath $runtimePython)) { throw 'Execute scripts\setup.ps1 primeiro.' }
    $pythonPath = $runtimePython
    $env:PYTHONPATH = Join-Path $projectRoot '.venv\Lib\site-packages'
}
$nextPath = Join-Path $projectRoot 'frontend\node_modules\next\dist\bin\next'
$mediaPath = Join-Path $projectRoot 'tools\mediamtx\mediamtx.exe'
$mediaConfig = Join-Path $projectRoot 'tools\mediamtx\mediamtx.yml'
$mediaProcess = $null
if (Test-Path -LiteralPath $mediaPath) {
    $mediaListening = Get-NetTCPConnection -LocalPort 8554 -State Listen -ErrorAction SilentlyContinue
    if (-not $mediaListening) {
        $mediaProcess = Start-Process -FilePath $mediaPath -ArgumentList $mediaConfig -WorkingDirectory (Split-Path $mediaPath) -WindowStyle Hidden -PassThru
    }
}
$backendProcess = Start-Process -FilePath $pythonPath -ArgumentList '-m','uvicorn','backend.api:app','--host','127.0.0.1','--port','8000','--no-access-log' -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru
try {
    Set-Location -LiteralPath (Join-Path $projectRoot 'frontend')
    Write-Host 'Tennis Vision AI: http://127.0.0.1:3000 — Ctrl+C para encerrar.'
    & node $nextPath dev --hostname 127.0.0.1 --port 3000
} finally {
    if (-not $backendProcess.HasExited) { Stop-Process -Id $backendProcess.Id }
    if ($mediaProcess -and -not $mediaProcess.HasExited) { Stop-Process -Id $mediaProcess.Id }
}
