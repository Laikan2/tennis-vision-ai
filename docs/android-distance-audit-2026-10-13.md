# Android 0.6.4 — bola pequena e distante

Data: 13/10/2026. Base de comparação: Android 0.6.3. Prioridade: bola de tênis verde.

## Entrega e publicação

**Atualização de 14/10/2026:** [android-v0.6.4 foi publicado](https://github.com/Laikan2/tennis-vision-releases/releases/tag/android-v0.6.4) pelo GitHub CLI autenticado, após nova autorização. O download público foi conferido: 3.224.892 bytes e SHA-256 `29608b170becf396541555a703dc996887412ac3f1ed2b448d6775b55bd909e6`. O latest do Windows permaneceu em 0.6.9. A prova está em `mobile/releases/verification/android-v0.6.4/published.json`. Os itens abaixo descrevem o estado da tentativa original de 13/10.

- APK local: [Tennis Vision Camera 0.6.4](../mobile/releases/Tennis-Vision-Camera-Android-v0.6.4.apk), pacote `ai.tennisvision.camera`, versionCode 17, 3.224.892 bytes.
- SHA-256: `29608b170becf396541555a703dc996887412ac3f1ed2b448d6775b55bd909e6`.
- Assinatura anterior preservada: certificado SHA-256 `faf843630ad0a52fef6ca4f844cae592e3da1e266b7b823f481c34d3b2049ce9`. Instalar sobre a versão existente; não desinstalar para atualizar.
- **Não publicado no GitHub.** A nova tentativa de enviar o APK pelo conector retornou `MCP tool call requires approval, but approval policy is never`. Nenhum blob, branch, workflow ou release foi criado. A consulta ao GitHub ainda mostrava Android 0.6.1 e Windows 0.6.9. O aplicativo não encontrará a 0.6.4 antes da publicação.
- [PUBLICAR-ANDROID-0.6.4.bat](../PUBLICAR-ANDROID-0.6.4.bat) está preparado para execução no Windows fora desta sessão. Confere pacote, versão, certificado, tamanho e hash; publica via GitHub CLI sem alterar o `latest` do Windows e confere o download público. A etapa local `-VerifyOnly` passou em PowerShell 7 e Windows PowerShell 5.1; a publicação remota desse script não foi executada.

## Falhas encontradas e correções

1. **Perda da cor em poucos pixels.** Mistura com o fundo, posições entre pixels e redução da informação de cor alteravam o contorno. A expansão agora aproveita pixels de cor mais fraca próximos ao sinal original. Exige ao menos um pixel da cor original; um tom verde fraco sozinho não basta. Componentes sem detalhe suficiente permanecem incertos, sem velocidade ou decisão IN/OUT automática.
2. **Centro pulando para a borda.** Componentes pequenos usam o peso do sinal de cor para localizar o centro, reduzindo saltos causados por um halo fraco. Componentes maiores e oclusões mantêm o tratamento anterior.
3. **Objeto parado bloqueando bola menor.** A aquisição podia permanecer indefinidamente num objeto verde maior. Duas posições coerentes em movimento, junto de evidência de que o alvo anterior continua parado, permitem recuperar o alvo pequeno. Isso ainda pode mostrar o objeto errado nos dois primeiros quadros do cenário testado; o problema não foi declarado eliminado.
4. **Regressão detectada durante a correção.** A primeira expansão de cor seguiu reflexos ao soltar a bola numa gravação antiga. O teste sintético sozinho não revelou isso. A expansão foi restringida e a soltura passou a dar prioridade a um contorno resolvido sobre pequenos reflexos fracos perto da mão. A gravação foi reprocessada e o acompanhamento anterior foi preservado.
5. **Fundo verde pouco saturado.** Uma checagem adicional revelou que a expansão podia conectar uma quadra de cor fraca à bola e apagar o componente inteiro. A estimativa do fundo fraco agora tem um limite separado, mantendo a evidência original de cor forte. O novo teste reproduziu 0/16 localizações no candidato anterior; após a correção, os casos de 4 e 6 pixels tiveram 15/16 e 16/16, sem posições no lugar errado.
6. **Enquadramento pouco explicado.** O app ganhou **Enquadramento e detalhe → Melhorar bola distante → Usar 1080p**. As lentes são descritas pelo campo estimado a partir dos dados da câmera, como Detalhe, Equilibrada ou Ampla, sem inventar zoom óptico. O diagnóstico mostra o tamanho aparente da bola em pixels. Cor fraca aparece como **SINAL INCERTO** em amarelo.

As áreas continuam independentes da busca: o detector procura na imagem inteira para poder localizar também bolas fora. Lente, orientação e resolução mantêm suas áreas próprias. Configurações, histórico, pareamentos, gravações e APKs anteriores foram preservados. O detector local usa cor, forma, movimento e continuidade; não houve treinamento ou substituição de modelo neural nesta etapa.

## Testes executados

### Núcleo e interface

- **172 testes JUnit/Robolectric, 28 classes: zero falhas na execução final.** Incluem permissões, ciclo de vida, armazenamento, atualização, geometria, editor de quatro cantos, IN/OUT, mão, oclusão e detecção.
- O subconjunto do núcleo, executado separadamente, passou em **72 testes**.
- A interface foi executada em **Robolectric com recursos Android SDK 33**, no computador. Isso não é um celular nem um emulador Android. SDK 36/37 não foram executados pelo runner Java 17.
- O novo fluxo de bola distante foi aberto por toque, o perfil 1080p foi salvo e a área anterior foi conferida sem alteração. A primeira execução desse teste falhou porque a asserção ocorreu antes do callback do botão na fila principal. O teste passou a aguardar o callback e a suíte completa foi repetida.
- Build Android release com redução de código/recursos concluído; assinatura, alinhamento, pacote, versão e hash do APK conferidos. O APK release não foi executado em aparelho físico nesta rodada.
- **4 testes do empacotamento desktop passaram.** As funções Python de oferta/download Android retornaram a 0.6.4 correta, bytes/hash conferidos e 404 para arquivo ausente. Foram chamadas diretas às funções; não foi um teste HTTP.

### Bola pequena: comparação com posições conhecidas

`DistanceBallEvaluation` define 16 sequências de 16 quadros, com posições conhecidas, amostragem de área, perda de cor semelhante a 4:2:0 e ruído limitado. Inclui fundos branco, escuro e verde, borrão, posições entre pixels, objeto estático concorrente e uma quicada conhecida. Os mesmos quadros foram processados pelo código 0.6.3 e pelo novo código.

- Nos **15 cenários de 3–6 pixels de diâmetro**, localização na região conhecida: **188/240 → 227/240 quadros**. Posições no objeto errado: **32 → 4**, concentradas nos dois primeiros quadros de cada cenário com objeto concorrente.
- Bola de 6 pixels em fundo escuro com perda de cor: **7/16 → 16/16** localizações.
- Bola de 3 pixels em fundo escuro: **13/16 → 15/16**.
- Bola de 3 e 4 pixels ao lado de objeto verde maior parado: cada sequência passou de **0/16 → 14/16**. Ainda há duas observações iniciais no objeto parado em cada uma.
- Erro máximo entre os centros localizados e conhecidos nos cenários de 3–6 pixels: **0,271 pixel** no código novo. Esse número vale somente para esses desenhos controlados e não mede erro de linha em quadra real.
- A quicada pequena conhecida gerou um candidato **REVIEW**, sem velocidade ou IN/OUT automático, em ambas as versões. Os cenários de voo não geraram impactos.
- O caso intencionalmente abaixo do detalhe, com diâmetro de **1,5 pixel**, permaneceu instável: **5/16 → 8/16** localizações. Todas permanecem sem detalhe para certificar velocidade/impacto. Não é prova de identificação confiável de uma bola quase sem pixels.
- Negativos adicionais: reflexo fraco parado, ruído cinza, brilho branco em movimento e dois alvos pequenos igualmente plausíveis não iniciaram observações/contatos nos testes correspondentes.

Essas sequências representam dificuldades de imagem, não metros de distância ou saques medidos. O caso com timestamps de 120 FPS não comprova captura física nessa taxa.

### Mão, queda e gravação anterior

- Os 24 cenários controlados anteriores de mão, dedos, soltura, queda e negativos continuaram passando, incluindo os 10 contatos conhecidos. Carregar a bola não produziu OUT nesses cenários.
- Uma gravação anterior disponível em 87 imagens foi reprocessada. Nas 21 regiões visualmente revisadas anteriormente para diagnóstico, foram mantidas **13/13 localizações na mão e 8/8 em voo**, iguais à 0.6.3. Os cinco candidatos de contato continuaram em REVIEW.
- Essa revisão foi feita depois de conhecer a saída do algoritmo e cobre somente um trecho doméstico. Não é avaliação independente de partidas nem comprova todos os contatos. As imagens pessoais ficaram na pasta local de trabalho e não foram incluídas no APK nem na preparação de publicação.

### Custo de análise

Medição isolada no computador, JVM, oito quadros de aquecimento e 32 medidos por cenário. Câmera, conversão YUV, renderização, exposição e transporte não estão incluídos.

- Bola em movimento sobre quadra verde, 720p: mediana **7,24 → 7,73 ms**, P95 **19,87 → 8,15 ms**.
- Mesmo cenário, 1080p: mediana **19,16 → 20,99 ms**, P95 **19,46 → 21,07 ms**.
- Bola parada, 720p: mediana **7,35 → 7,24 ms**, P95 **20,75 → 18,18 ms**.
- Bola parada, 1080p: mediana **16,02 → 21,00 ms**, P95 **16,18 → 21,24 ms**.
- Os quatro cenários medidos mantiveram 32/32 observações corretas e nenhuma posição errada nos quadros medidos. Houve aumento de aproximadamente 1,8 ms no cenário em movimento e 5,0 ms no estacionário em 1080p. A amostra é pequena, com aquecimento limitado, e não estabelece significância estatística. Não há comprovação de menor atraso total no S25; conferir os FPS e os tempos medidos no próprio aparelho continua necessário.

## O que ainda depende de câmera física

O ADB desta sessão não conseguiu iniciar/comunicar com o servidor local, portanto não houve instalação nem captura física da 0.6.4. A última conferência física documentada continua sendo a 0.6.1 no S25 Ultra/Android 16. Permanecem pendentes: bola real distante numa quadra, saque a 200 km/h, iluminação externa, exposição curta, perda por oclusão, precisão junto às linhas e estabilidade sustentada no perfil de detalhe.

Uma bola fora da imagem ou sem detalhe suficiente não pode ser recuperada de forma confiável apenas ampliando o contorno. Prefira 1080p, boa luz e meia quadra quando a bola fica minúscula. Uma lente de campo menor pode aumentar o tamanho aparente, mas precisa preservar chão e margem para OUT no enquadramento. Posições observadas incertas e previsões continuam diferenciadas; decisões sem evidência suficiente exigem revisão.

## Evidências

Resultados e logs estão em [validation/android-distance-2026-10-13](validation/android-distance-2026-10-13/): comparação antes/depois, suítes, build, assinatura, manifesto, alinhamento, resumo diagnóstico, benchmark e bloqueio da publicação. Os fixtures e testes ficam em `mobile/android/app/src/sharedTest` e `src/test`.
