# Validação Windows 0.6.0 · 2 de outubro de 2026

- 19 testes do desktop passaram: atualização estável, download adiado durante sessão, bloqueio de reinício, consultas simultâneas, funcionamento sem internet, arquivo corrompido, encerramento dos serviços e integridade do release.
- TypeScript sem erros e compilação de produção do painel concluída.
- Motor PyInstaller reconstruído e instalador NSIS gerado com `app-update.yml`, `latest.yml` e blockmap.
- Programa empacotado abriu em estado isolado, motor conectado em 1,823 s, sem erro de inicialização, ponte nativa disponível e decodificação de vídeo habilitada.
- API confirmou armazenamento de credenciais pronto. APK Android 0.5.1 servido pelo programa tem o mesmo SHA-256 do APK original.
- EXE: 370.727.080 bytes; SHA-256 `6222473189a17af5c89e5c10e89380b4e4416810516ee82a51c5e345bcd3a1b5`.
- Os quatro arquivos enviados ao rascunho GitHub têm os tamanhos e hashes esperados.

O teste empacotado encontrou e corrigiu a leitura do campo `build` removido pelo empacotador. Agora o aplicativo usa o `app-update.yml` instalado; um teste de regressão cobre esse caso.

Publicação: repositório do código privado com o código enviado. Repositório de instaladores privado, release 0.6.0 em rascunho. A oferta pública da atualização aguarda a escolha de visibilidade do proprietário. O ciclo de download e instalação a partir de um feed público ainda depende dessa publicação; os testes de controle da instalação usam um atualizador simulado.

Esta validação trata do empacotamento e das atualizações. Não mede a precisão da bola ou os FPS de um celular físico.
