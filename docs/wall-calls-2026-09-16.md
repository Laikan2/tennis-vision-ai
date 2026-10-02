# Correções de impactos e chamadas — 16/09/2026

A lógica antiga exigia `insideTarget` para lembrar uma aproximação à parede. Isso descartava uma chegada fora do alvo. A entrada no retângulo também podia gerar um impacto antes do contato físico. Os testes de regressão reproduziram esses casos, a perda de uma curva oblíqua, um contato entre quadros e uma falsa colisão causada por mudança de identidade.

O detector de contato agora opera em toda a imagem, distingue aproximação/perda de uma mudança medida de direção e aceita curvas oblíquas com movimento coerente antes/depois. Cruzar a área não gera evento. As primeiras observações usadas para confirmar a bola alimentam a janela de contato, sem usar posições previstas como medidas. Uma troca de objeto inicia outra trajetória.

A classificação IN/OUT mede a distância à zona projetada em pixels, considera o raio da bola e devolve REVISAR quando a incerteza cruza a borda. A marca recebe a posição medida no contato ou uma interseção temporal compatível entre ida e volta; esta última tem incerteza maior. O desaparecimento ainda produz somente um candidato aproximado, não prova física do impacto.

A velocidade usa a espessura do contorno rotacionado para reduzir erro de escala em borrões diagonais e calcula ida/volta separadamente. Movimento de profundidade e lateral são combinados quando há dados confiáveis; permanece estimativa monocular. O seletor 40/67 mm pode ser alterado por câmera durante a sessão.

## Replay já anotado

No replay `0533c57e-d0f3-4f99-92d1-0953f3d8abf4`, com 132 quadros e 24 anotações disponíveis: antes, 21 posições corretas e 2 incorretas; depois, 22 corretas e 2 incorretas. Propostas próximas da bola: 23/24 em ambas as versões. O quadro restante sem proposta tem oclusão pela mão. Este pequeno conjunto já foi usado no desenvolvimento; não é teste independente de precisão em quadra.

No replay recente `912d44ab-8b2d-4a45-856e-4b8ba62b9f82`, foram inspecionados três objetos verdes fixos da decoração: antes, 1 quadro rastreado nesses objetos; depois, nenhum. Isso cobre esses objetos, não todas as possíveis detecções falsas. A avaliação offline reinicializa o fundo e não reproduz todo o estado anterior da sessão.

Tempo mediano do detector/rastreador nesse ensaio local: 29,5 → 30,7 ms no primeiro replay e 20,6 → 21,0 ms no segundo. Exclui decodificação, pose, JPEG, transporte e tela. Não houve medição física da velocidade ou validação de todos os impactos das gravações.

Resultados detalhados: `data/diagnostics/impact-baseline.json` e `impact-after.json`. Reproduza com `.venv/Scripts/python scripts/evaluate-impact-fix.py --baseline` e sem `--baseline` para o código atual.

## Áudio

O painel usa `public/audio/out.wav`, sintetizado localmente com a voz instalada Microsoft Zira. O controlador observa todos os eventos da sessão, deduplica pelo ID e espera 550 ms com duas câmeras para absorver a fusão. Não chama OUT para IN, REVISAR ou sessões antigas. O botão Testar som OUT desbloqueia o áudio mediante interação no navegador; a reprodução física depende do volume/dispositivo do usuário.
