# Tennis Vision mobile 0.6.13

No teste físico da 0.6.12, o usuário fez cinco quicadas, mas as quatro marcas geradas tinham evidências da bola ainda na mão. Elas não representam quatro acertos. Esta versão corrige causas reproduzidas desse comportamento nos motores Android e iPhone.

- A vizinhança usada para separar bola e fundo agora acompanha a resolução. Uma bola grande e próxima deixa de ser recortada em pequenos fragmentos porque as amostras estavam dentro do próprio feltro.
- A análise distingue melhor uma região de mão próxima da bola sobre piso de madeira. O contorno informa “BOLA · PRÓXIMA À MÃO”; nesse estado os contatos e a velocidade ficam suspensos.
- Pontos brilhantes sobre uma região maior de feltro amarelado muito escuro não são tratados como uma bola pequena resolvida. Bolas pequenas sem esse contexto continuam nos testes.
- Mantém o processamento otimizado, histórico, pareamento e vozes IN/OUT offline. As áreas seguem temporárias e precisam ser marcadas após reiniciar ou atualizar.

As mudanças usam evidência de cor, forma e trajetória; não anunciam treinamento de um modelo novo, detecção de todos os impactos, precisão de partidas ou saques a 200 km/h. A bola muito escura ou cortada pode ficar sem posição, e uma câmera não comprova contato em profundidade. Confira o relatório de validação para separar testes sintéticos, recortes e medições físicas.

Instale sobre a versão anterior. Android e iPhone usam a mesma versão de recursos; no iPhone o AltStore precisa confirmar e assinar a instalação. O programa Windows mantém sua versão e atualização atuais.
