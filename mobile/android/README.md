# Tennis Vision Camera para Android

Aplicativo nativo que transforma o Galaxy S25 Ultra em uma câmera do Tennis Vision AI. Ele captura a câmera traseira, codifica H.264 no hardware do celular e envia o vídeo por SRT diretamente ao computador na rede local.

## Como o conjunto funciona

1. O painel do PC cria um QR com o IP local e o caminho `/galaxy`.
2. O aplicativo lê esse QR e salva o pareamento.
3. Ao tocar em **Transmitir para o PC**, o app publica em `srt://IP-DO-PC:8890`.
4. O MediaMTX do projeto recebe o vídeo e o disponibiliza internamente como `rtsp://127.0.0.1:8554/galaxy`.
5. O backend Python/OpenCV analisa os frames e o painel mostra vídeo, bola, trajetória, impacto, velocidade e estatísticas.

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

A fila de envio é curta e o bitrate se ajusta à rede para reduzir acúmulo de vídeo. Isso não elimina o tempo de captura, transporte e análise. Mantenha o app aberto; a transmissão para quando ele vai para o fundo e deve ser iniciada novamente ao voltar.

## APK pronto para teste

O APK compilado e assinado para teste está em:

```text
mobile/releases/Tennis-Vision-Camera-Android-v0.4.1.apk
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

## Primeiro uso

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

Ainda não foi realizado um teste físico de câmera, foco, exposição ou transmissão no S25 Ultra. A combinação real de FPS, resolução e iluminação precisa ser conferida no aparelho. A versão iOS permanece separada.

## 120 e 240 fps

O aplicativo implementa sessões Camera2 `CONSTRAINED_HIGH_SPEED`. Ele consulta as câmeras traseiras e inclui somente resoluções de até 1080p com faixas fixas de 120 ou 240 fps e um codificador H.264 por hardware que suporte a mesma resolução/taxa. Uma faixa variável de 30–240 não basta para oferecer um perfil de 240 fps.

A câmera alimenta diretamente duas superfícies de mesmo tamanho: a prévia `SurfaceView` e a entrada do `MediaCodec`. O app usa `createHighSpeedRequestList` e `setRepeatingBurst`, mantém os timestamps da câmera no H.264 e não força um limitador de 60 fps. A prévia do celular pode mostrar 30/60 fps enquanto a superfície de gravação recebe 120/240; confira **Captura**, **H.264** e **envio** durante a transmissão.

Foco e exposição automáticos são uma restrição destas sessões do Android. Ilumine bem a cena. O anúncio de 240 fps pela câmera/codec não garante transmissão sustentada: temperatura, Wi-Fi e recursos do aparelho podem reduzir a taxa. A câmera padrão da Samsung também pode oferecer modos de câmera lenta que não estão expostos a aplicativos pelo Camera2. O teste físico no S25 continua pendente.

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
