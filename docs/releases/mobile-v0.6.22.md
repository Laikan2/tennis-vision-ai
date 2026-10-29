# Tennis Vision mobile 0.6.22

Melhorias compartilhadas entre Android e iPhone para quicadas com variação de distância, recuperação da bola e encerramento do treino.

- A análise espera uma janela curta de confirmação antes de registrar um retorno no piso apenas pela mudança do tamanho da bola. Isso permite reconhecer a descida e a subida antes de encerrar a trajetória como REVISAR.
- Uma nova compensação de perspectiva trata quicadas com mudança de tamanho aparente. Ela exige observações reais nos dois lados do contato; movimentos apenas de aproximação e afastamento continuam sujeitos a revisão.
- Detalhes estáticos sem contorno suficiente deixam de prender o rastreamento indefinidamente. A recuperação considera mais de um candidato e preserva a detecção de bolas pequenas e da bola na mão.
- Novo botão **Encerrar treino** (quadrado de parar no iPhone): salva o treino, remove os círculos e reinicia as contagens para o próximo treino. Mantém a área atual e os registros no Histórico. **Pausar** mantém as marcas para revisão.
- O Histórico permite abrir contatos antigos, ver a imagem de evidência e revisar IN/OUT. Uma falha de gravação não é apresentada como encerramento bem-sucedido.

Na comparação controlada com a versão 0.6.21, uma quicada gerada com perspectiva passou de REVISAR para um único IN, mantendo a velocidade estimada e aproximando a marca do ponto conhecido de contato. Isso não mede a taxa de acerto dos vídeos reais.

Validação: 318 testes Android aprovados (3 casos opcionais não executados), 180 testes do núcleo iOS, 11 cenários em emulador Android 15, compilação nativa, transmissão H.264/RTSP e interface iOS no simulador. O encerramento foi conferido nas duas plataformas.

Retornos na parede, contatos vistos somente em profundidade, bola sem detalhe suficiente, dúvidas perto da linha e área deslocada ainda podem exigir revisão. A velocidade é lateral e estimada. A versão ainda precisa de teste físico com quicadas conhecidas no seu aparelho.

Instale sobre a versão existente: APK no Android; IPA pelo iLoader ou AltStore no iPhone, usando a mesma conta Apple. Não desinstale para atualizar. O ZIP contém somente o projeto Xcode. A atualização do Windows permanece independente.

[Relatório de testes e limites](https://github.com/Laikan2/tennis-vision-ai/blob/main/docs/validation/mobile-contact-0.6.22/report.txt).

