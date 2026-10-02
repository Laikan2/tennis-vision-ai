$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$mediaPath = (Resolve-Path (Join-Path $projectRoot 'tools\mediamtx\mediamtx.exe')).Path
$rules = @(
    @{ Name = 'Tennis Vision Camera - WebRTC'; Protocol = 'TCP'; Ports = '8889' },
    @{ Name = 'Tennis Vision Camera - WebRTC Media'; Protocol = 'UDP'; Ports = '8189' },
    @{ Name = 'Tennis Vision Camera - MoQ HTTPS'; Protocol = 'TCP'; Ports = '8892' },
    @{ Name = 'Tennis Vision Camera - MoQ Media'; Protocol = 'UDP'; Ports = '8892,8893' },
    @{ Name = 'Tennis Vision Camera - Native SRT'; Protocol = 'UDP'; Ports = '8890' }
)

foreach ($rule in $rules) {
    Get-NetFirewallRule -DisplayName $rule.Name -ErrorAction SilentlyContinue |
        Remove-NetFirewallRule -ErrorAction SilentlyContinue
    New-NetFirewallRule -DisplayName $rule.Name -Direction Inbound -Action Allow `
        -Program $mediaPath -Protocol $rule.Protocol -LocalPort $rule.Ports `
        -Profile Private -RemoteAddress LocalSubnet | Out-Null
}

Write-Host ''
Write-Host 'A câmera do celular foi liberada somente para a rede privada/local.' -ForegroundColor Green
Write-Host 'Mantenha o perfil do Wi-Fi do Windows como Rede privada.'
$publicConnections = Get-NetConnectionProfile -ErrorAction SilentlyContinue |
    Where-Object { $_.NetworkCategory -eq 'Public' }
if ($publicConnections) {
    Write-Host ''
    Write-Host ('Conexões com perfil Público: ' + (($publicConnections.InterfaceAlias) -join ', ')) -ForegroundColor Yellow
    Write-Host 'Se esta for sua rede doméstica, abra Configurações > Rede e Internet > Ethernet ou Wi-Fi > Propriedades e selecione Rede privada.'
    Write-Host 'As regras acima não permitem a transmissão através de uma conexão com perfil Público.'
}
