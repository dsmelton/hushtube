#!/bin/sh
# Scheduled Invidious restart (upstream: at least daily, ideally hourly).
# Invoked detached by
# invidious-restart.service. Uses stop+start per container because `podman
# restart` / `podman-compose restart` can hang the client when a rootless
# container with port bindings is stopped inside a systemd unit (rootlessport
# crash) — the containers restart fine, the command just never returns.
for c in $(podman ps -a --format '{{.Names}}' | grep -E '_invidious_1$|_companion_1$'); do
    podman stop -t 10 "$c" >/dev/null 2>&1
    podman start "$c" >/dev/null 2>&1
done
