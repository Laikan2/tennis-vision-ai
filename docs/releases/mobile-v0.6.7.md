# Tennis Vision mobile 0.6.7

Corrige a perda da bola de tênis sob luz quente, o limite que descartava uma bola próxima da lente e a ambiguidade causada por pequenos fragmentos de cor ao lado de uma bola bem visível.

Obstruções da lente suspendem impactos e aguardam a imagem de referência retornar, sem encerrar o treino. Se o enquadramento mudou, a análise pede conferência da área e mantém os impactos suspensos. No iPhone, interrupções breves de quadros e a volta de uma notificação não reiniciam desnecessariamente a câmera.

O treino explica a superfície escolhida e quando falta o limite do piso. Eventos antigos continuam no histórico, mas não são projetados sobre uma câmera reaberta. Somente as três marcas mais recentes recebem rótulos, mantendo os demais pontos visíveis. Fragmentos minúsculos precisam de trajetória coerente para gerar um candidato de impacto.

Mantém vozes ElevenLabs offline, pareamentos, áreas e sessões. As correções de detecção e recuperação são equivalentes em Android e iPhone. Modos de captura e instalação continuam próprios de cada plataforma.

Validação: testes de regressão com posições conhecidas, comparação local de dois recortes do vídeo reportado e compilação nativa. Recortes de tela comprimidos não equivalem aos quadros originais da câmera. Testes sintéticos não comprovam precisão em partidas. Parede, oclusões, detalhe insuficiente e contato próximo da linha podem exigir revisão; IN/OUT automático não é prometido nessas condições.
