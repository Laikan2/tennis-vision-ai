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

A 0.6.14 ainda não foi instalada nem validada fisicamente neste relatório. Os números de câmera e as tentativas de quicada descritos na 0.6.13 não são resultados da 0.6.14. Nem os testes sintéticos nem uma compilação bem-sucedida comprovam IN/OUT em partidas, posição exata de impactos, velocidade real de saques ou captura sustentada em 120/240 FPS.
