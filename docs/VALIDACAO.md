# Protocolo de validação por etapas

Nenhum resultado numérico de precisão de campo foi obtido nesta entrega. Os testes automatizados são controles de software, não substitutos de filmagem, radar ou marcação manual.

## 1. Captura da Hikvision

Registre modelo/variante, firmware, lente, resolução configurada e observada, FPS configurado e recebido, shutter, ganho, codec, bitrate, intervalo de I-frame, posição/altura/ângulo da câmera, distância, campo de visão e iluminação. Os campos ainda desconhecidos devem permanecer desconhecidos.

Configure a câmera e faça gravações em diferentes condições. Reduza tempo de exposição para reduzir blur, verificando ruído e iluminação. Confirme os controles disponíveis no firmware; não assuma que a IA recupera detalhes ausentes. Preserve um conjunto de imagens de bolas rápidas, pequenas, ocultas e próximas a jogadores/linhas.

Valide estabilidade por ao menos uma sessão completa, desconecte/reconecte a rede e observe FPS, idade local do frame e descartes. O MVP mede timestamps de chegada, não o instante de exposição. Para medir latência ponta a ponta, filme um relógio/LED de referência e compare origem e tela de saída.

## 2. Detector

Anote bounding boxes em dados reais e divida por sessão/condição, nunca distribuindo frames adjacentes entre treino e teste. Treine/fine-tune com classes corretas. Meça precisão/recall, falsos positivos e falhas por tamanho de bola, exposição e velocidade. Teste um conjunto reservado e registre versão/hash dos pesos, limiar e condições. Fallback de cor/movimento não é detector validado.

## 3. Tracking

Anote identidade da bola em sequências. Meça trocas de ID, perda/recuperação e erro de posição durante oclusão. Verifique que previsões não entram como novas detecções nem como evidência de quique. Ajuste gating e duração de previsão com base nos timestamps e na resolução reais.

## 4. Calibração

Marque os quatro cantos externos. Para avaliar a homografia, envie pelo menos dois outros pontos conhecidos da quadra em `checks`:

```json
{"image":[500,320],"court":[1.37,5.485]}
```

Os números acima ilustram o **formato**, não pontos medidos. Substitua pelas suas marcações. A API `/api/calibrations` aceita `imagePoints`, `resolution`, `cameraId`, `name` e `checks`. O RMSE dos pontos de verificação é um erro observado; não é intervalo de confiança nem mede viés por distorção. Sem pontos independentes, a qualidade fica “não validada”, mesmo com ajuste perfeito dos quatro cantos.

Marcar os cantos com resolução diferente invalida o perfil. Mover/rotacionar a câmera ou alterar zoom/lente exige nova calibração mesmo que a resolução não mude. Calibração intrínseca e correção de distorção permanecem necessárias para precisão superior.

## 5. Velocidade

Confirme escala com movimento conhecido no plano. Compare os cálculos com timestamps irregulares, interrupções e mudanças de ID. Isso valida somente a projeção planar. Para velocidade da bola em voo, implemente uma reconstrução física validada ou duas vistas calibradas/sincronizadas. Não publique `projectedKmh` como velocidade de saque.

Colete pares externos realmente sincronizados e registre fonte, evento, estimativa e referência em “Precisão do sistema”. O app calcula MAE, erro percentual médio, viés e desvio padrão. O IC 95% aproximado do viés só aparece a partir de 30 pares e pressupõe independência. Não representa a incerteza de cada golpe.

## 6–8. Quique, posição e IN/OUT

Anote o frame de contato com câmera de referência. Separe quique, golpe na raquete, oclusão, erro de tracking e mudança aparente por perspectiva. Compare candidatos com anotações. Só habilite eventos confirmados após definir critérios e validar precisão/recall. A classificação de limites usa o envelope conservador de erro e permanece inconclusiva próximo às linhas. A geometria atual é a quadra externa de duplas, não regras de saque/simples.

## 9–10. Dashboard, replay e histórico

Confira que cada overlay corresponde ao próprio frame e que a pausa/replay preserva timestamps. Verifique memória, tamanho máximo, fechamento parcial ao encerrar, persistência após reinício e exportação de nulos. Teste campo vazio, stream offline, modelo ausente e perda do backend.

## 11–12. Próximas fases

Treine detector com dataset da câmera. Para fusão, estabeleça relógios de origem sincronizados, associação da mesma bola entre vistas, intrínsecos/extrínsecos e erro de reprojeção. A função de triangulação existe, mas o pipeline multicâmera ainda não está conectado.
