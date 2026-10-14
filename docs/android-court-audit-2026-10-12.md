# Tennis Vision Android 0.6.2 — revisão da quadra

## Entrega e estado real

Foi gerado o APK **0.6.2 / código 15**, com melhorias no enquadramento, seleção de quadra, detecção verde e registro da câmera. **149 testes JVM/Robolectric passaram**. O APK de distribuição passou por compilação Kotlin, R8, redução de recursos, verificação de assinatura e alinhamento. Isso não comprova a câmera nem a detecção numa partida real.

Não houve teste físico da 0.6.2 nesta rodada. A [comunicação ADB](validation/android-court-2026-10-12/adb-device-check.log) não pôde ser estabelecida neste ambiente, que restringe conexões locais. Não foi recebido um vídeo novo do teste na quadra descrito na mensagem. Assim, as falhas abaixo foram investigadas no código e reproduzidas com imagens controladas; não foram medidas diretamente na partida do usuário. A última conferência física, no S25 Ultra SM-S938B / Android 16, foi da [0.6.1](android-fast-ball-audit-2026-10-11.md).

O aplicativo continua executando o treino local no Android, sem Windows ou servidor. A transmissão opcional para o Windows permanece separada. Não houve recompilação do Windows nem alteração dos modelos Python nesta rodada.

## Causas encontradas e correções

### 1. Uma reta de quarto estava sendo usada como quadra

O limite de piso foi criado para distinguir parede e chão nos testes de quarto. Não representa os quatro limites de uma quadra em perspectiva. A interface anterior não tornava essa distinção suficientemente clara.

- **Marcar quadra · 4 cantos** permite quadra inteira ou metade, com quatro toques e ajuste posterior dos cantos. Pontos próximos no fundo não são confundidos com arraste do primeiro ponto.
- O editor usa a imagem inteira com proporção preservada, referência explicitamente pausada, lupa e botões Salvar/Recomeçar/Cancelar sempre visíveis. A posição ao soltar o dedo é respeitada. Cancelar preserva a área anterior.
- Rede, linhas de serviço e laterais de simples são guias projetadas a partir dos quatro cantos. São guias visuais; não fornecem profundidade 3D nem validam automaticamente as caixas de saque.
- **Área livre · arrastar** e **Quarto · limite do piso** continuam disponíveis. Quadra inteira/metade não exige a reta de quarto para gerar contatos candidatos.
- IN/OUT continua se referindo ao contorno marcado. Não foi implementada arbitragem completa de regras de saque, duplas e simples por fase do ponto.

### 2. O enquadramento não podia ser escolhido no modo local

Agora **Enquadramento e detalhe** permite selecionar câmeras traseiras realmente expostas pelo Android e zoom amplo quando anunciado pela câmera. Há perfis **Fluidez até 720p**, **Bola distante até 1080p** e **Economia até 30 FPS**. As taxas regulares são escolhidas conforme as faixas anunciadas e a duração mínima YUV da resolução; não são inventadas faixas de FPS.

A orientação na interface recomenda celular na horizontal, suporte fixo, posição recuada/elevada fora da área de jogo e margem além das linhas para detectar OUT. Uma lente ampla pode fazer caber mais quadra, mas diminui a bola. Filmar meia quadra pode oferecer leitura melhor que enquadrar os dois lados com uma bola de poucos pixels. Software não recupera a parte da quadra que a lente não capturou.

### 3. Pequenos movimentos deixavam a marcação sem referência

- Estabilização óptica e de vídeo são desativadas quando o controle é suportado, evitando alterações automáticas do enquadramento sob a marcação.
- Registro de fundo estima pequenas translações entre quadros, com concordância de pontos distribuídos em pelo menos três quadrantes. Inclui pontos próximos ao contorno para aproveitar linhas finas.
- Quando há evidência suficiente, área, rastro e marcas são deslocados juntos. A configuração salva não é alterada continuamente. A imagem de revisão usa o deslocamento do quadro de evidência.
- Movimento de uma pessoa em primeiro plano não deve arrastar a quadra quando o fundo permanece estático.
- Deslocamentos maiores, giro, mudança de perspectiva ou perda de referência pausam a contagem. Ao reabrir, o usuário precisa conferir visualmente a área. Não há correção geral de homografia/rotação, nem garantia de compensação em fundo sem textura.
- Áreas ficam separadas por câmera, lente/zoom, resolução e orientação. A chave anterior da lente 1× foi mantida. Sessões, gravações, pareamentos e áreas de outras câmeras não são apagados.

### 4. Bola parada sobre verde e borrão eram descartados

O filtro anterior podia juntar bola e piso verde num componente grande e apagar esse componente pela ausência de movimento. Em imagem controlada, a bola estacionária contrastante desaparecia em todos os quadros, inclusive antes do saque.

- Contraste local separa uma bola mais clara do piso verde, sem exigir movimento para toda detecção. Fundo branco não exige que a bola verde seja mais clara que a parede.
- Componentes muito pequenos podem aparecer como incertos. Eles não geram velocidade nem uma decisão automática de linha confiável.
- Borrão alongado admite proporções maiores com evidência temporal. Retângulos sólidos e componentes sem suporte continuam filtrados; não se removeu indiscriminadamente o filtro de forma.
- Continuidade considera distância, dimensão e ambiguidade antes de escolher um alvo. Uma bola perdida não autoriza saltar imediatamente para um objeto verde distante. Vários alvos igualmente plausíveis exigem nova evidência. Movimento único pode ajudar a adquirir a bola ao lado de um objeto verde estático maior.
- Previsões duram no máximo cerca de 60 ms e não contam impactos. Borrão forte suspende a estimativa de velocidade.

O detector local continua sendo visão por cor, forma, contraste e movimento. **Não houve treinamento de rede neural novo**, nem inclusão de dados reais revisados de partidas. Bola ocultada, verde sem contraste, poucos pixels, reflexos e objetos semelhantes ainda podem causar perda de rastreamento e falsos positivos.

## Testes executados e resultados

### JVM e interface

**149 testes, zero falhas**, em 26 classes. Os 50 testes puramente Kotlin são um subconjunto desses 149, não um total adicional. Cobertura relevante: rastreamento, contatos IN/OUT/REVISAR, ausência de contato, ambiguidade, câmera deslocada, projeção de quadra, quatro toques, cantos próximos, arraste, cancelamento, persistência por lente e manutenção de pareamento/histórico. Testes anteriores de atualização/QR/captura também fazem parte da regressão.

A interface foi exercitada com Robolectric **SDK 33**, gráficos nativos, usando os recursos e a Activity do app, em retrato e paisagem. Não é um teste com emulador nem com um S25 físico. Capturas usam uma quadra sintética e não imagens da partida. Salvar/Recomeçar/Cancelar ficam visíveis em ambas as orientações. [Log final](validation/android-court-2026-10-12/unit-tests-final.log), [paisagem](validation/android-court-2026-10-12/landscape-editor.png), [retrato](validation/android-court-2026-10-12/portrait-editor.png).

### Casos controlados antes/depois

Três falhas foram reproduzidas no detector anterior, preservado para a comparação. [Falhas anteriores](validation/android-court-2026-10-12/core-before.log).

- Bola parada sobre piso verde contrastante: **0/20 → 20/20** quadros localizados no cenário controlado.
- Borrão móvel 6:1: **0/12 → 11/12** posições localizadas. A primeira aquisição ainda pode falhar; não é acompanhamento perfeito.
- Alvo de 2 × 2 pixels: antes descartado, agora candidato de confiança limitada (0,6), sem velocidade e com contato para revisão.
- Objeto verde estático distante após perda da bola: **0/12 tomadas indevidas** no caso testado.
- Bola em movimento ao lado de objeto verde maior parado: **11/12** posições da bola localizadas; o primeiro quadro fica ambíguo.
- Dois alvos igualmente plausíveis: nenhum rastro aleatório nem impacto contado no caso testado.
- Quadra inteira marcada sem a reta do quarto: um IN e um OUT nos dois arremessos sintéticos conhecidos, sem duplicação. Bola não resolvida: REVISAR.

O conjunto de **19 trajetórias sintéticas** de 1280 × 720 cobre diâmetros de 3/6/12 pixels, até 80 pixels por quadro, borrão, contato entre quadros, OUT, oclusão e passagem reta no ar. As nove combinações sem borrão tiveram 12/12 posições localizadas; o diâmetro de 3 pixels passou a exigir revisão. OUT conhecido foi OUT, contato ocultado foi REVISAR, passagem reta no ar não gerou contato. Erro de contato de 0–0,5 pixel nesses desenhos não representa precisão em quadra real. [CSV final](validation/android-court-2026-10-12/fast-ball-after.csv).

A confirmação usa observações posteriores ao contato: 25–37,5 ms de evidência nos casos de 60 FPS e 66,7 ms no caso de 30 FPS. Esses tempos são da linha temporal sintética; não incluem exposição, captura, conversão nem tela. Deslocamento em pixels/quadro não foi convertido em uma alegação de saque real a 200 km/h.

### Custo de processamento

Ensaio no computador Windows, JDK 17, JVM Kotlin, identificador AMD64 Family 25 Model 33, 16 processadores lógicos. Cada caso usa 8 quadros de aquecimento e 32 medidos. O custo mede somente `LocalAnalysis.process`, sem construção da imagem, câmera, conversão YUV ou desenho. Não é benchmark de FPS do celular.

- Em piso verde 720p, medianas anteriores de **3,77 ms parada / 6,58 ms movimento** passaram a **8,37 / 8,41 ms**. A detecção da bola parada passou de 0/32 para 32/32; movimento manteve 32/32. Não houve posições falsas nesses casos.
- Em 1080p, medianas anteriores de **13,56 / 14,88 ms** passaram a **17,93 / 17,83 ms**, com a mesma melhoria de 0/32 para 32/32 parada e manutenção de 32/32 em movimento.
- A primeira implementação da correção custava aproximadamente 18/42 ms em 720p/1080p nesse cenário. Antes da entrega, foi substituída por contraste em blocos e reaproveitamento da classificação de cor. Isso reduziu o custo dessa primeira implementação; **a versão final ainda custa mais que o detector antigo**, que falhava na bola parada.
- O P95 do movimento em 720p chegou a 19,86 ms nesta execução. Não se comprovou ausência de engasgos, latência óptica mínima nem 60 FPS sustentados no aparelho. 1080p deve ser escolhido observando a taxa recebida; pode reduzir a fluidez.

Evidências: [antes](validation/android-court-2026-10-12/green-court-before.csv), [depois](validation/android-court-2026-10-12/green-court-after.csv), [gerador do ensaio](validation/android-court-2026-10-12/CourtBenchmark.kt).

### Empacotamento e ligação com o painel

Quatro testes de preparação/seleção de pacotes do desktop passaram. A referência do backend foi atualizada para 0.6.2. A chamada direta da rota Python confirmou nome, tamanho, SHA-256 e erro 404 quando o APK está ausente. **Não houve validação HTTP desta rota**: TestClient ficou bloqueado ao criar o socket local interno do asyncio no ambiente restrito; o ensaio foi interrompido e o diagnóstico preservado. [Testes desktop](validation/android-court-2026-10-12/desktop-apk-tests.log), [rota direta](validation/android-court-2026-10-12/apk-route-check.json), [diagnóstico HTTP incompleto](validation/android-court-2026-10-12/backend-apk-diagnostic.log).

## APK e atualização

[Tennis-Vision-Camera-Android-v0.6.2.apk](../mobile/releases/Tennis-Vision-Camera-Android-v0.6.2.apk), **3.220.532 bytes**. SHA-256: `c8b4a4cc3711b8f0093bd8ca80d8ee7935ad80af7f802b508d5f20baecaee09d`.

Pacote `ai.tennisvision.camera`, código 15, mínimo Android 8 / API 26, alvo API 37. APK alinhado e assinatura v2 válida. Certificado SHA-256 igual ao anterior: `faf843630ad0a52fef6ca4f844cae592e3da1e266b7b823f481c34d3b2049ce9`. Continua usando a chave de desenvolvimento já entregue, para preservar compatibilidade. Instale sobre a versão anterior sem desinstalar. A versão 0.6.1 foi preservada para recuperação. [Metadados](validation/android-court-2026-10-12/apk-manifest.json), [assinatura](validation/android-court-2026-10-12/apk-signature.txt), [compilação](validation/android-court-2026-10-12/build-final.log).

Os componentes de atualização GitHub foram preservados e seus testes continuaram na regressão. **A publicação da 0.6.2 não foi concluída:** a [consulta via CLI ao GitHub](validation/android-court-2026-10-12/github-connectivity.log) falhou por conexão com `api.github.com` neste ambiente. O arquivo acima é local, não um novo download público nem uma atualização já recebida pelo telefone. O Windows instalado também não foi atualizado.

A compilação usou caches locais offline. O ambiente bloqueia a comunicação local de workers/daemon; foi usado javac direto para o único Java gerado (`BuildConfig`), compilador Kotlin em processo e JUnitCore com o classpath exportado do Gradle. R8, empacotamento, recursos e assinatura foram executados pelas tarefas Android. Nenhuma classe de aplicação Java foi ignorada. As configurações globais de Gradle/Android não foram alteradas. Fora desse ambiente, os comandos normais são `gradlew.bat testDebugUnitTest assembleRelease`.

## Saques rápidos: limites e validação ainda necessária

**Não há comprovação de que uma câmera local leia todos os saques a 200 km/h ou arbitre uma partida completa.** A essa velocidade, 55,6 m/s, a bola percorre cerca de 93 cm por quadro a 60 FPS, 46 cm a 120 e 23 cm a 240. FPS alto reduz espaçamento temporal, mas não recupera uma bola sem detalhe, fora da imagem ou borrada pela exposição. O relógio correto e o rastro não criam observações que a câmera não capturou.

As sessões especiais Camera2 de 120/240 FPS têm restrições de superfícies; uma taxa disponível para gravação não implica análise local por ImageReader/YUV na mesma taxa. A escolha da lente ampla também depende das características anunciadas. Referências oficiais: [CameraConstrainedHighSpeedCaptureSession](https://developer.android.com/reference/android/hardware/camera2/CameraConstrainedHighSpeedCaptureSession), [CONTROL_ZOOM_RATIO_RANGE](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#CONTROL_ZOOM_RATIO_RANGE).

Permanecem pendentes:

- Testar 0.6.2 no S25 físico: lentes, zoom amplo, 1080p, exposição, temperatura e treino longo, com taxa de apresentação e atraso medidos.
- Comparar detecção e contatos com vídeos reais anotados independentemente, incluindo bola na mão, saque rápido, fundo verde, jogadores, sombras e oclusões. Não há percentual de precisão real de IN/OUT nesta entrega.
- Validar pequenas translações na quadra e exigir remarcação em giro/perspectiva. Fundo sem textura pode não permitir registro confiável.
- Diferenciar retorno de mão/raquete de quicada em todas as perspectivas. Mudança de trajetória monocular não comprova contato físico. Na parede, continua candidato para revisão.
- Medir velocidade contra referência independente. A estimativa local é lateral pelo diâmetro aparente de 67 mm, não radar/velocidade 3D do saque.
- Avaliar duas câmeras sincronizadas e/ou análise de gravação de alta velocidade se uma câmera não oferecer detalhe e amostragem suficientes. Não foi adicionada fusão de duas câmeras ao Android local.

## Como retestar na quadra

1. Atualize com o APK 0.6.2, sem apagar o app.
2. Fixe o celular na horizontal. Ajuste posição e lente para ver os quatro cantos e margem fora das linhas. Se a bola ficar minúscula, filme meia quadra com mais detalhe.
3. Em **Enquadramento e detalhe**, teste **Bola distante · até 1080p**, com boa luz, e confira FPS recebidos/exposição. Use Fluidez se o aparelho não sustentar o processamento.
4. Use **Marcar quadra · 4 cantos**, escolha inteira/metade, ajuste com a lupa e salve. A reta de quarto não é necessária aqui.
5. Confira primeiro a bola parada e deslocamentos lentos. Só depois aumente a velocidade. Não aceite uma decisão incerta como arbitragem validada.
6. Reposicionou a câmera: confira e ajuste os cantos. Durante o treino, movimento não compensável pausa a contagem para evitar decisões sobre uma área deslocada.
