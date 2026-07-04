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
