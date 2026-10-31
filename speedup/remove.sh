#!/usr/bin/env bash
# Removes apps from the TV for the current user, backing each one up first.
#
#   TV=<tv-ip>:5555 speedup/remove.sh com.example.one com.example.two ...
#
# Backups go to speedup/backup/<package>/; every result is appended to speedup/removed.log.
# System apps are removed with `pm uninstall -k --user 0` (restore.sh brings them back); apps
# that refuse are disabled instead.
set -eu # no pipefail: adb exits non-zero for "not installed" and similar answers
export MSYS_NO_PATHCONV=1 # Git Bash on Windows: don't rewrite device paths

tv=${TV:?set TV to your TV, e.g. TV=192.168.1.20:5555}
here=$(cd "$(dirname "$0")" && pwd)
log="$here/removed.log"

for p in "$@"; do
  paths=$(adb -s "$tv" shell pm path "$p" 2>/dev/null | tr -d '\r' | sed 's/^package://')
  if [ -z "$paths" ]; then echo "$(date +%FT%T) $p not-installed" | tee -a "$log"; continue; fi
  mkdir -p "$here/backup/$p"
  for apk in $paths; do adb -s "$tv" pull "$apk" "$here/backup/$p/" >/dev/null; done
  out=$(adb -s "$tv" shell pm uninstall -k --user 0 "$p" | tr -d '\r')
  if [ "$out" != "Success" ]; then
    out="uninstall refused ($out); $(adb -s "$tv" shell pm disable-user --user 0 "$p" | tr -d '\r')"
  fi
  echo "$(date +%FT%T) $p $out" | tee -a "$log"
done
