param([Parameter(Mandatory=$true)][string]$ReceiverPath,
      [string]$StudioPath,
      [Parameter(Mandatory=$true)][string]$ResultPath)
$ErrorActionPreference = 'Stop'
try {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        # User clicks Prepare Network; only this helper requests Windows UAC.
        $elevatedArguments = @('-NoProfile','-ExecutionPolicy','Bypass','-File',
            ('"{0}"' -f $PSCommandPath),'-ReceiverPath',('"{0}"' -f $ReceiverPath),
            '-ResultPath',('"{0}"' -f $ResultPath)) -join ' '
        if ($StudioPath) { $elevatedArguments += ' -StudioPath "' + $StudioPath + '"' }
        $elevated = Start-Process -FilePath 'powershell.exe' -ArgumentList $elevatedArguments -Verb RunAs -WindowStyle Hidden -Wait -PassThru
        exit $elevated.ExitCode
    }
    $receiverResolved = (Resolve-Path -LiteralPath $ReceiverPath).Path
    if ([IO.Path]::GetFileName($receiverResolved) -ne 'mediamtx.exe') { throw 'Receptor invalido.' }
    $rules = @(
        @{ Name='Tennis Vision Studio - SRT'; Protocol='UDP'; Ports='8890' },
        @{ Name='Tennis Vision Studio - RTSP'; Protocol='TCP'; Ports='8554' },
        @{ Name='Tennis Vision Studio - WebRTC'; Protocol='TCP'; Ports='8889' },
        @{ Name='Tennis Vision Studio - WebRTC Media'; Protocol='UDP'; Ports='8189' },
        @{ Name='Tennis Vision Studio - Mobile HTTPS'; Protocol='TCP'; Ports='8892' },
        @{ Name='Tennis Vision Studio - Mobile Media'; Protocol='UDP'; Ports='8892,8893' }
    )
    foreach ($rule in $rules) {
        # Independent names leave the original project's rules intact.
        $existing = Get-NetFirewallRule -DisplayName $rule.Name -ErrorAction SilentlyContinue
        if ($existing) { $existing | Remove-NetFirewallRule }
        New-NetFirewallRule -DisplayName $rule.Name -Direction Inbound -Action Allow -Program $receiverResolved `
            -Protocol $rule.Protocol -LocalPort $rule.Ports -Profile Private -RemoteAddress LocalSubnet | Out-Null
    }
    if ($StudioPath) {
        $studioResolved = (Resolve-Path -LiteralPath $StudioPath).Path
        if ([IO.Path]::GetFileName($studioResolved) -notin @('Tennis Vision Studio.exe','electron.exe')) { throw 'Studio invalido.' }
        Get-NetFirewallRule -DisplayName 'Tennis Vision Studio - Painel celular' -ErrorAction SilentlyContinue | Remove-NetFirewallRule
        New-NetFirewallRule -DisplayName 'Tennis Vision Studio - Painel celular' -Direction Inbound -Action Allow `
            -Program $studioResolved -Protocol TCP -LocalPort 8894 -Profile Private -RemoteAddress LocalSubnet | Out-Null
    }
    @{ ok=$true; detail='Rede preparada para a camera e o painel do celular. Mantenha o PC e o celular na mesma rede privada.' } |
        ConvertTo-Json | Set-Content -LiteralPath $ResultPath -Encoding UTF8
} catch {
    @{ ok=$false; detail='Nao foi possivel preparar a rede. Autorize o pedido do Windows e confira o perfil de rede privada.' } |
        ConvertTo-Json | Set-Content -LiteralPath $ResultPath -Encoding UTF8
    exit 1
}
