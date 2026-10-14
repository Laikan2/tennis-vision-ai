# Tennis Vision Camera Android 0.6.4

Treino local no Android com câmera, bola verde, rastro, contatos estimados, IN/OUT, som e histórico sem Windows ou servidor.

## Melhorias

- Acompanhamento de bolas pequenas com bordas misturadas ao fundo, mantendo a indicação de incerteza quando falta detalhe.
- Centro mais estável em componentes pequenos e recuperação da bola em movimento quando um objeto verde maior estava parado na busca.
- Prioridade corrigida na soltura, para reflexos fracos perto da mão não substituírem a bola visível.
- Separação do fundo verde pouco saturado, evitando juntar a superfície inteira à bola.
- Enquadramento e detalhe → Melhorar bola distante, com acesso ao perfil até 1080p, tamanho aparente em pixels e orientação sobre lentes e meia quadra.
- Preserva áreas por câmera, configuração, pareamentos, sessões e a assinatura anterior.

## Validação e limites

172 testes JUnit/Robolectric e 4 testes de empacotamento desktop passaram. Em 15 sequências sintéticas de bola de 3–6 pixels, a localização correta passou de 188/240 para 227/240 quadros. Os dois casos com objeto verde concorrente ainda mostram esse objeto nos dois primeiros quadros. A revisão diagnóstica de gravação antiga preservou as localizações anteriores na mão e em voo.

Esses resultados não são precisão em partida real. A 0.6.4 não foi instalada/testada em câmera física nesta rodada; saques a 200 km/h e captura local sustentada a 120/240 FPS continuam sem comprovação. Bola minúscula, cor fraca, oclusão e contato incerto exigem revisão, sem velocidade ou OUT automático nessas condições. O custo do detector nos cenários 1080p do benchmark do computador aumentou aproximadamente 1,8–5,0 ms; confira os FPS recebidos no celular.

Instale sobre a versão anterior, sem desinstalar. Depois da publicação, use Atualizações → Buscar atualização → Instalar atualização. O Android exige confirmação da instalação.

APK SHA-256: 29608b170becf396541555a703dc996887412ac3f1ed2b448d6775b55bd909e6
