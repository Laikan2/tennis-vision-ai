# Impactos de chão em 60 FPS — mobile 0.6.16

## Falha reproduzida e correção

O usuário relatou ausência de impacto e velocidade mesmo em 60 FPS. A versão 0.6.15 foi testada antes da alteração com pixels ARGB e timestamps gerados: três controles positivos falharam entre oito testes, apesar de a bola permanecer observada. Os cinco controles negativos passaram. Isso reproduz mecanismos compatíveis com a queixa, mas não determina a causa exata de cada quicada física anterior.

A janela antiga aceitava até 200 ms de trajetória e oito espaçamentos de quadros, com histórico máximo de 40 observações. Um retorno mais lento podia não fornecer deslocamento suficiente nesse período. Uma perda de observação maior que 100 ms também apagava a descida antes de a bola reaparecer.

Android e iPhone agora procuram evidência dentro de até 420 ms. A extensão exige referência de chão válida, bola resolvida, cor apoiada nas observações, tamanho consistente, ausência de mão/oclusão/borrão e dois trechos coerentes. O histórico permanece limitado a 600 ms/160 observações. A confirmação usa o retorno observado; a marca de uma janela longa usa amostras locais no pico, evitando deslocá-la pela curvatura dos trechos externos.

Uma descida limpa pode ser retida por até 160 ms sem observação, com referência de chão. A previsão desenhada continua expirando após 60 ms. Uma lacuna não cria amostras de bola e força REVISAR no contato, com incerteza proporcional ao movimento e ao tempo ausente. O cálculo da velocidade mantém a proteção contra intervalos ausentes maiores que 75 ms.

## Resultados locais verificados

- **274 testes Android/JVM:** zero falhas, erros ou testes ignorados. APK Release compilado, manifesto e certificado anterior conferidos; vozes offline mantidas. Lint: zero erros/fatais; permanecem 150 avisos.
- **28 testes Python:** publicação coordenada, fonte AltStore, áudios e comparação de desempenho passaram.
- **11 novos testes de pixels:** IN e OUT em 60 FPS com posição e velocidade de entrada verificadas, suporte temporal em 30/120/240 FPS, contato entre quadros, bola carregada, queda sem retorno, limite do piso, falta de referência, perdas curta/longa e prazo da previsão passaram.
- No controle visível de 60 FPS, contato conhecido em 0,300 s: marca em 0,30017 s e y=194,5 px, velocidade lateral estimada de aproximadamente 1,162 km/h. IN e OUT foram decididos conforme os polígonos distintos, mantendo a mesma posição da marca.
- A decisão desse retorno lento chegou em 0,500 s: **200 ms após o contato**, sem introduzir fila ou espera na prévia. Nos controles de 120/240 FPS, foram 183/175 ms. Esses valores medem a evidência temporal do controle, não latência física total ou FPS de um celular.

Detalhes numéricos: [regressão de impactos](validation/mobile-0.6.16/floor-60fps-regression.json) e [compilação Android](validation/mobile-0.6.16/android-build.json).

## Validações externas e limites

### Android emulado local

Foram executados dez cenários em um emulador Android 15/API 35 neste Windows, com aceleração WHPX e AVD somente leitura. O script validou o serial `emulator-5574` e `ro.kernel.qemu=1`; nenhum telefone físico foi selecionado.

Os oito testes funcionais e a concessão de câmera passaram na primeira execução. A recusa de câmera falhou na checagem imediata do texto após o diálogo do Android; o teste passou após aguardar o estado real devolvido por ActivityResult, com limite de quatro segundos. A asserção também passou a comparar o número de eventos antes/depois, verificando que a recusa não cria contatos. Apenas o teste foi alterado nessa repetição; a aplicação de produção permaneceu igual. A tentativa inicial com falha foi preservada.

O vídeo H.264 sintético foi realmente decodificado por MediaCodec: 191 quadros produziram IN, OUT e REVISAR, com marcas visíveis, velocidade estimada e contagens persistidas verificadas pelo teste. SoundPool aceitou uma chamada OUT; a audibilidade não foi verificada porque o emulador estava sem áudio. O controle também conferiu áreas separadas, cancelamento, nova sessão/histórico, toques, segundo plano/recriação, gravação atômica, contorno sem iniciar treino, bloqueio/desbloqueio, economia de bateria e permissões.

O processamento desse clipe emulado teve mediana de 2,91 ms e percentil 95 de 6,08 ms; esses tempos **não representam FPS ou atraso físico do celular**. Evidências: [resultado emulado](validation/mobile-0.6.16/android-runtime-local.json) e [marcas na interface](validation/mobile-0.6.16/floor-pipeline-emulator.png).

### iPhone e comparação de desempenho

A [execução nativa iOS](https://github.com/Laikan2/tennis-vision-ai/actions/runs/39284363622) terminou com sucesso: **141 testes Swift**, compilação para iPhone físico sem assinatura, compilação para simulador, empacotamento do IPA e **60 quadros H.264 transmitidos e decodificados**. As versões e os áudios foram conferidos dentro do pacote. Isso não comprova câmera, contato ou saída de áudio de um iPhone físico.

O passo opcional de abrir o simulador e produzir captura expirou após dois minutos. A ausência da captura foi registrada; não se afirmou validação visual ou uso de câmera no simulador. Evidência: [iOS CI](validation/mobile-0.6.16/ios-ci.json).

A [comparação de desempenho](https://github.com/Laikan2/tennis-vision-ai/actions/runs/39284363490) também passou: 12 cenas geradas, executadas em duas ordens, mantiveram as posições, rastros, contatos, incerteza e estados da referência. Só diferiram os campos de velocidade intencionalmente corrigidos na 0.6.15. Dois testes de memória com AddressSanitizer passaram. A referência é o commit da 0.6.10, não uma comparação direta 0.6.15→0.6.16. Ganhos nesse ensaio não representam FPS do iPhone. Evidências: [desempenho e limites](validation/mobile-0.6.16/performance.json).

### Repetições Android no GitHub

A primeira execução em nuvem falhou com o diálogo de ANR do **Pixel Launcher** sobre o aplicativo: seis testes funcionais e dois de permissões falharam com toques enviados a uma janela de outro processo. A execução seguinte parou antes dos testes porque `adb root` fechou a conexão do emulador. Os registros dessas falhas foram preservados. A pré-condição do script foi corrigida para confirmar o mesmo emulador, acesso root e boot completo, com tentativas limitadas de reconexão. As asserções funcionais não são repetidas automaticamente para ocultar falhas.

A [execução do script corrigido](https://github.com/Laikan2/tennis-vision-ai/actions/runs/39287449425) falhou na tentativa 1 antes de iniciar o emulador: o download da imagem do SDK resultou em “Error on ZipFile unknown archive”. A tentativa 2 foi solicitada, mantendo exatamente o mesmo código. Os dez cenários Android concluídos e descritos acima foram executados **localmente**, não são apresentados como sucesso dessa execução em nuvem. Consulte o [registro das tentativas](validation/mobile-0.6.16/android-cloud-validation.json). O GitHub registrou [degradação do Actions durante a validação](https://www.githubstatus.com/incidents/8tzbpcnl75jp); os resultados de build e desempenho só foram aceitos após conferir seus artefatos e conclusões finais.

### Publicação verificada

A [0.6.16 para Android e iPhone](https://github.com/Laikan2/tennis-vision-releases/releases/tag/mobile-v0.6.16) foi publicada. A fonte AltStore foi relida sem cache e aponta para **0.6.16/build 17**; o APK é **0.6.16/código 29**. Tamanhos, SHA-256 e downloads públicos foram conferidos. O projeto iOS contém somente os 71 arquivos rastreados selecionados. A referência de atualização do Windows permaneceu v0.6.11. Evidência: [publicação](validation/mobile-0.6.16/publication.json). Código e revisão: [PR 11](https://github.com/Laikan2/tennis-vision-ai/pull/11).

Nenhum celular foi acessado nesta etapa, a pedido do usuário. Não houve instalação, teste físico de bola, medição física de FPS ou validação de saques rápidos nesta versão. Dados sintéticos e três imagens anteriores de desenvolvimento não são um conjunto de contatos reais anotados. Não houve treinamento de modelo neural.

Uma câmera monocular não comprova todo contato físico, retorno no ar ou velocidade 3-D. Mãos, oclusões, bolas minúsculas/borradas e posições próximas à linha continuam exigindo cautela/revisão. A velocidade pode ficar indisponível quando não houver evidência observada válida; não se inventa valor para preencher a marca.

Histórico, formatos de armazenamento, vozes e identidade de atualização foram mantidos. Áreas continuam temporárias por sessão. A versão instalada anteriormente no iPhone não foi substituída remotamente nesta etapa.
