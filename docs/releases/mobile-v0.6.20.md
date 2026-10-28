# Tennis Vision mobile 0.6.20

Android e iPhone recebem as mesmas correções de análise local, sem precisar do PC.

- **Revisão de contatos:** uma pequena variação nos intervalos dos quadros não obriga mais toda quicada clara no chão a ficar em REVISAR. A marca mantém sua margem de erro; contatos próximos à linha, ocultos, rápidos demais para os quadros disponíveis ou sem superfície confirmada continuam exigindo revisão.
- **Velocidade:** a estimativa usa a trajetória anterior ao instante calculado da batida. Um borrão breve pode aproveitar a última sequência nítida imediatamente anterior. O app explica quando não há medida confiável e preserva os decimais das velocidades baixas.
- **Processamento:** reduz cálculos repetidos de cor e fundo, mantendo a resolução completa. Imagens com muito verde usam um caminho próprio para evitar o custo extra da avaliação por demanda.

A velocidade continua sendo uma estimativa lateral pela dimensão aparente da bola, não uma medição em 3D de um saque. Retornos em profundidade e candidatos de parede continuam em REVISAR: uma única câmera não comprova sozinha qual superfície causou a mudança de direção.

As medições comparativas e os resultados de validação estão no [relatório técnico](https://github.com/Laikan2/tennis-vision-ai/blob/main/docs/validation/mobile-contact-0.6.20/report.txt). Ganho no teste de processamento não é promessa de FPS ou precisão em uma partida real.

No Android, instale o APK sobre o app existente. No iPhone, use a IPA com a mesma conta de assinatura no iLoader/AltStore, sem apagar o aplicativo anterior. O projeto Xcode é apenas o código-fonte. A atualização do Windows permanece independente.
