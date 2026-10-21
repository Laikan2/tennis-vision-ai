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

Código dos aplicativos validado no commit `db4f0c183cd21caa1b96235ea01c3552ec871ee3`.

- **282 testes Android/JVM**, sem falhas, erros ou testes ignorados. APK release compilado; versão 0.6.17/code 30 e certificado de atualização conferidos no arquivo real. Lint sem erros/fatais, com 156 avisos ainda existentes.
- **147 testes Swift** aprovados no [CI iOS](https://github.com/Laikan2/tennis-vision-ai/actions/runs/39546388994). Builds de iPhone e simulador aprovados; IPA 0.6.17/build 18 verificado, com contrato compartilhado e vozes idênticos aos esperados. Integração recebeu e decodificou 60 quadros H.264. A etapa opcional de abrir o simulador/capturar a tela excedeu dois minutos; não há prova visual da execução iOS nem validação em telefone físico.
- **11 cenários no Android 15/API 35 emulado**, incluindo o novo botão de piso, permissões concedida/negada, áreas separadas, cancelamento, câmera, reabertura, bloqueio da tela, economia de bateria e ausência de rede. A segunda execução do [CI Android](https://github.com/Laikan2/tennis-vision-ai/actions/runs/39546388790) passou integralmente. A primeira ficou sob um diálogo de ANR do Pixel Launcher, que interceptava os toques; os registros da falha foram preservados, sem desabilitar ANRs ou enfraquecer as verificações.
- O fluxo Android decodificou **191 quadros H.264 de controle**, gerou marcas IN/OUT/REVISAR, verificou contagens persistidas e aceitou uma reprodução OUT no SoundPool. Áudio aceito no emulador não comprova audibilidade física.
- **28 testes Python** de publicação conjunta, fonte AltStore, áudios e comparação de desempenho passaram. A execução inicial não conseguiu criar a pasta temporária do ambiente restrito; repetida com pasta explícita no workspace, passou.
- Dois testes de memória com AddressSanitizer e 12 cenas de referência em duas ordens de execução passaram no [CI de desempenho](https://github.com/Laikan2/tennis-vision-ai/actions/runs/39546388956). Bola, rastro, impactos e estados permaneceram iguais à referência 0.6.10; somente as velocidades já alteradas em 0.6.15 diferem. Essa comparação não mede o ganho desta versão sobre 0.6.16 nem os FPS de um iPhone.

A falha da linha compensada foi reproduzida antes da correção. Os seis controles novos passaram depois dela. O corte dos botões foi capturado visualmente, convertido em asserção de posição na tela e corrigido; o teste de toque no emulador também passou. Registros em [validation/mobile-0.6.17](validation/mobile-0.6.17).

Os controles incluem chão, quadra inteira, meia quadra, retornos de parede em 30/60/120/240 FPS de entrada sintética, bola carregada na mão e passagem sem retorno. O mesmo contato acima de uma linha mal colocada é comparado com a configuração de imagem inteira como piso. Os testes Android de aparelho usam pacote de auditoria isolado, sem dados pessoais.

O processamento do vídeo sintético teve mediana de 5,95 ms e percentil 95 de 24,14 ms no emulador; não é atraso ponta a ponta nem medida de câmera física. A captura emulada 720p teve instantâneo de apenas 9 FPS, com conversão 77,7 ms e detecção 106 ms. O emulador serve para validar ações e resultados, não para demonstrar fluidez ou 120/240 FPS no celular.

## Limites

Nenhum celular físico foi acessado nesta revisão. Testes gerados ou em emulador não comprovam acerto em todos os impactos reais, saques de 200 km/h ou 240 FPS sustentados. Uma câmera voltada diretamente para baixo pode não mostrar uma mudança suficiente da trajetória no plano da imagem. Desaparecimento, velocidade ou presença da bola sozinhos não comprovam contato.

Na parede, a marca é candidata para **REVISAR**; o retorno também pode ter sido causado por mão ou raquete. Não há áudio OUT automático para esse candidato. Velocidade é estimativa lateral pelo diâmetro aparente da bola, não medição 3D ou radar. Histórico, vozes e preferências são mantidos; áreas continuam temporárias por sessão, como nas versões anteriores.

## Publicação

[Versão mobile 0.6.17](https://github.com/Laikan2/tennis-vision-releases/releases/tag/mobile-v0.6.17) publicada com APK, IPA para assinatura pessoal, projeto iOS e metadados. Downloads APK/IPA foram conferidos por tamanho e SHA-256; a fonte oficial do AltStore foi relida sem cache e anuncia 0.6.17/build 18. A referência `latest` do Windows continua em v0.6.11. A instalação desta versão no celular do usuário não foi realizada nesta revisão.
