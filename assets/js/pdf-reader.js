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
