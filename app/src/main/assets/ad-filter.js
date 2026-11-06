(() => {
  'use strict';
  const host = location.hostname.toLowerCase();
  if (!(host === 'taiav.com' || host.endsWith('.taiav.com')) || window.__taiavAdFilter) return;
  const rules = __TAIAV_AD_RULES__;
  const matches = (h, d) => h === d || h.endsWith('.' + d);
  const blocked = value => {
    try {
      const u = new URL(value, location.href), h = u.hostname.toLowerCase().replace(/\.$/, '');
      if (!['https:', 'http:', 'ws:', 'wss:'].includes(u.protocol)) return false;
      if (rules.allowed.some(d => matches(h, d))) return false;
      return rules.blocked.some(d => matches(h, d)) ||
        (matches(h, 'taiav.com') && /^\/acs\/(desktop|phone)\/\d+\/\d+\.js$/i.test(u.pathname));
    } catch (_) { return false; }
  };
  const stats = { hidden: 0, popups: 0 };
  Object.defineProperty(window, '__taiavAdFilter', { value: stats });
  // Native onCreateWindow is a second guard. Blocking here also preserves the
  // user's activation for play/fullscreen instead of consuming it in a pop-under.
  const suppressPopup = () => { stats.popups++; return null; };
  try { Object.defineProperty(window, 'open', { value: suppressPopup, writable: false, configurable: false }); }
  catch (_) { window.open = suppressPopup; }

  const selectors = '.tai-wrapper-desktop,.tai-wrapper-mobile,.tai-box,.tai-box-mobile,ins.adsbyjuicy,[data-adxad-place]';
  const style = document.createElement('style');
  style.id = '__taiav_ad_style';
  style.textContent = selectors + '{display:none!important;height:0!important;min-height:0!important;margin:0!important;padding:0!important;pointer-events:none!important}';
  const hidden = new WeakSet();
  function hide(element) {
    if (hidden.has(element)) return;
    hidden.add(element); stats.hidden++;
    element.style.setProperty('display', 'none', 'important');
    element.style.setProperty('pointer-events', 'none', 'important');
    if (element.tagName === 'IFRAME') element.setAttribute('src', 'about:blank');
  }
  function clean() {
    if (!style.isConnected && document.documentElement) document.documentElement.appendChild(style);
    document.querySelectorAll(selectors).forEach(hide);
    document.querySelectorAll('iframe[src],a[href]').forEach(element => {
      if (blocked(element.getAttribute(element.tagName === 'A' ? 'href' : 'src'))) hide(element);
    });
    // Preserve normal site links that originally opened a new WebView window.
    document.querySelectorAll('a[target="_blank"]').forEach(link => {
      try {
        const u = new URL(link.href, location.href);
        if (u.protocol === 'https:' && matches(u.hostname, 'taiav.com')) link.target = '_self';
      } catch (_) { /* leave unsupported URLs for WebView's navigation policy */ }
    });
  }
  let scheduled = false;
  const observer = new MutationObserver(() => {
    if (scheduled) return;
    scheduled = true;
    setTimeout(() => { scheduled = false; clean(); }, 60);
  });
  observer.observe(document, { childList: true, subtree: true, attributes: true,
    attributeFilter: ['src', 'href', 'class', 'id', 'target', 'data-adxad-place'] });
  document.addEventListener('click', event => {
    const link = event.target instanceof Element ? event.target.closest('a[href]') : null;
    if (!link) return;
    if (blocked(link.href)) { event.preventDefault(); event.stopImmediatePropagation(); return; }
    try {
      const u = new URL(link.href, location.href);
      if (link.target === '_blank' && u.protocol === 'https:' && matches(u.hostname, 'taiav.com')) link.target = '_self';
    } catch (_) { /* normal navigation handles invalid URLs */ }
  }, true);
  clean();
  document.addEventListener('DOMContentLoaded', clean, { once: true });
})();
