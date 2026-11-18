"""Cast bridge: Invidious -> Chromecast over LAN.

Endpoints (bind = CASTBRIDGE_BIND, default 127.0.0.1; production unit binds the tailnet IP only —
never expose it to the LAN):
  GET  /cast/devices              mDNS discovery list
  POST /cast/play   {videoId, device?, dryRun?, t?}  -> SmartTube via ADB when set up (no ads), else YouTube receiver
  GET  /cast/tv                   ADB / SmartTube readiness per TV
  POST /cast/control {action, ...}  pause|play|toggle|stop|seek|volume_set|volume_up|volume_down
  GET  /cast/status               current device + media status
  GET  /manifest/<id>.mpd         rewritten DASH manifest (BaseURLs -> this bridge)
  ANY  /proxy/<path>              passthrough to loopback Invidious (Range-aware)

The TV never touches Tailscale or YouTube; it streams laptop -> TV over LAN.
"""
import os
import re
import shlex
import socket
import subprocess
import threading
import time
import html
import logging

import requests
from flask import Flask, request, jsonify, Response

INVIDIOUS = os.environ.get("INVIDIOUS_URL", "http://127.0.0.1:3000")
PORT = int(os.environ.get("CASTBRIDGE_PORT", "8300"))
# Security: default-bind loopback; the systemd unit sets this to the tailnet IP.
BIND = os.environ.get("CASTBRIDGE_BIND", "127.0.0.1")
VIDEO_ID_RE = re.compile(r"[A-Za-z0-9_-]{11}")

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("castbridge")


def detect_host_ip() -> str:
    env = os.environ.get("CASTBRIDGE_HOST_IP")
    if env:
        return env
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    finally:
        s.close()


HOST_IP = detect_host_ip()
BASE = f"http://{HOST_IP}:{PORT}"
log.info("bridge base URL: %s (Invidious at %s)", BASE, INVIDIOUS)

app = Flask(__name__)
app.log = log

# ---------------------------------------------------------------- discovery
import pychromecast
from pychromecast import CastBrowser
from pychromecast.discovery import AbstractCastListener
from zeroconf import Zeroconf

_dev_lock = threading.Lock()
_devices: dict[str, dict] = {}          # name -> device info


class _Listener(AbstractCastListener):
    def __init__(self, browser):
        self.browser = browser

    def _sync(self, uuid):
        ci = self.browser.devices.get(uuid)
        if ci is None or not ci.friendly_name:
            return
        with _dev_lock:
            _devices[ci.friendly_name] = {
                "name": ci.friendly_name, "host": ci.host, "port": ci.port,
                "model": ci.model_name, "manufacturer": ci.manufacturer,
                "uuid": str(uuid), "last_seen": time.time()}
        log.info("device up: %s (%s @ %s)", ci.friendly_name, ci.model_name, ci.host)

    def add_cast(self, uuid, service):
        self._sync(uuid)

    def update_cast(self, uuid, service):
        self._sync(uuid)

    def remove_cast(self, uuid, service, cast_info):
        name = getattr(cast_info, "friendly_name", None)
        with _dev_lock:
            _devices.pop(name, None)
        log.info("device down: %s", name)


_zeroconf = Zeroconf()
_listener = _Listener(None)
_cast_browser = CastBrowser(_listener, zeroconf_instance=_zeroconf)
_listener.browser = _cast_browser
_cast_browser.start_discovery()


def discover(force=False, wait=8.0) -> dict:
    """Current device table; if empty (e.g. just started / network came back),
    give mDNS up to `wait` seconds to find something."""
    deadline = time.time() + wait
    while True:
        with _dev_lock:
            devs = dict(_devices)
        if devs or time.time() >= deadline:
            return devs
        time.sleep(0.5)


# ------------------------------------------------------------- invidious API
_session = requests.Session()
_manifest_cache: dict[str, tuple[float, str]] = {}   # videoId -> (ts, manifest path w/ check)
MANIFEST_TTL = 300.0

MANIFEST_RE = re.compile(r'(/companion/api/manifest/dash/id/[^\s"\'<>]+)')
TITLE_RE = re.compile(r"<title[^>]*>(.*?)</title>", re.S | re.I)


def manifest_path(video_id: str) -> str:
    """Scrape the watch page for the companion DASH manifest URL (carries check token)."""
    now = time.time()
    hit = _manifest_cache.get(video_id)
    if hit and now - hit[0] < MANIFEST_TTL:
        return hit[1]
    # default quality is "medium" (progressive) -> the page only embeds the DASH link when asked
    r = _session.get(f"{INVIDIOUS}/watch?v={video_id}&quality=dash", timeout=30)
    r.raise_for_status()
    m = MANIFEST_RE.search(r.text)
    if not m:
        raise RuntimeError("no DASH manifest link on watch page (video unavailable?)")
    path = html.unescape(m.group(1))
    _manifest_cache[video_id] = (now, path)
    return path


@app.get("/manifest/<video_id>.mpd")
def bridge_manifest(video_id: str):
    # same fullmatch as /cast/play — no query/param injection into the loopback fetch
    if not VIDEO_ID_RE.fullmatch(video_id):
        return "videoId (11 chars) required", 400
    try:
        path = manifest_path(video_id)
        r = _session.get(f"{INVIDIOUS}{path}", timeout=60)
        r.raise_for_status()
    except Exception as e:  # noqa: BLE001
        return f"manifest fetch failed: {e}", 502
    mpd = r.text
    # point every segment BaseURL at this bridge's loopback proxy
    mpd = mpd.replace("<BaseURL>/companion/", f"<BaseURL>{BASE}/proxy/companion/")
    _manifest_cache.pop(video_id, None)  # tokens rotate; re-scrape next time
    return Response(mpd, mimetype="application/dash+xml",
                    headers={"Cache-Control": "no-store"})


@app.route("/proxy/<path:subpath>", methods=["GET", "HEAD"])
def proxy(subpath: str):
    headers = {}
    if "Range" in request.headers:
        headers["Range"] = request.headers["Range"]
    try:
        upstream = _session.get(f"{INVIDIOUS}/{subpath}", params=request.args,
                                headers=headers, stream=True, timeout=60)
    except Exception as e:  # noqa: BLE001
        return f"proxy fetch failed: {e}", 502
    resp_headers = {}
    for h in ("Content-Type", "Content-Range", "Content-Length", "Accept-Ranges"):
        if h in upstream.headers:
            resp_headers[h] = upstream.headers[h]
    return Response(upstream.iter_content(65536), status=upstream.status_code,
                    headers=resp_headers)


# ------------------------------------------------- SmartTube over ADB (ad-free casting)
# Casting to the official YouTube TV app plays YouTube's ads, and nothing on the sending side
# can switch them off. SmartTube (ad-free YouTube client for Android TV) can't be a Cast
# receiver, so instead the bridge opens the video in it via ADB network debugging:
#   adb -s <tv>:5555 shell am start -a VIEW -d https://www.youtube.com/watch?v=ID -p <pkg>
# One-time TV setup: Developer options -> Network debugging ON, then "Allow" the laptop's key.
# If SmartTube is missing, the bridge installs it from castbridge/SmartTube.apk (universal).
# Anything not ready (debugging off, key not allowed yet, install running) -> falls back to the
# YouTube receiver, so casting always works.
ADB = os.environ.get("CASTBRIDGE_ADB", os.path.expanduser("~/Android/platform-tools/adb"))
SMARTTUBE_APK = os.path.join(os.path.dirname(os.path.abspath(__file__)), "SmartTube.apk")
SMARTTUBE_PKGS = ("org.smarttube.stable", "org.smarttube.beta", "com.teamsmart.videomanager.tv",
                  "com.liskovsoft.smarttubetv", "com.liskovsoft.smarttubetv.beta")
USE_SMARTTUBE = os.environ.get("CASTBRIDGE_SMARTTUBE", "1") != "0"
_tv: dict[str, dict] = {}          # host -> {"ts", "adb": off|unauthorized|ok|error, "pkg", "installing"}
_tv_lock = threading.Lock()
_last_mode = {"mode": "youtube", "host": None, "pkg": None}


def _adb(host, *args, timeout=10):
    return subprocess.run([ADB, "-s", f"{host}:5555", *args], capture_output=True, text=True, timeout=timeout)


def _install_smarttube(host):
    try:
        log.info("installing SmartTube on %s (one-time)", host)
        r = _adb(host, "install", "-r", SMARTTUBE_APK, timeout=300)
        log.info("SmartTube install on %s: %s", host, (r.stdout + r.stderr).strip()[-200:])
    except Exception as e:  # noqa: BLE001
        log.warning("SmartTube install on %s failed: %s", host, e)
    finally:
        with _tv_lock:
            _tv.pop(host, None)        # re-probe next time


def tv_probe(host, force=False) -> dict:
    """ADB + SmartTube status of a TV. Cached (10 min when ready, 30 s otherwise)."""
    now = time.time()
    with _tv_lock:
        hit = _tv.get(host)
        if hit and not force and (hit.get("installing") or now - hit["ts"] < (600 if hit.get("pkg") else 30)):
            return hit
    st = {"ts": now, "adb": "off", "pkg": None}
    try:
        with socket.create_connection((host, 5555), timeout=1.5):
            pass                                            # port closed = network debugging off
        subprocess.run([ADB, "connect", f"{host}:5555"], capture_output=True, text=True, timeout=10)
        r = _adb(host, "shell", "pm", "list", "packages")
        out = (r.stdout + r.stderr).strip()
        if r.returncode == 0 and "package:" in out:
            st["adb"] = "ok"
            have = {l.split(":", 1)[1].strip() for l in r.stdout.splitlines() if l.startswith("package:")}
            st["pkg"] = next((p for p in SMARTTUBE_PKGS if p in have), None)
            if not st["pkg"] and os.path.exists(SMARTTUBE_APK):
                st["installing"] = True
                threading.Thread(target=_install_smarttube, args=(host,), daemon=True).start()
        elif "unauthorized" in out:
            st["adb"] = "unauthorized"                      # TV shows "Allow debugging?" -> Always allow
        else:
            st["adb"] = "error"; st["detail"] = out[:200]
    except OSError:
        pass
    except Exception as e:  # noqa: BLE001
        st["adb"] = "error"; st["detail"] = str(e)[:200]
    with _tv_lock:
        _tv[host] = st
    return st


def smarttube_play(host, pkg, video_id, t=0):
    url = f"https://www.youtube.com/watch?v={video_id}" + (f"&t={int(t)}s" if t else "")
    _adb(host, "shell", "input", "keyevent", "KEYCODE_WAKEUP")          # TV/CEC on, like a cast would
    r = _adb(host, "shell", "am", "start", "-a", "android.intent.action.VIEW",
             "-d", shlex.quote(url), "-p", pkg, timeout=15)
    out = (r.stdout + r.stderr)
    if r.returncode != 0 or "Error" in out:
        raise RuntimeError(f"SmartTube launch failed: {out.strip()[:200]}")
    _last_mode.update(mode="smarttube", host=host, pkg=pkg)


def _device_host(name):
    devs = discover()
    if not devs:
        return None, None
    if name is None:
        name = _current_name if _current_name in devs else next(iter(devs))
    d = devs.get(name)
    return (name, d["host"]) if d else (None, None)


# --------------------------------------------------------------- casting
_cast_lock = threading.Lock()
_current = None          # pychromecast.Chromecast
_current_name = None


class _MediaLog(pychromecast.controllers.media.MediaStatusListener):
    def media_status_changed(self, status): pass
    def new_media_status(self, status): pass
    def load_media_failed(self, request_id, error_code, error_description):
        log.error("LOAD FAILED %s: %s", error_code, error_description)
    def media_status_error(self, status, error_code, error_description):
        log.error("MEDIA ERROR %s: %s", error_code, error_description)


def _wait_state(cc, timeout=25):
    deadline = time.time() + timeout
    state = None
    while time.time() < deadline:
        state = cc.media_controller.status.player_state
        if state in ("PLAYING", "BUFFERING"):
            return state
        if state == "IDLE":
            idle = getattr(cc.media_controller.status, "idle_reason", None)
            if idle == "ERROR":
                raise RuntimeError("receiver failed to fetch media "
                                   "(default receiver likely unsupported)")
        time.sleep(0.5)
    return state


def _drop_current():
    global _current, _current_name
    cc, _current, _current_name = _current, None, None
    if cc is not None:
        try:
            cc.disconnect(timeout=3)
        except Exception:  # noqa: BLE001
            pass


def _connect(name: str | None):
    """Return a connected Chromecast for `name` (or the current/only one)."""
    global _current, _current_name
    import pychromecast
    devs = discover()
    if not devs:
        raise RuntimeError("no Chromecast devices found on the LAN")
    if name is None:
        name = _current_name if _current_name in devs else next(iter(devs))
    if name not in devs:
        raise RuntimeError(f"device {name!r} not found; have: {sorted(devs)}")
    if _current is not None and _current_name == name:
        # A cached Chromecast can sit in "connecting..." forever after a network
        # blip (socket thread lost the device) -> only reuse a live socket.
        if _current.socket_client.is_connected:
            return _current
        log.info("cached connection to %s is dead; reconnecting", name)
        _drop_current()
    elif _current is not None:
        _drop_current()
    host = devs[name]["host"]
    import uuid as _uuid
    cast_info = _cast_browser.devices.get(_uuid.UUID(devs[name]["uuid"]))
    if cast_info is None:
        raise RuntimeError(f"lost {name!r} during connect")
    cc = pychromecast.Chromecast(cast_info, timeout=10, zconf=_zeroconf)
    cc.wait(timeout=15)
    if not cc.socket_client.is_connected:
        try:
            cc.disconnect(timeout=3)
        except Exception:  # noqa: BLE001
            pass
        raise RuntimeError(f"could not connect to {name!r} at {host}")
    cc.media_controller.register_status_listener(_MediaLog())
    from pychromecast.controllers.youtube import YouTubeController
    yt = YouTubeController()
    cc.socket_client.register_handler(yt)
    cc.bridge_yt = yt
    _current, _current_name = cc, name
    log.info("connected to %s (%s)", name, host)
    return cc


@app.get("/cast/devices")
def cast_devices():
    return jsonify({"bridge": BASE, "devices": list(discover(force=True).values())})


@app.get("/cast/tv")
def cast_tv():
    """Diagnostics for the ad-free path: ADB + SmartTube status per TV."""
    out = []
    for d in discover().values():
        st = dict(tv_probe(d["host"], force=request.args.get("force") == "1"))
        st.pop("ts", None)
        out.append({"name": d["name"], "host": d["host"], **st})
    return jsonify({"smarttube": USE_SMARTTUBE, "apk": os.path.exists(SMARTTUBE_APK), "tvs": out})


@app.post("/cast/play")
def cast_play():
    data = request.get_json(silent=True) or {}
    video_id = data.get("videoId")
    if not video_id or not VIDEO_ID_RE.fullmatch(video_id):
        return "videoId (11 chars) required", 400
    try:
        path = manifest_path(video_id)
    except Exception as e:  # noqa: BLE001
        return f"resolve failed: {e}", 502
    manifest_url = f"{BASE}/manifest/{video_id}.mpd"
    title = video_id
    page = _session.get(f"{INVIDIOUS}/watch?v={video_id}", timeout=30)
    page.encoding = "utf-8"   # server omits charset -> requests guesses latin-1 (mojibake)
    t = TITLE_RE.search(page.text or "")
    if t:
        title = html.unescape(t.group(1)).replace(" - Invidious", "").strip()

    if data.get("dryRun"):
        return jsonify({"ok": True, "dryRun": True, "manifestUrl": manifest_url,
                        "upstream": INVIDIOUS + path.split("?")[0], "title": title})

    mode = data.get("mode", "youtube")

    # ad-free: open it in SmartTube on the TV when that's set up (else fall through to YouTube)
    if mode == "youtube" and USE_SMARTTUBE and not data.get("noSmartTube"):
        name, host = _device_host(data.get("device"))
        st = tv_probe(host) if host else {}
        if st.get("pkg"):
            try:
                with _cast_lock:
                    smarttube_play(host, st["pkg"], video_id, data.get("t") or 0)
                log.info("cast %s -> SmartTube on %s", video_id, name)
                return jsonify({"ok": True, "device": name, "title": title, "mode": "smarttube",
                                "state": "PLAYING", "manifestUrl": manifest_url})
            except Exception as e:  # noqa: BLE001
                log.warning("SmartTube cast failed (%s); falling back to the YouTube receiver", e)
                with _tv_lock:
                    _tv.pop(host, None)
        elif host:
            log.info("SmartTube not ready on %s (adb=%s%s) -> YouTube receiver (ads)", name, st.get("adb"),
                     ", installing" if st.get("installing") else "")

    def _do():
        cc = _connect(data.get("device"))
        if mode == "youtube":
            # leave the YouTube receiver running (relaunch is slow); evict others
            if cc.app_id and cc.app_id != "233637DE":
                cc.quit_app()
                time.sleep(2)
            cc.bridge_yt.play_video(video_id)
        else:
            if cc.app_id and cc.app_id != "CC1AD845":
                cc.quit_app()
                time.sleep(2)
            cc.play_media(manifest_url, "application/dash+xml", title=title)
        return _wait_state(cc)

    _last_mode.update(mode=mode, host=None, pkg=None)
    try:
        with _cast_lock:
            try:
                state = _do()
            except Exception as e:  # noqa: BLE001  -- stale socket etc: reconnect once
                log.warning("cast attempt failed (%s); reconnecting and retrying", e)
                _drop_current()
                state = _do()
    except Exception as e:  # noqa: BLE001
        log.error("cast failed: %s", e)
        return f"cast failed: {e}", 502
    return jsonify({"ok": True, "device": _current_name, "title": title,
                    "mode": mode, "state": state, "manifestUrl": manifest_url})


SMARTTUBE_KEYS = {"pause": "KEYCODE_MEDIA_PAUSE", "play": "KEYCODE_MEDIA_PLAY", "resume": "KEYCODE_MEDIA_PLAY",
                  "toggle": "KEYCODE_MEDIA_PLAY_PAUSE", "stop": "KEYCODE_MEDIA_STOP", "quit": None}


@app.post("/cast/control")
def cast_control():
    data = request.get_json(silent=True) or {}
    action = data.get("action", "")
    if _last_mode["mode"] == "smarttube" and action in SMARTTUBE_KEYS:
        try:
            host, pkg = _last_mode["host"], _last_mode["pkg"]
            if action == "quit":                             # "Stop casting": close SmartTube on the TV
                _adb(host, "shell", "am", "force-stop", pkg)
                _adb(host, "shell", "input", "keyevent", "KEYCODE_HOME")
                _last_mode.update(mode="youtube", host=None, pkg=None)
            else:
                _adb(host, "shell", "input", "keyevent", SMARTTUBE_KEYS[action])
        except Exception as e:  # noqa: BLE001
            return f"control failed: {e}", 502
        return jsonify({"ok": True, "action": action, "mode": "smarttube"})
    try:
        with _cast_lock:
            cc = _connect(data.get("device"))
            ms = cc.media_controller
            if action == "pause":
                ms.pause()
            elif action in ("play", "resume"):
                ms.play()
            elif action == "toggle":
                ms.play_pause()
            elif action == "stop":
                ms.stop()
            elif action == "quit":       # "Stop casting": close the app on the TV
                cc.quit_app()
            elif action == "seek":
                try:                                    # no KeyError 500s
                    seconds = float(data["seconds"])
                except (KeyError, TypeError, ValueError):
                    return "seek requires numeric 'seconds'", 400
                ms.seek(seconds)
            elif action == "volume_set":
                try:
                    level = float(data["level"])
                except (KeyError, TypeError, ValueError):
                    return "volume_set requires numeric 'level'", 400
                cc.set_volume(level)
            elif action == "volume_up":
                cc.set_volume(min(1.0, (cc.status.volume_level or 0) + 0.1), True)
            elif action == "volume_down":
                cc.set_volume(max(0.0, (cc.status.volume_level or 0) - 0.1), True)
            else:
                return f"unknown action {action!r}", 400
    except Exception as e:  # noqa: BLE001
        return f"control failed: {e}", 502
    return jsonify({"ok": True, "action": action})


@app.get("/cast/status")
def cast_status():
    try:
        with _cast_lock:
            cc = _connect(None)
            ms = cc.media_controller.status
            meta = ms.media_metadata.get("metadata") or ms.media_metadata or {}
            return jsonify({
                "device": _current_name,
                "volume": cc.status.volume_level,
                "muted": cc.status.volume_muted,
                "state": ms.player_state,
                "title": meta.get("title") or ms.content_id,
                "content_url": ms.content_id,
                "position": ms.current_time,
                "duration": ms.duration,
            })
    except Exception as e:  # noqa: BLE001
        return jsonify({"device": None, "error": str(e)}), 200


if __name__ == "__main__":
    app.run(host=BIND, port=PORT, threaded=True)
