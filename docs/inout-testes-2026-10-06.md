# IN/OUT · testes e correções · Windows 0.6.6

Prioridade: bola de tênis verde. Android permanece na 0.5.6; a análise dos impactos ocorre no Windows. As configurações, pareamentos, áreas e sessões existentes permanecem preservados.

## Causas reproduzidas e correções

- **Bola na mão sem postura disponível:** nos quadros revisados de 18 de setembro, uma região encoberta pela mão encolhia abruptamente de 88 para 37 pixels. A sequência parecia uma descida e retorno e gerava um falso OUT. O contato agora exige espessuras coerentes entre as leituras de retorno. O rastreamento da bola pode continuar, mas essa sequência não prova impacto.
- **Batida na porta contada como chão:** a área válida de IN/OUT não descreve, sozinha, todo o piso fora dela. O antigo limite amplo de altura deixava passar uma inversão na porta. O limite físico do piso agora pode ser marcado separadamente, sem cortar a busca da bola nem excluir o chão fora do alvo.
- **Faixa possível cruzando o canto da área:** duas posições possíveis podem estar fora e a faixa entre elas atravessar o alvo. OUT agora exige que toda a faixa possível, incluindo a margem do tamanho da bola e a incerteza, esteja afastada da área. Caso contrário, o evento fica para revisão e não anuncia OUT.
- **Revisão depois de editar a área:** os novos contatos guardam área, zona, orientação, limite do piso e calibração usados naquele momento, por câmera. Corrigir o ponto de um contato usa essa referência original. Uma câmera secundária conserva sua própria geometria. Gravações antigas sem essa referência recebem uma explicação na revisão.
- A revisão mostra a área válida original, o local estimado, a faixa entre posições possíveis e a incerteza. Dados inválidos de posição, tamanho ou incerteza não geram uma chamada OUT.
- O painel parado agora usa o modo e as áreas da câmera selecionada. Antes, o título e as ferramentas podiam indicar parede enquanto o seletor já indicava chão. As linhas só são desenhadas quando há uma imagem com resolução conhecida.

## Como separar o chão da parede

1. Inicie a câmera e escolha **Chão** em **Detectar impacto**.
2. Abaixo do vídeo, clique em **Separar chão da parede**. A imagem pausa para marcar.
3. Clique onde o piso encontra a parede à esquerda e à direita. Se houver uma esquina, marque também esse canto. São aceitos de dois a seis pontos; os extremos continuam até as laterais da imagem.
4. Ajuste os círculos arrastando ou usando as setas do teclado e clique em **Salvar limite do piso**.
5. A linha amarela representa o piso; a área de IN/OUT continua com seus quatro cantos. Salve um limite para cada câmera, pois a perspectiva muda.

Esta seleção é opcional e não altera a área anterior. Use **Ajustar limite do piso** para editar e **Remover limite do piso** para voltar ao comportamento anterior. A bola continua sendo acompanhada no ar. A linha apenas exclui a parede acima dela; contato ainda exige descida e retorno medidos. Em uma quadra aberta sem parede visível, preserve o enquadramento e a seleção da quadra.

## Comparação nos quadros reais

Foram reprocessados **1.247 JPEGs originais salvos nos replays**, sem marcas desenhadas dentro da imagem. A detecção e o rastreamento foram executados novamente; foram reutilizadas as observações de postura desses quadros. O classificador de aparência existente foi mantido e a memória automática foi copiada para dados isolados. Os testes não alteraram os dados da sessão ao vivo.

A comparação usa as mesmas novas leituras de bola para o algoritmo de impactos anterior e o corrigido. Na sequência da tarde, o novo limite do piso foi marcado manualmente sobre o rodapé e a esquina visíveis. Esse resultado depende dessa configuração explícita; o programa não detectou a linha automaticamente.

- **Tarde de 18 de setembro, 428 quadros:** 22 eventos antes e 20 depois. Foram removidos os dois falsos OUT revisados, um na mão e outro na porta. Os 17 IN e três contatos para revisar permaneceram com as mesmas decisões.
- **Noite de 17 de setembro, 539 quadros:** 34 eventos antes e depois, com 24 IN, três OUT e sete para revisar. Nenhuma decisão mudou.
- **Trecho posterior de 17 de setembro, 280 quadros:** oito eventos antes e depois. A área original não estava disponível na evidência; mantiveram-se inconclusivos, sem inventar uma área para decidir IN/OUT.

Esses números medem regressão sobre gravações já utilizadas no desenvolvimento. Não constituem um novo conjunto independente de avaliação, nem uma taxa de acerto de contatos em quadra. Nem todo ponto de contato físico aparece no JPEG; alguns trechos têm intervalos de 80–280 ms entre observações. Nesses casos, a posição continua estimada entre quadros.

## Desempenho medido nesta execução

A mediana de detecção, rastreamento e atualização da memória foi **14,8 ms / 17,1 ms / 15,8 ms** nos três trechos; o percentil 95 foi **20,7 / 24,6 / 22,8 ms**. Os dois algoritmos de contato juntos acrescentaram medianas de **0,046 / 0,113 / 0,023 ms**, com percentis 95 de **3,04 / 2,81 / 1,93 ms**.

Esses tempos excluem leitura/decodificação, inferência de postura, rede, codificação da prévia e renderização. Não medem atraso do sensor até a tela e não comprovam análise ao vivo de 120/240 quadros por segundo. Esta correção não adiciona uma nova rede neural por quadro.

## Testes de software e limites

Foram aprovados **254 testes do backend**, **23 do Windows**, **15 do painel** e **87 do Android**, além da checagem TypeScript. Os novos casos cobrem o falso contato da mão, separação piso/porta, esquina do quarto, persistência independente de duas câmeras, revisão após mudar área/escala, incerteza próxima à linha e trajetórias sintéticas densas em 60/120/240 FPS. Um ensaio de 250 faixas aleatórias verifica que nenhum OUT cruza a área entre os extremos.

O Windows 0.6.6 compilado iniciou com o motor pronto e a ponte do aplicativo funcionando, usando dados isolados. Um MP4 sintético de 240 FPS, 640 × 360 e 480 quadros foi decodificado e analisado por inteiro: três quicadas previstas, com duas IN e uma OUT, sem duplicação. O processamento levou 5,25 segundos para um arquivo de dois segundos; é análise de arquivo, não comprovação de captura ou análise ao vivo a 240 FPS.

Na interface compilada foram conferidos três pontos no rodapé, ajuste por arraste e teclado, cancelamento, persistência após recarregar e reiniciar, manutenção da área de IN/OUT e independência da segunda câmera. Editar a área da câmera depois da análise não modificou o polígono exibido na revisão do contato antigo. As ferramentas e o diálogo de revisão foram verificados em larguras de 430 e 1280 pixels, sem erros de console. A edição ficou bloqueada durante a análise da gravação. O manifesto, o hash do instalador e a correspondência entre código e interface exportada passaram pela verificação de publicação.

Falta validar precisão e atraso total com a câmera/celular físicos e contatos revisados em uma quadra real. Nenhum percentual de precisão de VAR é atribuído a esses testes. Bola oculta, contato fora do enquadramento e falta de quadros continuam limitando a localização. A posição projetada de uma bola no ar não recupera, sozinha, profundidade tridimensional nem contato físico. Decisões perto da linha continuam exigindo revisão; não são arbitragem oficial.
