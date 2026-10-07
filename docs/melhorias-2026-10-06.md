# Revisão, gravação original e diagnóstico · 6 de outubro de 2026

Windows 0.6.5 / Android 0.5.6. Os dados e pareamentos existentes são preservados. As atualizações continuam pelo GitHub.

## Como usar

### Android → Captura

- **Testar estabilidade do perfil:** mede captura e H.264 durante 25 segundos, com cinco segundos iniciais de aquecimento. Durante uma transmissão, também avalia envio, fila, perdas e temperatura. Sem conexão, testa a captura e codificação local. O relatório distingue falha de câmera, codificador e rede. Não troca o FPS escolhido; mantém um histórico dos últimos 30 testes.
- **Assistente de imagem:** usa a exposição, ISO e foco informados pela câmera para indicar iluminação insuficiente, risco de borrão e necessidade de travar o foco. Não mede a nitidez da bola por uma rede neural. Em alta velocidade, as restrições de exposição/foco do aparelho continuam valendo.
- **Gravar original no celular:** grava H.264 no MP4 com os timestamps do codificador, antes do transporte e da compressão da prévia do painel. Também permite gravar sem transmitir. Depois de parar, **Compartilhar último MP4** abre o compartilhamento do Android para enviar o arquivo ao PC.

A gravação começa em um quadro-chave. Uma fila limitada protege a captura de escrita lenta. Se o armazenamento não acompanhar, o trecho já gravado é finalizado e o app informa o problema; não descarta quadros intermediários dentro do arquivo. Arquivos só aparecem como MP4 concluídos depois da finalização. O espaço livre e a velocidade do armazenamento continuam sendo limites reais.

### Windows → Configurações

- **Analisar gravação original:** escolha o perfil da câmera, abra um MP4, marque a área no primeiro quadro e calibre as medidas quando necessário. Encerre a sessão ao vivo antes de analisar. A análise percorre todos os quadros decodificados com filas limitadas e o relógio do arquivo; pode levar mais tempo que o vídeo. O original e os resultados parciais ficam preservados. Limites: 1 GB, dez minutos e 150 mil quadros.
- **Sincronizar duas câmeras:** durante uma sessão com duas câmeras, grave um trecho de referência. Faça três gestos distintos visíveis nas duas imagens. Escolha os mesmos instantes nos dois replays, adicione as referências e salve. A interface mostra a diferença medida, variação e resolução temporal dos quadros. Repita se mudar a transmissão/rede.
- **Diagnóstico da postura:** mostra CPU/GPU e a comparação que autorizou a aceleração. O programa só usa DirectML quando o modelo aprovado e o driver passam na verificação de equivalência e desempenho. Em falha, retoma na CPU.

### Windows → Calibração

O assistente distingue **chão**, **parede** e **quadra de tênis**. Para chão/parede, marque os quatro cantos de um retângulo físico no mesmo plano e informe sua largura e altura reais. Para tênis, use os cantos externos da quadra. Os cantos podem ser ajustados; áreas cruzadas ou muito pequenas recebem feedback.

A área define o alvo de IN/OUT. A calibração relaciona pixels com medidas reais para projeção e velocidade. Quatro cantos não comprovam a precisão: pontos independentes com coordenadas medidas permitem calcular o erro. O assistente aceita esses pontos opcionais. Uma homografia de chão/parede não recupera o movimento tridimensional de uma bola no ar. Uma mudança de resolução invalida a escala anterior para aquela análise.

### Windows → Timeline → Revisar contato

Impactos iniciam automaticamente um replay associado à câmera, incluindo imagens anteriores e posteriores ao contato. Contatos próximos podem compartilhar um trecho. A revisão permite escolher a câmera, avançar quadro a quadro e ocultar as marcas originais.

- Corrigir **IN/OUT** ou manter para revisão.
- Marcar o **local real do contato**. A velocidade do ponto antigo é retirada, pois não foi calculada no ponto corrigido.
- Rejeitar um impacto que não aconteceu. Ele permanece no histórico de revisão, mas sai das contagens e marcas.
- Marcar uma bola perdida ou um objeto confundido com bola. O recorte revisado entra no conjunto local de aprendizado, conservando a sessão de origem para separar treino de avaliação.

A correção mantém o evento anterior em um registro de auditoria. Uma observação posterior da segunda câmera não sobrescreve a decisão humana. Estatísticas de sessões encerradas e vídeos analisados são atualizadas após a revisão. A revisão humana não dispara novamente o áudio OUT.

## O que muda na análise

Os eventos guardam evidências e replays por câmera. A fusão utiliza o deslocamento temporal medido, sua variação e a resolução real dos quadros. Sem referências salvas, usa o comportamento anterior. Permanecem as verificações de superfície, posição e ambiguidade; sincronizar não transforma as câmeras em um sistema de triangulação 3D.

O modo ao vivo mantém o quadro recente, enquanto a análise de arquivo aplica pressão entre etapas para preservar a sequência completa sem acumular uma fila grande. O atualizador Windows também espera análises de arquivo e treinamento terminarem antes de preparar uma atualização.

## Resultados medidos

- **Postura em 12 quadros reais do quarto, Radeon RX 9060 XT:** mediana de 22,36 ms no PyTorch/CPU e 2,44 ms no caminho ONNX/DirectML, incluindo preparação e saída. A diferença máxima das saídas foi 0,00116. O runtime ONNX isolado mediu 14,70 ms CPU e 1,92 ms GPU. Esse ganho é da inferência de postura, não uma medição do atraso total nem da precisão dos impactos.
- **Classificador de aparência existente:** 684 exemplos de treino e 295 de avaliação separados por sessão, com 35 bolas na avaliação. Nos recortes originais houve 34 acertos de bola, uma bola perdida e um falso positivo: precisão/recall de 97,1%. No ensaio sintético com aumento de brilho, a precisão caiu para 82,5%; com borrão horizontal, precisão/recall de 94,3%. Isso mede classificação de recortes revisados do quarto, não contato no chão, velocidade, detecção completa ou generalização para outra quadra. O modelo de bola atual foi mantido.
- **Testes de software:** 224 testes do backend, 87 testes Android, 23 testes Windows e 15 testes do painel aprovados. TypeScript e exportação de produção do painel aprovados; APK release compilado com o certificado anterior, preservando a compatibilidade de atualização.
- **Windows empacotado:** duas transmissões H.264 sintéticas abriram em uma pasta de dados isolada; as duas câmeras ativaram DirectML após a inicialização e comparação de desempenho feitas separadamente. Sequências de vídeo e análise corresponderam; reconexão, áreas, sessões e download do APK foram conferidos. Um MP4 sintético de 240 FPS teve todos os 480 quadros decodificados e analisados. Esse teste valida a análise de arquivo; não mede captura física ou 240 análises por segundo ao vivo.
- A conferência encontrou uma exportação anterior do painel dentro do instalador. O fluxo de build agora exporta e registra hashes dos fontes e arquivos produzidos, bloqueando o empacotamento de uma interface desatualizada ou incompleta. As licenças do novo runtime também acompanham o programa.
- Na interface final empacotada, editar a área/calibração ficou bloqueado durante a análise. Rejeitar um dos três contatos sintéticos atualizou a contagem para dois, conservou o evento como IGNORADO e não gerou erros no console. A revisão não é uma medição da precisão física do detector.
- Os testes de arquivo verificam timestamps de 120 FPS, leitura sequencial sem repetição/descarte, análise completa, preservação do perfil e estatísticas. Os testes Android cobrem os timestamps de 240 FPS no escritor, início em quadro-chave, armazenamento lento, relógio regressivo e preservação do arquivo se sua renomeação falhar. O escritor real usa MediaMuxer; os testes unitários desse caminho usam um destino de gravação substituto.

## Limites e teste físico pendente

Nenhum celular foi encontrado por ADB nesta revisão. Captura sustentada de 120/240 FPS, MP4 real do S25 Ultra, temperatura e atraso de sensor até tela precisam de teste físico. Suporte anunciado pela câmera/codificador não garante desempenho sustentado.

Também falta um conjunto revisado de contatos reais em quadra para medir precisão de IN/OUT e impacto. As medidas de classificador e postura acima não comprovam essa precisão. Bolas invisíveis, fora do enquadramento ou entre dois quadros não podem ter o contato recuperado com certeza. Próximo à linha, preserve a indicação de incerteza e revise as evidências.

## Reproduzir a validação

Execute `.venv/Scripts/python.exe -m pytest backend/tests -q`, `pnpm test` em `desktop` e `gradlew.bat :app:testDebugUnitTest :app:assembleRelease` em `mobile/android`.

Para a postura, `scripts/export-pose-onnx.py` exporta o modelo e `scripts/benchmark-pose-runtime.py` compara as saídas com os pesos originais em imagens escolhidas. O arquivo de aprovação vincula os hashes do PT e do ONNX. Pesos personalizados ou alterados continuam no caminho original, sem usar uma aprovação de outro modelo.
