#!/usr/bin/env bash
# End-to-end Invidious playback verification — proves real media bytes flow,
# not just that containers are running. Usage:
#   BASE_URL=https://mylaptop.tailnet.ts.net ./verify.sh [videoId]
set -uo pipefail
BASE="${BASE_URL:?set BASE_URL, e.g. https://host.tailnet.ts.net}"
VID="${1:-dVmXcRwIobA}"
PASS=0; FAIL=0
ok()  { echo "  PASS  $1"; PASS=$((PASS+1)); }
bad() { echo "  FAIL  $1"; FAIL=$((FAIL+1)); }

echo "1/5 API stats"
curl -sf -m 10 "$BASE/api/v1/stats" | grep -q invidious \
  && ok "instance responding" || bad "instance not responding (is the stack up?)"

echo "2/5 search"
curl -sf -m 20 "$BASE/api/v1/search?q=linux&search_index=current" | grep -q videoId \
  && ok "search returns results" || bad "search failed (companion/PO token issue?)"

echo "3/5 video metadata + streams"
STREAMS=$(curl -sf -m 30 "$BASE/api/v1/videos/$VID?fields=formatStreams,audioStreams" \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); print(len(d.get("formatStreams",[]))+len(d.get("audioStreams",[])))' 2>/dev/null || echo 0)
[ "$STREAMS" -gt 0 ] && ok "$STREAMS stream(s) offered" || bad "no streams (503? wait ~5 min after companion start for PO token)"

echo "4/5 DASH manifest"
CHECK=$(curl -sf -m 30 "$BASE/watch?v=$VID&quality=dash" | grep -oE '/companion/api/manifest/dash/[^"]*' | head -1)
[ -n "$CHECK" ] || bad "no DASH source in player page"
MPD=$(curl -sf -m 30 "$BASE$CHECK" || true)
echo "$MPD" | grep -q '<MPD' && ok "manifest served ($(echo "$MPD" | wc -c) bytes)" || bad "manifest failed (body: $(echo "$MPD" | head -c 80))"

echo "5/5 actual segment bytes (the one that catches silent failures)"
SEG=$(python3 - "$MPD" <<'EOF'
import re,html,sys
m=re.search(r'<BaseURL>([^<]+)</BaseURL>',sys.argv[1])
print(html.unescape(m.group(1)) if m else '')
EOF
)
if [ -n "$SEG" ]; then
  HDR=$(curl -s -m 30 -H 'Range: bytes=0-100000' "$BASE$SEG" -o /tmp/verify_seg -w '%{http_code} %{content_type} %{size_download}')
  CT=$(echo "$HDR" | cut -d' ' -f2); SIZE=$(echo "$HDR" | cut -d' ' -f3)
  # NB: no `xxd | head` here — with pipefail, head closing the pipe early makes
  # xxd die of SIGPIPE and the pipeline "fail" even when the magic matches.
  if [ "$SIZE" -gt 1000 ] && head -c 4096 /tmp/verify_seg | grep -aqE 'ftyp|styp|moof|mdat'; then
    ok "segment: 206-style response, $SIZE bytes of $CT, valid ISO-BMFF box header"
  elif [ "$CT" = "application/vnd.yt-ump" ]; then
    bad "0-byte yt-ump segments — UMP flag is enabled, REMOVE IT (see TROUBLESHOOTING)"
  else
    bad "segment fetch broken: $HDR"
  fi
else
  bad "no segment BaseURL found in manifest"
fi

echo; echo "result: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
