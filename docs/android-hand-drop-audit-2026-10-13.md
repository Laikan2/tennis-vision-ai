# Android 0.6.3 — bola na mão, soltura, queda e contatos

## Resultado e limites da entrega

APK **0.6.3 / código 16** compilado e assinado, **161 testes JVM/Robolectric aprovados**, quatro testes de empacotamento desktop aprovados, verificação local do publicador aprovada. O treino continua local no Android, sem Windows ou servidor.

**Não houve teste físico da 0.6.3.** ADB não conseguiu iniciar/conectar ao servidor local nesta sessão. Os testes Android de interface usam Robolectric SDK 33 em JVM, não um celular ou emulador Android. A última conferência física disponível foi da [0.6.1 no S25 Ultra / Android 16](android-fast-ball-audit-2026-10-11.md). Os resultados abaixo não comprovam detecção em partidas ou saques de 200 km/h.

**Publicação pendente:** a leitura pelo conector GitHub funcionou, mas a tentativa de enviar o APK recebeu `MCP tool call requires approval, but approval policy is never`. A política da sessão não permite solicitar essa aprovação. Nenhum blob, branch, workflow ou release novo foi criado. O APK final não está disponível pelo atualizador enquanto não for publicado. [Registro do bloqueio](validation/android-hand-drop-2026-10-13/github-publication-blocked.json).

## Falhas investigadas e corrigidas

1. **Reflexos pequenos impediam adquirir uma bola maior parada.** Componentes de 1–2 pixels eram tratados como concorrentes igualmente plausíveis. Agora uma única bola com detalhe suficiente pode ser adquirida sem que esses reflexos a bloqueiem. Duas bolas plausíveis continuam ambíguas; não se escolhe simplesmente o maior objeto verde.
2. **Dedos separavam uma bola em dois candidatos.** Fragmentos próximos, com contorno combinado compacto e evidência de pele no intervalo, podem ser reunidos como uma observação parcialmente oculta. Ela recebe indicação de oclusão, não gera velocidade e não decide impacto automaticamente. Não se juntam quaisquer objetos verdes próximos.
3. **Falso OUT ao carregar a bola em pouca luz.** O limiar de cor usado para evidência de mão falhava no cenário escuro controlado. Foi ampliado e testado em três cores/iluminações de pele sintéticas. Isso não equivale a um modelo semântico de pessoa ou a validação de todos os tons de pele, luvas e cenários reais.
4. **Bola na mão sobre fundo verde desaparecia.** A estimativa de fundo próximo era contaminada pela própria bola ou pela mão. O contraste agora requer suporte do entorno mais amplo e usa a luz das amostras verdes. Inclui regressão sobre piso verde, parede branca e piso de madeira.
5. **A evidência de mão podia persistir depois da soltura sobre madeira.** O teste acrescentado para esse caso mantém a bola em queda sem confundir todo o piso de madeira com uma mão. Contato conhecido continua sendo registrado.
6. **A soltura rápida apagava o histórico antes da primeira quicada.** A dimensão visível cresce ao sair dos dedos. A aquisição aceitava esse aumento, mas outra condição apagava a trajetória usando um limite diferente. Os limites foram alinhados. Quando o primeiro contato ainda inclui pouca evidência ou mão perto da aproximação, ele fica para REVISAR, sem OUT automático nem velocidade inventada.
7. **Reflexo minúsculo parado iniciava um rastro depois que a bola sumia.** Um novo alvo de 2–3 pixels agora precisa de duas posições coerentes em movimento. Continua incerto e sem velocidade/decisão de linha. Isso pode adiar ou impedir a aquisição de uma bola real parada com tão poucos pixels; aproximar/enquadrar melhor continua necessário.
8. **Ajuste linear podia deslocar a marca além do ponto observado.** Quando a trajetória curva não cabe no ajuste e o resíduo é relevante, a marca fica no máximo observado, mantendo o erro do modelo como incerteza. A interpolação entre quadros permanece nos casos com suporte adequado.

O detector continua usando cor, contraste, forma e continuidade. Não foi treinada uma rede neural nova. Oclusão total não permite observar a bola; fragmentos visíveis não comprovam o centro completo do objeto. A velocidade continua lateral estimada pela dimensão aparente de uma bola de 67 mm.

## Testes controlados

O gerador [HandDropEvaluation.kt](../mobile/android/app/src/sharedTest/java/ai/tennisvision/camera/HandDropEvaluation.kt) cria posições e resultados conhecidos em 640 × 360. Foram avaliados **24 cenários**, com bola parada na mão, mão em movimento com trajetória semelhante a quicada, dedos atravessando a bola, piso verde/madeira, soltura, queda, rebote, oclusão total e mão sem bola. As taxas 30/60/120 são timestamps de entrada; não representam captura física do telefone.

- **461/461 posições visíveis localizadas**, zero posições falsas nos 24 desenhos controlados.
- Na fase segurada: **312/312** localizadas. Na fase de queda: **99/99**. Os demais 50 quadros visíveis pertencem ao retorno após os contatos.
- Dez sequências de soltura/rebote: **7 IN e 3 OUT**, um contato em cada uma, sem duplicação. Erro de marca de zero pixel nesses desenhos, não em quadra real.
- Bola carregada em trajetória de quicada: nenhum impacto/OUT, nos três cenários de pele/iluminação. O cenário escuro anteriormente gerava um OUT falso.
- Dedos cruzando a bola: antes 0/20 posições por cenário; depois 20/20 nos três casos. Sem velocidade/impacto automático durante a oclusão.
- Mão segurando a bola sobre verde: 0/20 → 20/20 nesse caso.
- Queda sem retorno observado: a bola continua localizada, sem inferir impacto só porque sumiu. Oclusão total e mão sem bola: nenhuma observação de bola ou contato nos casos conhecidos.
- Teste adicional de primeira quicada rápida após pequena parte visível na mão: um REVISAR, sem velocidade, preservando o contato ao mudar a dimensão visível.

[Falhas anteriores reproduzidas](validation/android-hand-drop-2026-10-13/core-before.log), [CSV final dos 24 casos](validation/android-hand-drop-2026-10-13/hand-drop-after.csv), [log final](validation/android-hand-drop-2026-10-13/unit-tests-final.log). Os 62 testes puramente Kotlin são um subconjunto dos 161, não uma contagem adicional.

A regressão inclui os 19 cenários anteriores de bola rápida, contato entre quadros, OUT, oclusão e passagem no ar. Alvos de 3 pixels passam a ter uma observação inicial a menos, aguardando movimento antes da aquisição; continuam para revisão. Borrão 6:1 mantém 11/12 posições localizadas no cenário conhecido. [Resultados rápidos atuais](validation/android-hand-drop-2026-10-13/fast-ball-after.csv).

## Sequência real anterior: revisão diagnóstica

Foram processadas **87 imagens reais já disponíveis**, cobrindo cerca de 8,1 segundos, com tempos irregulares. As imagens de origem 1920 × 1080 foram redimensionadas para 1280 × 720 antes de passar pelo mesmo `LocalAnalysis` Kotlin. Isso não é captura atual do S25 nem uma fonte completa de alta velocidade.

Foram conferidas visualmente **21 regiões amplas** contendo a bola, depois de inspecionar também as saídas do detector:

- Bola na mão, 13 imagens: **10/13 → 13/13** observações dentro da região revisada.
- Oito imagens seguintes em voo, incluindo queda e retorno: **1/8 → 8/8**.
- O primeiro retorno passou a produzir um candidato para **REVISAR**, preservando a evidência limitada da soltura. Isso não comprova a exatidão de IN/OUT nesse contato real.
- Reflexos parados de poucos pixels no fim do trecho deixaram de sustentar o rastro que antes permanecia num objeto do ambiente.

Essas regiões foram revisadas após ver o resultado, sem um anotador independente. A medida indica presença na região visível da bola; não prova que o contorno coincide com o centro completo quando os dedos ou o borrão escondem parte dela. Não foi estabelecido resultado verdadeiro para todos os contatos/linhas do trecho; não há percentual de precisão real de IN/OUT. Os quadros privados e as montagens ficaram em `work/android-hand-drop-2026-10-13`, fora da documentação compartilhável e do APK. [Resumo sem imagens pessoais](validation/android-hand-drop-2026-10-13/private-review-summary.json).

## Desempenho, interface e preservação

O ensaio final no computador comparou 0.6.2 e 0.6.3 com os mesmos desenhos de piso verde: oito quadros de aquecimento e 32 medidos por caso. A mediana do detector em 720p passou de 8,25 para 7,35 ms parado e de 8,00 para 7,44 ms em movimento. Em 1080p, de 16,44 para 18,51 ms parado e de 18,49 para 18,77 ms em movimento. O P95 atual chegou a 19,21 ms em 720p/movimento e 18,95 ms em 1080p/movimento. [Antes](validation/android-hand-drop-2026-10-13/green-court-before.csv), [depois](validation/android-hand-drop-2026-10-13/green-court-after.csv).

Não houve melhora uniforme em todos os casos. São tempos somente de `LocalAnalysis.process` em JVM/JDK 17 no PC, sem câmera, YUV, tela ou condições térmicas do S25. Não se comprovaram FPS sustentados ou atraso óptico da 0.6.3. O perfil local padrão continua Fluidez/até 720p; 1080p precisa ser escolhido observando os FPS recebidos.

Os testes Robolectric SDK 33 exercitam também seleção de quatro cantos, retrato/paisagem, cancelamento, áreas por lente/resolução/orientação, armazenamento, atualização e regressões existentes. Não substituem testes de câmera e uso físico. Não foram apagadas áreas, sessões, gravações ou pareamentos pessoais, nem houve instalação no telefone nesta rodada. A consulta direta à rota Python do APK confirmou nome/tamanho/checksum e erro 404 na ausência do arquivo; não foi um teste HTTP. [Rota](validation/android-hand-drop-2026-10-13/apk-route-check.json), [empacotamento](validation/android-hand-drop-2026-10-13/desktop-apk-tests.log).

## APK e publicação preparada

[Tennis-Vision-Camera-Android-v0.6.3.apk](../mobile/releases/Tennis-Vision-Camera-Android-v0.6.3.apk), **3.222.560 bytes**, pacote `ai.tennisvision.camera`, código 16, mínimo API 26. Assinatura v2 e alinhamento conferidos. SHA-256: `73b8787b7733ac77bc42a9cc2b535fc474fa9722b3a8ceedcfbc22a45732b2f8`.

Certificado igual ao anterior: `faf843630ad0a52fef6ca4f844cae592e3da1e266b7b823f481c34d3b2049ce9`. A chave de desenvolvimento anterior foi mantida para compatibilidade. Instale sobre a versão existente, sem desinstalar. [Manifesto](validation/android-hand-drop-2026-10-13/apk-manifest.json), [assinatura](validation/android-hand-drop-2026-10-13/apk-signature.txt), [compilação](validation/android-hand-drop-2026-10-13/build-release-final.log).

A CLI e o acesso de rede do executor falharam; o conector GitHub permitiu leitura, mas bloqueou a escrita por aprovação. A tentativa foi interrompida nesse bloqueio. Nenhuma atualização nova foi anunciada ao app, e o Windows latest permaneceu v0.6.9 nas consultas.

Foi preparado [PUBLICAR-ANDROID-0.6.3.bat](../PUBLICAR-ANDROID-0.6.3.bat), que chama [publish-android-release.ps1](../scripts/publish-android-release.ps1) no PC. Ele verifica arquivo/versão/certificado, usa o GitHub CLI configurado, cria rascunho, confere o digest do upload, publica com `latest=false` e verifica o download público. Não incorpora token nem chave de assinatura no APK. **Somente a etapa local de verificação desse publicador foi executada e aprovada; o envio externo não foi executado.** [Verificação local](validation/android-hand-drop-2026-10-13/manual-publisher-verify.log).

As notas preparadas estão em [android-v0.6.3.md](releases/android-v0.6.3.md). A opção `latest=false` evita promover este APK a última versão do Windows, conforme o [manual oficial de criação](https://cli.github.com/manual/gh_release_create) e [edição de releases](https://cli.github.com/manual/gh_release_edit).

Depois de publicar de fato, o celular poderá usar **Atualizações → Buscar atualização → Instalar atualização**. O Android exige confirmação para instalar. Não foi validada uma atualização recebida pela rede nesta rodada.

## Pendências reais

- Testar a 0.6.3 no S25: câmera, iluminação, lente, exposição, aquecimento, fluidez prolongada e instalação sobre os dados existentes.
- Validar bola na mão/queda/contato com gravações de quadra anotadas independentemente, incluindo jogadores, luvas, oclusões, sombras e distâncias variadas.
- Medir velocidade contra referência externa. Saques de 200 km/h e precisão de arbitragem continuam sem comprovação. FPS e deslocamento em pixels não substituem escala e evidência de contato.
- Concluir publicação e conferir o download/instalação pelo próprio atualizador. O script preparado não significa publicação concluída.
