# Site estático de histórias seriadas

Site simples para publicar histórias gratuitamente em HTML, CSS e JavaScript puro.

## Estrutura

- `index.html`: pagina inicial.
- `sobre.html`, `contato.html`, `politica-de-privacidade.html`, `termos.html`: paginas institucionais.
- `obras/.../index.html`: paginas das obras.
- `capitulos/exemplo-capitulo.html`: modelo de capítulo.
- `capitulos/*.html`: páginas de leitura/download que apontam para PDFs.
- `assets/css/styles.css`: estilos principais.
- `assets/js/main.js`: menu mobile, ano do rodape e barra de progresso de leitura.
- `assets/img/`: imagens do site.
- `downloads/`: PDFs publicados, separados por obra.
- `robots.txt` e `sitemap.xml`: arquivos básicos de SEO.

## PDFs publicados

Use estes caminhos para os arquivos reais:

- `downloads/a-hora-vermelha/capitulo-01-gaia.pdf`
- `downloads/cronicas/cronica-01-a-orbe-de-uriel.pdf`
- `downloads/o-ultimo-dia/capitulo-01-dias-de-treinamento.pdf`

Ao adicionar novos PDFs, crie uma subpasta com o slug da obra e use nomes curtos, sem espaços, em minúsculas e com hífens.

## Como editar uma obra

Abra o arquivo `obras/nome-da-obra/index.html` e edite:

- titulo;
- sinopse;
- status;
- classificação e avisos de conteúdo;
- lista de capítulos;
- link do botão principal de leitura;
- link do botão de download em PDF.

Quando adicionar um PDF, coloque o arquivo em `downloads/nome-da-obra/` e troque o botão desativado por um link real.

## Painel local para PDFs

Existe um painel local em `admin-local/index.html` para acelerar a publicação de PDFs.

Ele roda no navegador, pede acesso à pasta local do repositório e pode:

- adicionar um PDF novo;
- substituir um PDF existente;
- criar a página HTML do capítulo;
- atualizar a página oficial da obra;
- atualizar a seção "Últimas atualizações" da home;
- atualizar a barra lateral desktop;
- atualizar `sitemap.xml`;
- tentar liberar o botão "Próximo capítulo" no capítulo anterior.

O painel não publica no GitHub sozinho. Depois de usar, revise o site localmente e faça commit/push.

### Abrir sem PowerShell

Com Python instalado, dê dois cliques em `abrir_painel.py`, na raiz do repositório. Ele abre uma pequena janela, inicia o servidor local e abre o painel no navegador automaticamente. Mantenha essa janela aberta enquanto usar o painel; fechá-la encerra o servidor local. O painel já sabe qual é a pasta do site, então não pede para selecionar diretório no navegador.

## Como adicionar um capitulo

1. Copie uma página existente em `capitulos/`, por exemplo `capitulos/a-hora-vermelha-capitulo-01.html`.
2. Renomeie o arquivo, por exemplo `capitulos/a-hora-vermelha-capitulo-02.html`.
3. Coloque o PDF em `downloads/nome-da-obra/`.
4. Edite o `title`, a `meta description`, o título da obra, o título do capítulo/crônica e os avisos.
5. Atualize os botões "Abrir PDF" e "Baixar PDF" para apontarem para o novo PDF.
6. Ajuste os botões "Voltar para a obra" e "Próximo capítulo" ou "Próxima crônica".
7. Adicione o novo link na lista de capítulos da página da obra.
8. Atualize a seção "Últimas atualizações" em `index.html`, se for uma publicação nova.
9. Atualize `sitemap.xml` com a nova URL.

## Como publicar uma nova obra ou capítulo

1. Crie ou edite a página da obra em `obras/nome-da-obra/index.html`.
2. Crie a página de leitura/download em `capitulos/`.
3. Coloque o PDF em `downloads/nome-da-obra/`.
4. Faça os links relativos funcionarem entre obra, capítulo e PDF.
5. Atualize `index.html` se a publicação deve aparecer na página inicial.
6. Atualize `sitemap.xml`.

## Como verificar antes do deploy

- Abra `index.html` no navegador e navegue pelos cards.
- Clique nos botões "Ler", "Abrir PDF" e "Baixar PDF".
- Verifique se nenhum link aponta para Netlify diretamente.
- Verifique se todos os caminhos são relativos.
- Procure por acentos quebrados visualmente antes de publicar.

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
