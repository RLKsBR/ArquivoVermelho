# Painel local do Arquivo Vermelho

Este painel é uma ferramenta privada para manutenção do site. Ele roda no seu navegador e grava arquivos diretamente na pasta local do repositório.

## Como usar

1. Dê dois cliques em `abrir_painel.py`, na raiz do repositório.
2. Escolha a obra, informe número, título e selecione o PDF.
3. Clique em `Gerar prévia`.
4. Clique em `Aplicar no repositório`.
5. Revise o site local.
6. Faça commit e push.

O painel usa automaticamente a pasta do repositório onde `abrir_painel.py` está localizado. Não é preciso selecionar a pasta do site no navegador.

## O que ele atualiza ao adicionar PDF

- Copia o PDF para `downloads/...`.
- Cria uma página HTML em `capitulos/...`.
- Atualiza a página oficial da obra.
- Atualiza a seção `Últimas atualizações` da home.
- Atualiza a barra lateral desktop em `assets/js/main.js`.
- Atualiza `sitemap.xml`.
- Tenta liberar o botão `Próximo capítulo` no capítulo anterior.

## Substituir PDF

Use a ação `Substituir PDF existente` e informe o caminho relativo exato do arquivo, por exemplo:

```text
downloads/a-hora-vermelha/capitulo-05-me-destranca.pdf
```

O painel troca apenas o arquivo PDF. Ele não altera páginas HTML nessa ação.

## Observações

- O painel não publica no GitHub sozinho.
- O painel não usa login.
- O painel não expõe token.
- Abra o painel pelo `abrir_painel.py`; não abra `admin-local/index.html` diretamente pelo `file://`.
