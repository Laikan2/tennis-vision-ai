# Tennis Vision Camera para iPhone — 0.6.5

**Novas vozes IN/OUT:** geradas no ElevenLabs, incluídas no app e disponíveis sem internet. Em **Ajustes → Impactos**, o som OUT preserva a configuração anterior e o som IN é opcional. Os testes separados mostram reprodução, volume de mídia e saída de áudio, sem alterar contagens. Confira Bluetooth e volume se não ouvir. O detector mantém a base 0.6.4.

Aplicativo nativo SwiftUI para iOS 17.4 ou posterior. **Treino no próprio iPhone** é a tela inicial: câmera, bola verde, rastro, impactos candidatos, IN/OUT, som e histórico executam localmente. Não exige Windows, Larix, servidor, HTTPS, download de modelo ou internet. O modo de câmera/painel do Windows continua disponível em Ajustes como opção separada.

## Mesma versão de funcionalidades do Android

A versão iPhone 0.6.5 acompanha a Android 0.6.5, mantendo os recursos de detecção da base 0.6.4. Inclui as melhorias da bola pequena/distante, mão/oclusão e recuperação de rastreamento, mais quatro cantos, área livre, lupa no ajuste, toque nas marcas para revisar, tela cheia, enquadramento, contagens, histórico e atualização pelo GitHub. Aparência nativa, câmera, FPS e instalação dependem da plataforma; isso não significa desempenho idêntico.

`mobile/shared/release.json` define a entrega comum. A geração do projeto verifica as versões das duas plataformas. `mobile/shared/vision-cases.json` define 41 cenários de pixels conhecidos consumidos pelos testes Kotlin e Swift, cobrindo mão, soltura, queda, movimento rápido, borrão e IN/OUT. Alterações futuras de comportamento precisam ser portadas e passar nesses testes; o contrato não traduz código Kotlin para Swift automaticamente.

O publicador conjunto exige APK/IPA com a mesma versão, certificado Android anterior, contrato embutido no iPhone, build/testes nativos aprovados e IPA igual ao artefato daquele CI. Só anuncia a entrega e atualiza o AltStore após verificar os downloads públicos. O latest do Windows é preservado.

## Treinar no iPhone

1. Abra o app e permita a câmera. Espere a prévia e confira os FPS recebidos.
2. Fixe o aparelho. Em **Ajustes → Captura e distância**, escolha 720p para fluidez ou 1080p para detalhe; lentes e taxas vêm da câmera real. O padrão continua **Até 60 FPS · estável**. Há opção de maior taxa e 120/240 quando anunciadas pela lente/resolução. A taxa recebida/analisável pode ser menor; uma escolha manual indisponível informa erro e não é trocada silenciosamente. Isso não comprova detecção de saques a 200 km/h.
3. Use **Marcar área → Quadra inteira / Meia quadra** e toque nos quatro cantos em volta do piso, em ordem. Arraste os cantos com a lupa para corrigir; use **Tela cheia** para ampliar o editor. **Área livre** cria um retângulo por arraste. A reta **Quarto → limite do piso** é uma referência separada para testes domésticos.
4. Salve e inicie o treino. A busca continua na imagem inteira para observar também bolas fora. A área é usada para classificação, não como recorte da busca. Deixe margem além das linhas.
5. **IN**: contato candidato dentro ou tocando a linha. **OUT**: fora com margem suficiente e som incluído. **REVISAR**: detalhe, contato ou linha incertos; não toca OUT automático. **SEM ÁREA**: não há polígono válido para classificar.
6. Toque numa marca ou em **Último contato** para conferir o quadro mais próximo do instante estimado, com a marca na imagem, e confirmar IN/OUT ou descartar. A revisão preserva a decisão original. Ajustes permite esconder as marcas sem apagar o histórico, testar o som e exportar JSON. O acerto usa o mesmo cálculo inteiro do Android, apenas IN e OUT no denominador.

Áreas e sessões ficam no aparelho, separadas por lente, resolução e rotação. Ao reabrir, confirme se a área ainda coincide com o piso. Pequenas translações com referências suficientes podem ser compensadas; perda de referência pausa o treino. Girar ou deslocar o iPhone exige conferir os cantos. Bloquear a tela ou ir para segundo plano interrompe a captura; a retomada exige iniciar o treino.

O detector usa cor, forma, movimento e continuidade temporal; não houve treinamento de uma rede neural nesta atualização. Contorno e rastro são desenhados sobre o mesmo quadro analisado. Uma previsão é indicada explicitamente e expira após uma perda curta. Bola parcialmente oculta, na mão, borrada ou minúscula não certifica contato nem velocidade. A velocidade disponível é **lateral e estimada pelo diâmetro aparente da bola de tênis de 67 mm**, não uma medição 3D ou radar.

Uma câmera só não comprova contato real em todas as perspectivas. A área de quadra deve corresponder ao piso; um retorno no ar ainda pode parecer uma quicada. O modo parede mantém contatos para revisão. Boa luz, foco, bola suficientemente grande na imagem e visão do chão são necessários; nenhuma opção recupera detalhe que a câmera não captou.

## Validação

O núcleo Swift tem testes de bola pequena com bordas amostradas e perda de croma, oclusão por dedos, bola carregada, falsos reflexos, timestamps, previsão limitada, quicadas e classificação conhecida IN/OUT. O GitHub compila para aparelho e simulador e testa a transmissão H.264 separadamente.

Em **Ajustes → Testes do detector**, o mesmo núcleo executa imagens sintéticas no iPhone e salva `vision-diagnostics.json`. Esses testes não usam a câmera, não alteram as sessões e não comprovam partidas reais. A compilação ou o screenshot do simulador também não comprovam a câmera física. Confira o relatório de entrega para resultados efetivamente medidos no aparelho.

Para diagnóstico por ferramentas de desenvolvimento, `--vision-diagnostics` executa esses casos, e `--vision-camera-check` analisa pixels reais por 20 segundos e salva apenas métricas em `camera-validation.json`, sem incluir imagens ou eventos na sessão. A medição processamento→tela começa no callback da câmera; não inclui o tempo de exposição/leitura anterior nem comprova a atualização do painel físico.

## Câmera e painel Windows no iPhone — opcional

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

No app, **Atualizações → Buscar atualização → Instalar atualização pelo AltStore** abre o importador do AltStore diretamente com o IPA oficial, usando o deep link de instalação implementado pelo próprio AltStore. Há botão da fonte e download do IPA como alternativas. A confirmação/assinatura continua no AltStore. O botão não instala automaticamente ao consultar a versão.

O publicador Windows executa `scripts/altstore-source.py` após publicar a versão estável. O script confere IPA, versão, permissões, tamanho e hash do asset público antes de atualizar a fonte. Uma versão Windows sem novo iOS preserva o URL do IPA anterior.

## Distribuição e instalação

O projeto Xcode e o instalador `.ipa` são arquivos separados. O GitHub compila para iPhone sem assinatura, executa testes do núcleo/protocolo, verifica o transporte contra MediaMTX e compila/abre o simulador. Uma compilação sem assinatura precisa ser assinada pelo AltStore antes da instalação.

### Tenho somente Windows

Use **Tennis-Vision-Camera-iOS-v0.6.4-AltStore.ipa** da fonte oficial ou da página de versões. Ele contém a compilação para iPhone físico, preparada para assinatura pessoal pelo AltStore Classic. Painéis Windows antigos podem oferecer um IPA antigo incorporado naquela versão.

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
