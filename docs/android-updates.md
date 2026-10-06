# Atualizações do Tennis Vision Camera

## Ativar no celular

Instale **Tennis-Vision-Camera-Android-v0.5.5.apk** sobre a versão anterior, sem desinstalar. O APK usa o mesmo certificado e mantém o pareamento. Esta instalação inicial é necessária porque as versões até 0.5.2 não têm atualizador.

Depois, o app consulta o GitHub ao abrir e a cada seis horas enquanto está aberto. Quando a transmissão está parada e há conexão sem cobrança por dados, ele baixa a atualização automaticamente. Na rede móvel, o botão **Baixar atualização** permite autorizar o download manual.

Toque na versão no alto da tela, ou abra **Rede → Atualizações do aplicativo**. Quando aparecer **Instalar atualização**, toque nesse botão. Na primeira vez, o Android abre a autorização **Permitir desta fonte**; ative, volte ao app e confirme **Atualizar** no instalador do sistema. A instalação precisa da sua confirmação e não ocorre silenciosamente.

Se você iniciar uma transmissão ou sair do app, a transferência é interrompida e retomada em uma oportunidade posterior. A câmera continua funcionando sem internet. Uma atualização pronta fica guardada no celular e pode ser instalada depois.

## Verificação do arquivo

O app consulta as 100 releases mais recentes do repositório público `Laikan2/tennis-vision-releases` e escolhe a maior versão estável do APK Android, mesmo quando a versão do Windows é diferente. Não exige conta GitHub nem usa tokens.

O nome aceito é `Tennis-Vision-Camera-Android-vX.Y.Z.apk`. O download usa HTTPS, tem limite de 64 MiB e precisa corresponder ao tamanho e SHA-256 publicados pelo GitHub. Antes de instalar, o app confere novamente o arquivo, o pacote, a versão crescente, a compatibilidade com o Android e a assinatura da instalação atual.

Arquivos incompletos ou inválidos são apagados. O instalador recebe acesso temporário somente à pasta privada de atualizações através de um `FileProvider`. Falhas de rede têm um intervalo mínimo de 15 minutos antes da nova tentativa automática; a consulta manual continua disponível.

## Publicar futuras versões

O Windows 0.6.4 inclui o APK 0.5.5 para a instalação inicial. Para atualizar o Android:

1. Aumente `versionName` e `versionCode` em `mobile/android/app/build.gradle.kts`.
2. Compile e teste o APK usando o mesmo certificado da versão instalada.
3. Salve-o em `mobile/releases/Tennis-Vision-Camera-Android-vX.Y.Z.apk` e ajuste a versão anunciada em `backend/api.py` e o filtro de APK em `desktop/package.json` quando publicar o pacote Windows.
4. Publique o APK em uma release estável no repositório de distribuição. Uma release exclusiva do Android deve usar `--latest=false` para preservar a release completa usada pelo atualizador do Windows.

Enviar apenas o código ao GitHub não atualiza os aparelhos. É necessário publicar o APK compilado. Não substitua os arquivos de uma versão já publicada.

## Validação da versão 0.5.3

Os 73 testes Android passaram, incluindo consultas, download em rede móvel, interrupções, retomada, arquivo adulterado, assinatura divergente, autorização do instalador e consulta periódica. A interface foi renderizada com os recursos reais do aplicativo. O APK de produção foi compilado com redução de código e o lint não encontrou erros.

Os 182 testes do backend e 20 testes do desktop também passaram. O Windows 0.6.2 foi recompilado e abriu com dados de teste isolados em 1,407 s, com a ponte nativa e o motor disponíveis. A API anunciou Android 0.5.3 e serviu os mesmos 3.147.536 bytes do APK original. SHA-256 do APK: `f5ffc832597b4f362acdb3689b9201400f8790c2165313366d1e37b137763eaa`.

A release pública [Windows 0.6.2 / Android 0.5.3](https://github.com/Laikan2/tennis-vision-releases/releases/tag/v0.6.2) foi publicada em 3 de outubro de 2026. Um teste adicional usou o próprio `GitHubAppUpdateSource`, sem autenticação: encontrou a atualização para uma versão anterior, baixou e conferiu todos os bytes, e não ofereceu downgrade para 0.5.3. O manifesto público do Windows também corresponde ao arquivo local. Essa verificação não executou instalação no celular.

Os testes do controlador simulam a fronteira do instalador e a URI Android: o `FileProvider` não interpreta caminhos Windows da mesma forma que caminhos de um celular. Há testes instrumentados separados para verificar a leitura e o isolamento da pasta no Android; seu APK de teste foi compilado. Como nenhum aparelho estava conectado, a instalação e esses testes instrumentados ainda precisam ser executados em um aparelho ou emulador.

## Revisão atual

A revisão Windows 0.6.3 / Android 0.5.4 mantém esse fluxo e a assinatura. Veja [correções, ensaios e limitações](revisao-2026-10-05.md).
