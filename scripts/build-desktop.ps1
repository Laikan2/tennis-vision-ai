param([switch]$SkipEngine)
$ErrorActionPreference = 'Stop'
$studioRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$studioPython = Join-Path $studioRoot '.venv\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $studioPython)) { throw 'Execute INSTALAR.bat antes de compilar o instalador.' }
# Building .next while the browser server reads it can make CSS/chunks disappear.
$webListeners = Get-NetTCPConnection -LocalPort 3000 -State Listen -ErrorAction SilentlyContinue
foreach ($listener in $webListeners) {
    $webProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
    if ($webProcess.CommandLine -like "*$studioRoot*next*start*") {
        throw 'Encerre o painel web na porta 3000 antes de compilar. Seus dados ficam preservados.'
    }
}
Push-Location $studioRoot
try {
    & $studioPython scripts/prepare-desktop.py
    if ($LASTEXITCODE) { throw 'Falha ao preparar os modelos.' }
    Push-Location frontend
    $previousDesktop = $env:TV_DESKTOP_BUILD
    try {
        $env:TV_DESKTOP_BUILD = 'true'
        pnpm build
        if ($LASTEXITCODE) { throw 'Falha ao compilar a interface.' }
    } finally { $env:TV_DESKTOP_BUILD = $previousDesktop; Pop-Location }
    if (-not $SkipEngine) {
        & $studioPython -m pip install -r desktop/requirements-build.txt
        if ($LASTEXITCODE) { throw 'Falha ao preparar o compilador da IA.' }
        & $studioPython -m PyInstaller desktop/engine.spec --distpath desktop/build/engine --workpath desktop/build/pyinstaller --noconfirm
        if ($LASTEXITCODE) { throw 'Falha ao empacotar a IA.' }
    }
    Push-Location desktop
    try {
        pnpm install --frozen-lockfile
        if ($LASTEXITCODE) { throw 'Falha ao instalar as dependências do programa.' }
        pnpm test
        if ($LASTEXITCODE) { throw 'Falha nos testes do programa.' }
        pnpm run dist
        if ($LASTEXITCODE) { throw 'Falha ao gerar o instalador.' }
    } finally { Pop-Location }
} finally { Pop-Location }
