param([ValidatePattern('^\d+\.\d+\.\d+$')][string]$Version='0.6.4',[switch]$VerifyOnly)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$repository='Laikan2/tennis-vision-releases'
$tag="android-v$Version"
$manifestPath=Join-Path $projectRoot "mobile/releases/android-v$Version.json"
$meta=Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$expectedCertificate='faf843630ad0a52fef6ca4f844cae592e3da1e266b7b823f481c34d3b2049ce9'
if($meta.certificateSha256 -ne $expectedCertificate){throw 'O manifesto não corresponde ao certificado Android anterior.'}
$expectedName="Tennis-Vision-Camera-Android-v$Version.apk"
if($meta.version -ne $Version -or $meta.filename -ne $expectedName){throw 'Manifesto Android inconsistente.'}
$apk=Get-Item -LiteralPath (Join-Path $projectRoot "mobile/releases/$expectedName")
$hash=(Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash.ToLower()
if($apk.Length -ne $meta.bytes -or $hash -ne $meta.sha256){throw 'O tamanho ou SHA-256 do APK não confere. Não foi publicado.'}
$sdkRoot=if($env:ANDROID_HOME){$env:ANDROID_HOME}else{Join-Path $env:USERPROFILE '.codex/cache/tennis-vision-android/sdk'}
$buildTools=Get-ChildItem -LiteralPath (Join-Path $sdkRoot 'build-tools') -Directory | Where-Object Name -Match '^\d+\.\d+\.\d+$' | Sort-Object {[version]$_.Name} -Descending | Select-Object -First 1
if(-not $buildTools){throw 'Android SDK build-tools não encontrado para conferir a assinatura.'}
if(-not $env:JAVA_HOME){
 $jdk=Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE '.codex/cache/tennis-vision-android/jdk') -Directory | Where-Object Name -Like 'jdk-17*' | Select-Object -First 1
 if(-not $jdk){throw 'Configure JAVA_HOME para o JDK 17.'}
 $env:JAVA_HOME=$jdk.FullName
}
$signature=(& (Join-Path $buildTools.FullName 'apksigner.bat') verify --verbose --print-certs $apk.FullName) -join "`n"
if($LASTEXITCODE -ne 0 -or $signature -notmatch ([regex]::Escape("certificate SHA-256 digest: $($meta.certificateSha256)"))){throw 'Assinatura do APK inválida ou certificado diferente.'}
$badging=(& (Join-Path $buildTools.FullName 'aapt2.exe') dump badging $apk.FullName) -join "`n"
if($LASTEXITCODE -ne 0 -or $badging -notmatch ([regex]::Escape("name='ai.tennisvision.camera' versionCode='$($meta.versionCode)' versionName='$Version'"))){throw 'Pacote ou versão do APK não confere.'}
if($VerifyOnly){[pscustomobject]@{verified=$true;version=$Version;bytes=$apk.Length;sha256=$hash;certificateSha256=$meta.certificateSha256}|ConvertTo-Json;return}
$ghCommand=Get-Command gh -ErrorAction SilentlyContinue
$gh=if($ghCommand){$ghCommand.Source}else{Join-Path $env:LOCALAPPDATA 'TennisVisionTools/gh/bin/gh.exe'}
if(-not(Test-Path -LiteralPath $gh)){throw 'GitHub CLI não encontrado. Instale o GitHub CLI oficial e entre na sua conta.'}
& $gh auth status
if($LASTEXITCODE -ne 0){throw 'Entre no GitHub CLI antes de publicar: gh auth login'}
$latestText=& $gh api "repos/$repository/releases/latest"
if($LASTEXITCODE -ne 0){throw 'Não foi possível consultar a versão atual do Windows.'}
$latest=(($latestText -join "`n")|ConvertFrom-Json).tag_name
$feedText=& $gh api "repos/$repository/releases?per_page=100"
if($LASTEXITCODE -ne 0){throw 'Não foi possível consultar as versões do GitHub.'}
$existing=@(($feedText -join "`n")|ConvertFrom-Json)|Where-Object tag_name -EQ $tag|Select-Object -First 1
if($existing -and $existing.body -notmatch [regex]::Escape($hash)){throw 'Esta versão já existe com outro APK. Não substitua um APK publicado.'}
$notes=Join-Path $projectRoot "docs/releases/android-v$Version.md"
if(-not(Test-Path -LiteralPath $notes)){throw 'Notas da versão não encontradas.'}
if(-not $existing){
 & $gh release create $tag --repo $repository --target main --draft --latest=false --title "Tennis Vision Camera Android $Version" --notes-file $notes
 if($LASTEXITCODE -ne 0){throw 'Não criou o rascunho da versão. Nada foi anunciado ao aplicativo.'}
}
# GitHub may not resolve /releases/tags for an unpublished draft/tag. Read the
# authenticated release collection, which includes drafts, and then use its id.
function Read-AndroidRelease {
 $releaseText=& $gh api "repos/$repository/releases?per_page=100"
 if($LASTEXITCODE -ne 0){throw 'Não leu as publicações do GitHub.'}
 $found=@(($releaseText -join "`n")|ConvertFrom-Json)|Where-Object tag_name -EQ $tag|Select-Object -First 1
 if(-not $found){throw 'A publicação não foi encontrada na lista autenticada.'}
 return $found
}
$draft=Read-AndroidRelease
$asset=@($draft.assets)|Where-Object name -EQ $expectedName|Select-Object -First 1
if(-not $asset){
 if(-not $draft.draft){throw 'O APK está ausente numa versão publicada. Não será alterada.'}
 & $gh release upload $tag $apk.FullName --repo $repository
 if($LASTEXITCODE -ne 0){throw 'Upload incompleto. A versão continua em rascunho.'}
 $draft=Read-AndroidRelease
 $asset=@($draft.assets)|Where-Object name -EQ $expectedName|Select-Object -First 1
}
if(-not $asset -or $asset.state -ne 'uploaded' -or $asset.size -ne $meta.bytes -or $asset.digest -ne "sha256:$hash"){throw 'Integridade do upload divergente. A versão não será publicada.'}
if($draft.draft){
 & $gh release edit $tag --repo $repository --draft=false --latest=false
 if($LASTEXITCODE -ne 0){throw 'Não confirmou a publicação do rascunho.'}
}
$afterText=& $gh api "repos/$repository/releases/latest"
if($LASTEXITCODE -ne 0 -or (($afterText -join "`n")|ConvertFrom-Json).tag_name -ne $latest){throw 'Confira a referência latest do Windows; o resultado não foi confirmado.'}
$publicUrl="https://github.com/$repository/releases/download/$tag/$expectedName"
$verificationFolder=Join-Path $projectRoot "mobile/releases/verification/$tag"
New-Item -ItemType Directory -Path $verificationFolder -Force|Out-Null
$download=Join-Path $verificationFolder $expectedName
Invoke-WebRequest -Uri $publicUrl -OutFile $download
if((Get-Item -LiteralPath $download).Length -ne $meta.bytes -or (Get-FileHash -LiteralPath $download -Algorithm SHA256).Hash.ToLower() -ne $hash){throw 'A publicação aconteceu, mas o download público não passou na conferência.'}
$proof=[pscustomobject]@{published=$true;version=$Version;url=$publicUrl;publicDownloadBytes=$meta.bytes;sha256=$hash;windowsLatestUnchanged=$latest}
$proof|ConvertTo-Json|Set-Content -Encoding utf8 -LiteralPath (Join-Path $verificationFolder 'published.json')
$proof|ConvertTo-Json
Write-Output 'Publicado e conferido. No celular: Atualizações > Buscar atualização > Instalar atualização.'
