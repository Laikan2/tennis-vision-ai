# Quicadas no piso e reprodução — 18/09/2026

## Evidência

Foi inspecionado o vídeo `2026-09-18 13-01-11.mp4` (45 s, captura de tela a
60 fps), além de 428 imagens originais salvas nos replays da sessão. A área
selecionada não cortava essas imagens: a análise já percorria o quadro inteiro.

As quicadas perdidas próximas de 13:01:38–40 continham observações reais da bola.
O descarte acontecia quando a bola atravessava a margem do retângulo da pessoa,
um punho perdia a leitura da pose, ou o rastreador trocava de ID no retorno rápido.

Antes do reinício, uma amostra de 40 consultas mostrou mediana de 60 fps recebidos,
11,1 fps analisados e 60,8 ms de inferência, com pico de 221,7 ms. Uma leitura
separada do RTSP também encontrou chegadas em rajadas, com intervalo máximo de
172 ms, apesar de os tempos de apresentação dos quadros avançarem regularmente.
Esse teste não separa o atraso do telefone, da rede e do decodificador, nem mede
a latência absoluta da câmera.

## Correções

- A bola fora do corpo pode continuar como evidência mesmo com um punho perdido
  recentemente. A última posição desse punho funciona apenas como uma região
  de exclusão ampliada. Bola junto à mão e pose insuficiente continuam excluídas.
- A troca de ID mantém a sequência de quicada somente com duas observações
  fortes, móveis, de tamanho compatível e próximas no tempo. A decisão continua
  exigindo descida, região de piso e retorno medidos.
- Um possível contato além da imagem fica para revisão, sem decidir IN/OUT no
  último ponto visto no ar.
- Tempos de apresentação fornecidos pelo vídeo agora acompanham cada observação
  e são usados no cálculo da velocidade e da geometria de quicada. Horários de
  recepção continuam sendo usados para eventos e replays. Quando esses tempos
  não existem, há fallback; relógios de conexões diferentes não são misturados.
- A segmentação reaproveita a conversão de cor e descarta contornos de tamanho
  inviável antes dos cálculos de forma. Matrizes pequenas deixam de abrir vários
  trabalhadores numéricos por padrão.
- A cópia usada pela visão passa a ter 640 px de largura por padrão, sem reduzir
  o vídeo exibido. No mesmo replay, manteve os 23 candidatos a impacto e reduziu
  a mediana do detector de 23,36 ms para 13,82 ms (p95 de 35,13 para 20,32 ms).
  Houve 325 quadros com medição contra 336 a 768 px; por isso a busca focal em
  resolução original continua ativa depois da primeira aquisição.
- Abas ocultas deixam de iniciar o carregamento JPEG quando desligam a reprodução
  direta. Também deixam de redesenhar o painel e consultar listas em segundo plano.
  Essa era uma fonte concreta de trabalho redundante com várias abas abertas.

A resolução do vídeo exibido e a taxa enviada pelo telefone não foram reduzidas.

## Verificação

- 156 testes Python passaram, incluindo 10 regressões novas com observações do
  vídeo, exclusão das mãos, mudança de ID, tempos em rajada e contato fora da imagem.
- A checagem TypeScript passou.
- No mesmo conjunto de 428 quadros, o reprocessamento passou de 17 para 23
  candidatos a impacto. Cinco eventos recuperados estão no intervalo do vídeo
  enviado; outro está no trecho anterior retido no replay. As imagens das cinco
  sequências foram conferidas. Isso não é uma medição de precisão de 100%.
- A contagem em reprocessamento não substitui nem altera a sessão gravada.
- O servidor foi reiniciado, a sessão anterior foi salva e uma nova sessão foi
  aberta com a mesma câmera e área. A saúde da API e o acesso pelo site responderam.

O telefone interrompeu a transmissão antes do reinício. Assim, ainda falta uma
medição ao vivo da fluidez depois das mudanças. As durações do teste offline não
devem ser tratadas como FPS de reprodução. A câmera volta a conectar
automaticamente quando o Larix retoma o envio.

## Reprodução dos testes

Na raiz do projeto:

```powershell
.\.venv\Scripts\python.exe -m pytest backend/tests -q
.\.venv\Scripts\python.exe scripts/evaluate_evening_ground.py --folder data/diagnostics/ground-0918-1301 --name verificacao
.\.venv\Scripts\python.exe scripts/measure_live_runtime.py --output data/diagnostics/ground-0918-1301/runtime-after.json
```

Os resultados originais e finais, imagens de conferência e a amostra do servidor
estão em `data/diagnostics/ground-0918-1301`. Os tempos de apresentação são relativos
ao vídeo, não horários de captura sincronizados entre câmeras; a API continua
sem afirmar que conhece a latência absoluta da rede.
