param([switch]$SkipBuild, [switch]$KeepDraft)
$ErrorActionPreference = 'Stop'
$studioRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$package = Get-Content -LiteralPath (Join-Path $studioRoot 'desktop\package.json') -Raw | ConvertFrom-Json
$feed = $package.build.publish
if ($feed.provider -ne 'github' -or $feed.owner -notmatch '^[A-Za-z0-9-]+$' -or $feed.repo -notmatch '^[A-Za-z0-9_.-]+$') {
    throw 'Configure o repositório GitHub em desktop/package.json antes de publicar.'
}
$releaseRepo = "$($feed.owner)/$($feed.repo)"
$releaseVersion = $package.version
$androidGradle = Get-Content -LiteralPath (Join-Path $studioRoot 'mobile\android\app\build.gradle.kts') -Raw
$androidVersion = [regex]::Match($androidGradle, 'versionName\s*=\s*"(\d+\.\d+\.\d+)"').Groups[1].Value
if (-not $androidVersion) { throw 'Não foi possível identificar a versão Android.' }
if ($releaseVersion -notmatch '^\d+\.\d+\.\d+$') { throw 'Publique uma versão estável no formato 0.6.0.' }
$releaseTag = "v$releaseVersion"
$ghCommand = Get-Command gh -ErrorAction SilentlyContinue
$releaseGh = if ($ghCommand) { $ghCommand.Source } else { Join-Path $env:LOCALAPPDATA 'TennisVisionTools\gh\bin\gh.exe' }
if (-not (Test-Path -LiteralPath $releaseGh)) { throw 'Instale GitHub CLI e execute gh auth login no PC que publica as versões.' }
& $releaseGh auth status
if ($LASTEXITCODE) { throw 'Entre na sua conta com gh auth login antes de publicar.' }
$existing = & $releaseGh release list --repo $releaseRepo --limit 100 --json tagName,isDraft,isPrerelease
if ($LASTEXITCODE) { throw 'Não foi possível consultar o repositório de instaladores.' }
$allReleases = @($existing | ConvertFrom-Json)
if ($allReleases | Where-Object tagName -EQ $releaseTag) { throw "A versão $releaseTag já existe. Aumente a versão; não substitua arquivos publicados." }
foreach ($release in $allReleases) {
    if (-not $release.isDraft -and -not $release.isPrerelease -and $release.tagName -match '^v(\d+\.\d+\.\d+)$' -and [version]$Matches[1] -ge [version]$releaseVersion) {
        throw 'A nova versão precisa ser maior que as versões já publicadas.'
    }
}
if (-not $SkipBuild) { & (Join-Path $PSScriptRoot 'build-desktop.ps1'); if ($LASTEXITCODE) { throw 'A compilação falhou.' } }
Push-Location $studioRoot
try {
    $verified = node desktop/verify-release.cjs
    if ($LASTEXITCODE) { throw 'Os arquivos da atualização não passaram na verificação.' }
    $releaseFiles = $verified | ConvertFrom-Json
    $notesFile = Join-Path ([IO.Path]::GetTempPath()) ("tennis-release-notes-" + [guid]::NewGuid().ToString('N') + '.md')
    $releaseNotes = @"
# Tennis Vision Studio $releaseVersion

O programa para Windows recebe as câmeras e executa a análise no PC.

Atualizações: versões a partir de 0.6.0 consultam este repositório automaticamente, baixam quando a sessão está parada e instalam ao fechar. Para atualizar um programa anterior à 0.6.0, execute este instalador uma vez sobre a instalação existente.

Câmeras, áreas, sessões e credenciais locais são preservadas. O instalador inclui o aplicativo Android $androidVersion com atualizações pelo GitHub. Instale o APK uma vez sobre o app anterior para ativar o recurso; depois, abra Rede > Atualizações do aplicativo no celular para confirmar a instalação das próximas versões. O download automático espera a transmissão parar e uma rede sem cobrança por dados.
"@
    [IO.File]::WriteAllText($notesFile, $releaseNotes, [Text.UTF8Encoding]::new($false))
    & $releaseGh release create $releaseTag --repo $releaseRepo --draft --title "Tennis Vision Studio $releaseVersion" --notes-file $notesFile
    if ($LASTEXITCODE) { throw 'Não foi possível criar o rascunho da versão.' }
    $releaseAssets = @($releaseFiles.files)
    $android = Join-Path $studioRoot "mobile\releases\Tennis-Vision-Camera-Android-v$androidVersion.apk"
    if (Test-Path -LiteralPath $android) { $releaseAssets += $android }
    $iosVersion = [regex]::Match((Get-Content -LiteralPath (Join-Path $studioRoot 'mobile\ios\project.py') -Raw), '"MARKETING_VERSION": "([0-9.]+)"').Groups[1].Value
    $iosIpa = Join-Path $studioRoot "mobile\releases\Tennis-Vision-Camera-iOS-v$iosVersion-AltStore.ipa"
    if (Test-Path -LiteralPath $iosIpa) { $releaseAssets += $iosIpa }
    & $releaseGh release upload $releaseTag --repo $releaseRepo @releaseAssets
    if ($LASTEXITCODE) { throw 'O envio falhou. A versão permanece em rascunho e não será oferecida aos programas instalados.' }
    $remote = & $releaseGh release view $releaseTag --repo $releaseRepo --json assets
    if ($LASTEXITCODE) { throw 'Não foi possível conferir os arquivos enviados. A versão permanece em rascunho.' }
    $remoteAssets = ($remote | ConvertFrom-Json).assets
    foreach ($asset in $releaseAssets) {
        $localAsset = Get-Item -LiteralPath $asset
        $remoteAsset = $remoteAssets | Where-Object name -EQ $localAsset.Name
        if (-not $remoteAsset -or $remoteAsset.size -ne $localAsset.Length) { throw 'Arquivos incompletos no GitHub. A versão permanece em rascunho.' }
        $localDigest = 'sha256:' + (Get-FileHash -LiteralPath $asset -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($remoteAsset.digest -and $remoteAsset.digest -ne $localDigest) { throw 'O hash do arquivo no GitHub não corresponde ao arquivo local. A versão permanece em rascunho.' }
    }
    if (-not $KeepDraft) {
        & $releaseGh release edit $releaseTag --repo $releaseRepo --draft=false --latest
        if ($LASTEXITCODE) { throw 'Os arquivos foram enviados, mas a versão ainda não foi publicada.' }
    }
    Write-Output "https://github.com/$releaseRepo/releases/tag/$releaseTag"
} finally { Pop-Location }
