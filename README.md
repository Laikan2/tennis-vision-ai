# Tennis Vision AI

MVP local de captura e análise de tênis: Python/OpenCV/FastAPI, SQLite, Next.js/React/TypeScript, Tailwind e Framer Motion. **Não há dados demonstrativos no dashboard.**

## Aplicativos prontos

Windows **0.6.3** e Android **0.5.4**: [downloads e atualizações](https://github.com/Laikan2/tennis-vision-releases/releases/tag/v0.6.3). O Windows empacotado inclui o motor, painel e receptor e não exige Node/Python instalados. Consulte [como começar no Windows](docs/windows-studio.md), [atualizações Android](docs/android-updates.md) e a [revisão com resultados e limites reais](docs/revisao-2026-10-05.md).

## Executar no Windows

Requisitos: Python 3.12, Node.js 20.9+ e pnpm. A câmera deve estar acessível na rede do computador. O setup instala o suporte YOLO e baixa `yolo11n.pt` (~5 MB) para identificar pessoas e a classe genérica `sports ball`.

```powershell
# Na pasta tennis-vision-ai:
.\scripts\setup.ps1
.\scripts\start.ps1
```

Abra [o dashboard local](http://127.0.0.1:3000). A API fica em [FastAPI /docs](http://127.0.0.1:8000/docs). O script de configuração gera uma chave Fernet no `.env` sem exibi-la. Preserve essa chave para conseguir abrir as credenciais salvas.

### Aplicativo Galaxy S25 Ultra/Android sem Larix

O aplicativo nativo já está compilado em `mobile/releases/Tennis-Vision-Camera-Android-v0.5.4.apk` e pode ser baixado também pelo botão **Baixar aplicativo** no painel. Envie o APK ao Galaxy por USB ou Quick Share e instale-o sobre a versão existente. O código-fonte está em `mobile/android`.

Ele captura a câmera traseira, usa o codificador H.264 do celular e publica por SRT direto no MediaMTX do computador. Execute `LIBERAR-CAMERA-CELULAR.bat` uma vez, mantenha os aparelhos na mesma rede e abra **Conectar câmera → Câmera Tennis Vision → Preparar**. Leia o QR dentro do aplicativo e toque em **Transmitir para o PC**. No painel, clique em **Adicionar ao painel** e inicie a sessão. O app nativo não exige HTTPS para acessar a câmera.

O pareamento começa em 1080p60. Em **Captura → Qualidade**, o app também oferece 120/240 fps quando a câmera e o codificador H.264 anunciam suporte à mesma resolução e taxa. A captura usa Camera2 diretamente na superfície do codificador, preserva os timestamps e mostra separadamente FPS capturados, codificados e enviados. O receptor não aplica um limite fixo de 60 fps. Mantenha o app aberto durante o treino.

A versão 0.4 organiza o app em **Conexão, Captura e Rede**, com tela cheia, grade de enquadramento, zoom antes de transmitir e lanterna quando disponível. Nos perfis comuns, **Congelar bola** oferece 1/1000, 1/2000 ou 1/4000 s, além de foco por toque e trava de foco. Em alta velocidade, foco e exposição são automáticos. Em **Rede**, Mínimo atraso solicita buffer SRT de 40 ms e uma fila de aproximadamente 80 ms; Equilibrado e Mais tolerante toleram mais oscilação. Após descarte, o envio retoma por um quadro-chave para evitar uma sequência H.264 sem referência. O app exibe exposição, ISO, temperatura da bateria e diagnóstico copiável.

O detector prioriza tênis verde: procura bolas muito pequenas em até quatro recortes dos pixels originais, inclusive sem trajetória anterior; confirma movimento e aparência, e verifica tanto o contexto quanto o centro de propostas borradas. A prévia JPEG é comprimida somente com demanda e até 60 fps por padrão (`TV_PREVIEW_MAX_FPS`); isso não limita a captura ou o WebRTC e evita gastar CPU comprimindo 240 JPEGs por segundo. Impactos, IN/OUT e velocidades continuam sujeitos à evidência e à incerteza da câmera.

A versão 0.4.1 corrige a exposição/ISO ao trocar o obturador, callbacks antigos após fechar a câmera e a recuperação do H.264 depois de reconectar. O painel permite calibrar cada câmera na resolução original, recupera a reprodução quando o WebRTC falha e volta a carregar o áudio OUT após uma falha de rede. O rastreador preserva a posição atual da pessoa mesmo entre inferências do modelo. Consulte os detalhes e a validação em `docs/mobile-app.md`.

A captura e a transmissão em 120/240 fps no S25 Ultra ainda precisam de teste físico: o suporte anunciado pelo hardware não garante taxa sustentada ou atraso total de 40 ms. O aplicativo iOS permanece separado. Consulte `mobile/INSTALAR-APP-ANDROID.txt` e `mobile/android/README.md`.

No computador, nada muda no uso diário: `ABRIR.bat` inicia o receptor, a IA e o painel. O celular fornece os frames; detecção da bola, impacto, velocidade, IN/OUT, replays e estatísticas continuam sendo calculados e exibidos no PC.

### Alternativa temporária pelo navegador

O projeto inclui uma câmera web própria que transmite direto do Chrome para o MediaMTX deste computador, sem conta e sem servidor externo.

1. Deixe o celular e o computador no mesmo Wi-Fi. Execute `LIBERAR-CAMERA-CELULAR.bat` uma vez e aceite a elevação do Windows; ele libera somente as portas da câmera no perfil de rede privada e apenas para a sub-rede local.
2. Inicie o Tennis Vision com `ABRIR.bat` ou `scripts\start.ps1`.
3. No painel, abra a câmera atual ou **Conectar câmera**, escolha **Câmera Tennis Vision** e clique em **Preparar**.
4. No Galaxy S25 Ultra, leia o QR Code com a câmera/Google Lens. No Chrome, permita câmera e microfone quando solicitado, escolha a lente traseira e toque em **publish**. A página precisa dessa permissão inicial para listar os dispositivos; o perfil gerado não transmite áudio.
5. No computador, clique em **Adicionar ao painel** e inicie uma sessão.

O endereço é gerado com o IP local atual e solicita H.264, 1920 × 1080, 60 fps e 12 Mbps. Esses valores são solicitações: o Chrome e o aparelho podem escolher limites menores. Se o computador tiver mais de uma rede e o QR Code mostrar o IP errado, defina `TV_LAN_IP` no `.env` e reinicie o projeto.

O Galaxy publica no caminho `/galaxy`. O iPhone com Larix usa `/iphone`, então os dois podem transmitir simultaneamente e entrar na mesma sessão como câmera 1 e câmera 2.

Esse modo usa MoQ/WebTransport local para publicar e RTSP apenas dentro do computador para alimentar a análise. No iPhone, o Safari exige HTTPS com certificado confiável para esta captura; use um domínio/VPN com HTTPS confiável ou mantenha o Larix até configurar esse certificado.

### iPhone com Larix

1. Deixe o iPhone e o computador na mesma rede Wi-Fi e execute `scripts\start.ps1`. O script inicia também o servidor RTSP local MediaMTX na porta `8554`.
2. No Larix Broadcaster, use uma conexão RTSP com a URL `rtsp://IP-DO-COMPUTADOR:8554/iphone` e modo **Record/Publish**. No perfil de vídeo, escolha H.264, 1920 × 1080, 60 fps, bitrate entre 10 e 16 Mbps e intervalo de quadro-chave de 1 segundo. Desative B-frames se essa opção aparecer.
3. Inicie a transmissão no Larix. No painel, cadastre o IP do computador, porta `8554` e caminho `/iphone`, sem usuário e senha.

A prévia do próprio Larix sempre responde primeiro porque ainda não passou pela rede. Por padrão, o painel usa o H.264 do MediaMTX diretamente por WebRTC para uma visualização mais fluida; nesse modo a imagem pode chegar antes da análise. Ative **Alinhar vídeo à IA** para entregar a imagem e as marcações do mesmo frame analisado, com fluidez limitada pela taxa da análise. A captura e o modelo de postura usam somente o frame mais novo, sem acumular uma fila de inferências.

Alternativa manual, em dois terminais:

```powershell
.venv\Scripts\python -m uvicorn backend.api:app --host 127.0.0.1 --port 8000 --no-access-log
```

```powershell
cd frontend
pnpm dev
```

## Primeiro uso

1. **Conectar câmera:** informe nome, IP, porta, usuário, senha e caminho RTSP. O padrão de caminho é `/Streaming/Channels/101`; confirme-o para o firmware instalado. O cadastro não implica uma conexão validada.
2. **Nova sessão → Iniciar análise:** selecione uma ou duas câmeras. Para duas pontas da quadra, cadastre a câmera da primeira linha de fundo como **Lado A** e a câmera oposta como **Lado B**; o Lado B gira o mapa em 180° para ambas alimentarem a mesma quadra 2D. Informe também se o jogador é destro ou canhoto. O backend cria um analisador independente para cada stream, tenta conectar, decodifica frames e reconecta em caso de falha. A interface mostra FPS observado e resolução recebida de cada câmera. Sem stream, ela permanece sem sinal.
3. **Bola e escala:** use o seletor acima de cada vídeo para escolher 67 mm para tênis ou 40 mm para pingue-pongue. A alteração é salva naquela câmera e vale imediatamente para as próximas medições. No modo Ambiente, esse diâmetro permite uma velocidade visual aproximada. O diâmetro errado altera a velocidade; não é uma configuração de cor. Para melhorar a escala, abra “Calibre antes de medir”, marque dois pontos cuja distância você conhece e informe essa distância.
4. **Superfície opcional:** comece com **Imagem inteira**, sem selecionar área ou calibrar. Há três seleções: **Arrastar área** para um retângulo, **Marcar 4 cantos** para parede/trapézio e **Encaixar modelo da quadra**. O modelo projeta linhas de duplas, simples, rede e caixas de saque; depois escolha a zona válida (quadra inteira, simples, metade ou caixa de saque). Ajuste e salve os quatro cantos separadamente em cada câmera; a área permanece vinculada àquela câmera. A bola continua sendo detectada na imagem inteira: uma chegada fora da zona gera o alerta VAR. Os quatro cantos definem um alvo 2D em perspectiva, não uma escala física 3D.
5. **Bola, esqueleto e lado do golpe:** cor, movimento, modelo geral e rastreamento iniciam a busca. Após cinco observações contínuas e coerentes, uma assinatura visual automática por câmera passa a reconhecer a aparência da bola sem pedir sua cor. Objetos parados, saltos entre detalhes do fundo e trajetórias quebradas não entram nessa memória. O YOLO11n-pose detecta articulações em uma tarefa independente da bola; fluxo óptico atualiza os pontos entre inferências. Só os pontos visíveis e confiáveis aparecem. Enquadre o corpo, os ombros, o punho dominante e a bola para ver o esqueleto e obter a estimativa **forehand/backhand**. Essa classificação usa a posição relativa entre punho, ombros e bola; ela não reconhece a raquete e pode ficar como não classificada quando a evidência é fraca. A opção **Esqueleto** liga/desliga o desenho.
6. **Replay:** marque um momento pelo botão de vídeo. O sistema preserva até 5 s anteriores + 3 s posteriores, com timestamps e trajetórias. Veja em “Replays”, com reprodução 0.25×/0.5×/1× ou quadro a quadro.
7. **Treinar IA:** o aprendizado de aparência ocorre durante os arremessos, sem ação manual. A tela **Treinar IA** mostra as amostras automáticas. A revisão é opcional: para corrigir um caso difícil, salve um quadro, desenhe um retângulo justo e escolha **É a bola** ou **Não é bola**. Esse segundo modelo usa somente correções humanas e permanece separado da assinatura automática.
8. **Sessões:** encerre e exporte eventos como CSV ou JSON. Os campos indisponíveis permanecem nulos/vazios.

9. **Resumo do treino:** abaixo das câmeras aparecem impactos dentro/fora, alertas VAR, presença da bola nos frames, velocidade estimada, pico, média, forehand/backhand, setores da quadra, histórico em barras e mapa numerado. Um contato candidato abre **OFFICIAL REVIEW**; a posição recebe IN, OUT ou REVISAR conforme a área, o tamanho da bola e a incerteza da localização. **Som OUT** usa um arquivo de voz local; clique em **Testar som OUT** para verificar o volume e liberar o áudio no navegador. A chamada considera todos os eventos da sessão, inclusive os vistos apenas pela segunda câmera. Com duas câmeras, aguarda a janela de fusão e evita repetição ou OUT de uma decisão conflitante. O mapa expande a visualização para manter as marcas fora visíveis. A taxa de frames com bola não mede precisão. Rastro e velocidade de cada impacto permanecem no vídeo; o contato físico continua candidato e pode ser conferido no replay.

## Modelo especializado

### Aprendizado automático e revisão opcional

O detector combina cor/movimento, modelo geral, memória visual automática, uma rede pequena opcional treinada com correções e rastreamento temporal. A assinatura automática descreve HSV sem assumir verde ou branco, é isolada por câmera e só aprende de caixas medidas em uma trajetória coerente. O modelo de esqueleto continua separado.

- Seis amostras confiáveis ativam a assinatura em `data/learning/automatic`. Uma amostra isolada incompatível é rejeitada; uma sequência consistente de oito aparências novas permite trocar de bola sem informar a cor.
- A assinatura automática influencia associação e recuperação perto da posição prevista, mas não cria rótulos no conjunto neural revisado. Assim, um erro automático não contamina silenciosamente a validação humana.

- A coleta automática prioriza perda de acompanhamento e exemplos incertos, com até 40 quadros por sessão e 120 pendentes. Pode ser desligada na tela **Treinar IA**.
- Nenhuma previsão vira rótulo do modelo neural automaticamente. Somente recortes revisados entram nesse treinamento; a imagem completa é removida após a revisão e o recorte é preservado localmente.
- Após 8 novos exemplos revisados, o treino automático ocorre quando não há sessão ativa. Durante o treino, iniciar a câmera fica temporariamente bloqueado para evitar disputa de CPU. Também existe o botão de treino manual.
- Novas sessões são separadas integralmente entre treino e validação. Os dados iniciais usam trechos sem sobreposição das gravações do quarto; não equivalem a uma avaliação em uma quadra ou câmera diferente.
- O modelo candidato precisa atingir precisão ≥90% e recall ≥85% nos recortes de validação e preservar o F1 e a precisão do modelo anterior, medidos novamente nos mesmos exemplos. Se falhar, o modelo anterior permanece ativo. Isso reduz regressões, mas não garante melhora em qualquer cena nova.
- Os exemplos ficam em `data/learning/examples.json`, os pesos versionados em `data/learning/models`, o modelo ativo em `active.json` e o último resultado em `last-training.json`. O modelo ativo é recarregado sem reiniciar o servidor.

A prévia sincronizada usa `/api/video` por WebSocket: envia JPEG e marcações do mesmo quadro e espera a confirmação do navegador após decodificar. Um cliente lento recebe o quadro mais recente no próximo envio. Há fallback para `/api/frame`. A resolução de exibição é preservada; apenas a busca de propostas usa uma cópia menor (768 px de largura por padrão), com verificação dos recortes na resolução original.

O decodificador usa duas threads por padrão (`TV_DECODER_THREADS=2`), em vez do automático que escolhia 16 neste PC. A decodificação em paralelo por quadros pode acrescentar atraso, conforme a [documentação do FFmpeg](https://www.ffmpeg.org/doxygen/trunk/multithreading_8txt.html). Isso não limita o FPS enviado pela câmera. A latência exibida ainda exclui o tempo de exposição, codificação no celular e rede RTSP; não representa latência total.

Velocidade usa observações reais, escala pelo lado curto da bola quando borrada, ajuste robusto e janelas separadas antes/depois de uma reversão. Impactos rejeitam lacunas longas e profundidade estimada de caixas excessivamente alongadas. Com uma câmera, contatos e velocidade 3D continuam sendo estimativas; o treinamento de aparência não valida essas grandezas físicas.

[Registro do treinamento e verificação de 16/09/2026](docs/training-2026-09-16.md).

### Modelo YOLO externo

```powershell
.venv\Scripts\python -m pip install -r backend\requirements-ml.txt
```

No `.env`:

```dotenv
TV_MODEL_PATH=C:/modelos/tennis-ball.pt
TV_MODEL_CLASS=0
TV_BALL_CLASS=32
TV_PERSON_CLASS=0
TV_PERSON_CONF=.6
TV_DEVICE=auto
TV_MODEL_INTERVAL=12
TV_MODEL_CONF=.24
TV_ANALYSIS_MAX_WIDTH=640
TV_RTSP_TRANSPORT=tcp
```

Use pesos locais confiáveis compatíveis com Ultralytics. `TV_MODEL_CLASS` deve corresponder à classe bola de tênis do seu dataset. O `setup.ps1` baixa o `yolo11n.pt` geral para `data/models` quando não há um modelo especializado; ele reconhece pessoas e a classe COCO `sports ball`, mas uma bolinha de pingue-pongue pode exigir pesos treinados com imagens da sua câmera. `TV_MODEL_INTERVAL=12` reduz o custo do modelo de pessoas no CPU; o detector leve da bola continua rodando em cada frame analisado. `TV_ANALYSIS_MAX_WIDTH=640` reduz somente a cópia usada pela visão e preserva o preview em 1920 × 1080. Passes focais em resolução nativa usam a cor inicial e, quando pronta, a assinatura automática perto da posição prevista. Use 768 ou 960 somente se a bola ficar pequena demais na primeira aquisição e houver processamento disponível. `TV_MODEL_CONF` mantém a busca sensível e `TV_PERSON_CONF` evita caixas de pessoa com baixa confiança; `TV_RTSP_TRANSPORT=udp` pode reduzir mais o atraso em uma rede local estável. `auto` seleciona CUDA se o PyTorch instalado oferecer suporte; `cpu` força CPU e `gpu` exige CUDA. Confiança do modelo é um score, não uma probabilidade empiricamente calibrada.

O `setup.ps1` também baixa `yolo11n-pose.pt` para `data/models`. `TV_POSE_PATH` aceita outro caminho local compatível, ou uma string vazia para desligar postura. Quando a postura está ativa, ela fornece as caixas de pessoa e evita executar o modelo geral de pessoas na mesma tarefa da bola. Sem o modelo de postura, o detector geral continua disponível, sem inventar articulações. Caixas de depuração ficam nas opções de diagnóstico.

## Estado real da implementação

- **Captura:** implementação RTSP/OpenCV, reconexão, timeout de leitura, fila limitada ao frame mais novo, preview separado da inferência, FPS observado, frames recebidos/processados/descartados e erros sanitizados.
- **Detecção:** adaptador YOLO, propostas temporais/cromáticas e assinatura visual automática por câmera. A lógica rejeita reflexos pequenos, pausa durante movimento global do celular e só atualiza a assinatura com movimento coerente. A sequência fornecida ajudou a ajustar os limiares, mas ainda não substitui um ensaio controlado na câmera final.
- **Tracking:** filtro de Kalman, associação espacial, ID temporal e previsão curta. Testado com oclusões sintéticas, não validado em jogo.
- **Calibração:** homografia manual com quatro cantos, perfis persistidos e erro de pontos independentes via API. Não inclui correção intrínseca de lente nem estima automaticamente a pose.
- **Velocidade:** usa a escala calibrada quando disponível. Sem calibração no quarto, usa o diâmetro informado da bola para uma estimativa visual marcada como aproximada. `physicalKmh` continua nulo porque uma câmera única não mede a velocidade 3D real.
- **Impacto/quique:** a janela temporal detecta reversões de profundidade, curvas bruscas laterais e aproximação/perda da bola tanto dentro quanto fora do alvo. A simples entrada no retângulo não conta como contato. Quando o contato ocorre entre dois quadros, duas trajetórias compatíveis permitem estimar posição e instante com incerteza maior. Saltos para outra identidade reiniciam a evidência de impacto e velocidade. O contato permanece `candidate`; a posição usa a marca da bola e uma faixa de incerteza na borda para decidir IN/OUT/REVISAR. [Verificação das chamadas na parede](docs/wall-calls-2026-09-16.md).
- **Dashboard/histórico:** sessões, configurações, diagnóstico, replay, CSV/JSON, validação por pares externos, chamada visual/sonora IN/OUT, mapas de posição/concentração, setores da quadra e estatísticas estimadas de forehand/backhand. O pipeline não gera eventos confirmados automaticamente; a opção de incluir candidatos permite revisar suas marcas no mapa.
- **Múltiplas câmeras:** uma sessão aceita até duas capturas, com detector, tracker, preview e área próprios. Lado A e Lado B são orientados para um mapa global comum. Observações de impacto de câmeras diferentes em uma janela de 450 ms são reunidas em um único arremesso, preservando as duas posições e evitando contagem duplicada. Divergência espacial grande vira **REVISAR** em vez de uma chamada IN/OUT forçada. Isso é fusão temporal; sincronização de origem, calibração extrínseca e triangulação 3D ainda exigem um ensaio próprio.

**Ainda não implementado:** métricas físicas em voo, reconhecimento de raquete e classificação validada de golpes, saques, rallies, treino/alvos, comparação de sessões, gráfico de velocidade física, ONVIF/ISAPI para alterar a câmera, anotador visual, exportação PDF/vídeo, backups/retention automáticos e todas as configurações avançadas da especificação. Esses itens não são representados por botões fictícios.

## Medições e limitações

Homografia só é válida para o plano calibrado. Uma bola no ar não pertence a esse plano; sua projeção pode sugerir velocidades incorretas. Não use a projeção para medir velocidade de saque nem para arbitragem. Timestamps são de chegada ao host após decodificação: não são PTS sincronizados do sensor. `frameLatencyMs` mede espera local + processamento, **não** exposição-a-tela; latência da rede é desconhecida (`null`). Frames descartados são apenas descartes conhecidos da fila, não perdas RTP inferidas.

Resolução, codec e FPS desejados no cadastro não reconfiguram a câmera. Configure-os na interface da Hikvision e compare com FPS/resolução efetivamente recebidos. Detalhes de exposição, blur, bitrate, distância e iluminação precisam de inspeção no local. Veja [protocolo de validação](docs/VALIDACAO.md).

O modo sincronizado usa JPEG com metadados do próprio frame. A opção direta usa H.264/WebRTC e não depende do ritmo da IA. A fila de captura é limitada ao frame mais novo, o buffer FFmpeg é reduzido quando suportado e a postura é processada em paralelo. Buffers de replay são limitados em memória; o limite de bytes pode reduzir a duração em streams grandes. Não há limpeza automática do disco: acompanhe `data/replays` e `data/datasets`.

## Segurança e operação

- Executar apenas em loopback (`127.0.0.1`); API local sem contas/login. Não publicar diretamente na internet ou LAN. Host e Origin são verificados contra uma lista local.
- Credenciais da câmera são criptografadas no SQLite. A chave vive no `.env`, nunca no frontend. Restrinja permissões do arquivo `.env` ao usuário do sistema e proteja backups. Criptografia de credenciais não criptografa vídeos.
- Também é possível usar `TV_CAMERA_HOST`, `TV_CAMERA_USER`, `TV_CAMERA_PASSWORD`, `TV_CAMERA_PORT`, `TV_CAMERA_PATH` no ambiente. Valores secretos não são persistidos nesse modo.
- Logs em `data/logs/backend.log` não incluem a URL autenticada; log nativo verboso do FFmpeg é desativado. Falhas de inferência são registradas. A câmera deve ser configurada com conta própria e permissões mínimas.
- Encerramento salva estatísticas e replays parciais. Após queda abrupta, sessões antigas podem aparecer como abertas/interrompidas; inicie uma nova sessão. Sem recuperação automática de buffers em RAM.

## Arquitetura

`backend/camera.py` → CameraCapture + FrameBuffer → `detection.py` / `adaptive.py` → `tracking.py` → `target.py` / `calibration.py` / `speed.py` / `bounce.py` → `pipeline.py` → `multi_pipeline.py` → `api.py`.

Módulos separados: `court.py`, `trajectory.py`, `analytics.py`, `replay.py`, `fusion.py`, `security.py`, `database.py`. Interface em `frontend/app`, componentes em `frontend/components` e contratos em `frontend/lib`. Banco e mídia são criados em `data/`, ignorados pelo Git.

## Testes

```powershell
.venv\Scripts\python -m pytest backend\tests -q
cd frontend
pnpm build
```

Os testes usam fixtures artificiais isoladas e não alimentam o dashboard. Eles verificam contrato e cálculos; não comprovam precisão da câmera. Dependências resolvidas estão em `backend/requirements-lock.txt` e `frontend/pnpm-lock.yaml`.

## Referências

- [Datasheet oficial Hikvision DS-2CD1327G2-L(UF)](https://assets.hikvision.com/prd/public/all/doc/sm000041041/DS-2CD1327G2-LUF_Datasheet_20240204.pdf): conferir variantes e firmware antes de configurar.
- [Homografia e restrição ao plano — OpenCV](https://docs.opencv.org/4.5.1/d9/dab/tutorial_homography.html).

## Marcas e mapa de concentração

A quadra 2D mostra a cor da velocidade antes do quique quando ela existe; sem medição, usa cinza e não inventa km/h. Clique na marca para ver posição, horário e estado. A opção “Incluir possíveis quicadas (incertas)” permite revisar candidatos localizados, sem transformá-los em decisões confirmadas. O mapa de calor usa densidade espacial relativa: azul indica menor concentração e vermelho maior concentração. Ele não mede temperatura e sua escala não representa velocidade. Marcas no vídeo requerem posição em pixels e resolução compatível com o frame.

O detector geral `yolo11n.pt` é usado automaticamente quando presente em `data/models`, identificando pessoas e a classe genérica `sports ball`. Ele é um ponto de partida, não um modelo especializado para pingue-pongue; substitua-o por pesos treinados na sua câmera via `TV_MODEL_PATH` para reduzir falhas com bolas muito pequenas. O detector auxiliar procura bolas claras/brancas pequenas em movimento e rejeita estampas dentro da caixa da pessoa, mas sua pontuação permanece incerta. A marca de impacto é visível para revisão e não confirma automaticamente contato com a parede ou o chão.
