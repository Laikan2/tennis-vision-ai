# Auditoria Android — Tennis Vision 0.6.0

Revisão iniciada em 11/10/2026. Android `versionCode 13`.

## Resultado e alcance

O Android anterior era principalmente uma câmera transmissora: a análise dependia do receptor e do Python no Windows. Foi implementado um **treino local como tela inicial**, com captura, detecção da bola verde, rastro, candidatos de impacto, IN/OUT, som, revisão, estatísticas e armazenamento no próprio Android.

O APK foi instalado e operado em **emulador Android 15 / API 35, x86_64, perfil Pixel 6**. Nenhum S25 físico estava conectado. Os resultados abaixo comprovam os cenários executados no emulador; não comprovam precisão esportiva, FPS nem fluidez no S25.

O modo local funcionou com modo avião, Wi-Fi e dados desativados. Um teste Android conferiu `ConnectivityManager.activeNetwork == null`. Não há servidor, executável ou arquivo do Windows necessário a esse fluxo. **Não foi possível desligar fisicamente o computador durante o teste: ele hospeda o emulador.** A validação final no celular, com o PC desligado, continua pendente.

## Como IN e OUT funcionam

- **IN:** o contato candidato fica dentro da área ou a marca estimada toca a linha. Acrescenta a marca e a contagem IN.
- **OUT:** a marca estimada fica inteiramente fora, além da margem de incerteza. Acrescenta a marca, a contagem OUT e aciona uma vez o áudio incluído no APK, se habilitado.
- **REVISAR:** não há evidência suficiente para decidir. Não reproduz OUT e não entra no denominador da porcentagem IN/OUT.
- **SEM ÁREA:** pode haver um contato candidato, mas não uma decisão dentro/fora.
- **Descartado na revisão:** preserva o registro original, mas sai das contagens válidas.

A área IN/OUT **não recorta a busca da bola**. A procura ocorre na imagem inteira, permitindo observar aproximação e bolas fora da área. O limite do piso é uma segunda marcação: dois pontos separam a parede do chão, que deve estar abaixo dessa linha. Sem esse limite, as quicadas exigem revisão.

O detector local usa cor, forma, movimento e sequência temporal. Não é o modelo neural Python do Windows nem um modelo novo treinado com os vídeos. No chão, procura descida e subida observadas; previsões curtas são identificadas e não geram pontos. Na parede, uma inversão lateral continua sendo um candidato para revisão, pois uma câmera pode confundir parede, mão e raquete.

## Problemas encontrados e correções

1. **Dependência obrigatória do PC:** substituída no fluxo inicial por captura Camera2/YUV e análise Kotlin local. Transmitir para PC continua disponível como opção.
2. **Área terminava além do dedo:** a última posição de movimento podia diferir da posição em que o dedo era solto. A edição agora confirma a coordenada do evento de soltura. Área degenerada não pode ser salva; cancelar conserva a anterior.
3. **Bloquear/desbloquear rapidamente não pausava a análise:** adicionado tratamento explícito do bloqueio. Câmera é liberada e a retomada do treino exige ação do usuário.
4. **Piso de madeira confundido com mão:** cor de pele isolada deixou de bastar; é exigido movimento do entorno. Pixels que eram da bola no quadro anterior não são considerados uma mão ao revelar o piso.
5. **Bola confundida com quadra verde:** separação por movimento e brilho evita juntar bola e fundo num único componente. O caso de teste passou mantendo a bola observável.
6. **Quadrado verde produzia falso OUT:** o teste reproduziu o erro antes da correção. O filtro agora rejeita silhuetas retangulares preenchidas com tamanho suficiente para avaliar a forma. O teste de borrão alongado permaneceu aprovado. Isso não elimina todos os objetos parecidos com bolas.
7. **Quadros espaçados apagavam contatos:** a trajetória curta observada é preservada dentro de limites; grandes intervalos elevam a incerteza e exigem revisão, em vez de anunciar OUT com localização pouco confiável.
8. **Vídeo e marcações podiam acumular trabalho:** captura consome o quadro mais recente; processamento fica fora da interface; vídeo e desenho usam o mesmo quadro. Eventos de impacto são entregues separadamente para não desaparecer com descartes da prévia. Previsões vencem rapidamente.
9. **Consulta de câmera inexistente podia fechar o modo antigo:** consulta protegida e falha apresentada ao usuário. Reproduzido no emulador, cuja câmera traseira não usa o identificador presumido pelo perfil anterior.
10. **Tela cheia do modo antigo não escondia corretamente os controles:** corrigida a ordem de aplicação da visibilidade após o layout.

Também foram adicionados gravação atômica de sessões, aviso de falha de armazenamento, quadro de evidência do contato, revisão preservando a chamada original e limite de 2.000 contatos por sessão. As configurações locais usam um espaço separado dos pareamentos e perfis existentes. Arquivos do Windows, gravações e sessões anteriores não foram apagados.

## Cenários executados

**111 testes JVM aprovados**, sem falhas nem testes ignorados. Cobrem regras, geometria, timestamps, persistência e funcionalidades anteriores. São testes de lógica; não são prova de câmera física.

**11 testes instrumentados aprovados no Android:** nove na suíte final e dois de permissão executados separadamente.

- Primeira permissão concedida: diálogo real do Android, seguido de recebimento de imagens da câmera virtual.
- Permissão negada duas vezes: app não se declara pronto; permite abrir as configurações do sistema.
- Toques e arraste reais injetados na interface: desenhar área, marcar piso, cancelar e impedir área inválida.
- Dez toques rápidos em iniciar/pausar: estado final consistente.
- Segundo plano, recriação da tela e retorno: área preservada, treino pausado.
- Bloqueio/desbloqueio e economia de bateria do Android: pausa correta e configurações preservadas.
- Sem rede ativa: captura, edição e análise local continuam disponíveis.
- Escrita interrompida simulada: recuperação do arquivo de sessão anterior no sistema de arquivos Android.
- Regressão de conexão manual, tela cheia, navegação e compartilhamento restrito do APK de atualização. A transmissão SRT física não foi testada.

**Um teste adicional do APK release instalado foi aprovado.** Um pacote de teste independente operou a versão reduzida/ofuscada, com `debuggable=false`: recebeu imagens, iniciou/pausou, abriu/cancelou a área, abriu o guia, alterou o som, abriu histórico, confirmou uma revisão e encerrou/reabriu o processo. A revisão e as contagens permaneceram iguais após reiniciar.

Uma tentativa inicial de injetar o pacote de testes debug no aplicativo ofuscado falhou por dependências do próprio teste. Ela não foi contada como aprovação nem como falha funcional do APK; foi substituída pelo teste independente acima.

Também passaram **seis testes Python de regressão**, isolados dos dados pessoais, para o download do APK, metadados do celular e seleção de recursos empacotados. O backend e a configuração do próximo pacote Windows apontam para Android 0.6.0. O programa Windows já instalado não foi reconstruído ou substituído nesta auditoria.

## Detecção de ponta a ponta

### Vídeo com resultados conhecidos

Um MP4 H.264 sintético de **191 quadros, 320 × 240, 30 FPS** foi decodificado pelo `MediaCodec` do Android. Seus pixels passaram pela conversão YUV, detector, trajetória, classificação, interface, contagens, áudio e armazenamento. A câmera virtual foi interrompida durante essa entrada controlada.

Resultado: **IN, OUT e REVISAR**, exatamente os três eventos previstos pelo cenário, sem duplicatas ou impactos adicionais nos trechos negativos. Foram conferidos os eventos no arquivo JSON e a existência do JPEG de evidência, além da interface. O `SoundPool` aceitou **um** acionamento de OUT. O emulador foi iniciado sem saída de áudio: a audibilidade e a qualidade da voz não foram verificadas por escuta.

Os negativos incluem bola carregada pela mão, travessia no ar e objeto parado. Testes adicionais cobrem quadrado verde em movimento, ápice do voo, oclusão, timestamps repetidos, ausência de área e incerteza junto à linha. Os testes a 120/240 usam timestamps sintéticos; não comprovam captura física nessas taxas.

### Quadros reais já gravados

Foram processados no Android **87 quadros locais**, ao longo de aproximadamente 8,1 segundos, redimensionados para 960 × 540. O detector encontrou candidatos de bola em **63 quadros** e produziu **cinco contatos para REVISAR**. Não houve chamada automática OUT nesse trecho.

Esse material tem intervalos irregulares e não foi anotado independentemente quadro a quadro. **63/87 não é taxa de acerto**, e os cinco contatos não comprovam que todos os impactos reais foram encontrados. Não foi possível calcular precisão, recall ou erro em centímetros com esse material. Os quadros privados permaneceram locais; não integram o APK nem os dados sintéticos de teste.

## Desempenho medido e limites

- Detector no vídeo sintético 320 × 240: mediana **0,61 ms**, percentil 95 **1,05 ms** por quadro.
- Detector nos quadros gravados 960 × 540: mediana **5,09 ms**, percentil 95 **8,24 ms**.
- No teste do APK release com câmera virtual 1280 × 720, uma amostra registrou **25 FPS recebidos**, perfil solicitado de até 30 FPS, e **37,7 ms** no campo de conversão/análise. Não é média de sessão nem desempenho de um S25.

Os dois primeiros tempos medem somente o detector. O campo da câmera inclui conversão de pixels e análise, mas não mede toda a captura e apresentação na tela. A latência óptica completa não foi medida. Não há comparação válida de FPS “antes/depois” no celular, porque a versão anterior não tinha esse fluxo local e nenhum aparelho físico estava disponível.

O modo automático solicita a maior taxa regular YUV anunciada para a resolução escolhida, até 720p; economia solicita até 30 FPS. Não há promessa de 120/240 FPS. Sessões especiais de alta velocidade têm restrições de saídas para análise; a taxa de vídeo lento anunciada pelo fabricante não comprova suporte do fluxo local. Referência: [sessões Camera2](https://developer.android.com/media/camera/camera2/capture-sessions-requests).

A velocidade exibida é uma **estimativa lateral**, baseada no diâmetro aparente de uma bola de 67 mm e no tempo entre observações. Não representa velocidade 3D nem medição de radar. Fica indisponível quando a evidência é inadequada.

Uma câmera com esse detector ainda pode confundir objetos verdes, reflexos, movimentos da mão e retornos no ar. A marca é estimada no quadro; perspectiva, oclusão e borrão limitam a localização. Não foi comprovada precisão para arbitragem oficial.

## Dependências e recursos ainda separados

O treino local não precisa de Windows, MediaMTX, Python, navegador, conta ou internet. O som está incluído no APK. GitHub é usado apenas pela atualização opcional; o modo antigo de transmissão só precisa do PC quando escolhido explicitamente.

A assinatura do APK corresponde à da versão Android 0.5.6, permitindo atualização sobre ela sem desinstalar. O mecanismo de atualizações foi preservado, mas **esta versão não foi publicada no GitHub durante a auditoria**. Instalação silenciosa não foi prometida: o Android controla a autorização para instalar.

Análise conjunta de duas câmeras, esqueleto, classificação neural de golpes e treinamento do painel Windows **não foram portados**. O novo modo local tem os recursos de treino descritos aqui; não é uma cópia de todas as funções do programa Windows.

Lint terminou com **zero erros e 137 avisos**, principalmente internacionalização/textos, layout e alocações. A compilação passou, mas os avisos não foram todos eliminados.

## O que falta testar

- S25 Ultra real: câmera, lentes, foco, exposição, FPS sustentado e toques no aparelho. Motivo: nenhum aparelho físico conectado.
- Treino com PC desligado e desconectado. Motivo: o ambiente disponível foi um emulador hospedado no PC.
- Bolas físicas rápidas, linhas e contatos com referência independente; distância e iluminação variadas. Motivo: não houve captura física controlada nem conjunto real anotado suficiente.
- Aquecimento, bateria em treino prolongado, políticas da Samsung, chamadas telefônicas e concorrência de outro app pela câmera. Motivo: testes de sistema no emulador não reproduzem esses cenários de hardware/OEM.
- Voz escutada no alto-falante/Bluetooth e comportamento com volumes distintos. Motivo: saída sonora do emulador desativada.
- Exportação para diferentes provedores de arquivos e instalação de atualização baixada do GitHub nesta versão. Não foi executada essa sequência completa; serialização e compartilhamento restrito têm testes próprios.
- Parede com decisão automática confiável, múltiplas câmeras e equivalência total com a IA do PC. Esses recursos não foram comprovados/portados pelo novo modo local.

## Instalar e conferir no celular

1. Transfira e abra [Tennis Vision Android 0.6.0](../mobile/releases/Tennis-Vision-Camera-Android-v0.6.0.apk). Instale sobre o aplicativo anterior; não desinstale para atualizar.
2. Abra, permita a câmera, fixe o celular e aguarde **Câmera pronta**.
3. Em **Área IN / OUT**, arraste e ajuste os cantos. Em **Limite do piso**, marque a divisa com o chão. Toque em **Iniciar treino**.
4. Desative Wi-Fi/dados e mantenha o PC desligado para a conferência física pendente. Faça lançamentos conhecidos dentro, fora e junto à linha; confira as imagens de revisão e as contagens.

APK: **3.206.688 bytes**. SHA-256: `59b4f896f1941ca46367d944b82c0519a6b2f27d4230db6a55ce6a23470880ef`.

## Evidências e reprodução

- [Resumo verificável em JSON](validation/android-local-0.6.0/summary.json).
- [Resultado do vídeo controlado](validation/android-local-0.6.0/pipeline.json) e [tela do cenário](validation/android-local-0.6.0/fixture.png).
- [Metadados do APK instalado](validation/android-local-0.6.0/release.json) e [captura do aplicativo](validation/android-local-0.6.0/release.png). As barras coloridas são a câmera virtual; as contagens da captura acumulam execuções sintéticas, não uma partida real. A captura foi feita logo após reabrir, antes de completar a janela de medição de FPS; a amostra de 25 FPS no JSON é anterior ao reinício.
- [Suíte Android](validation/android-local-0.6.0/android-tests.log), [negar permissão](validation/android-local-0.6.0/permission-denied.log), [conceder permissão](validation/android-local-0.6.0/permission-granted.log) e [teste do release](validation/android-local-0.6.0/release-test.log).
- [Erro reproduzido do quadrado verde](validation/android-local-0.6.0/square-logo-before.log) e [compilação/testes após correção](validation/android-local-0.6.0/build.log).
- [Regressão do download e empacotamento](validation/android-local-0.6.0/packaging-regression.log).
- [Procedimento do teste independente do release](../mobile/android/release-probe/README.md). O gerador do vídeo sintético está em `scripts/generate-android-offline-fixture.py`.

Para compilar e executar os testes JVM, com JDK 17 e SDK configurados:

```powershell
.\mobile\android\gradlew.bat -p mobile/android testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug assembleRelease
```

Os testes instrumentados exigem Android e preparação separada das permissões/rede. A suíte do replay privado exige os quadros locais; sem eles não se deve declarar esse cenário testado.
