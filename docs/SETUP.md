# Setup Guide

Verified on Fedora 45 + Podman 6.1 (works identically with Docker). Total time: ~1–2 hours.

## 0. Prerequisites

```bash
# Fedora
sudo dnf install -y podman-compose tailscale
# Debian/Ubuntu: apt install podman-compose tailscale (or use docker + docker compose)
sudo systemctl enable --now tailscaled
tailscale up   # log into your tailnet
```

## 1. Clone Invidious (needed for Postgres init SQL)

```bash
mkdir -p ~/hushtube && cd ~/hushtube
git clone https://github.com/iv-org/invidious.git
```

## 2. Generate secrets

```bash
openssl rand -hex 12   # → DB_PASSWORD
openssl rand -hex 8    # → HMAC_KEY      (any ≥16-char secret)
openssl rand -hex 8    # → COMPANION_KEY (MUST be exactly 16 alphanumeric chars)
```

Copy `compose/.env.example` → `compose/.env` (it must sit **next to `docker-compose.yml`** — that's where podman-compose looks) and fill them in.
**Warning:** `COMPANION_KEY` is used in **two** places (Invidious config + companion env) — they must match exactly, or the companion crash-loops with a validation error.

## 3. Start the stack

```bash
cd compose
podman-compose up -d
podman-compose ps    # all three Up; postgres healthy
```

First boot takes a minute or two (DB init).

### 3b. The UI layer (already wired in)

The compose template already carries the built UI: the `banner:` block (all CSS + the
page shell + `<script src="/js/ivx.js?v=N">`) and a bind mount of `../ui/ivx.js` into the
container. After changing anything in `ui/`:

```bash
cd ~/hushtube   # (the folder containing ui/ and compose/)
echo $(( $(cat ui/VERSION) + 1 )) > ui/VERSION
COMPOSE=compose/docker-compose.yml python3 ui/inject_css.py
cd compose && podman-compose up -d --force-recreate --no-deps invidious
```

`--no-deps` keeps the companion running (no 5-minute PO-token 503 window). Test UI changes
first on a throwaway instance with `ui/testserver.sh` (expects a live `docker-compose.yml`
next to `ui/`). Details: [UI.md](UI.md).

## 4. Serve over HTTPS with Tailscale

```bash
tailscale serve --bg 3000
tailscale serve status
```

Your instance is now `https://<hostname>.<tailnet>.ts.net` from any device on your tailnet — with a valid TLS cert and no open ports on your router.

> **Warning:** Running `tailscale serve 3000` **without** `--bg` works but dies when the terminal closes. Check with `tailscale serve status --json` — a `Foreground` entry on 443 means it's terminal-bound.

## 5. Verify (don't skip)

"Container is up" ≠ "playback works". Run:

```bash
BASE_URL=https://<hostname>.<tailnet>.ts.net ../scripts/verify.sh
```

It checks, in order: API stats → search → video metadata → DASH manifest →
**actual segment bytes** (HTTP 206 + `ftyp` magic). The last check is the one
that catches silent killers like the UMP format trap (see TROUBLESHOOTING).

Allow ~5 minutes after any companion start for the PO token to generate
("Successfully generated PO token" in `podman logs` of the companion).

**Live streams:** the stock companion image cannot play live streams. Build the
patched image first (`LIVE-STREAMS.md`) — the compose template already
points at it; skipping the build means VOD-only playback.

## 6. Phone

1. Install Tailscale from the Play Store, log into the same tailnet, enable *connect on boot*.
2. Open the ts.net URL in Chrome → menu → **Install app**. Done: fullscreen app with its own icon.
3. Or build the native app: `cd apk && ./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk` (set your own `DEFAULT_SERVER` in `MainActivity.kt` first) — see [APK.md](APK.md).
4. Sensible defaults are already set server-side (dark theme, autoplay + resume-where-left-off, Trending home — see [UI.md](UI.md)); ⚙ Preferences only if you want per-account tweaks, plus background play and subscription import (Google Takeout / NewPipe / FreeTube formats supported).

## 7. Laptop-as-server hygiene

```bash
# restart timer — upstream requires "at least once a day, ideally every hour";
# shipped default is nightly (good for private single-user instances).
# For a public instance, set OnCalendar=hourly in invidious-restart.timer.
chmod +x scripts/restart-invidious.sh
cp systemd/invidious-restart.{service,timer} ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now invidious-restart.timer
loginctl enable-linger $USER          # keeps timers alive without a login session

# don't sleep on lid close
sudo sed -i 's/^#\?HandleLidSwitch=.*/HandleLidSwitch=ignore/' /etc/systemd/logind.conf
sudo systemctl restart systemd-logind
```

## 8. Updates

```bash
cd compose && podman-compose pull && podman-compose up -d && podman image prune -f
```

Run weekly-ish; it's also the standard fix when Google changes something.
