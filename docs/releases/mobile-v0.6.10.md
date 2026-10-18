# Tennis Vision mobile 0.6.10 — Android e iPhone

## Correções

- Bola de tênis melhor separada de piso amadeirado, com tratamento de costuras, mão, objetos verdes concorrentes e cores menos saturadas.
- Janela temporal de quicada ajustada para pequenos deslocamentos entre quadros, mantendo evidência observada antes e depois do contato, proteção contra mão e duplicatas.
- Modo CHÃO/PAREDE/RASTREAMENTO explícito; cancelar uma marcação informa qual modo foi mantido.
- Quadros atrasados não apagam a área ao reabrir o editor e não acrescentam contatos calculados em uma referência antiga.
- Marcas de contato com pulso curto e alinhamento à imagem. Preferências de som e visibilidade deixam de esconder indevidamente contatos anteriores.
- Etiquetas dos três impactos mais recentes procuram espaço sem se sobrepor; linhas discretas ligam o texto à marca original. Quando não cabe texto, o círculo e a revisão por toque continuam disponíveis.
- Prévia com proporção real e espaço para controles, sem esticar a imagem ou recortar a área.
- Android reutiliza buffers YUV e libera o quadro nativo antes da análise. iPhone evita uma fila extra de atualização da prévia.

## Mudança no funcionamento da área

**A área agora é temporária.** Nova sessão ou fechamento completo do aplicativo limpa chão, parede e quadra. Durante a mesma sessão, as áreas de chão e parede continuam separadas. Histórico, pareamento, vozes e preferências são preservados. Colocar o app em segundo plano mantém a sessão em memória.

Para testar no piso de casa: use **Marcar chão**, ajuste os quatro cantos, avance para marcar o encontro entre parede e chão e conclua os dois passos. Confira o indicador **CHÃO** antes de iniciar.

## Validação e limites

240 testes JVM/Robolectric aprovados. No pequeno conjunto de desenvolvimento retirado da gravação de tela, a bola na mão foi localizada corretamente em 7/7 amostras, contra 1/7 antes. Esse conjunto foi usado na correção e não representa precisão em partidas. A gravação pessoal não está incluída na publicação.

104 testes Swift, compilações para iPhone/simulador e recepção de 60 quadros H.264 aprovados. A tentativa de abrir a interface no simulador iOS excedeu o tempo limite; não houve validação visual em execução no iPhone nesta revisão.

Os testes instrumentados em Android 15 emulado passaram: vídeo controlado com IN/OUT/REVISAR, contorno, edição e descarte de áreas, histórico, permissões, segundo plano, bloqueio de tela e economia de bateria. O modo local foi exercitado sem rede ativa e com aplicativo de auditoria sem permissão de internet.

[Relatório completo e limites dos testes](https://github.com/Laikan2/tennis-vision-ai/blob/mobile/detection-session-0.6.10/docs/validation/mobile-0.6.10.md). Nenhum celular físico foi testado nesta revisão; FPS, temperatura, atraso e saques a 200 km/h continuam dependentes de teste real. Parede e casos ambíguos mantêm REVISAR. A velocidade é uma estimativa lateral, não uma medição por radar.

## Instalação

- Android: buscar atualização no aplicativo ou instalar o APK sobre a versão anterior.
- iPhone: atualizar pelo AltStore usando a fonte Tennis Vision. O IPA requer a assinatura pessoal do AltStore.
- A versão do Windows não é alterada por esta publicação.
