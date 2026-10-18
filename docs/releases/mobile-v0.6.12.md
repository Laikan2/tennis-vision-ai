# Tennis Vision mobile 0.6.12 — evidência da bola antes do impacto

Corrige a aquisição de falsos alvos em manchas amareladas do piso. Movimento de um fragmento de cor, por si só, não inicia mais o acompanhamento como bola verde. Sinais fracos só continuam por até 100 ms perto da posição esperada de uma trajetória que já tinha evidência de cor.

Um contato, inclusive REVISAR, exige observações com evidência de cor antes e depois da mudança de direção. Posições com cor incerta não geram uma velocidade válida. A correção vale para chão, parede e prévia, em Android e iPhone.

Mantém a otimização de análise completa da 0.6.11 e o editor de chão em dois passos. Histórico, pareamento e áudios são preservados. Áreas continuam temporárias por sessão; após atualizar, marque o chão ou a parede novamente.

Validação: 247 testes Android JVM/Robolectric, 114 testes Swift, 10 testes em Android 15 emulado, dois testes de segurança de memória e 21 testes Python aprovados. Os sete recortes revisados de bola na mão mantiveram localização dentro das tolerâncias fixadas anteriormente. A tentativa opcional de abrir a interface no simulador iOS excedeu o tempo e não comprova funcionamento no aparelho. O controle negativo da nova versão no iPhone físico permanece separado dos testes sintéticos e de compilação.

[Relatório de validação e limites](https://github.com/Laikan2/tennis-vision-ai/blob/main/docs/validation/mobile-0.6.12.md).

Uma câmera e cor isolada não garantem identidade do objeto, contato físico ou arbitragem. O filtro mais conservador pode deixar de emitir um contato quando a cor da bola desaparece dos dois lados da trajetória. Isso não comprova precisão em partidas nem captura sustentada em 120/240 FPS.

Android: Atualizações → Buscar atualização → Instalar. iPhone: AltStore → My Apps → Update, com a assinatura pessoal habitual.
