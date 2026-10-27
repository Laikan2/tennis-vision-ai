# Tennis Vision mobile 0.6.19

Melhorias na identificação e na exibição dos contatos no Android e no iPhone.

- Retornos com ruído na posição usam uma janela de observações antes/depois, reduzindo perdas em quicadas com subida mais lenta.
- Janela de observações compatível com 20 FPS analisados, mantendo limites de custo e evidência antes/depois.
- Movimento em profundidade pode gerar uma marca para revisão quando há evidência coerente na posição e no tamanho da bola. Não dispara OUT automaticamente.
- No iPhone, uma nova quicada não apaga antecipadamente as marcas anteriores. A duração escolhida continua valendo.
- Contatos calculados fora da imagem não entram na contagem nem viram círculos invisíveis: o aplicativo pede para recuar a câmera.
- O modo chão explica o enquadramento lateral necessário para observar a descida e a subida.

Os testes sintéticos conhecidos melhoraram de 16/18 para 18/18 casos de quicada com ruído. Isso não é uma medição de precisão em partidas reais. Quicadas com posição incerta, falhas de observação ou movimento em profundidade continuam exigindo revisão; a velocidade continua sendo uma estimativa lateral quando há suporte suficiente.

O APK mantém a assinatura anterior. O IPA deve ser instalado sobre o aplicativo existente, com a mesma conta de assinatura no iLoader/AltStore. Não desinstale o aplicativo para atualizar.

Validação final: 298 testes JVM aprovados (3 opcionais ignorados), 164 testes Swift, 11 cenários de uso/permissão no Android 15 emulado e 23 testes de empacotamento. A instalação física desta versão ficou pendente após a desconexão USB do iPhone.
