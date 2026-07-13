const WORKS = {
  "a-hora-vermelha": {
    label: "A Hora Vermelha",
    meta: "A Hora Vermelha",
    unit: "Capítulo",
    pagePrefix: "a-hora-vermelha-capitulo",
    pdfPrefix: "capitulo",
    downloads: ["downloads", "a-hora-vermelha"],
    workPage: ["obras", "a-hora-vermelha", "index.html"],
    workHref: "obras/a-hora-vermelha/",
    cover: "capa-a-hora-vermelha.png",
    notice: "Conteúdo adulto: violência, linguagem forte, drogas, morte, temas adultos e apocalipse.",
    updateVerb: "já pode ser lido ou baixado em PDF.",
    ratingPrefix: "a-hora-vermelha-capitulo"
  },
  cronicas: {
    label: "Crônicas",
    meta: "Crônicas",
    unit: "Crônica",
    useRoman: true,
    pagePrefix: "cronicas-cronica",
    pdfPrefix: "cronica",
    downloads: ["downloads", "cronicas"],
    workPage: ["obras", "cronicas", "index.html"],
    workHref: "obras/cronicas/",
    cover: "capa-cronicas.png",
    notice: "Conteúdo adulto/fantasia sombria: violência, guerra, trauma, abuso de poder, linguagem forte e temas adultos.",
    updateVerb: "está publicada em PDF.",
    ratingPrefix: "cronicas-cronica"
  },
  "checkpoint-zumbi": {
    label: "Checkpoint Zumbi",
    meta: "Checkpoint Zumbi",
    unit: "Capítulo",
    pagePrefix: "checkpoint-zumbi-capitulo",
    pdfPrefix: "capitulo",
    downloads: ["downloads", "checkpoint-zumbi"],
    workPage: ["obras", "checkpoint-zumbi", "index.html"],
    workHref: "obras/checkpoint-zumbi/",
    cover: "capa-checkpoint-zumbi.png",
    notice: "Conteúdo adulto: violência intensa, morte, linguagem forte, corpos e situações de sobrevivência.",
    updateVerb: "já está disponível em PDF.",
    ratingPrefix: "checkpoint-zumbi-capitulo"
  },
  "o-ultimo-dia": {
    label: "O Último Dia",
    meta: "O Último Dia",
    unit: "Capítulo",
    workPage: ["obras", "o-ultimo-dia", "index.html"],
    workHref: "obras/o-ultimo-dia/",
    cover: "capa-o-ultimo-dia.png",
    notice: "Conteúdo adulto: violência intensa, drogas, armas, morte, exploração humana, jogos mortais e sofrimento psicológico.",
    updateVerb: "já está disponível em PDF.",
    versions: {
      original: {
        label: "Original multilíngue",
        pagePrefix: "o-ultimo-dia-capitulo",
        pdfPrefix: "capitulo",
        downloads: ["downloads", "o-ultimo-dia"],
        ratingPrefix: "o-ultimo-dia-capitulo",
        description: "Publicação em PDF gratuito na versão original preservada."
      },
      portugues: {
        label: "100% português",
        pagePrefix: "o-ultimo-dia-portugues-capitulo",
        pdfPrefix: "capitulo",
        downloads: ["downloads", "o-ultimo-dia", "portugues"],
        ratingPrefix: "o-ultimo-dia-portugues-capitulo",
        description: "Publicação em PDF gratuito na versão 100% português."
      }
    }
  }
};

const state = {
  preview: null,
  publishToken: null
};

const $ = (selector) => document.querySelector(selector);
const form = $("[data-admin-form]");
const statusBox = $("[data-status]");
const logList = $("[data-log]");
const previewBox = $("[data-preview-box]");
const workSelect = $("[data-work]");
const versionRow = $("[data-version-row]");
const versionSelect = $("[data-version]");
const modeSelect = $("[data-mode]");
const addFields = $("[data-add-fields]");
const replaceFields = $("[data-replace-fields]");
const addOptions = $("[data-add-options]");
const tabButtons = document.querySelectorAll("[data-panel-tab]");
const panelViews = document.querySelectorAll("[data-admin-view]");
const analyticsSummary = $("[data-analytics-summary]");
const analyticsRecent = $("[data-analytics-recent]");

function log(message, type = "ok") {
  const item = document.createElement("li");
  item.className = type;
  item.innerHTML = message;
  logList.prepend(item);
}

function setStatus(title, detail) {
  statusBox.innerHTML = `<strong>${escapeHtml(title)}</strong><span>${escapeHtml(detail)}</span>`;
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

function slugify(value) {
  return String(value)
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .replace(/-{2,}/g, "-");
}

function pad2(number) {
  return String(number).padStart(2, "0");
}

function roman(number) {
  const table = [
    [1000, "M"], [900, "CM"], [500, "D"], [400, "CD"], [100, "C"],
    [90, "XC"], [50, "L"], [40, "XL"], [10, "X"], [9, "IX"],
    [5, "V"], [4, "IV"], [1, "I"]
  ];
  let n = Number(number);
  let result = "";
  for (const [value, label] of table) {
    while (n >= value) {
      result += label;
      n -= value;
    }
  }
  return result;
}

function pathJoin(parts) {
  return parts.filter(Boolean).join("/");
}

async function readText(path) {
  const response = await fetch(`/api/read?path=${encodeURIComponent(path)}`);
  if (!response.ok) {
    throw new Error(await response.text());
  }
  return response.text();
}

function escapeRegex(value) {
  return String(value).replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function normalizeTitle(value, config, numberLabel) {
  const original = String(value).trim();
  const prefix = new RegExp(`^${escapeRegex(config.unit)}\\s+${escapeRegex(numberLabel)}(?:\\s*[:.\\-—–]\\s*|\\s+)`, "i");
  const normalized = original.replace(prefix, "").trim();
  return normalized || original;
}

function formatBytes(bytes) {
  if (bytes < 1024 * 1024) {
    return `${Math.round(bytes / 1024)} KB`;
  }
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
}

function formatDate(value) {
  return new Intl.DateTimeFormat("pt-BR", {
    dateStyle: "short",
    timeStyle: "short"
  }).format(new Date(value));
}

async function loadAnalytics() {
  analyticsSummary.innerHTML = "<p>Carregando resumo do catálogo...</p>";
  analyticsRecent.innerHTML = "";

  try {
    const response = await fetch("/api/analytics");
    if (!response.ok) {
      throw new Error(await response.text());
    }
    const data = await response.json();
    const totals = [
      { value: data.total_files, label: "PDFs publicados" },
      { value: formatBytes(data.total_bytes), label: "Espaço ocupado" },
      { value: data.groups.reduce((count, group) => count + Number(group.files > 0), 0), label: "Catálogos ativos" }
    ];
    const groupCards = data.groups
      .filter((group) => group.files > 0)
      .map((group) => ({ value: `${group.files} PDF${group.files === 1 ? "" : "s"}`, label: `${group.label} · ${formatBytes(group.bytes)}` }));

    analyticsSummary.innerHTML = [...totals, ...groupCards]
      .map((card) => `<article class="analytics-card"><strong>${escapeHtml(card.value)}</strong><span>${escapeHtml(card.label)}</span></article>`)
      .join("");

    analyticsRecent.innerHTML = `<div class="analytics-list">${data.recent.map((file) => `
      <article>
        <div><strong>${escapeHtml(file.name)}</strong><span>${escapeHtml(file.path)}</span></div>
        <span>${escapeHtml(formatBytes(file.bytes))}<br>${escapeHtml(formatDate(file.updated))}</span>
      </article>
    `).join("")}</div>`;
  } catch (error) {
    analyticsSummary.innerHTML = `<p class="error">${escapeHtml(error.message)}</p>`;
  }
}

function selectPanelTab(name) {
  tabButtons.forEach((button) => {
    const active = button.dataset.panelTab === name;
    button.classList.toggle("is-active", active);
    button.setAttribute("aria-selected", String(active));
  });
  panelViews.forEach((view) => {
    view.hidden = view.dataset.adminView !== name;
  });
  if (name === "analytics") {
    loadAnalytics();
  }
}

async function writeText(path, text) {
  const response = await fetch("/api/write-text", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ path, text })
  });
  if (!response.ok) {
    throw new Error(await response.text());
  }
}

async function writeBinary(path, file) {
  const content = await new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result).split(",", 2)[1]);
    reader.onerror = () => reject(new Error("Nao foi possivel ler o PDF selecionado."));
    reader.readAsDataURL(file);
  });
  const response = await fetch("/api/write-binary", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ path, content })
  });
  if (!response.ok) {
    throw new Error(await response.text());
  }
}

async function publishToGithub() {
  if (!state.publishToken) {
    const configResponse = await fetch("/api/config");
    if (!configResponse.ok) {
      throw new Error("Não consegui preparar a publicação no GitHub.");
    }
    const config = await configResponse.json();
    state.publishToken = config.publishToken;
  }

  const response = await fetch("/api/publish", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Panel-Token": state.publishToken
    },
    body: "{}"
  });
  const result = await response.json();

  if (!response.ok || !result.ok) {
    throw new Error(result.error || "Não foi possível publicar no GitHub.");
  }

  return result;
}

function selectedWorkConfig() {
  const work = WORKS[workSelect.value];
  if (!work) {
    throw new Error("Obra inválida.");
  }

  if (!work.versions) {
    return { work, version: null, config: work };
  }

  const versionKey = versionSelect.value;
  const version = work.versions[versionKey];
  if (!version) {
    throw new Error("Versão inválida.");
  }

  const { label: versionLabel, ...versionConfig } = version;

  return {
    work,
    version: versionKey,
    config: { ...work, ...versionConfig, versionKey, versionLabel }
  };
}

function buildPublicationData() {
  const data = new FormData(form);
  const mode = data.get("mode");
  const pdf = data.get("pdf");
  const { work, config } = selectedWorkConfig();

  if (!pdf || !pdf.name) {
    throw new Error("Selecione um arquivo PDF.");
  }

  if (mode === "replace") {
    const replacePath = String(data.get("replacePath") || "").trim().replaceAll("\\", "/");
    if (!replacePath || !replacePath.endsWith(".pdf")) {
      throw new Error("Informe o caminho relativo do PDF existente.");
    }

    return {
      mode,
      pdf,
      replacePath,
      files: [{ path: replacePath, label: "PDF substituído" }]
    };
  }

  const number = Number(data.get("number"));
  const rawTitle = String(data.get("title") || "").trim();
  const summary = String(data.get("summary") || "").trim() || "Publicação em PDF gratuito para leitura no Arquivo Vermelho.";

  if (!Number.isInteger(number) || number < 1) {
    throw new Error("Informe um número de capítulo válido.");
  }

  if (!rawTitle) {
    throw new Error("Informe o título.");
  }

  const numberLabel = config.useRoman ? roman(number) : String(number);
  const title = normalizeTitle(rawTitle, config, numberLabel);
  const fullTitle = `${config.unit} ${numberLabel}: ${title}`;
  const slug = slugify(title);
  const fileSlug = `${config.pagePrefix}-${pad2(number)}-${slug}`;
  const htmlPath = `capitulos/${fileSlug}.html`;
  const pdfPath = pathJoin([...config.downloads, `${config.pdfPrefix}-${pad2(number)}-${slug}.pdf`]);
  const ratingId = `${config.ratingPrefix}-${pad2(number)}-${slug}`;

  return {
    mode,
    pdf,
    work,
    config,
    number,
    numberLabel,
    title,
    fullTitle,
    summary,
    slug,
    htmlPath,
    pdfPath,
    ratingId,
    files: [
      { path: pdfPath, label: "PDF" },
      { path: htmlPath, label: "Página HTML" }
    ],
    options: {
      updateWorkPage: Boolean(data.get("updateWorkPage")),
      updateHome: Boolean(data.get("updateHome")),
      updateSidebar: Boolean(data.get("updateSidebar")),
      updateSitemap: Boolean(data.get("updateSitemap")),
      updatePrevious: Boolean(data.get("updatePrevious"))
    }
  };
}

function renderPreview(data) {
  if (data.mode === "replace") {
    previewBox.innerHTML = `
      <p>O painel vai substituir o arquivo:</p>
      <ul><li><code>${escapeHtml(data.replacePath)}</code></li></ul>
    `;
    return;
  }

  previewBox.innerHTML = `
    <p><strong>${escapeHtml(data.config.label)}</strong> · ${escapeHtml(data.fullTitle)}</p>
    <ul>
      ${data.files.map((file) => `<li>${escapeHtml(file.label)}: <code>${escapeHtml(file.path)}</code></li>`).join("")}
      <li>Página da obra: <code>${escapeHtml(pathJoin(data.config.workPage))}</code></li>
      <li>Home: ${data.options.updateHome ? "atualiza Últimas atualizações" : "não altera"}</li>
      <li>Sidebar: ${data.options.updateSidebar ? "atualiza navegação desktop" : "não altera"}</li>
      <li>Sitemap: ${data.options.updateSitemap ? "adiciona URL nova" : "não altera"}</li>
    </ul>
  `;
}

function chapterPageTemplate(data, previous, next) {
  const rootPrefix = "../";
  const pdfHref = `${rootPrefix}${data.pdfPath}`;
  const backHref = `${rootPrefix}${data.config.workHref}`;
  const usesFeminineUnit = data.config.unit === "Crônica";
  const nextLabel = `${usesFeminineUnit ? "Próxima" : "Próximo"} ${data.config.unit.toLowerCase()}`;
  const ratingArticle = usesFeminineUnit ? "esta" : "este";
  const ratingPreposition = usesFeminineUnit ? "da" : "do";
  const prevControl = previous
    ? `<a class="button chapter-button chapter-button-previous" href="${rootPrefix}${previous.href}">${escapeHtml(data.config.unit)} anterior</a>`
    : `<span class="button chapter-button chapter-button-previous disabled">${escapeHtml(data.config.unit)} anterior</span>`;
  const nextControl = next
    ? `<a class="button chapter-button chapter-button-next" href="${rootPrefix}${next.href}">${nextLabel}</a>`
    : `<span class="button chapter-button chapter-button-next disabled">${nextLabel} em breve</span>`;
  const versionTitle = data.config.versionLabel ? ` | ${data.config.versionLabel}` : "";

  return `<!doctype html>
<html lang="pt-BR">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <script defer src="../assets/js/analytics.js?v=20260710"></script>
  <title>${escapeHtml(data.config.label)}${escapeHtml(versionTitle)} | ${escapeHtml(data.fullTitle)}</title>
  <meta name="description" content="${escapeHtml(data.fullTitle)} disponível em PDF gratuito no Arquivo Vermelho.">
  <meta property="og:title" content="${escapeHtml(data.config.label)} | ${escapeHtml(data.fullTitle)}">
  <meta property="og:description" content="${escapeHtml(data.fullTitle)} disponível em PDF gratuito no Arquivo Vermelho.">
  <meta property="og:type" content="article">
  <meta property="og:url" content="https://rlksbr.github.io/ArquivoVermelho/${escapeHtml(data.htmlPath)}">
  <meta property="og:image" content="https://rlksbr.github.io/ArquivoVermelho/assets/img/logo-arquivo-vermelho.png">
  <link rel="icon" href="../assets/img/logo-arquivo-vermelho.png" type="image/png">
  <link rel="stylesheet" href="../assets/css/styles.css?v=20260713-fullscreen">
</head>
<body class="reader-page">
  <header class="site-header">
    <div class="header-inner">
      <a class="brand" href="../index.html" aria-label="Arquivo Vermelho"><img class="brand-logo" src="../assets/img/logo-arquivo-vermelho.png" alt="Arquivo Vermelho" width="56" height="56"><span>Arquivo Vermelho</span></a>
      <button class="menu-toggle" type="button" data-menu-toggle aria-expanded="false" aria-controls="site-nav">Menu</button>
      <nav class="site-nav" id="site-nav" data-site-nav aria-label="Menu principal">
        <a href="../index.html">Início</a>
        <a href="../sobre.html">Sobre</a>
        <a href="../apoie.html">Apoie</a>
        <a href="../contato.html">Contato</a>
        <a href="../fale-conosco.html">Mensagem anônima</a>
        <a href="../politica-de-privacidade.html">Privacidade</a>
        <a class="mobile-app-link" href="../downloads/app/arquivo-vermelho-1.7.3.apk" download>Baixar app</a>
      </nav>
    </div>
  </header>
  <main>
    <article class="reader">
      <div class="reader-header">
        <p class="eyebrow">${escapeHtml(data.config.label)}</p>
        <h1>${escapeHtml(data.fullTitle)}</h1>
        <p class="lead">${escapeHtml(data.summary)}</p>
        <p class="notice">${escapeHtml(data.config.notice)}</p>
        <div class="actions">
          <a class="button" href="#leitor-pdf">Ler PDF aqui</a>
          <a class="button secondary" href="${pdfHref}" download>Baixar PDF</a>
          <a class="button secondary" href="${backHref}">Voltar para a obra</a>
        </div>
      </div>
      <aside class="reader-cover-panel" aria-label="Arte da obra">
        <img src="../assets/img/${escapeHtml(data.config.cover)}" alt="Capa de ${escapeHtml(data.config.label)}" loading="lazy">
        <span>${escapeHtml(data.config.label)}</span>
      </aside>
      <div class="reader-body">
        <p>O PDF pode ser lido abaixo, dentro desta página. Se o leitor não carregar no seu navegador, use o botão de download.</p>
      </div>
      <section class="pdf-reader-panel" id="leitor-pdf" aria-label="Leitor de PDF">
        <div class="pdf-reader-header">
          <h2>Leitor de PDF</h2>
          <a class="button secondary" href="${pdfHref}" download>Baixar PDF</a>
        </div>
        <div class="pdf-viewer" data-pdf-viewer data-pdf-src="${pdfHref}">
          <div class="pdf-toolbar" aria-label="Navegação do PDF">
            <button class="button secondary" type="button" data-pdf-previous>Página anterior</button>
            <span class="pdf-page-count">Página <span data-pdf-current>1</span> de <span data-pdf-total>...</span></span>
            <button class="button secondary" type="button" data-pdf-next>Próxima página</button>
            <nav class="chapter-nav reader-inline-nav" aria-label="Navegação entre capítulos">
              ${prevControl}
              <a class="button chapter-button chapter-button-back" href="${backHref}">Voltar para a obra</a>
              ${nextControl}
            </nav>
          </div>
          <p class="pdf-status" data-pdf-status>Carregando PDF...</p>
          <div class="pdf-canvas-wrap">
            <canvas class="pdf-canvas" data-pdf-canvas></canvas>
          </div>
        </div>
      </section>
      <section class="chapter-rating" data-chapter-rating="${escapeHtml(data.ratingId)}" aria-labelledby="rating-${escapeHtml(data.ratingId)}">
        <h2 id="rating-${escapeHtml(data.ratingId)}">Avalie ${ratingArticle} ${escapeHtml(data.config.unit.toLowerCase())}</h2>
        <p>Escolha uma nota de 0 a 5 estrelas. A avaliação fica salva neste dispositivo.</p>
        <div class="rating-controls" role="group" aria-label="Nota ${ratingPreposition} ${escapeHtml(data.config.unit.toLowerCase())}">
          <button class="rating-button" type="button" data-rating-value="0" aria-pressed="false">0</button>
          <button class="rating-button" type="button" data-rating-value="1" aria-pressed="false">★</button>
          <button class="rating-button" type="button" data-rating-value="2" aria-pressed="false">★★</button>
          <button class="rating-button" type="button" data-rating-value="3" aria-pressed="false">★★★</button>
          <button class="rating-button" type="button" data-rating-value="4" aria-pressed="false">★★★★</button>
          <button class="rating-button" type="button" data-rating-value="5" aria-pressed="false">★★★★★</button>
        </div>
        <p class="rating-message" data-rating-message>Você ainda não avaliou ${ratingArticle} ${escapeHtml(data.config.unit.toLowerCase())} neste dispositivo.</p>
      </section>
    </article>
  </main>
  <footer class="site-footer">
    <div class="footer-inner">
      <p>&copy; <span data-year></span> Arquivo Vermelho. Ficção independente gratuita.</p>
      <div class="footer-links"><a href="../termos.html">Termos</a><a href="../politica-de-privacidade.html">Privacidade</a><a href="../contato.html">Contato</a><a href="../fale-conosco.html">Mensagem anônima</a><a href="https://www.instagram.com/arquivovermelho599/" target="_blank" rel="noopener">Instagram</a></div>
    </div>
  </footer>
  <script src="../assets/js/main.js?v=20260710-ratings-v2"></script>
  <script type="module" src="../assets/js/pdf-reader.js?v=20260713-fullscreen"></script>
</body>
</html>
`;
}

function chapterArticle(data) {
  return `          <article class="chapter-item">
            <span class="meta">Disponível</span>
            <a href="../../${data.htmlPath}">${escapeHtml(data.fullTitle)}</a>
            <p>Abrir página ${data.config.useRoman ? "da" : "do"} ${escapeHtml(data.config.unit.toLowerCase())} ${escapeHtml(data.numberLabel)}.</p>
          </article>`;
}

function updateArticle(data) {
  const heading = data.config.useRoman
    ? `${data.fullTitle} disponível`
    : `${data.config.unit} ${data.number}: ${data.title} disponível`;

  return `            <article class="update-item">
              <span class="meta">${escapeHtml(data.config.meta)}</span>
              <h3>${escapeHtml(heading)}</h3>
              <p><a href="${escapeHtml(data.htmlPath)}">${escapeHtml(data.fullTitle)}</a> ${escapeHtml(data.config.updateVerb)}</p>
            </article>`;
}

function extractChaptersFromHtml(html, pagePrefix) {
  const expression = new RegExp(`<a href="(?:\\.\\./\\.\\./)?(capitulos/${pagePrefix}-(\\d{2})-[^"]+\\.html)">([^<]+)</a>`, "g");
  const chapters = [];
  let match;
  while ((match = expression.exec(html))) {
    chapters.push({
      href: match[1],
      number: Number(match[2]),
      label: match[3]
    });
  }
  return chapters.sort((a, b) => a.number - b.number);
}

function insertChapterInWorkPage(html, data) {
  if (html.includes(`href="../../${data.htmlPath}"`)) {
    return { html, changed: false };
  }

  const article = `${chapterArticle(data)}\n`;
  const chapters = extractChaptersFromHtml(html, data.config.pagePrefix);
  const previous = [...chapters].reverse().find((chapter) => chapter.number < data.number);

  if (previous) {
    const previousRegex = new RegExp(`(\\s*<article class="chapter-item">[\\s\\S]*?href="../../${previous.href.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}"[\\s\\S]*?</article>)`);
    return { html: html.replace(previousRegex, `$1\n${article}`), changed: true };
  }

  return {
    html: html.replace(/(\s*<article class="chapter-item">\s*<span class="meta">Em breve<\/span>)/, `\n${article}$1`),
    changed: true
  };
}

function replaceHomeUpdate(html, data) {
  const article = updateArticle(data);
  const metaRegex = new RegExp(`\\s*<article class="update-item">\\s*<span class="meta">${data.config.meta.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}</span>[\\s\\S]*?</article>`);

  if (metaRegex.test(html)) {
    return html.replace(metaRegex, `\n${article}`);
  }

  return html.replace(/(<div class="update-list">\s*)/, `$1\n${article}\n`);
}

function updateSidebar(html, data) {
  if (html.includes(data.htmlPath)) {
    return html;
  }

  const titleAnchor = data.work.label.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const titleMatch = html.match(new RegExp(`title:\\s*["']${titleAnchor}["']`));
  const sectionStart = titleMatch ? titleMatch.index : -1;
  if (sectionStart === -1) {
    throw new Error("Não encontrei a obra na barra lateral.");
  }

  let chaptersStart = html.indexOf("chapters:", sectionStart);

  if (data.config.versionLabel) {
    const versionAnchor = data.config.versionLabel.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    const versionMatch = html.slice(sectionStart).match(new RegExp(`label:\\s*["']${versionAnchor}["']`));
    if (!versionMatch || versionMatch.index === undefined) {
      throw new Error("Não encontrei a versão correta na barra lateral.");
    }
    chaptersStart = html.indexOf("chapters:", sectionStart + versionMatch.index);
  }

  if (chaptersStart === -1) {
    throw new Error("Não encontrei a lista correta na barra lateral.");
  }

  const listStart = html.indexOf("[", chaptersStart);
  if (listStart === -1) {
    throw new Error("Não encontrei o início da lista na barra lateral.");
  }

  let depth = 0;
  let listEnd = -1;
  for (let index = listStart; index < html.length; index += 1) {
    if (html[index] === "[") {
      depth += 1;
    } else if (html[index] === "]") {
      depth -= 1;
      if (!depth) {
        listEnd = index;
        break;
      }
    }
  }

  if (listEnd === -1) {
    throw new Error("Não encontrei o fim da lista na barra lateral.");
  }

  const listContents = html.slice(listStart + 1, listEnd);
  const indentation = listContents.match(/\n(\s+)\[/)?.[1] || "        ";
  const entry = `${indentation}["${data.fullTitle}", "${data.htmlPath}"]`;
  const before = html.slice(0, listEnd).replace(/,?\s*$/, ",");
  const after = html.slice(listEnd);
  return `${before}\n${entry}${after}`;
}

function updateSitemap(html, data) {
  const url = `https://RLKsBR.github.io/ArquivoVermelho/${data.htmlPath}`;
  if (html.includes(url)) {
    return html;
  }
  return html.replace("</urlset>", `  <url><loc>${url}</loc></url>\n</urlset>`);
}

function updatePreviousChapter(html, data) {
  const nextPrefix = data.config.unit === "Crônica" ? "Próxima" : "Próximo";
  const nextLabel = `${nextPrefix} ${data.config.unit.toLowerCase()}`;
  const nextLink = `<a class="button chapter-button chapter-button-next" href="../${data.htmlPath}">${nextLabel}</a>`;
  return html.replace(
    /<span class="button chapter-button chapter-button-next disabled">Próxim[oa] [^<]+ em breve<\/span>/,
    nextLink
  );
}

async function applyAdd(data) {
  const workPath = pathJoin(data.config.workPage);
  let workHtml = await readText(workPath);
  const existingChapters = extractChaptersFromHtml(workHtml, data.config.pagePrefix);
  const previous = [...existingChapters].reverse().find((chapter) => chapter.number < data.number);
  const next = existingChapters.find((chapter) => chapter.number > data.number);

  let chapterPageExists = false;
  try {
    await readText(data.htmlPath);
    chapterPageExists = true;
  } catch (_error) {
    // A página ainda não existe e será criada abaixo.
  }

  await writeBinary(data.pdfPath, data.pdf);
  log(`PDF salvo ou substituído em <code>${data.pdfPath}</code>.`);

  if (chapterPageExists) {
    log(`Página já existente mantida em <code>${data.htmlPath}</code>.`);
  } else {
    await writeText(data.htmlPath, chapterPageTemplate(data, previous, next));
    log(`Página criada em <code>${data.htmlPath}</code>.`);
  }

  if (data.options.updateWorkPage) {
    const workUpdate = insertChapterInWorkPage(workHtml, data);
    workHtml = workUpdate.html;
    if (workUpdate.changed) {
      await writeText(workPath, workHtml);
      log(`Página da obra atualizada: <code>${workPath}</code>.`);
    } else {
      log("Página da obra já tinha este capítulo; segui com as demais atualizações.");
    }
  }

  if (data.options.updateHome) {
    const home = replaceHomeUpdate(await readText("index.html"), data);
    await writeText("index.html", home);
    log(`Home atualizada em "Últimas atualizações".`);
  }

  if (data.options.updateSidebar) {
    const sidebar = updateSidebar(await readText("assets/js/main.js"), data);
    await writeText("assets/js/main.js", sidebar);
    log(`Barra lateral atualizada em <code>assets/js/main.js</code>.`);
  }

  if (data.options.updateSitemap) {
    const sitemap = updateSitemap(await readText("sitemap.xml"), data);
    await writeText("sitemap.xml", sitemap);
    log(`Sitemap atualizado.`);
  }

  if (data.options.updatePrevious && previous) {
    try {
      const previousHtml = updatePreviousChapter(await readText(previous.href), data);
      await writeText(previous.href, previousHtml);
      log(`Capítulo anterior atualizado: <code>${previous.href}</code>.`);
    } catch (error) {
      log(`Não consegui atualizar o capítulo anterior automaticamente: ${escapeHtml(error.message)}`, "warn");
    }
  }
}

async function applyReplace(data) {
  await writeBinary(data.replacePath, data.pdf);
  log(`PDF substituído em <code>${data.replacePath}</code>.`);
}

function updateFormForWork() {
  const work = WORKS[workSelect.value];
  versionSelect.innerHTML = "";

  if (work.versions) {
    versionRow.hidden = false;
    Object.entries(work.versions).forEach(([key, version]) => {
      const option = document.createElement("option");
      option.value = key;
      option.textContent = version.label;
      versionSelect.append(option);
    });
  } else {
    versionRow.hidden = true;
  }
}

function updateMode() {
  const replace = modeSelect.value === "replace";
  addFields.hidden = replace;
  addOptions.hidden = replace;
  replaceFields.hidden = !replace;
}

Object.entries(WORKS).forEach(([key, work]) => {
  const option = document.createElement("option");
  option.value = key;
  option.textContent = work.label;
  workSelect.append(option);
});

updateFormForWork();
updateMode();

workSelect.addEventListener("change", updateFormForWork);
modeSelect.addEventListener("change", updateMode);
tabButtons.forEach((button) => {
  button.addEventListener("click", () => selectPanelTab(button.dataset.panelTab));
});

$("[data-preview]").addEventListener("click", () => {
  try {
    state.preview = buildPublicationData();
    renderPreview(state.preview);
  } catch (error) {
    previewBox.innerHTML = `<p class="error">${escapeHtml(error.message)}</p>`;
  }
});

form.addEventListener("submit", async (event) => {
  event.preventDefault();

  try {
    const data = buildPublicationData();
    renderPreview(data);

    if (data.mode === "replace") {
      await applyReplace(data);
    } else {
      await applyAdd(data);
    }

    setStatus("Enviando ao GitHub...", "Criando commit e acionando o GitHub Pages.");
    const publication = await publishToGithub();
    if (publication.published) {
      log(`Publicado no GitHub no commit <code>${escapeHtml(publication.revision)}</code>.`, "ok");
      setStatus("Publicação enviada.", publication.message);
    } else {
      log(publication.message, "warn");
      setStatus("Sem alterações novas.", publication.message);
    }
  } catch (error) {
    log(escapeHtml(error.message), "error");
  }
});
