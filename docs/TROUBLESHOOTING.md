# Troubleshooting — lessons learned the hard way

Every entry below was hit and fixed during real deployment. Ordered by likelihood.

## "The media could not be loaded" but everything is Up

Check the actual bytes, not the page:

```bash
# fetch a DASH segment with a Range header and look for ftyp magic
curl -s -H 'Range: bytes=0-500000' "<segment BaseURL from manifest>" | xxd | head -1
```

- `ftyp` at offset 4 → stream is healthy; problem is client-side (player settings).
- **`content-type: application/vnd.yt-ump` with 0 bytes** → the UMP trap, below.
- `503` from `/companion/...` → PO token not ready yet, below.

## The UMP trap

`NETWORKING_VIDEOPLAYBACK_UMP=true` on the companion makes DASH segments return
`206` + `application/vnd.yt-ump` with **zero bytes**. The browser shows the
generic media error; the page, the manifest, and the API all look healthy.
**Do not enable this flag.** If it's set: remove it, `up -d --force-recreate companion`, wait for PO token, re-verify.

## 503 on all stream endpoints after a restart

Normal. The companion regenerates its YouTube session (PO token) on a cron
(~every 5 min). Watch for:

```
[INFO] Successfully generated PO token
```

Until then, everything proxied 503s. If it *never* succeeds and logs show
`Failed to validate PO token: all validation attempts returned non-200` —
YouTube is temporarily 403-ing your IP (often from rapid restart/testing
cycles). Wait 15–30 min; repeated restarts make it worse.

## Companion crash-loops with a zod/validation error

`SERVER_SECRET_KEY` must be **exactly 16 alphanumeric characters** and must
**equal** `invidious_companion_key` in the Invidious config. Common causes:
an unfilled placeholder (`<...>`, `***`), wrong length, or the two keys
diverging. Inspect what the container actually got:

```bash
podman inspect <companion_container> --format '{{range .Config.Env}}{{println .}}{{end}}' | grep SERVER_SECRET
```

## Container "Up" but env changes didn't apply

`podman-compose up -d` may **not** recreate a container after editing its
environment. Force it and verify:

```bash
podman-compose up -d --force-recreate <service>
podman inspect <container> --format 'RestartCount: {{.RestartCount}}'   # should stay 0
```

Note: `restart: unless-stopped` inflates `RestartCount` during crash loops —
"Up N seconds" can be a crash loop in disguise. Wait 30s and re-check.

## 360p only / "No URLs found for adaptive formats"

YouTube (2025–2026 crackdown) refuses HD + separate-audio ("adaptive") formats
to anonymous third-party sessions. Symptoms: only itag 18 (360p progressive)
is offered; companion logs warn about adaptive formats and fall back to the
`TV_SIMPLY` client.

**Fix:** give the companion an authenticated YouTube session —
`YOUTUBE_SESSION_COOKIES` or OAuth (`YOUTUBE_SESSION_OAUTH_ENABLED=true`) in
the companion env. Use a burner Google account; the account may be at risk
under ToS. No flag-only fix exists.

## Live-stream watch page shows the thumbnail and never plays

Symptom: uploads play fine, 24/7-style live streams stall; companion log shows
`GET /companion/latest_version?id=<live>&itag=18` → 400 in a loop. Live videos
have no progressive itag-18 — they need HLS, which the stock companion doesn't
serve. Fix: build the patched companion image per [LIVE-STREAMS.md](LIVE-STREAMS.md)
(and wipe the companion cache volume after swapping images, or the old
no-HLS responses keep being served from cache).

## Postgres won't initialize (SELinux / Fedora + Podman)

Bind mounts into the postgres container need the `:z` label suffix
(`./invidious/config/sql:/config/sql:z`). Without it, init scripts silently
don't run and Invidious can't connect.

## `tailscale serve` refuses to configure port 443

A **foreground** serve session already owns 443 (someone ran `tailscale serve`
without `--bg` in a terminal). `tailscale serve status --json` shows it under
`Foreground`. Kill that terminal process, then `tailscale serve --bg 3000`.

## "Sign in to confirm you're not a bot"

Your IP is rate-limited by YouTube — common after heavy use or on datacenter
IPs. Home residential IPs recover in hours. Datacenter/VPS IPs are blocked
permanently by design; this stack is only viable at home.

## Secrets became `***` in a config file

If you manage config files through AI tooling: some redaction layers replace
secret-looking strings in tool arguments with `***` *before the file is
written*. Inject secrets with shell variables instead (`sed` with a value
extracted from the file itself), and always `grep` the file after editing.

## APK: "Download failed: no protocol:"

The companion answers the download POST with a **relative** redirect
(`Location: /companion/videoplayback?...`). Code that follows redirects manually
with `HttpURLConnection` must resolve it: `URL(conn.url, location)`, not
`URL(location)`. Fixed in APK v1.19. Browsers resolve relative redirects themselves,
so a browser test won't catch this.

## The stock Invidious page flashes before the custom UI

CSS injected through `footer:` is parsed last, so the stock layout can paint first.
Inject through **`banner:`** (raw HTML right after the navbar, before content) and
drive first-paint decisions from the navbar's `?referer=` links, not from JS or
from elements later in the page. Details: [UI.md](UI.md#v4--seamless-transitions-how-a-screen-change-works).

## A UI change "doesn't take effect"

`/js/` is served with a 1-month cache. Bump `ui/VERSION` (→ `ivx.js?v=N`) on every
change, including between test iterations on `ui/testserver.sh`, or you are testing
the previous script.

## Thumbnails and pages load slowly

Invidious keeps no cache of its own for thumbnails, avatars, channel info, trending or search:
each request goes back to YouTube (0.3-1.8 s), and thumbnails come back with `max-age=300`.
The `edge` service (nginx, `nginx/nginx.conf`) sits in front on `127.0.0.1:3000` and caches the
user-independent responses (images 2 d, channel info 1 d, channel tabs 15 min, trending/search
10-30 min, the Home and search HTML 5-10 min keyed per cookie), serving the cached copy while it
refreshes in the background. Watch pages, video bytes, `/api/v1/auth/*` and POSTs pass through
unbuffered and uncached.

- Check a response: `curl -sI https://<host>/vi/<id>/hqdefault.jpg | grep -i x-cache` (HIT/MISS/STALE).
- Bypass the cache for debugging: Invidious itself is on `127.0.0.1:3002`.
- Clear it: `podman rm -f <edge container> && podman volume rm <project>_edgecache && podman-compose up -d --no-deps edge`.
