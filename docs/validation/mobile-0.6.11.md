# Tennis Vision mobile 0.6.11 — análise completa no iPhone

Revisão de 18 de outubro de 2026. **Versão 0.6.11 publicada para Android e iPhone**, com os resultados disponíveis consolidados abaixo. A nova medição física no iPhone permanece pendente.

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

A [versão pública 0.6.11](https://github.com/Laikan2/tennis-vision-releases/releases/tag/mobile-v0.6.11) foi publicada. Os downloads públicos do APK e IPA foram lidos novamente e coincidem em tamanho e SHA-256 com os arquivos compilados. A fonte do AltStore anuncia 0.6.11, e o APK estável mais recente também é 0.6.11. A referência de atualização do Windows permanece em v0.6.11. A instalação da nova versão em celular físico não foi verificada.

Evidências estruturadas: [compilação Android](mobile-0.6.11/android-build.json), [execução Android](mobile-0.6.11/android-runtime.json), [CI Apple](mobile-0.6.11/ios-ci.json), [resumo do desempenho](mobile-0.6.11/performance.json), [primeira ordem](mobile-0.6.11/comparison.json), [ordem invertida](mobile-0.6.11/comparison-repeat.json), [iPhone antes da correção](mobile-0.6.11/iphone-before.json) e [publicação](mobile-0.6.11/publication.json).

O benchmark público usa imagens geradas de 1280 × 720, 720 × 1280 e 1920 × 1080: bola pequena, piso quente, quadra verde e cena negativa. A geração da imagem fica fora do tempo medido. As versões são compiladas em Release no mesmo job e recebem os mesmos quadros e timestamps. Cada cena tem uma rodada de aquecimento e duas rodadas medidas. A ordem é repetida de forma invertida com os binários já compilados, para reduzir a influência da ordem e do aquecimento.

As assinaturas comparam posição, tamanho, confiança, atributos da bola, trajetória, previsão, velocidade, contato, mensagem e compensação da câmera em cada quadro. A comparação exige igualdade dos campos registrados; não considera apenas a quantidade de detecções. Os dados são sintéticos e não contêm filmagens pessoais. Os tempos são da máquina da CI, não FPS de um celular.

Além da suíte Swift, um ensaio com AddressSanitizer exercita dimensões pequenas, ímpares e grandes, trocas de resolução, reset e preservação dos pixels de entrada. Ele procura acessos fora dos limites e referências inválidas no novo caminho de buffers.

## Limites restantes

A medição anterior no iPhone descreve uma cena real sem posições e impactos anotados. As 410 observações daquele diagnóstico não são contadas como acertos, pois não existe verdade de referência para elas. Os 43,54 ms do aparelho também não devem ser comparados diretamente aos milissegundos de um computador diferente.

A nova versão ainda não foi medida no iPhone físico. O aparelho deixou de aparecer no USB após informar bateria baixa; a solicitação para recarregar e reconectar ficou pendente. A medição anterior e os testes sintéticos físicos descritos neste relatório pertencem à versão 0.6.10, não à nova versão.

O aumento de velocidade do núcleo precisa ser distinguido da taxa real de captura e renderização. A exposição, iluminação, temperatura e perfil da câmera podem limitar o resultado. Não há validação de saques a 200 km/h, todos os impactos, 120/240 FPS sustentados ou precisão de arbitragem. Velocidades continuam laterais e estimadas; os candidatos incertos de parede continuam sujeitos a revisão.
