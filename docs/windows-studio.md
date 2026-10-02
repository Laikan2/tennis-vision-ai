# Tennis Vision Studio · Windows 0.6.1

O instalador inclui o painel, Python, bibliotecas de visão, modelos, receptor MediaMTX e o aplicativo Android 0.5.2. No computador que recebe o instalador, não é necessário instalar Node ou Python para usar o programa.

A atualização Android **0.5.2** também é distribuída separadamente. Instale `Tennis-Vision-Camera-Android-v0.5.2.apk` sobre a versão anterior para manter o pareamento. As atualizações automáticas do Windows não instalam o APK no celular.

## Começar

1. Instale `Tennis-Vision-Studio-0.6.1-Windows-x64.exe` e abra **Tennis Vision Studio** pelo atalho.
2. Clique em **Conectar celular**. Baixe o aplicativo Android e instale no Galaxy.
3. Clique em **Preparar rede do PC** uma vez, se necessário. O Windows solicita autorização; a regra permite o receptor apenas na rede privada local. PC e celular devem estar na mesma rede.
4. No aplicativo Android, leia o QR, escolha a captura e toque em **Transmitir para o PC**.
5. No programa Windows, clique em **Adicionar ao painel** e **Nova sessão**. Selecione uma ou duas câmeras.

Para o iPhone, mantenha o transmissor compatível com RTSP/SRT já configurado. O APK é exclusivo do Android.

## Atualizações pelo GitHub

Se a instalação for anterior a 0.6.0, instale o novo 0.6.1 uma vez para ativar esse recurso. Depois, o programa consulta as versões estáveis publicadas no repositório de instaladores ao abrir e a cada quatro horas. Ele baixa a atualização automaticamente quando não há sessão ativa, verifica o SHA-512 e instala silenciosamente ao fechar o programa. Sem internet, você pode continuar usando a análise local.

Em **Configurações → Programa para Windows → Atualizações pelo GitHub**, acompanhe o download ou clique em **Buscar atualização**. Quando o arquivo estiver pronto, **Reiniciar para atualizar** instala e reabre; encerre a sessão antes de usar esse botão. Fechar normalmente instala sem reabrir.

O código fica em um repositório privado. O repositório público de distribuição recebe apenas os instaladores, o APK e os arquivos de atualização, com a publicação autorizada pelo proprietário. Câmeras, senhas, áreas, gravações e imagens pessoais ficam no PC. O atualizador não instala versões antigas nem versões de teste.

Para compilar e publicar uma nova versão, consulte [github-updates.md](github-updates.md).

## Áreas, impactos e velocidade

Cada câmera salva sua própria área. Selecione **Parede** para impactos na parede ou **Chão** para quicadas no piso. **Não contar impactos** mantém apenas o acompanhamento. **Mostrar impactos** altera as marcas visuais sem apagar as estatísticas. O **Som OUT** pode ser ligado e testado nos controles do vídeo.

Use **Arrastar parede** para desenhar um retângulo, **Marcar 4 cantos** para perspectiva ou o modelo da quadra no modo chão. **Imagem inteira** permite começar sem restringir a superfície. A região descreve onde interpretar um contato; atravessar a região no ar não comprova impacto. IN/OUT permanece uma estimativa, e candidatos incertos exigem revisão.

Selecione a bola de tênis de **67 mm**. A velocidade pelo diâmetro é aproximada; uma escala medida melhora a projeção. Uma câmera não comprova sozinha a velocidade física em três dimensões ou uma decisão oficial de linha.

## Seus dados

Em uma instalação nova, ficam em `%APPDATA%\TennisVisionStudio\data`; a chave das câmeras fica em `camera.key`, ao lado dessa pasta. Para transportar seus dados, preserve a pasta e a chave juntos. Desinstalar o programa não apaga esses dados.

Para continuar o projeto antigo, abra **Configurações → Usar dados de um projeto existente** e selecione a pasta `tennis-vision-ai`, que contém `backend`, `data` e `.env`. O programa usa essa pasta diretamente e preserva a chave original. Encerre sessões nos painéis que usam essa pasta antes de trocar ou abrir outra análise.

**Abrir dados** e **Registros** mostram as pastas locais. Quando o programa fecha, encerra sua análise e seu receptor; um receptor já aberto por outro programa continua funcionando.

## Fluidez e FPS

O painel é compilado para produção e o vídeo H.264 direto usa a decodificação disponível no sistema. A análise roda separadamente: uma inferência demorada não deve interromper o vídeo direto. **Alinhar vídeo à IA** exibe os frames analisados e pode reduzir a fluidez; use para conferir a posição exata de uma marca.

O receptor não impõe um limite artificial de 60 FPS. A taxa real depende da câmera, codificador, rede e PC. Com duas câmeras, o total de captura é a soma das duas; cada imagem também mostra sua taxa individual. A taxa de análise pode ser menor. A versão desktop não transforma uma captura de 60 FPS em 120/240 FPS nem garante detectar todas as bolas rápidas.

## Compilar a partir do código

No Windows, instale as dependências do projeto com `INSTALAR.bat`, depois execute `powershell -File scripts/build-desktop.ps1`. O painel web na porta 3000 deve estar encerrado durante a compilação. Os instaladores saem em `desktop/releases`.

O pacote inclui pesos e o modelo de aparência já treinado. Vídeos pessoais, imagens de treino, credenciais e perfis de câmeras não entram no instalador. O treinamento local existente continua disponível em **Treinar IA**.

O instalador desta versão não tem certificado comercial de assinatura de código; o Windows pode mostrar o editor como desconhecido.
