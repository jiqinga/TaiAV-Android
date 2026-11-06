const { parseHTML } = require('linkedom');
const { readFileSync, writeFileSync } = require('node:fs');
const { resolve } = require('node:path');
const { createHash } = require('node:crypto');
const vm = require('node:vm');
const assert = require('node:assert/strict');

const template = readFileSync(resolve(__dirname, '../app/src/main/assets/media-monitor.js'), 'utf8');
const checks = [];
function fixture({ host = 'taiav.com', iframe = false } = {}) {
  const dom = parseHTML('<html><head><title>Fixture movie</title></head><body><div class="movie-poster"><video class="hidden-video" id="preview"></video></div><div id="player"><video id="main"></video></div></body></html>');
  const messages = [], observers = [], timers = new Set(), listeners = new Map();
  let clock = 10000, playCalls = 0;
  function prepare(video, position = 0) {
    Object.assign(video, { duration: 120, currentTime: position, ended: false, paused: true, src: 'https://media.example/video.m3u8' });
    Object.defineProperty(video, 'clientWidth', { value: 640, configurable: true });
    video.pause = () => { video.paused = true; video.dispatchEvent(new dom.window.Event('pause')); };
    video.play = () => { playCalls++; video.paused = false; video.dispatchEvent(new dom.window.Event('playing')); };
    return video;
  }
  const video = prepare(dom.document.querySelector('#main'));
  prepare(dom.document.querySelector('#preview'), 90);
  const performance = { getEntriesByType: () => [
    { name: 'https://img.example/poster.jpg', initiatorType: 'img' },
    { name: 'https://media.example/master.m3u8?token=secret', initiatorType: 'fetch' },
  ] };
  const location = { hostname: host, href: `https://${host}/cn/movie/fixture` };
  const window = { location, performance, TaiavPageEvents: { postMessage: text => messages.push(JSON.parse(text)) },
    addEventListener: (type, callback) => listeners.set(type, callback) };
  window.top = iframe ? {} : window;
  const context = vm.createContext({ window, location, document: dom.document, performance,
    Date: { now: () => clock }, URL,
    setTimeout: (fn, delay) => { const timer = setTimeout(() => { timers.delete(timer); fn(); }, delay); timers.add(timer); return timer; },
    MutationObserver: class extends dom.window.MutationObserver { constructor(callback) { super(callback); observers.push(this); } },
  });
  const run = () => vm.runInContext(template, context);
  run();
  return { document: dom.document, window, video, messages, listeners, run, prepare,
    event: (name, element = video) => element.dispatchEvent(new dom.window.Event(name)),
    advance: ms => { clock += ms; }, playCalls: () => playCalls,
    flush: () => new Promise(r => setTimeout(r, 180)),
    close: () => { observers.forEach(o => o.disconnect()); timers.forEach(clearTimeout); } };
}
async function check(name, action) { await action(); checks.push(name); console.log('PASS', name); }

(async () => {
  await check('native sources preserve signed manifests, exclude blob and segment URLs and ignore preview video', async () => {
    const f = fixture();
    try {
      f.video.src = 'blob:https://taiav.com/player';
      f.document.querySelector('#preview').src = 'https://preview.example/trailer.mp4';
      const sources = Array.from(f.window.__taiavMedia.sources());
      assert.deepEqual(sources, ['https://media.example/master.m3u8?token=secret']);
      f.video.src = 'https://cdn.example/movie.mp4?signature=a%2Fb';
      assert.equal(f.window.__taiavMedia.sources().at(-1), f.video.src);
    } finally { f.close(); }
  });
  await check('main player selected, initial preview and unplayed state do not save progress', async () => {
    const f = fixture();
    try {
      assert.equal(f.window.__taiavMedia.snapshot().position, 0);
      assert.equal(f.window.__taiavMedia.snapshot().active, false);
      assert.equal(f.messages.filter(x => x.kind === 'progress').length, 0);
      assert.equal(f.messages.filter(x => x.kind === 'ready').length, 1);
      const count = f.messages.length; f.run(); assert.equal(f.messages.length, count);
    } finally { f.close(); }
  });
  await check('playing progress is throttled, pause seek and ended are saved immediately', async () => {
    const f = fixture();
    try {
      f.event('playing'); f.video.currentTime = 25; f.event('timeupdate');
      assert.equal(f.messages.at(-1).position, 25);
      const count = f.messages.length;
      f.advance(1000); f.video.currentTime = 26; f.event('timeupdate');
      assert.equal(f.messages.length, count);
      f.advance(5001); f.video.currentTime = 31; f.event('timeupdate');
      assert.equal(f.messages.at(-1).position, 31);
      f.video.currentTime = 60; f.event('seeked'); assert.equal(f.messages.at(-1).position, 60);
      f.event('pause'); assert.equal(f.messages.at(-1).active, true);
      f.video.currentTime = 120; f.video.ended = true; f.event('ended'); assert.equal(f.messages.at(-1).ended, true);
    } finally { f.close(); }
  });
  await check('resume seeks with finite bounds and preserves the user playback gesture', async () => {
    const f = fixture();
    try {
      assert.equal(f.window.__taiavMedia.resume(40), true); assert.equal(f.video.currentTime, 40);
      assert.equal(f.window.__taiavMedia.resume(Infinity), false);
      f.window.__taiavMedia.resume(999); assert.equal(f.video.currentTime, 119);
      f.window.__taiavMedia.resume(-10); assert.equal(f.video.currentTime, 0);
      assert.equal(f.playCalls(), 0);
    } finally { f.close(); }
  });
  await check('replacement player takes over and stale player events are ignored', async () => {
    const f = fixture();
    try {
      f.event('playing'); f.video.currentTime = 20;
      const replacement = f.prepare(f.document.createElement('video'), 0);
      f.video.replaceWith(replacement); await f.flush();
      assert.equal(f.window.__taiavMedia.snapshot().position, 0);
      const count = f.messages.length; f.event('playing'); f.event('timeupdate');
      assert.equal(f.messages.length, count);
      assert.equal(f.window.__taiavMedia.snapshot().active, false);
    } finally { f.close(); }
  });
  await check('resource observations deduplicate and page exit flushes progress', async () => {
    const f = fixture();
    try {
      assert.equal(f.messages.filter(x => x.kind === 'resource').length, 2);
      f.window.__taiavMedia.flush(); assert.equal(f.messages.filter(x => x.kind === 'resource').length, 2);
      f.event('playing'); f.video.currentTime = 42;
      f.listeners.get('pagehide')(); assert.equal(f.messages.at(-1).position, 42);
    } finally { f.close(); }
  });
  await check('observation bridge is restricted to top-level site documents', async () => {
    for (const options of [{ host: 'external.example' }, { iframe: true }]) {
      const f = fixture(options);
      try { assert.equal(f.window.__taiavMedia, undefined); assert.equal(f.messages.length, 0); }
      finally { f.close(); }
    }
  });
  writeFileSync(resolve(__dirname, '../artifacts/media-monitor-dom.json'), JSON.stringify({ success: true,
    runtime: process.version, checks, androidEmulatorUsed: false,
    scriptSha256: createHash('sha256').update(template).digest('hex'),
  }, null, 2) + '\n');
  console.log('ALL MEDIA MONITOR DOM CHECKS PASSED');
})().catch(error => { console.error(error); process.exitCode = 1; });
