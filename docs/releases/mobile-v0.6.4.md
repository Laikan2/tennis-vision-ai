# Tennis Vision mobile 0.6.4 — Android e iPhone

O iPhone acompanha os recursos do Android 0.6.4: treino local sem Windows, detector da bola verde pequena/distante, mão/oclusão, rastro observado e previsão breve identificada, contatos candidatos, IN/OUT com revisão, som OUT, velocidade lateral estimada, áreas, histórico e exportação.

O Android deste release é exatamente o APK 0.6.4 já publicado, com o mesmo hash e certificado. O iPhone passa de 0.3.0 para 0.6.4 para representar a mesma entrega de funcionalidades; os números de build permanecem próprios de cada plataforma.

## O que foi completado no iPhone

- Lupa ao arrastar os cantos, editor ampliado em tela cheia e arraste que chega aos limites da imagem.
- Toque nas marcas para revisão, com marca no quadro de evidência.
- Área e marcas mantêm a compensação da câmera ao pausar/editar; salvar usa a referência congelada correta.
- Orientações de enquadramento e acesso a 1080p para bola distante.
- Contagens e cálculo de acerto alinhados ao Android; distinção entre quadra e área livre/teste de quarto.
- Taxas 120/240 só quando anunciadas pela lente/resolução, com taxa recebida medida. A escolha manual não é substituída silenciosamente. O padrão Até 60 FPS continua disponível.
- Tela própria de atualizações, aviso de nova versão e botão para abrir o importador do AltStore com o IPA oficial do GitHub.
- A câmera só fica pronta após uma imagem real. Conceder permissão enquanto o app está em segundo plano não inicia uma captura escondida.

## Atualizações coordenadas

A entrega comum tem APK e IPA separados. Um contrato de funcionalidades e 41 cenários sintéticos comuns aos motores Kotlin e Swift ajudam a impedir diferenças de comportamento. O publicador exige versões iguais, integridade, assinatura Android anterior, build nativo aprovado e IPA igual ao artefato do CI. A fonte do AltStore só é atualizada após conferir os downloads públicos. O latest do Windows permanece em 0.6.9.

No Android: **Atualizações → Buscar atualização → Instalar atualização**. No iPhone atualizado: **Atualizações → Buscar atualização → Instalar atualização pelo AltStore**. Para sair da versão antiga, use a fonte oficial no AltStore. A instalação/assinatura do iOS exige confirmação e pode precisar do AltServer; o treino local não depende de computador. Não desinstale o aplicativo anterior para atualizar.

## Limites de validação

Os cenários comuns usam pixels e posições conhecidos, não uma partida filmada. Compilação, testes de núcleo e screenshot do simulador não provam a câmera física nem os gestos no aparelho. Esta entrega ainda precisa de conferência física no iPhone conectado, incluindo instalação, câmera, edição por toque, som audível, 120/240 FPS sustentados e quicadas reais.

Não houve treinamento de uma rede neural nesta etapa. Uma câmera única não garante contato real, arbitragem junto à linha, cobertura dos dois lados ou detecção de saques a 200 km/h. FPS e desempenho variam conforme aparelho, luz e enquadramento; velocidade continua lateral e estimada. Posições/contatos incertos exigem revisão.
