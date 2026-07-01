# Site estatico de historias seriadas

Primeira versao de um site simples para publicar historias gratuitamente em HTML, CSS e JavaScript puro.

## Estrutura

- `index.html`: pagina inicial.
- `sobre.html`, `contato.html`, `politica-de-privacidade.html`, `termos.html`: paginas institucionais.
- `obras/.../index.html`: paginas das obras.
- `capitulos/exemplo-capitulo.html`: modelo de capitulo.
- `assets/css/styles.css`: estilos principais.
- `assets/js/main.js`: menu mobile, ano do rodape e barra de progresso de leitura.
- `assets/img/`: imagens do site.
- `downloads/`: pasta reservada para PDFs futuros.
- `robots.txt` e `sitemap.xml`: arquivos basicos de SEO.

## Como editar uma obra

Abra o arquivo `obras/nome-da-obra/index.html` e edite:

- titulo;
- sinopse;
- status;
- classificacao e avisos de conteudo;
- lista de capitulos;
- link do botao "Comecar a ler".

Quando adicionar um PDF, coloque o arquivo em `downloads/` e troque o botao desativado por um link real.

## Como adicionar um capitulo

1. Copie `capitulos/exemplo-capitulo.html`.
2. Renomeie o arquivo, por exemplo `capitulos/a-hora-vermelha-01.html`.
3. Edite o `title`, a `meta description`, o titulo da obra, o titulo do capitulo e o texto.
4. Ajuste os botoes "Capitulo anterior", "Proximo capitulo" e "Voltar para a obra".
5. Adicione o novo link na lista de capitulos da pagina da obra.
6. Atualize `sitemap.xml` com a nova URL.

## Anuncios

Os blocos de anuncio sao apenas reservas visuais. Os comentarios HTML indicam onde o codigo do Google AdSense pode entrar no futuro.

Nao ha script de anuncio nesta versao.

## Aviso de conteudo adulto

O site usa avisos discretos para informar que as historias podem conter ficcao adulta, violencia, linguagem forte, uso de drogas, morte e temas sensiveis. Nao ha bloqueio pesado de idade.

## Publicacao no Cloudflare Pages

1. Suba esta pasta para um repositorio GitHub.
2. No Cloudflare Pages, crie um projeto conectado ao repositorio.
3. Configure:
   - Framework preset: `None`;
   - Build command: deixe em branco;
   - Build output directory: `/`.
4. Publique.

Se publicar apenas a pasta `site` dentro de um repositorio maior, configure o diretorio raiz do projeto para essa pasta ou mova estes arquivos para a raiz do repositorio.

## Antes de publicar

- Troque `https://seu-dominio.com.br` pelo dominio real em todos os metadados, `robots.txt` e `sitemap.xml`.
- Substitua os textos provisórios pelos capitulos reais.
- Revise as paginas legais conforme sua necessidade.
