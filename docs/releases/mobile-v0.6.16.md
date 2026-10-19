# Tennis Vision mobile 0.6.16

Corrige impactos de chão perdidos mesmo com a bola observada em 60 FPS. Os novos controles reproduziram a falha na 0.6.15 antes da alteração: quicada com retorno mais lento e breve perda de imagem perto do contato.

- A janela de evidência acompanha o tempo real da trajetória, com limite de 420 ms. Retornos lentos podem usar uma janela maior que os antigos 200 ms, exigindo piso marcado, bola resolvida, cor consistente, ausência de mão/oclusão/borrão e descida/subida coerentes.
- O histórico mantém até 600 ms/160 observações, para que 120 e 240 FPS não esgotem o suporte temporal em apenas 40 quadros. Isso não cria uma fila de vídeo: a prévia continua usando o quadro atual.
- Uma descida limpa já observada pode sobreviver a até 160 ms sem bola, somente com referência de chão. A previsão visível continua limitada a 60 ms. Não são criadas posições observadas durante a lacuna; contatos com quadros ausentes exigem revisão e recebem maior incerteza proporcional ao intervalo e movimento.
- Janelas maiores confirmam o retorno; a posição da marca usa os quadros próximos ao pico observado para não deslocar o impacto pelas curvas dos trechos mais distantes.
- Mantém velocidade de entrada lateral/projetada quando há observações válidas. Não estima velocidade atravessando perdas grandes nem promete medir a velocidade 3-D de um saque.
- Android 0.6.16/código 29 e iPhone 0.6.16/build 17 compartilham as alterações. Histórico e vozes preservados; áreas continuam temporárias por sessão.

Os controles usam pixels e timestamps gerados com resultado conhecido, incluindo IN, OUT, contato entre quadros, bola na mão, queda sem retorno, retorno acima do piso, ausência de referência, lacuna curta/longa e limite da previsão. São testes de regressão; não comprovam detecção de todas as quicadas reais, saques a 200 km/h ou FPS físicos sustentados.

Esta versão foi preparada sem acessar o celular, a pedido do usuário. O novo teste físico ainda é necessário. Uma câmera única pode confundir um retorno no ar com contato na superfície; decisões incertas continuam sujeitas a revisão. Confirmar um retorno lento pode levar cerca de 200 ms após o contato nos controles, sem atrasar a reprodução do vídeo.
