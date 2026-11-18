# Cast bridge — phone → TV remote

Tailnet-only Python service (Flask + `pychromecast`, served by gunicorn on the
Tailscale address, never the LAN) on the server. The phone
(APK or a cast button injected via Invidious custom-JS) calls the HTTP API;
the service drives the TV.

## API

| Endpoint | Body / params | Notes |
|---|---|---|
| `GET /cast/devices` | — | mDNS-discovered devices (event-driven cache) |
| `POST /cast/play` | `{"videoId":"…","mode":"youtube\|dash","device":"<name>","dryRun":true}` | `mode` default `youtube`; `dryRun` resolves without casting |
| `POST /cast/control` | `{"action":"pause\|play\|toggle\|stop\|quit\|seek\|volume_set\|volume_up\|volume_down","seconds":…,"level":…}` | |
| `GET /cast/status` | — | player state, title, position, volume |

## Two playback modes

**`youtube` (default).** Launches the YouTube app receiver on the TV
(`YouTubeController.play_video(videoId)`). The TV pulls YouTube itself:
full quality, no laptop bandwidth, works on Android TV boxes.

**`dash`.** Laptop-hosted DASH: `GET /manifest/<id>.mpd` fetches the
companion MPD from loopback Invidious (scraping the `check=` token off the
watch page) and rewrites the relative BaseURLs to `http://<server-ip>:8300/proxy/…`;
`/proxy/*` is a Range-preserving passthrough to `127.0.0.1:3000`. Invidious
never leaves loopback. Use for real Chromecast / Google TV devices.

## Why `youtube` is the default (learned on a SHIELD Android TV)

The **Default Media Receiver is dead on Android TV** (2026): any
`play_media` URL — even a public MP4 — loads to `IDLE` with
`idle_reason=ERROR` and the TV never fetches anything. No load-error
callback fires, so health must be polled from `player_state` +
`idle_reason`. Do not make `dash` the default for Android TV boxes.

## pychromecast 14.x API traps (all hit in production)

- `Chromecast(cast_info, zconf=…)` takes a `CastInfo`, not a host, and
  connects **async** — call `cc.wait()` before use.
- Discovery: `CastBrowser(AbstractCastListener)` + `start_discovery()`
  (not `.start()`). The old `get_chromecasts()` + `stop_discovery()` kills
  the shared zeroconf ("loop already stopped"). Keep one long-lived
  `Zeroconf` + `CastBrowser`; devices arrive via `add_cast`, metadata in
  `browser.devices[uuid]`.
- Status attrs: `cc.status.volume_level`, `ms.player_state`,
  `ms.content_id`, `ms.current_time`, `ms.duration`. There is no
  `.volume`, no `content_state`, no `block_until_playing`, no
  `cc.register_controller` (use `cc.socket_client.register_handler`).
- Evict hijacker apps (e.g. Spotify holding the media session) with
  `cc.quit_app()` before playing.

## Deploy

```
castbridge/app.py             # env: INVIDIOUS_URL, CASTBRIDGE_PORT, CASTBRIDGE_BIND, CASTBRIDGE_HOST_IP
castbridge/castbridge.service # systemd USER unit → ~/.config/systemd/user/
```

```bash
cd castbridge
python -m venv .venv && .venv/bin/pip install flask pychromecast requests gunicorn
# edit castbridge.service: replace every <TAILNET_IP> with your Tailscale IP
# (tailscale ip -4), and check the %h/hushtube/castbridge paths match where you put this repo
cp castbridge.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now castbridge
loginctl enable-linger $USER   # survive reboot without login
```

Port 8300 binds the tailnet address only (`CASTBRIDGE_BIND` in the unit). The `tailscale0` interface sits in the firewalld `trusted`
zone, so no firewall changes are needed for tailnet access. mDNS replies pass
via conntrack.

## Reliability fixes (2026-11-17)

- **Stale connection → permanent 502** ("Chromecast … is connecting..."): after a
  Wi-Fi blip the cached `Chromecast` object never reconnected. `_connect()` now
  only reuses it if `cc.socket_client.is_connected`, otherwise disconnects and
  rebuilds; `/cast/play` also drops the connection and retries once on any error.
- Empty device table (just after start / network back): `discover()` waits up
  to 8 s for mDNS before answering "no devices".
- The YouTube receiver (`233637DE`) is no longer quit before each cast — a
  second cast takes ~1.6 s instead of ~8.5 s. Other apps are still evicted.
- `action: "quit"` closes the app on the TV (the phone's "Stop casting").
- Watch-page title decoded as UTF-8 (was latin-1 mojibake).

## Phone UI (APK v1.4)

Standard cast icon in the video player's control bar (next to fullscreen) →
bottom sheet "Cast to" listing devices from `GET /cast/devices` → tap one →
`POST /cast/play {videoId, device}`. Icon turns into "cast connected" while
casting; tapping it again offers "Play this video on <TV>" / "Stop casting".
Empty and error states explain what to do (e.g. wake the SHIELD).

- 2026-11-18: default quality became `medium`, so watch pages no longer embed the DASH manifest link by default — `manifest_path()` requests `/watch?v=<id>&quality=dash`.

## Ad-free casting via SmartTube (2026-11-18)

- **Problem:** the default cast mode drives the TV's official YouTube app (YouTube receiver), and that app plays YouTube's ads. Nothing on the sending side can turn them off.
- **Fix:** `POST /cast/play` (mode `youtube`, the APK default) first checks whether the TV can be driven over **ADB network debugging** with **SmartTube** (ad-free YouTube client for Android TV; packages `org.smarttube.beta/.stable`, legacy `com.teamsmart.videomanager.tv`, `com.liskovsoft.smarttubetv[.beta]`). If yes: `input keyevent KEYCODE_WAKEUP` + `am start -a VIEW -d 'https://www.youtube.com/watch?v=ID&t=Ns' -p <pkg>` → response `mode:"smarttube"`. If not (debugging off / key not allowed / install running / launch error) → the old YouTube receiver path, so casting never breaks.
- **Auto-install:** when ADB is authorized but SmartTube is missing, the bridge installs `castbridge/SmartTube.apk` (universal build, v32.77 beta, signer SHA-256 `109735aa…482a`; not in the repo — download from github.com/yuliskov/SmartTube/releases) in the background (one-time).
- **Controls** in SmartTube mode: pause/play/toggle/stop = media keyevents; `quit` = `am force-stop <pkg>` + HOME. Volume still goes through the Cast connection.
- **Diagnostics:** `GET /cast/tv` → per TV `adb: off|unauthorized|ok|error`, `pkg`, `installing`. Probes are cached 10 min when ready, 30 s otherwise. Disable the whole path with `CASTBRIDGE_SMARTTUBE=0`; adb path via `CASTBRIDGE_ADB` (default `~/Android/platform-tools/adb`).
- **One-time TV setup:** Settings → Device Preferences → About → click *Build* 7× → Developer options → **Network debugging ON**; first cast after that shows "Allow network debugging?" on the TV → tick *Always allow from this computer* → Allow. The cast after that installs SmartTube, the next one plays in it. (Open SmartTube once and sign in if you want your subscriptions/history there; playback works without.)
- APK v1.21 sends the current position (`castAt(id, device, t)`, bridge field `t`) so the TV continues where the phone was (SmartTube only).
- Verified: compile + service restart, `/cast/tv` reports `adb: off` for the SHIELD today (fallback active), full ADB path exercised against a stub `adb` (probe → auto-install → play with quoted `&t=` URL → pause → quit). Not yet verified on the real TV (network debugging is off).

