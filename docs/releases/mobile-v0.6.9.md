# Tennis Vision mobile 0.6.9

O contorno da bola agora aparece na prévia local, antes de iniciar o treino. A prévia usa análise de até 640 pixels no lado maior sobre o próprio quadro mostrado, sem registrar impactos, velocidade ou alterar as estatísticas. Iniciar treino usa a resolução de análise selecionada e ativa a contagem. A redução para prévia não transforma uma bola de poucos pixels em observação confiável.

O contorno circular/elíptico acompanha as dimensões observadas da bola, inclusive um borrão alongado, com borda escura para contraste. Verde indica observação resolvida e laranja indica sinal incerto ou parcial; a previsão continua identificada separadamente.

O modo Parede passa a aceitar retornos verticais e diagonais além dos horizontais. A posição candidata usa o encontro temporal dos trechos observados antes/depois da mudança, limitado aos quadros ao redor dela. Retornos sem trajetória coerente e desaparecimentos sem retorno observado não geram essa marca. A posição e a velocidade lateral continuam estimadas; contatos de parede exigem revisão, pois uma câmera não comprova a profundidade nem distingue todos os retornos de mão/raquete.

Mantém Marcar chão/parede, áreas separadas, histórico, pareamentos, certificado Android e vozes ElevenLabs offline. Não houve treinamento ou substituição de um modelo neural nesta entrega. Testes de pixels e vídeos simulados não comprovam precisão em partidas reais.
