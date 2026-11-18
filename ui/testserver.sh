#!/usr/bin/env bash
# Throwaway Invidious on http://127.0.0.1:3091 with the CURRENT ui/ (banner/footer/ivx.js), same DB.
# Lets you QA UI changes without touching the live container. Never prints secrets.
#   ui/testserver.sh        (re)start      ui/testserver.sh stop
set -euo pipefail
cd "$(dirname "$0")/.."
podman rm -f ivtest >/dev/null 2>&1 || true
[ "${1:-}" = stop ] && exit 0
T=$(mktemp -d "${TMPDIR:-/tmp}/ivtest.XXXX")
cp docker-compose.yml "$T/"; mkdir -p "$T/ui"; cp ui/banner.html ui/footer.html ui/ivx.css ui/VERSION "$T/ui/"
(cd "$T" && python3 "$OLDPWD/ui/inject_css.py" >/dev/null)
python3 - "$T" <<'E'
import sys
T=sys.argv[1]; L=open(T+'/docker-compose.yml').read().split('\n')
i=next(k for k,l in enumerate(L) if l.strip()=='INVIDIOUS_CONFIG: |')
out=[]
for l in L[i+1:]:
    if l.strip() and (len(l)-len(l.lstrip()))<8: break
    out.append(l[8:])
open(T+'/cfg.yml','w').write('\n'.join(out)+'\n')
E
podman run -d --name ivtest --network invidious-deploy_default -p 127.0.0.1:3091:3000 \
  -v "$PWD/ui/ivx.js:/invidious/assets/js/ivx.js:ro,z" \
  -e INVIDIOUS_CONFIG="$(cat "$T/cfg.yml")" quay.io/invidious/invidious:latest >/dev/null
rm -rf "$T"
for i in $(seq 30); do curl -sf -o /dev/null http://127.0.0.1:3091/feed/trending && break; sleep 1; done
echo "test server up: http://127.0.0.1:3091"
