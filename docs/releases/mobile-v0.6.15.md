# Tennis Vision mobile 0.6.15

Corrige falhas reproduzidas no acompanhamento, na evidência de quicada e na velocidade estimada. O ensaio físico da 0.6.14 encontrou contatos falsos e impactos perdidos; IN/OUT físico continua sem aprovação.

- Fragmentos dentro ou perto de uma silhueta de feltro cortada pela borda deixam de criar um alvo pequeno independente. Uma bola pequena separada permanece detectável no controle.
- A silhueta inteira pode substituir imediatamente o pequeno fragmento anteriormente acompanhado, sem transformar essa troca em quicada.
- A evidência atual de mão também é verificada em fragmentos pequenos. A liberação da bola continua coberta pelos testes das versões anteriores.
- A velocidade usa um intervalo curto de tempo observado, com pelo menos três amostras e 20 ms de suporte, em vez de quatro quadros fixos. Isso corrige a velocidade sempre indisponível em 240 FPS nos controles. A marca usa o histórico anterior ao contato, sem atravessar uma lacuna maior que 75 ms.
- Uma quicada com pequenas oscilações de localização pode usar evidência consistente de descida e subida em uma janela ampliada. O desvio entra na incerteza da marca. Tremor parado, queda sem retorno, mão, oclusão e limite do piso continuam protegidos pelos testes.
- A proteção de fragmentos pequenos exige que o contexto de mão termine nas laterais; fundo rosado uniforme não basta para bloquear uma bola livre.
- O diagnóstico explícito de câmera do iPhone registra amostras numéricas limitadas, evidências dos candidatos, suspensão e estado térmico. Não grava imagens ou contatos na sessão nesse diagnóstico.
- Android 0.6.15/código 28 e iPhone 0.6.15/build 16 compartilham as correções. Histórico e vozes são mantidos; áreas continuam temporárias.

Os controles de velocidade e quicada com ruído usam trajetórias geradas a 30, 60, 120 e 240 FPS. Esses testes e as imagens de desenvolvimento não comprovam sensibilidade física, arbitragem de partidas ou captura sustentada nesses modos no celular. O teste físico da nova versão foi adiado a pedido do usuário. A velocidade é lateral/projetada estimada, não uma medição 3-D ou de radar; nem todo retorno da bola comprova contato com a superfície.
