# Atualizações do Tennis Vision Studio

## Para quem usa o programa

A versão Windows 0.6.0 inclui o atualizador. A instalação inicial substitui o programa anterior e preserva `%APPDATA%\TennisVisionStudio`, incluindo a configuração do projeto existente. Futuras versões são consultadas no GitHub, baixadas automaticamente quando a sessão termina e instaladas ao fechar. O menu **Ajuda → Buscar atualização** e o painel de Configurações permitem conferir o estado.

Publicar código no GitHub não muda o programa instalado: é preciso compilar e publicar uma versão com número maior. Apenas releases estáveis completos são oferecidos. Sem internet, o estúdio permanece funcional. O Android continua com instalação própria de APK.

## Repositórios

- Código privado: `Laikan2/tennis-vision-ai`.
- Feed público ativo: `Laikan2/tennis-vision-releases`. Os instaladores estáveis podem ser baixados sem conta GitHub.
- Não enviar `.env`, chaves, bancos de dados, configurações das câmeras, gravações, imagens de treino nem diretórios de trabalho. O `.gitignore` os exclui. `release-assets/models` contém apenas pesos redistribuíveis e o classificador numérico de aparência.

O consumidor do feed público não precisa de conta GitHub. Um feed privado exige autenticação própria; tokens de quem publica nunca devem entrar no EXE.

## Publicar uma nova versão pelo PC de desenvolvimento

1. Altere `version` em `desktop/package.json` para um número estável maior, por exemplo `0.6.1`.
2. Instale as dependências do projeto. Use o GitHub CLI autenticado com acesso de escrita ao repositório de instaladores; confira `gh auth status`.
3. Execute `powershell -File scripts/publish-windows-release.ps1` na raiz. O script compila a IA, o painel e o instalador, executa os testes do desktop e confere versão, SHA-512, tamanho, blockmap e repositório gravado no pacote.
4. O script cria um rascunho, envia o EXE, `.blockmap`, `latest.yml` e o APK e confere os tamanhos antes de publicar. Os programas instalados passam a receber a nova versão automaticamente.
5. Salve o código correspondente no repositório privado. Não substitua um instalador já publicado com outro conteúdo; publique uma versão maior.

`-SkipBuild` usa uma compilação já verificada. `-KeepDraft` envia os arquivos sem oferecer a versão aos usuários; depois de revisar, publique o rascunho no GitHub. Uma falha no envio deixa o rascunho sem publicação.

O comando usa a autenticação local do GitHub CLI. Não guarda uma chave do GitHub no aplicativo ou no repositório. Este fluxo de publicação roda no PC de desenvolvimento; não depende de um token de conta salvo em GitHub Actions.

## Proteção dos dados e verificação

O NSIS mantém o mesmo `appId` e o diretório de dados. O motor recebe o comando de encerramento e salva/fecha os serviços antes da saída. Atualizações não substituem o diretório de gravações ou a chave das câmeras. O receptor compartilhado com outro processo permanece aberto.

O download incremental usa `.blockmap` e pode recorrer ao download completo. A integridade do EXE é conferida com o SHA-512 de `latest.yml`; um arquivo inválido não é instalado. Se o projeto receber um certificado de assinatura de código no futuro, configure o mesmo editor nas versões para habilitar a validação da assinatura. A versão atual usa a proteção HTTPS do GitHub e o hash do manifesto, sem certificado comercial.

Validação: `pnpm test` em `desktop`, `pnpm exec tsc --noEmit` em `frontend`, compilação completa e abertura do programa empacotado. Falhas de rede ficam no estado do atualizador e não bloqueiam a câmera.
