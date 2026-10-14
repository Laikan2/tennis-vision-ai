# Tennis Vision Android — testes no S25 Ultra, 11/10/2026

## Estado da validação

Os testes foram executados no **Samsung S25 Ultra, modelo SM-S938B, Android 16 / API 36**. O aplicativo pessoal foi atualizado de 0.5.4 para **0.6.1 / código 14** com `adb install -r`, sem desinstalação nem limpeza dos dados. A assinatura é a mesma e a primeira instalação continua registrada em 06/10/2026. Operações que alteram sessões usam o pacote isolado **Tennis Vision Testes**; exemplos sintéticos não foram inseridos no aplicativo pessoal.

**Conferência final concluída por depuração sem fio:** o APK otimizado com a última correção passou pela rodada de câmera/interface de 60 segundos. Nenhum novo contato foi registrado na cena apontada para o teto. Os pacotes auxiliares de teste foram removidos, e o ajuste temporário da tela foi restaurado. Isso encerra a pendência de conexão; a validação com bolas físicas rápidas em uma partida permanece em aberto.

O ensaio antes/depois abaixo foi obtido na JVM do computador; a seção do S25 identifica os testes já executados no aparelho físico. A auditoria anterior com emulador permanece separada em [android-offline-audit-2026-10-11.md](android-offline-audit-2026-10-11.md).

## Problemas reproduzidos e corrigidos

1. **Contato marcado antes do ponto mais baixo observado.** Uma sequência rápida podia satisfazer a mudança de direção ainda durante a descida. Agora o quadro central deve ser um máximo local observado. A posição e o instante do contato são estimados pela interseção dos trechos observados de aproximação e saída, dentro do intervalo entre esses quadros. Ajustes inconsistentes exigem revisão.
2. **OUT decidido com quadro do contato ausente.** A lacuna temporal agora eleva a incerteza e exige revisão, sem anunciar OUT como certo. A imagem de evidência é identificada como o quadro observado mais próximo do contato estimado.
3. **Conversão da câmera consumia tempo demais.** As consultas nativas a propriedades dos planos YUV estavam dentro do laço de pixels. Agora acontecem uma vez por quadro, e os cálculos de cor são compartilhados pelos quatro pixels correspondentes de cada bloco YUV. Resolução e cores foram preservadas. Foram testados preenchimento/intercalação, bordas, dimensões ímpares e quatro rotações.
4. **Candidato acima do piso poluiu a contagem.** No ensaio com a câmera apontada para o teto, um componente de raio 1,5 pixel gerou REVISAR em `(0,0322; 0,0723)`, acima do limite do piso em `y=0,28`. Não disparou OUT, mas entrou na sessão. Agora uma trajetória claramente acima do piso, incluindo sua margem de incerteza, não gera contato. Pontos junto à divisa continuam revisáveis; contatos no piso fora do polígono válido continuam sendo OUT.

Isso não comprova contato físico apenas por uma mudança de direção. A marcação do piso, a oclusão, a perspectiva e objetos parecidos com a bola continuam limitando a decisão.

## Funcionamento local e decisões

O Android executa identificação da bola verde por cor, forma e movimento, rastro, contatos estimados, som, revisão e persistência. Não é o modelo neural Python do Windows nem um modelo recém-treinado. A velocidade é uma estimativa lateral pela dimensão aparente da bola de 67 mm; não foi comparada com radar ou referência 3D.

**IN** significa marca estimada dentro ou tocando a área; **OUT**, marca inteiramente fora considerando a incerteza, com áudio se habilitado. **REVISAR** indica evidência insuficiente e não anuncia OUT automaticamente. Previsões breves do rastro não contam contatos.

O **limite do piso** separa o chão do restante da imagem; a **área IN/OUT** define onde um contato no chão é válido. São funções diferentes. O detector continua procurando a bola fora do polígono IN para encontrar OUT. Marque a divisa com o chão abaixo dela e mantenha o celular fixo. A imagem de referência pausa durante a edição para permitir ajuste dos cantos.

## Ensaio controlado

O gerador independente usa imagens de 1280 × 720, posições conhecidas, diâmetros de 3, 6 e 12 pixels e deslocamentos de até 80 pixels por quadro. São **19 cenários sintéticos**, incluindo borrão alongado, contato entre quadros, oclusão, OUT, passagem no ar e entrada de 30/60 quadros por segundo. Os FPS são timestamps de entrada, não captura de câmera.

- Bolas sem borrão: nove combinações de tamanho/deslocamento, todas com 12/12 posições observáveis encontradas e um contato IN. Não é uma taxa de acerto em partidas.
- Contato entre quadros: erros anteriores de 12,5, 25,3 e 38,1 pixels passaram a 0,5 pixel nos três casos conhecidos.
- Contato OUT com um quadro ausente: antes havia OUT com erro de 43,5 pixels; agora há REVISAR e erro de 0,5 pixel neste cenário.
- Passagem reta pelo ar: nenhum contato contado.
- Borrão de proporção 2:1 e 4:1: acompanhado neste ensaio. **Borrão 6:1: nenhuma das 12 posições encontrada.** Esse limite não foi escondido nem corrigido relaxando indiscriminadamente o filtro de forma.
- A decisão usa quadros posteriores ao contato. Nos três casos entre quadros, o tempo de evidência foi de 25 a 37,5 ms na linha temporal sintética de 60 FPS. Isso **não mede atraso completo da câmera até a tela**.

Regressão final: **119 testes JVM/Robolectric aprovados, zero falhas e zero ignorados**, incluindo contatos entre quadros, revisão de lacunas, conversão/rotação de pixels e exclusão acima do piso. Os casos cobrem também falsos positivos conhecidos, armazenamento e atualização. [Contagem final](validation/android-fast-ball-2026-10-11/unit-tests-final.json).

Evidências: [antes](validation/android-fast-ball-2026-10-11/fast-ball-before.csv) e [depois](validation/android-fast-ball-2026-10-11/fast-ball-after.csv). Tempos de CPU nesses CSVs são do computador, não do S25.

## Preparação para o aparelho

Compilados `app-audit.apk` e `app-audit-androidTest.apk`. A aplicação isolada chama-se **Tennis Vision Testes**, pacote `ai.tennisvision.camera.audit`. Não substitui a aplicação pessoal e usa dados próprios. Seu manifesto não tem permissão INTERNET, permitindo verificar o fluxo local sem alterar a conectividade pessoal do telefone. O provedor de arquivos usa o identificador do pacote para evitar conflito com o app principal.

Os testes preparados incluem operações de área, cancelamento, toques rápidos, retomada, persistência, vídeo H.264 conhecido, trajetórias rápidas e dois perfis de câmera com medição de quadros recebidos, intervalos e tempo de conversão/análise. Apenas os testes relacionados a seguir foram executados no aparelho. Os modos de vídeo 120/240 FPS anunciados pelo fabricante não significam suporte à análise local nessa taxa.

## S25 físico: resultados já obtidos

**Nove verificações JUnit distintas aprovadas no aparelho**, além do teste independente do APK com R8 descrito abaixo. Repetições não foram somadas como novos cenários:

1. Os 19 cenários sintéticos foram processados no S25, com as mesmas decisões e erros de localização medidos na JVM. As medianas por cenário ficaram entre 9,44 e 9,61 ms em 1280 × 720. São tempos apenas do detector em uma compilação de auditoria, com pixels gerados; não incluem a lente, captura, conversão YUV, apresentação nem garantem 60 FPS. O limite de borrão 6:1 permaneceu.
2. Escrita atômica no armazenamento Android: atualização, recuperação de arquivo interrompido e preservação da sessão anterior aprovadas.
3. Compartilhamento do APK por URI: arquivo autorizado legível e acesso a arquivos privados fora do caminho permitido rejeitado.
4. Permissão negada duas vezes: o app não mostra câmera pronta e oferece acesso aos ajustes do Android.
5. Permissão concedida pelo diálogo do Android: a câmera real entrega imagens.
6. Área e piso por toques/arraste, salvamento, cancelamento, área inválida, dez toques rápidos em iniciar/pausar, segundo plano e recriação da tela: aprovados, sem perda da configuração.
7. Vídeo H.264 sintético de 191 quadros decodificado por MediaCodec no S25: sequência IN, OUT, REVISAR confirmada nos eventos, na sessão salva e na interface. Uma reprodução de OUT aceita por SoundPool; audibilidade não confirmada por escuta. Mediana do detector 1,32 ms e P95 2,01 ms em 320 × 240.
8. Câmera real em perfis de 60 e 30 FPS, com análise e apresentação no aplicativo. A primeira medição falhou; as repetições após a correção passaram pelo critério de recepção de quadros. Isso não prova taxa sustentada de 60 FPS nem atraso óptico completo.
9. Exclusão acima do piso: as dez posições visíveis da trajetória sintética foram localizadas, **zero contatos acima da divisa** e **OUT preservado na trajetória sobre o piso fora do polígono**. [Resultado](validation/android-fast-ball-2026-10-11/floor-boundary.json).

O vídeo conhecido e os testes de área, dez toques rápidos, cancelamento e recriação foram repetidos após a otimização. O vídeo produziu novamente um IN, um OUT e um REVISAR, sem duplicação nessa sequência; mediana do detector 1,35 ms e P95 2,93 ms. [Resultado](validation/android-fast-ball-2026-10-11/yuv-block-pipeline.json). Os testes de exclusão acima do piso e os 19 cenários rápidos foram repetidos após a última correção.

**Primeira tentativa bloqueada:** o teste de negar a permissão não alcançou o diálogo porque a tela segura do S25 estava bloqueada. Após o desbloqueio manual, as duas verificações de permissão passaram. A tentativa inicial não conta como aprovação.

### Gargalo confirmado na câmera real

O primeiro teste de estabilidade **falhou por taxa insuficiente de quadros**. Com 1280 × 720, análise ligada e apresentação no app, foram medidos dois intervalos úteis de cerca de 12 segundos:

- Perfil solicitado de 60 FPS: 177 quadros, **14,75 FPS recebidos**; conversão + detector com mediana 52,63 ms e P95 122,74 ms; 33 intervalos acima de 100 ms.
- Perfil solicitado de 30 FPS: 101 quadros, **8,38 FPS recebidos**; conversão + detector com mediana 121,05 ms e P95 129,63 ms; 55 intervalos acima de 100 ms.

Não houve erro de abertura da câmera nem contato candidato contado nesse trecho. Isso não torna a fluidez aceitável. O monitor térmico do Android informou status 0 após o ensaio; a temperatura de bateria amostrada durante o teste foi 34 °C, sem inferir daí comportamento térmico em treinos longos.

Após retirar as consultas nativas do laço de pixels, os mesmos perfis de auditoria receberam **48,37 FPS** (582 quadros) e **28,17 FPS** (337 quadros), respectivamente. As medianas de conversão + análise foram 23,22 e 27,75 ms; P95 26,38 e 44,80 ms. Não houve intervalo acima de 100 ms; os maiores foram 33,51 e 66,64 ms. [Dados depois](validation/android-fast-ball-2026-10-11/camera-after.json).

Após compartilhar os cálculos YUV entre pixels, a rodada de auditoria com o telefone já aquecido recebeu **31,79 FPS** no perfil de 60 e **30,01 FPS** no perfil de 30. Novamente nenhum intervalo acima de 100 ms; máximos de 50,26 e 33,32 ms. [Dados dessa rodada](validation/android-fast-ball-2026-10-11/camera-final.json).

No APK com a otimização de distribuição R8, antes da última exclusão acima do piso, os indicadores da tela foram amostrados uma vez por segundo durante 60 segundos: **41–59 FPS**, mediana **42 FPS**, média **45,6 FPS**. Câmera, iniciar/pausar, área/cancelar, guia, som, histórico, revisão manual e recuperação após encerramento do processo passaram. [Execução](validation/android-fast-ball-2026-10-11/release-final.json).

Na repetição final **após a exclusão acima do piso**, realizada por depuração sem fio, os 60 indicadores recebidos ficaram em **47–60 FPS**, mediana **48 FPS** e média **51,4 FPS**. Câmera, iniciar/pausar, área/cancelamento, guia, configuração de som, histórico, revisão e encerramento/reabertura passaram. A contagem permaneceu exatamente em 5 IN, 3 OUT e 2 REVISAR antes/depois da câmera: eram dez eventos anteriores da sessão isolada de teste, **nenhum novo contato nesse minuto**. Não são dez impactos físicos observados nesta rodada. A bateria estava a 34,7 °C antes e 36,1 °C depois; status térmico 0 nas duas amostras. [Resultado final](validation/android-fast-ball-2026-10-11/release-floor-final.json), [execução](validation/android-fast-ball-2026-10-11/release-floor-final.log).

As rodadas não são um ensaio térmico A/B de laboratório: ocorreram sequencialmente, com compilações de auditoria/distribuição e aquecimento distintos. A bateria passou de aproximadamente 34 °C para 41,7 °C em amostras da rodada, e o estado térmico informado pelo Android passou de 0 para 2. Isso não mede a temperatura exata do processador. **60 FPS sustentados não foram comprovados.** Não se deve atribuir a diferença entre duas rodadas exclusivamente a uma otimização quando as condições mudaram.

Os indicadores da tela são janelas móveis arredondadas, não um histograma de intervalos. FPS recebido não mede necessariamente todos os quadros efetivamente apresentados na tela. O tempo de processamento não inclui toda a exposição do sensor e o atraso óptico completo.

As características lidas da câmera traseira anunciam YUV 1280 × 720 com duração mínima de 16,67 ms e faixas regulares até 60 FPS. Também anunciam vídeo de alta velocidade 120/240 FPS, mas esse é outro tipo de sessão/saída e não foi validado para este fluxo de análise local.

Evidências: [metadados do S25](validation/android-fast-ball-2026-10-11/device.json), [trajetórias processadas no S25](validation/android-fast-ball-2026-10-11/fast-ball-device.csv), [execução do detector](validation/android-fast-ball-2026-10-11/fast-ball-device.log) e [armazenamento/compartilhamento](validation/android-fast-ball-2026-10-11/storage-sharing-device.log).

## APK, independência do PC e preservação

[Tennis-Vision-Camera-Android-v0.6.1.apk](../mobile/releases/Tennis-Vision-Camera-Android-v0.6.1.apk), 3.208.256 bytes, instalado no aplicativo principal. SHA-256: `16abe840d93f56314cad745b0edfa72febb3f03cb0f04f4b7d631d1839333f5f`. Certificado SHA-256 verificado e igual ao da versão pessoal anterior: `faf843630ad0a52fef6ca4f844cae592e3da1e266b7b823f481c34d3b2049ce9`.

**Seis testes do backend/empacotamento passaram** após atualizar a referência para esse APK. [Resultado](validation/android-fast-ball-2026-10-11/backend-apk-tests.log). O Windows já instalado não foi recompilado nesta rodada.

A versão Android 0.6.1 foi publicada em [android-v0.6.1](https://github.com/Laikan2/tennis-vision-releases/releases/tag/android-v0.6.1) para download e compartilhamento pelo celular. O download público sem autenticação retornou HTTP 200, 3.208.256 bytes e o mesmo SHA-256 do APK testado. A referência de última versão do Windows permaneceu em v0.6.9. A atualização Android consulta os arquivos publicados por versão do APK e pode encontrar esta versão. [Verificação do download](validation/android-fast-ball-2026-10-11/github-download.json).

A variante de auditoria não possui permissão INTERNET: câmera, processamento, som e persistência funcionaram sem possibilidade de consultar Windows ou nuvem. O PC permaneceu ligado para comandar e registrar os testes por USB; não foi feito um ensaio físico com o PC desligado. GitHub é necessário somente para obter atualizações. Transmissão para o PC é opcional.

As imagens reais e capturas do telefone ficaram locais, fora da documentação pública. O teste independente confirmou persistência após encerramento e reabertura do processo. Dados pessoais, pareamento e gravações não foram apagados.

### Encerramento no aplicativo principal

A câmera foi aberta no pacote pessoal `ai.tennisvision.camera`, versão 0.6.1. A área entrou em edição, exibindo explicitamente **imagem de referência pausada**, e o botão Voltar cancelou a edição, exibindo **área anterior mantida**. A configuração não foi salva nem substituída durante essa conferência; os contadores pessoais continuaram em zero. Uma tentativa de ler a árvore de interface com `uiautomator dump` não obteve estado ocioso; o XML anterior não foi aceito como resultado da edição. A abertura e o cancelamento foram conferidos em capturas atuais do telefone.

Os três pacotes auxiliares (`.audit`, `.audit.test` e `auditprobe`) foram desinstalados com sucesso. A listagem final manteve somente o aplicativo pessoal Tennis Vision dentro desses pacotes, com sua primeira instalação preservada. O app foi deixado em segundo plano ao terminar, e `stay_on_while_plugged_in` foi novamente lido como **0**. [Registro de encerramento](validation/android-fast-ball-2026-10-11/device-cleanup.json).

A conexão foi recuperada por depuração sem fio com pareamento autorizado pelo usuário. [Registro da restauração da tela](validation/android-fast-ball-2026-10-11/screen-setting-restored.json). A interface USB ADB permaneceu ausente na última enumeração do Windows; nenhum driver foi removido ou substituído.

## Limites e pendências

- As tentativas anteriores interrompidas por movimento amplo e perda de ADB continuam registradas como incompletas. A rodada final por Wi-Fi concluiu a conferência da câmera/interface, mas não comprova estabilidade em treinos longos nem resolve a causa da ausência da interface USB.
- Medir câmera e processamento em treinos longos, sob sol, com foco e iluminação variados. Borrão 6:1 ainda falha no ensaio controlado.
- Testar bolas físicas rápidas com vídeo e impactos anotados independentemente. Apontar o celular para o teto serve para testar câmera, interface e estabilidade; não demonstra precisão numa partida.
- Não foi validada análise local a 120/240 FPS nem velocidade contra radar. Deslocamento em pixels por quadro não determina, sozinho, velocidade em km/h numa quadra.
- Economia de bateria, chamadas telefônicas, bloqueio/retomada com PIN e consumo sustentado precisam de ensaio físico específico. O teste automatizado de bloqueio/economia sem PIN permanece comprovado somente no emulador da rodada anterior.
- Não houve nova avaliação física do iPhone, transmissão SRT de alta velocidade, fusão de duas câmeras ou modelos neurais do Windows nesta rodada. O detector local continua sujeito a falsos positivos e contatos perdidos; não equivale a arbitragem oficial.
