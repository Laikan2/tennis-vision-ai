# Tennis Vision Camera no iPhone

O aplicativo iOS **0.6.4** tem treino no próprio iPhone: câmera, bola verde, rastro, áreas, impactos candidatos, IN/OUT com revisão, som OUT e histórico local. Não exige Windows, servidor ou internet para analisar. A câmera H.264 e o painel do Windows continuam opcionais. Consulte o [guia do modo local](../mobile/ios/README.md) e o [relatório de validação atual](validation/mobile-parity-2026-10-14/report.txt).

## Atualizar a versão já instalada

A [0.6.4 foi publicada](https://github.com/Laikan2/tennis-vision-releases/releases/tag/mobile-v0.6.4) e o download público foi conferido em 14/10/2026. Adicione uma vez a fonte oficial ao AltStore Classic:

    https://raw.githubusercontent.com/Laikan2/tennis-vision-releases/main/altstore.json

Abra **My Apps → Update**. Se a instalação antiga aparecer apenas como Sideloaded e não oferecer Update, abra a ficha Tennis Vision da fonte ou selecione o novo IPA pelo botão **+** de My Apps. Atualize sobre o app existente; não desinstale. Mantenha AltServer disponível para a assinatura pessoal. A fonte não substitui silenciosamente o executável e a renovação continua necessária.

O treino local usa 720p/1080p. O padrão é Até 60 FPS; 120/240 só são oferecidos quando anunciados pela lente/resolução, com taxas recebidas medidas. Bola minúscula ou borrada, oclusão, contato e linha incertos exigem revisão. Velocidade é lateral e estimada, não radar. Não foi comprovada precisão em partidas ou saques a 200 km/h. O relatório distingue compilação/simulador dos testes físicos realmente concluídos.

Na versão 0.6.4, Atualizações → Instalar atualização pelo AltStore abre o importador diretamente com o IPA oficial do GitHub. O botão aparece quando há uma versão nova compatível; a confirmação e a assinatura continuam no AltStore. A entrega acompanha os recursos do Android 0.6.4, com cenários sintéticos comuns aos dois motores.

## Como instalar agora

**Ainda não existe um link TestFlight ou App Store.** Há duas opções: o `.ipa` compilado para assinatura pessoal pelo AltStore Classic no Windows, ou o projeto Xcode para Mac.

### Somente Windows e iPhone

1. Instale AltServer e AltStore Classic conforme o [guia oficial para Windows](https://faq.altstore.io/altstore-classic/how-to-install-altstore-windows). Conecte o iPhone por USB. Você informa sua conta Apple diretamente no AltServer.
2. Baixe o IPA da [versão iOS 0.6.4](https://github.com/Laikan2/tennis-vision-releases/releases/tag/mobile-v0.6.4). Painéis Windows antigos podem oferecer um IPA antigo incorporado naquela versão.
3. Coloque o arquivo `Tennis-Vision-Camera-iOS-v0.6.4-AltStore.ipa` no app **Arquivos** do iPhone. Com AltServer aberto no PC, use **AltStore Classic → My Apps → +** e selecione o arquivo.
4. Renove em **My Apps → Refresh All** antes de 7 dias. Mantenha AltServer disponível no PC. Sem renovação, o app para de abrir até ser assinado novamente.

Não é necessário comprar um Mac ou uma conta de desenvolvedor paga. O arquivo é uma compilação sem assinatura, preparada para esse processo; tocá-lo no Safari não instala. O pacote não contém sua conta Apple, certificados pessoais ou pareamento. A instalação no iPhone físico ainda depende de você concluir a assinatura no AltStore.

### Com Mac e Xcode

O ZIP `Tennis-Vision-Camera-iOS-v0.6.4-Projeto.zip` contém o código e o projeto, não um instalador de iPhone. Está disponível na página de versões.

1. Em um Mac, instale o Xcode e descompacte o ZIP.
2. Abra `TennisVisionCamera.xcodeproj`.
3. Em **Xcode → Settings → Accounts**, adicione sua conta Apple.
4. Em **TennisVisionCamera → Signing & Capabilities**, selecione sua equipe e mantenha **Automatically manage signing**. Use um Bundle Identifier próprio se necessário.
5. Conecte o iPhone por USB, confie no Mac e ative o Modo de Desenvolvedor quando solicitado.
6. Selecione o iPhone como destino e clique em **Run**.

Requer iOS 17.4 ou posterior. O Windows não oferece o Xcode; AltStore Classic permite assinatura pessoal no Windows.

## Preciso pagar?

Baixar um app gratuito ou receber um teste gratuito pelo TestFlight não exige que você compre uma assinatura de desenvolvedor. Para testar este projeto no seu próprio iPhone pelo Xcode, uma conta Apple gratuita permite instalação pessoal; os perfis expiram após **7 dias**, exigindo recompilar e reinstalar.

No AltStore Classic, uma conta gratuita também exige renovação a cada 7 dias. Consulte os [limites do AltStore](https://faq.altstore.io/altstore-classic/your-altstore).

Para publicar pelo TestFlight ou App Store, o responsável pelo aplicativo precisa do **Apple Developer Program: US$ 99 por ano, ou valor local onde disponível**, além de configurar assinatura e distribuição. A conta gratuita não permite essa distribuição. [Comparação oficial de contas Apple](https://developer.apple.com/support/compare-memberships/).

## Parear com o Windows — opcional

1. No PC, abra **Conectar celular → iPhone / iOS**.
2. Mantenha ambos na mesma rede Wi-Fi. Use **Preparar rede do PC** caso o firewall bloqueie o receptor local.
3. No iPhone, permita **Câmera** e **Rede local**.
4. Leia o QR iPhone. Ele informa o IP do PC, transporte RTSP, porta **8554** e caminho **iphone**.
5. Toque em **Transmitir ao Windows**. No PC, espere **Vídeo recebido** e clique em **Conectar e iniciar vídeo**.

O QR Android usa SRT e caminho `galaxy`. O app iPhone explica quando um QR de outra plataforma é lido. Para duas câmeras, cadastre ambas e selecione as duas na nova sessão. Câmeras, áreas e credenciais salvas são reutilizadas.

## Captura e controles

- Padrão solicitado: **1080p, 60 FPS e 12 Mbps**, sem áudio. O microfone não é acessado.
- Resolução e 30/60/120/240 FPS são oferecidos conforme os formatos realmente anunciados pela câmera. A câmera e o codificador precisam suportar juntos o perfil escolhido. Uma falha não muda silenciosamente o perfil para 60 FPS.
- O app mostra FPS de captura, codificação e envio, bitrate, descartes e temperatura. Taxa enviada não mede a taxa de análise no PC.
- Foco por toque/trava, exposição curta quando suportada, lanterna, grade e tela cheia ajudam a enquadrar a bola e reduzir borrão.
- A fila de rede é limitada a 8 quadros, 2 MB e 80 ms. Após descarte, o envio aguarda um quadro-chave. Escrita parada por um segundo aciona reconexão; as tentativas são limitadas.
- O leitor QR para a prévia antes de usar a câmera. A transmissão e a câmera param em segundo plano.

## Validação realizada em 08/10/2026

- **23 testes do núcleo iOS** passaram, incluindo pareamento, protocolo RTSP, fragmentação H.264/RTP, descarte e taxas.
- **7 testes do empacotamento IPA** verificam o binário arm64 para iPhone, permissões, versão, integridade e ausência de perfis pessoais. Rejeitam compilação de simulador, executável criptografado e caminhos fora do pacote.
- O GitHub compilou o aplicativo para iPhone **sem assinatura** e para simulador. O simulador abriu a interface; ele não fornece uma câmera física.
- Um teste sintético no Mac publicou **300 quadros** H.264 no MediaMTX local. O FFmpeg recebeu e decodificou **60 quadros sem erro**. O teste de transporte admite codificação por software; o app exige hardware.
- A execução completa está no [GitHub Actions](https://github.com/Laikan2/tennis-vision-ai/actions/runs/37809459809), no repositório privado.
- Os testes do backend, programa Windows e painel passaram. O Windows 0.6.7 compilado abriu com motor e receptor integrados, decodificação de vídeo habilitada e ponte do painel pronta. Neste teste, a inicialização até o relatório levou **7,8 s**; isso não é latência da câmera.
- O download do projeto pela API do programa compilado correspondeu ao ZIP testado, por SHA-256. Os caminhos Android/iPhone foram conferidos separadamente. O teste do Windows usou dados isolados e não executou o instalador sobre os dados existentes.
- O pacote final com AltStore abriu em **1,8 s** neste teste com cache; a medição anterior de **7,8 s** foi feita na primeira execução. Essas medidas são de inicialização, não de latência da transmissão.
- O download do `.ipa` pelo botão do painel e pelo link público do GitHub correspondeu ao binário testado, por SHA-256. A versão **0.6.7** e o arquivo `latest.yml` do atualizador estão publicados e foram conferidos.
- Uma repetição do teste de resolução encontrou um quadro de teste expirado enquanto a compilação concorria por recursos. O teste agora usa relógio controlado para verificar somente a resolução; passou após a correção. A rejeição de quadros antigos do produto continua ativa. Na execução ampla, 273 testes passaram; o teste corrigido passou separadamente.
- O simulador hospedado travou na inicialização em uma execução posterior. O diagnóstico de screenshot agora tem prazo de dois minutos e não bloqueia o envio dos artefatos quando o ambiente de simulação falha. Compilação, testes do núcleo e decodificação do transporte continuam obrigatórios. A interface foi conferida na execução completa vinculada acima.

**Ainda depende de teste físico:** instalação pelo AltStore, câmera do iPhone 15 Pro, leitura do QR por câmera, foco/exposição, H.264 sustentado em 120/240 FPS, rede, temperatura e latência do sensor até a tela. A assinatura pessoal é feita no AltStore; a distribuição pelo TestFlight continua pendente. A validação de transporte não comprova a precisão física de impactos, velocidade ou decisões IN/OUT.
