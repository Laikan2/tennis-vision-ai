# Tennis Vision mobile 0.6.11 — análise completa no iPhone

Revisão de 18 de outubro de 2026. **Versão 0.6.11 publicada para Android e iPhone**. A instalação e duas medições de análise completa foram conferidas no iPhone 15 Pro físico: aproximadamente 60 FPS recebidos, com médias de 4,32 e 4,53 ms por quadro. A precisão de quicadas reais ainda não foi medida com posições de referência.

## Problema reproduzido no aparelho

O iPhone 15 Pro conectado por USB tinha a versão 0.6.10, build 11, e iOS 27.2. A leitura inicial da interface mostrou 57 FPS recebidos para 60 solicitados na prévia. O diagnóstico explícito de câmera analisou imagens reais de 720 × 1280 por 20,04 segundos: 437 quadros entregues à interface, média de análise de 43,54 ms, máximo de 57,90 ms e taxa final de entrega de 19,52 FPS. A amostra final de conversão foi 2,61 ms e a idade de processamento até a interface foi 51,55 ms. Essa idade exclui exposição, leitura do sensor e apresentação física da tela.

A diferença entre a prévia e o treino é relevante: a prévia procura a bola numa cópia com lado maior de até 640 pixels; o treino processa a resolução completa. Os acessos a vários Arrays armazenados no detector Swift repetiam verificações de acesso e de unicidade em laços por pixel e durante a expansão dos componentes. Esse trabalho ocorre milhões de vezes em cada quadro. A versão anterior foi medida novamente em comparação controlada com a mesma entrada e compilação Release.

O diagnóstico do detector executado no iPhone 0.6.10 também concluiu seus 18 casos sintéticos: 288 quadros, 276 localizações na tolerância, duas posições erradas e dez sem localização. IN, OUT e REVISAR apareceram nos casos conhecidos. O editor de chão abriu e cancelou; os 30 eventos de uma sessão existente e suas preferências ficaram byte a byte iguais após os testes automáticos. Esses ensaios não incluíram quicadas físicas anotadas nem comprovam precisão em partidas.

## Correção de processamento

O detector agora obtém um acesso exclusivo aos buffers de trabalho por quadro, em vez de repetir o custo de atualização de Arrays em cada pixel. Os ponteiros são usados apenas dentro do processamento síncrono e não são guardados, enviados à interface ou usados por outra fila. Os arrays permanecem vivos durante esse acesso. A referência ao quadro anterior e a troca dos buffers de cor ocorrem depois que todos os acessos temporários terminam.

A resolução, os pixels, os limiares de cor e confiança, as verificações de mão/oclusão, a aquisição, a trajetória e as regras de contato permanecem as mesmas. O caminho otimizado continua analisando a imagem inteira. Não há descarte periódico de partes da quadra nem promessa de encontrar uma bola cujos pixels não tenham detalhe suficiente.

O arquivo principal é `mobile/ios/Sources/TennisVisionCore/LocalVision.swift`. O Android já utiliza arrays primitivos neste caminho e não recebe uma promessa de ganho equivalente por esta alteração específica de Swift.

## Editor do limite do piso

Ao selecionar os dois pontos, o aplicativo exige uma separação horizontal mínima para conseguir definir qual região está abaixo do limite. Antes, uma linha curta ou vertical apenas deixava o botão de salvar desativado, sem explicar o motivo. No iPhone, novos toques também eram ignorados depois do segundo ponto. No Android, um toque distante podia reiniciar a linha em vez de ajustar um extremo.

Android e iPhone agora mostram a contagem de pontos e o motivo do bloqueio, permitem corrigir o extremo mais próximo com outro toque e informam quando o limite está pronto para salvar. A linha inválida recebe cor de aviso no iPhone. A regra de estabilidade da linha é mantida; uma marcação geometricamente inválida não é aceita para forçar um resultado IN/OUT. Foram adicionadas regressões para dois pontos próximos, correção por terceiro toque e preservação dos demais pontos.

Não houve captura da marcação exata que o usuário tentou salvar: ao verificar a tela, o editor já estava fechado. Portanto a causa específica daquele desenho não é afirmada como comprovada; foram corrigidos os bloqueios de interação e feedback reproduzíveis no código e nos testes.

## Comparação e validação

O [ensaio comparativo final](https://github.com/Laikan2/tennis-vision-ai/actions/runs/39145040158) passou. Na repetição com ordem invertida, a redução do tempo correspondeu a **4,78–7,20 vezes** entre as 12 cenas/resoluções. Em 1280 × 720, as medianas passaram de **39,20–45,69 ms** para **5,75–9,24 ms**, dependendo da cena. Em 1920 × 1080, passaram de **89,56–98,47 ms** para **12,99–20,60 ms**. Cada ordem comparou 144 assinaturas de quadros, sem divergência nos campos registrados.

- **241 testes Android JVM/Robolectric aprovados**, sem falhas ou ignorados. O teste de recortes privados foi executado explicitamente; as imagens não fazem parte do repositório nem do APK. Lint Release: zero erros/fatais e 150 avisos restantes.
- **108 testes Swift aprovados** na [CI Apple](https://github.com/Laikan2/tennis-vision-ai/actions/runs/39145040235). Compilações para iPhone e simulador aprovadas; recepção de 60 quadros H.264 decodificados aprovada. A tentativa opcional de abrir o app no simulador excedeu dois minutos e não gerou captura. O resultado verde global da CI não é tratado como prova de execução da interface iOS.
- **Dois testes com AddressSanitizer aprovados**, incluindo troca de dimensões, bordas e vida útil dos buffers, sem erro de memória detectado nesses cenários.
- **20 testes Python aprovados** para publicação conjunta, fonte AltStore e arquivos de voz.
- APK real 0.6.11, código 24: manifesto e assinatura verificados com a identidade anterior. SHA-256: `96314a1970377c58c8be28a0df380c0af6842e8d2d49deda67c1ec1f25ee12fc`.
- IPA real 0.6.11, build 12: estrutura, versão, contrato compartilhado e hashes das vozes IN/OUT conferidos. SHA-256: `5552557ab6ba7ead97020693e742b3b368ba2392c950eb6d16afd3de2aca6bc1`.
- **10 testes instrumentados aprovados** em [Android 15/API 35 emulado](https://github.com/Laikan2/tennis-vision-ai/actions/runs/39146037729), modelo `sdk_gphone64_x86_64`, sem rede padrão ativa e com variante de auditoria sem permissão de internet. Incluíram edição e cancelamento de chão/parede, sessões e histórico, toques rápidos, segundo plano, recriação, permissões, contorno na prévia, armazenamento interrompido e bloqueio de tela com economia de bateria verificada.
- MediaCodec decodificou 191 quadros sintéticos no Android e o fluxo completo produziu IN, OUT e REVISAR, persistência dos eventos e uma solicitação OUT aceita pelo SoundPool. A captura de tela foi inspecionada: etiquetas e velocidade estimada legíveis, ligadas aos círculos originais. Não foi confirmado som audível: o emulador usa áudio desativado.

A primeira rodada Android falhou com um aviso de que o Pixel Launcher do emulador não respondia, sobrepondo a interface e impedindo a injeção de toques. Os testes de permissão também ficaram obstruídos. A rodada não é contada como aprovada. Uma nova instância, com o processo de compilação encerrado antes de iniciar o emulador, passou todos os cenários sem remover as verificações do aplicativo.

A [versão pública 0.6.11](https://github.com/Laikan2/tennis-vision-releases/releases/tag/mobile-v0.6.11) foi publicada. Os downloads públicos do APK e IPA foram lidos novamente e coincidem em tamanho e SHA-256 com os arquivos compilados. A fonte do AltStore anuncia 0.6.11, e o APK estável mais recente também é 0.6.11. A referência de atualização do Windows permanece em v0.6.11. A instalação no iPhone foi verificada na etapa física descrita a seguir; não há validação nova em Android físico.

Evidências estruturadas: [compilação Android](mobile-0.6.11/android-build.json), [execução Android](mobile-0.6.11/android-runtime.json), [CI Apple](mobile-0.6.11/ios-ci.json), [resumo do desempenho](mobile-0.6.11/performance.json), [primeira ordem](mobile-0.6.11/comparison.json), [ordem invertida](mobile-0.6.11/comparison-repeat.json), [iPhone antes da correção](mobile-0.6.11/iphone-before.json) e [publicação](mobile-0.6.11/publication.json).

O benchmark público usa imagens geradas de 1280 × 720, 720 × 1280 e 1920 × 1080: bola pequena, piso quente, quadra verde e cena negativa. A geração da imagem fica fora do tempo medido. As versões são compiladas em Release no mesmo job e recebem os mesmos quadros e timestamps. Cada cena tem uma rodada de aquecimento e duas rodadas medidas. A ordem é repetida de forma invertida com os binários já compilados, para reduzir a influência da ordem e do aquecimento.

As assinaturas comparam posição, tamanho, confiança, atributos da bola, trajetória, previsão, velocidade, contato, mensagem e compensação da câmera em cada quadro. A comparação exige igualdade dos campos registrados; não considera apenas a quantidade de detecções. Os dados são sintéticos e não contêm filmagens pessoais. Os tempos são da máquina da CI, não FPS de um celular.

Além da suíte Swift, um ensaio com AddressSanitizer exercita dimensões pequenas, ímpares e grandes, trocas de resolução, reset e preservação dos pixels de entrada. Ele procura acessos fora dos limites e referências inválidas no novo caminho de buffers.

## Nova medição no iPhone físico

O aparelho foi reconectado por USB com o Tennis Vision **0.6.11, build 12**, instalado. A versão foi conferida pelo serviço de instalação e pela interface. O modelo é iPhone 15 Pro, com iOS 27.2, build 24B5110a. A bateria informava 38% no início desta etapa.

Foram executadas duas sessões independentes do diagnóstico explícito de câmera, ativando o mesmo núcleo completo usado no treino. Na primeira, **1.202 quadros** chegaram à interface em **20,01 s**, com taxa final de **59,98 FPS**, média de análise de **4,32 ms** e máximo de **7,90 ms**. Na repetição, **1.201 quadros** chegaram em **20,00 s**, com taxa final de **60,00 FPS**, média de **4,53 ms** e máximo de **8,35 ms**. As amostras finais de conversão foram 1,87 e 2,32 ms; a idade do processamento até a interface foi 6,91 e 8,28 ms. Essa idade não é o atraso total entre o mundo real e a tela: exclui exposição, leitura do sensor e apresentação física do quadro. A exposição mostrada na interface era de 16,4 ms.

A resolução foi **1280 × 720**, com 60 FPS solicitados. O teste anterior de 0.6.10 usou **720 × 1280**, também com 60 solicitados: o número de pixels é igual, mas a orientação e a cena mudaram. Por isso, a diferença entre 43,54 ms/19,52 FPS antes e 4,32–4,53 ms/~60 FPS agora é uma comparação entre medições reais, sem tratar as cenas como uma entrada controlada idêntica. A limitação próxima a 20 FPS não apareceu nestes dois ensaios da nova versão. O ensaio controlado com entradas idênticas continua sendo o benchmark da CI descrito acima.

O diagnóstico sintético também foi repetido pelo app instalado no iPhone: **18 casos, 288 quadros, 276 localizações na tolerância, duas posições erradas e dez sem localização**. Os resultados registrados coincidiram com os casos de 0.6.10, incluindo IN, OUT e REVISAR. Os critérios sintéticos passaram, mas as falhas de localização continuam registradas; esses números não representam precisão com bolas e quicadas físicas.

O histórico de uma sessão com **30 eventos** permaneceu byte a byte igual durante os testes automáticos. Nenhum evento, som ou imagem de diagnóstico foi acrescentado ao treino. O aplicativo foi reaberto no modo normal e a interface confirmou câmera pronta, 60 FPS e o botão Iniciar treino. A verificação física de desenhar e salvar o limite do piso depende da interação do usuário e permanece separada deste ensaio de desempenho.

As [evidências estruturadas do iPhone após a correção](mobile-0.6.11/iphone-after.json) contêm as duas medições e os limites. Capturas pessoais da câmera, identificadores do aparelho e o conteúdo do histórico não foram incluídos no repositório.

## Limites restantes

A medição anterior no iPhone descreve uma cena real sem posições e impactos anotados. As 410 observações daquele diagnóstico não são contadas como acertos, pois não existe verdade de referência para elas. Os 43,54 ms do aparelho também não devem ser comparados diretamente aos milissegundos de um computador diferente.

As duas medições físicas da nova versão duraram aproximadamente 20 segundos cada. Elas não equivalem a um teste longo de aquecimento ou a uma partida. A medição inicial de 0.6.10 foi preservada separadamente e não é apresentada como execução da nova versão.

O aumento de velocidade do núcleo precisa ser distinguido da taxa real de captura e renderização. A exposição, iluminação, temperatura e perfil da câmera podem limitar o resultado. Não há validação de saques a 200 km/h, todos os impactos, 120/240 FPS sustentados ou precisão de arbitragem. Velocidades continuam laterais e estimadas; os candidatos incertos de parede continuam sujeitos a revisão.
