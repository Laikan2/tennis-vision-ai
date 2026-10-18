# Tennis Vision mobile 0.6.11 — Android e iPhone

- Reduz o custo de acessar os buffers da análise completa no iPhone. Mantém resolução, pixels e critérios do detector.
- O editor do chão explica por que dois pontos próximos ou verticais impedem salvar. Um novo toque agora permite ajustar o extremo mais próximo, sem recomeçar a marcação inteira.
- Preserva o histórico, pareamento, vozes e preferências. Áreas continuam temporárias por sessão, como na versão 0.6.10.

Para marcar o chão: desenhe a área no passo 1; no passo 2, escolha um ponto à esquerda e outro à direita no encontro parede/piso. A orientação indica quando o limite está pronto para salvar.

Na comparação Release repetida em ordem inversa, o núcleo ficou **4,8–7,2 vezes mais rápido**, com resolução integral e os mesmos resultados comparados. Passaram 241 testes Android, 108 testes Swift, dois testes de segurança de memória e 10 testes dentro do Android 15 emulado. Esses tempos são do computador da CI.

No **iPhone 15 Pro físico, iOS 27.2**, a versão 0.6.11 instalada manteve aproximadamente **60 FPS com análise completa** em dois ensaios de 20 segundos, com médias de **4,32 e 4,53 ms por quadro**, em 1280 × 720. O teste anterior de 0.6.10 havia medido 43,54 ms e 19,52 FPS, com o mesmo número de pixels, mas outra cena e orientação. O histórico com 30 eventos foi preservado. Isso comprova a melhora de fluidez nos cenários medidos; não comprova precisão de quicadas físicas ou desempenho durante uma partida inteira.

Comparações controladas e limites constam no [relatório de validação](https://github.com/Laikan2/tennis-vision-ai/blob/main/docs/validation/mobile-0.6.11.md). O ganho do núcleo no computador de teste não equivale à taxa real da câmera do celular. A precisão em partidas e modos sustentados de 120/240 FPS continuam sem comprovação.

Android: Atualizações → Buscar atualização → Instalar. iPhone: AltStore → My Apps → Update, com a assinatura pessoal habitual.
