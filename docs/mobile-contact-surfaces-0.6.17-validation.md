# Marcas no chão, na quadra e na parede — 0.6.17

## Evidência recebida

Análise visual dos dois vídeos de tela enviados em 21/10/2026. No vídeo da parede, a interface permanece em **IMPACTOS SUSPENSOS**: o rastreamento continua, mas a referência da área exige confirmação. Isso explica a ausência de novas marcas nesse estado; a gravação não permite afirmar se a câmera se moveu ou se uma obstrução disparou o bloqueio.

No vídeo do chão há novas marcas IN na primeira sequência. Nas sequências seguintes, a linha laranja diagonal deixa uma parte do piso real no lado excluído. O algoritmo rejeita retornos acima dessa referência, mesmo quando localiza a bola. Não seria correto resolver isso contando qualquer passagem no ar como contato.

Os vídeos são gravações de tela comprimidas, com contornos e textos já desenhados. Seus aproximadamente 60 FPS não medem os FPS da câmera ou da análise. Não foram usados como se fossem imagens brutas nem como prova quantitativa de precisão física. Os arquivos e imagens pessoais permanecem fora do repositório e dos aplicativos.

## Correções

- **Toda a imagem é piso · salvar**, no segundo passo de Marcar chão e no ajuste do limite. Dispensa desenhar uma linha fictícia quando só aparece piso. Mantém o polígono IN/OUT e a área separada da parede.
- Sombra do lado excluído pela linha, durante a edição e o treino. Retorno rejeitado acima do limite informa o motivo.
- Geometria do limite continua válida após pequena compensação da câmera. O editor continua exigindo pontos dentro da imagem; a projeção tem sua validação separada. Polígonos compensados conservam IN/OUT e guias dentro da margem de movimento permitida.
- **Área coincide · retomar impactos** exige confirmação explícita na vista atual. Preserva histórico e configurações, limpa marcas da referência anterior e reinicia a referência. Não libera automaticamente uma câmera reposicionada apenas porque ela ficou parada.
- Parede usa deslocamento e tempo observados, em vez de depender de apenas cinco quadros e um corte fixo de velocidade em pixels. Mantém evidência nas duas pernas, coerência espacial, rejeição de mão/oclusão e revisão obrigatória do contato monocular.
- Marcas com mira e número, velocidade lateral quando válida e resumo do último contato na tela cheia do iPhone. O contorno da bola continua separado das marcas históricas.
- Editor Android recalcula a altura da prévia quando os controles mudam; teste visual encontrou e reproduziu botões cortados antes dessa correção.

## Validação

Em andamento. Os primeiros 50 testes dirigidos passaram. A falha anterior da linha compensada foi reproduzida e preservada no registro local; a falha visual do editor também foi convertida em teste e corrigida. A suíte completa, os aplicativos compilados e os testes nativos serão registrados antes da publicação.

Os controles incluem chão, quadra inteira, meia quadra, retornos de parede em 30/60/120/240 FPS de entrada sintética, bola carregada na mão e passagem sem retorno. O mesmo contato abaixo de uma linha mal colocada é comparado com a configuração de imagem inteira como piso. Os testes Android de aparelho usam pacote de auditoria isolado, sem dados pessoais.

## Limites

Nenhum celular físico foi acessado nesta revisão. Testes gerados ou em emulador não comprovam acerto em todos os impactos reais, saques de 200 km/h ou 240 FPS sustentados. Uma câmera voltada diretamente para baixo pode não mostrar uma mudança suficiente da trajetória no plano da imagem. Desaparecimento, velocidade ou presença da bola sozinhos não comprovam contato.

Na parede, a marca é candidata para **REVISAR**; o retorno também pode ter sido causado por mão ou raquete. Não há áudio OUT automático para esse candidato. Velocidade é estimativa lateral pelo diâmetro aparente da bola, não medição 3D ou radar. Histórico, vozes e preferências são mantidos; áreas continuam temporárias por sessão, como nas versões anteriores.
