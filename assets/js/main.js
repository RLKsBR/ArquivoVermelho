const menuToggle = document.querySelector("[data-menu-toggle]");
const siteNav = document.querySelector("[data-site-nav]");

if (menuToggle && siteNav) {
  menuToggle.addEventListener("click", () => {
    const isOpen = siteNav.classList.toggle("is-open");
    menuToggle.setAttribute("aria-expanded", String(isOpen));
  });
}

const year = document.querySelector("[data-year]");
if (year) {
  year.textContent = new Date().getFullYear();
}

document.querySelectorAll("[data-character-count]").forEach((field) => {
  const counter = document.getElementById(field.dataset.characterCount);
  if (!counter) {
    return;
  }

  const updateCounter = () => {
    counter.textContent = String(field.value.length);
  };

  updateCounter();
  field.addEventListener("input", updateCounter);
});

document.querySelectorAll("[data-contact-form]").forEach((form) => {
  const status = form.querySelector("[data-form-status]");
  const submitButton = form.querySelector('button[type="submit"]');
  const successUrl = form.dataset.successUrl || "mensagem-enviada.html";
  const fallbackEmail = form.dataset.fallbackEmail || "arquivovermelhoofc@gmail.com";

  const setFormStatus = (message, type = "") => {
    if (!status) {
      return;
    }

    status.textContent = message;
    status.classList.toggle("is-error", type === "error");
    status.classList.toggle("is-success", type === "success");
  };

  form.addEventListener("submit", async (event) => {
    event.preventDefault();

    if (submitButton) {
      submitButton.disabled = true;
      submitButton.textContent = "Enviando...";
    }

    setFormStatus("Enviando mensagem...", "success");

    try {
      const response = await fetch(form.action, {
        method: "POST",
        headers: {
          Accept: "application/json"
        },
        body: new FormData(form)
      });

      if (!response.ok) {
        throw new Error(`Falha no envio: ${response.status}`);
      }

      window.location.assign(successUrl);
    } catch (error) {
      const fallbackLink = `mailto:${fallbackEmail}`;
      setFormStatus(`Não foi possível enviar pelo formulário agora. Tente novamente ou envie pelo e-mail oficial: ${fallbackEmail}`, "error");

      if (status && !form.querySelector("[data-form-fallback]")) {
        const link = document.createElement("a");
        link.href = fallbackLink;
        link.dataset.formFallback = "";
        link.textContent = "Abrir e-mail oficial";
        link.className = "form-fallback-link";
        status.insertAdjacentElement("afterend", link);
      }

      if (submitButton) {
        submitButton.disabled = false;
        submitButton.textContent = "Enviar mensagem";
      }
    }
  });
});

const nativeApp = window.ArquivoVermelhoApp;

if (nativeApp && typeof nativeApp.checkForUpdates === "function") {
  document.documentElement.classList.add("is-native-app");

  document.querySelectorAll(".mobile-app-link").forEach((link) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = `${link.className} app-update-button`;
    button.textContent = "Verificar atualizações";
    button.addEventListener("click", () => {
      nativeApp.checkForUpdates();
    });

    link.replaceWith(button);
  });
}

if (!nativeApp) {
  const mainScript = document.currentScript || document.querySelector('script[src*="assets/js/main.js"]');
  const siteRoot = mainScript ? new URL("../../", mainScript.src) : new URL("./", window.location.href);
  const toUrl = (path) => new URL(path, siteRoot).toString();
  const currentPath = window.location.pathname.replace(/\/+$/, "");

  const libraryWorks = [
    {
      title: "A Hora Vermelha",
      href: "obras/a-hora-vermelha/",
      chapters: [
        ["Capítulo 1: GAIA", "capitulos/a-hora-vermelha-capitulo-01.html"],
        ["Capítulo 2: E AGORA", "capitulos/a-hora-vermelha-capitulo-02-e-agora.html"],
        ["Capítulo 3: Cadê a Arma", "capitulos/a-hora-vermelha-capitulo-03-cade-a-arma.html"],
        ["Capítulo 4: Um Não Vinte", "capitulos/a-hora-vermelha-capitulo-04-um-nao-vinte.html"],
        ["Capítulo 5: Me Destranca", "capitulos/a-hora-vermelha-capitulo-05-me-destranca.html"]
      ]
    },
    {
      title: "O Último Dia",
      href: "obras/o-ultimo-dia/",
      groups: [
        {
          label: "Original multilíngue",
          chapters: [
            ["Capítulo 1: Dias de Treinamento", "capitulos/o-ultimo-dia-capitulo-01-dias-de-treinamento.html"],
            ["Capítulo 2: Dia 1", "capitulos/o-ultimo-dia-capitulo-02-dia-1.html"],
            ["Capítulo 3: Dia 2", "capitulos/o-ultimo-dia-capitulo-03-dia-2.html"],
            ["Capítulo 4: Dia 3", "capitulos/o-ultimo-dia-capitulo-04-dia-3.html"],
            ["Capítulo 5: Dia 4", "capitulos/o-ultimo-dia-capitulo-05-dia-4.html"]
          ]
        },
        {
          label: "100% português",
          chapters: [
            ["Capítulo 1: Dias de Treinamento", "capitulos/o-ultimo-dia-portugues-capitulo-01-dias-de-treinamento.html"],
            ["Capítulo 2: Dia 1", "capitulos/o-ultimo-dia-portugues-capitulo-02-dia-1.html"],
            ["Capítulo 3: Dia 2", "capitulos/o-ultimo-dia-portugues-capitulo-03-dia-2.html"],
            ["Capítulo 4: Dia 3", "capitulos/o-ultimo-dia-portugues-capitulo-04-dia-3.html"],
            ["Capítulo 5: Dia 4", "capitulos/o-ultimo-dia-portugues-capitulo-05-dia-4.html"]
          ]
        }
      ]
    },
    {
      title: "Crônicas",
      href: "obras/cronicas/",
      chapters: [
        ["Crônica I: A Orbe de Uriel", "capitulos/cronicas-cronica-01.html"],
        ["Crônica II: O Rei que Mandou os Outros Morrerem", "capitulos/cronicas-cronica-02-o-rei-que-mandou-os-outros-morrerem.html"],
        ["Crônica III: A Coroa Livre", "capitulos/cronicas-cronica-03-a-coroa-livre.html"]
      ]
    },
    {
      title: "Checkpoint Zumbi",
      href: "obras/checkpoint-zumbi/",
      chapters: [
        ["Capítulo 1: Prólogo", "capitulos/checkpoint-zumbi-capitulo-01-prologo.html"],
        ["Capítulo 2: Carlos e a Ligação do Coronel", "capitulos/checkpoint-zumbi-capitulo-02-carlos-e-a-ligacao-do-coronel.html"],
        ["Capítulo 3: Cigarros, Magão, Dona Célia e João", "capitulos/checkpoint-zumbi-capitulo-03-cigarros-magao-dona-celia-e-joao.html"],
        ["Capítulo 4: Cristina Sem Internet e Sem Plano", "capitulos/checkpoint-zumbi-capitulo-04-cristina-sem-internet-e-sem-plano.html"]
      ]
    }
  ];

  const isCurrent = (href) => {
    const linkPath = new URL(href, siteRoot).pathname.replace(/\/+$/, "");
    return currentPath === linkPath;
  };

  const createChapterList = (chapters) => {
    const list = document.createElement("ol");
    list.className = "library-chapter-list";

    chapters.forEach(([label, href]) => {
      const item = document.createElement("li");
      const link = document.createElement("a");
      link.href = toUrl(href);
      link.textContent = label;

      if (isCurrent(href)) {
        link.setAttribute("aria-current", "page");
      }

      item.append(link);
      list.append(item);
    });

    return list;
  };

  const sidebar = document.createElement("nav");
  sidebar.className = "desktop-library-sidebar";
  sidebar.setAttribute("aria-label", "Navegação das obras");
  sidebar.innerHTML = '<p class="library-sidebar-title">Obras</p>';

  libraryWorks.forEach((work) => {
    const details = document.createElement("details");
    details.className = "library-work";

    const workPaths = [work.href];
    if (work.chapters) {
      work.chapters.forEach(([, href]) => workPaths.push(href));
    }
    if (work.groups) {
      work.groups.forEach((group) => group.chapters.forEach(([, href]) => workPaths.push(href)));
    }
    details.open = workPaths.some(isCurrent);

    const summary = document.createElement("summary");
    summary.textContent = work.title;
    details.append(summary);

    const workLink = document.createElement("a");
    workLink.className = "library-work-link";
    workLink.href = toUrl(work.href);
    workLink.textContent = "Página da obra";
    if (isCurrent(work.href)) {
      workLink.setAttribute("aria-current", "page");
    }
    details.append(workLink);

    if (work.chapters) {
      details.append(createChapterList(work.chapters));
    }

    if (work.groups) {
      work.groups.forEach((group) => {
        const groupTitle = document.createElement("p");
        groupTitle.className = "library-group-title";
        groupTitle.textContent = group.label;
        details.append(groupTitle);
        details.append(createChapterList(group.chapters));
      });
    }

    sidebar.append(details);
  });

  document.body.prepend(sidebar);
  document.body.classList.add("has-library-sidebar");
}

const progress = document.querySelector("[data-reading-progress]");
if (progress) {
  const updateProgress = () => {
    const scrollable = document.documentElement.scrollHeight - window.innerHeight;
    const percent = scrollable > 0 ? (window.scrollY / scrollable) * 100 : 0;
    progress.style.width = `${Math.min(100, Math.max(0, percent))}%`;
  };

  updateProgress();
  window.addEventListener("scroll", updateProgress, { passive: true });
}

const ratingBlocks = document.querySelectorAll("[data-chapter-rating]");

if (ratingBlocks.length) {
  const readerIdKey = "arquivoVermelho.readerId.v1";
  const ratingsKey = "arquivoVermelho.chapterRatings.v1";

  const createReaderId = () => {
    if (window.crypto && window.crypto.randomUUID) {
      return window.crypto.randomUUID();
    }

    return `reader-${Date.now()}-${Math.random().toString(16).slice(2)}`;
  };

  let readerId = localStorage.getItem(readerIdKey);
  if (!readerId) {
    readerId = createReaderId();
    localStorage.setItem(readerIdKey, readerId);
  }

  const readRatings = () => {
    try {
      return JSON.parse(localStorage.getItem(ratingsKey)) || {};
    } catch {
      return {};
    }
  };

  const writeRatings = (ratings) => {
    localStorage.setItem(ratingsKey, JSON.stringify(ratings));
  };

  const formatRatingLabel = (rating) => {
    return rating === 0 ? "0 estrela" : `${rating} ${rating === 1 ? "estrela" : "estrelas"}`;
  };

  const getRatingSummary = (block) => {
    let summary = block.querySelector("[data-rating-summary]");

    if (!summary) {
      summary = document.createElement("p");
      summary.className = "rating-summary";
      summary.dataset.ratingSummary = "";

      const controls = block.querySelector(".rating-controls");
      if (controls) {
        controls.insertAdjacentElement("afterend", summary);
      } else {
        block.append(summary);
      }
    }

    return summary;
  };

  const renderRatingSummary = (block, rating, count = 1) => {
    const summary = getRatingSummary(block);

    if (Number.isFinite(rating)) {
      const countLabel = count === 1 ? "1 avaliação" : `${count} avaliações`;
      summary.textContent = `Média geral: ${formatRatingLabel(rating)} (${countLabel}).`;
      return;
    }

    summary.textContent = "Média geral: ainda sem avaliações.";
  };

  const renderSavedState = (block, rating) => {
    const buttons = block.querySelectorAll("[data-rating-value]");
    const message = block.querySelector("[data-rating-message]");

    buttons.forEach((button) => {
      const value = Number(button.dataset.ratingValue);
      button.disabled = true;
      button.setAttribute("aria-pressed", String(value === rating));
      button.classList.toggle("is-selected", value === rating);
    });

    if (message) {
      const label = formatRatingLabel(rating);
      message.textContent = `Sua nota (${label}) foi salva neste dispositivo.`;
    }

    renderRatingSummary(block, rating);
  };

  ratingBlocks.forEach((block) => {
    const chapterId = block.dataset.chapterRating || window.location.pathname;
    const buttons = block.querySelectorAll("[data-rating-value]");
    const ratings = readRatings();
    const saved = ratings[chapterId];

    if (saved && saved.readerId === readerId) {
      renderSavedState(block, Number(saved.rating));
      return;
    }

    renderRatingSummary(block, Number.NaN);

    buttons.forEach((button) => {
      button.addEventListener("click", () => {
        const rating = Number(button.dataset.ratingValue);
        const currentRatings = readRatings();

        if (currentRatings[chapterId] && currentRatings[chapterId].readerId === readerId) {
          renderSavedState(block, Number(currentRatings[chapterId].rating));
          return;
        }

        currentRatings[chapterId] = {
          rating,
          readerId,
          ratedAt: new Date().toISOString()
        };

        writeRatings(currentRatings);
        renderSavedState(block, rating);
      });
    });
  });
}
