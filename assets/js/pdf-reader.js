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
  let zoom = 1;

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
  };

  const setSpeechStatus = (message) => {
    const speechStatus = viewer.querySelector("[data-speech-status]");
    if (speechStatus) {
      speechStatus.textContent = message;
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
      <button class="button" type="button" data-speech-play>Ouvir capítulo</button>
      <button class="button secondary" type="button" data-speech-pause>Pausar</button>
      <button class="button secondary" type="button" data-speech-resume>Continuar</button>
      <button class="button secondary" type="button" data-speech-stop>Parar</button>
      <p class="speech-status" data-speech-status>O áudio usa a voz disponível no seu navegador ou celular.</p>
    `;

    viewer.insertBefore(controls, viewer.firstChild);
  };

  const createReaderControls = () => {
    const toolbar = viewer.querySelector(".pdf-toolbar");
    const panel = viewer.closest(".pdf-reader-panel") || viewer;

    if (!toolbar || toolbar.querySelector("[data-pdf-zoom-in]")) {
      return;
    }

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

    toolbar.append(zoomOutButton, zoomInButton, fullscreenButton);

    zoomOutButton.addEventListener("click", () => {
      zoom = Math.max(0.75, Number((zoom - 0.15).toFixed(2)));
      queueRender(pageNumber);
    });

    zoomInButton.addEventListener("click", () => {
      zoom = Math.min(2.4, Number((zoom + 0.15).toFixed(2)));
      queueRender(pageNumber);
    });

    fullscreenButton.addEventListener("click", async () => {
      try {
        if (!panel.requestFullscreen) {
          panel.classList.toggle("is-fullscreen");
          fullscreenButton.textContent = panel.classList.contains("is-fullscreen") ? "Sair da tela cheia" : "Tela cheia";
          queueRender(pageNumber);
          return;
        }

        if (document.fullscreenElement) {
          await document.exitFullscreen();
          return;
        }

        await panel.requestFullscreen();
      } catch {
        panel.classList.toggle("is-fullscreen");
        fullscreenButton.textContent = panel.classList.contains("is-fullscreen") ? "Sair da tela cheia" : "Tela cheia";
        queueRender(pageNumber);
      }
    });

    document.addEventListener("fullscreenchange", () => {
      fullscreenButton.textContent = document.fullscreenElement ? "Sair da tela cheia" : "Tela cheia";
      queueRender(pageNumber);
    });
  };

  const getPauseAfterSegment = (segment, isEndOfLine) => {
    const trimmed = segment.trim();
    let pause = isEndOfLine ? 420 : 0;

    if (/[.!?]$/.test(trimmed)) {
      pause = Math.max(pause, 430);
    } else if (/[:;]$/.test(trimmed)) {
      pause = Math.max(pause, 340);
    } else if (/[,—–-]$/.test(trimmed)) {
      pause = Math.max(pause, 210);
    }

    return pause;
  };

  const pushSpeechSegment = (chunks, text, pause) => {
    const cleanText = text.replace(/\s+/g, " ").trim();

    if (!cleanText) {
      if (chunks.length) {
        chunks[chunks.length - 1].pause = Math.max(chunks[chunks.length - 1].pause, pause);
      }
      return;
    }

    if (cleanText.length <= 1400) {
      chunks.push({ text: cleanText, pause });
      return;
    }

    const words = cleanText.split(" ");
    let current = "";

    words.forEach((word) => {
      const next = current ? `${current} ${word}` : word;
      if (next.length > 1200) {
        chunks.push({ text: current, pause: 180 });
        current = word;
        return;
      }

      current = next;
    });

    if (current) {
      chunks.push({ text: current, pause });
    }
  };

  const splitText = (text) => {
    const chunks = [];
    const lines = text.replace(/\r/g, "").split("\n");

    lines.forEach((line) => {
      const cleanLine = line.trim();

      if (!cleanLine) {
        if (chunks.length) {
          chunks[chunks.length - 1].pause = Math.max(chunks[chunks.length - 1].pause, 780);
        }
        return;
      }

      const segments = cleanLine.match(/[^,;.!?:—–-]+[,;.!?:—–-]?/g) || [cleanLine];
      segments.forEach((segment, index) => {
        pushSpeechSegment(chunks, segment, getPauseAfterSegment(segment, index === segments.length - 1));
      });
    });

    return chunks;
  };

  const getPageText = (items) => {
    const lines = [];
    let currentLine = [];
    let currentY = null;

    items.forEach((item) => {
      const text = String(item.str || "").trim();
      if (!text) {
        return;
      }

      const transform = item.transform || [];
      const y = Number(transform[5] || 0);

      if (currentY !== null && Math.abs(y - currentY) > 5) {
        lines.push(currentLine.join(" ").replace(/\s+/g, " ").trim());
        currentLine = [];
      }

      currentY = y;
      currentLine.push(text);
    });

    if (currentLine.length) {
      lines.push(currentLine.join(" ").replace(/\s+/g, " ").trim());
    }

    return lines.filter(Boolean).join("\n");
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
      const pageText = getPageText(content.items);

      if (pageText) {
        pages.push(pageText);
      }
    }

    extractedText = pages.join("\n\n");
    speechChunks = splitText(extractedText);
    return extractedText;
  };

  const speakChunk = (runId) => {
    window.clearTimeout(speechTimer);

    if (runId !== speechRunId) {
      return;
    }

    if (!speechChunks.length || speechIndex >= speechChunks.length) {
      currentUtterance = null;
      setSpeechStatus("Leitura concluída.");
      return;
    }

    const chunk = speechChunks[speechIndex];
    currentUtterance = new SpeechSynthesisUtterance(chunk.text);
    currentUtterance.lang = document.documentElement.lang || "pt-BR";
    currentUtterance.rate = 0.95;
    currentUtterance.pitch = 1;

    currentUtterance.onend = () => {
      if (runId !== speechRunId) {
        return;
      }

      speechIndex += 1;
      setSpeechStatus(`Lendo parte ${Math.min(speechIndex + 1, speechChunks.length)} de ${speechChunks.length}.`);
      speechTimer = window.setTimeout(() => speakChunk(runId), chunk.pause || 0);
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
    const resumeButton = viewer.querySelector("[data-speech-resume]");
    const stopButton = viewer.querySelector("[data-speech-stop]");

    const nativeBridge = window.ArquivoVermelhoApp;
    const hasNativeSpeech = nativeBridge && typeof nativeBridge.speak === "function";
    const hasWebSpeech = "speechSynthesis" in window && "SpeechSynthesisUtterance" in window;

    if (!hasNativeSpeech && !hasWebSpeech) {
      [playButton, pauseButton, resumeButton, stopButton].forEach((button) => {
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

        if (hasNativeSpeech) {
          nativeBridge.speak(JSON.stringify(speechChunks));
          setSpeechStatus(`Leitura enviada ao app: ${speechChunks.length} partes.`);
          return;
        }

        speakChunk(runId);
      });
    }

    if (pauseButton) {
      pauseButton.addEventListener("click", () => {
        window.clearTimeout(speechTimer);
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

    if (resumeButton) {
      resumeButton.addEventListener("click", () => {
        if (hasNativeSpeech && typeof nativeBridge.resume === "function") {
          nativeBridge.resume();
          setSpeechStatus("Leitura retomada no app.");
          return;
        }

        if (hasWebSpeech) {
          window.speechSynthesis.resume();
        }
        setSpeechStatus("Leitura retomada.");
      });
    }

    if (stopButton) {
      stopButton.addEventListener("click", () => {
        speechRunId += 1;
        window.clearTimeout(speechTimer);
        if (hasNativeSpeech && typeof nativeBridge.stop === "function") {
          nativeBridge.stop();
          currentUtterance = null;
          setSpeechStatus("Leitura parada no app.");
          return;
        }

        if (hasWebSpeech) {
          window.speechSynthesis.cancel();
        }
        currentUtterance = null;
        setSpeechStatus("Leitura parada.");
      });
    }

    window.addEventListener("beforeunload", () => {
      speechRunId += 1;
      window.clearTimeout(speechTimer);
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
