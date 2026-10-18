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

A medição física de 60 FPS e 4,32–4,53 ms por quadro já registrada pertence à **0.6.11**; não é apresentada como teste de 0.6.12. A nova versão ainda precisa repetir o controle negativo no iPhone instalado.

Evidências: [falha física anterior](mobile-0.6.12/negative-control-before.json), [Android compilado](mobile-0.6.12/android-build.json), [execução Android](mobile-0.6.12/android-runtime.json), [CI iOS](mobile-0.6.12/ios-ci.json), [recortes revisados](mobile-0.6.12/held-crop-check.json), [desempenho](mobile-0.6.12/performance.json), [primeira ordem](mobile-0.6.12/comparison.json) e [ordem inversa](mobile-0.6.12/comparison-repeat.json).

## Limites

A cor isolada não diferencia todo objeto de uma bola. Esta correção pode deixar de emitir contato quando a cor desaparece de um trecho da trajetória; é um comportamento conservador, e não uma prova de que a bola não quicou. Revisão continua necessária em imagens pequenas, borradas, oclusões e perto da linha. Uma câmera não comprova contato em profundidade, velocidade 3D ou arbitragem de uma partida.

Não foram validados saques a 200 km/h, todos os impactos reais, precisão por centímetro ou 120/240 FPS sustentados. Os testes sintéticos, recortes de tela e medições da CI não substituem quicadas físicas com resultado conhecido. As fotos dos contatos são instantes de evidência, não uma sequência contínua anotada para reconstruir o impacto real.
