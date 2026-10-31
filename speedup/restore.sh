#!/usr/bin/env bash
# Brings back apps removed by remove.sh.
#
#   TV=<tv-ip>:5555 speedup/restore.sh com.example.one ...   # some apps
#   TV=<tv-ip>:5555 speedup/restore.sh --all                  # everything in removed.log
set -eu # no pipefail: adb exits non-zero for "not installed" and similar answers
export MSYS_NO_PATHCONV=1

tv=${TV:?set TV to your TV, e.g. TV=192.168.1.20:5555}
here=$(cd "$(dirname "$0")" && pwd)
if [ "${1:-}" = "--all" ]; then
  set -- $(awk '$3 != "not-installed" {print $2}' "$here/removed.log" | sort -u)
fi

for p in "$@"; do
  # Preinstalled apps come back from the system image; disabled ones just need enabling.
  out=$(adb -s "$tv" shell cmd package install-existing --user 0 "$p" 2>&1 | tr -d '\r')
  adb -s "$tv" shell pm enable --user 0 "$p" >/dev/null 2>&1 || true
  if ! echo "$out" | grep -q "installed for user"; then
    # Apps someone installed were fully removed: reinstall from the backup.
    if ls "$here/backup/$p/"*.apk >/dev/null 2>&1; then
      out=$(adb -s "$tv" install-multiple -r "$here/backup/$p/"*.apk 2>&1 | tr -d '\r')
    else
      out="no backup found"
    fi
  fi
  echo "$p -> $out"
done
