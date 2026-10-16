# Tennis Vision mobile 0.6.9

O contorno da bola agora aparece na prévia local, antes de iniciar o treino. A prévia usa análise de até 640 pixels no lado maior sobre o próprio quadro mostrado, sem registrar impactos, velocidade ou alterar as estatísticas. Iniciar treino usa a resolução de análise selecionada e ativa a contagem. A redução para prévia não transforma uma bola de poucos pixels em observação confiável.

O contorno circular/elíptico acompanha as dimensões observadas da bola, inclusive um borrão alongado, com borda escura para contraste. Verde indica observação resolvida e laranja indica sinal incerto ou parcial; a previsão continua identificada separadamente.

O modo Parede passa a aceitar retornos verticais e diagonais além dos horizontais. A posição candidata usa o encontro temporal dos trechos observados antes/depois da mudança, limitado aos quadros ao redor dela. Retornos sem trajetória coerente e desaparecimentos sem retorno observado não geram essa marca. A posição e a velocidade lateral continuam estimadas; contatos de parede exigem revisão, pois uma câmera não comprova a profundidade nem distingue todos os retornos de mão/raquete.

Mantém Marcar chão/parede, áreas separadas, histórico, pareamentos, certificado Android e vozes ElevenLabs offline. Não houve treinamento ou substituição de um modelo neural nesta entrega. Testes de pixels e vídeos simulados não comprovam precisão em partidas reais.

## Validação e instalação

85 testes XCTest do motor Swift passaram, com compilação Release para iPhone arm64, compilação para simulador e integração que recebeu/decodificou 60 quadros H.264. O IPA foi conferido quanto a versão 0.6.9/build10, plataforma iPhoneOS, contrato de recursos e arquivos IN/OUT ElevenLabs. No Android, 210 testes JUnit/Robolectric e quatro cenários no emulador Android 15 passaram; 15 testes dos publicadores e da fonte AltStore passaram.

Não houve teste desta versão em iPhone ou Android físico nesta rodada. A captura automática da tela do simulador excedeu o limite de dois minutos; a compilação do simulador passou, mas a abertura visual ficou sem confirmação. Os testes de detecção e contatos usam situações sintéticas; não comprovam precisão em partidas, contato físico com a parede ou velocidade 3D. Contatos incertos permanecem para revisão.

A fonte AltStore existente recebe a 0.6.9. No iPhone, atualize a lista de Sources/My Apps e use Update no Tennis Vision Camera, com o AltServer disponível para a assinatura pessoal. Não é necessário apagar a versão instalada. No Android, use Ajustes → Atualizações. O Android e o AltStore exigem confirmação da instalação. A versão Windows latest permanece v0.6.11.
