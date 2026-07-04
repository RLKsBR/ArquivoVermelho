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
      const label = rating === 0 ? "0 estrela" : `${rating} ${rating === 1 ? "estrela" : "estrelas"}`;
      message.textContent = `Sua nota (${label}) foi salva neste dispositivo.`;
    }
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
