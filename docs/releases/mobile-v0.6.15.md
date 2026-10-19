# Tennis Vision mobile 0.6.15

Corrige casos reproduzidos em que uma parte de uma bola grande era acompanhada como uma bolinha independente. O ensaio físico da 0.6.14 encontrou contatos falsos e três registros REVISAR durante três quicadas informadas, sem aprovar IN/OUT.

- Fragmentos dentro ou perto de uma silhueta de feltro cortada pela borda deixam de criar um alvo pequeno independente. Uma bola pequena separada permanece detectável no controle.
- A silhueta inteira pode substituir imediatamente o pequeno fragmento anteriormente acompanhado, sem transformar essa troca em quicada.
- A evidência atual de mão também é verificada em fragmentos pequenos. A liberação da bola continua coberta pelos testes das versões anteriores.
- O diagnóstico explícito de câmera do iPhone registra amostras numéricas limitadas, evidências dos candidatos, suspensão e estado térmico. Não grava imagens ou contatos na sessão nesse diagnóstico.
- Android 0.6.15/código 28 e iPhone 0.6.15/build 16 compartilham as correções. Histórico e vozes são mantidos; áreas continuam temporárias.

Os testes controlados e a reprodução de imagens de desenvolvimento não comprovam sensibilidade física, arbitragem de partidas ou captura sustentada em 120/240 FPS. O teste físico da nova versão permanece necessário. A análise de uma câmera é uma estimativa; nem todo retorno da bola comprova contato com a superfície.
