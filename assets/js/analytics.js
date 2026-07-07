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

  const googleTag = document.createElement('script');
  googleTag.async = true;
  googleTag.src = `https://www.googletagmanager.com/gtag/js?id=${encodeURIComponent(measurementId)}`;
  document.head.append(googleTag);

  window.gtag('js', new Date());
  window.gtag('config', measurementId);
})();
