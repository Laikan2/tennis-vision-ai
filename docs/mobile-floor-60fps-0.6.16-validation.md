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

### GitHub e iPhone pendentes

Os testes nativos/compilações do iPhone, transmissão decodificada e comparação de desempenho foram enviados ao GitHub, mas permaneceram na fila sem iniciar. O GitHub registrou [degradação do Actions em 19/10/2026](https://www.githubstatus.com/incidents/8tzbpcnl75jp), com atrasos no processamento de workflows. Não há resultado de teste Swift, IPA 0.6.16 ou publicação conjunta verificados nesta etapa. A repetição Android no GitHub também aguarda execução; a evidência emulada acima é local.

- [Compilação/testes iPhone](https://github.com/Laikan2/tennis-vision-ai/actions/runs/39284363622).
- [Comparação de desempenho](https://github.com/Laikan2/tennis-vision-ai/actions/runs/39284363490).
- [Repetição Android com sincronização do teste de permissão](https://github.com/Laikan2/tennis-vision-ai/actions/runs/39285953917).
- [Alterações e evidências no PR 11](https://github.com/Laikan2/tennis-vision-ai/pull/11).

A versão 0.6.16 do Android está compilada localmente com a assinatura anterior. Os motores receberam a mesma correção, mas a nova versão ainda não foi disponibilizada à fonte AltStore: falta compilar e validar o iPhone antes da publicação conjunta. A versão pública anterior permanece 0.6.15. Não foi usado um IPA antigo com versão alterada.

Nenhum celular foi acessado nesta etapa, a pedido do usuário. Não houve instalação, teste físico de bola, medição física de FPS ou validação de saques rápidos nesta versão. Dados sintéticos e três imagens anteriores de desenvolvimento não são um conjunto de contatos reais anotados. Não houve treinamento de modelo neural.

Uma câmera monocular não comprova todo contato físico, retorno no ar ou velocidade 3-D. Mãos, oclusões, bolas minúsculas/borradas e posições próximas à linha continuam exigindo cautela/revisão. A velocidade pode ficar indisponível quando não houver evidência observada válida; não se inventa valor para preencher a marca.

Histórico, formatos de armazenamento, vozes e identidade de atualização foram mantidos. Áreas continuam temporárias por sessão. A versão instalada anteriormente no iPhone não foi substituída remotamente nesta etapa.
