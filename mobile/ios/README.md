# Tennis Vision Camera para iPhone — 0.1.0

Aplicativo nativo SwiftUI para iOS 17.4 ou posterior. A câmera traseira transmite H.264 por RTSP/TCP ao Tennis Vision Studio no Windows. Bola verde, rastro, impactos, IN/OUT e estatísticas são analisados no PC. A câmera nativa não precisa de Larix ou HTTPS.

## Distribuição e instalação

Este pacote é um **projeto Xcode**, não um instalador de iPhone. O GitHub compila para iPhone sem assinatura, executa os testes de protocolo, verifica o transporte contra MediaMTX e compila/abre o simulador. Uma compilação sem assinatura não pode ser instalada diretamente no iPhone.

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

- Padrão solicitado: **1080p / 60 FPS / 12 Mbps**, sem áudio.
- A lista usa taxas realmente anunciadas pela câmera traseira. 120/240 FPS só são oferecidos quando aparecem nos formatos de captura.
- O H.264 exige hardware, não reordena quadros e opera em tempo real. O perfil pode ser recusado pelo dispositivo ou interrompido por temperatura; o app explica e **não troca silenciosamente para 60 FPS**.
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
