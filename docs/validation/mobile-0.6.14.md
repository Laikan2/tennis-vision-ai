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

A primeira execução local completa após a correção do motor passou nos 253 testes Android JVM/Robolectric, incluindo os dois novos controles. A execução anterior desses dois controles apresentou uma falha após a mão, com o controle livre aprovado. Compilação final, testes Swift nativos e pacotes desta versão ainda estão sendo conferidos; os resultados serão registrados aqui quando concluídos.

## Limites

A 0.6.14 ainda não foi instalada nem validada fisicamente neste relatório. Os números de câmera e as tentativas de quicada descritos na 0.6.13 não são resultados da 0.6.14. Nem os testes sintéticos nem uma compilação bem-sucedida comprovam IN/OUT em partidas, posição exata de impactos, velocidade real de saques ou captura sustentada em 120/240 FPS.
