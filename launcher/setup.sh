#!/usr/bin/env bash
# Send private dashboard settings to the launcher after installing it. Values are never printed.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && (pwd -W 2>/dev/null || pwd))"
FILE="${1:-$HERE/dashboard.txt}"
[ -f "$FILE" ] || { echo "no dashboard file (copy dashboard.example.txt to dashboard.txt and edit it)" >&2; exit 1; }
cd "$(dirname "$0")/../tools"
: "${TV_HOST:?set TV_HOST to the TV IP address}"
PY=venv/bin/python
[ -x "$PY" ] || PY=venv/Scripts/python.exe
[ -x "$PY" ] || { echo "set up tools/venv first" >&2; exit 1; }
export MSYS_NO_PATHCONV=1
PKG=com.gathin.tvhome
for properties in ../launcher/gradle.properties ../launcher/local.properties; do
  if [ -f "$properties" ]; then
    configured=$(awk -F= '/^[[:space:]]*launcherApplicationId[[:space:]]*=/ { sub(/^[^=]*=/, ""); sub(/\r$/, ""); gsub(/^[[:space:]]+|[[:space:]]+$/, ""); print; exit }' "$properties")
    [ -z "$configured" ] || PKG="$configured"
  fi
done
TMP=/data/local/tmp/tv-dashboard.txt
tv() { "$PY" -I tv.py "$@"; }
tv "pm path $PKG; true" | grep -q package: || { echo "Launcher is not installed" >&2; exit 1; }
tv "appops set $PKG AUTO_START allow 2>/dev/null; true" >/dev/null
"$PY" -I b64push.py "$(cygpath -w "$FILE" 2>/dev/null || printf '%s' "$FILE")" "$TMP" >/dev/null || { echo "sending dashboard failed" >&2; exit 1; }
tv "content write --uri content://$PKG.files/dashboard.txt < $TMP; rm -f $TMP" >/dev/null
echo "Launcher dashboard sent. Values are not printed."
