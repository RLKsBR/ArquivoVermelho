# Voice Controller TFT para Android

Protótipo de acessibilidade por voz para controlar o TFT com o serviço de Acessibilidade do Android.

## v0.16

- reinterpretação conservadora de erros comuns do reconhecimento de voz, sem remover a validação dos gestos;
- comandos “pegar orbes” e “coletar orbes” procuram brilhos compactos na área de jogo e visitam apenas candidatos visuais de alta confiança;
- anúncio automático “Estágio X, rodada X”, sem repetir a mesma rodada;
- detecção de aprimoramentos, bigornas/arsenal e escolhas de componentes, com leitura automática das opções;
- durante a leitura de escolhas, “parar” interrompe a voz e “leia X” repete apenas a opção pedida;
- alerta sonoro quando o contador detectado chega a dez segundos; na ausência de contador legível, usa uma janela conservadora de trinta segundos;
- carrossel lido em três imagens sucessivas, informando textos encontrados e direções aproximadas no relógio;
- posições táticas por voz: “linha de frente”, “segunda linha de frente”, “terceira linha” e “retaguarda”, combinadas com “esquerda”, “meio” ou “direita”;
- comando “auto calibrar” reconhece loja, rolar, XP e regiões contextuais visíveis; tabuleiro e banco continuam guiados para evitar coordenadas perigosas.

O tabuleiro do TFT tem sete colunas (A–G) e quatro fileiras. Os nomes táticos são calculados pela posição visual, portanto continuam corretos mesmo se a numeração usada na calibração estiver invertida.

## v0.15

- catálogo offline com as 36 combinações dos oito componentes básicos;
- comandos “quais componentes fazem...”, “o que faz com...” e “quais itens existem”;
- a calibração principal termina após os 16 pontos que realmente estão disponíveis no início;
- detector contextual tenta salvar automaticamente as regiões quando surgem telas de aprimoramento, arsenal, inventário ou sinergias;
- comandos experimentais “ler tabuleiro”, “ler inventário” e “ler carrossel”;
- base factual para registrar sinergias ativas e consultar campeões registrados fora delas;
- nenhum caminho, compra, venda, escolha de item, aprimoramento ou campeão é decidido automaticamente;
- identificação de campeões e itens apenas por ícones ainda depende de amostras reais das telas e não é anunciada como pronta.

## v0.14

- comando “ler aviso” e variações como “por que não pegou”;
- interpretação de mensagens de reserva, banco ou espaço de itens cheio;
- cadastro factual provisório de campeões por voz;
- consultas de maior vida máxima com/sem itens, maior valor e frontline/backline;
- empates são falados por completo;
- nenhuma escolha, venda, item ou posicionamento é decidido automaticamente.

## v0.13

- leitura por voz com OCR local: loja, itens, sinergias, escolhas e tela inteira;
- resposta falada pelo mecanismo de voz do Android;
- comando para repetir a última leitura;
- último texto reconhecido visível dentro do app;
- opção de salvar recortes usados pelo OCR em Pictures/Voice Controller/TFT Captures/OCR Samples;
- modelo latino incluído no APK para funcionar sem baixar o OCR durante a partida.

## v0.12

- calibração guiada: 16 pontos principais são salvos juntos; itens, sinergias e escolhas são marcados quando essas telas aparecem;
- botões Voltar um passo e Cancelar durante a marcação;
- coordenadas persistidas com resolução e orientação, bloqueando gestos quando a tela mudou;
- comandos de jogo bloqueados quando o TFT não está em primeiro plano;
- screenshot convertida em imagem, analisada e salva em Pictures/Voice Controller/TFT Captures;
- miniatura da última captura dentro do app e notificações para passos e erros;
- correção de barras do sistema na tela principal.

A branch de desenvolvimento é voice-controller-android. O GitHub Actions publica um APK de teste a cada alteração no app.
