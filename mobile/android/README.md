# Tennis Vision para Android — treino local 0.6.7

**Correções 0.6.7:** bola amarelada pela iluminação, bola perto da lente, recuperação após obstruções sem encerrar o treino e indicação do limite do piso. Marcas antigas ficam no histórico e não são projetadas sobre uma câmera reaberta. Veja docs/releases/mobile-v0.6.7.md.

**Novas vozes IN/OUT:** geradas no ElevenLabs, incluídas no app e disponíveis sem internet. Em **Ajustes → Vozes IN / OUT**, o som OUT preserva a configuração anterior e o som IN é opcional. Use **Testar vozes** para ouvir cada chamada e conferir o volume de mídia. O teste não conta impactos. Áreas e histórico são preservados; o detector inclui as correções de iluminação e continuidade da versão 0.6.7.

O aplicativo agora abre no **treino local**. Câmera, detecção da bola verde, rastro, candidatos de impacto, IN/OUT, áudio, revisão, estatísticas e armazenamento executam no Android. Não é necessário Windows, Python, MediaMTX, pareamento, conta ou conexão com a internet para esse modo.

## Começar no celular

Android e iPhone acompanham a entrega **0.6.7**. O release conjunto `mobile-v0.6.7` oferece APK e IPA separados, preservando certificado Android e dados anteriores. Os motores Kotlin e Swift são avaliados pelos mesmos 41 cenários sintéticos e pelos testes novos de iluminação e recuperação da referência; formatos de instalação, taxas da câmera e desempenho continuam próprios de cada plataforma. Consulte `mobile/shared/release.json` e as notas `docs/releases/mobile-v0.6.7.md`.

1. Instale `mobile/releases/Tennis-Vision-Camera-Android-v0.6.7.apk` sobre a versão anterior, preservando os dados. Não desinstale para atualizar.
2. Abra o app, toque em **Permitir câmera** e aguarde **Câmera pronta**.
3. Fixe o celular na horizontal, recuado e elevado fora da área de jogo, com margem além das linhas. Em **Enquadramento e detalhe**, escolha a lente antes de marcar. **Ampla**, quando disponível, mostra mais, mas deixa a bola menor. **Bola distante · até 1080p** preserva mais detalhe; confira os FPS recebidos. Se a imagem engasgar, use **Fluidez**. Não é possível recuperar por software partes da quadra fora da imagem.
4. Em **Marcar quadra · 4 cantos**, escolha quadra inteira ou metade e toque nos quatro cantos ao redor do piso. Para meia quadra, comece pela rede; para a inteira, por um fundo. Ajuste os cantos arrastando, com a lupa, e toque em **Salvar área**. Cancelar mantém a marcação anterior. A referência pausa apenas durante a edição. **Área livre · arrastar** continua disponível. **Quarto · limite do piso** é a reta entre parede e chão usada nos testes de quarto; não substitui os quatro cantos de uma quadra e não é exigida no modo quadra.
5. Toque em **Iniciar treino**. O detector procura a bola na imagem inteira. IN significa marca dentro ou tocando a linha; OUT fica totalmente fora e reproduz o áudio incluído no APK. Posições incertas ficam em **REVISAR**, sem gritar OUT.
6. Toque numa marca ou em **Revisar último contato** para ver o quadro observado e confirmar IN, OUT ou descartar um falso impacto. A chamada original continua no registro.

**Ajustes** contém superfície, áudio, marcas, captura, nova sessão e exportação JSON. **Histórico** reúne as sessões locais. As áreas são salvas por câmera, lente/zoom, resolução e orientação; a chave da lente 1× anterior foi preservada. Ao reabrir, confira visualmente os cantos antes de retomar IN/OUT. Pequenas translações podem ser compensadas quando há fundo texturizado suficiente. Giro, mudança de perspectiva, deslocamento maior ou perda de referência pausam a contagem e exigem ajustar os cantos. Alterações locais não apagam pareamentos, perfis, sessões e gravações anteriores.

Ao bloquear a tela ou abrir outro aplicativo, a análise para e a câmera é liberada. Ao voltar, a prévia reabre e o treino precisa ser iniciado novamente. Isso evita registrar impactos enquanto o usuário não está acompanhando. As sessões usam gravação atômica; falha de armazenamento pausa a análise e informa o erro. O limite por sessão é de 2.000 contatos; sessões anteriores continuam disponíveis.

## Precisão, desempenho e serviços externos

- A 0.6.7 passou em 187 testes JVM/Robolectric e gerou APK com o certificado anterior. A câmera Android física não foi retestada nesta entrega. O [relatório conjunto](../../docs/validation/detection-2026-10-15/report.txt) separa testes controlados, medição no iPhone físico e situações ainda não verificadas em quadra.
- O detector local é visão computacional por cor, movimento, forma e continuidade temporal, especializado na bola de tênis verde. Não é o modelo neural Python do Windows nem um treinamento novo desse modelo. Objetos verdes semelhantes, reflexos, oclusão, distância e borrão ainda podem causar erros.
- Só posições observadas formam contatos. Previsões curtas são identificadas e não pontuam. Passar pela área no ar ou o sumiço da bola não prova impacto.
- No quarto, descida e subida observadas abaixo do limite do piso geram contatos estimados. Na quadra marcada, essa reta de quarto não é exigida; o detector usa a aproximação e o retorno observados para estimar o contato. Marcar uma quadra não comprova por si só contato físico. Poucos pixels, perda de quadros, proximidade da mão ou da linha exigem revisão. Na parede, retornos laterais são candidatos para revisão: uma câmera não distingue sempre parede, mão e raquete. IN/OUT usa o contorno marcado, sem aplicar regras de caixas de saque automaticamente.
- A velocidade mostrada é **lateral e estimada**, pela dimensão aparente de uma bola de 67 mm. Não é medição 3D nem radar. Quando escala, tempo ou observações não permitem estimar, fica indisponível.
- **Fluidez** escolhe até 720p; **Bola distante** até 1080p; **Economia** solicita até 30 FPS. As taxas são limitadas pelo suporte regular YUV anunciado para a resolução/lente. Gravação especial a 120/240 FPS não comprova suporte a análise YUV nessa taxa. A taxa recebida é medida, separada da solicitada. Exposição recente, conversão, detector e idade captura → UI, quando o relógio do sensor permite, aparecem no diagnóstico.
- A fila usa a imagem mais recente. Marca e vídeo vêm do mesmo quadro. A 0.6.3 corrige aquisição na mão, pequenos reflexos bloqueando uma bola maior, partes separadas pelos dedos e falso contato ao carregar a bola em pouca luz. Fragmentos reunidos aparecem como oclusão, sem velocidade nem impacto automático. A evidência de mão não deve permanecer presa à bola depois que ela se afasta sobre piso de madeira.
- A 0.6.4 melhora componentes pequenos com bordas misturadas ao fundo, mantendo ao menos um pixel da cor original, e recupera o alvo pequeno em movimento ao lado de um alvo maior parado após duas observações coerentes. Cor fraca/halo não certifica tamanho, velocidade ou IN/OUT. **Enquadramento e detalhe → Melhorar bola distante → Usar 1080p** oferece o perfil e orientações; o diagnóstico mostra o tamanho aparente em pixels. As lentes têm descrições de campo para facilitar a escolha.
- Alvos verdes igualmente plausíveis não autorizam saltar o rastro para qualquer objeto. Um alvo de 2–3 pixels parado não inicia um rastro novo; duas observações coerentes em movimento podem mostrá-lo como incerto, sem velocidade nem IN/OUT automático. Oclusão total não permite localizar a bola pela imagem.
- A última conferência física foi da **0.6.1**, no S25/Android 16: 47–60 FPS indicados durante um minuto, mediana 48. A **0.6.4** tem 172 testes JVM/Robolectric, comparação de sequências sintéticas, avaliação diagnóstica de gravação antiga e APK verificado; não foi instalada/testada em câmera física nesta rodada. Nos cenários controlados de 3–6 pixels, localizou 227/240 quadros, ante 188/240 na 0.6.3. Isso não mede precisão em quadra real. O detector custou aproximadamente 1,8–5,0 ms a mais nos cenários 1080p do benchmark do computador; esses tempos não são FPS do S25. Não foi comprovada precisão em partidas nem em saques a 200 km/h.
- Não há serviço externo necessário ao treino local. **GitHub** é usado apenas para atualização opcional. O áudio OUT está incluído no APK. As permissões de rede continuam disponíveis para atualização e transmissão opcional.
- A análise conjunta de duas câmeras e os modelos de pose/treinamento do painel Windows não foram portados para este modo local.

Consulte [bola pequena/distante 0.6.4](../../docs/android-distance-audit-2026-10-13.md), [bola na mão e queda 0.6.3](../../docs/android-hand-drop-audit-2026-10-13.md), [revisão de quadra 0.6.2](../../docs/android-court-audit-2026-10-12.md), [S25 físico na 0.6.1](../../docs/android-fast-ball-audit-2026-10-11.md) e [auditoria anterior no emulador](../../docs/android-offline-audit-2026-10-11.md). Testes de trajetórias rápidas com posições conhecidas não substituem uma avaliação anotada de partidas reais. A 200 km/h, a bola percorre cerca de 93 cm entre quadros a 60 FPS, 46 cm a 120 e 23 cm a 240; borrão, resolução, distância e exposição continuam limitando a leitura. Prefira meia quadra com a bola maior na imagem quando uma única câmera não fornece detalhe suficiente.

## Transmissão opcional para Windows (modo anterior)

Em **Ajustes → Transmitir para PC (opcional)**, o app mantém a captura H.264 por hardware e o envio SRT ao computador na rede local. Só esse modo opcional usa o receptor e a análise do Windows. Os controles e procedimentos a seguir se referem a ele.

## Como o conjunto funciona

1. O painel do PC cria um QR com o IP local e o caminho `/galaxy`.
2. O aplicativo lê esse QR e salva o pareamento.
3. Ao tocar em **Transmitir para o PC**, o app publica em `srt://IP-DO-PC:8890`.
4. O MediaMTX do projeto recebe o vídeo e o disponibiliza internamente como `rtsp://127.0.0.1:8554/galaxy`.
5. No Windows, aguarde **Vídeo recebido** e toque em **Conectar e iniciar vídeo**. O backend Python/OpenCV analisa os frames e o painel mostra vídeo, bola, trajetória, impacto, velocidade e estatísticas.

Nenhum vídeo precisa sair da rede local.

## Controles de captura

- **Qualidade:** oferece 1080p60, 720p60 e 1080p30 compatíveis com a câmera e o H.264, mais perfis de 120/240 fps compatíveis com ambos. O pareamento começa em 1080p60 quando disponível.
- **Toque na imagem:** nos perfis comuns, ajusta o foco. Depois use **Travar foco na distância atual** para evitar que o autofoco procure a bola durante o arremesso.
- **Congelar bola:** em Captura, nos perfis comuns com controle manual, solicita 1/1000, 1/2000 ou 1/4000 s. Precisa de boa iluminação; a exposição automática continua disponível. Em alta velocidade, o Android controla foco e exposição e esses ajustes ficam desabilitados. A exposição efetiva e o ISO aparecem abaixo dos ajustes.
- **Enquadramento:** zoom de até 4× dentro do suporte da câmera, grade e tela cheia. Ajuste o zoom antes de transmitir e de marcar a área no PC. Trocar o perfil reinicia zoom e trava de foco.
- **Luz do celular:** liga a lanterna quando a câmera oferece esse recurso. Não substitui iluminação uniforme da quadra.
- **Rede:** Mínimo atraso, Equilibrado ou Mais tolerante. Comece no primeiro; se houver perdas, reduza a resolução ou aumente a tolerância. Mostra buffer solicitado, fila em frames, retomadas, conexão, bateria e temperatura. Copiar diagnóstico inclui essas medições reais.
- **Captura / H.264 / envio:** medem separadamente os frames observados na câmera, na saída do codificador e no transmissor. Não são uma medida da latência total até o painel. Mbps e descartes ajudam a identificar perdas na rede.
- **Reconexão:** tenta retomar após interrupção de rede; o botão **Parar transmissão** cancela as tentativas.
- **Falha em 120/240 fps:** a versão 0.5.5 reinicia uma vez o mesmo modo e mantém o perfil salvo. Ela não reduz automaticamente para 60 fps. A prévia da tela pode rodar a 30/60 enquanto a captura e o H.264 usam a taxa escolhida.

A fila de envio é curta e o bitrate se ajusta à rede para reduzir acúmulo de vídeo. Isso não elimina o tempo de captura, transporte e análise. Mantenha o app aberto; a transmissão para quando ele vai para o fundo e deve ser iniciada novamente ao voltar.

## APK pronto para teste

O APK compilado e assinado para teste está em:

```text
mobile/releases/Tennis-Vision-Camera-Android-v0.6.4.apk
```

Envie esse arquivo ao Galaxy, abra-o e autorize a instalação desta fonte quando o Android solicitar. Esta versão usa uma assinatura de desenvolvimento; ela serve para instalar e testar diretamente no seu aparelho. Uma publicação futura na loja precisa de uma chave de lançamento própria.

## Gerar o APK no Windows

Pré-requisitos:

- Android Studio atual;
- Android SDK Platform 37;
- JDK 17, que pode ser o JDK incluído no Android Studio;
- acesso à internet na primeira compilação para baixar as dependências Gradle.

Passos:

1. Abra esta pasta `mobile/android` no Android Studio.
2. Aguarde a sincronização do Gradle.
3. Conecte o Galaxy por USB e habilite **Opções do desenvolvedor → Depuração USB**, ou use um emulador apenas para verificar a interface.
4. Selecione a configuração `app` e clique em **Run** para instalar no aparelho.
5. Para gerar um arquivo instalável, use **Build → Build APK(s)**. O APK de teste será criado em `app/build/outputs/apk/debug/app-debug.apk`.

Também é possível compilar no terminal após configurar `JAVA_HOME` e `ANDROID_HOME`:

```powershell
.\gradlew.bat assembleDebug
```

## Primeiro uso da transmissão opcional

1. No PC, abra o projeto com `ABRIR.bat`.
2. Execute `LIBERAR-CAMERA-CELULAR.bat` como administrador uma vez. A regra libera a porta UDP 8890 somente na rede privada/local.
3. No painel, escolha **Conectar câmera → Câmera Tennis Vision → Preparar**.
4. No app, toque em **Ler QR do computador** e leia o QR exibido.
5. Em **Captura → Qualidade**, escolha **1080p · 60 fps · 12 Mbps**. Em **Rede**, comece com **Mínimo atraso** e toque em **Transmitir para o PC**.
6. No PC, clique em **Adicionar ao painel** e inicie uma sessão.

Se a rede ficar instável, teste **720p · 60 fps · 8 Mbps**. Para alta velocidade, pare a transmissão e escolha um dos perfis de 120/240 fps disponíveis em **Qualidade**. Teste primeiro 120 fps e confira os três indicadores. O app mantém o último perfil usado ao transmitir; ler um novo QR aplica o perfil inicial do pareamento.

Na rede doméstica, o perfil da conexão do PC precisa ser **Rede privada** no Windows. Uma conexão marcada como Pública continua bloqueada por essas regras. O computador pode usar Ethernet e o Galaxy Wi-Fi do mesmo roteador; não precisa estar conectado por Wi-Fi.

O pareamento aceita a porta e o caminho mostrados no painel; também é possível preencher IP, porta e caminho manualmente. No Android 17 ou superior, permita o acesso à rede local quando solicitado. O app não pede microfone e não transmite áudio. Não exige HTTPS para a captura nativa.

## Verificação desta versão

Na versão 0.4.1, os testes Android também cobrem compensação de ISO ao trocar o obturador e liberação dos outros recursos se um encerramento falhar. A revisão corrigiu callbacks antigos da câmera, controles manuais consultando o sensor errado e retomada da transmissão com um quadro-chave novo após a conexão SRT. Os resultados completos ficam em `docs/mobile-app.md`.

O modo local da 0.6.1 foi testado com a câmera real do S25 Ultra. A 0.6.4 e os novos modos de lente/quadra ainda precisam de teste físico. Foco/exposição com bolas físicas rápidas e transmissão SRT em alta velocidade continuam pendentes. A versão iOS permanece separada.

## Publicação da 0.6.4 concluída em 14/10/2026

O [release android-v0.6.4](https://github.com/Laikan2/tennis-vision-releases/releases/tag/android-v0.6.4) foi publicado pelo GitHub CLI autenticado. O download público teve tamanho e SHA-256 conferidos contra o APK local; `mobile/releases/verification/android-v0.6.4/published.json` guarda a prova. O latest do Windows permanece em 0.6.9. A tentativa anterior de 13/10 ficou bloqueada; a autorização e publicação de 14/10 resolveram essa pendência. As notas estão em `docs/releases/android-v0.6.4.md`.

No celular, use **Atualizações → Buscar atualização → Instalar atualização**. O Android exige confirmação da instalação. O treino local continua independente da internet.

## 120 e 240 fps

O aplicativo implementa sessões Camera2 `CONSTRAINED_HIGH_SPEED`. Ele consulta as câmeras traseiras e inclui somente resoluções de até 1080p com faixas fixas de 120 ou 240 fps e um codificador H.264 por hardware que suporte a mesma resolução/taxa. Uma faixa variável de 30–240 não basta para oferecer um perfil de 240 fps.

A câmera alimenta diretamente duas superfícies de mesmo tamanho: a prévia `SurfaceView` e a entrada do `MediaCodec`. O app usa `createHighSpeedRequestList` e `setRepeatingBurst`, mantém os timestamps da câmera no H.264 e não força um limitador de 60 fps. A prévia do celular pode mostrar 30/60 fps enquanto a superfície de gravação recebe 120/240; confira **Captura**, **H.264** e **envio** durante a transmissão.

Foco e exposição automáticos são uma restrição destas sessões do Android. Ilumine bem a cena. O anúncio de 240 fps pela câmera/codec não garante transmissão sustentada: temperatura, Wi-Fi e recursos do aparelho podem reduzir a taxa. A câmera padrão da Samsung também pode oferecer modos de câmera lenta que não estão expostos a aplicativos pelo Camera2. O teste físico de transmissão em 120/240 FPS continua pendente; a análise local usa a saída YUV regular, medida separadamente.

O MediaMTX recebe a taxa enviada. O cadastro aceita até 240 fps; esse campo é uma preferência, não um limitador do receptor. A IA processa o frame mais recente e a visualização tem uma taxa própria: receber 240 fps não significa analisar ou exibir todos eles em tempo real.

## Transmissão na versão 0.4

Mínimo atraso solicita 40 ms de buffer SRT e aproximadamente 80 ms de fila de frames; Equilibrado usa 80/150 ms e Mais tolerante usa 160/250 ms. A fila arredonda para frames completos, com mínimo de três. O receptor negocia sua própria latência; esses números não medem o atraso total até o painel.

Se a fila estourar ou acumular vídeo, o app descarta o conteúdo antigo, solicita um quadro-chave e aguarda esse quadro antes de voltar a enviar frames dependentes. A recuperação tem intervalo mínimo de 1,5 s e o bitrate se adapta entre metade e o máximo do perfil. Isso evita reproduzir uma fila crescente e reduz a espera por uma referência válida do H.264.

O detector no PC passou a buscar pequenas silhuetas verdes em movimento nos pixels originais. A área selecionada continua sendo a superfície para impactos e IN/OUT, não um recorte que apague a aproximação da bola. A visualização WebRTC permanece independente da IA. A prévia JPEG tem orçamento próprio de compressão para economizar CPU.

Referências: [sessões Camera2 de alta velocidade](https://developer.android.com/reference/android/hardware/camera2/CameraConstrainedHighSpeedCaptureSession), [criação de sessão e superfícies](https://developer.android.com/reference/android/hardware/camera2/CameraDevice), [suporte de resolução e taxa no codec](https://developer.android.com/reference/android/media/MediaCodecInfo.VideoCapabilities).

## iPhone como segunda câmera

O mesmo protocolo e os mesmos caminhos podem ser usados em um aplicativo iOS com AVFoundation:

- Galaxy: `/galaxy`
- iPhone: `/iphone`

O backend já aceita duas câmeras simultâneas. A versão iOS é uma etapa separada porque o código de captura e codificação precisa ser escrito em Swift.
