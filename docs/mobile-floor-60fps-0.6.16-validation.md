# Impactos de chão em 60 FPS — mobile 0.6.16

## Falha reproduzida e correção

O usuário relatou ausência de impacto e velocidade mesmo em 60 FPS. A versão 0.6.15 foi testada antes da alteração com pixels ARGB e timestamps gerados: três controles positivos falharam entre oito testes, apesar de a bola permanecer observada. Os cinco controles negativos passaram. Isso reproduz mecanismos compatíveis com a queixa, mas não determina a causa exata de cada quicada física anterior.

A janela antiga aceitava até 200 ms de trajetória e oito espaçamentos de quadros, com histórico máximo de 40 observações. Um retorno mais lento podia não fornecer deslocamento suficiente nesse período. Uma perda de observação maior que 100 ms também apagava a descida antes de a bola reaparecer.

Android e iPhone agora procuram evidência dentro de até 420 ms. A extensão exige referência de chão válida, bola resolvida, cor apoiada nas observações, tamanho consistente, ausência de mão/oclusão/borrão e dois trechos coerentes. O histórico permanece limitado a 600 ms/160 observações. A confirmação usa o retorno observado; a marca de uma janela longa usa amostras locais no pico, evitando deslocá-la pela curvatura dos trechos externos.

Uma descida limpa pode ser retida por até 160 ms sem observação, com referência de chão. A previsão desenhada continua expirando após 60 ms. Uma lacuna não cria amostras de bola e força REVISAR no contato, com incerteza proporcional ao movimento e ao tempo ausente. O cálculo da velocidade mantém a proteção contra intervalos ausentes maiores que 75 ms.

## Resultados locais verificados

- **274 testes Android/JVM:** zero falhas, erros ou testes ignorados. APK Release compilado, manifesto e certificado anterior conferidos; vozes offline mantidas. Lint: zero erros/fatais; permanecem 150 avisos.
- **28 testes Python:** publicação coordenada, fonte AltStore, áudios e comparação de desempenho passaram.
- **11 novos testes de pixels:** IN e OUT em 60 FPS com posição e velocidade de entrada verificadas, suporte temporal em 30/120/240 FPS, contato entre quadros, bola carregada, queda sem retorno, limite do piso, falta de referência, perdas curta/longa e prazo da previsão passaram.
- No controle visível de 60 FPS, contato conhecido em 0,300 s: marca em 0,30017 s e y=194,5 px, velocidade lateral estimada de aproximadamente 1,162 km/h. IN e OUT foram decididos conforme os polígonos distintos, mantendo a mesma posição da marca.
- A decisão desse retorno lento chegou em 0,500 s: **200 ms após o contato**, sem introduzir fila ou espera na prévia. Nos controles de 120/240 FPS, foram 183/175 ms. Esses valores medem a evidência temporal do controle, não latência física total ou FPS de um celular.

Detalhes numéricos: [regressão de impactos](validation/mobile-0.6.16/floor-60fps-regression.json) e [compilação Android](validation/mobile-0.6.16/android-build.json).

## Validações externas e limites

Os testes nativos/compilações do iPhone, transmissão decodificada, Android emulado offline e comparação de desempenho foram enviados ao GitHub. Aguardam execução; seus resultados não estão incluídos nas afirmações acima.

Nenhum celular foi acessado nesta etapa, a pedido do usuário. Não houve instalação, teste físico de bola, medição física de FPS ou validação de saques rápidos nesta versão. Dados sintéticos e três imagens anteriores de desenvolvimento não são um conjunto de contatos reais anotados. Não houve treinamento de modelo neural.

Uma câmera monocular não comprova todo contato físico, retorno no ar ou velocidade 3-D. Mãos, oclusões, bolas minúsculas/borradas e posições próximas à linha continuam exigindo cautela/revisão. A velocidade pode ficar indisponível quando não houver evidência observada válida; não se inventa valor para preencher a marca.

Histórico, formatos de armazenamento, vozes e identidade de atualização foram mantidos. Áreas continuam temporárias por sessão. A versão instalada anteriormente no iPhone não foi substituída remotamente nesta etapa.
