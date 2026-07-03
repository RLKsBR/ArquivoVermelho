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
      <button class="button" type="button" data-speech-play>Ouvir capitulo</button>
      <button class="button secondary" type="button" data-speech-pause>Pausar</button>
      <button class="button secondary" type="button" data-speech-resume>Continuar</button>
      <button class="button secondary" type="button" data-speech-stop>Parar</button>
      <p class="speech-status" data-speech-status>O audio usa a voz disponivel no seu navegador ou celular.</p>
    `;

    viewer.insertBefore(controls, viewer.firstChild);
  };

  const splitText = (text) => {
    const cleanText = text.replace(/\s+/g, " ").trim();
    const parts = cleanText.match(/.{1,2400}(?:[.!?;:]|\s|$)/g) || [];
    return parts.map((part) => part.trim()).filter(Boolean);
  };

  const extractPdfText = async () => {
    if (extractedText) {
      return extractedText;
    }

    setSpeechStatus("Preparando texto do capitulo...");
    const pages = [];

    for (let i = 1; i <= pdf.numPages; i += 1) {
      const page = await pdf.getPage(i);
      const content = await page.getTextContent();
      const pageText = content.items
        .map((item) => item.str)
        .join(" ")
        .replace(/\s+/g, " ")
        .trim();

      if (pageText) {
        pages.push(pageText);
      }
    }

    extractedText = pages.join("\n\n");
    speechChunks = splitText(extractedText);
    return extractedText;
  };

  const speakChunk = (runId) => {
    if (runId !== speechRunId) {
      return;
    }

    if (!speechChunks.length || speechIndex >= speechChunks.length) {
      currentUtterance = null;
      setSpeechStatus("Leitura concluida.");
      return;
    }

    currentUtterance = new SpeechSynthesisUtterance(speechChunks[speechIndex]);
    currentUtterance.lang = document.documentElement.lang || "pt-BR";
    currentUtterance.rate = 0.95;
    currentUtterance.pitch = 1;

    currentUtterance.onend = () => {
      if (runId !== speechRunId) {
        return;
      }

      speechIndex += 1;
      setSpeechStatus(`Lendo parte ${Math.min(speechIndex + 1, speechChunks.length)} de ${speechChunks.length}.`);
      speakChunk(runId);
    };

    currentUtterance.onerror = () => {
      setSpeechStatus("Nao foi possivel continuar a leitura em voz alta neste dispositivo.");
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

    if (!("speechSynthesis" in window) || !("SpeechSynthesisUtterance" in window)) {
      [playButton, pauseButton, resumeButton, stopButton].forEach((button) => {
        if (button) {
          button.disabled = true;
        }
      });
      setSpeechStatus("Este navegador nao oferece leitura em voz alta.");
      return;
    }

    if (playButton) {
      playButton.addEventListener("click", async () => {
        speechRunId += 1;
        const runId = speechRunId;
        window.speechSynthesis.cancel();
        speechIndex = 0;
        await extractPdfText();

        if (runId !== speechRunId) {
          return;
        }

        if (!speechChunks.length) {
          setSpeechStatus("Nao encontrei texto selecionavel neste PDF.");
          return;
        }

        speakChunk(runId);
      });
    }

    if (pauseButton) {
      pauseButton.addEventListener("click", () => {
        window.speechSynthesis.pause();
        setSpeechStatus("Leitura pausada.");
      });
    }

    if (resumeButton) {
      resumeButton.addEventListener("click", () => {
        window.speechSynthesis.resume();
        setSpeechStatus("Leitura retomada.");
      });
    }

    if (stopButton) {
      stopButton.addEventListener("click", () => {
        speechRunId += 1;
        window.speechSynthesis.cancel();
        currentUtterance = null;
        setSpeechStatus("Leitura parada.");
      });
    }

    window.addEventListener("beforeunload", () => {
      speechRunId += 1;
      window.speechSynthesis.cancel();
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
    setStatus("Carregando pagina...");

    const page = await pdf.getPage(pageNumber);
    const baseViewport = page.getViewport({ scale: 1 });
    const canvasWrap = viewer.querySelector(".pdf-canvas-wrap") || viewer;
    const availableWidth = Math.max(280, canvasWrap.clientWidth - 2);
    const cssScale = availableWidth / baseViewport.width;
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
    setStatus("Nao foi possivel carregar o PDF dentro da pagina neste dispositivo. Use o botao Baixar PDF.");
  }
};

viewers.forEach((viewer) => {
  renderViewer(viewer);
});
