# Tennis Vision mobile 0.6.12 — falsos contatos no piso

## Falha física confirmada na 0.6.11

O usuário conseguiu salvar a área do chão no iPhone 15 Pro com iOS 27.2. A captura inspecionada mostrou o polígono e a linha do limite do piso aplicados. Em seguida, apareceram sete registros REVISAR. O usuário confirmou **nenhuma quicada real** naquele teste: são sete falsos candidatos de contato, não sete acertos ou decisões IN/OUT confiáveis. Ao pausar o treino, três candidatos adicionais haviam aparecido; não entram na contagem dos sete confirmados por aquela resposta.

As imagens de evidência inspecionadas mostram piso de madeira, sombras e parte do pé, sem bola nos contatos examinados. A máscara ampla de amarelo/verde também admite componentes amarelados de baixa saturação. A confirmação de movimento por dois quadros conseguia adquirir esses fragmentos; depois, alterações pequenas de forma e posição podiam parecer uma descida e retorno. Geometria de trajetória e a etiqueta REVISAR não substituem evidência de que o objeto era uma bola.

O treino foi pausado e o arquivo permaneceu igual em uma leitura posterior. A sessão original com 30 eventos foi preservada; os registros do teste não foram apagados nem convertidos artificialmente em acertos. Fotos pessoais, conteúdo do histórico e identificadores do aparelho não são publicados.

## Correção no Android e iPhone

A aquisição agora precisa de dominância de verde ou saturação compatível com o feltro amarelo já reconhecido pelo detector. Um fragmento amarelado fraco não inicia um rastreamento somente porque se moveu. Uma observação de cor fraca ainda pode acompanhar uma trajetória que já tinha evidência de cor, por até 100 ms e perto da posição prevista. Ela fica marcada como incerta e não fornece velocidade válida. A previsão continua limitada; nenhum ponto previsto é usado como contato observado.

Para gerar um candidato de chão ou parede, inclusive REVISAR, a trajetória observada precisa ter evidência de cor **antes e depois** da mudança de direção. Um único ponto colorido no instante do retorno não valida os dois trechos. Os critérios de mão/oclusão, superfície, espaçamento dos quadros, incerteza da linha e revisão de parede permanecem ativos.

A política e seis regressões correspondentes foram implementadas nos dois motores. O processamento integral dos pixels e a otimização de buffers da 0.6.11 são mantidos. A nova versão é 0.6.12, código 25 no Android e build 13 no iPhone. Histórico, pareamento e áudios são preservados. Áreas continuam temporárias; atualizar ou reiniciar o app exige marcar a superfície novamente.

## Resultados verificados

- **247 testes Android JVM/Robolectric** aprovados, sem falhas, erros ou ignorados. APK Release compilado e conferido com o certificado anterior, manifesto 0.6.12/código 25 e os hashes dos áudios. Lint: zero erros/fatais, 150 avisos restantes.
- Dos quatro novos cenários inicialmente executados na implementação anterior, dois falharam por adquirir os fragmentos amarelados como bola. A correção passou nesses casos e nas duas regressões adicionais de expiração da cor fraca e ausência de evidência depois do retorno. Bolas verdes desaturadas, feltro sob luz quente, bola pequena, oclusão e quicadas sintéticas em diferentes taxas continuam nas suítes existentes.
- **21 testes Python** aprovados para fonte AltStore, publicação conjunta e áudios decodificados.
- **10 testes instrumentados** aprovados em [Android 15/API 35 emulado](https://github.com/Laikan2/tennis-vision-ai/actions/runs/39150687777), sem rede padrão ativa. Incluem permissões, seleção de áreas, cancelamento, sessões, histórico, toques rápidos, segundo plano, recriação, bloqueio da tela e economia de bateria. Não foi usado Android físico nesta etapa.
- Android MediaCodec decodificou 191 quadros sintéticos e o fluxo produziu IN, OUT e REVISAR, persistência dos eventos e um OUT aceito pelo SoundPool. A captura foi inspecionada: etiquetas e velocidades estimadas estavam ligadas aos círculos de contato. O áudio do emulador está desativado; aceitação pelo SoundPool não comprova som audível.
- Os **sete recortes privados de bola na mão**, com tolerâncias manuais fixadas anteriormente entre 12 e 16 pixels do recorte, mantiveram sete localizações dentro da tolerância e erro médio de 6,81 pixels. Os quatro recortes negativos não geraram observações. São imagens comprimidas de uma gravação antiga da tela, avaliadas independentemente, não vídeo do sensor ou verdade de referência de impactos. Um negativo já contém uma anotação antiga; três positivos cobertos por anotações foram excluídos antes desta revisão.
- A [comparação Release na CI Apple](https://github.com/Laikan2/tennis-vision-ai/actions/runs/39150687796) preservou as assinaturas registradas de 144 quadros em 12 cenas/resoluções, nas duas ordens de execução. O ganho sobre a base 0.6.10 na ordem inversa ficou entre 3,54 e 7,79 vezes. São tempos do mesmo computador de CI, não FPS de celular. Os negativos amarelados têm comportamento intencionalmente diferente e são avaliados pelas novas regressões.
- Dois testes com AddressSanitizer passaram sem erro de memória detectado nos cenários executados.
- **114 testes Swift** passaram na [CI Apple](https://github.com/Laikan2/tennis-vision-ai/actions/runs/39150687780). Compilações para iPhone físico sem assinatura e para simulador concluíram; 60 quadros H.264 recebidos foram decodificados. O IPA 0.6.12/build 13 foi conferido, incluindo o contrato conjunto e hashes das vozes. A tentativa opcional de abrir o app no simulador excedeu dois minutos e não produziu captura: não é considerada execução bem-sucedida da interface iOS.

A medição física de 60 FPS e 4,32–4,53 ms por quadro registrada anteriormente pertence à **0.6.11**. A medição da versão nova está separada abaixo; não é uma comparação da mesma cena entre versões.

## Instalação e checagem no iPhone 0.6.12

O botão UPDATE apareceu no AltStore e foi acionado. A leitura posterior do manifesto instalado confirmou **0.6.12/build 13** no iPhone 15 Pro com iOS 27.2. As duas sessões e os 40 eventos ficaram byte a byte iguais depois da instalação, dos testes da câmera e do diagnóstico sintético. Os registros falsos antigos não foram apagados ou reclassificados.

O usuário preparou a câmera voltada para o piso, sem bola. Duas capturas privadas foram inspecionadas, sem publicação das imagens. Em **duas execuções independentes de aproximadamente 20 segundos**, na orientação vertical a 720 × 1280 e 60 FPS solicitados, foram medidos:

- 60,01 e 60,00 FPS recebidos; 1.200 e 1.201 quadros entregues à interface.
- Análise média de **4,20 e 5,96 ms por quadro**, com máximos de 7,54 e 10,81 ms.
- **Zero candidatos de contato** nas duas execuções. Houve duas observações breves de bola sem bola real na primeira execução e nenhuma na segunda; elas não produziram contato. Não se afirma que os falsos positivos foram eliminados em geral.
- Nenhuma escrita de evento, imagem ou som desses diagnósticos no treino.

As preferências desse enquadramento estavam em modo parede e sem área após reiniciar o aplicativo. Portanto, esse controle negativo verifica o rastreamento e a emissão de candidatos nessa configuração; **não comprova decisões reais IN/OUT no chão**. A medida processamento→interface é de um instante, não atraso total câmera→olho, e não inclui exposição, leitura do sensor ou apresentação no painel físico. O primeiro quadro da sonda ativa a análise completa.

O diagnóstico sintético também foi repetido no aparelho atualizado: 18 casos, 288 quadros, **276 localizações dentro da tolerância, duas erradas e dez ausentes**. Os critérios declarados passaram, incluindo saídas conhecidas IN, OUT e REVISAR; passar não significa acertar todos os quadros ou quicadas de uma partida. Ao terminar, o app voltou ao modo normal.

No ajuste posterior, Salvar ficou inicialmente desabilitado porque os dois pontos do limite eram verticais. Com extremos mais separados, o botão habilitou, foi acionado e a interface confirmou **CHÃO · QUICADAS** com a área aplicada. A linha salva ainda atravessava o piso em diagonal: salvar uma geometria válida não comprova que ela representa a superfície correta. Em tela cheia, o usuário refez os extremos no alto da imagem, na mesma altura, e salvou; a captura posterior confirmou a linha horizontal no alto e a área aplicada. A prévia ficou temporariamente escura; depois da confirmação de prontidão pelo usuário, outra captura voltou a mostrar o piso.

Foi então feito **outro controle negativo no modo normal, com área e limite do chão salvos e contagem de treino ativa**. Entre as leituras passaram 22,36 segundos, incluindo pelo menos 20 segundos de espera; os 40 eventos e o arquivo permaneceram iguais: **zero eventos novos**. A interface foi lida no início e no fim como CHÃO · QUICADAS, com botão Pausar treino e bola não localizada. Ao terminar, o treino foi pausado. Esse intervalo não é somado às duas sondas como se fosse uma única medição contínua ou comparação controlada com a versão anterior. A validação de quicadas físicas com contagem e posição conhecidas continua pendente.

Evidências adicionais: [iPhone instalado e testes físicos](mobile-0.6.12/iphone-physical.json) e [publicação e downloads conferidos](mobile-0.6.12/publication.json). Os dois ensaios ao vivo foram calculados no iPhone; o Windows apenas controlou a execução e leu os relatórios pelo USB.

Evidências: [falha física anterior](mobile-0.6.12/negative-control-before.json), [Android compilado](mobile-0.6.12/android-build.json), [execução Android](mobile-0.6.12/android-runtime.json), [CI iOS](mobile-0.6.12/ios-ci.json), [recortes revisados](mobile-0.6.12/held-crop-check.json), [desempenho](mobile-0.6.12/performance.json), [primeira ordem](mobile-0.6.12/comparison.json) e [ordem inversa](mobile-0.6.12/comparison-repeat.json).

## Limites

A cor isolada não diferencia todo objeto de uma bola. Esta correção pode deixar de emitir contato quando a cor desaparece de um trecho da trajetória; é um comportamento conservador, e não uma prova de que a bola não quicou. Revisão continua necessária em imagens pequenas, borradas, oclusões e perto da linha. Uma câmera não comprova contato em profundidade, velocidade 3D ou arbitragem de uma partida.

Não foram validados saques a 200 km/h, todos os impactos reais, precisão por centímetro ou 120/240 FPS sustentados. Os testes sintéticos, recortes de tela e medições da CI não substituem quicadas físicas com resultado conhecido. As fotos dos contatos são instantes de evidência, não uma sequência contínua anotada para reconstruir o impacto real.
