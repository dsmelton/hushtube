# UI — Hermes layer on top of Invidious

| Pass | Date | Status |
|---|---|---|
| C — prefs baseline (config only) | 2026-11-17 | still current |
| D1 — dark card redesign (footer CSS + APK inject.js) | 2026-11-17 | superseded |
| v2 — "shell": server-side nav + layout | 2026-11-17 | superseded (tokens/traps still valid) |
| v3 — YouTube-app parity (ivx.js v16) | 2026-11-18 | current look |
| **v4 — seamless transitions (banner shell, ivx.js v20)** | 2026-11-18 | **current delivery** |

Playback re-verified (`scripts/verify.sh`, 5/5) after every pass.

## Files (`ui/`)

| File | Role |
|---|---|
| `ivx.css` | All CSS (~95 KB). Inlined into the Invidious `banner:` config. |
| `banner.html` | Banner template: `<style id="ivx-boot">{{CSS}}</style>` + static shell `#ivx-shell` (`#ivx-nav` tab bar/sidebar, `#ivx-tb` top-bar icons, `#ivx-sback`, `#ivx-sk` skeleton) + `<script src="/js/ivx.js?v={{V}}">` |
| `ivx.js` | All page JS (~95 KB). Bind-mounted into the container at `/invidious/assets/js/ivx.js` (compose `volumes:`), same-origin so CSP `script-src 'self'` allows it. |
| `VERSION` | Cache-buster for `ivx.js?v=N` — Invidious serves `/js/` with a 1-month max-age. **Bump on every change** (also between test iterations). |
| `footer.html` | Now just a comment; the `footer:` option is unused since v4. |
| `inject_css.py` | Writes `banner:` (CSS + VERSION inlined) and `footer:` into the compose `INVIDIOUS_CONFIG`. |
| `testserver.sh` | Throwaway Invidious on `http://127.0.0.1:3091` using the working-tree UI and the same DB — QA without touching the live container. `ui/testserver.sh stop` removes it. |
| `archive/` | Rollback snapshots: `footer-d1.css`, `footer-v14.html`, `footer-v16.html` + `ivx-v16.js`. |

Deploy:

```bash
cd ~/hushtube
echo $(( $(cat ui/VERSION) + 1 )) > ui/VERSION                # if ivx.js / ivx.css changed
python3 ui/inject_css.py                                    # -> compose banner: + footer:
podman-compose up -d --force-recreate --no-deps invidious  # --no-deps: companion/db keep running, no 503 window
```

## v4 — seamless transitions (how a screen change works)

Goal (user requirement): **a tap shows the next screen immediately; content loads into it; stock Invidious never paints, not even for one frame.**

1. **Early CSS.** Invidious has no head hook, and the `footer:` is rendered at the very end of `<body>`, so with footer CSS the browser could paint the stock layout first. The `banner:` option is rendered raw **right after the navbar, before any page content** — all CSS lives there now.
2. **CSS knows the page before JS runs.** The navbar is parsed before the banner, and every navbar link carries `?referer=<current path>` (anonymous and logged-in). Selectors like `body:has(.navbar [href*="referer=%2Fwatch%3F"])` pick the skeleton, the active tab, the search-pill mode and the watch-page layout at the first paint. The logo is CSS-only; the results-page search pill is the real `#searchbox` restyled.
3. **Skeleton on the new page.** The static `#ivx-sk` (in the banner) covers content until `ivx.js` (sync, cached) has built cards/pills/panels at DOMContentLoaded, then `reveal()` fades it (140 ms). Failsafe: it fades itself after 8 s if JS never runs.
4. **Skeleton on tap.** A capture-phase click/submit handler calls `go(url)`: the SAME `#ivx-sk` gets `data-k=<next kind>` (home · subs · search · feed · channel · you · watch · page), the tab bar / top bar switch to the target, and the browser navigates underneath. Both documents therefore show identical frames across the swap. Cleared on bfcache `pageshow` and after 20 s.
5. **For You** paints synchronously from its sessionStorage cache; without a cache it shows skeleton cards (never the stock Trending grid). Anonymous home keeps Trending.
6. **Prefetch:** `<link rel=prefetch>` on a tap-like touch (held 60 ms, not scrolling) or a 70 ms hover (watch/channel/search/feed/playlist only); the next video is prefetched 15 s into a watch page.
7. **APK extras (v1.18):** document-start pre-paint of the shell from sessionStorage (closes the last parser-yield gap), back-forward cache (instant back), skeletons for system Back and mini-player expand. See [APK.md](APK.md).

**Measured** with CDP screencast capture during real taps (home→watch→channel ×3, tab switch, search submit, back), at 1×/2×/4×/6× CPU throttle: no stock, blank or jumping frames. The one exception is a plain browser (no APK pre-paint) at ≥4× CPU throttle, which can show a single flat dark-grey frame between documents.

## v3 — YouTube-app parity (feature list)

- **Phone & Fold (touch ≤ 999 px):**
  - **Top bar:** logo + "YouTube" wordmark, search and notification bell, hides on scroll down.
  - **Search screen:** suggestions from `/api/v1/search/suggestions` plus recent searches (localStorage). The results page shows ← plus a query pill.
  - **Tab bar:** Home · Subscriptions · You. Hidden on the watch page.
  - **Chips:** All (For You) · Trending · Music · Gaming · Live · Movies · Subscriptions.
- **Cards (`.ivx-card`, rebuilt from Invidious' tiles by `decorate()`):**
  - Full-bleed thumbnails on narrow phones.
  - Duration badge and a red resume bar (from Invidious' `save_player_pos`).
  - Channel avatar, cached in localStorage for 14 d.
  - ⋮ sheet: Save to playlist (`/api/v1/auth/playlists`), Share, Play as audio, Go to channel, Remove from playlist, Not interested.
- **Feeds:**
  - Infinite scroll on every paginated grid.
  - Pull-to-refresh.
  - You page on `/feed/playlists`: profile, history carousel, playlists, settings rows.
  - Subscriptions avatar row.
- **Watch page:**
  - "views · age ...more" line under the title.
  - Channel row with the sub count and a Subscribe / Subscribed pill.
  - Action pills: Like (read-only), Share, Download sheet, Save, Audio only.
  - Comments teaser; description and comments open as panels under the sticky player.
  - Android back closes sheets and panels (history-state layers).
- **Player:**
  - Instant tap toggles the controls.
  - Double-tap ∓10 s with a ripple; keep tapping to add 10 s per tap.
  - Hold for 2×.
  - Prev/next buttons and an autoplay toggle.
  - Swipe up → fullscreen; swipe down → mini player (APK) / PiP (browsers).
- **Desktop:** the same cards, chips and actions, with a YouTube-desktop-style sidebar.

## v3/v4 traps (respect them in future edits)

- **Never rely on `:has(#player-container)` for first-paint layout** — it isn't parsed yet when the banner runs; use the navbar `referer` selectors (already combined as `body:is(:has(#player-container),:has(.navbar [href*="referer=%2Fwatch%3F"]))`).
- **The skeleton markup lives inside `#contents`** (banner) — scope selectors for real cards to `.pure-u-md-1-4 .ivx-card`.
- **Class-name collisions with old builds**: `.ivx-more` was the v1.1 APK's description toggle (still hidden) — the ⋮ button is `.ivx-kebab`.
- Upstream mobile CSS makes `.navbar` `flex-direction:column` and v2 set `.navbar>div{flex:none!important}` — a flex top bar needs `flex-direction:row!important`.
- `.ivx-dec>:not(.ivx-card)` loses to upstream `!important` rules — double the class (`html .ivx-dec.ivx-dec>…`).
- Inserting nodes right after `.feed-menu` breaks every `.feed-menu + .pure-g.h-box` selector.
- **Owning taps on the video:** stop `touchstart` *and* `touchend` in the capture phase on the player element; hide controls with `player.trigger('mouseup'); player.userActivity_=false; player.userActive(false)` (a dangling mousedown keeps video.js re-activating every 250 ms).
- subscribe_widget.js rewrites `#subscribe` innerHTML — relabel via `::after{content:attr(data-ivx-label)}` + MutationObserver.
- `boot()` is retried until the player exists — every listener it installs must be guarded (an unguarded submit listener once caused N downloads per tap).
- Layers (sheets/panels/search) push a history entry and close on `popstate`, so Android back closes them.

## QA method

- `ui/testserver.sh` → test at `http://127.0.0.1:3091` (log in with a temp DB user — recipe in the skill, never real credentials). Bump `ui/VERSION` every iteration.
- Phone emulation needs an **Android WebView UA + touch emulation before page load** (videojs-mobile-ui only builds its overlay for Android/iOS UAs).
- **Frame-level transition check:** CDP `Page.startScreencast`, ack every `Page.screencastFrame`, tap with `Input.dispatchTouchEvent`, then flag flat frames (grayscale stddev < 3) and review a de-duplicated contact sheet. Repeat at `Emulation.setCPUThrottlingRate` 1/4/6. APK pre-paint: add `apk/.../assets/inject.js` via `Page.addScriptToEvaluateOnNewDocument`.
- Layout: `document.documentElement.scrollWidth === innerWidth` at 344/390/412/832; desktop 1280.

## UI v2 — the "shell" redesign (history; tokens + traps below still apply)

*Superseded as the delivery mechanism by v4 (banner), see above.* At the time the source of truth was `ui/footer.html` — a `<style>` block
**plus a `<nav id="ivx-nav">`** of static links. Invidious renders the
`footer:` config unescaped on every page, so this injects real navigation for
every client (PWA, desktop, APK) with zero JS (page CSP blocks inline JS
anyway). Deploy:

```bash
cd ~/hushtube
python3 ui/inject_css.py                                   # (v2 era: footer.html -> compose footer:; now builds banner + footer)
podman-compose up -d --force-recreate --no-deps invidious  # --no-deps: companion/db keep running, no 503 window
```

(v2-era iteration trick — replacing `#footer-info`'s innerHTML in a browser — no longer applies; use `ui/testserver.sh`.)

**Design tokens** (CSS vars on `:root`): bg `#0f0f0f`, surfaces
`#181818/#222/#2e2e2e`, hairline `rgba(255,255,255,.1)`, text `#f1f1f1`,
muted `#aaa`, accent `#f2433a`, link `#6cb6ff`; `--ivx-top` 56px top bar,
`--ivx-side` sidebar width, `--ivx-pad` page gutter. System font stack
(Roboto on Android) — pure-min forces FreeSans/Arimo on `.pure-g`, overridden.

**Shell (information architecture)**

| Width | Navigation |
|---|---|
| ≥1200px | fixed top bar (logo · centred search · bell/Log out) + 240px sidebar: *Home, Live, Gaming* / *You: Subscriptions, Playlists, History* (or a sign-in prompt) / *Settings* |
| 768–1199px, and the watch page on desktop | 72px icon rail (same items) |
| <768px | compact top bar (logo mark + search) + bottom tab bar: logged in = Home · Subscriptions · Playlists · History · Settings; anonymous = Home · Live · Gaming · Sign in · Settings |

- Logged-in vs anonymous is detected with `body:has(#notification_ticker)`
  (only rendered for signed-in users). Items carry `.ivx-auth` / `.ivx-anon`.
- Active item is detected per page with `body:has(<element unique to that
  page>)` — trending type links/`<b>`, `/subscription_manager`,
  `/create_playlist`, `/clear_watch_history`, `form[action^="/preferences"]`,
  `form[action^="/login"]`. Selectors are scoped to `#contents>…` so the nav's
  own links never self-match.
- Top-bar clutter removed: theme toggle (always dark), cog (→ Settings), Log in
  (→ nav). Log out stays in the top bar on desktop; on phones it appears in
  the top bar on the Settings page only. Upstream footer links hidden; a
  small "Source (AGPL) · Privacy" line lives in the sidebar.
- `.feed-menu` pills hidden (the nav replaces them). Pages get real titles via
  `::before` (Trending / Live now / Gaming / Subscriptions / History /
  Playlists / Settings / Sign in); header links become chips. Empty
  Subscriptions shows a dashed empty-state hint.

**Content**
- Video grid: CSS grid `repeat(auto-fill,minmax(min(100%,300px),1fr))`, no
  card boxes — rounded 16:9 thumbs (hover zoom), 2-line titles, channel line,
  `views • age` line (views first). Trending view counts are hidden (YouTube
  returns 0 for all trending items); live cards drop "0 views" and get a red
  LIVE badge. Top pagination copy hidden (bottom one kept).
- Watch page: `#contents` becomes a CSS grid; `.pure-g` columns are
  `display:contents` so their children are placed directly. Desktop ≥1000px:
  player / title / channel row / info chips / description+comments on the
  left, Up-next column spanning the right (row 6 is a `1fr` filler — see
  traps). <1000px: one column, order = player, title, channel, chips,
  description, **Up next, then comments**. Phone: no top bar at all (user request), player full-bleed + sticky under the
  status bar, info chips scroll horizontally.
- Description uses Invidious' own CSS-only "Show more/less" toggle
  (`extend_desc: false` in defaults + both DB users) — works without JS.
- Autoplay-next and every Settings checkbox render as switches; Settings
  fieldsets are cards with row dividers; Sign in is a centred card.
- Channel page: rounded banner, 80px avatar, underline tabs, sort chips.
- Search: Filters as a chip that opens a card.

**inject.js (APK v1.3)** — only system-bar insets + the cast button now.
Old APKs' tab bar (`#hermes-tabnav`) and `.ivx-more` are hidden by CSS.

### CSS traps learned (respect them in future edits)

- **Page JS for every client: `ui/ivx.js`** is bind-mounted into the Invidious container as
  `/invidious/assets/js/ivx.js` and loaded from the footer (`<script src="/js/ivx.js?v=N" defer>`).
  Same-origin → allowed by CSP `script-src 'self'`. Bump `?v=N` on each change (1-month cache).
  Currently: smooth scrubbing (video.js 7.12 only redraws the bar on `timeupdate`, i.e. after
  the seek target has buffered — ivx.js writes the target into `cache_.currentTime` and redraws).
- **Swipe gestures** (ivx.js): swipe up on the video = fullscreen, swipe down in fullscreen =
  exit, swipe down inline = mini player (APK: native floating PiP-style window, back to the previous page; browsers: `requestPictureInPicture`). Needs `.video-js.vjs-touch-enabled{touch-action:pan-up}` (browser pans only for
  finger-down, so page scroll from the sticky player still works) and `touch-action:none` in
  fullscreen. Fullscreen requests landscape for landscape videos (native bridge in the APK).
- **Touch player = YouTube-app style** (all rules scoped to `.video-js.vjs-touch-enabled`):
  scrim on `.vjs-touch-overlay` unless `.vjs-user-inactive.vjs-playing`, 68px centre play,
  bottom bar = time + fullscreen with the scrubber absolutely positioned along the bottom
  edge, top-right `.mobile-operations-bar` ordered cast/CC/speed/gear. The centre overlay
  (videojs-mobile-ui) only exists for Android/iOS user agents — QA with an Android UA.
- **Default quality is `medium` (progressive 360p)** — DASH took ~1.9 s to start at 240p and
  sometimes stalled; progressive starts in 0.1–0.4 s. Anything that scrapes the DASH manifest
  from a watch page must request `&quality=dash`.
- **Two player toolbars on touch devices:** Invidious' player.js moves CC/audio/speed/
  quality/share into `.mobile-operations-bar` (a second `.vjs-control-bar`, top-right) and
  videojs-mobile-ui hides the bottom play button. Scope bottom-bar rules with
  `.vjs-control-bar:not(.mobile-operations-bar)`; the ops bar needs `bottom:auto` (video.js
  anchors control bars at the bottom) and flex-centred glyphs with `line-height:1`.
  Desktop QA: enable touch emulation BEFORE loading the page, add `&quality=dash` for the cog.
- **Never set `font-family` on all `button`s** — video.js icons are glyphs of the VideoJS
  font set on the button itself (`.vjs-icon-cog`); exclude `.vjs-button`/`.vjs-menu-button`.
- **Grid items need `min-width:0`** when they hold nowrap/ellipsis text — otherwise
  their min-content width widens the `1fr` track and the whole page (Up next rows made
  the page 380px on a 344px Fold cover screen → edge looked cut off). Phones also get
  `html,body{overflow-x:clip}` (`clip`, not `hidden`, or sticky breaks). QA check:
  `document.documentElement.scrollWidth === innerWidth` at 344/390/412.
- **Insets are physical px natively** — convert to CSS px (`/ density`) before
  exposing `--iv-*`; raw values are ~2.6× too big on the Fold. Phone watch pages
  also get a fixed black `body::before` strip of height `--iv-sb`.
- **Sticky player + edge-to-edge:** the player sticks at `top:var(--iv-sb)`, so the
  status-bar strip above it is see-through and scrolled text shows there. Paint it with
  an upward box-shadow (`0 calc(-1 * var(--iv-sb,0px)) 0 0 #000`) — not clipped by the
  player's own `overflow:hidden`, and no layout change.
- **`.navbar` is also a `.pure-g`.** Any `#contents>.pure-g` selector (e.g.
  the watch-page `display:contents` trick) hits the top bar too — always use
  `#contents>.pure-g:not(.navbar)`.
- **The channel row on the watch page is `.pure-g.h-box.title`** — a selector
  for "the description `.h-box`" must exclude it (`.h-box:not(.title)`), or
  both land in the same grid cell.
- **Spanning sidebar column in a grid without gaps:** an item spanning auto
  rows inflates them (gaps between title/channel/etc.). Give the grid a
  trailing `1fr` row and make the spanning item cover it — items crossing a
  flexible track are excluded from intrinsic auto-row sizing, so only the
  `1fr` row grows.
- **`.bottom-right-overlay` has `z-index:100` upstream** — the fixed top bar
  needs a higher z-index (1000) or duration badges scroll over it.
- pure-min sets `font-family` on `.pure-g` and `[class*=pure-u]`; override
  there, not just on `body`.
- `#continue` id exists on both the watch page and Settings — styles for it
  apply to both.

- Pure-CSS grid: `.pure-g` uses negative margins + padded columns —
  `#contents` needs compensating padding or cards overflow the viewport.
- `.video-card-row:first-of-type` matches nothing (first div child is
  `.thumbnail`) — target `.video-card-row>a>p` for card titles.
- `~` sibling selectors from `.feed-menu` also hit the card grid (it is a
  later sibling) — use `+` (adjacent) for the trending-chips row.
- `fieldset` in a flex form needs `min-width:0` or the search input
  collapses.
- Chrome reports `display:-webkit-box` as `flow-root` in computed style —
  line-clamp still works; verify by element height, not by display string.
- Related-video rows are 3 siblings (thumb/title/meta) — use CSS grid
  (`164px minmax(0,1fr)`, meta `grid-column:2`) not flex.
- **System-bar insets (v1.2):** targetSdk 35 forces edge-to-edge, so the
  APK WebView renders under the status/gesture bars. Native pushes them
  into CSS vars `--iv-sb/-l/-r/-b` (and inject.js re-reads them via the
  `InvidiousInsets` bridge each tick, since navigation wipes them). Navbar
  top padding, sticky-player `top`, body side padding, and the tab bar all
  consume these vars with `var(--iv-x,0px)` fallbacks.
- **Font boosting:** WebView inflates text on large/foldable screens (Z
  Fold inner display). `androidx.webkit` has no public API for it — the
  fix is `html{-webkit-text-size-adjust:100%}` in footer.css.
- **Comment rows:** avatar column is %-wide (`pure-u-4-24`), so folds
  inflate it — pin it (`width:44px!important;flex:0 0 44px`) and give the
  text column `flex:1 1 0!important` — basis MUST be 0, not `auto`: with
  `flex-wrap:wrap`, wrapping is decided on the hypothetical (content)
  size before shrinking, so `auto` wraps long comments under the avatar.
- Mobile player full-bleed margin must equal the `#contents` gutter — v2 uses
  `margin:0 calc(-1 * var(--ivx-pad))` so they can't drift apart.

## C — prefs baseline (still current)

**Theme / layout / player defaults** — `default_user_preferences` in
`INVIDIOUS_CONFIG` (see [`compose/docker-compose.yml`](../compose/docker-compose.yml)):

| Key | Value | Why |
|---|---|---|
| `dark_mode` | `dark` | always-dark, OLED-friendly |
| `autoplay` | `true` | standard phone viewing flow |
| `continue` + `save_player_pos` | `true` | resume every video where you left off |
| `extend_desc` | `false` (v2) | collapsed description with Invidious' CSS-only Show more/less toggle |
| `default_home` | `Trending` | **`/api/v1/popular` returns `[]` for anonymous sessions** — Popular is dead on an anonymous instance; do not re-add it to `feed_menu` either |

**Global custom CSS** — in pass C via the `footer:` config option, which Invidious
renders **unescaped** into every page's footer (since v4: the `banner:` option, same raw rendering but early in the page). Current rules: dark
scrollbars, red text selection, rounded thumbnails, tap-target polish
(`touch-action: manipulation`, no tap highlight), mobile watch-page
tightening, hides the upstream donate link and the useless
"watch on another instance" entry.

## Why these injection points (and not others)

- **`custom_css` / `custom_js` user settings no longer exist** in current
  Invidious (removed upstream as of v2.20261114.x). There is no per-user
  CSS/JS hook anymore.
- The instance CSP is
  `style-src 'self' 'unsafe-inline'` (allowed) but `script-src 'self'` (blocks inline) —
  so footer `<style>` works, footer `<script>` is silently blocked.
  **Update 2026-11-18: same-origin JS works** — see `ui/ivx.js` (bind-mounted into `/invidious/assets/js/`). Previously: JS polish had to live in the APK's
  `assets/inject.js` (`addDocumentStartJavaScript` bypasses page CSP —
  same mechanism as the cast button). The Chrome PWA has no equivalent
  hook without a userscript manager.
- Server-rendered banner/footer CSS reaches **every** client at once: PWA,
  APK WebView, desktop browser.
- **`banner:` beats `footer:`** (v4): both are rendered raw, but the banner sits right after the
  navbar — before page content — so its `<style>` applies at the first paint. Verified on a
  throwaway container before switching. Its wrapper (`<div class="h-box"><h3>`) is neutralised
  with `display:contents`.

## Precedence gotchas

- `default_user_preferences` applies to **anonymous** clients; a stored
  `SESSPREFS` cookie overrides per key. A browser that previously toggled
  the theme keeps its old value until site data is cleared for the
  instance.
- Logged-in users' preferences live as JSON text in `users.preferences`
  (postgres). Both accounts on this instance were synced to the new
  defaults with:

  ```sql
  UPDATE users SET preferences = (preferences::jsonb
    || '{"dark_mode":"dark","autoplay":true,"continue":true,
         "save_player_pos":true,"extend_desc":true,
         "default_home":"Trending",
         "feed_menu":["Trending","Subscriptions","Playlists"]}'::jsonb)::text;
  ```

## Verifying a CSS/prefs change is live

```bash
curl -s https://<host>/feed/trending | grep -c 'id="ivx-boot"'    # banner CSS present (v4)
curl -s https://<host>/feed/trending | grep -o 'ivx.js?v=[0-9]*'   # expected ui/VERSION
curl -s -o /dev/null -D - https://<host>/ | grep -i location      # -> /feed/trending
curl -s https://<host>/feed/trending | grep -o 'class="dark-theme"' | head -1
```

Then re-run [`scripts/verify.sh`](../scripts/verify.sh) — a UI change
should never regress playback, but prove it anyway.

## v24 — bug batch T1–T3 (2026-11-18)

- **T1 watch-page channel row:** was width-starved at 344 px (sub-count + Subscribe pill squeezed the name to ~104 px). `.channel-profile` on watch pages is now a grid (`auto minmax(0,1fr)`; avatar spans both rows, sub-count stacks under the name — YouTube-app stack). Name ellipsizes only when genuinely too long.
- **T2 history pollution:** prefetch fired authenticated GETs of `/watch` (hover 70 ms / touch 60 ms + the 15 s next-video warm) and Invidious records history on ANY authenticated watch GET. `/watch` is out of the prefetch whitelist; the next-video warm uses `/api/v1/videos/<id>` (no history write, still warms the server cache). Never re-add `/watch` to prefetch.
- **T3 chips:** YouTube removed the aggregated trending pages upstream — `?type=Music|Movies|Livestreams` return the same list as bare trending (only `Gaming` differs). Chips are now All · Live · Gaming (+ Subscriptions logged-in). Dead chips are removed, never left broken.

## v25 — load speed (2026-11-18)

- **Avatars in one call:** `/api/v1/trending` and `/api/v1/search` items carry `authorThumbnails`, so trending and search pages (and each search infinite-scroll page) harvest every avatar from one list request. Per-channel `/api/v1/channels/<id>` lookups are held until it lands (2.5 s failsafe) and then only fetch what is still missing, 6 at a time. Home used to make 15 channel lookups and fill avatars in ~5.5 s.
- **Cacheable reads:** For You seeds, history hydration and the You-page shelf request `/api/v1/videos/<id>?ivxc=1`, which the edge cache may serve (1 h). The 15 s next-video warm stays a plain request so it still warms Invidious itself.
- **Thumbnails:** the first 4 cards load with `fetchpriority="high"`, the rest stay `loading="lazy"`; all `decoding="async"`.
- Pairs with the server-side edge cache (`nginx/`, see TROUBLESHOOTING "Thumbnails and pages load slowly").
