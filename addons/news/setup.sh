#!/usr/bin/env bash
# Send private settings to News after installing the app. Values are never printed.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && (pwd -W 2>/dev/null || pwd))"
FILE="${1:-$HERE/settings.txt}"
[ -f "$FILE" ] || { echo "no settings file (copy settings.example.txt to settings.txt and edit it)" >&2; exit 1; }
cd "$(dirname "$0")/../../tools"
: "${TV_HOST:?set TV_HOST to the TV IP address}"
PY=venv/bin/python
[ -x "$PY" ] || PY=venv/Scripts/python.exe
[ -x "$PY" ] || { echo "set up tools/venv first" >&2; exit 1; }
export MSYS_NO_PATHCONV=1
PKG=com.gathin.news
TMP=/data/local/tmp/news-settings.txt
tv() { "$PY" -I tv.py "$@"; }
tv "pm path $PKG; true" | grep -q package: || { echo "News is not installed" >&2; exit 1; }
tv "appops set $PKG AUTO_START allow 2>/dev/null; true" >/dev/null
"$PY" -I b64push.py "$(cygpath -w "$FILE" 2>/dev/null || printf '%s' "$FILE")" "$TMP" >/dev/null || { echo "sending settings failed" >&2; exit 1; }
tv "content write --uri content://$PKG.files/settings.txt < $TMP; rm -f $TMP" >/dev/null
echo "News settings sent. Values are not printed."
