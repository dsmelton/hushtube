/* Hermes UI v2 — page JS for ALL clients (PWA, desktop, APK).
 * Served by Invidious itself: bind-mounted to /invidious/assets/js/ivx.js and
 * loaded from the footer as <script src="/js/ivx.js?v=N" defer>. Same-origin, so the
 * CSP (script-src 'self') allows it — unlike inline <script>. Bump ?v=N in
 * ui/footer.html on every change (Invidious serves /js/ with a 1-month max-age).
 */
(function () {
  'use strict';

  /* ---- 0. shared helpers (icons, layers, toast, avatars, cards) ------------- */
  var PHONE_Q = '(max-width:767px), (hover:none) and (pointer:coarse) and (max-width:999px)';
  function isPhone() { return window.matchMedia && matchMedia(PHONE_Q).matches; }
  function loggedIn() { return !!document.getElementById('notification_ticker'); }
  var I = {
    more: 'M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z',
    search: 'M20.87 20.17l-5.59-5.59A6.956 6.956 0 0 0 17 10c0-3.87-3.13-7-7-7s-7 3.13-7 7 3.13 7 7 7c1.75 0 3.35-.65 4.58-1.71l5.59 5.59.7-.71zM10 16c-3.31 0-6-2.69-6-6s2.69-6 6-6 6 2.69 6 6-2.69 6-6 6z',
    bell: 'M10 20h4c0 1.1-.9 2-2 2s-2-.9-2-2zm10-2.65V19H4v-1.65l2-1.88v-5.15C6 7.4 7.56 5.1 10 4.34v-.38c0-1.42 1.49-2.5 2.99-1.76.65.32 1.01 1.03 1.01 1.76v.39c2.44.75 4 3.06 4 5.98v5.15l2 1.87zm-1 .42-2-1.88v-5.47c0-2.47-1.19-4.36-3.13-5.1-1.26-.53-2.64-.5-3.84.03C8.15 6.11 7 7.99 7 10.42v5.47l-2 1.88V18h14v-.23z',
    back: 'M21 11v1H5.64l6.72 6.72-.71.71-7.93-7.93 7.92-7.92.71.71L5.64 11H21z',
    close: 'M12.71 12l8.15 8.15-.71.71L12 12.71l-8.15 8.15-.71-.71L11.29 12 3.15 3.85l.71-.71L12 11.29l8.15-8.15.71.71L12.71 12z',
    hist: 'M14.97 16.95 10 13.87V7h2v5.76l4.03 2.49-1.06 1.7zM22 12c0 5.51-4.49 10-10 10S2 17.51 2 12h1c0 4.96 4.04 9 9 9s9-4.04 9-9-4.04-9-9-9C8.81 3 5.92 4.64 4.28 7.38c-.11.18-.22.37-.31.56L3.94 8H8v1H1.96V3h1v4.74c.04-.09.07-.17.11-.25.11-.22.23-.42.35-.63C5.22 3.86 8.51 2 12 2c5.51 0 10 4.49 10 10z',
    fill: 'M5 15h2V8.41L18.59 20 20 18.59 8.41 7H15V5H5z',
    like: 'M18.77 11h-4.23l1.52-4.94C16.38 5.03 15.54 4 14.38 4c-.58 0-1.14.24-1.52.65L7 11H3v10h4h1h9.43c1.06 0 1.98-.67 2.19-1.61l1.34-6C21.23 12.15 20.18 11 18.77 11zM7 20H4v-8h3V20zM19.98 13.17l-1.34 6C18.54 19.65 18.03 20 17.43 20H8v-8.61l5.6-6.06C13.79 5.12 14.08 5 14.38 5c.26 0 .5.11.63.3.07.1.15.26.09.47l-1.52 4.94L13.18 12h1.35h4.23c.41 0 .8.17 1.03.46.13.15.26.4.19.71z',
    dislike: 'M17 4h-1H6.57C5.5 4 4.59 4.67 4.38 5.61l-1.34 6C2.77 12.85 3.82 14 5.23 14h4.23l-1.52 4.94C7.62 19.97 8.46 21 9.62 21c.58 0 1.14-.24 1.52-.65L17 14h4V4H17zM10.4 19.67C10.21 19.88 9.92 20 9.62 20c-.26 0-.5-.11-.63-.3-.07-.1-.15-.26-.09-.47l1.52-4.94.4-1.29H9.46H5.23c-.41 0-.8-.17-1.03-.46-.12-.15-.25-.4-.18-.72l1.34-6c.1-.47.61-.82 1.21-.82H16v8.61L10.4 19.67zM20 13h-3V5h3V13z',
    share: 'M15 5.63 20.66 12 15 18.37V14h-1c-3.96 0-7.14 1-9.75 3.09 1.84-4.07 5.11-6.4 9.89-7.1l.86-.13V5.63M14 3v6C6.22 10.13 3.11 15.33 2 21c2.78-3.97 6.44-6 12-6v6l8-9-8-9z',
    dl: 'M17 18v1H6v-1h11zm-.5-6.6-.7-.7-3.8 3.7V4h-1v10.4l-3.8-3.8-.7.7 5 5 5-4.9z',
    save: 'M22 13h-4v4h-1v-4h-4v-1h4V8h1v4h4v1zM14 7H2v1h12V7zM2 12h9v-1H2v1zm0 4h9v-1H2v1z',
    listen: 'M12 4a8 8 0 0 0-8 8v1h3a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H6a3 3 0 0 1-3-3v-6a9 9 0 0 1 18 0v6a3 3 0 0 1-3 3h-1a2 2 0 0 1-2-2v-4a2 2 0 0 1 2-2h3v-1a8 8 0 0 0-8-8zM4 14v4a2 2 0 0 0 2 2h1a1 1 0 0 0 1-1v-4a1 1 0 0 0-1-1H4zm16 0h-3a1 1 0 0 0-1 1v4a1 1 0 0 0 1 1h1a2 2 0 0 0 2-2v-4z',
    chan: 'M12 3c-4.96 0-9 4.04-9 9s4.04 9 9 9 9-4.04 9-9-4.04-9-9-9m0-1c5.52 0 10 4.48 10 10s-4.48 10-10 10S2 17.52 2 12 6.48 2 12 2zm0 5c1.65 0 3 1.35 3 3s-1.35 3-3 3-3-1.35-3-3 1.35-3 3-3m0-1c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4zm-6.06 12.12c.98-1.33 3.5-2.12 6.06-2.12s5.08.79 6.06 2.12l.8-.6C17.62 15.86 14.85 15 12 15s-5.62.86-6.86 2.52l.8.6z',
    block: 'M12 3c-4.96 0-9 4.04-9 9s4.04 9 9 9 9-4.04 9-9-4.04-9-9-9m0-1c5.52 0 10 4.48 10 10s-4.48 10-10 10S2 17.52 2 12 6.48 2 12 2zM5.98 6.69l11.33 11.33-.71.71L5.27 7.4l.71-.71z',
    trash: 'M11 17H9V8h2v9zm4-9h-2v9h2V8zm4-4v1h-1v16H6V5H5V4h4V3h6v1h4zm-2 1H7v15h10V5z',
    prev: 'M6 6h2v12H6zm3.5 6 8.5 6V6z',
    next: 'M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z',
    play: 'M8 5v14l11-7z',
    pause: 'M6 19h4V5H6v14zm8-14v14h4V5h-4z',
    check: 'M9 16.17 4.83 12l-1.42 1.42L9 19 21 7l-1.41-1.41z',
    add: 'M20 12h-8v8h-1v-8H3v-1h8V3h1v8h8v1z',
    pl: 'M22 7H2v1h20V7zm-9 5H2v-1h11v1zm0 4H2v-1h11v1zm2 3v-8l7 4-7 4z',
    gear: 'M12 9.5c1.38 0 2.5 1.12 2.5 2.5s-1.12 2.5-2.5 2.5-2.5-1.12-2.5-2.5 1.12-2.5 2.5-2.5m0-1c-1.93 0-3.5 1.57-3.5 3.5s1.57 3.5 3.5 3.5 3.5-1.57 3.5-3.5-1.57-3.5-3.5-3.5zM13.22 3l.55 2.2.13.51.5.18c.61.23 1.19.56 1.72.98l.4.32.5-.14 2.17-.62 1.22 2.11-1.63 1.59-.37.36.08.51c.05.32.08.64.08.98s-.03.66-.08.98l-.08.51.37.36 1.63 1.59-1.22 2.11-2.17-.62-.5-.14-.4.32c-.53.43-1.11.76-1.72.98l-.5.18-.13.51-.55 2.24h-2.44l-.55-2.2-.13-.51-.5-.18c-.6-.23-1.18-.56-1.72-.99l-.4-.32-.5.14-2.17.62-1.21-2.12 1.63-1.59.37-.36-.08-.51c-.05-.32-.08-.65-.08-.98s.03-.66.08-.98l.08-.51-.37-.36L3.6 8.56l1.22-2.11 2.17.62.5.14.4-.32c.53-.44 1.11-.77 1.72-.99l.5-.18.13-.51.54-2.21h2.44M14 2h-4l-.74 2.96c-.73.27-1.4.66-2 1.14l-2.92-.83-2 3.46 2.19 2.13c-.06.37-.09.75-.09 1.14s.03.77.09 1.14l-2.19 2.13 2 3.46 2.92-.83c.6.48 1.27.87 2 1.14L10 22h4l.74-2.96c.73-.27 1.4-.66 2-1.14l2.92.83 2-3.46-2.19-2.13c.06-.37.09-.75.09-1.14s-.03-.77-.09-1.14l2.19-2.13-2-3.46-2.92.83c-.6-.48-1.27-.87-2-1.14L14 2z',
    out: 'M20 3v18H8v-1h11V4H8V3h12zm-8.9 12.1.7.7 4.4-4.4L11.8 7l-.7.7 3.1 3.1H3v1h11.3l-3.2 3.3z',
    subs: 'M10 18v-6l5 3-5 3zm7-15H7v1h10V3zm3 3H4v1h16V6zm2 3H2v12h20V9zM3 10h18v10H3V10z',
    io: 'M17 18v1H6v-1h11zm-.5-6.6-.7-.7-3.8 3.7V4h-1v10.4l-3.8-3.8-.7.7 5 5 5-4.9z',
    chev: 'M9.4 18.4l-.7-.7 5.6-5.6-5.7-5.7.7-.7 6.4 6.4-6.3 6.3z'
  };
  function svg(path) { return '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="' + path + '"/></svg>'; }
  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); }
  function vidOf(href) { var m = (href || '').match(/[?&]v=([\w-]{11})/); return m ? m[1] : ''; }
  function ucidOf(href) { var m = (href || '').match(/\/channel\/(UC[\w-]{22})/); return m ? m[1] : ''; }
  function parseDur(t) {
    var p = String(t || '').trim().split(':').map(Number);
    if (!p.length || p.some(isNaN)) return 0;
    return p.reduce(function (a, b) { return a * 60 + b; }, 0);
  }
  function compact(n) {
    n = Number(n);
    if (!isFinite(n)) return '';
    if (n >= 1e9) return (n / 1e9).toFixed(n < 1e10 ? 1 : 0).replace(/\.0$/, '') + 'B';
    if (n >= 1e6) return (n / 1e6).toFixed(n < 1e7 ? 1 : 0).replace(/\.0$/, '') + 'M';
    if (n >= 1e3) return (n / 1e3).toFixed(n < 1e4 ? 1 : 0).replace(/\.0$/, '') + 'K';
    return '' + n;
  }
  function agoMs(ms) {
    if (!ms || isNaN(ms)) return '';
    var s = (Date.now() - ms) / 1000;
    var u = [[31536000, 'year'], [2592000, 'month'], [604800, 'week'], [86400, 'day'], [3600, 'hour'], [60, 'minute']];
    for (var i = 0; i < u.length; i++) if (s >= u[i][0]) {
      var n = Math.floor(s / u[i][0]); return n + ' ' + u[i][1] + (n > 1 ? 's' : '') + ' ago';
    }
    return 'just now';
  }

  // ---- toast (YouTube snackbar)
  var toastEl = null, toastT = null;
  function toast(msg) {
    if (!toastEl) { toastEl = document.createElement('div'); toastEl.id = 'ivx-toast'; document.body.appendChild(toastEl); }
    toastEl.textContent = msg;
    void toastEl.offsetWidth;
    toastEl.classList.add('on');
    clearTimeout(toastT);
    toastT = setTimeout(function () { toastEl.classList.remove('on'); }, 2600);
  }

  // ---- layers (sheets / panels / search) closable with Android back
  var layers = [];
  window.addEventListener('popstate', function () {
    var l = layers.pop();
    if (l) l.close(true);
  });
  function openLayer(close) {
    var l = { close: close };
    layers.push(l);
    try { history.pushState({ ivxLayer: layers.length }, ''); } catch (e) {}
    return function () {                        // programmatic close = go back one entry
      if (layers.indexOf(l) >= 0) history.back();
    };
  }
  function scrim(on, onTap) {
    var s = document.getElementById('ivx-scrim');
    if (!s) {
      s = document.createElement('div'); s.id = 'ivx-scrim'; document.body.appendChild(s);
      s.addEventListener('click', function () { if (s.__tap) s.__tap(); });
    }
    s.__tap = onTap;
    if (on) { void s.offsetWidth; s.classList.add('on'); } else s.classList.remove('on');
  }
  // bottom sheet: rows = [{icon, label, sub, href, fn, cls}] or a DOM node
  function sheet(title, content) {
    var sh = document.createElement('div');
    sh.className = 'ivx-sheet';
    sh.innerHTML = '<div class="ivx-grab"></div>' + (title ? '<div class="ivx-sh"></div>' : '');
    if (title) sh.querySelector('.ivx-sh').textContent = title;
    var dismiss;
    function close(fromPop) {
      sh.classList.remove('on'); scrim(false);
      setTimeout(function () { sh.remove(); }, 260);
      if (!fromPop) dismiss();
    }
    if (Array.isArray(content)) content.forEach(function (r) { sh.appendChild(row(r, function () { close(); })); });
    else if (content) sh.appendChild(content);
    document.body.appendChild(sh);
    dismiss = openLayer(function () { close(true); });
    scrim(true, function () { dismiss(); });
    void sh.offsetWidth;
    sh.classList.add('on');
    // swipe the sheet down to dismiss
    var y0 = null;
    sh.addEventListener('touchstart', function (e) { y0 = sh.scrollTop <= 0 ? e.touches[0].clientY : null; }, { passive: true });
    sh.addEventListener('touchmove', function (e) {
      if (y0 == null) return;
      var d = e.touches[0].clientY - y0;
      sh.style.transition = 'none';
      sh.style.transform = d > 0 ? 'translateY(' + d + 'px)' : '';
    }, { passive: true });
    sh.addEventListener('touchend', function (e) {
      if (y0 == null) return;
      var d = e.changedTouches[0].clientY - y0; y0 = null;
      sh.style.transition = ''; sh.style.transform = '';
      if (d > 90) dismiss();
    });
    return { el: sh, close: function () { dismiss(); } };
  }
  function row(r, closeSheet) {
    var a = document.createElement(r.href ? 'a' : 'button');
    a.className = 'ivx-row' + (r.cls ? ' ' + r.cls : '');
    if (r.href) a.href = r.href; else a.type = 'button';
    a.innerHTML = (r.icon ? svg(r.icon) : '') + '<span></span>' + (r.sub ? '<span class="ivx-sub"></span>' : '') +
                  (r.check ? '<svg class="ck" viewBox="0 0 24 24"><path d="' + I.check + '"/></svg>' : '');
    a.querySelector('span').textContent = r.label;
    if (r.sub) a.querySelector('.ivx-sub').textContent = r.sub;
    a.addEventListener('click', function (e) {
      if (r.fn) { e.preventDefault(); if (r.keep) r.fn(a); else { closeSheet && closeSheet(); setTimeout(function () { r.fn(a); }, 10); } }
    });
    return a;
  }
  // full-height panel under the player (description / comments), YouTube watch page style
  function panel(title, body) {
    var pc = document.getElementById('player-container');
    var top = pc ? Math.max(0, pc.getBoundingClientRect().bottom) : innerHeight * 0.35;
    var p = document.createElement('div');
    p.className = 'ivx-panel';
    p.style.setProperty('--ivx-ptop', top + 'px');
    p.innerHTML = '<div class="ivx-ph"><span></span><button type="button" aria-label="Close">' + svg(I.close) + '</button></div><div class="ivx-pb"></div>';
    p.querySelector('.ivx-ph span').textContent = title;
    p.querySelector('.ivx-pb').appendChild(body);
    document.body.appendChild(p);
    var dismiss;
    function close(fromPop) {
      p.classList.remove('on');
      setTimeout(function () { p.remove(); }, 300);
      if (!fromPop) dismiss();
    }
    dismiss = openLayer(function () { close(true); });
    p.querySelector('.ivx-ph button').addEventListener('click', function () { dismiss(); });
    void p.offsetWidth;
    p.classList.add('on');
    return p;
  }

  // ---- share / copy
  function share(id, title) {
    var url = 'https://youtu.be/' + id;
    if (window.InvidiousPlayer && InvidiousPlayer.share) {
      try { InvidiousPlayer.share(JSON.stringify({ url: url, title: title || '' })); return; } catch (e) {}
    }
    if (navigator.share) { navigator.share({ title: title || '', url: url }).catch(function () {}); return; }
    var done = function () { toast('Link copied to clipboard'); };
    if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(url).then(done, function () { legacyCopy(url); done(); });
    else { legacyCopy(url); done(); }
  }
  function legacyCopy(t) {
    var ta = document.createElement('textarea'); ta.value = t; ta.style.position = 'fixed'; ta.style.opacity = '0';
    document.body.appendChild(ta); ta.select(); try { document.execCommand('copy'); } catch (e) {} ta.remove();
  }

  // ---- channel avatars (cached in localStorage, 14 days)
  var AVK = 'ivxAv1', AV = {}, avQ = [], avBusy = 0, avPending = {}, avSaveT = null;
  try { AV = JSON.parse(localStorage.getItem(AVK) || '{}') || {}; } catch (e) { AV = {}; }
  function avNorm(u) {
    if (!u) return '';
    u = String(u).replace(/^https?:\/\/yt\d*\.(ggpht|googleusercontent)\.com/, '/ggpht');
    if (/^\/\//.test(u)) u = 'https:' + u;
    return u.replace(/=s\d+-/, '=s88-');
  }
  function avPut(ucid, url) {
    if (!ucid) return;
    AV[ucid] = [avNorm(url), Date.now()];
    document.querySelectorAll('img[data-ucid="' + ucid + '"]').forEach(function (im) { if (AV[ucid][0]) im.src = AV[ucid][0]; });
    clearTimeout(avSaveT);
    avSaveT = setTimeout(function () {
      var keys = Object.keys(AV);
      if (keys.length > 700) keys.sort(function (a, b) { return AV[a][1] - AV[b][1]; }).slice(0, keys.length - 600).forEach(function (k) { delete AV[k]; });
      try { localStorage.setItem(AVK, JSON.stringify(AV)); } catch (e) {}
    }, 400);
  }
  function avHarvest(list) {
    (list || []).forEach(function (v) {
      var t = v && v.authorThumbnails;
      if (v && v.authorId && t && t.length) avPut(v.authorId, (t.filter(function (x) { return x.width >= 68; })[0] || t[t.length - 1]).url);
    });
  }
  function avPump() {
    while (avBusy < 3 && avQ.length) {
      var u = avQ.shift();
      avBusy++;
      fetch('/api/v1/channels/' + u, { credentials: 'same-origin' })
        .then(function (r) { return r.ok ? r.json() : null; })
        .then((function (ucid) { return function (j) {
          var t = j && j.authorThumbnails;
          avPut(ucid, t && t.length ? (t.filter(function (x) { return x.width >= 68; })[0] || t[t.length - 1]).url : '');
        }; })(u))
        .catch(function () {})
        .then(function () { avBusy--; avPump(); });
    }
  }
  function avSet(img, ucid) {
    if (!ucid) return;
    img.setAttribute('data-ucid', ucid);
    var c = AV[ucid];
    if (c && (c[0] || Date.now() - c[1] < 86400000) && Date.now() - c[1] < 14 * 86400000) { if (c[0]) img.src = c[0]; return; }
    if (avPending[ucid]) return;
    avPending[ucid] = 1;
    avQ.push(ucid);
    setTimeout(avPump, 0);
  }

  // ---- resume positions (Invidious player.js keeps them in localStorage)
  function savedPos() {
    try {
      var raw = localStorage.getItem('save_player_pos');
      if (!raw) return {};
      try { return JSON.parse(decodeURIComponent(raw)) || {}; } catch (e) { return JSON.parse(raw) || {}; }
    } catch (e) { return {}; }
  }
  var POS = null;

  // ---- "not interested" list (hides cards, excluded from For You)
  function niList() { try { return JSON.parse(localStorage.getItem('ivxNI') || '[]'); } catch (e) { return []; } }
  function niAdd(id) { var l = niList(); if (l.indexOf(id) < 0) l.push(id); try { localStorage.setItem('ivxNI', JSON.stringify(l.slice(-500))); } catch (e) {} }

  /* card(v): v = {id, title, author, ucid, len (text), live, views, ago, href, pct, noAv, thumb} */
  function card(v) {
    var c = document.createElement('div');
    c.className = 'ivx-card' + (v.noAv ? ' ivx-noav' : '');
    c.setAttribute('data-id', v.id);
    var href = v.href || '/watch?v=' + v.id;
    var meta = [v.noAv ? '' : v.author, v.views, v.ago].filter(Boolean).join(' \u00b7 ');
    var badge = v.live ? '<span class="ivx-dur live">LIVE</span>' : v.len ? '<span class="ivx-dur">' + esc(v.len) + '</span>' : '';
    var pct = v.pct;
    if (pct == null && !v.live) {
      POS = POS || savedPos();
      var p = POS[v.id], L = parseDur(v.len);
      if (p > 0 && L > 0) pct = Math.max(4, Math.min(100, p / L * 100));
    }
    c.innerHTML =
      '<a class="ivx-thumb" href="' + esc(href) + '" tabindex="-1"><img loading="lazy" alt="" src="' + esc(v.thumb || '/vi/' + v.id + '/hqdefault.jpg') + '">' +
        (pct ? '<span class="ivx-pbar"><i style="width:' + pct.toFixed(1) + '%"></i></span>' : '') + badge + '</a>' +
      '<div class="ivx-info">' +
        (v.ucid ? '<a class="ivx-av" href="/channel/' + v.ucid + '" tabindex="-1"><img alt=""></a>' : '<span class="ivx-av"></span>') +
        '<div class="ivx-txt"><a class="ivx-t" href="' + esc(href) + '"></a><div class="ivx-m"></div></div>' +
        '<button type="button" class="ivx-kebab" aria-label="More actions">' + svg(I.more) + '</button>' +
      '</div>';
    c.querySelector('.ivx-t').textContent = v.title || '';
    c.querySelector('.ivx-m').textContent = meta;
    if (v.ucid && !v.noAv) avSet(c.querySelector('.ivx-av img'), v.ucid);
    c.__v = v;
    return c;
  }
  function skelCard() {
    var d = document.createElement('div');
    d.className = 'pure-u-1 pure-u-md-1-4 ivx-skel';
    d.innerHTML = '<div class="h-box ivx-dec"><div class="ivx-card"><div class="ivx-thumb"></div><div class="ivx-info"><span class="ivx-av"></span>' +
                  '<div class="ivx-txt"><span class="ivx-sl"></span><span class="ivx-sl"></span></div></div></div></div>';
    return d;
  }

  // ---- ⋮ menu
  function cardMenu(c) {
    var v = c.__v || {};
    var rows = [];
    if (loggedIn()) rows.push({ icon: I.save, label: 'Save to playlist', fn: function () { saveToPlaylist(v.id); } });
    rows.push({ icon: I.share, label: 'Share', fn: function () { share(v.id, v.title); } });
    rows.push({ icon: I.listen, label: 'Play as audio', href: '/watch?v=' + v.id + '&listen=1' });
    if (v.ucid) rows.push({ icon: I.chan, label: 'Go to channel', href: '/channel/' + v.ucid });
    if (v.removeBtn) rows.push({ icon: I.trash, label: 'Remove from playlist', fn: function () { v.removeBtn.click(); toast('Removed from playlist'); } });
    rows.push({ icon: I.block, label: 'Not interested', fn: function () {
      niAdd(v.id);
      var t = c.closest('.pure-u-md-1-4, .pure-u-lg-1-5 .pure-u-1') || c;
      t.style.display = 'none';
      toast('Video removed — you won\u2019t see it in your feed');
    } });
    sheet(null, rows);
  }
  document.addEventListener('click', function (e) {
    var b = e.target.closest && e.target.closest('.ivx-kebab');
    if (!b) return;
    e.preventDefault();
    var c = b.closest('.ivx-card');
    if (c) cardMenu(c);
  });

  // ---- save to playlist sheet
  function saveToPlaylist(id) {
    var box = document.createElement('div');
    box.innerHTML = '<div class="ivx-sh">Save video to\u2026</div><div class="ivx-pll"><div class="ivx-row" style="color:#aaa">Loading playlists\u2026</div></div>';
    var sh = sheet(null, box);
    var list = box.querySelector('.ivx-pll');
    function add(pl) {
      return fetch('/api/v1/auth/playlists/' + pl.playlistId + '/videos', {
        method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ videoId: id })
      }).then(function (r) {
        if (!r.ok) throw new Error(r.status);
        sh.close(); toast('Saved to ' + pl.title);
      }).catch(function () { toast('Couldn\u2019t save — try again'); });
    }
    fetch('/api/v1/auth/playlists', { credentials: 'same-origin' }).then(function (r) { return r.json(); }).then(function (pls) {
      list.innerHTML = '';
      (pls || []).forEach(function (pl) {
        var has = (pl.videos || []).some(function (x) { return x.videoId === id; });
        list.appendChild(row({ icon: I.pl, label: pl.title, sub: has ? 'Saved' : (pl.videoCount + ' videos'), keep: true,
          fn: function () { if (has) { toast('Already in ' + pl.title); return; } add(pl); } }));
      });
      list.appendChild(row({ icon: I.add, label: 'New playlist', keep: true, fn: function () {
        var name = prompt('Playlist name');
        if (!name) return;
        fetch('/api/v1/auth/playlists', { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ title: name, privacy: 'private' }) })
          .then(function (r) { return r.json(); })
          .then(function (pl) { return add({ playlistId: pl.playlistId, title: name }); })
          .catch(function () { toast('Couldn\u2019t create playlist'); });
      } }));
    }).catch(function () { list.innerHTML = '<div class="ivx-row">Couldn\u2019t load playlists</div>'; });
  }

  /* decorate(box): rebuild a server-rendered video tile as an .ivx-card */
  function pageUcid() { return location.pathname.indexOf('/channel/') === 0 ? ucidOf(location.pathname) : ''; }
  function decorate(box) {
    if (!box || box.classList.contains('ivx-dec')) return;
    var img = box.querySelector('img.thumbnail');
    var ta = img && img.closest('a');
    if (!ta || !/\/watch\?v=/.test(ta.getAttribute('href') || '')) return;
    if (box.querySelector('a > center')) return;
    var href = ta.getAttribute('href');
    var id = vidOf(href);
    if (!id) return;
    var tp = box.querySelector('.video-card-row > a > p') || box.querySelector('.video-card-row a p') || box.querySelector(':scope > p');
    var chA = box.querySelector('.video-card-row a[href^="/channel/"], h5 a[href^="/channel/"]');
    var lenEl = box.querySelector('.length');
    var live = !!(lenEl && (lenEl.querySelector('.ion-ios-play-circle') || /live/i.test(lenEl.textContent)));
    var data = [].map.call(box.querySelectorAll('.video-data, h5 .pure-u-10-24 b'), function (p) { return p.textContent.trim(); });
    var views = '', ago = '';
    data.forEach(function (t) {
      if (/views?$/i.test(t) || /watching$/i.test(t)) { if (!/^0 views$/.test(t)) views = t.replace(/^([\d.]+[KMB]?) /, '$1 '); }
      else if (/^(Shared|Premiered|Streamed)\s/i.test(t)) ago = t.replace(/^Shared\s+/i, '');
      else if (/ago$/.test(t)) ago = t;
    });
    var ucid = chA ? ucidOf(chA.getAttribute('href')) : '';
    var wi = box.querySelector('.watched-indicator');
    var v = {
      id: id, href: href, title: tp ? tp.textContent.trim() : '',
      author: chA ? chA.textContent.replace(/\s+/g, ' ').trim() : '', ucid: ucid,
      len: lenEl && !live ? lenEl.textContent.trim() : '', live: live, views: views, ago: ago,
      noAv: !!(pageUcid() && ucid === pageUcid()) || (!ucid && !!pageUcid()),
      removeBtn: box.querySelector('[data-onclick="remove_playlist_item"]')
    };
    if (wi && !(savedPos()[id] > 0)) v.pct = 100;
    var c = card(v);
    var related = !!box.closest('.pure-u-lg-1-5');
    if (related) c.classList.add('ivx-rel');
    if (niList().indexOf(id) >= 0 && !/^\/(playlist|feed\/history)/.test(location.pathname)) box.style.display = 'none';
    box.appendChild(c);
    box.classList.add('ivx-dec');
    if (!v.title) hydrate(c, id);
  }
  // history tiles arrive without a title (upstream bug) -> fill from the API
  function hydrate(c, id) {
    fetch('/api/v1/videos/' + id, { credentials: 'same-origin' })
      .then(function (r) { return r.ok ? r.json() : Promise.reject(); })
      .then(function (j) {
        avHarvest([j]);
        var v = c.__v;
        v.title = j.title || id; v.author = j.author || ''; v.ucid = j.authorId || v.ucid;
        if (!v.len && j.lengthSeconds) v.len = fmtDur(j.lengthSeconds);
        if (j.viewCount) v.views = compact(j.viewCount) + ' views';
        if (j.published) v.ago = agoMs(j.published * (j.published < 1e11 ? 1000 : 1));
        var n = card(v);
        if (c.classList.contains('ivx-rel')) n.classList.add('ivx-rel');
        c.replaceWith(n);
      })
      .catch(function () { c.querySelector('.ivx-t').textContent = 'Unavailable video (' + id + ')'; });
  }
  function decorateAll(root) {
    (root || document).querySelectorAll('.pure-u-md-1-4 > .h-box, .pure-u-lg-1-5 .h-box > .pure-u-1').forEach(decorate);
  }


  /* ---- 1. smooth scrubbing --------------------------------------------------
   * video.js 7.12: dragging the seek bar calls player.currentTime(t), but the bar
   * and the time label are drawn from player.cache_.currentTime, which only
   * refreshes on the next `timeupdate` — and the browser fires that only after it
   * has data at the new position. Result: the dot sits still until buffering
   * catches up. Fix: while scrubbing, write the target time into the cache and
   * redraw immediately, so the dot and "0:42 / 27:14" follow the finger.
   */
  function patchSeekBar() {
    var vjs = window.videojs;
    if (!vjs || !vjs.getComponent) return false;
    var SeekBar = vjs.getComponent('SeekBar');
    if (!SeekBar) return false;
    if (SeekBar.prototype.__ivxSmooth) return true;
    var orig = SeekBar.prototype.handleMouseMove;
    SeekBar.prototype.handleMouseMove = function (event) {
      orig.call(this, event);
      try {
        var p = this.player_;
        if (!p || !p.scrubbing || !p.scrubbing()) return;
        var lt = p.liveTracker;
        if (lt && lt.isLive && lt.isLive()) return;          // live: leave stock behaviour
        var d = p.duration();
        if (!isFinite(d) || d <= 0) return;
        var t = Math.max(0, Math.min(this.calculateDistance(event) * d, d - 0.1));
        p.cache_.currentTime = t;                               // what the bar + label render from
        this.update();                                          // redraw the play-progress bar now
        var cb = p.getChild('controlBar');
        var ctd = cb && cb.getChild('currentTimeDisplay');
        if (ctd && ctd.updateContent) ctd.updateContent();      // "0:42" label
      } catch (e) { /* never break the stock seek */ }
    };
    SeekBar.prototype.__ivxSmooth = true;
    return true;
  }

  /* ---- 2. touch gestures on the video (YouTube app) ---------------------------
   * Not fullscreen: swipe UP -> fullscreen; swipe DOWN -> mini player (APK) / PiP.
   * Fullscreen:     swipe DOWN -> exit fullscreen.
   * Tap            -> show/hide controls instantly (no 300 ms wait).
   * Double-tap left/right third -> seek -/+10 s with the YouTube ripple; keep
   *                  tapping the same side to add 10 s per tap ("30 seconds").
   * Press and hold -> 2x speed while held ("2x >>" pill), back on release.
   * We own every touchend on the picture (capture phase on the player element,
   * stopped there), so videojs-mobile-ui's own 300 ms tap logic never runs.
   * Footer CSS: `touch-action: pan-up` (browser pans only for finger-DOWN moves),
   * `none` in fullscreen. Fullscreen is requested from touchend (user activation).
   */
  var EXCLUDE = '.vjs-progress-control,.vjs-menu,.vjs-modal-dialog,.vjs-text-track-settings,' +
                '.vjs-control-bar .vjs-button,.mobile-operations-bar,.ivx-cast-control,' +
                '.vjs-touch-overlay .vjs-play-control,.vjs-big-play-button,.ivx-np,.vjs-error-display';

  function setupSwipe(player) {
    var el = player && player.el && player.el();
    if (!el || el.__ivxSwipe) return;
    el.__ivxSwipe = true;

    var st = null;            // gesture state for the current touch
    var lastTap = null;       // {t, zone} of the previous plain tap
    var combo = null;         // active double-tap seek streak {side, n, timer}
    if (canMinimize()) el.style.touchAction = 'none';   // swipe down is ours too (CSS default: pan-up)

    function tech() { return el.querySelector('.vjs-tech'); }
    function follow(px) {
      var t = tech();
      if (!t) return;
      t.style.transition = 'none';
      t.style.transform = px ? 'translateY(' + px + 'px)' : '';
    }
    function settle() {
      var t = tech();
      if (!t) return;
      t.style.transition = 'transform .18s ease-out';
      t.style.transform = '';
    }

    // --- hold for 2x
    var fastPill = null, fastPrev = 1;
    function startFast() {
      if (!fastPill) {
        fastPill = document.createElement('div');
        fastPill.className = 'ivx-2x';
        fastPill.innerHTML = '2x <i></i><i></i>';
        el.appendChild(fastPill);
      }
      fastPrev = player.playbackRate() || 1;
      player.playbackRate(2);
      if (player.paused()) player.play();
      fastPill.classList.add('on');
      hideNow();
      if (navigator.vibrate) try { navigator.vibrate(12); } catch (e) {}
    }
    function stopFast() {
      player.playbackRate(fastPrev);
      if (fastPill) fastPill.classList.remove('on');
    }

    // --- double-tap seek ripple
    var rip = {};
    function ripple(side, n) {
      var r = rip[side];
      if (!r) {
        r = rip[side] = document.createElement('div');
        r.className = 'ivx-seek ' + (side < 0 ? 'l' : 'r');
        r.innerHTML = '<div class="ar"><i></i><i></i><i></i></div><div class="tx"></div>';
        el.appendChild(r);
      }
      r.querySelector('.tx').textContent = (n * 10) + ' seconds';
      r.classList.add('on');
      var o = rip[-side]; if (o) o.classList.remove('on');
    }
    function seekBy(side) {
      var d = player.duration(), t = player.currentTime();
      if (!isFinite(d) || d <= 0) d = Infinity;
      player.currentTime(Math.max(0, Math.min(d - 0.3, t + side * 10)));
    }
    function doubleTap(side) {
      if (combo && combo.side === side) combo.n++;
      else { if (combo) clearTimeout(combo.timer); combo = { side: side, n: 1 }; }
      clearTimeout(combo.timer);
      seekBy(side);
      ripple(side, combo.n);
      hideNow();
      var c = combo;
      c.timer = setTimeout(function () {
        if (rip[c.side]) rip[c.side].classList.remove('on');
        if (combo === c) combo = null;
      }, 750);
    }
    // video.js keeps re-activating while it believes a mouse button is down; end that first
    function hideNow() {
      try { player.trigger('mouseup'); player.userActivity_ = false; player.userActive(false); } catch (e) {}
    }
    function toggleControls() {
      var ov = el.querySelector('.vjs-touch-overlay');
      try {
        if (player.userActive() && !player.paused() && !el.classList.contains('vjs-user-inactive')) {
          hideNow();
          if (ov) ov.classList.remove('show-play-toggle');
        } else {
          player.userActive(true);
          if (ov) ov.classList.add('show-play-toggle');
        }
      } catch (e) {}
    }
    function plainTap(e) {
      var t = e.changedTouches && e.changedTouches[0];
      if (!t) return false;
      if (!player.hasStarted || !player.hasStarted()) return false;     // before first play: stock big-play behaviour
      var rect = el.getBoundingClientRect();
      var x = (t.clientX - rect.left) / rect.width;
      var zone = x < 0.38 ? -1 : x > 0.62 ? 1 : 0;
      var now = Date.now();
      if (combo && zone !== 0 && now - (combo.last || 0) < 750) {
        combo.last = now; doubleTap(zone);                             // streak: every tap adds 10 s
      } else if (lastTap && zone !== 0 && zone === lastTap.zone && now - lastTap.t < 320) {
        doubleTap(zone); combo.last = now; lastTap = null;
      } else {
        toggleControls();                                               // instant, no 300 ms wait
        lastTap = { t: now, zone: zone };
      }
      return true;
    }

    el.addEventListener('touchstart', function (e) {
      st = null;
      if (e.touches.length !== 1) return;
      if (e.target.closest && e.target.closest(EXCLUDE)) return;
      var t = e.touches[0];
      e.stopPropagation();                 // video.js would flash the controls on every touch
      st = { x: t.clientX, y: t.clientY, t: Date.now(), dy: 0, claimed: false, moved: false, long: false,
             fs: player.isFullscreen() };
      var me = st;
      if (player.hasStarted && player.hasStarted() && !player.ended()) {
        me.lp = setTimeout(function () {
          if (st === me && !me.claimed && !me.moved) { me.long = true; startFast(); }
        }, 480);
      }
    }, { passive: true, capture: true });

    el.addEventListener('touchmove', function (e) {
      if (!st) return;
      if (e.touches.length !== 1) { clearTimeout(st.lp); if (st.long) stopFast(); st = null; settle(); return; }
      var t = e.touches[0], dx = t.clientX - st.x, dy = t.clientY - st.y;
      if (st.long) { if (e.cancelable) e.preventDefault(); return; }
      st.dy = dy;
      if (Math.abs(dx) > 10 || Math.abs(dy) > 10) { st.moved = true; clearTimeout(st.lp); }
      if (!st.claimed) {
        if (Math.abs(dx) < 10 && Math.abs(dy) < 10) return;
        var vertical = Math.abs(dy) > Math.abs(dx) * 1.3;
        // fullscreen: down = exit; inline: up = fullscreen, down = mini player / PiP
        var wanted = st.fs ? dy > 0 : (dy < 0 || canMinimize());
        if (!vertical || !wanted) { st = null; return; }   // not ours (page scroll, etc.)
        st.claimed = true;
      }
      if (e.cancelable) e.preventDefault();
      var off = Math.max(-90, Math.min(90, dy * 0.4));     // damped follow, capped
      follow(off);
    }, { passive: false, capture: true });

    function end(e) {
      if (!st) return;
      var g = st;
      st = null;
      clearTimeout(g.lp);
      if (g.long) {
        stopFast();
        e.stopPropagation();
        if (e.cancelable) e.preventDefault();
        return;
      }
      if (!g.claimed) {
        if (e.type === 'touchcancel' || g.moved) return;
        if (plainTap(e)) {
          e.stopPropagation();              // mobile-ui must not run its own (delayed) tap logic
          if (e.cancelable) e.preventDefault();
        }
        return;
      }
      e.stopPropagation();                  // keep mobile-ui from seeing a "tap"
      if (e.cancelable) e.preventDefault();
      settle();
      var dt = Math.max(1, Date.now() - g.t);
      var dist = Math.abs(g.dy), speed = dist / dt;      // px per ms
      if (e.type === 'touchcancel') return;
      if (dist > 70 || (dist > 30 && speed > 0.45)) {
        try {
          if (g.fs) player.exitFullscreen();
          else if (g.dy < 0) player.requestFullscreen();
          else minimize(player);
        } catch (err) { /* ignore */ }
      }
    }
    el.addEventListener('touchend', end, { capture: true });
    el.addEventListener('touchcancel', end, { capture: true });
    el.addEventListener('contextmenu', function (e) { e.preventDefault(); });
  }

  /* ---- 2c. player chrome: prev / next in the centre row, autoplay toggle ---- */
  function setupPlayerChrome(player) {
    var el = player.el();
    if (el.__ivxChrome) return;
    var ov = el.querySelector('.vjs-touch-overlay');
    var ops = el.querySelector('.mobile-operations-bar');
    if (!ov || !ops) {                             // desktop layout, or player.js not done yet
      player.__ivxChromeTries = (player.__ivxChromeTries || 0) + 1;
      if (player.__ivxChromeTries < 40) setTimeout(function () { setupPlayerChrome(player); }, 250);
      return;
    }
    el.__ivxChrome = true;
    var vd = window.video_data || {};
    var next = vd.next_video, listen = vd.params && vd.params.listen;
    var prevOk = false;
    try { prevOk = document.referrer && new URL(document.referrer).origin === location.origin &&
                   new URL(document.referrer).pathname === '/watch' && history.length > 1; } catch (e) {}
    function btn(cls, path, ok, fn) {
      var b = document.createElement('button');
      b.className = 'ivx-np ' + cls + (ok ? '' : ' dis');
      b.setAttribute('aria-label', cls);
      b.innerHTML = svg(path);
      b.addEventListener('click', function (e) { e.stopPropagation(); if (ok) fn(); });
      b.addEventListener('touchend', function (e) { e.stopPropagation(); }, false);
      ov.appendChild(b);
    }
    btn('prev', I.prev, prevOk, function () { navStart(); history.back(); });
    btn('next', I.next, !!next, function () {
      navStart();
      location.href = '/watch?v=' + next + (listen ? '&listen=1' : '');
    });
    var cb = document.getElementById('continue');
    if (cb) {
      var t = document.createElement('div');
      t.className = 'vjs-control ivx-auto' + (cb.checked ? ' on' : '');
      t.setAttribute('role', 'switch');
      t.title = 'Autoplay';
      t.innerHTML = '<div class="tr"><div class="th"><svg class="p" viewBox="0 0 24 24"><path d="' + I.play + '"/></svg>' +
                    '<svg class="s" viewBox="0 0 24 24"><path d="' + I.pause + '"/></svg></div></div>';
      t.addEventListener('click', function (e) {
        e.stopPropagation();
        cb.click();
        t.classList.toggle('on', cb.checked);
        toast(cb.checked ? 'Autoplay is on' : 'Autoplay is off');
      });
      ops.insertBefore(t, ops.firstChild);
    }
  }

  /* ---- 2b. mini player / picture-in-picture -----------------------------------
   * APK: swipe down hands the video to the app's native mini player
   * (window.InvidiousPlayer.minimize), which keeps playing while you browse; the app
   * also goes picture-in-picture on Home while a video plays, using the state this
   * page reports. Browsers: swipe down = the browser's own picture-in-picture.
   */
  function videoEl(player) { return player.el().querySelector('video'); }
  function videoId() {
    try { return new URLSearchParams(location.search).get('v') || ''; } catch (e) { return ''; }
  }
  function meta(player) {
    var v = videoEl(player);
    var ch = document.getElementById('channel-name');
    var h1 = document.querySelector('#contents h1');
    var title = h1 && h1.firstChild && h1.firstChild.nodeType === 3 ? h1.firstChild.textContent.trim() : '';
    if (!title) title = (document.title || '').replace(/ - Invidious$/, '').replace(/^\uD83D\uDC34\s*/, '').trim();
    return {
      id: videoId(),
      title: title,
      author: ch ? ch.textContent.trim() : '',
      t: v ? v.currentTime : 0,
      playing: !!(v && !v.paused && !v.ended)
    };
  }
  function native() { return window.InvidiousPlayer && window.InvidiousPlayer.minimize ? window.InvidiousPlayer : null; }
  function canMinimize() {
    if (!/^\/watch/.test(location.pathname)) return false;
    if (native()) return true;
    return !!(document.pictureInPictureEnabled);
  }
  function minimize(player) {
    var v = videoEl(player);
    var n = native();
    if (n) {
      var m = meta(player);
      if (!/^[A-Za-z0-9_-]{11}$/.test(m.id)) return;
      window.__ivxMinimized = true;
      // Do NOT pause: the app pauses this video the moment the mini player actually
      // starts (it has been buffering the same stream muted), so nothing freezes.
      if (v) v.muted = true;
      n.minimize(JSON.stringify(m));
    } else if (v && document.pictureInPictureEnabled && v.requestPictureInPicture) {
      v.requestPictureInPicture().catch(function () {});
    }
  }
  function setupReporting(player) {
    var n = window.InvidiousPlayer;
    if (!n || !n.report || player.__ivxReport || !/^\/watch/.test(location.pathname)) return;
    player.__ivxReport = true;
    // a minimized page must stop reporting: the video keeps playing muted during the
    // handover and the app already owns playback; re-arm on bfcache restore
    window.addEventListener('pageshow', function () { window.__ivxMinimized = false; });
    var last = 0;
    function send(force) {
      if (window.__ivxMinimized) return;
      var now = Date.now();
      if (!force && now - last < 2000) return;
      last = now;
      try { n.report(JSON.stringify(meta(player))); } catch (e) {}
    }
    ['play', 'playing', 'pause', 'seeked', 'ended'].forEach(function (ev) { player.on(ev, function () { send(true); }); });
    player.on('timeupdate', function () { send(false); });
    window.addEventListener('pagehide', function () {
      if (window.__ivxMinimized) return;
      try { n.report(JSON.stringify({ id: videoId(), playing: false, t: 0 })); } catch (e) {}
    });
    send(true);
  }

  /* ---- 3. fullscreen = landscape (YouTube app) ---------------------------------
   * Entering fullscreen (swipe, button, or rotate) turns the screen to landscape for
   * landscape videos, portrait videos stay portrait; leaving it restores free
   * rotation. In the APK this goes through the native InvidiousScreen bridge
   * (WebView can't lock orientation itself); browsers use screen.orientation.lock.
   */
  function setOrientation(mode) {
    try {
      if (window.InvidiousScreen && window.InvidiousScreen.setOrientation) {
        window.InvidiousScreen.setOrientation(mode);
        return;
      }
      var so = screen.orientation;
      if (!so) return;
      if (mode === 'auto') { if (so.unlock) so.unlock(); }
      else if (so.lock) so.lock(mode).catch(function () {});
    } catch (e) { /* ignore */ }
  }

  function setupOrientation(player) {
    if (player.__ivxOrient) return;
    player.__ivxOrient = true;
    player.on('fullscreenchange', function () {
      if (player.isFullscreen()) {
        var v = player.el().querySelector('video');
        var w = v && v.videoWidth, h = v && v.videoHeight;
        setOrientation(w && h && h > w ? 'portrait' : 'landscape');
      } else {
        setOrientation('auto');
      }
    });
  }

  /* ---- 4. instant tap / navigation feedback -----------------------------------
   * First view of a page costs 0.7-2.3 s (Invidious fetching from YouTube), so a
   * tap must produce visible feedback the same frame: the tapped link stays dimmed
   * (CSS a.ivx-tapped img) and a red top progress bar starts crawling immediately,
   * finishing when the new page is shown. Covers links and form submits (search).
   */
  var navStart = function () {};
  function setupNavFeedback() {
    if (window.__ivxNavFb) return;
    window.__ivxNavFb = true;
    var bar = null;
    function el() {
      if (!bar || !bar.isConnected) {
        bar = document.createElement('div');
        bar.id = 'ivx-prog';
        document.documentElement.appendChild(bar);
      }
      return bar;
    }
    function start() {
      var b = el();
      b.className = '';
      void b.offsetWidth;                 // restart the width transition
      b.className = 'ivx-go';
    }
    navStart = start;
    function done() {
      if (!bar) return;
      bar.className = 'ivx-done';
      var b = bar;
      setTimeout(function () { if (b.className === 'ivx-done') b.className = ''; }, 600);
      var dim = document.querySelectorAll('a.ivx-tapped');
      for (var i = 0; i < dim.length; i++) dim[i].classList.remove('ivx-tapped');
    }
    document.addEventListener('click', function (e) {
      if (e.defaultPrevented || e.button > 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
      var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
      if (!a) return;
      var href = a.getAttribute('href') || '';
      if (!href || href.charAt(0) === '#' || /^(javascript|mailto|tel):/i.test(href)) return;
      if (a.target && a.target !== '_self') return;
      a.classList.add('ivx-tapped');
      start();
    }, true);
    document.addEventListener('submit', function (e) {
      if (e.target && e.target.tagName === 'FORM') start();
    }, true);
    window.addEventListener('pageshow', done);   // fires on load AND bfcache back/forward
  }

  /* ---- 5. formatting helpers (history hydration now lives in decorate/hydrate) */
  function fmtDur(s) {
    var h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60), sec = s % 60;
    return (h ? h + ':' + ('0' + m).slice(-2) : m) + ':' + ('0' + sec).slice(-2);
  }
  function fmtCount(n) { return compact(n); }

  /* ---- 6. "For You" home feed ---------------------------------------------------
   * Replaces the Trending grid on the home page with videos related to the user's
   * watch history. Invidious 2.x removed the /related API, but the video object
   * embeds YouTube's own `recommendedVideos`, so each recently-watched video
   * contributes two signals:
   *   - its recommendedVideos (niche/uploader affinity, weight w)
   *   - its channel's recent uploads (freshness from same uploader, weight w*0.5)
   * Weight w = 0.65^rank in history: the most recent watch dominates and each
   * older one peters off. Candidates sum weights across all sources (co-occurrence
   * boost), already-watched videos are excluded, max 2 per channel. Infinite
   * scroll: a sentinel below the grid appends more batches as you near the bottom;
   * when the scored pool runs dry it seeds the rest of the history page (ranks
   * 7-20), then shows an end note. Recomputed on
   * every home load; invalidated the moment a new video lands in history, and
   * cached in sessionStorage for 15 min otherwise. Logged-out or on any error the
   * stock Trending grid stays untouched.
   */
  function setupHomeFeed() {
    if (location.pathname !== '/feed/trending' || window.__ivxHome) return;
    if (/[?&]type=/.test(location.search)) return;   // Live/Gaming tabs keep their grid
    window.__ivxHome = true;
    var grid = document.querySelector('#contents > .pure-g:not(.navbar):not(.h-box)');
    if (!grid) return;

    function watchedIds() {
      return fetch('/feed/history', { credentials: 'same-origin' }).then(function (r) {
        // anonymous users get a silent 302 to /feed/trending — do NOT treat its
        // watch links as history
        if (!r.ok || new URL(r.url).pathname !== '/feed/history') throw new Error('history ' + r.status);
        return r.text();
      }).then(function (html) {
        var doc = new DOMParser().parseFromString(html, 'text/html');
        var seen = {}, out = [];
        doc.querySelectorAll('a[href^="/watch?v="]').forEach(function (a) {
          var m = a.getAttribute('href').match(/[?&]v=([\w-]{11})/);
          if (m && !seen[m[1]]) { seen[m[1]] = 1; out.push(m[1]); }
        });
        if (!out.length) throw new Error('empty history');
        return out;
      });
    }
    function getJSON(url) {
      return fetch(url, { credentials: 'same-origin' }).then(function (r) {
        return r.ok ? r.json() : Promise.reject(new Error(url + ' ' + r.status));
      });
    }
    function ago(pub) {
      if (!pub) return '';
      var t = typeof pub === 'number' ? pub * (pub < 1e11 ? 1000 : 1) : new Date(pub).getTime();
      return agoMs(t);
    }
    function pubMs(v) { var t = new Date(v.published).getTime(); return isNaN(t) ? 0 : t; }
    function views(v) {
      if (v.viewCount > 0) return compact(v.viewCount) + ' views';
      if (v.viewCountText) return v.viewCountText + ' views';
      return '';
    }
    function tile(v) {
      var d = document.createElement('div');
      d.className = 'pure-u-1 pure-u-md-1-4';
      var h = document.createElement('div');
      h.className = 'h-box ivx-dec';
      h.appendChild(card({ id: v.videoId, title: v.title, author: v.author, ucid: v.authorId,
                           len: v.lengthSeconds > 0 ? fmtDur(v.lengthSeconds) : '', views: views(v), ago: ago(v.published) }));
      d.appendChild(h);
      return d;
    }
    function skeleton(n) {
      var f = document.createDocumentFragment();
      for (var i = 0; i < n; i++) f.appendChild(skelCard());
      return f;
    }
    var BATCH = 18, MORE = 12, cacheKey = 'ivxHome4';
    var NI = {}; niList().forEach(function (id) { NI[id] = 1; });
    var pool = [], shown = 0, sentinel = null, hist = null, scores = {}, watched = {},
        expanded = false, expanding = false, pumpPending = false,
        painted = false, paintTimer = null;

    function slim(v) {
      return { videoId: v.videoId, title: v.title, author: v.author, authorId: v.authorId,
               lengthSeconds: v.lengthSeconds, viewCount: v.viewCount,
               viewCountText: v.viewCountText, published: v.published };
    }
    function offer(v, w) {
      if (!v || !v.videoId || !v.title || watched[v.videoId] || NI[v.videoId]) return;
      var t = (v.videoType || '').toLowerCase();
      if (v.live === true || v.liveNow === true || t === 'live' || t === 'scheduled') return;
      if (!v.lengthSeconds) return;   // live/premiere placeholders (0 or missing)
      var s = scores[v.videoId];
      if (!s) { scores[v.videoId] = { v: slim(v), s: w }; }
      else { s.s += w; if (pubMs(v) > pubMs(s.v)) s.v.published = v.published; }
    }
    function seed(i, w) {
      return getJSON('/api/v1/videos/' + hist[i]).then(function (v) {
        avHarvest([v]);
        (v.recommendedVideos || []).slice(0, 12).forEach(function (s) { offer(s, w); });
        maybePaint();
        if (!v.authorId) return;
        return getJSON('/api/v1/channels/' + v.authorId + '/videos').then(function (page) {
          ((page.videos || page.videoStreams) || []).slice(0, 8).forEach(function (s) { offer(s, w * 0.5); });
          var vids = page.videos || [];
          if (vids[0] && vids[0].authorThumbnails) avHarvest(vids.slice(0, 1));
          maybePaint();
        });
      }).catch(function () {});
    }
    // first paint as soon as ANY seed has data (don't wait for the slowest one)
    function maybePaint() {
      if (painted || paintTimer) return;
      paintTimer = setTimeout(function () {
        paintTimer = null;
        if (painted) return;
        pool = buildPool();
        if (!pool.length) return;
        painted = true;
        saveCache();
        render();
      }, 120);
    }
    function buildPool() {
      var list = Object.keys(scores).map(function (k) { return scores[k]; });
      list.sort(function (a, b) { return b.s - a.s || pubMs(b.v) - pubMs(a.v); });
      var chan = {}, out = [];
      for (var j = 0; j < list.length; j++) {
        var ch = list[j].v.author || list[j].v.videoId;
        chan[ch] = (chan[ch] || 0) + 1;
        if (chan[ch] <= 2) out.push(list[j].v);
      }
      return out;
    }
    function saveCache() {
      try { sessionStorage.setItem(cacheKey, JSON.stringify({ first: hist[0], ts: Date.now(), pool: pool })); } catch (e) {}
    }
    function appendBatch(n) {
      var f = document.createDocumentFragment();
      var end = Math.min(pool.length, shown + n);
      for (; shown < end; shown++) f.appendChild(tile(pool[shown]));
      grid.appendChild(f);
    }
    function addSentinel() {
      if (sentinel) return;
      sentinel = document.createElement('div');
      sentinel.className = 'ivx-scroll-sentinel';
      grid.parentNode.insertBefore(sentinel, grid.nextSibling);
    }
    function endSentinel() {
      if (!sentinel) return;
      sentinel.className = 'ivx-scroll-end';
      sentinel.textContent = "That's everything your history points to for now — it grows as you watch.";
      sentinel = null;
    }
    function expand() {
      // pool exhausted: seed the rest of the history page (ranks 7-20, tiny weights)
      expanding = true;
      var jobs = [];
      for (var i = 6; i < Math.min(20, hist.length); i++) jobs.push(seed(i, Math.pow(0.65, i)));
      if (!jobs.length) { expanding = false; expanded = true; pump(); return; }
      Promise.all(jobs).then(function () {
        expanding = false; expanded = true;
        pool = buildPool();
        saveCache();
        pump();
      });
    }
    function pump() {
      if (!sentinel) return;
      if (sentinel.getBoundingClientRect().top > window.innerHeight + 500) return;
      if (shown < pool.length) { appendBatch(MORE); setTimeout(pump, 60); return; }
      if (expanding) return;
      if (expanded) { endSentinel(); return; }
      expand();
    }
    function onScroll() {
      if (pumpPending) return;
      pumpPending = true;
      setTimeout(function () { pumpPending = false; pump(); }, 50);
    }
    function render() {
      grid.innerHTML = '';        // drop the skeleton placeholders
      grid.classList.add('ivx-foryou-grid');
      var pag = document.querySelector('#contents .page-nav-container');
      if (pag) pag.style.display = 'none';
      // the stock "Trending" title + filter pills would sit above For You misleadingly
      ['#contents > .pure-g.h-box', '#contents > .h-box'].forEach(function (sel) {
        var el = document.querySelector(sel);
        if (el) el.style.display = 'none';
      });
      appendBatch(BATCH);
      addSentinel();
      window.addEventListener('scroll', onScroll, { passive: true });
      setTimeout(pump, 150);   // sentinel may already be visible on tall screens
    }

    function build() {
      watchedIds().then(function (h) {
        hist = h;
        try {
          var c = JSON.parse(sessionStorage.getItem(cacheKey) || '0');
          if (c.first === hist[0] && Date.now() - c.ts < 900000 && c.pool.length) {
            pool = c.pool; render(); return;
          }
        } catch (e) { /* recompute */ }
        hist.forEach(function (id) { watched[id] = 1; });
        var jobs = [];
        for (var i = 0; i < Math.min(6, hist.length); i++) jobs.push(seed(i, Math.pow(0.65, i)));
        // progressive: skeleton now, final grid when all sources settle (or 6 s)
        var orig = grid.innerHTML;
        grid.innerHTML = '';
        grid.appendChild(skeleton(12));
        var settled = false;
        function finish() {
          if (settled) return; settled = true;
          if (paintTimer) { clearTimeout(paintTimer); paintTimer = null; }
          pool = buildPool();
          if (!pool.length) { if (!painted) grid.innerHTML = orig; return; }   // restore stock Trending
          saveCache();
          if (!painted) { painted = true; render(); return; }
          // late seeds changed the ranking: re-sort in place, but never yank the
          // grid out from under a user who already started scrolling
          if (window.scrollY < 120) {
            shown = 0; grid.innerHTML = '';
            appendBatch(BATCH);
            setTimeout(pump, 100);
          }
        }
        Promise.all(jobs).then(finish);
        setTimeout(finish, 6000);
      }).catch(function () { /* logged out or error: leave Trending as-is */ });
    }
    build();
  }

  /* ---- 7. unique download names -------------------------------------------------
   * The "Download as:" widget POSTs to /companion/download with target=_blank,
   * which the WebView ignores, and the server-side filename is generic. Intercept
   * the submit: in the app hand the POST to the native downloader (bridge), in a
   * plain browser fetch it as a blob — either way the file is saved as
   * "Title (videoId).ext" so downloads never collide or end up nameless.
   */
  function setupDownloads() {
    document.addEventListener('submit', function (e) {
      var f = e.target;
      if (!f || !/\/companion\/download/.test(f.getAttribute('action') || '')) return;
      e.preventDefault();
      var fd = new FormData(f);
      var pairs = [];
      fd.forEach(function (v, k) { pairs.push(encodeURIComponent(k) + '=' + encodeURIComponent(String(v))); });
      var meta = {
        url: new URL(f.getAttribute('action'), location.origin).href,
        body: pairs.join('&'),
        id: fd.get('id') || '',
        title: (fd.get('title') || document.title || 'video').slice(0, 140)
      };
      if (window.InvidiousPlayer && InvidiousPlayer.download) {
        InvidiousPlayer.download(JSON.stringify(meta));
        return;
      }
      fetch(meta.url, { method: 'POST', body: meta.body, credentials: 'same-origin',
                        headers: { 'Content-Type': 'application/x-www-form-urlencoded' } })
        .then(function (r) {
          if (!r.ok) throw new Error('HTTP ' + r.status);
          var ct = r.headers.get('content-type') || 'video/mp4';
          return r.blob().then(function (b) {
            return { b: b, ext: ct.indexOf('webm') >= 0 ? 'webm' : 'mp4' };
          });
        })
        .then(function (x) {
          var a = document.createElement('a');
          a.href = URL.createObjectURL(x.b);
          a.download = meta.title.replace(/[\\/:*?"<>|]/g, '_') + ' (' + meta.id + ').' + x.ext;
          document.body.appendChild(a);
          a.click();
          a.remove();
          setTimeout(function () { URL.revokeObjectURL(a.href); }, 30000);
        })
        .catch(function (err) { alert('Download failed: ' + err); });
    }, true);
  }

  /* ---- 8. phone top bar: logo, search / notifications, hide on scroll ------- */
  function setupTopBar() {
    var nb = document.querySelector('.navbar');
    if (!nb || nb.__ivx) return;
    nb.__ivx = true;
    var logo = nb.querySelector('.index-link');
    if (logo && !logo.querySelector('.ivx-wm')) { logo.textContent = ''; var w = document.createElement('span'); w.className = 'ivx-wm'; w.textContent = 'YouTube'; logo.appendChild(w); }
    var sbx = document.getElementById('searchbox');
    if (sbx) sbx.placeholder = 'Search';
    var tb = document.createElement('div');
    tb.id = 'ivx-tb';
    var q = '';
    try { q = new URLSearchParams(location.search).get('q') || ''; } catch (e) {}
    if (location.pathname === '/search') {
      document.documentElement.classList.add('ivx-sp');
      var sq = document.createElement('div');
      sq.id = 'ivx-sq';
      sq.innerHTML = '<button type="button" class="ivx-ib" aria-label="Back">' + svg(I.back) + '</button><div class="q"></div>';
      sq.querySelector('.q').textContent = q;
      sq.querySelector('button').addEventListener('click', function () { history.length > 1 ? history.back() : (location.href = '/'); });
      sq.querySelector('.q').addEventListener('click', function () { openSearch(q); });
      nb.appendChild(sq);
      if (q) rememberSearch(q);
    }
    var sb = document.createElement('button');
    sb.type = 'button'; sb.className = 'ivx-tbs'; sb.setAttribute('aria-label', 'Search');
    sb.innerHTML = svg(I.search);
    sb.addEventListener('click', function () { openSearch(''); });
    tb.appendChild(sb);
    if (loggedIn()) {
      var bell = document.createElement('a');
      bell.href = '/feed/subscriptions'; bell.setAttribute('aria-label', 'Notifications');
      bell.innerHTML = svg(I.bell);
      var nc = document.getElementById('notification_count');
      var n = nc ? parseInt(nc.textContent, 10) : 0;
      if (n > 0) { var bd = document.createElement('span'); bd.className = 'badge'; bd.textContent = n > 9 ? '9+' : n; bell.appendChild(bd); }
      tb.appendChild(bell);
    }
    nb.appendChild(tb);
    // hide on scroll down, show on scroll up (phone, not the watch page)
    var lastY = scrollY, acc = 0, html = document.documentElement;
    window.addEventListener('scroll', function () {
      if (!isPhone() || document.getElementById('player-container')) return;
      var y = scrollY, dy = y - lastY; lastY = y;
      if (y < 60) { acc = 0; html.classList.remove('ivx-tbh'); return; }
      if (dy > 0) { acc = Math.max(0, acc) + dy; if (acc > 30) html.classList.add('ivx-tbh'); }
      else { acc = Math.min(0, acc) + dy; if (acc < -30) html.classList.remove('ivx-tbh'); }
    }, { passive: true });
  }

  /* ---- 9. search overlay: suggestions + recent searches (YouTube style) ----- */
  function recentSearches() { try { return JSON.parse(localStorage.getItem('ivxSearches') || '[]'); } catch (e) { return []; } }
  function rememberSearch(q) {
    q = (q || '').trim();
    if (!q || /^channel:/.test(q)) return;
    var l = recentSearches().filter(function (x) { return x.toLowerCase() !== q.toLowerCase(); });
    l.unshift(q);
    try { localStorage.setItem('ivxSearches', JSON.stringify(l.slice(0, 25))); } catch (e) {}
  }
  var searchEl = null, searchClose = null;
  function openSearch(initial) {
    if (!searchEl) {
      searchEl = document.createElement('div');
      searchEl.id = 'ivx-search';
      searchEl.innerHTML =
        '<form action="/search" method="get" role="search">' +
          '<button type="button" class="ivx-ib bk" aria-label="Back">' + svg(I.back) + '</button>' +
          '<input type="search" name="q" autocomplete="off" autocapitalize="none" spellcheck="false" enterkeyhint="search" placeholder="Search YouTube">' +
          '<button type="button" class="ivx-ib cl" aria-label="Clear">' + svg(I.close) + '</button>' +
        '</form><ul></ul>';
      document.body.appendChild(searchEl);
      var inp = searchEl.querySelector('input'), ul = searchEl.querySelector('ul'), form = searchEl.querySelector('form');
      var seq = 0, t = null;
      searchEl.querySelector('.bk').addEventListener('click', function () { searchClose && searchClose(); });
      searchEl.querySelector('.cl').addEventListener('click', function () { inp.value = ''; inp.focus(); render([]); });
      form.addEventListener('submit', function (e) {
        var v = inp.value.trim();
        if (!v) { e.preventDefault(); return; }
        rememberSearch(v);
      });
      function item(text, typed, recent) {
        var li = document.createElement('li');
        var a = document.createElement('a');
        a.href = '/search?q=' + encodeURIComponent(text);
        a.innerHTML = svg(recent ? I.hist : I.search) + '<span></span>';
        var sp = a.querySelector('span');
        var tl = (typed || '').toLowerCase();
        if (tl && text.toLowerCase().indexOf(tl) === 0) {
          sp.appendChild(document.createTextNode(text.slice(0, tl.length)));
          var b = document.createElement('b'); b.textContent = text.slice(tl.length); sp.appendChild(b);
        } else sp.textContent = text;
        a.addEventListener('click', function () { rememberSearch(text); });
        var f = document.createElement('button');
        f.type = 'button'; f.setAttribute('aria-label', 'Edit');
        f.innerHTML = svg(I.fill);
        f.addEventListener('click', function () { inp.value = text + ' '; inp.focus(); update(); });
        li.appendChild(a); li.appendChild(f);
        return li;
      }
      function render(sugg) {
        var typed = inp.value.trim();
        ul.innerHTML = '';
        var rec = recentSearches().filter(function (x) { return !typed || x.toLowerCase().indexOf(typed.toLowerCase()) === 0; }).slice(0, typed ? 3 : 15);
        rec.forEach(function (x) { ul.appendChild(item(x, typed, true)); });
        (sugg || []).forEach(function (x) {
          if (rec.some(function (r) { return r.toLowerCase() === x.toLowerCase(); })) return;
          ul.appendChild(item(x, typed, false));
        });
      }
      function update() {
        var v = inp.value.trim();
        searchEl.querySelector('.cl').style.visibility = inp.value ? 'visible' : 'hidden';
        clearTimeout(t);
        if (!v) { render([]); return; }
        render(searchEl.__last && searchEl.__lastQ && v.indexOf(searchEl.__lastQ) === 0 ? searchEl.__last : []);
        var my = ++seq;
        t = setTimeout(function () {
          fetch('/api/v1/search/suggestions?q=' + encodeURIComponent(v), { credentials: 'same-origin' })
            .then(function (r) { return r.json(); })
            .then(function (j) { if (my !== seq) return; searchEl.__last = j.suggestions || []; searchEl.__lastQ = v; render(searchEl.__last); })
            .catch(function () {});
        }, 90);
      }
      inp.addEventListener('input', update);
      searchEl.__update = update;
    }
    var inp2 = searchEl.querySelector('input');
    inp2.value = initial || '';
    searchEl.classList.add('on');
    inp2.focus();
    try { inp2.setSelectionRange(inp2.value.length, inp2.value.length); } catch (e) {}
    searchEl.__update();
    searchClose = openLayer(function () { searchEl.classList.remove('on'); inp2.blur(); });
  }

  /* ---- 10. chips row on the home / trending feeds ---------------------------- */
  function setupChips() {
    if (location.pathname !== '/feed/trending' && location.pathname !== '/') return;
    var contents = document.getElementById('contents');
    if (!contents || document.getElementById('ivx-chips')) return;
    var type = '';
    try { type = new URLSearchParams(location.search).get('type') || ''; } catch (e) {}
    var auth = loggedIn();
    var chips = auth
      ? [['All', '/feed/trending', !type], ['Trending', '/feed/trending?type=Default', type === 'Default']]
      : [['All', '/feed/trending', !type || type === 'Default']];
    chips = chips.concat([['Music', '/feed/trending?type=Music', type === 'Music'], ['Gaming', '/feed/trending?type=Gaming', type === 'Gaming'],
                          ['Live', '/feed/trending?type=Livestreams', type === 'Livestreams'], ['Movies', '/feed/trending?type=Movies', type === 'Movies']]);
    if (auth) chips.push(['Subscriptions', '/feed/subscriptions', false]);
    var row = document.createElement('div');
    row.id = 'ivx-chips';
    chips.forEach(function (c) {
      var a = document.createElement('a');
      a.href = c[1]; a.textContent = c[0];
      if (c[2]) a.className = 'on';
      row.appendChild(a);
    });
    contents.insertBefore(row, contents.firstChild);
  }

  /* ---- 11. bottom tab bar state + the "You" page ----------------------------- */
  function setupNav() {
    var p = location.pathname, key = '';
    if (p === '/' || p === '/feed/trending' || p === '/feed/popular') key = 'home';
    else if (p === '/feed/subscriptions') key = 'subs';
    else if (/^\/(feed\/(playlists|history)|preferences|login|subscription_manager|data_control|playlist|create_playlist|change_password|token_manager)/.test(p)) key = 'you';
    if (!key) return;
    document.querySelectorAll('#ivx-nav .ivx-' + key).forEach(function (a) { a.classList.add('ivx-act'); });
  }
  function setupYouPage() {
    if (location.pathname !== '/feed/playlists' || !loggedIn() || !isPhone() || document.getElementById('ivx-youpg')) return;
    var contents = document.getElementById('contents');
    var un = document.getElementById('user_name');
    var name = un ? un.textContent.trim() : 'You';
    var pg = document.createElement('div');
    pg.id = 'ivx-youpg';
    pg.innerHTML =
      '<div class="ivx-yh"><div class="ivx-yav"></div><div><div class="ivx-yn"></div><div class="ivx-ys">Your private Invidious account</div></div></div>' +
      '<div class="ivx-sech"><b>History</b><a href="/feed/history">View all</a></div>' +
      '<div class="ivx-hscroll ivx-yhist"><a><div class="ivx-thumb"></div></a><a><div class="ivx-thumb"></div></a><a><div class="ivx-thumb"></div></a></div>' +
      '<div class="ivx-sech"><b>Playlists</b><a href="/create_playlist">+ New</a></div>';
    pg.querySelector('.ivx-yav').textContent = name.charAt(0);
    pg.querySelector('.ivx-yn').textContent = name.replace(/@.*$/, '');
    var stockHead = contents.querySelector(':scope > .pure-g.h-box');
    contents.insertBefore(pg, stockHead || contents.firstChild);
    document.documentElement.classList.add('ivx-youon');
    // settings list after the playlists grid
    var list = document.createElement('div');
    list.className = 'ivx-ylist';
    [{ icon: I.hist, label: 'History', href: '/feed/history' },
     { icon: I.subs, label: 'Manage subscriptions', href: '/subscription_manager' },
     { icon: I.io, label: 'Import & export data', href: '/data_control' },
     { icon: I.gear, label: 'Settings', href: '/preferences' },
     { icon: I.out, label: 'Sign out', fn: function () {
        var f = document.querySelector('form[action^="/signout"]');
        if (f) { navStart(); f.submit(); }
     } }].forEach(function (r) { list.appendChild(row(r)); });
    var grid = contents.querySelector('.pure-g:has(> .pure-u-md-1-4)');
    if (!grid) {
      var empty = document.createElement('div');
      empty.className = 'ivx-ys'; empty.style.margin = '0 0 8px';
      empty.textContent = 'No playlists yet. Use \u22ee \u2192 Save to playlist on any video.';
      pg.appendChild(empty);
    }
    (grid ? grid.parentNode : contents).insertBefore(list, grid ? grid.nextSibling : null);
    // history carousel
    var hs = pg.querySelector('.ivx-yhist');
    fetch('/feed/history', { credentials: 'same-origin' }).then(function (r) {
      if (!r.ok || new URL(r.url).pathname !== '/feed/history') throw 0;
      return r.text();
    }).then(function (html) {
      var doc = new DOMParser().parseFromString(html, 'text/html');
      var seen = {}, items = [];
      doc.querySelectorAll('.pure-u-md-1-4').forEach(function (t) {
        var a = t.querySelector('a[href*="/watch?v="]');
        var id = a && vidOf(a.getAttribute('href'));
        if (!id || seen[id]) return; seen[id] = 1;
        var tp = t.querySelector('.video-card-row a p'), ch = t.querySelector('.channel-name');
        items.push({ id: id, title: tp ? tp.textContent.trim() : '', author: ch ? ch.textContent.trim() : '' });
      });
      hs.innerHTML = '';
      if (!items.length) { hs.outerHTML = '<div class="ivx-ys">Videos you watch will show up here.</div>'; return; }
      POS = POS || savedPos();
      items.slice(0, 15).forEach(function (v) {
        var a = document.createElement('a');
        a.href = '/watch?v=' + v.id;
        a.innerHTML = '<div class="ivx-thumb"><img loading="lazy" alt="" src="/vi/' + v.id + '/mqdefault.jpg"></div><div class="t"></div><div class="m"></div>';
        a.querySelector('.t').textContent = v.title;
        a.querySelector('.m').textContent = v.author;
        hs.appendChild(a);
        if (!v.title) fetch('/api/v1/videos/' + v.id).then(function (r) { return r.json(); }).then(function (j) {
          a.querySelector('.t').textContent = j.title || ''; a.querySelector('.m').textContent = j.author || '';
        }).catch(function () {});
      });
    }).catch(function () { hs.style.display = 'none'; });
  }
  function setupLoginExtras() {
    if (location.pathname !== '/login' || !isPhone()) return;
    var f = document.querySelector('#contents form[action^="/login"]');
    if (!f || document.querySelector('.ivx-ylist')) return;
    var list = document.createElement('div');
    list.className = 'ivx-ylist';
    list.style.maxWidth = '476px'; list.style.margin = '24px auto 0';
    list.appendChild(row({ icon: I.gear, label: 'Settings', href: '/preferences' }));
    f.parentNode.insertBefore(list, f.nextSibling);
  }

  function setupSubsRow() {
    if (location.pathname !== '/feed/subscriptions' || !isPhone() || document.getElementById('ivx-subrow')) return;
    var contents = document.getElementById('contents');
    var row = document.createElement('div');
    row.id = 'ivx-subrow';
    var head = contents.querySelector(':scope > .feed-menu + .pure-g.h-box') || contents.querySelector(':scope > .feed-menu');
    contents.insertBefore(row, head ? head.nextSibling : contents.firstChild);
    fetch('/api/v1/auth/subscriptions', { credentials: 'same-origin' }).then(function (r) { return r.json(); }).then(function (subs) {
      (subs || []).forEach(function (s) {
        var a = document.createElement('a');
        a.href = '/channel/' + s.authorId;
        a.innerHTML = '<img alt=""><span></span>';
        a.querySelector('span').textContent = s.author;
        avSet(a.querySelector('img'), s.authorId);
        row.appendChild(a);
      });
      var all = document.createElement('a');
      all.className = 'all'; all.href = '/subscription_manager'; all.textContent = 'All';
      row.appendChild(all);
    }).catch(function () { row.remove(); });
  }

  /* ---- 12. watch page: YouTube-app details layout ----------------------------- */
  function setupWatch() {
    if (location.pathname !== '/watch' || window.__ivxWatch) return;
    var h1 = document.querySelector('#contents h1');
    if (!h1) return;
    window.__ivxWatch = true;
    var box = h1.closest('.h-box') || h1.parentNode;
    var phone = isPhone();
    var id = videoId();
    var titleText = (h1.firstChild && h1.firstChild.nodeType === 3 ? h1.firstChild.textContent : h1.textContent).trim();
    var viewsEl = document.getElementById('views'), likesEl = document.getElementById('likes');
    var nViews = viewsEl ? parseInt(viewsEl.textContent.replace(/[^\d]/g, ''), 10) : NaN;
    var nLikes = likesEl ? parseInt(likesEl.textContent.replace(/[^\d]/g, ''), 10) : NaN;
    var pubEl = document.getElementById('published-date');
    var pubTxt = pubEl ? pubEl.textContent.trim() : '';
    var pubDate = pubTxt.replace(/^(Shared|Premiered|Streamed( live)?|Started streaming|Scheduled for)\s+(on\s+)?/i, '');
    var pubMs = new Date(pubDate).getTime();
    var agoTxt = isNaN(pubMs) ? pubTxt : agoMs(pubMs);

    // meta line: "26M views  1 week ago  ...more"
    var meta = document.createElement('div');
    meta.id = 'ivx-wmeta';
    meta.textContent = [isNaN(nViews) ? '' : compact(nViews) + ' views', agoTxt].filter(Boolean).join('  \u00b7  ');
    var more = document.createElement('b'); more.textContent = '...more'; meta.appendChild(more);
    box.appendChild(meta);

    // channel row moves directly under the title
    var chRow = document.querySelector('#contents .title.flexible');
    if (chRow) box.appendChild(chRow);
    setupSubscribe();

    // action pills
    var acts = document.createElement('div');
    acts.id = 'ivx-actions';
    function pill(icon, label, fn, cls) {
      var b = document.createElement(typeof fn === 'string' ? 'a' : 'button');
      b.className = 'ivx-pill' + (cls ? ' ' + cls : '');
      if (typeof fn === 'string') b.href = fn; else { b.type = 'button'; b.addEventListener('click', fn); }
      b.innerHTML = svg(icon) + '<span></span>';
      b.querySelector('span').textContent = label;
      acts.appendChild(b);
      return b;
    }
    var lk = document.createElement('div');
    lk.className = 'ivx-pill ivx-like';
    lk.innerHTML = '<span>' + svg(I.like) + '<em style="font-style:normal"></em></span><span>' + svg(I.dislike) + '</span>';
    lk.querySelector('em').textContent = isNaN(nLikes) ? 'Like' : compact(nLikes);
    lk.addEventListener('click', function () { toast('Likes are read-only on this server'); });
    acts.appendChild(lk);
    pill(I.share, 'Share', function () { share(id, titleText); });
    var dlForm = document.querySelector('form[action*="/companion/download"]');
    if (dlForm) pill(I.dl, 'Download', function () {
      var wrap = document.createElement('div');
      wrap.appendChild(dlForm);
      var sh = sheet('Download', wrap);
      dlForm.addEventListener('submit', function () { setTimeout(function () { sh.close(); }, 50); toast('Download started'); }, { once: true });
    });
    if (loggedIn()) pill(I.save, 'Save', function () { saveToPlaylist(id); });
    var listen = document.getElementById('link-iv-listen');
    if (listen) pill(I.listen, /listen=1/.test(location.search) ? 'Video' : 'Audio only', listen.getAttribute('href'));
    box.appendChild(acts);
    // the stock info strip (views / likes / download / YouTube link) is replaced by the pills
    if (viewsEl && viewsEl.parentNode) viewsEl.parentNode.classList.add('ivx-hide');

    // description: phone -> panel under the player (tap title / ...more); wide -> inline box
    var desc = document.getElementById('description-box');
    var stats = document.createElement('div');
    stats.className = 'ivx-dstats';
    stats.innerHTML = '<div><b></b><span>Likes</span></div><div><b></b><span>Views</span></div><div><b></b><span></span></div>';
    var sb = stats.querySelectorAll('b'), ss = stats.querySelectorAll('span');
    sb[0].textContent = isNaN(nLikes) ? '\u2014' : compact(nLikes);
    sb[1].textContent = isNaN(nViews) ? '\u2014' : nViews.toLocaleString();
    if (!isNaN(pubMs)) { var d = new Date(pubMs); sb[2].textContent = d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' }); ss[2].textContent = d.getFullYear(); }
    else { sb[2].textContent = '\u2014'; ss[2].textContent = pubTxt; }
    if (desc && phone) {
      var dbody = document.createElement('div');
      var dt = document.createElement('div'); dt.className = 'ivx-dtitle'; dt.textContent = titleText;
      dbody.appendChild(dt); dbody.appendChild(stats);
      desc.classList.add('ivx-hide');
      var openDesc = function (e) {
        if (e && e.target.closest && e.target.closest('a')) return;
        desc.classList.remove('ivx-hide');
        if (desc.parentNode !== dbody) dbody.appendChild(desc);
        panel('Description', dbody);
      };
      meta.addEventListener('click', openDesc);
      h1.addEventListener('click', openDesc);
    } else if (desc) {
      var dl = document.createElement('div');
      dl.className = 'ivx-dline';
      dl.textContent = [isNaN(nViews) ? '' : nViews.toLocaleString() + ' views', isNaN(pubMs) ? pubTxt : new Date(pubMs).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' })].filter(Boolean).join('  ');
      desc.insertBefore(dl, desc.firstChild);
    }

    // comments: phone -> teaser card + panel
    var cm = document.getElementById('comments');
    if (cm && phone) {
      var tz = document.createElement('div');
      tz.id = 'ivx-cteaser';
      tz.className = 'ivx-load';
      tz.innerHTML = '<div class="h">Comments<span></span></div><div class="c"></div>';
      box.appendChild(tz);
      var cbody = document.createElement('div');
      var fill = function () {
        var h3 = cm.querySelector('h3');
        var m = h3 && h3.textContent.match(/([\d,.]+)/);
        tz.querySelector('.h span').textContent = m ? compact(parseInt(m[1].replace(/[^\d]/g, ''), 10)) : '';
        var first = cm.querySelector('.pure-g p[style*="pre-wrap"]') || cm.querySelector('.pure-g p:nth-of-type(2)');
        var av = cm.querySelector('.pure-g .channel-profile img');
        if (!first) return false;
        var c = tz.querySelector('.c');
        c.innerHTML = '';
        if (av) { var im = document.createElement('img'); im.src = av.getAttribute('src'); c.appendChild(im); }
        var sp = document.createElement('span'); sp.textContent = first.textContent.trim(); c.appendChild(sp);
        tz.classList.remove('ivx-load');
        return true;
      };
      if (!fill()) {
        var mo = new MutationObserver(function () { if (fill()) mo.disconnect(); });
        mo.observe(cm, { childList: true, subtree: true });
        setTimeout(function () { mo.disconnect(); if (tz.classList.contains('ivx-load')) { tz.classList.remove('ivx-load'); tz.querySelector('.c').textContent = cm.textContent.trim() ? 'Tap to read comments' : 'Comments are turned off or unavailable'; } }, 15000);
      }
      cm.classList.add('ivx-hide');
      tz.addEventListener('click', function () {
        cm.classList.remove('ivx-hide');
        if (cm.parentNode !== cbody) cbody.appendChild(cm);
        panel('Comments', cbody);
      });
    }
  }
  // comments: drop the "|[YT]|" permalink clutter, "4,800" -> "4.8K" (YouTube app style)
  function cleanComments(root) {
    (root || document).querySelectorAll('#comments a[title="YouTube comment permalink"]').forEach(function (a) {
      var p = a.parentNode;
      a.remove();
      if (!p || p.__ivxClean) return;
      p.__ivxClean = true;
      [].slice.call(p.childNodes).forEach(function (n) {
        if (n.nodeType !== 3) return;
        var t = n.textContent.replace(/\|/g, ' ').replace(/\s+/g, ' ');
        var m = t.match(/^\s*([\d,]+)\s*$/);
        n.textContent = m ? ' ' + compact(parseInt(m[1].replace(/,/g, ''), 10)) : t;
      });
      p.classList.add('ivx-cmeta');
    });
  }
  function setupComments() {
    var cm = document.getElementById('comments');
    if (!cm || cm.__ivx) return;
    cm.__ivx = true;
    cleanComments(cm);
    new MutationObserver(function () { cleanComments(cm); }).observe(cm, { childList: true, subtree: true });
  }
  // Subscribe button: "Subscribe" (white) / "Subscribed" (grey) + count next to the channel name
  function setupSubscribe() {
    var b = document.getElementById('subscribe');
    if (!b || b.__ivx) return;
    b.__ivx = true;
    var data = {};
    try { data = JSON.parse(document.getElementById('subscribe_data').textContent); } catch (e) {}
    var subTxt = data.subscribe_text || 'Subscribe', unsubTxt = data.unsubscribe_text || 'Unsubscribe';
    function label() {
      var t = b.textContent.trim() || ((b.querySelector('input') || {}).value || '');
      var subd = t.indexOf(unsubTxt) === 0;
      b.setAttribute('data-ivx-label', subd ? 'Subscribed' : 'Subscribe');
      b.classList.toggle('ivx-subd', subd);
      return t;
    }
    var t = label();
    var count = data.sub_count_text || (t.split('|')[1] || '').trim();
    var name = document.querySelector('#contents .title.flexible .channel-name, #channel-name');
    if (count && name && !document.querySelector('.ivx-subcount')) {
      var s = document.createElement('span');
      s.className = 'ivx-subcount';
      s.textContent = count + (location.pathname === '/watch' ? '' : ' subscribers');
      var host = name.closest('.channel-name-pronouns') || name;
      if (host === name) name.parentNode.insertBefore(s, name.nextSibling); else host.appendChild(s);
    }
    new MutationObserver(label).observe(b, { childList: true, subtree: true, characterData: true });
  }

  /* ---- 13. infinite scroll on paginated pages (search, channel, subs, history) */
  function setupInfinite() {
    var p = location.pathname;
    if (p === '/watch' || (p === '/feed/trending' && loggedIn() && !/type=/.test(location.search))) return;
    var grids = document.querySelectorAll('#contents .pure-g:has(> .pure-u-md-1-4)');
    var grid = grids[grids.length - 1];
    if (!grid) return;
    function nextFrom(doc, base) {
      var as = doc.querySelectorAll('.page-next-container a[href]');
      var a = as[as.length - 1];
      if (a) return new URL(a.getAttribute('href'), base).href;
      if (p === '/search') {
        var u = new URL(base);
        var n = parseInt(u.searchParams.get('page') || '1', 10);
        u.searchParams.set('page', n + 1);
        return u.href;
      }
      return '';
    }
    var next = nextFrom(document, location.href);
    if (!next) return;
    document.documentElement.classList.add('ivx-inf');
    var seen = {};
    grid.querySelectorAll('a[href*="/watch?v="], a[href^="/channel/"], a[href^="/playlist"]').forEach(function (a) { seen[a.getAttribute('href')] = 1; });
    var sent = document.createElement('div');
    sent.className = 'ivx-scroll-sentinel';
    grid.parentNode.insertBefore(sent, grid.nextSibling);
    var busy = false, done = false, pend = false;
    function finish() { done = true; sent.remove(); }
    function load() {
      if (busy || done) return;
      if (sent.getBoundingClientRect().top > innerHeight + 900) return;
      busy = true;
      var url = next;
      fetch(url, { credentials: 'same-origin' }).then(function (r) { return r.ok ? r.text() : Promise.reject(); }).then(function (html) {
        var doc = new DOMParser().parseFromString(html, 'text/html');
        var tiles = doc.querySelectorAll('#contents .pure-u-md-1-4');
        var f = document.createDocumentFragment(), added = 0;
        tiles.forEach(function (t) {
          var a = t.querySelector('a[href]');
          var k = a && a.getAttribute('href');
          if (k && seen[k]) return;
          if (k) seen[k] = 1;
          var n = document.importNode(t, true);
          var hb = n.querySelector('.h-box');
          if (hb) decorate(hb);
          f.appendChild(n); added++;
        });
        grid.appendChild(f);
        next = nextFrom(doc, url);
        if (!added || !next || next === url) finish();
        busy = false;
        setTimeout(load, 120);
      }).catch(function () { busy = false; finish(); });
    }
    window.addEventListener('scroll', function () {
      if (pend) return; pend = true;
      setTimeout(function () { pend = false; load(); }, 60);
    }, { passive: true });
    setTimeout(load, 300);
  }

  /* ---- 14. pull to refresh (feeds) ------------------------------------------ */
  function setupPTR() {
    if (location.pathname === '/watch' || !('ontouchstart' in window) || window.__ivxPTR) return;
    window.__ivxPTR = true;
    var ind = null, y0 = null, d = 0, armed = false;
    function el() {
      if (!ind) {
        ind = document.createElement('div');
        ind.id = 'ivx-ptr';
        ind.innerHTML = '<svg viewBox="0 0 24 24"><path d="M20 12a8 8 0 1 1-2.34-5.66"/></svg>';
        document.body.appendChild(ind);
      }
      return ind;
    }
    document.addEventListener('touchstart', function (e) {
      y0 = null;
      if (layers.length || e.touches.length !== 1 || scrollY > 0) return;
      if (e.target.closest && e.target.closest('.ivx-sheet,.ivx-panel,#ivx-search,.ivx-hscroll,#ivx-chips,.video-js')) return;
      y0 = e.touches[0].clientY; d = 0; armed = false;
    }, { passive: true });
    document.addEventListener('touchmove', function (e) {
      if (y0 == null) return;
      if (scrollY > 0) { y0 = null; reset(); return; }
      d = (e.touches[0].clientY - y0) * 0.5;
      if (d <= 0) { reset(); return; }
      var i = el();
      var off = Math.min(d, 90);
      i.style.transition = 'none';
      i.style.transform = 'translateY(' + (off - 70 + 56) + 'px) rotate(' + (d * 3) + 'deg)';
      i.style.opacity = Math.min(1, d / 64);
      armed = d >= 64;
    }, { passive: true });
    document.addEventListener('touchend', function () {
      if (y0 == null) return;
      y0 = null;
      if (armed) {
        var i = el();
        i.style.transition = 'transform .2s';
        i.style.transform = 'translateY(' + (64 - 70 + 56) + 'px)';
        i.classList.add('spin');
        navStart();
        location.reload();
      } else reset();
    });
    function reset() { if (!ind) return; ind.style.transition = 'transform .2s, opacity .2s'; ind.style.transform = ''; ind.style.opacity = 0; }
  }

  /* ---- 15. search page: harvest channel avatars from the API in one call ---- */
  function setupSearchAvatars() {
    if (location.pathname !== '/search') return;
    var missing = [].some.call(document.querySelectorAll('.ivx-av img[data-ucid]'), function (im) { return !im.getAttribute('src'); });
    if (!missing) return;
    fetch('/api/v1/search' + location.search, { credentials: 'same-origin' })
      .then(function (r) { return r.ok ? r.json() : []; })
      .then(function (list) { avHarvest(Array.isArray(list) ? list : []); })
      .catch(function () {});
  }

  /* ---- boot ------------------------------------------------------------------ */
  function players() {
    var vjs = window.videojs;
    if (!vjs) return [];
    if (vjs.getPlayers) {
      var all = vjs.getPlayers(), out = [];
      for (var k in all) if (all[k]) out.push(all[k]);
      return out;
    }
    return [];
  }

  var uiDone = false;
  function bootUI() {
    if (uiDone || !document.body || !document.getElementById('ivx-nav')) return;
    uiDone = true;
    document.documentElement.classList.add('ivx-on');
    var steps = [setupTopBar, setupNav, setupChips, function () { decorateAll(); }, setupWatch, setupComments, setupSubscribe, setupSubsRow, setupYouPage,
                 setupLoginExtras, setupInfinite, setupPTR, setupSearchAvatars];
    steps.forEach(function (f) { try { f(); } catch (e) { if (window.console) console.warn('ivx', f.name, e); } });
  }
  function boot() {
    var ok = patchSeekBar();
    setupNavFeedback();
    bootUI();
    setupHomeFeed();
    setupDownloads();
    players().forEach(function (p) {
      setupSwipe(p); setupOrientation(p); setupReporting(p);
      try { setupPlayerChrome(p); } catch (e) {}
    });
    return ok && players().length > 0;
  }

  if (!boot()) {
    document.addEventListener('DOMContentLoaded', boot);
    window.addEventListener('load', boot);
    // player.js may create the player after load on slow devices
    var tries = 0, iv = setInterval(function () { if (boot() || ++tries > 40) clearInterval(iv); }, 250);
  }
})();
