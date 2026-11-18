// Injected at document start on every page (document-start scripts bypass page CSP).
// UI v2: navigation + layout are server-side (ui/footer.html). This script only
// does what needs native help:
// 1) System-bar insets -> CSS vars --iv-sb/-l/-r/-b (edge-to-edge, targetSdk 35).
// 2) Standard Cast button in the player toolbar (touch: top-right bar with CC/speed/quality,
//    rightmost; desktop: bottom bar left of fullscreen) -> device picker
//    sheet (devices discovered on the Wi-Fi by the cast bridge on the laptop)
//    -> native bridge -> POST http://<bridge>:8300/cast/play {videoId, device}.
//    Native HTTP instead of fetch() dodges mixed-content (https page -> http
//    bridge) + CORS. Results come back via window.__ivxCast.on*().
(function () {
  'use strict';
  // Chrome may run document-start scripts before <html> exists: wait for it (one microtask-ish hop)
  function main() {

  document.documentElement.classList.add('ivx-js');


  /* ---------- 0. seamless page transitions (ivx.js v17+) ----------
   * The page's own shell (inline CSS + tab bar + skeleton) is rendered from the Invidious banner,
   * right after the navbar. On a slow frame the WebView could paint once before the parser gets
   * there (a flat stock-grey frame between two skeletons). Document-start beats that: ivx.js keeps
   * a copy of the shell in sessionStorage (ivxShell) and the kind of screen being opened (ivxTx);
   * we paint that skeleton before a single byte of the page is parsed. ivx.js removes #ivx-pre as
   * soon as the real shell exists (identical markup, so nothing visibly changes). */
  (function preShell() {
    try {
      if (!/^https?:$/.test(location.protocol) || window.top !== window) return;
      var sh = JSON.parse(sessionStorage.getItem('ivxShell') || 'null');
      if (!sh || !sh.css || !sh.html) return;
      var tx = JSON.parse(sessionStorage.getItem('ivxTx') || 'null');
      sessionStorage.removeItem('ivxTx');
      var here = location.pathname + location.search;
      var k = tx && tx.u === here && Date.now() - tx.t < 15000 ? tx.k : '';
      if (!k) {
        var p = location.pathname;
        k = p === '/watch' ? 'watch' : (p === '/' || p === '/feed/trending' || p === '/feed/popular') ? 'home' :
            p === '/feed/subscriptions' ? 'subs' : p === '/search' ? 'search' :
            /^\/(channel|c|user)\//.test(p) ? 'channel' : /^\/(feed\/(history|playlists|channel)|playlist)/.test(p) ? 'feed' : 'page';
      }
      var de = document.documentElement;
      var st = document.createElement('style');
      st.id = 'ivx-pre-css';
      st.textContent = sh.css;
      var w = document.createElement('div');
      w.id = 'ivx-pre';
      w.innerHTML = sh.html;
      var sk = w.querySelector('#ivx-sk');
      if (sk) { sk.className = 'tx'; sk.setAttribute('data-k', k); }
      de.classList.add('ivx-tx');
      if (k !== 'watch') de.classList.add('ivx-txc');
      if (k === 'search') de.classList.add('ivx-sp');
      de.setAttribute('data-ivx-tab', k === 'subs' ? 'subs' : (k === 'feed' || k === 'page' || k === 'you') ? 'you' : 'home');
      de.appendChild(st);
      de.appendChild(w);
    } catch (e) { /* never block the page */ }
  })();

  /* ---------- 1. system-bar insets -> CSS vars ---------- */
  function syncInsets() {
    try {
      var i = JSON.parse(window.InvidiousInsets.get());
      var s = document.documentElement.style;
      s.setProperty('--iv-sb', i.t + 'px');
      s.setProperty('--iv-l', i.l + 'px');
      s.setProperty('--iv-r', i.r + 'px');
      s.setProperty('--iv-b', i.b + 'px');
      // native mini player bar height (0 when closed) -> page keeps that much free at the bottom
      if (window.InvidiousPlayer && window.InvidiousPlayer.miniHeight)
        s.setProperty('--ivx-mini', window.InvidiousPlayer.miniHeight() + 'px');
    } catch (e) { /* PWA/desktop: vars stay unset, CSS falls back to 0 */ }
  }
  syncInsets();

  /* ---------- 2. cast ---------- */
  var KEY = 'ivxCastDevice';           // device we're casting to (survives navigation)
  var SVG_NS = 'http://www.w3.org/2000/svg';
  // Material "cast" / "cast_connected" / "tv" glyphs (24px grid)
  var P_CAST = 'M21 3H3c-1.1 0-2 .9-2 2v3h2V5h18v14h-7v2h7c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zM1 18v3h3c0-1.66-1.34-3-3-3zm0-4v2c2.76 0 5 2.24 5 5h2c0-3.87-3.13-7-7-7zm0-4v2c4.97 0 9 4.03 9 9h2c0-6.08-4.93-11-11-11z';
  var P_CONN = 'M1 18v3h3c0-1.66-1.34-3-3-3zm0-4v2c2.76 0 5 2.24 5 5h2c0-3.87-3.13-7-7-7zm18-7H5v1.63c3.96 1.28 7.09 4.41 8.37 8.37H19V7zM1 10v2c4.97 0 9 4.03 9 9h2c0-6.08-4.93-11-11-11zm20-7H3c-1.1 0-2 .9-2 2v3h2V5h18v14h-7v2h7c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2z';
  var P_TV = 'M21 3H3c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h5v2h8v-2h5c1.1 0 1.99-.9 1.99-2L23 5c0-1.1-.9-2-2-2zm0 14H3V5h18v12z';

  function hasNative() { return !!(window.InvidiousCast && window.InvidiousCast.devices); }

  function icon(path, size) {
    var svg = document.createElementNS(SVG_NS, 'svg');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('width', size);
    svg.setAttribute('height', size);
    svg.setAttribute('aria-hidden', 'true');
    var p = document.createElementNS(SVG_NS, 'path');
    p.setAttribute('d', path);
    p.setAttribute('fill', 'currentColor');
    svg.appendChild(p);
    return svg;
  }

  function videoId() {
    if (!/^\/watch/.test(location.pathname)) return null;
    try {
      var v = new URLSearchParams(location.search).get('v');
      return v && /^[A-Za-z0-9_-]{11}$/.test(v) ? v : null;
    } catch (e) { return null; }
  }
  function castingTo() { try { return sessionStorage.getItem(KEY); } catch (e) { return null; } }
  function setCastingTo(d) {
    try { if (d) sessionStorage.setItem(KEY, d); else sessionStorage.removeItem(KEY); } catch (e) {}
    refreshButton();
  }

  var CSS = '' +
    // in the control bar, just left of fullscreen (Invidious gives every control a flex order; fullscreen = 7)
    '.video-js .ivx-cast-control{cursor:pointer;flex:none;order:6;padding:0}' +
    '.video-js .mobile-operations-bar .ivx-cast-control{order:99}' +
    '.video-js .ivx-cast-control svg{display:block;margin:0 auto}' +
    '.video-js .ivx-cast-control.ivx-on{color:#6cb6ff}' +
    '#ivx-cast-sheet{position:fixed;inset:0;z-index:2147483647;display:flex;align-items:flex-end;justify-content:center;' +
      'background:rgba(0,0,0,.55);font:400 15px system-ui,Roboto,sans-serif;color:#f1f1f1;animation:ivxFade .15s ease}' +
    // NOTE: these class names are cast-specific ON PURPOSE. The page's own ivx.css defines a GLOBAL
    // .ivx-panel (position:fixed; top:40vh; transform:translateY(105%)) and .ivx-row for its bottom
    // sheets; reusing them here pushed the picker below the viewport (backdrop flashed, panel unseen).
    '#ivx-cast-sheet .ivx-cast-panel{position:static;transform:none;width:100%;max-width:480px;box-sizing:border-box;' +
      'background:#212121;border-radius:18px 18px 0 0;max-height:92vh;overflow-y:auto;' +
      'padding:8px 8px calc(16px + var(--iv-b,0px));box-shadow:0 -8px 30px rgba(0,0,0,.5)}' +
    '@media (min-width:600px){#ivx-cast-sheet{align-items:center}#ivx-cast-sheet .ivx-cast-panel{border-radius:18px;padding-bottom:12px}}' +
    '#ivx-cast-sheet .ivx-cast-grab{width:36px;height:4px;border-radius:2px;background:#555;margin:4px auto 10px}' +
    '#ivx-cast-sheet h2{margin:0;padding:6px 16px 4px;font-size:18px;font-weight:600;text-align:left}' +
    '#ivx-cast-sheet .ivx-cast-sub{padding:0 16px 10px;color:#aaa;font-size:13px;margin-left:0}' +
    '#ivx-cast-sheet .ivx-cast-row{display:flex;align-items:center;gap:16px;width:100%;min-height:56px;padding:0 16px;box-sizing:border-box;' +
      'border:0;border-radius:12px;background:none;color:inherit;font:inherit;text-align:left;cursor:pointer}' +
    '#ivx-cast-sheet button.ivx-cast-row:hover,#ivx-cast-sheet button.ivx-cast-row:active{background:#2e2e2e}' +
    '#ivx-cast-sheet div.ivx-cast-row{cursor:default}' +
    '#ivx-cast-sheet .ivx-cast-row svg{flex:0 0 24px;width:24px;height:24px;fill:currentColor}' +
    '#ivx-cast-sheet .ivx-cast-row .ivx-name{flex:1;min-width:0}' +
    '#ivx-cast-sheet .ivx-cast-row .ivx-name b{display:block;font-weight:500}' +
    '#ivx-cast-sheet .ivx-cast-row .ivx-name small{display:block;color:#aaa;font-size:12.5px;margin-top:2px}' +
    '#ivx-cast-sheet .ivx-msg{padding:18px 16px 8px;color:#aaa;font-size:14px;line-height:1.5}' +
    '#ivx-cast-sheet .ivx-actions{display:flex;gap:8px;justify-content:flex-end;padding:10px 8px 0}' +
    '#ivx-cast-sheet .ivx-btn{height:40px;padding:0 18px;border:0;border-radius:999px;font:500 14px system-ui,Roboto,sans-serif;cursor:pointer;' +
      'background:#2e2e2e;color:#f1f1f1}' +
    '#ivx-cast-sheet .ivx-btn.ivx-primary{background:#f1f1f1;color:#0f0f0f}' +
    '#ivx-cast-sheet .ivx-cast-spin{width:22px;height:22px;box-sizing:border-box;border:2.5px solid #555;border-top-color:#f1f1f1;border-radius:50%;' +
      'animation:ivxSpin .8s linear infinite;flex:none}' +
    '@keyframes ivxSpin{to{transform:rotate(360deg)}}' +
    '@keyframes ivxFade{from{opacity:0}}' +
    '#hermes-cast-btn{display:none!important}';

  function ensureStyle() {
    if (document.getElementById('ivx-cast-style') || !document.head) return;
    var st = document.createElement('style');
    st.id = 'ivx-cast-style';
    st.textContent = CSS;
    document.head.appendChild(st);
  }

  /* --- the button in the control bar --- */
  function refreshButton() {
    var b = document.querySelector('.ivx-cast-control');
    if (!b) return;
    var dev = castingTo();
    b.classList.toggle('ivx-on', !!dev);
    b.title = dev ? 'Casting to ' + dev : 'Cast';
    b.replaceChild(icon(dev ? P_CONN : P_CAST, 22), b.firstChild);
  }

  function ensureButton() {
    if (!hasNative() || !videoId()) return;
    // Touch devices: Invidious moves CC/speed/quality into a top-right bar (.mobile-operations-bar)
    // -> cast goes at its right end. Otherwise: bottom bar, just left of fullscreen.
    var ops = document.querySelector('.video-js .mobile-operations-bar');
    var bar = ops || document.querySelector('.video-js .vjs-control-bar');
    if (!bar) return;
    var existing = document.querySelector('.ivx-cast-control');
    if (existing) {
      if (existing.parentNode !== bar) bar.appendChild(existing);   // ops bar appeared later: move it
      return;
    }
    ensureStyle();
    var b = document.createElement('button');
    b.type = 'button';
    b.className = 'ivx-cast-control vjs-control vjs-button';
    b.setAttribute('aria-label', 'Cast');
    b.appendChild(icon(P_CAST, 22));
    var t = document.createElement('span');
    t.className = 'vjs-control-text';
    t.textContent = 'Cast';
    b.appendChild(t);
    // keep taps from reaching video.js (mobile-ui would treat them as play/seek gestures)
    ['touchstart', 'touchend', 'mousedown', 'mouseup'].forEach(function (t) {
      b.addEventListener(t, function (ev) { ev.stopPropagation(); });
    });
    b.addEventListener('click', function (ev) { ev.preventDefault(); ev.stopPropagation(); openSheet(); });
    if (ops) bar.appendChild(b);
    else bar.insertBefore(b, bar.querySelector('.vjs-fullscreen-control'));
    refreshButton();
  }

  /* --- the device sheet --- */
  var sheet = null;

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }

  function closeSheet() { if (sheet) { sheet.remove(); sheet = null; } }

  function panel() {
    closeSheet();
    ensureStyle();
    sheet = el('div');
    sheet.id = 'ivx-cast-sheet';
    sheet.addEventListener('click', function (e) { if (e.target === sheet) closeSheet(); });
    var p = el('div', 'ivx-cast-panel');
    p.setAttribute('role', 'dialog');
    p.appendChild(el('div', 'ivx-cast-grab'));
    sheet.appendChild(p);
    document.body.appendChild(sheet);
    return p;
  }

  function actions(p, buttons) {
    var a = el('div', 'ivx-actions');
    buttons.forEach(function (bd) {
      var b = el('button', 'ivx-btn' + (bd.primary ? ' ivx-primary' : ''), bd.label);
      b.type = 'button';
      b.addEventListener('click', bd.onClick);
      a.appendChild(b);
    });
    p.appendChild(a);
  }

  function busyRow(p, title, sub) {
    var row = el('div', 'ivx-cast-row');
    row.appendChild(el('div', 'ivx-cast-spin'));
    var n = el('div', 'ivx-name');
    n.appendChild(el('b', null, title));
    if (sub) n.appendChild(el('small', null, sub));
    row.appendChild(n);
    p.appendChild(row);
  }

  function openSheet() {
    // a fixed overlay can't show above a fullscreen element -> leave fullscreen first
    try { if (document.fullscreenElement) document.exitFullscreen(); } catch (e) {}
    var dev = castingTo();
    if (dev) { connectedSheet(dev); return; }
    var p = panel();
    p.appendChild(el('h2', null, 'Cast to'));
    busyRow(p, 'Looking for devices\u2026', 'On this Wi\u2011Fi network');
    actions(p, [{ label: 'Cancel', onClick: closeSheet }]);
    window.InvidiousCast.devices();
  }

  function deviceRow(name, sub, path) {
    var r = el('button', 'ivx-cast-row');
    r.type = 'button';
    r.appendChild(icon(path, 24));
    var n = el('div', 'ivx-name');
    n.appendChild(el('b', null, name));
    n.appendChild(el('small', null, sub));
    r.appendChild(n);
    return r;
  }

  function connectedSheet(dev) {
    var p = panel();
    p.appendChild(el('h2', null, 'Casting to ' + dev));
    p.appendChild(el('div', 'ivx-cast-sub', 'Playing on the TV. Pause / stop here controls it.'));
    var id = videoId();
    if (id) {
      var r = deviceRow('Play this video on ' + dev, 'Replaces what\u2019s on the TV', P_CONN);
      r.addEventListener('click', function () { startCast(id, dev); });
      p.appendChild(r);
    }
    actions(p, [
      { label: 'Stop casting', onClick: function () {
          window.InvidiousCast.stop();
          setCastingTo(null);
          closeSheet();
        } },
      { label: 'Close', primary: true, onClick: closeSheet }
    ]);
  }

  function startCast(id, name) {
    var p = panel();
    p.appendChild(el('h2', null, 'Cast to'));
    busyRow(p, name, 'Connecting\u2026 starting YouTube on the TV');
    // stop local playback so it doesn't play in two places
    try { var v = document.querySelector('video'); if (v) v.pause(); } catch (e) {}
    // v1.21: continue on the TV from where you are (SmartTube honours it; the YouTube receiver ignores it)
    var v = document.querySelector('video'), t = v && v.currentTime > 5 ? Math.floor(v.currentTime) : 0;
    if (window.InvidiousCast.castAt) window.InvidiousCast.castAt(id, name, t);
    else window.InvidiousCast.castTo(id, name);
  }

  function messageSheet(title, msg, retry) {
    var p = panel();
    p.appendChild(el('h2', null, title));
    p.appendChild(el('div', 'ivx-msg', msg));
    actions(p, [{ label: 'Close', onClick: closeSheet }, { label: 'Try again', primary: true, onClick: retry }]);
  }

  function parse(json) {
    try { return JSON.parse(json); } catch (e) { return { ok: false, error: 'Bad reply from the app' }; }
  }

  window.__ivxCast = {
    onDevices: function (json) {
      if (!sheet) return;                       // user cancelled meanwhile
      var r = parse(json);
      if (!r.ok) {
        messageSheet('Can\u2019t cast right now',
          r.error + '. Check the laptop is awake and on the same Wi\u2011Fi.', openSheet);
        return;
      }
      var devs = r.devices || [];
      if (!devs.length) {
        messageSheet('No devices found',
          'Nothing to cast to on this Wi\u2011Fi right now. Turn the TV on (wake the SHIELD), then try again.', openSheet);
        return;
      }
      var p = panel();
      p.appendChild(el('h2', null, 'Cast to'));
      var id = videoId();
      devs.forEach(function (d) {
        var row = deviceRow(d.name, d.model || 'Cast device', P_TV);
        row.addEventListener('click', function () { startCast(id, d.name); });
        p.appendChild(row);
      });
      actions(p, [{ label: 'Cancel', onClick: closeSheet }]);
    },
    onCastResult: function (json) {
      var r = parse(json);
      if (r.ok) {
        setCastingTo(r.device);
        closeSheet();
      } else {
        var id = videoId();
        messageSheet('Couldn\u2019t cast to ' + r.device, r.error || 'Unknown error',
          function () { startCast(id, r.device); });
      }
    },
    onStopResult: function () { /* state already cleared optimistically */ }
  };

  /* ---------- boot + cheap polling (video.js builds the player after load) ---------- */
  function tick() { syncInsets(); ensureButton(); }
  tick();
  setInterval(tick, 1000);
  document.addEventListener('DOMContentLoaded', tick);
  document.addEventListener('keydown', function (e) { if (e.key === 'Escape') closeSheet(); });
  }
  if (document.documentElement) main();
  else new MutationObserver(function (m, o) {
    if (document.documentElement) { o.disconnect(); main(); }
  }).observe(document, { childList: true });
})();
