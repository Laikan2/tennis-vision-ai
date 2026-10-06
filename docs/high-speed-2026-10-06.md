# Alta velocidade e conexão · 6 de outubro de 2026

Android 0.5.5 / Windows 0.6.4.

## Problemas encontrados

O aviso no Android 0.5.4 acionava uma recuperação que selecionava e salvava 60 fps. O monitor de captura podia considerar o último callback da prévia durante a troca para a sessão de alta velocidade, sem respeitar o tempo de inicialização já concedido ao H.264. Também ignorava frames efetivamente codificados como evidência de que a câmera continuava funcionando.

O vídeo enviado mostra o celular transmitindo a cerca de 60 fps e o painel sem sessão. No estado do Windows examinado, a câmera Galaxy estava cadastrada, mas a análise não estava iniciada. Publicar no receptor não iniciava a sessão no painel; faltava um passo no fluxo.

Sem o aparelho conectado por ADB, não foi possível determinar se há também uma falha específica do driver Samsung nos modos de 120/240 fps. A bateria a 1% na imagem não comprova a causa do problema.

## Correções

- A troca para gravação reinicia o monitor de captura e recebe uma janela inicial de oito segundos. A ausência de callbacks do sensor não força recuperação se o H.264 continua produzindo vídeo. O encoder mantém seu monitor separado para detectar uma falha real.
- O app reinicia o mesmo modo de alta velocidade uma vez. Uma segunda falha encerra a transmissão e mantém o perfil salvo. A escolha de reduzir FPS fica com o usuário.
- O pedido inicial da sessão já contém a faixa fixa de FPS. Os mesmos parâmetros são fornecidos antes de configurar o sensor e no pedido de captura. Os pedidos pendentes da prévia são cancelados na transição. Isso segue a orientação para evitar mudanças caras de parâmetros após configurar a sessão. [SessionConfiguration do Android](https://developer.android.com/reference/android/hardware/camera2/params/SessionConfiguration#setSessionParameters(android.hardware.camera2.CaptureRequest)).
- A conexão SRT começa depois que a sessão foi configurada e o H.264 produziu vídeo. Os logs de câmera registram resolução, faixa de FPS, câmera selecionada e erros, sem URLs de transmissão.
- O Windows verifica o vídeo publicado por uma consulta RTSP local, com limite de tempo e tamanho de resposta, sem abrir um decoder adicional. Porta aberta não é apresentada como vídeo recebido.
- **Conectar e iniciar vídeo** registra ou reutiliza a câmera Galaxy e inicia a sessão. A ação é idempotente para uma sessão que já contém essa câmera e não encerra uma sessão de outra câmera. Áreas, credenciais e configurações existentes são preservadas.
- A ação de iniciar fica junto do estado do receptor. A janela abre no QR code, sem deslocar a rolagem para um campo somente de leitura.

## Validação

- Android: 77 testes JVM/Robolectric, incluindo a preservação do FPS escolhido e salvo após falha em 120 e 240. APK release compilado; Lint sem erros, com 71 avisos, em sua maioria textos e internacionalização já existentes.
- Backend: 211 testes. Incluem handshake RTSP fragmentado, resposta sem vídeo, autenticação recusada, limite de resposta, tempo máximo de espera, preservação do cadastro/área e conexão sem duplicação.
- Interface: TypeScript e testes de reprodução, dados de impactos e áudio. Desktop: 20 testes de relay, empacotamento e políticas de atualização.
- Ensaio de transmissão separado dos dados do usuário: FFmpeg gerou H.264 1280×720 por SRT para um MediaMTX em portas próprias de localhost. O decoder do projeto recebeu 300, 599 e 1200 frames em cinco segundos por perfil, aproximadamente **60,0 / 119,8 / 240,0 fps**. Havia timestamps da fonte nos três perfis. Não houve limite de 60 fps no receptor/decoder.
- O APK mantém o certificado SHA-256 `faf843630ad0a52fef6ca4f844cae592e3da1e266b7b823f481c34d3b2049ce9`, permitindo atualizar a instalação existente. O fluxo de atualizações GitHub permanece ativo.
- O programa Windows compilado foi aberto em uma pasta de teste vazia, sem usar os dados da instalação existente. Reconheceu a transmissão SRT, iniciou captura e análise, reutilizou a mesma sessão ao conectar novamente e preservou a câmera e sua área entre sessões. O download de APK pelo painel correspondeu ao hash do Android 0.5.5. Esse teste confirma o fluxo de conexão; não mede 240 análises de visão por segundo.
- Na interface compilada, o QR code e a ação de conectar permaneceram visíveis. O clique exibiu “Celular conectado ao painel. Vídeo e análise iniciados” e não duplicou a sessão.

No ensaio completo com análise ativa, a amostra final registrou aproximadamente 39,3 fps decodificados e 1,1 fps analisados para o padrão artificial colorido, enquanto a reprodução direta H.264 mostrou cerca de 176 fps no navegador de teste. São métricas de etapas diferentes, medidas sob carga, e não taxas garantidas para uma partida. O teste de conexão usa progresso efetivo de captura/análise, sem exigir um pico instantâneo de 120 fps; o ensaio separado de transporte mede o decoder por cinco segundos. Nenhum desses testes comprova 240 fps de análise ou captura física.

Para repetir o ensaio do transporte, execute `scripts/validate-mobile-transport.py` com `--ffmpeg` apontando para um executável compatível com SRT e `--output` para o relatório JSON. As portas padrão isoladas são RTSP 18554 e SRT 18890.

## Teste físico pendente

O ensaio sintético valida transporte e decodificação no PC. Ele não mede o sensor, encoder, Wi-Fi ou temperatura do S25 Ultra, nem garante 240 fps sustentados no celular. Nenhum aparelho foi encontrado por ADB nesta revisão. Confirmar 120/240 fps na câmera real exige instalar o APK, transmitir e acompanhar captura, H.264 e envio; com USB autorizado, os logs podem localizar qualquer falha restante do driver.
