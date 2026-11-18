# Roadmap

## A. Chromecast bridge — DONE (2026-11-16)

Live as systemd user service `castbridge` (port 8300, LAN-only). Code:
`castbridge/` · full docs: [CAST.md](CAST.md). Verified end-to-end on a
SHIELD Android TV: play / pause / resume / stop.

Two modes: `youtube` (default — drives the YouTube app receiver on the TV,
full quality) and `dash` (laptop-hosted DASH manifest + Range-preserving
segment proxy through loopback Invidious, for real Chromecast devices).
Original plan assumed the Default Media Receiver; it turned out to be dead
on Android TV — see CAST.md for the evidence and the pychromecast 14.x
API traps hit along the way.

UI hook for the APK (B): `POST http://<server>:8300/cast/play {"videoId":"…"}`.

## B. Native Android app (WebView wrapper) — DONE (2026-11-16)

Kotlin app owning the WebView: fullscreen video via
`WebChromeClient.onShowCustomView`, back-button stack (fullscreen → history →
exit), Tailscale-down error screen with deep link to the Tailscale app,
runtime JS injection (cast button, versioned in-app), cast button → service
from (A), release keystore + self-hosted `version.json` update check.
Now at **v1.25** (versionCode 32) — renamed **HushTube** (package
`app.hushtube.android`, new keystore; fresh install): mini player + PiP-style mini window, cast
picker, landscape fullscreen, persistent login, named downloads + offline
playback, share sheet, back-forward cache, transition pre-paint, background
audio ("keep listening") with a real MediaStyle media notification.
Source in [`apk/`](../apk/), build guide and per-version
notes: [APK.md](APK.md). Remaining: hosting `version.json` + on-device test of
the background-audio hand-off.

## C. UI polish (custom CSS/JS pass) — DONE (2026-11-17)

Theme/layout/player defaults via `default_user_preferences` (dark always,
autoplay + resume-where-left-off, Trending home — Popular is dead for
anonymous sessions) and global custom CSS via the unescaped `footer:`
config option. Config-only, playback re-verified after. Rationale, CSP
constraints (`style` yes / `script` no) and the SESSPREFS precedence
gotcha: [UI.md](UI.md).

## D. Visually pleasing UI — DONE: D1 · v2 shell · v3 YouTube-app parity · v4 seamless transitions (2026-11-17/18)

**Current: UI v4.** v3 rebuilt the client to look and behave like the official
YouTube Android app (cards, sheets, search screen, tabs, watch-page pills and
panels, player gestures). v4 made screen changes seamless: CSS + a static shell
injected through the `banner:` config (early in the page), a skeleton of the next
screen on every tap, and no stock-Invidious frame ever painted. See [UI.md](UI.md).

### v2 (superseded, kept for history)

**UI v2** supersedes D1's look: one persistent navigation for every client
(desktop sidebar / tablet rail / phone bottom tab bar) rendered as static HTML
from the `footer:` config, YouTube-like grid + two-column watch page, page
titles, real settings/sign-in screens. Source: `ui/footer.html`. See
[UI.md](UI.md). APK v1.3 drops its JS tab bar (now server-side).

### D1 (superseded, kept for history)

Full redesign shipped via tier D1: heavy custom CSS through the `footer:`
injection (card-grid feeds, sticky blurred navbar with pill search, chips,
player-first mobile watch page with sticky full-bleed player, restyled
related rows) + real JS via the APK's `inject.js` (bottom tab bar,
description more/less, cast button kept). APK rebuilt + re-signed as v1.1.
Design tokens, deploy command and the CSS traps: [UI.md](UI.md).

D2 (custom frontend on the REST API, full ownership) remains an open
option, though v3/v4 deliver most of what it promised on top of Invidious' own pages.

## E. HD playback

Companion YouTube session (cookies or OAuth, burner account) to lift the
360p anonymous cap. Decision pending — account risk disclosure in
TROUBLESHOOTING.

## F. Nice-to-haves

- Pi-hole-style dashboard for "bytes served, ads blocked: ∞" vanity metrics
- Watch progress sync across phone/laptop via Invidious account (requires enabling registration LAN-side)
