# Tennis Vision mobile 0.6.15 — propriedade da silhueta

## Origem da correção

O [ensaio físico da 0.6.14](mobile-0.6.14.md) confirmou a instalação, o editor e a contagem ativa, mas encontrou falsos candidatos. Três quicadas informadas produziram três REVISAR e nenhuma decisão IN. Os dois primeiros quadros de evidência mostravam a bola ainda na mão com raios registrados de 2,5 e 1 px; não é válido chamar os três registros de três acertos.

Na reprodução independente de uma dessas imagens, o motor escolhia uma região de raio 3,5 px junto de uma bola grande cortada na borda. A silhueta incompleta era descartada, enquanto uma pequena região desconectada de feltro permanecia. Em outra imagem, a bola inteira de raio 95,5 px foi reconhecida com mão; o estado anterior pequeno podia impedir essa recuperação durante o vídeo.

## Implementação

- Os dois motores guardam o contexto limitado de feltro cortado na borda antes de descartar a silhueta. Ilhas pequenas dentro ou na margem ambígua desse contexto deixam de ser alvos independentes. O restante da imagem continua sendo pesquisado.
- Uma única silhueta grande, resolvida e com feltro distinto pode substituir um fragmento de até 6 px que ela contém. A trajetória antiga é limpa antes da troca, preservando a proteção contra contabilizar a troca como impacto.
- O contexto atual de pele/grip também alcança fragmentos pequenos; não é reintroduzida a herança indefinida de mão corrigida na 0.6.14.
- A execução explícita do diagnóstico de câmera no iPhone passa a guardar até 5.000 amostras numéricas e 32 detalhes de candidatos, mais estado térmico, economia de energia e contadores de mão/oclusão/suspensão. Não salva imagens, áudio nem eventos de treino nesse modo.

## Testes e limites

Há quatro regressões equivalentes em Kotlin e Swift: ilhas de feltro dentro/perto de bola cortada, bola pequena separada, fragmento carregado na mão e recuperação imediata da silhueta inteira. Os quatro casos iniciais falharam antes da alteração. Foi acrescentada uma franja desconectada fora do retângulo inicial para cobrir a região observada na imagem real.

A bateria Kotlin intermediária passou em 257 testes, sem ignorados. O teste opcional de imagens privadas foi executado com três imagens desta investigação; elas são dados de desenvolvimento, não avaliação inédita. A imagem cortada deixou de produzir a falsa região pequena depois do ajuste da margem. Outra imagem ainda produz um fragmento de raio 4 px perto de mão/oclusão: esse caso continua sem resolução comprovada e não pode ser anunciado como corrigido.

As verificações finais de pacote, Swift, desempenho e Android emulado serão registradas com seus resultados. A 0.6.15 ainda não foi instalada no iPhone neste relatório. Não há medição de precisão física IN/OUT, velocidade de saque, saques a 200 km/h ou 120/240 FPS sustentados. Não houve treinamento nem troca de pesos de modelo neural.
