# Tennis Vision Camera para iPhone — 0.2.0

Aplicativo nativo SwiftUI para iOS 17.4 ou posterior. A câmera traseira transmite H.264 por RTSP/TCP ao Tennis Vision Studio no Windows. Bola verde, rastro, impactos, IN/OUT e estatísticas são analisados no PC. A câmera nativa não precisa de Larix ou HTTPS.

## Câmera e painel no iPhone

- **Câmera:** prévia nativa, foco por toque, exposição curta e taxas medidas.
- **Treino:** a mesma sessão do Windows 0.6.8+, incluindo áreas por arraste ou quatro cantos, calibração, IN/OUT, revisão, som OUT, estatísticas, histórico e duas câmeras. O PC precisa estar ligado e continua executando a IA. A câmera permanece transmitindo nesta aba.
- **Ajustes:** QR, perfil automático ou manual, exposição, foco, lanterna e rede.
- **Atualizações:** consulta à fonte oficial no GitHub e botão para abrir o AltStore.

Leia o **novo QR** no Windows atualizado para habilitar Treino. O QR antigo ainda conecta a câmera, mas não contém o pareamento do painel. Use **Analisar câmera do iPhone** para iniciar a sessão e **Testar som OUT** para liberar o áudio. As áreas ficam salvas no PC por câmera.

O painel usa TCP 8894, separado da câmera RTSP 8554. Um token aleatório por PC é trocado por cookie HttpOnly; o iPhone guarda o pareamento no Keychain. O motor de IA permanece em loopback. Preparar rede do PC libera o painel para a rede privada local. O transporte do painel é HTTP na LAN; use sua rede confiável, sem encaminhar essa porta na internet.

### Atualizações sem procurar o IPA novamente

Adicione uma vez ao **AltStore Classic → Sources → +**:

    https://raw.githubusercontent.com/Laikan2/tennis-vision-releases/main/altstore.json

O AltStore detecta as versões e oferece **My Apps → Update**. O usuário ainda confirma a instalação; a assinatura continua pelo AltStore e AltServer. O aplicativo não pode substituir silenciosamente seu próprio executável no iOS. Atualize sobre o app existente e mantenha a renovação semanal da assinatura gratuita.

O publicador Windows executa `scripts/altstore-source.py` após publicar a versão estável. O script confere IPA, versão, permissões, tamanho e hash do asset público antes de atualizar a fonte. Uma versão Windows sem novo iOS preserva o URL do IPA anterior.

## Distribuição e instalação

Este pacote é um **projeto Xcode**, não um instalador de iPhone. O GitHub compila para iPhone sem assinatura, executa os testes de protocolo, verifica o transporte contra MediaMTX e compila/abre o simulador. Uma compilação sem assinatura não pode ser instalada diretamente no iPhone.

### Tenho somente Windows

Use o arquivo separado **Tennis-Vision-Camera-iOS-v0.2.0-AltStore.ipa**, disponível no painel Windows em **Conectar celular → iPhone / iOS** e na página de versões do projeto. Ele contém a compilação para iPhone físico, preparada para assinatura pessoal pelo AltStore Classic.

1. Instale AltServer e AltStore Classic conforme o [guia oficial para Windows](https://faq.altstore.io/altstore-classic/how-to-install-altstore-windows). Você informa sua conta Apple diretamente no AltServer.
2. Coloque o `.ipa` no app Arquivos do iPhone. Com o AltServer aberto no PC e o iPhone conectado, use **AltStore Classic → My Apps → +** para selecionar o arquivo.
3. Use **My Apps → Refresh All** antes de 7 dias. Uma conta Apple gratuita permite esse teste, com renovação semanal; não é preciso comprar um Mac nem uma assinatura Apple Developer.

O IPA não instala apenas tocando nele no Safari. AltStore Classic e TestFlight são caminhos diferentes. O aplicativo ainda não está publicado no TestFlight ou App Store. A instalação pessoal e a câmera ainda precisam ser conferidas no seu aparelho.

### Instalar pelo Xcode no Mac

Para instalar no seu próprio iPhone:

1. No Mac, instale o Xcode e descompacte este projeto.
2. Abra **TennisVisionCamera.xcodeproj**.
3. Em **Xcode → Settings → Accounts**, adicione sua conta Apple.
4. No target **TennisVisionCamera → Signing & Capabilities**, escolha sua equipe e mantenha **Automatically manage signing**. Se necessário, altere o Bundle Identifier para um identificador seu.
5. Conecte o iPhone por USB, confie no computador e ative o **Modo de Desenvolvedor** quando solicitado pelo iOS.
6. Escolha o iPhone como destino e clique em **Run**.

Uma conta Apple pessoal permite teste pelo Xcode, com limites e perfis temporários. Para distribuir por **TestFlight ou App Store**, é necessária inscrição no Apple Developer Program, configuração no App Store Connect, assinatura e revisão da Apple. Nenhuma assinatura, compra ou publicação é feita automaticamente por este projeto.

Referências: [conta Apple](https://developer.apple.com/help/account/basics/about-your-developer-account/) e [distribuição](https://developer.apple.com/documentation/xcode/distributing-your-app-for-beta-testing-and-releases).

## Conectar ao Windows

1. No Tennis Vision Studio, abra **Conectar celular → iPhone / iOS**.
2. Deixe PC e iPhone na mesma rede Wi-Fi. No Windows, use **Preparar rede do PC** se a conexão local estiver bloqueada.
3. No iPhone, permita **Câmera** e **Rede local**.
4. Leia o QR **iPhone / iOS** ou informe IP do PC, porta **8554** e caminho **iphone**.
5. Toque em **Transmitir ao Windows**. Quando o PC mostrar **Vídeo recebido**, clique em **Conectar e iniciar vídeo**.
6. Com um Galaxy adicional, cadastre as duas câmeras e selecione ambas ao iniciar a sessão. Os caminhos **iphone** e **galaxy** são independentes; as áreas continuam salvas por câmera.

O QR Android usa SRT e não serve para este app. O aplicativo também aceita o link de pareamento tennisvision://pair com transporte rtsp.

## Captura e atraso

- Padrão: **melhor perfil automático**, sem áudio. Usa a maior taxa anunciada pela câmera traseira e prefere 1080p na mesma taxa. O modo manual mantém sua escolha.
- A lista usa taxas realmente anunciadas pela câmera traseira. 120/240 FPS só são oferecidos quando aparecem nos formatos de captura.
- O H.264 exige hardware, sem reordenar quadros. Em automático, uma falha de captura/codificação ou seis amostras abaixo de 65% da taxa após aquecimento ajustam para o próximo perfil, com motivo visível. Falha de rede não é tratada como câmera incompatível. O modo manual não altera a taxa. Temperatura crítica interrompe a transmissão.
- Captura, codificação e envio têm FPS medidos separadamente. Envio não comprova a taxa de decodificação ou análise no PC.
- A fila aceita até 8 quadros, 2 MB e 80 ms. Ao descartar vídeo, espera um novo quadro completo antes de enviar quadros dependentes. Uma escrita parada por 1 segundo força reconexão.
- Exposição curta, boa iluminação, câmera firme e foco correto reduzem o borrão. Exposição manual aceita somente valores suportados pelo perfil ativo.
- A transmissão para em segundo plano. Não há captura oculta nem acesso ao microfone.

**Limites:** compilação em Mac não comprova 120/240 FPS sustentados no iPhone físico. É preciso medir câmera, H.264, rede, aquecimento e PC juntos. Uma câmera única não garante precisão arbitrária de impacto ou decisões próximas à linha.

## Testes

No Mac, execute:

    swift test --parallel
    python3 project.py
    xcodebuild -project TennisVisionCamera.xcodeproj -scheme TennisVisionCamera -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build

TransportSmoke produz vídeo sintético para o receptor RTSP local ios-ci. Seu codificador admite software **apenas no teste**; o app exige hardware. A pipeline GitHub guarda logs e screenshot do simulador. Dados pessoais, certificados e pareamentos não entram neste ZIP.
