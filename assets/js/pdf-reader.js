import * as pdfjsLib from "../vendor/pdfjs/pdf.mjs";

pdfjsLib.GlobalWorkerOptions.workerSrc = new URL("../vendor/pdfjs/pdf.worker.mjs", import.meta.url).toString();

const viewers = document.querySelectorAll("[data-pdf-viewer]");

const renderViewer = async (viewer) => {
  const source = viewer.dataset.pdfSrc;
  const status = viewer.querySelector("[data-pdf-status]");
  const canvas = viewer.querySelector("[data-pdf-canvas]");
  const pageCurrent = viewer.querySelector("[data-pdf-current]");
  const pageTotal = viewer.querySelector("[data-pdf-total]");
  const previousButton = viewer.querySelector("[data-pdf-previous]");
  const nextButton = viewer.querySelector("[data-pdf-next]");
  const downloadLink = viewer.querySelector("[data-pdf-download]");

  if (!source || !canvas) {
    return;
  }

  const context = canvas.getContext("2d");
  let pdf = null;
  let pageNumber = 1;
  let rendering = false;
  let pendingPage = null;
  let extractedText = "";
  let speechChunks = [];
  let speechIndex = 0;
  let currentUtterance = null;
  let speechRunId = 0;
  let speechTimer = null;
  let nextChapterTimer = null;
  let speechFinished = false;
  let zoom = 1;
  let fullscreenScrollPosition = null;
  const speechProgressKey = `arquivoVermelho.speechProgress.v1:${new URL(source, window.location.href).pathname}`;
  const speechRateBase = 1.25;
  const speechRateStep = 0.1;
  const speechRateMin = 0.5;
  const speechRateMax = 3;
  let speechRateMultiplier = 1;

  const setStatus = (message) => {
    if (status) {
      status.textContent = message;
    }
  };

  const setControls = () => {
    if (pageCurrent) {
      pageCurrent.textContent = String(pageNumber);
    }

    if (pageTotal && pdf) {
      pageTotal.textContent = String(pdf.numPages);
    }

    if (previousButton) {
      previousButton.disabled = !pdf || pageNumber <= 1 || rendering;
    }

    if (nextButton) {
      nextButton.disabled = !pdf || pageNumber >= pdf.numPages || rendering;
    }

    const pagePicker = viewer.querySelector("[data-pdf-page-picker]");
    if (pagePicker && document.activeElement !== pagePicker) {
      pagePicker.value = String(pageNumber);
    }
  };

  const setSpeechStatus = (message) => {
    const speechStatus = viewer.querySelector("[data-speech-status]");
    if (speechStatus) {
      speechStatus.textContent = message;
    }
  };

  const loadSpeechProgress = () => {
    try {
      const saved = JSON.parse(localStorage.getItem(speechProgressKey) || "null");
      if (!saved || saved.source !== source) {
        return null;
      }

      const index = Number(saved.index);
      const total = Number(saved.total);
      if (!Number.isFinite(index) || !Number.isFinite(total) || index < 0 || index >= total) {
        return null;
      }

      return { index, total };
    } catch {
      return null;
    }
  };

  const saveSpeechProgress = (index, total) => {
    if (!total || index >= total) {
      localStorage.removeItem(speechProgressKey);
      return;
    }

    localStorage.setItem(speechProgressKey, JSON.stringify({
      source,
      index: Math.max(0, index),
      total,
      rate: speechRateMultiplier,
      updatedAt: new Date().toISOString()
    }));
  };

  const getActualSpeechRate = () => Number((speechRateBase * speechRateMultiplier).toFixed(2));

  const getScaledPause = (pause) => {
    if (!pause) {
      return 0;
    }

    return Math.max(0, Math.round(pause / speechRateMultiplier));
  };

  const updateSpeechRateDisplay = () => {
    const rateValue = viewer.querySelector("[data-speech-rate-value]");
    const slowerButton = viewer.querySelector("[data-speech-slower]");
    const fasterButton = viewer.querySelector("[data-speech-faster]");

    if (rateValue) {
      rateValue.textContent = `${speechRateMultiplier.toFixed(1)}x`;
    }

    if (slowerButton) {
      slowerButton.disabled = speechRateMultiplier <= speechRateMin;
    }

    if (fasterButton) {
      fasterButton.disabled = speechRateMultiplier >= speechRateMax;
    }
  };

  const createSpeechControls = () => {
    if (viewer.querySelector("[data-speech-controls]")) {
      return;
    }

    const controls = document.createElement("div");
    controls.className = "speech-controls";
    controls.dataset.speechControls = "";
    controls.innerHTML = `
      <button class="button speech-button speech-button-play" type="button" data-speech-play>Ouvir capítulo</button>
      <button class="button speech-button speech-button-pause" type="button" data-speech-pause>Pausar</button>
      <button class="button speech-button speech-button-continue-saved" type="button" data-speech-continue-saved>Continuar de onde parou</button>
      <div class="speech-rate" aria-label="Velocidade da leitura">
        <button class="button speech-button speech-button-slower" type="button" data-speech-slower>Desacelerar</button>
        <span>Velocidade <strong data-speech-rate-value>1.0x</strong></span>
        <button class="button speech-button speech-button-faster" type="button" data-speech-faster>Acelerar</button>
      </div>
      <p class="speech-status" data-speech-status>O áudio usa a voz disponível no seu navegador ou celular.</p>
    `;

    viewer.insertBefore(controls, viewer.firstChild);
    updateSpeechRateDisplay();
  };

  const createReaderControls = () => {
    const toolbar = viewer.querySelector(".pdf-toolbar");
    const panel = viewer.closest(".pdf-reader-panel") || viewer;

    if (!toolbar || toolbar.querySelector("[data-pdf-zoom-in]")) {
      return;
    }

    const chapterNavigation = toolbar.querySelector(".reader-inline-nav");
    const pageCounter = toolbar.querySelector(".pdf-page-count");
    const pageNavigation = document.createElement("div");
    pageNavigation.className = "pdf-page-navigation";
    const pageListId = `pdf-page-options-${Math.random().toString(36).slice(2)}`;
    const pagePicker = document.createElement("label");
    pagePicker.className = "pdf-page-picker";
    pagePicker.innerHTML = `Ir para página <input type="text" inputmode="numeric" pattern="[0-9]*" autocomplete="off" list="${pageListId}" data-pdf-page-picker aria-label="Ir para página" value="${pageNumber}"><datalist id="${pageListId}"></datalist>`;
    const pagePickerInput = pagePicker.querySelector("[data-pdf-page-picker]");
    const pageOptions = pagePicker.querySelector("datalist");

    for (let page = 1; page <= pdf.numPages; page += 1) {
      const option = document.createElement("option");
      option.value = String(page);
      pageOptions.append(option);
    }

    const goToPickedPage = () => {
      const nextPage = Number(pagePickerInput.value);
      if (!Number.isInteger(nextPage) || nextPage < 1 || nextPage > pdf.numPages) {
        pagePickerInput.value = String(pageNumber);
        setStatus(`Escolha uma página entre 1 e ${pdf.numPages}.`);
        return;
      }

      queueRender(nextPage);
    };

    pagePickerInput.addEventListener("change", goToPickedPage);
    pagePickerInput.addEventListener("keydown", (event) => {
      if (event.key === "Enter") {
        event.preventDefault();
        goToPickedPage();
      }
    });

    pageNavigation.append(previousButton, pageCounter, nextButton, pagePicker);

    const zoomControls = document.createElement("div");
    zoomControls.className = "pdf-zoom-controls";

    if (chapterNavigation) {
      toolbar.prepend(chapterNavigation);
    }
    toolbar.append(pageNavigation, zoomControls);

    const zoomOutButton = document.createElement("button");
    zoomOutButton.className = "button secondary";
    zoomOutButton.type = "button";
    zoomOutButton.dataset.pdfZoomOut = "";
    zoomOutButton.textContent = "Diminuir";

    const zoomInButton = document.createElement("button");
    zoomInButton.className = "button secondary";
    zoomInButton.type = "button";
    zoomInButton.dataset.pdfZoomIn = "";
    zoomInButton.textContent = "Ampliar";

    const fullscreenButton = document.createElement("button");
    fullscreenButton.className = "button secondary";
    fullscreenButton.type = "button";
    fullscreenButton.dataset.pdfFullscreen = "";
    fullscreenButton.textContent = "Tela cheia";
    fullscreenButton.setAttribute("aria-pressed", "false");

    zoomControls.append(zoomOutButton, zoomInButton, fullscreenButton);

    zoomOutButton.addEventListener("click", () => {
      zoom = Math.max(0.75, Number((zoom - 0.15).toFixed(2)));
      queueRender(pageNumber);
    });

    zoomInButton.addEventListener("click", () => {
      zoom = Math.min(2.4, Number((zoom + 0.15).toFixed(2)));
      queueRender(pageNumber);
    });

    const readReaderPosition = () => {
      const canvasWrap = viewer.querySelector(".pdf-canvas-wrap");
      return {
        page: pageNumber,
        scrollTop: canvasWrap?.scrollTop || 0,
        scrollLeft: canvasWrap?.scrollLeft || 0
      };
    };

    const setFullscreenUi = (active) => {
      panel.classList.toggle("is-fullscreen", active);
      document.body.classList.toggle("pdf-reader-fullscreen-active", active);
      fullscreenButton.textContent = active ? "Sair da tela cheia" : "Tela cheia";
      fullscreenButton.setAttribute("aria-pressed", String(active));
      fullscreenButton.setAttribute("aria-label", active ? "Sair da tela cheia" : "Abrir leitor em tela cheia");
    };

    const exitReaderFullscreen = async () => {
      if (document.fullscreenElement === panel) {
        await document.exitFullscreen();
        return;
      }

      if (panel.classList.contains("is-fullscreen")) {
        fullscreenScrollPosition = readReaderPosition();
        setFullscreenUi(false);
        queueRender(pageNumber);
      }
    };

    fullscreenButton.addEventListener("click", async () => {
      if (document.fullscreenElement === panel || panel.classList.contains("is-fullscreen")) {
        await exitReaderFullscreen();
        return;
      }

      fullscreenScrollPosition = readReaderPosition();

      try {
        if (typeof panel.requestFullscreen !== "function") {
          throw new Error("Fullscreen API indisponível");
        }

        await panel.requestFullscreen({ navigationUI: "hide" });
      } catch {
        setFullscreenUi(true);
        queueRender(pageNumber);
      }
    });

    document.addEventListener("fullscreenchange", () => {
      const isNativeFullscreen = document.fullscreenElement === panel;
      const wasFullscreen = document.body.classList.contains("pdf-reader-fullscreen-active");

      if (!isNativeFullscreen && wasFullscreen) {
        fullscreenScrollPosition = readReaderPosition();
      }

      setFullscreenUi(isNativeFullscreen);
      queueRender(pageNumber);
    });

    document.addEventListener("keydown", (event) => {
      if (event.key === "Escape" && (document.fullscreenElement === panel || panel.classList.contains("is-fullscreen"))) {
        exitReaderFullscreen();
      }
    });
  };

  const getPauseAfterSegment = (segment, isEndOfParagraph) => {
    const trimmed = segment.trim();
    let pause = isEndOfParagraph ? 420 : 0;

    if (/[.!?]$/.test(trimmed)) {
      pause = Math.max(pause, 430);
    } else if (/[:;]$/.test(trimmed)) {
      pause = Math.max(pause, 340);
    } else if (/[,—–-]$/.test(trimmed)) {
      pause = Math.max(pause, 210);
    }

    return pause;
  };

  const pushSpeechSegment = (chunks, text, pause, page) => {
    const cleanText = text.replace(/\s+/g, " ").trim();

    if (!cleanText) {
      if (chunks.length) {
        chunks[chunks.length - 1].pause = Math.max(chunks[chunks.length - 1].pause, pause);
      }
      return;
    }

    if (cleanText.length <= 1400) {
      chunks.push({ text: cleanText, pause, page });
      return;
    }

    const words = cleanText.split(" ");
    let current = "";

    words.forEach((word) => {
      const next = current ? `${current} ${word}` : word;
      if (next.length > 1200) {
        chunks.push({ text: current, pause: 180, page });
        current = word;
        return;
      }

      current = next;
    });

    if (current) {
      chunks.push({ text: current, pause, page });
    }
  };

  const splitText = (paragraphs, page) => {
    const chunks = [];

    paragraphs.forEach((paragraph) => {
      const cleanParagraph = paragraph.trim();

      if (!cleanParagraph) {
        if (chunks.length) {
          chunks[chunks.length - 1].pause = Math.max(chunks[chunks.length - 1].pause, 780);
        }
        return;
      }

      const segments = cleanParagraph.match(/[^,;.!?:—–-]+[,;.!?:—–-]?/g) || [cleanParagraph];
      segments.forEach((segment, index) => {
        pushSpeechSegment(chunks, segment, getPauseAfterSegment(segment, index === segments.length - 1), page);
      });
    });

    return chunks;
  };

  const joinPdfLines = (previous, next) => {
    if (!previous) {
      return next;
    }

    return /[\u2010-\u2015-]$/.test(previous) ? `${previous}${next}` : `${previous} ${next}`;
  };

  const getPageParagraphs = (items) => {
    const lines = [];
    let currentLine = [];
    let currentY = null;
    let currentX = null;
    let currentHeight = 0;

    const pushCurrentLine = () => {
      const text = currentLine.join(" ").replace(/\s+/g, " ").trim();
      if (text) {
        lines.push({ text, y: currentY, x: currentX, height: currentHeight });
      }
      currentLine = [];
      currentY = null;
      currentX = null;
      currentHeight = 0;
    };

    items.forEach((item) => {
      const text = String(item.str || "").trim();
      if (!text) {
        return;
      }

      const transform = item.transform || [];
      const y = Number(transform[5] || 0);
      const x = Number(transform[4] || 0);
      const height = Number(item.height || Math.abs(transform[3]) || 0);

      if (currentY !== null && Math.abs(y - currentY) > 5) {
        pushCurrentLine();
      }

      currentY = y;
      currentX = currentX === null ? x : Math.min(currentX, x);
      currentHeight = Math.max(currentHeight, height);
      currentLine.push(text);
    });

    pushCurrentLine();

    const readableLines = lines.filter((line) => {
      const compact = line.text.replace(/\s+/g, " ").trim();
      const pageMarker = /(?:p[aá]g(?:ina)?\.?|page)\s*\d+/i;
      return !(pageMarker.test(compact) && compact.length <= 180);
    });

    if (!readableLines.length) {
      return [];
    }

    const gaps = readableLines
      .slice(1)
      .map((line, index) => Math.abs(line.y - readableLines[index].y))
      .filter((gap) => gap > 0)
      .sort((a, b) => a - b);
    // O quartil inferior representa a cadência das linhas que pertencem ao
    // mesmo parágrafo; os espaços maiores ficam reservados para parágrafos.
    const typicalGap = gaps.length ? gaps[Math.floor(gaps.length * 0.25)] : 0;
    const heights = readableLines
      .map((line) => line.height)
      .filter((height) => height > 0)
      .sort((a, b) => a - b);
    const typicalHeight = heights.length ? heights[Math.floor(heights.length / 2)] : 0;
    const paragraphGap = Math.max(typicalGap * 1.5, typicalHeight * 1.65, 16);
    const paragraphs = [];
    let paragraph = "";
    let previousLine = null;

    readableLines.forEach((line) => {
      const gap = previousLine ? Math.abs(previousLine.y - line.y) : 0;
      const startsNewParagraph = previousLine && gap > paragraphGap;

      if (startsNewParagraph && paragraph) {
        paragraphs.push(paragraph);
        paragraph = "";
      }

      paragraph = joinPdfLines(paragraph, line.text);
      previousLine = line;
    });

    if (paragraph) {
      paragraphs.push(paragraph);
    }

    return paragraphs;
  };

  const extractPdfText = async () => {
    if (extractedText) {
      return extractedText;
    }

    setSpeechStatus("Preparando texto do capítulo...");
    const pages = [];

    for (let i = 1; i <= pdf.numPages; i += 1) {
      const page = await pdf.getPage(i);
      const content = await page.getTextContent();
      const paragraphs = getPageParagraphs(content.items);

      if (paragraphs.length) {
        pages.push({ number: i, paragraphs });
      }
    }

    extractedText = pages.map((page) => page.paragraphs.join("\n\n")).join("\n\n");
    speechChunks = pages.flatMap((page) => splitText(page.paragraphs, page.number));
    return extractedText;
  };

  const finishSpeech = () => {
    if (speechFinished) {
      return;
    }

    speechFinished = true;
    currentUtterance = null;
    saveSpeechProgress(speechChunks.length, speechChunks.length);

    const nextChapter = viewer.querySelector(".reader-inline-nav .chapter-button-next[href]:not(.disabled)");
    if (!nextChapter) {
      setSpeechStatus("Leitura concluída.");
      return;
    }

    setSpeechStatus("Leitura concluída. Abrindo o próximo capítulo...");
    nextChapterTimer = window.setTimeout(() => {
      window.location.assign(nextChapter.href);
    }, 900);
  };

  const syncPageToSpeech = async (index) => {
    const targetPage = speechChunks[index]?.page;
    if (targetPage && pdf && targetPage !== pageNumber) {
      await renderPage(targetPage);
    }
  };

  const speakChunk = async (runId) => {
    window.clearTimeout(speechTimer);

    if (runId !== speechRunId) {
      return;
    }

    if (!speechChunks.length || speechIndex >= speechChunks.length) {
      finishSpeech();
      return;
    }

    const chunk = speechChunks[speechIndex];
    await syncPageToSpeech(speechIndex);

    if (runId !== speechRunId) {
      return;
    }

    saveSpeechProgress(speechIndex, speechChunks.length);
    currentUtterance = new SpeechSynthesisUtterance(chunk.text);
    currentUtterance.lang = document.documentElement.lang || "pt-BR";
    currentUtterance.rate = getActualSpeechRate();
    currentUtterance.pitch = 1;

    currentUtterance.onend = () => {
      if (runId !== speechRunId) {
        return;
      }

      speechIndex += 1;
      saveSpeechProgress(speechIndex, speechChunks.length);
      setSpeechStatus(`Lendo parte ${Math.min(speechIndex + 1, speechChunks.length)} de ${speechChunks.length}.`);
      speechTimer = window.setTimeout(() => {
        void speakChunk(runId);
      }, getScaledPause(chunk.pause));
    };

    currentUtterance.onerror = () => {
      setSpeechStatus("Não foi possível continuar a leitura em voz alta neste dispositivo.");
    };

    setSpeechStatus(`Lendo parte ${speechIndex + 1} de ${speechChunks.length}.`);
    window.speechSynthesis.speak(currentUtterance);
  };

  const setupSpeech = () => {
    createSpeechControls();

    const playButton = viewer.querySelector("[data-speech-play]");
    const pauseButton = viewer.querySelector("[data-speech-pause]");
    const continueSavedButton = viewer.querySelector("[data-speech-continue-saved]");
    const slowerButton = viewer.querySelector("[data-speech-slower]");
    const fasterButton = viewer.querySelector("[data-speech-faster]");

    const nativeBridge = window.ArquivoVermelhoApp;
    const hasNativeSpeech = nativeBridge && typeof nativeBridge.speak === "function";
    const hasNativeRateControl = nativeBridge && typeof nativeBridge.setSpeechRate === "function";
    const hasWebSpeech = "speechSynthesis" in window && "SpeechSynthesisUtterance" in window;

    const applyNativeSpeechRate = () => {
      if (hasNativeRateControl) {
        nativeBridge.setSpeechRate(getActualSpeechRate());
      }
    };

    const speakFromIndex = (startIndex, runId) => {
      speechIndex = Math.max(0, Math.min(startIndex, speechChunks.length - 1));

      if (hasNativeSpeech) {
        applyNativeSpeechRate();
        const targetPage = speechChunks[speechIndex]?.page;
        if (targetPage && targetPage !== pageNumber) {
          queueRender(targetPage);
        }
        nativeBridge.speak(JSON.stringify({
          progressKey: speechProgressKey,
          startIndex: speechIndex,
          chunks: speechChunks
        }));
        setSpeechStatus(`Leitura enviada ao app: parte ${speechIndex + 1} de ${speechChunks.length}.`);
        return;
      }

      speakChunk(runId);
    };

    window.addEventListener("arquivoVermelhoSpeechProgress", (event) => {
      const detail = event.detail || {};
      if (detail.progressKey !== speechProgressKey) {
        return;
      }

      const index = Number(detail.index);
      const total = Number(detail.total);
      if (Number.isFinite(total) && index >= total) {
        finishSpeech();
        return;
      }

      saveSpeechProgress(index, total);
      const targetPage = speechChunks[index]?.page;
      if (targetPage && targetPage !== pageNumber) {
        queueRender(targetPage);
      }
    });

    const changeSpeechRate = (direction) => {
      const nextRate = Number((speechRateMultiplier + (direction * speechRateStep)).toFixed(1));
      speechRateMultiplier = Math.min(speechRateMax, Math.max(speechRateMin, nextRate));
      updateSpeechRateDisplay();
      applyNativeSpeechRate();
      setSpeechStatus(`Velocidade ajustada para ${speechRateMultiplier.toFixed(1)}x.`);
    };

    updateSpeechRateDisplay();
    applyNativeSpeechRate();

    if (!hasNativeSpeech && !hasWebSpeech) {
      [playButton, pauseButton, continueSavedButton, slowerButton, fasterButton].forEach((button) => {
        if (button) {
          button.disabled = true;
        }
      });
      setSpeechStatus("Este navegador não oferece leitura em voz alta.");
      return;
    }

    if (playButton) {
      playButton.addEventListener("click", async () => {
        speechRunId += 1;
        const runId = speechRunId;
        window.clearTimeout(speechTimer);
        window.clearTimeout(nextChapterTimer);
        speechFinished = false;
        if (hasNativeSpeech && typeof nativeBridge.stop === "function") {
          nativeBridge.stop();
        }
        if (hasWebSpeech) {
          window.speechSynthesis.cancel();
        }
        speechIndex = 0;
        await extractPdfText();

        if (runId !== speechRunId) {
          return;
        }

        if (!speechChunks.length) {
          setSpeechStatus("Não encontrei texto selecionável neste PDF.");
          return;
        }

        saveSpeechProgress(0, speechChunks.length);
        speakFromIndex(0, runId);
      });
    }

    if (pauseButton) {
      pauseButton.addEventListener("click", () => {
        window.clearTimeout(speechTimer);
        if (speechChunks.length) {
          saveSpeechProgress(speechIndex, speechChunks.length);
        }
        if (hasNativeSpeech && typeof nativeBridge.pause === "function") {
          nativeBridge.pause();
          setSpeechStatus("Leitura pausada no app.");
          return;
        }

        if (hasWebSpeech) {
          window.speechSynthesis.pause();
        }
        setSpeechStatus("Leitura pausada.");
      });
    }

    if (continueSavedButton) {
      continueSavedButton.addEventListener("click", async () => {
        speechRunId += 1;
        const runId = speechRunId;
        window.clearTimeout(speechTimer);
        window.clearTimeout(nextChapterTimer);
        speechFinished = false;
        if (hasNativeSpeech && typeof nativeBridge.stop === "function") {
          nativeBridge.stop();
        }

        if (hasWebSpeech) {
          window.speechSynthesis.cancel();
        }

        await extractPdfText();
        if (runId !== speechRunId) {
          return;
        }

        const progress = loadSpeechProgress();
        if (!progress) {
          setSpeechStatus("Nenhum ponto salvo para este capítulo.");
          return;
        }

        currentUtterance = null;
        speakFromIndex(progress.index, runId);
      });
    }

    if (slowerButton) {
      slowerButton.addEventListener("click", () => changeSpeechRate(-1));
    }

    if (fasterButton) {
      fasterButton.addEventListener("click", () => changeSpeechRate(1));
    }

    window.addEventListener("beforeunload", () => {
      speechRunId += 1;
      window.clearTimeout(speechTimer);
      window.clearTimeout(nextChapterTimer);
      if (hasNativeSpeech && typeof nativeBridge.stop === "function") {
        nativeBridge.stop();
      }
      if (hasWebSpeech) {
        window.speechSynthesis.cancel();
      }
    });
  };

  const queueRender = (nextPage) => {
    if (rendering) {
      pendingPage = nextPage;
      return;
    }

    renderPage(nextPage);
  };

  const renderPage = async (nextPage) => {
    rendering = true;
    pageNumber = nextPage;
    setControls();
    setStatus("Carregando página...");

    const page = await pdf.getPage(pageNumber);
    const baseViewport = page.getViewport({ scale: 1 });
    const canvasWrap = viewer.querySelector(".pdf-canvas-wrap") || viewer;
    const availableWidth = Math.max(280, canvasWrap.clientWidth - 2);
    const cssScale = (availableWidth / baseViewport.width) * zoom;
    const pixelRatio = Math.min(window.devicePixelRatio || 1, 2);
    const viewport = page.getViewport({ scale: cssScale });

    canvas.width = Math.floor(viewport.width * pixelRatio);
    canvas.height = Math.floor(viewport.height * pixelRatio);
    canvas.style.width = `${Math.floor(viewport.width)}px`;
    canvas.style.height = `${Math.floor(viewport.height)}px`;

    context.setTransform(pixelRatio, 0, 0, pixelRatio, 0, 0);
    await page.render({ canvasContext: context, viewport }).promise;

    rendering = false;
    setStatus("");
    setControls();

    if (fullscreenScrollPosition) {
      const savedPosition = fullscreenScrollPosition;
      fullscreenScrollPosition = null;
      requestAnimationFrame(() => {
        const currentCanvasWrap = viewer.querySelector(".pdf-canvas-wrap");
        if (currentCanvasWrap && savedPosition.page === pageNumber) {
          currentCanvasWrap.scrollTop = savedPosition.scrollTop;
          currentCanvasWrap.scrollLeft = savedPosition.scrollLeft;
        }
      });
    }

    if (pendingPage !== null) {
      const queuedPage = pendingPage;
      pendingPage = null;
      queueRender(queuedPage);
    }
  };

  try {
    if (downloadLink) {
      downloadLink.href = source;
    }

    setStatus("Carregando PDF...");
    pdf = await pdfjsLib.getDocument(source).promise;
    setControls();
    createReaderControls();
    setupSpeech();
    await renderPage(pageNumber);

    if (previousButton) {
      previousButton.addEventListener("click", () => {
        if (pageNumber > 1) {
          queueRender(pageNumber - 1);
        }
      });
    }

    if (nextButton) {
      nextButton.addEventListener("click", () => {
        if (pdf && pageNumber < pdf.numPages) {
          queueRender(pageNumber + 1);
        }
      });
    }

    let resizeTimer = null;
    window.addEventListener("resize", () => {
      clearTimeout(resizeTimer);
      resizeTimer = setTimeout(() => queueRender(pageNumber), 180);
    });
  } catch {
    setStatus("Não foi possível carregar o PDF dentro da página neste dispositivo. Use o botão Baixar PDF.");
  }
};

viewers.forEach((viewer) => {
  renderViewer(viewer);
});
