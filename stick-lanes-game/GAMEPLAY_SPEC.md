# Stick Lanes — Especificação de Gameplay do Protótipo

> Documento de referência do protótipo. Regras marcadas como **confirmadas** vieram das decisões já tomadas. Valores marcados como **provisórios** existem para permitir teste e balanceamento.

## 1. Identidade do jogo

- Visual original baseado em **stick figures estilizados**, sem copiar personagens, assets ou interface de outros jogos.
- Combate em tempo real com **3 lanes grandes e longas**.
- Cada exército escolhe **2 facções**.
- Cada batalha usa **8 unidades no loadout**.
- Não existe sistema explícito de sinergia por composição.
- Tempo máximo de geração de qualquer unidade: **30 segundos**.

## 2. Escala de atributos

A escala atual usa valores maiores apenas nos atributos de combate:

- **Vida:** valores antigos ×10.
- **Defesa:** valores antigos ×10.
- **Ataque:** valores antigos ×10.

Não são multiplicados:

- preço;
- velocidade de movimento;
- alcance;
- velocidade de ataque;
- tempo de geração;
- recargas;
- percentuais de habilidade.

## 3. Economia

### Confirmado

- Renda passiva: **+30 de ouro a cada 2 segundos**.
- Ao abater uma unidade inimiga, o jogador recebe **10% do custo original da unidade abatida**.
- Unidades criadas sem custo, como invocações gratuitas, não geram recompensa por abate.

Exemplos:

- unidade de custo 100 → recompensa 10;
- unidade de custo 390 → recompensa 39;
- unidade de custo 700 → recompensa 70.

## 4. Mapa e estruturas

Cada lane possui **3 estruturas defensivas** entre as duas bases.

Ordem a partir da base de cada jogador:

1. Torre traseira;
2. Torre central;
3. Torre avançada;
4. Base principal.

As estruturas não são mais simples marcações visuais: possuem corpo, barra de vida e podem ser atacadas normalmente.

### Vida inicial — provisória

| Estrutura | Vida |
|---|---:|
| Torre avançada | 600 |
| Torre central | 800 |
| Torre traseira | 1000 |
| Base principal | 3000 |

Esses valores são apenas baseline para teste.

### Regra de dano em estruturas

- Unidade não morre automaticamente ao alcançar a base inimiga.
- Unidade precisa **atacar fisicamente a estrutura** usando seu intervalo de ataque.
- A estrutura perde vida pelos golpes recebidos.
- Quando a estrutura chega a 0 de vida, ela é destruída.
- A unidade continua viva após destruir a estrutura e pode avançar para o próximo objetivo.
- Unidades de cerco podem receber modificadores específicos contra estruturas.

## 5. Ordens por lane

Os comandos antigos `Avançar/Ficar` serão substituídos por ordens de posicionamento mais úteis.

### Comandos

**Base**
- Unidades daquela lane recuam e se posicionam próximas à própria base.

**Atrás da torre**
- A formação procura a **torre aliada sobrevivente mais próxima** e ocupa uma posição atrás dela.

**Na torre**
- A formação ocupa a região da torre aliada sobrevivente mais próxima.

**À frente da torre**
- A formação ocupa uma posição adiante da torre aliada sobrevivente mais próxima.

**Avançar**
- Unidades avançam até encontrar inimigos ou uma estrutura inimiga.

### Torre de referência

- A ordem usa a torre aliada sobrevivente mais próxima da posição atual da formação.
- Se essa torre for destruída, o ponto de referência muda automaticamente para a próxima torre aliada sobrevivente.
- Se nenhuma torre sobreviver, a **base** vira a referência defensiva.

### Combate durante deslocamento

Uma ordem de posição não torna a unidade passiva.

Se encontrar um inimigo no caminho:

1. verifica prioridade de alvo;
2. entra em combate quando o alvo estiver no alcance;
3. depois do combate, volta a tentar cumprir a ordem da lane.

## 6. Prioridade básica de alvo

Prioridade padrão:

1. unidade inimiga válida dentro do alcance;
2. estrutura inimiga mais próxima;
3. base inimiga.

Categorias podem alterar a prioridade.

Exemplos:

- **Cerco:** prefere estruturas quando possível.
- **Assassino:** procura alvos frágeis/prioritários.
- **Suporte:** permanece próximo de aliados relevantes.
- **Controle:** pode preferir elites ou unidades de alto impacto para suas habilidades.

## 7. Categorias de unidade

As categorias são tags de função. Uma unidade pode ter mais de uma.

Categorias iniciais:

- Corpo a corpo
- À distância
- Tanque
- Suporte
- Controle
- Cerco
- Voador
- Invocador
- Assassino
- Elite
- Única
- Monstro
- Explosivo
- Perfurante

As tags ajudam IA, prioridade de alvo, interface e futuros efeitos de habilidade. Elas **não criam bônus de sinergia por composição**.

## 8. Categorias — Medievais

| Unidade | Categorias |
|---|---|
| Camponês | Corpo a corpo |
| Espadachim | Corpo a corpo |
| Escudeiro | Corpo a corpo, Tanque |
| Lanceiro | Corpo a corpo |
| Arqueiro | À distância |
| Besteiro | À distância, Perfurante |
| Cavaleiro | Corpo a corpo, Elite |
| Cavaleiro Pesado | Corpo a corpo, Tanque, Elite |
| Mercenário | Corpo a corpo, Assassino |
| Padre | Suporte, À distância |
| Inquisidor | Corpo a corpo, Elite |
| Caçador | À distância |
| Médico | Suporte |
| Carrasco | Corpo a corpo, Elite |
| Comandante | Corpo a corpo, Elite |
| Catapulta | Cerco, À distância |
| Balista | Cerco, À distância, Perfurante |
| Aríete | Cerco, Tanque |
| Nobre | Corpo a corpo, Elite |
| Rei | Única, Suporte, Elite |

## 9. Categorias — Alienígenas

| Unidade | Categorias |
|---|---|
| Gosma | Corpo a corpo, Monstro |
| Massa Pulsante | Tanque, Monstro |
| Trípode | Corpo a corpo, Monstro |
| Olho Flutuante | À distância, Voador, Monstro |
| Boca Ambulante | Corpo a corpo, Monstro |
| Parasita | Assassino, Controle, Monstro |
| Cuspidor | À distância, Monstro |
| Bolha Voadora | Voador, Explosivo, Monstro |
| Tentacular | Controle, Monstro |
| Cérebro Gigante | Suporte, Controle, Monstro |
| Casulo | Invocador, Monstro |
| Devorador | Corpo a corpo, Elite, Monstro |
| Translúcido | Assassino, Monstro |
| Aberração Gigante | Tanque, Elite, Monstro |

## 10. Categorias — Mentalistas

| Unidade | Categorias |
|---|---|
| Iniciado | À distância |
| Empurrador | Controle |
| Puxador | Controle |
| Paralisador | Controle |
| Confusor | Controle |
| Dominador | Controle, Elite |
| Ilusionista | Suporte, Controle |
| Escudeiro Psíquico | Tanque, Suporte |
| Levitador | Controle |
| Telepata | À distância, Perfurante |
| Drenador | Corpo a corpo |
| Oráculo | Suporte |
| Pesadelo | Controle |
| Projetor Astral | Suporte, Elite |
| Mente Coletiva | Suporte, Controle |
| Anulador | Controle, Elite |
| Implosor | À distância |
| Saltador Mental | Corpo a corpo, Assassino |
| Mestre Mentalista | Elite, Controle |
| Entidade Psíquica | Única, Controle, Elite |

## 11. Limites especiais já definidos

- **Rei:** máximo 1 por jogador.
- **Dominador:** máximo 2 por jogador.
- **Mente Coletiva:** máximo 2 por jogador.
- **Anulador:** máximo 1 por jogador.
- **Entidade Psíquica:** máximo 1 por jogador.
- Entidade Psíquica **não pode roubar outra Entidade Psíquica**.

## 12. Direção visual dos stick figures

Os personagens continuam reconhecíveis como stick figures, porém deixam de ser simples linhas coloridas sem personalidade.

Cada unidade deve ganhar, conforme o papel:

- silhueta própria;
- arma ou objeto claramente reconhecível;
- postura diferenciada;
- cabeça/rosto simples com identidade;
- pequenos efeitos visuais de habilidade;
- animação de caminhada;
- animação de ataque;
- reação ao receber dano;
- morte visual curta;
- barra de vida legível.

### Identidade visual por facção

**Medievais**
- metais, couro, madeira, dourado e tons terrosos;
- armas e armaduras definem rapidamente a função da unidade.

**Alienígenas**
- formas orgânicas e assimétricas;
- verde ácido, roxo, vermelho orgânico e efeitos viscosos;
- nem toda unidade precisa ter anatomia humana.

**Mentalistas**
- violeta/lilás como linguagem principal;
- transparências, halos, ondas, distorção e partículas psíquicas;
- algumas unidades podem flutuar ou ter postura antinatural.

## 13. Escala visual do mapa

O campo atual deve ser ampliado visualmente.

Objetivos:

- unidades maiores e mais fáceis de distinguir no celular;
- lanes mais altas;
- estruturas maiores;
- barras de vida das estruturas visíveis;
- mais espaço entre os pontos defensivos;
- leitura clara mesmo com várias unidades na mesma lane.

A câmera pode mostrar menos extensão horizontal de uma vez se isso for necessário para deixar unidades e estruturas legíveis, desde que o jogador consiga acompanhar a batalha de forma simples.

## 14. Ordem de implementação recomendada

1. Adicionar categorias/tags aos dados das unidades.
2. Criar estruturas reais com HP.
3. Remover o comportamento de suicídio ao alcançar a base.
4. Implementar ataque normal contra torres/base.
5. Substituir os comandos antigos pelo sistema de posição relativo às torres.
6. Aumentar o mapa e as unidades na tela.
7. Diferenciar visualmente os stick figures por facção/unidade.
8. Adicionar animações básicas.
9. Refinar prioridades de alvo por categoria.
10. Testar e recalibrar vida de torres/base.

## 15. Próximos pontos ainda não fechados

- dano ou ausência de dano próprio das torres;
- distância exata entre `atrás`, `na` e `à frente` da torre;
- HP final das estruturas;
- eventual armadura/defesa das estruturas;
- regras de unidades voadoras contra estruturas;
- prioridade de alvo detalhada para cada categoria;
- comportamento de formação quando muitas unidades tentarem ocupar o mesmo ponto.
