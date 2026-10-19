# Tennis Vision mobile 0.6.14 — bola solta e estado da contagem

## Falha reproduzida

No ensaio físico da [0.6.13](mobile-0.6.13.md), a bola próxima na mão pôde ser acompanhada sem contatos falsos. As tentativas de quicada não produziram eventos, mas a câmera se moveu e houve períodos de contagem pausada e de referência suspensa: não foi possível medir a sensibilidade física de forma válida.

Um teste independente, com imagens geradas e movimento conhecido, isolou outro defeito. Na mesma trajetória sobre um piso de duas cores, a bola começando livre produzia um contato; depois de ser apresentada na mão, o estado anterior de proximidade da mão continuava sendo herdado pela cor quente do fundo. O controle positivo passou e a tentativa após soltar falhou na 0.6.13.

## Correção

- Android e Swift agora exigem evidência atual de mão, movimento de pele ou silhueta parcial. O estado anterior sozinho não mantém esse bloqueio. Foram mantidos os testes de movimento carregado, oclusão, sinais de cor e contato antes/depois da mudança de direção.
- Os dois motores têm controles de bola livre e de bola primeiro segurada, depois solta, com a mesma trajetória e fundo. O teste exige continuar vendo a bola e registrar exatamente um contato; não pode passar apenas perdendo o alvo.
- No iPhone, a tela normal e a tela cheia indicam CONTAGEM PAUSADA, CONTAGEM DE IMPACTOS ATIVA ou IMPACTOS SUSPENSOS. Mudança de referência fica em laranja, com orientação para fixar o celular e conferir a área. Android destaca a suspensão em laranja e inicia o aviso com IMPACTOS SUSPENSOS.
- O relatório sintético do iPhone lê a versão do aplicativo em execução, em vez de carregar um número antigo fixo.
- Android 0.6.14/código 27 e iOS 0.6.14/build 15 compartilham a alteração. Histórico, vozes e pareamento mantidos; áreas continuam temporárias por sessão.

Não houve treinamento ou troca de pesos de modelo neural. Não foram removidas as proteções para mudança de câmera ou incerteza perto da linha.

## Validação

- **253 testes Android JVM/Robolectric passaram**, sem falhas, erros ou ignorados na conferência final. Na primeira bateria, um teste privado fora ignorado por falta do caminho dos arquivos; a bateria foi repetida com os dados presentes. Os dois controles novos passaram. Antes da correção, o controle de bola livre passava e o de liberação após a mão falhava.
- APK Release compilado, com manifesto 0.6.14/código 27, certificado anterior e hashes das vozes conferidos. Lint: zero erros/fatais, **150 avisos restantes**. Nas quatro imagens privadas de desenvolvimento já usadas na 0.6.13, a reprodução independente manteve os mesmos resultados: três regiões grandes de bola com evidência de mão e uma imagem cortada/subexposta sem observação. Isso não é avaliação inédita nem sequência de impacto.
- **21 testes Python passaram** para publicação coordenada, fonte AltStore e áudios decodificados.
- **10 testes Android 15/API 35 emulado passaram**, com câmera permitida/negada, editor chão/parede, nova sessão/histórico, toques rápidos, segundo plano, recriação da tela, bloqueio e economia de bateria. A rede padrão ficou ausente antes e depois. O app auditado não tem permissão INTERNET. Não houve teste Android físico nesta etapa.
- Android MediaCodec decodificou **191 quadros sintéticos**, produzindo IN, OUT e REVISAR com persistência. A captura foi inspecionada: marcas, etiquetas e velocidades estimadas estavam ligadas aos pontos por linhas. O SoundPool aceitou um OUT; o som do emulador estava desativado, portanto a audibilidade não foi comprovada. A mediana/p95 de análise nesse trecho emulado foi 4,15/18,04 ms, sem representar FPS do S25 ou atraso físico.
- A comparação nativa em Release, no mesmo computador Apple de CI, passou em **12 cenas/resoluções, repetidas em ordem inversa**. As saídas de referência permaneceram idênticas nas duas ordens. Contra a base 0.6.10, os tempos ficaram entre 4,59 e 6,68 vezes menores na primeira ordem e entre 4,54 e 6,66 na inversa. Isso não compara 0.6.13 com 0.6.14 no celular. O comportamento de liberação que mudou está na regressão separada. **Dois testes de buffers com AddressSanitizer passaram**, sem erro detectado nesses casos.

- **120 testes Swift passaram** na [execução nativa](https://github.com/Laikan2/tennis-vision-ai/actions/runs/39194698319), com compilação para iPhone sem assinatura, compilação para simulador, IPA 0.6.14/build 15 e **60 quadros H.264 recebidos e decodificados**. Contrato de recursos, binário para aparelho e hashes das vozes conferidos. A abertura opcional da interface no simulador excedeu dois minutos e não produziu screenshot; a execução dessa interface no simulador continua sem comprovação, embora o API marque a etapa como sucesso por `continue-on-error`.

Evidências: [compilação Android](mobile-0.6.14/android-build.json), [Android emulado](mobile-0.6.14/android-runtime.json), [iOS nativo](mobile-0.6.14/ios-ci.json), [liberação da bola](mobile-0.6.14/release-regression.json), [desempenho](mobile-0.6.14/performance.json), [primeira ordem](mobile-0.6.14/comparison.json) e [ordem inversa](mobile-0.6.14/comparison-repeat.json).

## Limites

A instalação da 0.6.14/build 15 foi confirmada no iPhone 15 Pro com iOS 27.2. O ensaio físico abaixo encontrou falhas; IN/OUT não foi aprovado. Os números e tentativas da 0.6.13 continuam separados. Nem os testes sintéticos nem uma compilação bem-sucedida comprovam IN/OUT em partidas, posição exata de impactos, velocidade real de saques ou captura sustentada em 120/240 FPS.

## Publicação

A [0.6.14 está publicada para Android e iPhone](https://github.com/Laikan2/tennis-vision-releases/releases/tag/mobile-v0.6.14). A fonte pública AltStore foi lida novamente e confirmou 0.6.14/build 15, com tamanho e endereço do IPA corretos. APK/IPA e arquivos enviados tiveram a integridade conferida; o ZIP contém somente fontes rastreadas do projeto iOS/recursos compartilhados. O marcador latest do Windows permanece em v0.6.11. Veja a [verificação da publicação](mobile-0.6.14/publication.json).

Após o usuário atualizar e reconectar, a versão 0.6.14/build 15 foi lida no aparelho. Os 44 eventos antigos e as duas sessões permaneceram intactos. A instalação está comprovada; a detecção física e o IN/OUT não foram aprovados.

## Ensaio físico posterior à instalação

- Câmera real, 720 × 1280, 60 FPS solicitados. No primeiro intervalo sem bola/mãos, confirmado pelo usuário, chegaram 1.196 quadros à interface em 20,015 s, com indicador final de 60,01 FPS e análise média de 10,37 ms. Houve **647 quadros com alvo observado e 10 candidatos falsos a contato**. O modo diagnóstico não escreve no treino.
- Com a bola segurada, sem quicar, 1.037 de 1.061 quadros entregues tiveram observação; o contorno inteiro e a indicação de mão apareceram na imagem inspecionada. Mesmo assim, houve **um candidato falso a contato**. Análise média de 16,19 ms, indicador final de 56,65 FPS. Isso não mede precisão do centro nem sensibilidade a bolas distantes.
- Área e linha do piso foram salvas; a linha foi verificada quase horizontal no alto. A área ocupou grande parte da imagem, deixando margem externa inadequada para um bom teste OUT. Foi observado CONTAGEM DE IMPACTOS ATIVA antes e depois do controle negativo, sem suspensão nas telas inspecionadas. Em 20,14 s de amostragem da tela (62 imagens), os eventos ficaram em **44 → 44**. Amostras não são vídeo contínuo do sensor.
- O usuário confirmou **três quicadas, todas dentro**. Os registros passaram de **44 → 47: três REVISAR, zero IN e zero OUT**. Isso não comprova três acertos: os dois primeiros quadros de evidência mostram bola grande ainda na mão, embora os raios registrados fossem 2,5 e 1 px. No terceiro, a mão aparece no ponto baixo e o motivo do evento indica quadros espaçados. A prévia amostrada também mostrou uma bola livre com contorno correto. Não foi possível parear cada marca com cada contato real. Houve intervalo de aproximadamente 18 s entre lotes de captura da tela.
- Uma repetição do diagnóstico sem novas quicadas solicitadas, com amostragem da tela em paralelo, teve **zero observações e zero candidatos**, mas apenas 549 quadros entregues à interface em 20,025 s; indicador final de 42,16 FPS e análise média de 19,22 ms. É uma medição sob carga, com enquadramento/iluminação e uso acumulado diferentes; não comprova a causa da queda nem compara versões. O estado térmico não foi registrado.
- A fila de evidências do iPhone guarda seis quadros; não registra a distância temporal da imagem escolhida ao contato estimado. Portanto o quadro salvo, sozinho, não comprova o instante exato do impacto.
- Em uma segunda tentativa, a linha inicialmente diagonal foi corrigida e a contagem ativa foi verificada. O usuário informou outras **três quicadas dentro**, mas o arquivo ficou em **47 → 47**, sem novos contatos. Marcas e som estavam ligados nos ajustes salvos. As amostras iniciais estavam muito escuras; a tela final pausada mostrava piso iluminado e enquadramento diferente. Sem sequência completa de pixels/diagnóstico durante as três tentativas, não foi isolada a causa de cada perda. O resultado confirma eventos ausentes, sem constituir um ensaio controlado de sensibilidade. Depois disso, o acesso ao aparelho foi interrompido a pedido do usuário, que passaria a usá-lo.

As falsas observações e a perda da silhueta motivaram correções separadas na 0.6.15. O teste OUT foi adiado até obter um controle positivo IN válido. Não houve validação de partida, saque rápido, 120/240 FPS sustentados ou velocidade física. Veja a [evidência sanitizada](mobile-0.6.14/iphone-physical.json); fotos, vídeos, identificadores pessoais e arquivos de sessão permanecem privados.
