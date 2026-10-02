# Validação Windows 0.6.0 · 2 de outubro de 2026

- 19 testes do desktop passaram: atualização estável, download adiado durante sessão, bloqueio de reinício, consultas simultâneas, funcionamento sem internet, arquivo corrompido, encerramento dos serviços e integridade do release.
- TypeScript sem erros e compilação de produção do painel concluída.
- Motor PyInstaller reconstruído e instalador NSIS gerado com `app-update.yml`, `latest.yml` e blockmap.
- Programa empacotado abriu em estado isolado, motor conectado em 1,823 s, sem erro de inicialização, ponte nativa disponível e decodificação de vídeo habilitada.
- API confirmou armazenamento de credenciais pronto. APK Android 0.5.1 servido pelo programa tem o mesmo SHA-256 do APK original.
- EXE: 370.727.080 bytes; SHA-256 `6222473189a17af5c89e5c10e89380b4e4416810516ee82a51c5e345bcd3a1b5`.
- Os quatro arquivos enviados ao rascunho GitHub têm os tamanhos e hashes esperados.

O teste empacotado encontrou e corrigiu a leitura do campo `build` removido pelo empacotador. Agora o aplicativo usa o `app-update.yml` instalado; um teste de regressão cobre esse caso.

Publicação autorizada pelo proprietário e concluída: o repositório do código permanece privado; `Laikan2/tennis-vision-releases` está público com o release estável 0.6.0. Manifesto e instalador respondem sem autenticação.

Teste real com `electron-updater` sem tokens: a consulta da versão 0.6.0 retornou `current`. Uma instância de teste configurada com a versão anterior 0.5.0 detectou a versão nova, baixou os 370.727.080 bytes em cache isolado e retornou `downloaded`. O SHA-256 do arquivo baixado corresponde ao instalador original. As duas consultas e o download terminaram em 19,584 s. A instalação automática foi desativada nesse teste para preservar o programa instalado.

A revisão automática de execução bloqueou o comando que incluía executar a primeira instalação no PC, informando apenas `blocked by policy`. O programa instalado ainda está na versão 0.5.0. Para ativar o atualizador nesse PC, o usuário deve executar uma vez `Tennis-Vision-Studio-0.6.0-Windows-x64.exe`, já disponível em Downloads e no GitHub. As próximas versões são baixadas pelo programa e instaladas ao fechar. Os testes do controle de encerramento/instalação usam um atualizador simulado.

Esta validação trata do empacotamento e das atualizações. Não mede a precisão da bola ou os FPS de um celular físico.
