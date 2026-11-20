// Loaded synchronously in <head> to apply the saved theme before first paint (no flash).
(function () {
  try {
    var t = localStorage.getItem('swapp.theme');
    if (t === 'light' || t === 'dark') document.documentElement.setAttribute('data-theme', t);
  } catch (e) { /* storage unavailable */ }
})();
