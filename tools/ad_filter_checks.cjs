// Lightweight DOM verification; runs in Node, without a browser or Android emulator.
const { parseHTML } = require('linkedom');
const { readFileSync, writeFileSync } = require('node:fs');
const { resolve } = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const { createHash } = require('node:crypto');

const template = readFileSync(resolve(__dirname, '../app/src/main/assets/ad-filter.js'), 'utf8');
const results = [];
function fixture({ host = 'taiav.com', blocked = ['jads.co', 'pemsrv.com'], allowed = [] } = {}) {
  const dom = parseHTML(`<!doctype html><html><head></head><body>
    <div class="tai-wrapper-mobile"><a href="https://poweredby.jads.co/ad">ad</a></div>
    <div id="player"><video src="blob:https://taiav.com/video"></video></div>
    <iframe id="adframe" src="https://a.pemsrv.com/ad"></iframe>
    <iframe id="realplayer" src="https://media.example/player"></iframe>
    <iframe id="challenge" src="https://challenges.cloudflare.com/check"></iframe>
    <a id="movie" href="https://taiav.com/cn/movie/123" target="_blank">video</a>
    <a id="ai" href="https://taiav.com/acs/ai">feature</a>
  </body></html>`);
  const observers = [];
  const document = dom.document;
  const location = { hostname: host, href: `https://${host}/cn` };
  let nativeOpens = 0;
  const window = { open: () => { nativeOpens++; }, location };
  const context = vm.createContext({ window, location, document, URL, Element: dom.window.Element,
    setTimeout, MutationObserver: class extends dom.window.MutationObserver {
      constructor(callback) { super(callback); observers.push(this); }
    } });
  const script = template.replace('__TAIAV_AD_RULES__', JSON.stringify({ blocked, allowed }));
  const run = () => vm.runInContext(script, context);
  run();
  return { window, document, context, run, nativeOpens: () => nativeOpens,
    flush: () => new Promise(r => setTimeout(r, 100)),
    close: () => observers.forEach(o => o.disconnect()) };
}
async function check(name, action) { await action(); results.push(name); console.log('PASS', name); }

(async () => {
  await check('pop-under suppression before native window creation and reinjection', async () => {
    const f = fixture();
    try {
      assert.equal(f.window.open('https://a.pemsrv.com/ad'), null);
      assert.equal(f.nativeOpens(), 0);
      assert.equal(f.window.__taiavAdFilter.popups, 1);
      assert.throws(() => vm.runInContext("'use strict';window.open=()=>42", f.context));
      f.run();
      assert.equal(f.document.querySelectorAll('#__taiav_ad_style').length, 1);
    } finally { f.close(); }
  });
  await check('ad wrappers hidden while video and unrelated frames remain intact', async () => {
    const f = fixture();
    try {
      assert.equal(f.document.querySelector('.tai-wrapper-mobile').style.display, 'none');
      assert.equal(f.document.querySelector('#adframe').getAttribute('src'), 'about:blank');
      assert.equal(f.document.querySelector('#player').style.display, '');
      assert.equal(f.document.querySelector('video').getAttribute('src'), 'blob:https://taiav.com/video');
      assert.equal(f.document.querySelector('#realplayer').getAttribute('src'), 'https://media.example/player');
      assert.equal(f.document.querySelector('#challenge').getAttribute('src'), 'https://challenges.cloudflare.com/check');
    } finally { f.close(); }
  });
  await check('normal site navigation including same-origin non-ad paths', async () => {
    const f = fixture();
    try {
      assert.equal(f.document.querySelector('#movie').target, '_self');
      const event = new f.document.defaultView.Event('click', { bubbles: true, cancelable: true });
      f.document.querySelector('#ai').dispatchEvent(event);
      assert.equal(event.defaultPrevented, false);
    } finally { f.close(); }
  });
  await check('dynamic banners and ad links filtered without repeated mutation loops', async () => {
    const f = fixture();
    try {
      const ad = f.document.createElement('a');
      ad.href = 'https://child.pemsrv.com/banner'; ad.textContent = 'ad';
      f.document.body.appendChild(ad);
      const event = new f.document.defaultView.Event('click', { bubbles: true, cancelable: true });
      ad.dispatchEvent(event);
      assert.equal(event.defaultPrevented, true);
      await f.flush();
      assert.equal(ad.style.display, 'none');
      const hidden = f.window.__taiavAdFilter.hidden;
      await f.flush();
      assert.equal(f.window.__taiavAdFilter.hidden, hidden);
    } finally { f.close(); }
  });
  await check('custom domain allowlist takes priority and respects domain boundaries', async () => {
    const f = fixture({ blocked: ['pemsrv.com', 'ads.example'], allowed: ['a.pemsrv.com'] });
    try {
      assert.equal(f.document.querySelector('#adframe').getAttribute('src'), 'https://a.pemsrv.com/ad');
      const frame = f.document.createElement('iframe'); frame.src = 'https://cdn.ads.example/banner';
      const other = f.document.createElement('iframe'); other.src = 'https://ads.example.evil/player';
      f.document.body.append(frame, other);
      await f.flush();
      assert.equal(frame.getAttribute('src'), 'about:blank');
      assert.equal(other.getAttribute('src'), 'https://ads.example.evil/player');
    } finally { f.close(); }
  });
  await check('script restricted to the site origin', async () => {
    const f = fixture({ host: 'external.example' });
    try {
      assert.equal(f.window.__taiavAdFilter, undefined);
      assert.equal(f.document.querySelector('#__taiav_ad_style'), null);
      f.window.open('https://external.example/link');
      assert.equal(f.nativeOpens(), 1);
    } finally { f.close(); }
  });
  writeFileSync(resolve(__dirname, '../artifacts/ad-filter-dom.json'), JSON.stringify({
    success: true, runtime: process.version, checks: results, androidEmulatorUsed: false,
    scriptSha256: createHash('sha256').update(template).digest('hex'),
  }, null, 2) + '\n');
  console.log('ALL AD FILTER DOM CHECKS PASSED');
})().catch(error => { console.error(error); process.exitCode = 1; });
