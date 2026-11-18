# Android app (WebView wrapper) — build & design notes

Kotlin WebView wrapper around the Invidious instance: one APK, no Play
Store, sideloaded. Source: [`apk/`](../apk/) (applicationId
`app.hushtube.android`, minSdk 26, target/compileSdk 35).

**Current: v1.26.2 (versionCode 35)** — mini-player & background-audio bug batch (T4/T5/T6, see the v1.26 section below). Last version confirmed on the device: v1.26.2.
**No APKs are published in this repo** — every build bakes in the owner's server URL; build
from source instead. Version history at the end of this file.

### Native bridges exposed to page JS (current)

| JS name | Methods | Used for |
|---|---|---|
| `InvidiousInsets` | `get()` | system-bar insets → CSS vars `--iv-sb/-l/-r/-b` (CSS px) |
| `InvidiousCast` | `devices()`, `castTo(id, device)`, `stop()`, `cast(id)` | cast picker → LAN cast bridge |
| `InvidiousScreen` | `setOrientation(mode)` | landscape fullscreen |
| `InvidiousPlayer` | `report(json)`, `minimize(json)`, `miniHeight()`, `download(json)`, `share(json)` | mini player / PiP handover, downloads, share sheet |
| (native → JS) | `window.ivxGo(url[, back])` | skeleton of the next screen before Back / mini-player expand |

## Features (v1)

- Fullscreen WebView to the server; URL switchable at runtime (overflow
  menu → *Change server*, persisted) — ts.net or LAN IP both fine
- `domStorageEnabled` + `mediaPlaybackRequiresUserGesture=false`
- Fullscreen video via `WebChromeClient.onShowCustomView` + immersive
  system bars (the make-or-break piece — works)
- Back button: exit fullscreen → web history → exit app
- Downloads handed to the system `DownloadManager`; external links
  (other hosts, non-http schemes) handed off via `ACTION_VIEW`
- Tailscale-down error screen with an **Open Tailscale** button
  (`com.tailscale.ipn` launch intent, Play Store fallback)
- Cast button injected on `/watch` pages → cast bridge from
  [CAST.md](CAST.md) (see below)
- Update check against a self-hosted `version.json`
  (`{"versionCode":N,"versionName":"x.y","url":"…"}`); every failure mode
  is silent so an unreachable check never nags
- Release keystore from day one — signing identity is stable across updates

Out of scope v1 (deliberate): background audio/MediaSession, PiP, native
Cast SDK.

## Cast button: why a JS bridge, not `fetch()`

`assets/inject.js` is injected at document start via
`WebViewCompat.addDocumentStartJavaScript` and adds a floating Cast
button on watch pages. Clicking it calls
`window.InvidiousCast.cast(videoId)` — a `@JavascriptInterface` bridge —
and the **native** side POSTs `{"videoId":"…","mode":"youtube"}` to the
cast bridge.

A page-side `fetch()` cannot work here: the page is https (ts.net cert)
while the bridge is plain http on the LAN, so the request dies on
mixed-content blocking *and* the bridge sends no CORS headers. The
native POST sidesteps both. The bridge URL defaults to the server's LAN
IP (`http://<server-ip>:8300`) because phones don't resolve bare
hostnames; it is editable in the overflow menu.

## inject.js contents (v1.1+) — *history; current contents at the end of this section*

Beyond the cast button, `inject.js` carries the client-side UI polish
that page CSP (`script-src 'self'`) forbids server-side: a mobile bottom
tab bar (Home / Subs / Playlists / History), the watch-page description
`more`/`less` toggle, and the `.ivx-js` html-class that gates
CSS-dependent clamps (non-JS clients keep full text). Details + design
tokens in [UI.md](UI.md). v1.1 = versionCode 2 — first release built
with the D1 redesign; side-loading it over v1.0 upgrades in place
(same keystore).

**Current `inject.js` (v1.18+)** — runs at document start on every page (bypasses page CSP):
(0) **pre-paint** — copies the page shell (CSS + skeleton markup, saved by ivx.js in
sessionStorage `ivxShell`) into the document before a byte of the new page is parsed, with the
skeleton kind of the tapped link (`ivxTx`); ivx.js removes `#ivx-pre` when the real shell
exists. Waits for `document.documentElement` (Chrome can run document-start scripts before
`<html>` exists). (1) system-bar insets → CSS vars. (2) cast button + device picker. All other
UI JS lives server-side in `ui/ivx.js`.

## v1.2 — Z Fold / edge-to-edge fixes (versionCode 3)

- `WindowCompat.setDecorFitsSystemWindows(window, false)` +
  `ViewCompat.setOnApplyWindowInsetsListener` capture system-bar +
  cutout insets and expose them two ways: native `evaluateJavascript`
  push on every inset change, and an `InvidiousInsets.get()` JS bridge
  returning `{t,l,r,b}` JSON that inject.js polls each tick (survives
  navigation). CSS consumes them as `--iv-sb/-l/-r/-b`.
- `useWideViewPort` + `loadWithOverviewMode` were already on; the "huge
  everything on the inner display" bug was WebView font boosting, fixed
  server-side in footer.css (`text-size-adjust:100%`) — no APK change
  needed for it, but the tab bar/cast button now dodge the gesture bar
  via the inset vars.
- Fold/unfold needs no extra handling: the activity recreates on
  size change and the inset listener re-fires.

## Toolchain (reproducible, no root)

| Piece | Version | Location |
|---|---|---|
| JDK | Temurin 21 | `~/tools/jdk21`, pinned via `org.gradle.java.home` in `gradle.properties` |
| Gradle | 8.14.7 | wrapper committed |
| AGP / Kotlin | 8.13.2 / 2.2.20 | |
| Android SDK | cmdline-tools + platform-tools + platforms 34/35 + build-tools 34/35 | `~/Android` |

The system JDK 25 is **too new** for this AGP/Gradle pair — keep the
pinned JDK 21 (or move the whole stack forward together).

## Build

```bash
cd apk
echo "sdk.dir=$HOME/Android" > local.properties   # gitignored
export ANDROID_HOME=$HOME/Android
./gradlew assembleDebug      # or assembleRelease
```

Release signing reads `keystore.properties` (`storeFile`,
`storePassword`, `keyAlias`, `keyPassword`) — **gitignored; back it up.
Losing the keystore means no updatable releases.** `storeFile` resolves
relative to the project root.

## Release flow

1. Bump `versionCode`/`versionName` in `apk/app/build.gradle.kts`.
2. `./gradlew assembleRelease`.
3. Host the new APK + a `version.json` pointing at it, at the URL the
   update check reads.

## Gotchas hit during the build

- `InputType.TYPE_TEXT_VARIATION_URI` — VARIATION, not VARIANCE.
- `URLUtil` is `android.webkit.URLUtil`, not `android.util`.
- `onBackPressedDispatcher.addCallback(owner) { }` lambda needs
  `activity-ktx`; with plain appcompat use
  `object : OnBackPressedCallback(true)`.
- AGP `validateSigningRelease` resolves `storeFile` against the *root
  project* dir — a `../` prefix silently points outside the project.

## v1.3 — UI v2 (versionCode 4)

- `assets/inject.js` slimmed down: the bottom tab bar and the description
  more/less toggle moved **server-side** (UI v2 `ui/footer.html` renders the
  navigation as static HTML and Invidious' own CSS-only "Show more" toggle is
  used via `extend_desc: false`). inject.js now only syncs system-bar insets
  and adds the cast button ("Cast to TV", ionicon, restyled by footer CSS).
- Older APKs keep working with UI v2: footer CSS hides v1.1/v1.2's
  `#hermes-tabnav` and `.ivx-more`, so there is never a double tab bar.
- Built + `apksigner`-verified: `invidiousweb-v1.3.apk`, sha256
  `792335036163aa6d56afc94f47b8c0131046abd6f8b4f38123124192e23506a9`.

## v1.4 — in-player Cast button + device picker (versionCode 5)

- Floating "Cast to TV" pill replaced by the standard cast icon inside the
  video.js control bar (`order:6`, just before fullscreen `order:7` — Invidious
  sets flex `order` on every control, so an injected control without one lands
  first).
- Tap → bottom sheet with the cast devices on the Wi-Fi (via the bridge),
  connecting spinner, connected state (`sessionStorage`, survives navigation),
  "Stop casting", empty/error states with Try again.
- Native `CastBridge` is now async: `devices()`, `castTo(id, device)`, `stop()`
  reply through `window.__ivxCast.onDevices/onCastResult/onStopResult` via
  `evaluateJavascript` (never block the WebView thread). Legacy `cast(id)` kept.
- Phone control bar (footer CSS, all clients): seek bar on its own full-width
  row; remaining-time, share, volume, audio-track hidden — the 14-control bar
  overflowed 400px screens (fullscreen was off-screen).
- `invidiousweb-v1.4.apk`, sha256
  `f2692fa58e9dec51084e8b21723ad5b3ee2bd6afd62d3fdab5173eb1fe51956e`, apksigner OK.

## v1.5 — insets in CSS px (versionCode 6)

- Bug since v1.2: `WindowInsetsCompat` values are **physical pixels** but were
  pushed to the page as CSS px, so every inset was ~density× too big (≈2.6×
  on the Z Fold: huge black band above the video, oversized bottom bar
  padding). Now converted with `ceil(px / displayMetrics.density)`.
- `invidiousweb-v1.5.apk`, sha256
  `6d700f50ee79bda698e0fa20889e55da7a6594931464541b8234ef87041e2bca`, apksigner OK.

## v1.6 — cast icon top-right of the player (versionCode 7)

- The cast button is now an overlay in the player's top-right corner (like the
  YouTube app) instead of a control-bar button: 44px, dark translucent circle,
  hidden together with the controls while playing (`.vjs-playing.vjs-user-inactive`),
  touch/mouse events stopped so tapping it never toggles playback.
- `invidiousweb-v1.6.apk`, sha256
  `9948ef960604a6aae8b821455ee0df37250d62c5faa052f618018ac2d8f5eb85`, apksigner OK.

## v1.7 — cast icon in the player toolbar (versionCode 8)

- On touch devices Invidious moves CC / speed / quality / share into a top-right
  control bar (`.mobile-operations-bar`); v1.6's overlay sat on top of it. The
  cast icon is now the rightmost item of that bar (touch) or sits left of
  fullscreen in the bottom bar (desktop). If the ops bar appears after the
  button was placed, the button is moved into it.
- `invidiousweb-v1.7.apk`, sha256
  `9eaf1df154c5289116d662a6e4b1f60ab0b287e050b9c2d545c40dc282f96c1e`, apksigner OK.

## v1.8 — fullscreen landscape + edge-to-edge fix (versionCode 9)

- New `window.InvidiousScreen.setOrientation("landscape"|"portrait"|"auto")` bridge, called by
  the page's `ui/ivx.js` on `fullscreenchange` (swipe-up gesture, fullscreen button, rotate):
  landscape videos lock to `SENSOR_LANDSCAPE` like the YouTube app; `exitFullscreenVideo()`
  always restores `UNSPECIFIED`.
- `showSystemUi()` no longer calls `setDecorFitsSystemWindows(true)` (it dropped the app out of
  edge-to-edge after the first fullscreen exit).
- `invidiousweb-v1.8.apk`, sha256
  `969df95e28f52cf8658b161b996f81af4395ade5c6ea44145d74bef5f596aeef`, apksigner OK.

## v1.9 — mini player + picture-in-picture (versionCode 10)

- `MiniPlayer.kt`: native Media3 (1.9.4 — 1.10+ requires compileSdk 36) mini player that
  survives page loads. Swipe down on a watch page's video (page JS → `InvidiousPlayer.minimize`)
  → bar above the bottom tab bar keeps playing while you browse; tap/swipe up expands to
  `/watch?v=…&t=…`, swipe down/✕ closes, opening any video replaces it.
- Picture-in-picture (`supportsPictureInPicture`): Home while the mini player or the page video
  is playing → PiP with the native player (auto-enter on Android 12+); returning hands the
  position back to the page; dismissing PiP stops playback.
- New page bridge `window.InvidiousPlayer` {`report(json)`, `minimize(json)`, `miniHeight()`}.
- APK grows to ~1.8 MB (Media3). `invidiousweb-v1.9.apk`, sha256
  `e16fc1de10f0dc35b4f1a6fa02d749d5f60bee1d34c6dfa52e93278720974f98`, apksigner OK.
  Not yet tested on a device.

## v1.10 — swipe down = picture-in-picture (versionCode 11)

- Swiping down on the video inside the app now enters system picture-in-picture
  immediately (was: in-app mini bar). Returning to the app lands on the watch page with
  the video continuing from the PiP position. The in-app mini bar remains as a fallback
  when PiP is unavailable.
- `invidiousweb-v1.10.apk`, sha256
  `3a19fc375191fb6ff2d6e6fca496658ddadd759b9612cf37f094f4a4d4b1d551`, apksigner OK.

## v1.11 — swipe down = in-app mini player again (versionCode 12)

- v1.10's swipe-down → system PiP is reverted (it left the app). Swiping down on the video
  keeps it playing in the in-app mini player and returns to the previous non-video page
  (e.g. search results). System PiP still happens when leaving the app while playing.
- `invidiousweb-v1.11.apk`, sha256
  `20684b9c6ae379748a4245e981ef8ce8043343f807d5d63358ce2fc8b23e6221`, apksigner OK.

## v1.12 — floating picture-in-picture-style mini player (versionCode 13)

- The mini player is now a rounded floating video window (like the current YouTube app)
  instead of a bottom bar: drag it anywhere, it snaps to the nearest corner; tap for
  expand / play-pause / close controls; thin red progress line along the bottom edge.
  It stays between the page's top bar and bottom tab bar.
- `invidiousweb-v1.12.apk`, sha256
  `e39174def2d53581b867ebfe553fe238d7240af1d9a6e2673985b88b0a6394d6`, apksigner OK.

## v1.13 — zero-gap swipe-down handover (versionCode 14)

- Removed the freeze when swiping a video down into the mini player. Two parts:
  - **Shadow player** (`MiniPlayer.kt`): `InvidiousPlayer.report()` traffic keeps a muted
    ExoPlayer (`handleAudioFocus=false`, `volume=0`) playing the same `/latest_version`
    stream in the background while the page video plays (drift-corrected >5 s, paused with
    the page, dropped on navigation/close/onStop). On minimize, `adoptShadow()` hands that
    already-buffered player to the mini window — audio focus is taken at adopt and the
    `seekTo` lands inside its buffer, so playback is instant. Home→PiP from a watch page
    adopts too.
  - **Delayed handover** (`MainActivity`): ivx.js v5 no longer pauses the page video on
    minimize — it mutes it and keeps playing (no freeze, no echo). Native pauses the page
    video and only then navigates back (`finishMiniHandoff`), triggered by
    `MiniPlayer.onStarted` (fires once when the mini really plays; 1500 ms timeout
    fallback; paused handovers finish immediately).
- Cost: watch pages use ~2× stream bandwidth while playing (360p ≈ 0.5 Mbps extra).
- Needs ivx.js **v5** on the server (mute-not-pause + stop reporting after minimize).
- `invidiousweb-v1.13.apk`, sha256
  `71cb1cc9a7ffbcba3756cb9edca89a674846b644d85127fb277c8bd8843ddc42`, apksigner OK. Not device-tested.

## v1.14 — immediate back-nav + poster/spinner mini (versionCode 15)

- v1.13 REJECTED on device: waiting for the mini to actually start before leaving the
  watch page read as "hangs at the video page, mini stalled, uninteractable".
- Back to v1.12 flow: `minimize()` → start mini → `leaveWatchPage()` immediately.
  Shadow adopt kept (instant when warm) but nothing gates on it. Shadow drift correction
  only at >30 s (small frequent seeks were eating the forward buffer that makes adopt
  instant); adopt seeks only when >750 ms off.
- Mini never looks black/dead: `mqdefault.jpg` thumbnail (`/vi/ID/`) drawn over the
  PlayerView until `onRenderedFirstFrame` (re-fires on adopt's new surface), white
  indeterminate spinner while `playWhenReady && STATE_BUFFERING`.
- `invidiousweb-v1.14.apk`, sha256
  `7219a5fc5e9b9a2e20c65b62f25c1068be09a9ed066c6ef210abbc31dc10b8ba`, apksigner OK. Not device-tested.

## v1.15 — persistent logins (versionCode 16)

- Invidious' `SID` auth cookie is a SESSION cookie (no expiry) and the WebView flushes
  its cookie store to disk only asynchronously → force-stopping the app logged you out.
- `onPageFinished` (own host only) re-writes `SID` as a persistent cookie
  (`max-age=2592000` = 30 d, `Secure` on https) and flushes; `onPause` flushes too.
  Server-side session expiry still governs — the cookie max-age is just an upper bound.
- `invidiousweb-v1.15.apk`, sha256
  `268dad65e949a4f125dbad6aa02e067d22612d88d3d4cd0743e990fc51a2d5a6`, apksigner OK.

## v1.16 — unique download names (versionCode 17)

- "Download as:" on watch pages now saves files as **`Title (videoId).ext`** — no more generic or
  colliding names. ivx.js intercepts the form (its `target=_blank` POST was ignored by the WebView
  anyway) and hands the POST to a new `InvidiousPlayer.download` bridge; native side replays it with
  the session cookies and streams into MediaStore Downloads (auto-dedupe on any clash).
- `invidiousweb-v1.16.apk`, sha256
  `6faf83dfcb694443706b1d9803e14fd42edd9ec0e42134974ef41b06143d802f`, apksigner OK. Not device-tested.


## v1.17 — share sheet + YouTube-style tab bar (versionCode 18)

- New bridge `InvidiousPlayer.share({url,title})` → `Intent.ACTION_SEND` chooser (the system share
  sheet). Used by every Share button of UI v3 (watch page pill, card ⋮ menu); shares
  `https://youtu.be/<id>` so links work outside the tailnet. WebView has no `navigator.share`;
  browsers fall back to it / clipboard + snackbar.
- Mini-player float area follows the new 48 + 1 px tab bar (`PAGE_TAB_BAR_CSS = 49`) and the
  touch layout up to 999 CSS px (unfolded Fold now uses the phone layout).
- `invidiousweb-v1.17.apk`, sha256
  `b251ccb2debeb1e67ee533ff04729aa736d5acef119b31a23c2e2cd757805ca1`, apksigner OK. Not device-tested.

## v1.18 — seamless transitions (versionCode 19)

- **Pre-paint** in `inject.js` (see "Current inject.js" above): closes the last gap where a
  slow frame could show a flat stock-grey page between two skeletons.
- **Back-forward cache**: `WebSettingsCompat.setBackForwardCacheEnabled(settings, true)` when
  `WebViewFeature.BACK_FORWARD_CACHE` is supported (experimental API, opted in with
  `@OptIn(ExperimentalBackForwardCache)`). Back restores the previous page instantly — scroll
  position, For You grid, loaded comments.
- System Back → `ivxGo(prevUrl, true)` before `goBack()`: ivx.js shows the previous screen's
  skeleton after 150 ms unless the bfcache already restored it, and skips it when Back only
  closes a sheet/panel. Mini-player expand → `ivxGo(url)` then `loadUrl`.
- WebView background `#0F0F0F` (= page background) instead of black.
- `invidiousweb-v1.18.apk`, sha256
  `23224cbf84b1025776df00b5507dc7e232332002aca43e468535a4938a789fd7`, apksigner OK. Not device-tested.

## v1.19 — download fix (versionCode 20)

- Device report: **"Download failed: no protocol:"**. The companion answers the download POST
  with a *relative* redirect (`Location: /companion/videoplayback?...`); the native downloader
  (manual redirect following, because POST redirects aren't auto-followed) did `URL(loc)` →
  `MalformedURLException: no protocol`. Fixed: `URL(conn.url, loc)`, also for the JSON `{url}`
  fallback. Browsers were never affected (fetch resolves relative redirects) — which is why the
  v1.16 browser test passed.
- Verified with a JVM replica of the exact HttpURLConnection flow against the live server:
  POST → 302 → 200 `video/mp4`, `ftyp` header.
- `invidiousweb-v1.19.apk`, sha256
  `aa567da4acd0ae7ff63c5bd918a4bf45cbd673e6f8d48a24c3633669ec2531a4`, apksigner OK.
- Lesson: when a feature has a separate native code path, test that path (a scratch
  `java T.java` with JDK 21 is enough), not just the browser path.

## v1.20 — Downloads folder + offline playback, YouTube-app style (versionCode 21)

- **Own folder:** every download goes to `Download/InvidiousWeb/Title (videoId).ext` (MediaStore `RELATIVE_PATH`, `IS_PENDING` while writing → no half files; no storage permission). Pre-1.20 downloads in the root of `Download/` are moved into the folder once (API 30+, own files only). The generic WebView `DownloadListener` also targets the folder.
- **`DownloadStore.kt`**: download worker (same POST → manual 3xx flow as v1.19) with live progress — the companion sends no `Content-Length`, so the total comes from the videoplayback URL's `clen=`; app-private index `filesDir/downloads/index.json` (title, channel, length, size, uri) + thumbnail `thumbs/<id>.jpg` (fetched from `/vi/<id>/mqdefault.jpg`, else a frame via `MediaMetadataRetriever`) so everything works offline; reconciles with MediaStore on each open (files deleted in the Files app disappear, files in the folder without metadata are picked up from their name); resume positions in prefs `dlpos`.
- **`DownloadsActivity`** (You tab → Downloads, or "Go to downloads" on the can't-reach-server screen): newest first, running downloads on top with %/bytes + bar, thumbnail with duration badge + red resume bar, ⋮ → Play / Delete (row vanishes, Undo for 4 s) / Share / Watch online; empty state; "N videos · size · Saved in Download/InvidiousWeb · X free".
- **`OfflinePlayerActivity`** (Media3, local file): thumbnail until first frame, title/channel/size, Downloaded · Share · Watch online pills, the rest of your downloads underneath (tap = play, autoplays to the next one, prev/next in the controls). Gestures as the web player: instant tap toggle, double-tap ±10 s with streak, hold = 2x, swipe up = fullscreen, swipe down in fullscreen = exit, rotate = fullscreen; resumes where you left off, marks finished; PiP on Home; MediaSession.
- **New bridge methods** on `InvidiousPlayer`: `dlState(id)` → `{s:none|dl|done,p}`, `downloadsInfo()` → `{count,bytes,active}`, `openDownloads()`, `playDownload(id)`, `deleteDownload(id)`, `cancelDownload(id)`; `download(json)` now also takes `author/authorId/lengthSeconds`. Native pushes every state change to `window.__ivxDlPush({id:{s,p}})`.
- Page side = ivx.js v21: Download pill → "Download quality" sheet (only playable formats: video-with-sound + best audio; "More formats" = stock form) → progress ring "42%" → "Downloaded" (sheet: Play from downloads / Go to downloads / Delete). You tab (logged-in and anonymous `/login`) gets a **Downloads** row ("2 videos · 79 MB").
- Opening the native screens first pauses the page video + mini player and refreshes the PiP params so starting our own activity can't auto-enter PiP. `MainActivity.onNewIntent(EXTRA_URL)` = "Watch online".
- Verified: build + apksigner, all bridge methods + activities survive R8 (dexdump), page side with a stub bridge (pill states, quality sheet payload incl. channel/length, You rows logged-in + anonymous), native HTTP flow replicated in a JVM against the live server (200 video/mp4, `clen` total, ftyp). Native screens: device test pending (no emulator).

## v1.21 — fold/unfold keeps fullscreen + cast position (versionCode 22)

- **Bug:** folding/unfolding the Z Fold while a video was fullscreen dropped out of fullscreen and left the screen stuck in portrait. Cause: the display switch fires `screen.orientation` change (the new panel briefly reports its natural orientation); videojs-mobile-ui's rotate handler then calls `exitFullscreen()` (its own lock flag never gets set in a WebView, which can't `screen.orientation.lock`), and our fullscreenchange handler released the landscape lock. Reproduced in the browser with CDP device-metrics changes.
- **Fix (page, ivx.js v22, all clients):** fold guard installed before player.js — takes over `screen.orientation.onchange` (accessor on the instance) and only forwards REAL rotations (width/height swap); other size changes = display switch → ignored, plus a 1.5 s settle window; while the APK holds a native orientation lock in fullscreen, rotation events are ignored altogether. Verified: fullscreen survives cover→inner→cover sequences; a real rotation still enters fullscreen; unfolding while not fullscreen doesn't jump into it.
- **Fix (native):** `MainActivity.onConfigurationChanged`/`onWindowFocusChanged` re-hide the system bars while a fullscreen video is shown (the display switch can bring them back). Offline player: fold vs rotation decided by window size (swap = rotation); on a fold it keeps fullscreen as-is, re-hides the bars and re-lays out.
- **Cast:** new `InvidiousCast.castAt(id, device, t)` (inject.js uses it when present) → bridge field `t` → SmartTube continues at the phone's position. See CAST.md "Ad-free casting via SmartTube".

## v1.22 — cast picker was invisible (versionCode 23)

- **Bug (user report):** tapping cast showed the dim backdrop for a split second and nothing else.
- **Cause:** inject.js built its sheet with class names `.ivx-panel` / `.ivx-row` / `.ivx-grab`, which the server-injected `ivx.css` ALSO defines globally for its own bottom sheets: `.ivx-panel{position:fixed;top:40vh;bottom:0;transform:translateY(105%)}`. The cast CSS (`#ivx-cast-sheet .ivx-panel{...}`) never set `position`/`top`/`transform`, so the global rule won on those properties and the panel was positioned off-viewport (measured: panel top 424px in a 412px viewport). Only the backdrop was visible, and it vanished when the device list replaced it → "appears for a split second".
- **Fix:** every cast-sheet class is now cast-scoped (`ivx-cast-panel`, `ivx-cast-row`, `ivx-cast-sub`, `ivx-cast-spin`, `ivx-cast-grab`) and the panel is explicitly `position:static;transform:none` with `max-height:92vh;overflow-y:auto`. The panel slide animation was dropped (a `translateY` entrance overshoots the bottom edge for a frame); the backdrop fade carries the entrance.
- **Rule for inject.js:** never use a bare `ivx-*` class name that the page CSS might own — prefix native-overlay classes (`ivx-cast-*`). Verify overlays by measuring `getBoundingClientRect()` against `innerHeight`, not by screenshot alone.
- Verified in phone emulation against the live site (which loads the same ivx.css): picker fits at 412x915, 915x412 and 900x840 (Fold inner); device list, connecting, connected ("Stop casting" clears state and unlights the icon), bridge-down and no-devices sheets all render in-viewport.

## v1.23 — keep listening when minimized / phone asleep (versionCode 24)

- **Feature:** audio keeps playing when the app is minimized or the screen sleeps. Default ON; toggle = speaker button at the bottom-left of the mini player's tap-controls (persisted in `settings` prefs, key `bgAudio`).
- **Why a service is needed:** a backgrounded app is suspended, so `mediaPlayback` foreground service (`PlaybackService`) must hold the process; the player itself stays in `MiniPlayer` (same process → no cross-process plumbing). Manifest: `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` + `POST_NOTIFICATIONS`, `<service android:foregroundServiceType="mediaPlayback">`.
- **Hand-off:** `onUserLeaveHint` (NOT `onStop`) does it when `bgAudio && mini.bgAudio && pipEligible()`: Android 14 restricts starting an FGS from the background, and `onUserLeaveHint` is the last guaranteed-foreground moment; it also makes the switch gap-free because the muted shadow player is already buffered (`adoptShadow`). `onStop` repeats the call as the fallback for leaving via another app (idempotent: if the mini already owns playback it just resumes/notifies).
- **Background audio replaces PiP** while enabled (`onUserLeaveHint` returns early) — the floating window dies when the screen sleeps, which is exactly the case the user cares about. Turning the toggle off restores the old PiP behaviour. `wasInPip` (set in `onPictureInPictureModeChanged`) keeps "dismiss PiP" = stop, so dismissing the window doesn't restart audio from `onStop`.
- **Notification:** silent, ongoing, `VISIBILITY_PUBLIC` (controls on the lock screen), Play/pause + Close actions delivered through `PlaybackReceiver` (a **broadcast**, so buttons work without foregrounding the app); `PlaybackService.handler` is the bridge back to MainActivity (`bgToggle`/`bgStop`). Started via `startForegroundService` even for updates — the first call starts it and `startForeground(..., FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)` must land inside the 10 s window.
- `MiniPlayer.notifyBg()` is called from the player listener (play/pause/state) and keeps the notification in sync; it STOPS the service when the mini is inactive, when `bgAudio` is off, or when the app is back in the foreground (`onResume`). `revealIfHidden()` re-shows the mini bar if the hand-off happened while away (hand-off uses `visibleBar = false`).
- Notification permission is requested once, lazily, on the first hand-off (Android 13+).
- NOT device-tested (no emulator in-session — it has crashed this session's host before). Test: play a video → press home → audio continues + notification shows; lock the screen → still playing; notification Pause/Play and Close work; speaker button off → home stops audio and PiP returns.

## v1.23.1 — v1.23 crashed on launch (versionCode 25)

- **Report:** v1.23 (background audio) crashed on launch on the Z Fold; v1.22 was fine, and the only delta was the background-audio service. No logcat available (no USB/wireless debugging at the time), so the fix was made blind at the user's request.
- **Action:** `isMinifyEnabled` / `isShrinkResources` set to **false** in the release build type (APK 1.87 MB → 5.9 MB). R8 is the prime suspect (v1.22 shipped with the same R8 config; a VerifyError at class load on One UI is the classic symptom of the new service/receiver classes). Keep rules for `PlaybackService`/`PlaybackReceiver` added anyway, so R8 can be re-enabled later without the system failing to instantiate them by name.
- Also hardened so a background-audio failure can never take the app down: `startForeground(...)` wrapped (refusal → log + `stopSelf()`), and `handOffToBackgroundAudio()` wrapped in try/catch (it runs from `onUserLeaveHint`/`onStop`).
- **If v1.23.1 still crashes** the cause is not R8 → get logcat (`adb logcat -b crash` or `adb logcat *:E AndroidRuntime:*`) and read the stack trace instead of guessing again.

## v1.23.3 — the real launch-crash cause (versionCode 27)

- **Trace (pulled over wireless debugging):** `android.app.RemoteServiceException$ForegroundServiceDidNotStartInTimeException: Context.startForegroundService() did not then call Service.startForeground()`, caused-by stack: `PlaybackService.stop() → startForegroundService()` … `MiniPlayer.close() → MainActivity.bgStop() → PlaybackService.onStartCommand()`.
- **Cause:** `PlaybackService.stop()` started the service with `startForegroundService()`, and the `ACTION_STOP` branch only called `stopSelf()` — never `startForeground()`. Android arms a "must call startForeground() within seconds" contract on every `startForegroundService()`; breaking it kills the process with that RemoteServiceException. Because `MiniPlayer.close()` runs during startup/teardown, the kill landed right after launch and looked like a startup crash. R8 was never involved (v1.23.1 crashed for the same reason).
- **Fix:** (a) `stop()` now uses `Context.stopService()` — a clean no-op when not running, `onDestroy()` when running; (b) `onStartCommand()` calls `startForeground(...)` FIRST, before dispatching any action, so the contract can never be violated again; (c) R8 + resource shrinking re-enabled (back to ~1.87 MB).
- **Crash reporter kept:** `CrashReport` installs a default handler that appends the full trace to `files/crash.log` (also `Android/data/.../files`) and replays it in a Copy-able dialog on the next launch. Cheap insurance for a device that is not always reachable by adb.
- **Verified on the device (the Z Fold over wireless adb):** installed with `adb install -r -g`, launched, process alive, `topResumedActivity = MainActivity`, crash buffer empty (it had ~10 identical crashes before the fix).

## v1.24 — real media notification (MediaSession), not a plain one

- **Ask:** minimized app showed a hand-built plain notification; the user wants the official-YouTube look — the One UI media card ("This phone" + artwork + title + scrubber + transport buttons).
- **Change:** `PlaybackService` is now a Media3 `MediaSessionService`. The ExoPlayer still lives in MiniPlayer; the service wraps it in a `MediaSession` and Media3 generates the notification (and lock screen / quick settings / Bluetooth / Auto surfaces). `PlaybackReceiver` + the hand-built notification are gone; transport acts on the player directly, "Close" is a custom `SessionCommand`. Artwork = `MediaMetadata.artworkUri` (video thumb) on both mini and shadow players. Manifest: service `exported=true` + `<intent-filter><action android:name="android.media.browse.MediaBrowserService"/></intent-filter>`, `MEDIA_CONTROL` permission.
- **THE BUG (took a device to find):** the session was created and `dumpsys media_session` showed `package=<old-package> ... active=true`, yet only the placeholder notification existed. Media3's `MediaNotificationManager.updateNotification()` starts with `if (!mediaSessionService.isSessionAdded(session) || !shouldShowNotification(session)) { removeNotification(); return }` — **`isSessionAdded` is only true after you call `MediaSessionService.addSession(session)`**. Keeping your own list of sessions and returning it from `onGetSession()` is invisible to the notification manager, so it silently never builds the media notification. Fix: `addSession(s)` on create, `removeSession(s)` before release. Verified after the fix: notification `id=1001 channel=default_channel_id` with `category=transport` appears alongside the active session.
- **Placeholder duplication:** the `startForegroundService` contract still needs a notification posted in `onStartCommand` before Media3's async one. Post it on **Media3's own channel + id** (`DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID` / `DEFAULT_NOTIFICATION_ID`) so the real media notification replaces it in place. Do NOT `stopForeground()`/cancel it yourself — Media3 owns the foreground state and will `stopSelf()` the service if you pull it out from under it.
- **Media3 1.9.4 API reality check** (no `SessionConfig`, no `COMMAND_CODE_*`): use `MediaSession.Builder(ctx, player).setId().setSessionActivity().setMediaButtonPreferences().setCallback()`; `CommandButton.Builder().setDisplayName().setIconResId().setPlayerCommand(Player.COMMAND_SEEK_BACK)`; icons live in `androidx.media3.session.R.drawable.media3_icon_*` (NOT the app's `R`); `onCustomCommand` returns `ListenableFuture<SessionResult>` and the command's string is `cmd.customAction`.
- **Second bug, same symptom:** the placeholder must be posted **once per service instance**. Player events re-enter `onStartCommand` (MiniPlayer re-calls `startForegroundService` on every state change) and re-posting the placeholder overwrote Media3's notification with "Invidious / Playing" while the session stayed active. `MediaNotificationManager.isStartedInForeground()` is package-private, so track it with a local `placeholderPosted` flag. After both fixes, on-device: `android.template=android.app.Notification$MediaStyle`, title `$1 vs $10,000 Mystery Box Challenge`, text `Nick DiGiovanni`, `largeIcon=BITMAP 81x45`, `showChronometer=true`, actions Back 10s / Pause / Forward 10s; the shade renders One UI's full-bleed "This phone" media card exactly like the official YouTube app, and tapping Pause in it moved the session `PLAYING → PAUSED`. (v1.24.3, versionCode 31.)
- **APK v1.24.3** (versionCode 31) `apk/invidiousweb-v1.24.3.apk`, sha256 `acd29437a1a7c1df6e138c83911ce6471a4066acf0bd00451b1b8525058f5cbb`, apksigner OK, installed on the phone. Intermediate v1.24 / v1.24.1 / v1.24.2 and the v1.23.x builds deleted; all temporary debug scaffolding (WebView CDP flag, diagnostic notification) removed from the shipping source.

## v1.25 — HushTube rebrand (versionCode 32)

- **Why:** the app was literally named "Invidious" (launcher label) / InvidiousWeb — that is the upstream frontend's name, not the app's. Renamed to **HushTube**; the stack brand and folder convention (`~/hushtube`) follow the same name — the old stack name was retired entirely.
- **New identity:** applicationId/namespace `app.hushtube.android` (was a personal-name package), launcher label **HushTube**, theme `Theme.HushTube`, download folder `Download/HushTube/`, APK filename `hushtube-vX.Y.apk`. Internal JS bridge names (`InvidiousCast`/`InvidiousPlayer`/`InvidiousInsets`/`InvidiousScreen`) are deliberately unchanged — they are the wire contract with `ivx.js` and must keep matching older installed builds.
- **Fresh install required:** the package-ID change makes this a NEW app to Android — install alongside or after uninstalling the old build; logins, prefs and the downloads index do not carry over (files in `Download/InvidiousWeb/` stay on disk, just no longer listed).
- **Keystore rotated at the same moment (free):** new `hushtube.keystore`, cert DN `CN=HushTube, O=HushTube, C=US` — the old cert DN leaked the previous name + a personal name via `apksigner --print-certs`. Since the package change already reset update identity, rotation cost nothing extra. Old keystore kept in the private deploy tree as archive.
- **Server-side branding follows:** UI footer now "Private HushTube on …" and the You-tab header "Your private HushTube account" (`ivx.js?v=23`).
- **APK v1.25** (versionCode 32) `apk/hushtube-v1.25.apk`, sha256 `29b6e414036868c34f0ec3206d5c88cd76ac4db0dc9070105d1451d0b31ca600`, apksigner OK (new cert). Not yet installed on the device.

## v1.26 / v1.26.1 / v1.26.2 — mini-player & background-audio bug batch (versionCode 33–35)

Six user-reported bugs; the three APK ones (T4/T5/T6) are here, the three server-UI ones in UI.md (`ivx.js?v=24`). All verified on the Fold.

- **T4 — "tiny window inside the PiP":** with background audio ON, `setAutoEnterEnabled` stayed armed, so a raced/failed hand-off let Android auto-enter PiP and scale the WHOLE activity (page + mini window) into the PiP surface. Fix: `pipEligible()` returns false while bg-audio is on (the hand-off replaces PiP by design); the mini's speaker toggle calls `onChanged()` so PiP arms/disarms immediately.
- **T5 — mini survives over the watch page + drift:** after a bg-audio hand-off, returning to the app re-revealed the mini floating over the watch page (page video paused, mini playing = two players, positions diverge). Nothing closed it because the mini only closes on a NEW `/watch` `onPageStarted`, and the page never navigated. Fix: **reverse hand-off** `handBackToPage()` in `onResume` — if the WebView is on `/watch?v=<mini id>`, seek the page video to the mini position, play it, close mini + notification.
- **T5b — expand stutter:** the mini used to close at `onPageStarted`, killing audio while the watch page still loaded. Now `MiniPlayer.hideNow()` hides the window the same frame the expand tap lands, but the player keeps running until the page video reports `playing` (ivx.js reports on the `play` event; 4 s failsafe timeout). Gap-free, no double audio.
- **T6 — background audio dies at screen-off, stuck until app reset:** TWO root causes. (a) **Race:** screen-off fires `onPause` → `pauseWeb()` → the page's `pause` report clobbers `pageState.playing=false` microseconds before `onStop`'s hand-off reads it → the hand-off "correctly" paused everything. Fix: `prePausePlaying` is captured in `onPause` BEFORE pausing; the hand-off uses it (`startMini(forcePlay=true)`). (b) **Regression found while testing T4:** the `onUserLeaveHint` hand-off was gated on `pipEligible()`, which the T4 fix made false under bg-audio — so the hand-off moved to background `onStop`, where Android 14+ may refuse the mediaPlayback FGS start: audio dead and every Play path pointing at a service that never promoted = "refuses to play until the app is reset". Fix (v1.26.2): the `onUserLeaveHint` hand-off uses its own condition, not `pipEligible()`.
- **Diagnostic channel that worked:** wireless adb dies when the screen sleeps, so state sampling during screen-off runs ON THE PHONE (`nohup sh -c 'for i in $(seq…); do dumpsys media_session …; done > /data/local/tmp/x.log'`) and is read back after wake. Playback continuity = `PlaybackState.position` advancing ~1× per wall-second; a session vanishing for seconds = teardown/rebuild (audible hitch).
- **APK v1.26.2** (versionCode 35) `apk/hushtube-v1.26.2.apk`, sha256 `7cd7a14f9e4400e49cac31198d7dba13766dbd81f4fab750901e554f5c500dd3`, apksigner OK, installed on the phone (cold-flow verified: play → Home → screen-off 25 s → wake → notification Play resumes, no hitch). Intermediates v1.26/v1.26.1 deleted.
