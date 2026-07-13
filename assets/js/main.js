(() => {
  const measurementId = 'G-TG69DXZH3R';

  if (window.__arquivoVermelhoAnalyticsLoaded) {
    return;
  }

  window.__arquivoVermelhoAnalyticsLoaded = true;
  window.dataLayer = window.dataLayer || [];
  window.gtag = window.gtag || function gtag() {
    window.dataLayer.push(arguments);
  };

  const script = document.createElement('script');
  script.async = true;
  script.src = `https://www.googletagmanager.com/gtag/js?id=${measurementId}`;
  document.head.appendChild(script);

  window.gtag('js', new Date());
  window.gtag('config', measurementId);
})();

const menuToggle = document.querySelector('[data-menu-toggle]');
const siteNav = document.querySelector('[data-site-nav]');

if (menuToggle && siteNav) {
  menuToggle.addEventListener('click', () => {
    const isOpen = siteNav.classList.toggle('is-open');
    menuToggle.setAttribute('aria-expanded', String(isOpen));
  });
}

const year = document.querySelector('[data-year]');
if (year) {
  year.textContent = new Date().getFullYear();
}

document.querySelectorAll('[data-character-count]').forEach((field) => {
  const counter = document.getElementById(field.dataset.characterCount);
  if (!counter) {
    return;
  }

  const updateCounter = () => {
    counter.textContent = String(field.value.length);
  };

  updateCounter();
  field.addEventListener('input', updateCounter);
});

document.querySelectorAll('[data-contact-form]').forEach((form) => {
  const status = form.querySelector('[data-form-status]');
  const submitButton = form.querySelector('button[type="submit"]');
  const successUrl = form.dataset.successUrl || 'mensagem-enviada.html';
  const fallbackEmail = form.dataset.fallbackEmail || 'arquivovermelhoofc@gmail.com';

  const setFormStatus = (message, type = '') => {
    if (!status) {
      return;
    }

    status.textContent = message;
    status.classList.toggle('is-error', type === 'error');
    status.classList.toggle('is-success', type === 'success');
  };

  form.addEventListener('submit', async (event) => {
    event.preventDefault();

    if (submitButton) {
      submitButton.disabled = true;
      submitButton.textContent = 'Enviando...';
    }

    setFormStatus('Enviando mensagem...', 'success');

    try {
      const response = await fetch(form.action, {
        method: 'POST',
        headers: {
          Accept: 'application/json'
        },
        body: new FormData(form)
      });

      if (!response.ok) {
        throw new Error(`Falha no envio: ${response.status}`);
      }

      window.location.assign(successUrl);
    } catch (error) {
      const fallbackLink = `mailto:${fallbackEmail}`;
      setFormStatus(`Não foi possível enviar pelo formulário agora. Tente novamente ou envie pelo e-mail oficial: ${fallbackEmail}`, 'error');

      if (status && !form.querySelector('[data-form-fallback]')) {
        const link = document.createElement('a');
        link.href = fallbackLink;
        link.dataset.formFallback = '';
        link.textContent = 'Abrir e-mail oficial';
        link.className = 'form-fallback-link';
        status.insertAdjacentElement('afterend', link);
      }

      if (submitButton) {
        submitButton.disabled = false;
        submitButton.textContent = 'Enviar mensagem';
      }
    }
  });
});

const nativeApp = window.ArquivoVermelhoApp;

if (nativeApp && typeof nativeApp.checkForUpdates === 'function') {
  document.documentElement.classList.add('is-native-app');

  document.querySelectorAll('.mobile-app-link').forEach((link) => {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = `${link.className} app-update-button`;
    button.textContent = 'Verificar atualizações';
    button.addEventListener('click', () => {
      nativeApp.checkForUpdates();
    });

    link.replaceWith(button);
  });
}

if (!nativeApp) {
  const mainScript = document.currentScript || document.querySelector('script[src*="assets/js/main.js"]');
  const siteRoot = mainScript ? new URL('../../', mainScript.src) : new URL('./', window.location.href);
  const toUrl = (path) => new URL(path, siteRoot).toString();
  const currentPath = window.location.pathname.replace(/\/+$/, '');

  const libraryWorks = [
    {
      title: 'A Hora Vermelha',
      href: 'obras/a-hora-vermelha/',
      chapters: [
        ['Capítulo 1: GAIA', 'capitulos/a-hora-vermelha-capitulo-01.html'],
        ['Capítulo 2: E AGORA', 'capitulos/a-hora-vermelha-capitulo-02-e-agora.html'],
        ['Capítulo 3: Cadê a Arma', 'capitulos/a-hora-vermelha-capitulo-03-cade-a-arma.html'],
        ['Capítulo 4: Um Não Vinte', 'capitulos/a-hora-vermelha-capitulo-04-um-nao-vinte.html'],
        ['Capítulo 5: Me Destranca', 'capitulos/a-hora-vermelha-capitulo-05-me-destranca.html'],
        ['Capítulo 6: Sem Café', 'capitulos/a-hora-vermelha-capitulo-06-sem-cafe.html']
      ]
    },
    {
      title: 'O Último Dia',
      href: 'obras/o-ultimo-dia/',
      groups: [
        {
          label: 'Original multilíngue',
          chapters: [
            ['Capítulo 1: Dias de Treinamento', 'capitulos/o-ultimo-dia-capitulo-01-dias-de-treinamento.html'],
            ['Capítulo 2: Dia 1', 'capitulos/o-ultimo-dia-capitulo-02-dia-1.html'],
            ['Capítulo 3: Dia 2', 'capitulos/o-ultimo-dia-capitulo-03-dia-2.html'],
            ['Capítulo 4: Dia 3', 'capitulos/o-ultimo-dia-capitulo-04-dia-3.html'],
            ['Capítulo 5: Dia 4', 'capitulos/o-ultimo-dia-capitulo-05-dia-4.html'],
            ['Capítulo 6: Dia 5', 'capitulos/o-ultimo-dia-capitulo-06-dia-5.html']
          ]
        },
        {
          label: '100% português',
          chapters: [
            ['Capítulo 1: Dias de Treinamento', 'capitulos/o-ultimo-dia-portugues-capitulo-01-dias-de-treinamento.html'],
            ['Capítulo 2: Dia 1', 'capitulos/o-ultimo-dia-portugues-capitulo-02-dia-1.html'],
            ['Capítulo 3: Dia 2', 'capitulos/o-ultimo-dia-portugues-capitulo-03-dia-2.html'],
            ['Capítulo 4: Dia 3', 'capitulos/o-ultimo-dia-portugues-capitulo-04-dia-3.html'],
            ['Capítulo 5: Dia 4', 'capitulos/o-ultimo-dia-portugues-capitulo-05-dia-4.html'],
            ["Capítulo 6: Dia 5", "capitulos/o-ultimo-dia-portugues-capitulo-06-dia-5.html"]]
        }
      ]
    },
    {
      title: 'Crônicas',
      href: 'obras/cronicas/',
      chapters: [
        ['Crônica I: A Orbe de Uriel', 'capitulos/cronicas-cronica-01.html'],
        ['Crônica II: O Rei que Mandou os Outros Morrerem', 'capitulos/cronicas-cronica-02-o-rei-que-mandou-os-outros-morrerem.html'],
        ['Crônica III: A Coroa Livre', 'capitulos/cronicas-cronica-03-a-coroa-livre.html'],
        ['Crônica IV: Crônica IV', 'capitulos/cronicas-cronica-04-cronica-iv.html']
      ]
    },
    {
      title: 'Checkpoint Zumbi',
      href: 'obras/checkpoint-zumbi/',
      chapters: [
        ['Capítulo 1: Prólogo', 'capitulos/checkpoint-zumbi-capitulo-01-prologo.html'],
        ['Capítulo 2: Carlos e a Ligação do Coronel', 'capitulos/checkpoint-zumbi-capitulo-02-carlos-e-a-ligacao-do-coronel.html'],
        ['Capítulo 3: Cigarros, Magão, Dona Célia e João', 'capitulos/checkpoint-zumbi-capitulo-03-cigarros-magao-dona-celia-e-joao.html'],
        ['Capítulo 4: Cristina Sem Internet e Sem Plano', 'capitulos/checkpoint-zumbi-capitulo-04-cristina-sem-internet-e-sem-plano.html'],
        ["Capítulo 5: A Casa De Seu Augusto", "capitulos/checkpoint-zumbi-capitulo-05-a-casa-de-seu-augusto.html"]]
    },
    {
      title: 'Espaço Mortal',
      href: 'obras/espaco-mortal/',
      chapters: [
        ['Capítulo 1: A Ideia de Íris', 'capitulos/espaco-mortal-capitulo-01-a-ideia-de-iris.html'],
        ['Capítulo 2: A Sábia Preto', 'capitulos/espaco-mortal-capitulo-02-a-sabia-preto.html']
      ]
    }
  ];

  const isCurrent = (href) => {
    const linkPath = new URL(href, siteRoot).pathname.replace(/\/+$/, '');
    return currentPath === linkPath;
  };

  const createChapterList = (chapters) => {
    const list = document.createElement('ol');
    list.className = 'library-chapter-list';

    chapters.forEach(([label, href]) => {
      const item = document.createElement('li');
      const link = document.createElement('a');
      link.href = toUrl(href);
      link.textContent = label;

      if (isCurrent(href)) {
        link.setAttribute('aria-current', 'page');
      }

      item.append(link);
      list.append(item);
    });

    return list;
  };

  const sidebar = document.createElement('nav');
  sidebar.className = 'desktop-library-sidebar';
  sidebar.setAttribute('aria-label', 'Navegação das obras');
  const headerBrand = document.querySelector('.site-header .brand');

  if (headerBrand) {
    const sidebarBrand = headerBrand.cloneNode(true);
    sidebarBrand.classList.add('library-sidebar-brand');
    sidebar.append(sidebarBrand);
  }

  sidebar.insertAdjacentHTML('beforeend', '<p class="library-sidebar-title">Obras</p>');

  libraryWorks.forEach((work) => {
    const details = document.createElement('details');
    details.className = 'library-work';
    const workPaths = [work.href];

    if (work.chapters) {
      work.chapters.forEach(([, href]) => workPaths.push(href));
    }

    if (work.groups) {
      work.groups.forEach((group) => group.chapters.forEach(([, href]) => workPaths.push(href)));
    }

    details.open = workPaths.some(isCurrent);

    const summary = document.createElement('summary');
    summary.textContent = work.title;
    details.append(summary);

    const workLink = document.createElement('a');
    workLink.className = 'library-work-link';
    workLink.href = toUrl(work.href);
    workLink.textContent = 'Página da obra';

    if (isCurrent(work.href)) {
      workLink.setAttribute('aria-current', 'page');
    }

    details.append(workLink);

    if (work.chapters) {
      details.append(createChapterList(work.chapters));
    }

    if (work.groups) {
      work.groups.forEach((group) => {
        const groupTitle = document.createElement('p');
        groupTitle.className = 'library-group-title';
        groupTitle.textContent = group.label;
        details.append(groupTitle);
        details.append(createChapterList(group.chapters));
      });
    }

    sidebar.append(details);
  });

  document.body.prepend(sidebar);
  document.body.classList.add('has-library-sidebar');
}

const progress = document.querySelector('[data-reading-progress]');
if (progress) {
  const updateProgress = () => {
    const scrollable = document.documentElement.scrollHeight - window.innerHeight;
    const percent = scrollable > 0 ? (window.scrollY / scrollable) * 100 : 0;
    progress.style.width = `${Math.min(100, Math.max(0, percent))}%`;
  };

  updateProgress();
  window.addEventListener('scroll', updateProgress, { passive: true });
}

const ratingBlocks = document.querySelectorAll('[data-chapter-rating]');

if (ratingBlocks.length) {
  const readerIdKey = 'arquivoVermelho.readerId.v1';
  const ratingsKey = 'arquivoVermelho.chapterRatings.v1';
  const ratingsSyncKey = 'arquivoVermelho.ratingSync.v1';
  const ratingsEndpoint = 'https://script.google.com/macros/s/AKfycbyX3Z3bsVM7NwNpJaSxbn-pqjnKK6--iLoLDpdfD_0eZUVKtXaAvV-V7Ch4IXQnP70UVg/exec';
  const workTitles = {
    'a-hora-vermelha': 'A Hora Vermelha',
    'cronicas': 'Crônicas',
    'checkpoint-zumbi': 'Checkpoint Zumbi',
    'espaco-mortal': 'Espaço Mortal',
    'o-ultimo-dia-original': 'O Último Dia — Original multilíngue',
    'o-ultimo-dia-portugues': 'O Último Dia — 100% Português'
  };

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

  const getWorkId = (ratingId) => {
    if (ratingId.startsWith('obra-')) {
      return ratingId.slice(5);
    }

    if (ratingId.startsWith('o-ultimo-dia-portugues-')) {
      return 'o-ultimo-dia-portugues';
    }

    if (ratingId.startsWith('o-ultimo-dia-')) {
      return 'o-ultimo-dia-original';
    }

    if (ratingId.startsWith('a-hora-vermelha-')) {
      return 'a-hora-vermelha';
    }

    if (ratingId.startsWith('cronicas-')) {
      return 'cronicas';
    }

    if (ratingId.startsWith('checkpoint-zumbi-')) {
      return 'checkpoint-zumbi';
    }

    if (ratingId.startsWith('espaco-mortal-')) {
      return 'espaco-mortal';
    }

    return ratingId;
  };

  const formatRatingLabel = (rating) => {
    const display = Number.isInteger(rating) ? String(rating) : rating.toFixed(1).replace('.', ',');
    return `${display} ${rating === 1 ? 'estrela' : 'estrelas'}`;
  };

  const getRatingTarget = (block) => {
    const ratingId = block.dataset.chapterRating || window.location.pathname;
    const isWork = ratingId.startsWith('obra-');
    const workId = getWorkId(ratingId);
    const pageTitle = document.querySelector('h1')?.textContent?.replace(/\s+/g, ' ').trim() || '';

    return {
      id: ratingId,
      type: isWork ? 'work' : 'chapter',
      workId,
      workTitle: workTitles[workId] || pageTitle,
      chapterId: isWork ? '' : ratingId,
      chapterTitle: isWork ? '' : pageTitle
    };
  };

  const getSyncState = () => {
    try {
      return JSON.parse(localStorage.getItem(ratingsSyncKey)) || {};
    } catch {
      return {};
    }
  };

  const markSynced = (target, ratedAt) => {
    const state = getSyncState();
    state[target.id] = ratedAt;
    localStorage.setItem(ratingsSyncKey, JSON.stringify(state));
  };

  const loadSummary = (target) => new Promise((resolve, reject) => {
    const callbackName = `arquivoVermelhoRating_${Date.now()}_${Math.random().toString(16).slice(2)}`;
    const script = document.createElement('script');
    const cleanup = () => {
      delete window[callbackName];
      script.remove();
    };
    const params = new URLSearchParams({
      action: 'summary',
      type: target.type,
      workId: target.workId,
      chapterId: target.chapterId,
      callback: callbackName
    });

    window[callbackName] = (response) => {
      cleanup();
      if (response?.ok) {
        resolve(response.summary);
        return;
      }
      reject(new Error(response?.error || 'Não foi possível carregar as avaliações.'));
    };

    script.src = `${ratingsEndpoint}?${params.toString()}`;
    script.async = true;
    script.onerror = () => {
      cleanup();
      reject(new Error('Não foi possível carregar as avaliações.'));
    };
    document.head.append(script);
  });

  const submitRating = async (target, rating) => {
    const body = new URLSearchParams({
      action: 'rate',
      type: target.type,
      workId: target.workId,
      workTitle: target.workTitle,
      chapterId: target.chapterId,
      chapterTitle: target.chapterTitle,
      rating: String(rating),
      readerId
    });

    await fetch(ratingsEndpoint, {
      method: 'POST',
      mode: 'no-cors',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
      body
    });
  };

  const getRatingSummary = (block) => {
    let summary = block.querySelector('[data-rating-summary]');

    if (!summary) {
      summary = document.createElement('p');
      summary.className = 'rating-summary';
      summary.dataset.ratingSummary = '';

      const controls = block.querySelector('.rating-controls');
      if (controls) {
        controls.insertAdjacentElement('afterend', summary);
      } else {
        block.append(summary);
      }
    }

    return summary;
  };

  const renderRatingSummary = (block, rating, count = 0) => {
    const summary = getRatingSummary(block);

    if (Number.isFinite(rating)) {
      const countLabel = count === 1 ? '1 avaliação' : `${count} avaliações`;
      summary.textContent = `Média geral: ${formatRatingLabel(rating)} (${countLabel}).`;
      return;
    }

    summary.textContent = 'Média geral: ainda sem avaliações.';
  };

  const renderSavedState = (block, rating) => {
    const buttons = block.querySelectorAll('[data-rating-value]');
    const message = block.querySelector('[data-rating-message]');

    buttons.forEach((button) => {
      const value = Number(button.dataset.ratingValue);
      button.disabled = true;
      button.setAttribute('aria-pressed', String(value === rating));
      button.classList.toggle('is-selected', value === rating);
    });

    if (message) {
      const label = formatRatingLabel(rating);
      message.textContent = `Sua nota (${label}) está vinculada a este dispositivo.`;
    }

  };

  const renderSummary = async (block, target) => {
    try {
      const summary = await loadSummary(target);
      const average = summary.average === null ? Number.NaN : Number(summary.average);
      renderRatingSummary(block, average, Number(summary.count));
      return summary;
    } catch {
      renderRatingSummary(block, Number.NaN);
      return null;
    }
  };

  const waitForSummary = async (block, target, minimumCount = 1) => {
    for (let attempt = 0; attempt < 3; attempt += 1) {
      await new Promise((resolve) => window.setTimeout(resolve, attempt ? 700 : 500));

      const summary = await renderSummary(block, target);
      if (summary && summary.count >= minimumCount) {
        return summary;
      }
    }

    return null;
  };

  ratingBlocks.forEach((block) => {
    const target = getRatingTarget(block);
    const buttons = block.querySelectorAll('[data-rating-value]');
    const ratings = readRatings();
    const saved = ratings[target.id];
    const description = block.querySelector('p:not([data-rating-message]):not([data-rating-summary])');

    if (description) {
      description.textContent = 'A nota é associada anonimamente a este dispositivo. A média é pública e não exige login.';
    }

    if (saved && saved.readerId === readerId) {
      renderSavedState(block, Number(saved.rating));
      const syncState = getSyncState();

      if (syncState[target.id] !== saved.ratedAt) {
        submitRating(target, Number(saved.rating))
          .then(() => waitForSummary(block, target))
          .then((summary) => {
            if (summary) {
              markSynced(target, saved.ratedAt);
            }
          })
          .catch(() => null);
      } else {
        renderSummary(block, target);
      }
      return;
    }

    renderSummary(block, target);

    buttons.forEach((button) => {
      button.addEventListener('click', async () => {
        const rating = Number(button.dataset.ratingValue);
        const currentRatings = readRatings();

        if (currentRatings[target.id] && currentRatings[target.id].readerId === readerId) {
          renderSavedState(block, Number(currentRatings[target.id].rating));
          return;
        }

        currentRatings[target.id] = {
          rating,
          readerId,
          ratedAt: new Date().toISOString()
        };

        writeRatings(currentRatings);
        renderSavedState(block, rating);

        const message = block.querySelector('[data-rating-message]');
        if (message) {
          message.textContent = 'Registrando sua nota...';
        }

        try {
          await submitRating(target, rating);
          const summary = await waitForSummary(block, target);
          if (!summary) {
            throw new Error('A avaliação ainda não foi confirmada.');
          }

          markSynced(target, currentRatings[target.id].ratedAt);
          if (message) {
            message.textContent = `Sua nota (${formatRatingLabel(rating)}) foi registrada.`;
          }
        } catch {
          if (message) {
            message.textContent = 'Sua nota foi salva neste dispositivo e será enviada quando a conexão estiver disponível.';
          }
        }
      });
    });
  });
}
