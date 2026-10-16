# Tennis Vision Camera Android 0.6.9

A bola de tênis verde passa a ter contorno também na prévia, antes de iniciar o treino. O contorno circular ou elíptico acompanha as dimensões observadas, com uma borda escura para contraste. Verde indica uma observação resolvida; laranja indica sinal incerto ou parcial. As posições previstas continuam identificadas separadamente.

No modo Parede, a análise aceita retornos verticais e diagonais além dos horizontais. A marca candidata usa a trajetória observada antes e depois da mudança de direção para estimar o instante e a posição do contato entre quadros. Não registra impacto só porque a bola desapareceu. A marca mostra PAREDE · REVISAR e a velocidade lateral estimada quando há evidência suficiente.

Para testar: Marcar parede → ajustar a área → Salvar → Iniciar treino. A prévia procura a bola sem alterar as estatísticas; iniciar o treino ativa a contagem. Áreas, histórico, pareamentos, assinatura Android e vozes ElevenLabs offline foram preservados.

## Validação

210 testes JUnit/Robolectric, quatro cenários de integração em emulador Android 15/API 35 e 15 testes dos publicadores/feed passaram. Os três casos sintéticos que falhavam antes da correção — retornos vertical, diagonal e posição de retorno horizontal — passaram. Os testes de posição horizontal/diagonal exigem erro de até 2 pixels na imagem simulada. A conferência visual verificou o contorno em volta da bola exibida e três marcas de parede no vídeo sintético, sem som OUT indevido. O APK final teve versão, assinatura e arquivos de áudio conferidos, e foi instalado e aberto no emulador.

Não houve teste desta versão em câmera física, treinamento neural ou comprovação de precisão em partidas. Uma câmera não comprova todos os contatos em profundidade; marcas de parede exigem revisão e velocidades são estimativas laterais. A prévia usa até 640 pixels no lado maior para reduzir o custo, sem transformar bolas de poucos pixels em observações confiáveis. Durante o treino vale a resolução de análise selecionada.

O código equivalente do iPhone está preparado, mas esta publicação contém apenas o Android: o GitHub bloqueou o início da compilação macOS por cobrança/limite de gastos. O aplicativo e a fonte AltStore anteriores permanecem disponíveis.

Atualize em Ajustes → Atualizações → Buscar atualização → Instalar atualização. Instale sobre a versão anterior, sem desinstalar, e confirme a instalação solicitada pelo Android.

APK SHA-256: b34f38a49e5c3aab90ae5a39ad0b8fafca4bf69afbf52c5ca0547e425ce65c1d
