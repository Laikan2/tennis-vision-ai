# Tennis Vision mobile 0.6.21

Correções compartilhadas entre Android e iPhone para contatos que deixavam de ser marcados após movimento da câmera e para saltos no tamanho da prévia.

- A contagem deixa de ficar permanentemente suspensa quando a câmera já parou. Após estabilizar, retornos com evidência podem gerar círculos de contato e velocidade estimada disponível. Se a área perdeu a referência, esses contatos ficam em REVISAR, sem voz ou decisão automática IN/OUT, até conferir a área.
- Trajetórias anteriores ao movimento da câmera são descartadas. As marcas ficam vinculadas ao enquadramento em que foram geradas, evitando reaparecer em outra posição ao pausar ou quando a interface pula um quadro. O histórico é preservado.
- A prévia mantém tamanho e posição ao mudar o texto de estado, os avisos e as estatísticas. As informações adicionais ficam em uma região com rolagem.
- Velocidades baixas mantêm uma casa decimal. Quando não há uma estimativa válida, a marca informa que a velocidade está indisponível.

Validação automatizada: 308 testes Android aprovados (3 testes opcionais com arquivos privados não executados), 172 testes do núcleo iOS, 11 cenários no emulador Android 15, construção nativa de iPhone e simulador, integração H.264/RTSP e seis estados da interface nativa iOS com a posição e o tamanho da imagem invariáveis.

As cenas de validação são sintéticas. Ainda é necessário conferir quicadas reais nesta versão; estes resultados não certificam precisão em partida, localização exata de contatos ocultos ou saques de 200 km/h. A velocidade continua sendo uma estimativa lateral pela dimensão aparente da bola. Retornos em profundidade e de parede permanecem sujeitos a revisão.

Instale sobre o aplicativo existente: APK no Android ou IPA no iLoader/AltStore usando a mesma conta Apple. Não desinstale a versão anterior. O pacote ZIP contém apenas o projeto Xcode. A atualização do Windows permanece independente.

[Resultados e limites da validação](https://github.com/Laikan2/tennis-vision-ai/blob/main/docs/validation/mobile-contact-0.6.21/report.txt).
