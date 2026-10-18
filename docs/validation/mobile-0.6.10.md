# Tennis Vision mobile 0.6.10 — diagnóstico e validação

Data da revisão: 18 de outubro de 2026.

**Entrega 0.6.10 publicada para Android e iPhone, com os resultados disponíveis consolidados abaixo.** Resultados de câmera física não foram obtidos: o usuário escolheu continuar sem o aparelho. Os testes controlados não comprovam precisão em partidas, e a abertura da interface iOS no simulador excedeu o tempo limite.

## Arquitetura e significado dos resultados

O treino local roda no próprio Android ou iPhone. No Android, Camera2 entrega imagens YUV; `LocalCamera` converte e orienta os pixels, executa o detector e entrega imagem e resultado à interface. No iPhone, AVFoundation entrega os quadros a `LocalCamera`, que faz a conversão e chama o núcleo Swift correspondente. O painel do computador e a transmissão são opcionais para esse modo.

Os núcleos `LocalAnalysis.kt` e `LocalVision.swift` usam cor, componentes da imagem, formato, contexto da mão, histórico de posições e referência do cenário. Esta entrega melhora esses algoritmos; não representa treinamento de uma rede neural nem aprendizado automático comprovado a cada treino. A prévia procura a bola sem criar pontos no histórico. Iniciar treino ativa a análise de contatos.

IN e OUT classificam a região estimada de contato em relação à área desenhada, com margem de incerteza. Não basta a bola passar dentro da área no ar. REVISAR indica evidência insuficiente para decisão automática; SEM ÁREA indica ausência de referência válida. O modo de parede continua conservador: uma mudança de direção em uma câmera não comprova, sozinha, contato em profundidade, portanto os candidatos exigem revisão.

## Causas encontradas e correções

### Bola na mão, piso amadeirado e movimento

Em amostras do vídeo de referência, o piso quente entrava na máscara antiga de cor e se unia à bola. O componente resultante ficava grande e irregular, sendo rejeitado. Uma costura ou impressão escura também podia dividir a bola em fragmentos. Havia ainda casos em que um objeto verde menos convincente tornava ambígua a aquisição de uma bola bem definida.

A segmentação passou a priorizar o feltro com cor consistente e a expandir de forma limitada para bordas menos saturadas. Uma segunda passagem mantém evidências fracas sem aceitar indiscriminadamente objetos do ambiente. As verificações de formato, tamanho, contraste, continuidade e contexto continuam ativas. Fragmentos compatíveis separados por uma costura estreita podem ser reunidos; oclusão pela mão continua explicitamente incerta e não gera velocidade ou pontuação a partir de evidência escondida.

O histórico antigo usava uma janela fixa de cinco observações. Em taxas altas, o deslocamento entre esses quadros pode ser pequeno demais mesmo durante uma quicada real. A nova análise considera janelas de observações mais amplas, limitadas no tempo, preservando os deslocamentos mínimos e conferindo a descida e a subida em todas as observações intermediárias. Posições apenas previstas não confirmam um contato. Mão, oclusão, lacunas de quadros, superfície e deduplicação continuam sendo verificadas.

Arquivos principais: `mobile/android/app/src/main/java/ai/tennisvision/camera/LocalAnalysis.kt`, `mobile/ios/Sources/TennisVisionCore/LocalVision.swift` e testes correspondentes `TargetedDetectionTest.kt`/`TargetedDetectionTests.swift`.

### Seleção da superfície e marcas de impacto

O vídeo de referência mostra uma configuração de chão cancelada antes de concluir a segunda etapa. O modo anterior, parede, permaneceu selecionado. Isso não permite interpretar os contatos exibidos como validação de quicadas no piso. A interface agora identifica explicitamente CHÃO, PAREDE ou RASTREAMENTO. Durante a edição mostra o modo em preparação; cancelar informa qual modo foi mantido. Cancelar continua preservando a configuração anterior, sem aplicar um rascunho incompleto.

A execução no Android emulado também revelou uma corrida entre a câmera e o editor: um quadro processado antes da gravação da área podia chegar depois dela, e sua geometria antiga tinha prioridade ao reabrir a marcação. A configuração permanecia guardada, mas o rascunho podia aparecer vazio ou com outra superfície. Android e iPhone agora só aceitam a geometria e a compensação de movimento de um quadro se corresponderem à configuração atual. Um quadro antigo continua mostrando seus próprios pixels e a bola observada, mas não substitui a área nem adiciona um contato da referência anterior. Preferências recentes de som e visibilidade também não são revertidas por um quadro atrasado.

As marcas eram círculos estáticos, sem animação de chegada. No iPhone, a visibilidade também dependia indevidamente da confirmação da área, e alterar preferências de som ou de marcas podia ocultar contatos anteriores. No Android, revisar um contato antigo depois de mudar a referência podia recolocar marcas de outra referência na imagem atual.

Agora, contatos apresentados recebem um pulso curto de 480 ms; o círculo permanece depois. A mesma transformação de imagem e compensação de movimento posiciona imagem, bola, área, contato e seleção por toque. O pulso é limitado, sem animação permanente quando não há contato novo. Abrir outra visualização no iPhone não reinicia a animação de contatos antigos. Revisar histórico não restaura marcas em uma lente ou área diferente. Marcas incertas mantêm a indicação REVISAR; a animação não transforma um candidato em contato confirmado.

A captura de tela do teste instrumentado também revelou rótulos sobrepostos quando impactos próximos estavam na mesma altura. As etiquetas dos três contatos mais recentes agora procuram espaço dentro da imagem, preservando os círculos nas coordenadas originais. O contato mais recente tem prioridade; se não houver espaço, o texto mais antigo é omitido, mantendo sua marca e revisão por toque. IN, OUT, REVISAR e velocidade deixam de se sobrepor entre si. O posicionamento é limitado a poucas tentativas, sem reorganização contínua de todos os contatos do histórico.

Arquivos principais: `LocalCourtView.kt`, `LocalTrainingActivity.kt`, `LocalTrainingModel.swift` e `LocalTrainingView.swift`.

### Enquadramento da câmera

O iPhone impunha uma proporção mínima de 0,85 ao contêiner, mesmo quando a imagem era vertical, por exemplo 9:16. A imagem cabia dentro de uma caixa mais larga, produzindo faixas laterais. No Android, o painel de altura ponderada podia reservar um grande bloco vazio abaixo da imagem.

Os componentes agora usam a proporção real da imagem. O painel Android mede a altura dos controles e ajusta o espaço da prévia, sem manter o bloco vazio. As ações do editor têm espaço reservado. No iPhone, iniciar uma marcação abre o editor em tela cheia, para preservar acesso aos comandos com imagens verticais. A correção não estica os pixels nem recorta a quadra para preencher o contêiner. Em telas e imagens com formatos distintos pode existir espaço fora do componente de vídeo; isso não altera as coordenadas nem esconde parte do quadro.

Arquivos principais: `LocalCourtView.kt` (`LocalVideoPanel` e `LocalCourtView`), `LocalTrainingActivity.kt`, `activity_local.xml` e `LocalTrainingView.swift`.

### Áreas temporárias

Antes, Android armazenava polígonos e limite do piso em SharedPreferences; iPhone gravava essas informações no arquivo de configuração local. A ação de nova sessão limpava eventos ativos, mas não limpava as áreas.

As áreas agora existem somente na memória da sessão, separadas por câmera, lente, resolução e orientação. Trocar entre chão e parede durante a mesma sessão conserva os desenhos separados. Iniciar nova sessão limpa todas as áreas. Fechar completamente e abrir o aplicativo também inicia sem geometria, inclusive após morte do processo. Apenas colocar o app em segundo plano não é tratado como encerramento completo da sessão.

A migração remove a geometria antiga sem apagar histórico, evidências de contato, pareamento, perfil de captura ou preferências de som e marcas. No iPhone, conclusões assíncronas de gravação são vinculadas à sessão e câmera que as originaram: uma gravação antiga não pode reaplicar uma área depois de iniciar outra sessão.

Arquivos principais: `LocalSessionStore.kt`, `LocalTrainingActivity.kt`, `LocalTrainingModel.swift` e `VisionSessionSettings.swift`.

### Processamento, memória e entrega de quadros

No Android, o buffer nativo da câmera permanecia ocupado durante conversão, detecção e entrega, e os planos YUV eram alocados novamente a cada quadro. Agora, os planos são copiados para armazenamento reutilizável e o buffer nativo é devolvido antes do trabalho mais pesado. A imagem RGB entregue permanece independente, para que uma captura seguinte não altere a imagem visível ou a evidência salva. Resolução, cor, strides e rotação continuam cobertos por testes.

No iPhone, o modelo criava uma segunda tarefa na fila principal depois que a câmera já havia selecionado o quadro mais recente. Essa segunda fila foi removida: o callback, que já chega na fila principal, atualiza o modelo diretamente. Eventos de contato continuam separados dos quadros de prévia descartados, para não perder a contagem ao substituir uma imagem atrasada.

Arquivos principais: `LocalCamera.kt`, `Yuv420.kt` e `LocalTrainingModel.swift`.

## Evidências focalizadas disponíveis

Os números abaixo são verificações locais de desenvolvimento. A execução no Android emulado e as compilações Apple são apresentadas separadamente na validação final.

- Em sete amostras de bola segurada na mão, rotuladas manualmente a partir de uma gravação de tela, a localização dentro da tolerância fixada passou de 1/7 para 7/7. O erro médio corrigido foi 6,81 pixels do recorte. Em três amostras negativas limpas e uma com uma marca antiga sobreposta, houve zero observações antes e depois. Cada recorte inicia o detector novamente; portanto isso mede aquisição independente, não continuidade do rastreamento ou impacto. Trata-se de uma amostra pequena usada na investigação, não de um conjunto independente de avaliação nem de uma taxa geral de precisão.
- Regressões com pixels e trajetórias conhecidos exercitam quicadas nas taxas de entrada simuladas de 30, 60, 120 e 240 FPS, além de bola parada, oscilação, queda sem retorno, contato acima do piso e mudança de direção enquanto a bola é carregada. A trajetória do teste de alta frequência usa 120 pixels por segundo, sem conversão para velocidade física. Essas taxas pertencem aos timestamps do teste; não comprovam que um aparelho entregue ou analise imagens nessa velocidade.
- Um ensaio de alocações na JVM, com 16 conversões por resolução, mediu redução aproximada de 1,84 MB por quadro em 720p e 4,15 MB por quadro em 1080p ao reutilizar os planos. O resultado indica menor pressão de alocação nesse ensaio; não mede FPS, aquecimento, atraso ou consumo de bateria do celular.
- Testes Robolectric com renderização nativa verificaram contorno sem cobrir o centro da bola, pulso no contato com compensação de coordenadas, expiração do pulso mantendo a marca, cancelamento da segunda etapa do chão e revisão histórica sem restaurar marcas antigas.
- Testes de layout incluem imagem horizontal e vertical em telas horizontais e verticais, os quatro cantos, proporção, posição das ações de salvar/cancelar e ausência do bloco vazio abaixo do vídeo.
- Testes de armazenamento verificaram áreas separadas na mesma sessão, descarte ao recriar o armazenamento, limpeza em nova sessão, migração da geometria antiga e preservação de preferências, pareamento e histórico.
- Regressões de quadros atrasados verificam chão e parede salvos, reabertura do editor após quadro antigo sem área, descarte de contatos da referência antiga e manutenção de uma translação legítima da câmera sem reverter preferências.
- Foram adicionados testes Swift de limpeza seletiva da geometria, serialização sem área persistida, rejeição de quadros antigos, disposição de etiquetas e manutenção da referência ao alternar som ou marcas. Eles passaram na CI Apple, junto com as regressões de detecção.

O vídeo fornecido é uma **gravação de tela comprimida com interface e anotações anteriores**, não uma sequência original do sensor. Seus recortes ajudam a reproduzir falhas de aparência, mas não permitem calcular de forma confiável a taxa de acerto de impactos reais no chão. Nenhum vídeo, imagem pessoal ou caminho privado foi incluído neste relatório ou nos testes públicos.

## Validação final

- Suíte completa Android JVM/Robolectric: **240 testes, zero falhas, erros ou testes ignorados**. O comando conjunto de testes, lint e APK final concluiu em 1 min 54 s neste computador. Foram acrescentados 11 testes de detecção, além dos testes de interface, persistência, etiquetas e buffers. A suíte completa encontrou uma regressão de seleção entre objeto parado e bola em movimento; foi corrigida antes desta execução final.
- APK Android: **0.6.10, código 23**, compilado com otimização R8. Assinatura conferida com a mesma identidade da versão anterior; vozes IN/OUT verificadas por hash dentro do APK. SHA-256: `2a67b2879341bfc58af3a951d5dac4fc645a93ded84ed0df843c23506c6ffbcb`. Instalação em celular físico: não realizada.
- Lint Android concluiu sem erros, com **151 avisos** no projeto, incluindo internacionalização, alocações em desenho, APIs e componentes de transmissão. Não se declara eliminação de todos os avisos existentes.
- Testes Python de fonte AltStore, publicação conjunta e decodificação/integração dos arquivos de voz: **20 aprovados**, nenhum ignorado, com FFmpeg configurado.
- Testes executados dentro do Android: **10 aprovados, zero falhas ou ignorados** — oito cenários de treino local e duas execuções separadas de permissão. Emulador Android 15/API 35, `sdk_gphone64_x86_64`, perfil Pixel 7 Pro; nenhum celular físico. A suíte de treino levou 120,425 s; conceder e negar permissão levaram 7,598 s e 11,005 s. [Execução e artefato de evidências](https://github.com/Laikan2/tennis-vision-ai/actions/runs/39110834300), [resultado estruturado](mobile-0.6.10/android-runtime.json).
- iOS: **104 testes Swift aprovados**, compilação para iPhone e compilação para simulador aprovadas. A integração recebeu e decodificou 60 quadros H.264. [Execução nativa e logs](https://github.com/Laikan2/tennis-vision-ai/actions/runs/39109347874). IPA 0.6.10, build 11, SHA-256 `f518fe41025c6d279618c2c5c87dc8e156c412fe6dc68b9c18636a26395e464f`; estrutura, versão, contrato de funcionalidades e vozes foram conferidos no arquivo real. **A abertura no simulador excedeu o limite de dois minutos e não gerou captura.** Esse passo diagnóstico opcional não bloqueia o resultado verde global da CI. Portanto não se afirma que a interface iOS foi testada em execução, nem que houve instalação em iPhone físico.
- [Android e iPhone 0.6.10 publicados](https://github.com/Laikan2/tennis-vision-releases/releases/tag/mobile-v0.6.10). Os downloads públicos do APK e IPA foram lidos novamente e seus tamanhos e SHA-256 coincidem com os arquivos compilados. A fonte pública do AltStore anuncia 0.6.10, e o APK 0.6.10 é o artefato Android estável mais recente. A referência de atualização do Windows permanece em v0.6.11. Não foi realizada instalação em celular físico.

Evidências estruturadas: [compilação Android](mobile-0.6.10/android-build.json), [execução Android](mobile-0.6.10/android-runtime.json), [CI Apple](mobile-0.6.10/ios-ci.json), [amostras de desenvolvimento](mobile-0.6.10/development-crops.json), [alocações](mobile-0.6.10/plane-allocation.json) e [publicação](mobile-0.6.10/publication.json).

JVM/Robolectric executa lógica e componentes Android no computador. Um teste instrumentado em emulador executa dentro de uma versão do Android emulada. Compilar na CI demonstra que o projeto compila naquele ambiente; somente lançar e testar o aplicativo demonstra o comportamento exercitado no runtime. Nenhuma dessas verificações, isoladamente, comprova comportamento da câmera física.

### Execução Android offline e inspeção visual

A execução instrumentada usou a variante isolada `audit`, sem permissão de internet, no commit `01ccd09e7cd88d2c74c521b8d9214e1e11bf082a`. Seu código de produção é o de `53704999db2061c01aa3c0b16f8d08d51973b4e2`; os commits posteriores alteraram somente o teste Android. O computador da CI hospedou o emulador e os comandos de teste. Nenhum backend Windows, servidor de câmera ou serviço de inferência participou desses cenários.

Foram exercitados a câmera emulada e o contorno antes de iniciar treino; a marcação e o cancelamento de chão e parede; restauração do rascunho correto ao alternar superfícies; nova sessão e histórico; recuperação após gravação interrompida; segundo plano, recriação e dez toques rápidos de iniciar/pausar; concessão e duas negações da permissão da câmera, incluindo a abertura dos ajustes do Android. Os testes verificam estado interno, pixels, eventos persistidos e respostas do sistema, além do texto da interface.

O teste de bloqueio preparou bateria simulada descarregando a 50%, aguardou o estado do framework e confirmou `PowerManager.isPowerSaveMode` antes e depois de apagar e reativar a tela. Confirmou configurações iguais e treino pausado ao retornar. A rede padrão ativa estava ausente antes e depois da suíte, e `activeNetwork` permaneceu nula no cenário de bloqueio. O preparo inicial do emulador precisou de correções: o modem recriava rede móvel após o boot; a economia era solicitada antes de estabilizar a bateria; uma verificação intermediária consultava a capacidade do HAL, que não acompanha a bateria simulada por `dumpsys`. O teste final consulta o broadcast de bateria do framework e continua exigindo a economia real ativa, sem remover as asserções.

Um vídeo **sintético H.264, 320 × 240**, foi decodificado por MediaCodec dentro do Android: **191 quadros**, sequência **IN, OUT, REVISAR**, exatamente três eventos adicionais no histórico, imagens de evidência e uma solicitação OUT aceita pelo SoundPool. O tempo da chamada do detector teve mediana de **6,55 ms** e percentil 95 de **27,35 ms**. Isso mede o detector nesse emulador e nessa sequência pequena, sem incluir captura e apresentação completa; não comprova FPS do celular nem precisão física. O emulador foi iniciado com áudio desativado, portanto não foi verificada voz audível. O cenário de parede registrou candidatos para revisão e não emitiu OUT indevido; ele não prova contato real em profundidade.

As capturas finais foram inspecionadas: contorno ao redor da bola sintética, área do chão restaurada e visível, retorno da câmera após bloqueio e rótulos IN/OUT/REVISAR com velocidade sem sobreposição entre si. As linhas dos rótulos terminam nas marcas originais, sem deslocar o contato. O artefato também registra uma amostra de métricas da câmera emulada: 1280 × 720, 11 FPS recebidos para até 30 solicitados e aproximadamente 131 ms de captura até a interface naquele instante. Esses valores descrevem o emulador, não o S25 Ultra ou iPhone, e não constituem um ensaio prolongado.

## Limites e testes ainda necessários

Não foram medidos nesta revisão, em celular físico, exposição real, quadros recebidos, atraso ponta a ponta, estabilidade térmica, bateria, segundo plano com câmera real ou funcionamento prolongado em quadra. Também não há validação desta entrega para saques a 200 km/h, bola distante em quadra inteira ou todos os impactos sob oclusão.

Continuam necessários ensaios com posições de contato conhecidas, câmera fixa, superfície concluída no editor, boa luz e gravações originais do sensor. É preciso comparar impactos reais, perdidos, extras e duplicados; medir erro de posição perto da linha; testar a bola segurada, parada e em velocidades diferentes; e repetir após interromper e reabrir o app. Os testes devem distinguir chão de parede e registrar resolução, exposição e FPS efetivamente recebidos.

A velocidade exibida é uma estimativa lateral baseada no tamanho aparente de uma bola de tênis de 67 mm. Não equivale à velocidade tridimensional medida por radar. Uma única câmera não prova o contato em profundidade, e bola pequena, borrão ou oclusão podem tornar posição, velocidade ou decisão indisponíveis. Nesses casos a interface deve manter a incerteza, sem inventar um impacto ou uma decisão IN/OUT.
