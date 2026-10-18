# Tennis Vision mobile 0.6.11 — Android e iPhone

- Reduz o custo de acessar os buffers da análise completa no iPhone. Mantém resolução, pixels e critérios do detector.
- O editor do chão explica por que dois pontos próximos ou verticais impedem salvar. Um novo toque agora permite ajustar o extremo mais próximo, sem recomeçar a marcação inteira.
- Preserva o histórico, pareamento, vozes e preferências. Áreas continuam temporárias por sessão, como na versão 0.6.10.

Para marcar o chão: desenhe a área no passo 1; no passo 2, escolha um ponto à esquerda e outro à direita no encontro parede/piso. A orientação indica quando o limite está pronto para salvar.

Na comparação Release repetida em ordem inversa, o núcleo ficou **4,8–7,2 vezes mais rápido**, com resolução integral e os mesmos resultados comparados. Passaram 241 testes Android, 108 testes Swift, dois testes de segurança de memória e 10 testes dentro do Android 15 emulado. Os tempos foram medidos no computador da CI; a nova versão ainda precisa de nova medição de câmera no iPhone físico.

Comparações controladas e limites constam no [relatório de validação](https://github.com/Laikan2/tennis-vision-ai/blob/mobile/analysis-throughput-0.6.11/docs/validation/mobile-0.6.11.md). O ganho do núcleo no computador de teste não equivale à taxa real da câmera do celular. A precisão em partidas e modos sustentados de 120/240 FPS continuam sem comprovação.

Android: Atualizações → Buscar atualização → Instalar. iPhone: AltStore → My Apps → Update, com a assinatura pessoal habitual.
