$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $projectRoot
$pythonPath = Join-Path $projectRoot '.venv\Scripts\python.exe'
$runtimePython = Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
$pnpmCommand = Get-Command pnpm -ErrorAction SilentlyContinue
$bundledPnpm = Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\bin\fallback\pnpm.cmd'
$npmCommand = Get-Command npm.cmd -ErrorAction SilentlyContinue
if (-not $npmCommand) { $npmCommand = Get-Command npm -ErrorAction SilentlyContinue }
if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    throw 'Node.js não encontrado. Instale a versão LTS em https://nodejs.org e execute INSTALAR.bat novamente.'
}
if ($pnpmCommand) { $frontendInstaller = @($pnpmCommand.Source, 'install', '--frozen-lockfile') }
elseif (Test-Path -LiteralPath $bundledPnpm) { $frontendInstaller = @($bundledPnpm, 'install', '--frozen-lockfile') }
elseif ($npmCommand) { $frontendInstaller = @($npmCommand.Source, 'install', '--no-audit', '--no-fund') }
else { throw 'npm/pnpm não encontrado. Reinstale o Node.js LTS e execute novamente.' }
if (-not (Test-Path -LiteralPath '.venv\Scripts\python.exe') -or -not (& $pythonPath -c "import sys" 2>$null)) {
    if (Test-Path -LiteralPath $runtimePython) { & $runtimePython -m venv .venv }
    elseif (Get-Command py -ErrorAction SilentlyContinue) { & py -3 -m venv .venv }
    elseif (Get-Command python -ErrorAction SilentlyContinue) { & python -m venv .venv }
    else { throw 'Python não encontrado. Instale Python 3.12 em https://python.org e execute INSTALAR.bat novamente.' }
    if ($LASTEXITCODE -ne 0) { throw 'Instale Python 3.12 e tente novamente.' }
}
& '.venv\Scripts\python.exe' -m pip install -r backend\requirements-lock.txt
if ($LASTEXITCODE -ne 0) { throw 'Falha ao instalar dependências Python.' }
Write-Host 'Instalando suporte de IA (pessoas e sports ball)...'
& '.venv\Scripts\python.exe' -m pip install -r backend\requirements-ml.txt
if ($LASTEXITCODE -ne 0) { throw 'Falha ao instalar suporte de IA.' }
New-Item -ItemType Directory -Force -Path 'data\models' | Out-Null
if (-not (Test-Path -LiteralPath 'data\models\yolo11n.pt')) {
    & '.venv\Scripts\python.exe' -c "from ultralytics import YOLO; YOLO('yolo11n.pt')"
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao baixar o modelo geral YOLO.' }
    if (Test-Path -LiteralPath 'yolo11n.pt') { Move-Item -LiteralPath 'yolo11n.pt' -Destination 'data\models\yolo11n.pt' -Force }
}
if (-not (Test-Path -LiteralPath '.env')) {
    & '.venv\Scripts\python.exe' -c "from pathlib import Path; from cryptography.fernet import Fernet; Path('.env').write_text('TV_SECRET_KEY='+Fernet.generate_key().decode()+'\n',encoding='utf-8')"
}
if (-not (Test-Path -LiteralPath 'data\models\yolo11n-pose.pt')) {
    Invoke-WebRequest -Uri 'https://github.com/ultralytics/assets/releases/download/v8.4.0/yolo11n-pose.pt' -OutFile 'data\models\yolo11n-pose.pt'
}
Push-Location frontend
try {
    $installer = $frontendInstaller[0]
    $installerArgs = $frontendInstaller[1..($frontendInstaller.Count-1)]
    & $installer @installerArgs
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao instalar frontend. Verifique Node.js e pnpm.' }
} finally { Pop-Location }
Write-Host 'Pronto. Execute scripts\start.ps1 para iniciar o aplicativo local.'
