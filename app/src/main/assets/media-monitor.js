(() => {
  'use strict';
  if (window.top !== window || window.__taiavMedia) return;
  const host = location.hostname.toLowerCase();
  if (!(host === 'taiav.com' || host.endsWith('.taiav.com'))) return;
  let video = null, lastSent = 0, active = false;
  function post(value) {
    try { if (window.TaiavPageEvents) window.TaiavPageEvents.postMessage(JSON.stringify(value)); } catch (_) {}
  }
  function snapshot() {
    if (!video || !Number.isFinite(video.duration) || video.duration < 10) return null;
    return { kind: 'progress', page: location.href, title: document.title,
      position: video.currentTime, duration: video.duration, ended: video.ended, active };
  }
  function emit(force = false) {
    const state = snapshot();
    if (state && active && (force || Date.now() - lastSent > 5000)) { lastSent = Date.now(); post(state); }
  }
  const ready = () => { if (video && Number.isFinite(video.duration) && video.duration >= 10) post({ kind: 'ready', page: location.href }); };
  function attach() {
    const candidates = Array.from(document.querySelectorAll('#player video,video'))
      .filter(v => !v.closest('.movie-poster') && !v.classList.contains('hidden-video'));
    const next = candidates.sort((a, b) => b.clientWidth - a.clientWidth)[0];
    if (!next || video === next) return;
    emit(true);
    video = next; active = false;
    const current = callback => () => { if (video === next) callback(); };
    next.addEventListener('loadedmetadata', current(ready));
    next.addEventListener('durationchange', current(ready));
    next.addEventListener('playing', current(() => { active = true; }));
    next.addEventListener('timeupdate', current(() => emit()));
    ['pause', 'seeked', 'ended'].forEach(name => next.addEventListener(name, current(() => emit(true))));
    next.addEventListener('error', current(() => post({ kind: 'resource', page: location.href,
      url: next.currentSrc || next.src, resource: 'video', failed: true, detail: '媒体错误 ' + (next.error ? next.error.code : 0) })));
    ready();
  }
  function resource(url, type, failed = false) {
    if (url && /^https:\/\//i.test(url)) post({ kind: 'resource', page: location.href,
      url, resource: type, failed, detail: failed ? '页面资源加载失败' : '页面资源观察' });
  }
  const seen = new Set();
  function observeResources() {
    if (!window.performance || !performance.getEntriesByType) return;
    performance.getEntriesByType('resource').slice(-80).forEach(entry => {
      if (!seen.has(entry.name)) {
        if (seen.size >= 200) seen.delete(seen.values().next().value);
        seen.add(entry.name); resource(entry.name, entry.initiatorType);
      }
    });
  }
  let queued = false;
  const observer = new MutationObserver(() => {
    if (!queued) { queued = true; setTimeout(() => { queued = false; attach(); }, 120); }
  });
  observer.observe(document, { childList: true, subtree: true });
  window.addEventListener('error', event => {
    const el = event.target;
    if (el && el !== window) resource(el.currentSrc || el.src || el.href, el.tagName === 'IMG' ? 'image' : 'other', true);
  }, true);
  document.addEventListener('visibilitychange', () => { if (document.hidden) emit(true); });
  window.addEventListener('pagehide', () => { emit(true); observer.disconnect(); });
  const api = { snapshot, flush: () => { emit(true); observeResources(); },
    sources: () => {
      const direct = video ? [video.currentSrc, video.src, ...Array.from(video.querySelectorAll('source')).map(s => s.src)] : [];
      const resources = window.performance && performance.getEntriesByType ? performance.getEntriesByType('resource').slice(-100).map(e => e.name) : [];
      return Array.from(new Set([...resources, ...direct].filter(url => {
        try { const value = new URL(url); return value.protocol === 'https:' && /\.(m3u8|mpd|mp4|webm|mkv)$/i.test(value.pathname); } catch (_) { return false; }
      })));
    },
    resume: seconds => {
      if (!video || !Number.isFinite(seconds) || !Number.isFinite(video.duration)) return false;
      video.currentTime = Math.max(0, Math.min(seconds, video.duration - 1)); return true;
    }, pause: () => { if (video) video.pause(); } };
  Object.defineProperty(window, '__taiavMedia', { value: api });
  attach(); observeResources();
  document.addEventListener('DOMContentLoaded', () => { attach(); observeResources(); }, { once: true });
})();
