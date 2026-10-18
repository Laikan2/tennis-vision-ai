# Tennis Vision mobile 0.6.13 — bola próxima e proximidade da mão

## Falha que motivou a mudança

O ensaio físico da [0.6.12](mobile-0.6.12.md) teve cinco quicadas informadas pelo usuário e quatro candidatos REVISAR. Nas quatro imagens de evidência, a bola estava ainda na mão. Não se considera isso quatro quicadas detectadas. Os controles sem bola dessa versão não haviam criado contatos, mas não cobriam esse movimento.

A análise do fundo terminava a 64 pixels. Uma bola próxima, com diâmetro acima de 128 pixels, podia ocupar também essas amostras: o próprio feltro era tratado como fundo, restando pequenos fragmentos. Sobre madeira, a cor ampla usada para procurar pele também se confundia com o piso e não bastava para indicar a mão.

## Alterações nos dois motores

- A amostragem externa agora acompanha um terço da menor dimensão da imagem, com mínimo de 64 pixels. Mantém as amostras próximas e o teste de contraste, inclusive em quadra verde.
- Uma região localizada de cor compatível com pele vermelha/rosada, perto da silhueta, acrescenta evidência de proximidade da mão. Ela não é tomada como identificação universal de uma pessoa. O contorno informa BOLA · PRÓXIMA À MÃO; contatos e velocidade ficam suspensos nesse estado.
- Pequenos brilhos cercados por uma região maior de feltro amarelado parcialmente subexposto não são usados como uma bola minúscula. Uma bola pequena isolada continua nas regressões. A rejeição pode retirar posições em situações de pouca luz; não reconstrói uma bola que a câmera não mostrou.
- Android 0.6.13/código 26 e iPhone 0.6.13/build 14 compartilham a política. Mantêm os buffers otimizados, vozes offline, histórico e pareamento. Áreas seguem temporárias por sessão, com ajuste necessário após atualizar ou reiniciar.

São correções do detector existente; não houve treinamento nem troca de pesos de um modelo neural.

## Validação concluída

- **251 testes JVM/Robolectric** passaram, sem falhas, erros ou ignorados. APK Release compilado; manifesto, certificado anterior e hashes dos áudios conferidos. Lint: zero erros/fatais, 150 avisos restantes.
- Dois novos cenários de bola próxima e movimento carregado falharam antes da correção. A etapa intermediária recuperou o corpo da bola, mas ainda chamou IN num movimento sintético carregado; o teste passou após a evidência adicional de mão. Os quatro cenários novos também cobrem brilho em feltro escuro e uma bola pequena isolada, para não passar simplesmente perdendo o alvo.
- Nas quatro evidências privadas usadas para desenvolver a correção, a reprodução independente pelo detector Android passou a recuperar uma região grande de feltro e proximidade da mão em três imagens; a quarta, cortada e subexposta, ficou sem observação. São regressões de desenvolvimento, não validação independente nem sequência contínua de quicadas. O centro ainda pode se deslocar quando dedos escondem parte da bola.
- Nos recortes antigos com tolerâncias manuais fixadas entre 12 e 16 pixels, continuam **7/7 posições dentro da tolerância e 0/4 observações nos negativos**. O erro médio foi de **6,81 para 7,26 pixels do recorte**: não se apresenta como melhora da precisão do centro. As imagens são recortes comprimidos da tela e já foram usadas durante desenvolvimento; não constituem uma avaliação inédita de partida.
- **21 testes Python** passaram para fonte AltStore, publicação conjunta e áudios decodificados.
- **118 testes Swift** passaram na [CI nativa](https://github.com/Laikan2/tennis-vision-ai/actions/runs/39154939836), com compilação para iPhone sem assinatura, compilação para simulador e 60 quadros H.264 recebidos e decodificados. IPA, contrato de recursos e vozes conferidos. A etapa opcional de abrir a interface no simulador excedeu dois minutos e não produziu screenshot: execução da interface nesse simulador permanece sem comprovação.
- A [comparação de desempenho](https://github.com/Laikan2/tennis-vision-ai/actions/runs/39154939849) executou 12 cenas/resoluções de referência em duas ordens, no mesmo computador Apple e em Release. As saídas registradas permaneceram idênticas nesses cenários. Contra a base 0.6.10, o ganho ficou entre 4,22 e 7,33 vezes numa ordem e entre 4,76 e 9,39 na inversa. Isso não compara a 0.6.13 com a 0.6.12 no celular, nem mede FPS de câmera. Os casos próximos que mudaram de comportamento estão nas regressões separadas.
- Dois testes de buffers com AddressSanitizer passaram, sem erro de memória detectado nesses cenários.

O primeiro ensaio Android instrumentado foi impedido por falhas de injeção de toque e uma janela “Process system isn’t responding” no emulador; os testes de permissão também falharam nesse estado. A [segunda execução da mesma revisão](https://github.com/Laikan2/tennis-vision-ai/actions/runs/39154939838), em uma instância nova e sem alterar ou desativar os testes, passou nos **10 testes Android 15/API 35 emulado**. Incluiu áreas por superfície e cancelamento, histórico e nova sessão, toques rápidos, segundo plano, recriação da tela, bloqueio e economia de bateria, permissões concedida/negada e operação sem rede padrão ativa. Não foi usado Android físico nesta etapa.

O Android MediaCodec decodificou 191 quadros sintéticos e o fluxo gerou IN, OUT e REVISAR com persistência e um OUT aceito pelo SoundPool. A imagem do ensaio foi inspecionada, com etiquetas e velocidades estimadas legíveis e ligadas aos círculos por linhas. O som do emulador estava desativado: isso não comprova áudio audível em um celular. A mediana/p95 da análise nesse trecho emulado foi 7,31/20,26 ms; não são tempos de S25 ou câmera física.

## Verificação física pendente

A [0.6.13 foi publicada para Android e iPhone](https://github.com/Laikan2/tennis-vision-releases/releases/tag/mobile-v0.6.13). A leitura da fonte pública confirmou 0.6.13/build 14, e os downloads tiveram a integridade conferida. O marcador latest do Windows permaneceu em v0.6.11. Veja a [verificação da publicação](mobile-0.6.13/publication.json).

A 0.6.13 ainda precisa ser instalada e medida no iPhone: na tentativa posterior à publicação, o aparelho deixou de ser enumerado pela conexão USB. O usuário foi solicitado a reconectar e abrir o AltStore. Os 60 FPS e 4,20–5,96 ms da medição anterior pertencem à **0.6.12**, em outro ensaio. A taxa solicitada não garante essa taxa de análise, e processamento→interface não é atraso total câmera→olho.

Não estão comprovados: contagem de todas as quicadas reais, precisão física de IN/OUT, posição exata dos impactos, velocidade 3D de saques, 200 km/h ou 120/240 FPS sustentados. Fotografias isoladas de evidência não permitem reconstruir uma quicada no tempo. É necessário repetir quedas com contagem conhecida e vídeo contínuo anotado para medir acertos, perdas e falsos contatos.

Evidências: [Android compilado](mobile-0.6.13/android-build.json), [execução Android](mobile-0.6.13/android-runtime.json), [iOS](mobile-0.6.13/ios-ci.json), [regressões próximas](mobile-0.6.13/close-held-regression.json), [recortes antigos](mobile-0.6.13/older-crop-regression.json), [desempenho](mobile-0.6.13/performance.json), [primeira ordem](mobile-0.6.13/comparison.json) e [ordem inversa](mobile-0.6.13/comparison-repeat.json). Fotos, arquivos de sessões e identificadores pessoais não são publicados.
