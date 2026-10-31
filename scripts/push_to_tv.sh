#!/usr/bin/env bash
# Copies MinimalTV's live wallpapers and private settings to the TV over ADB.
#
#   TV=192.168.1.20:5555 scripts/push_to_tv.sh [scenes-dir] [config-dir]
#
# scenes-dir (default ./scenes): the output of prepare_scenes.sh, if any
# config-dir (default ./config): location.json and tuya.json, copied from the *.example.json
#   files and filled in. Both are optional and stay off GitHub (.gitignore).
set -euo pipefail
export MSYS_NO_PATHCONV=1 # Git Bash on Windows: don't rewrite /sdcard paths

tv=${TV:?set TV to your TV, e.g. TV=192.168.1.20:5555}
scenes=${1:-scenes} config=${2:-config}
dest=/sdcard/Android/data/io.github.amitprna.minimaltv/files

adb connect "$tv" >/dev/null
adb -s "$tv" shell mkdir -p "$dest/scenes"
if ls "$scenes"/*.mp4 >/dev/null 2>&1; then
  adb -s "$tv" push "$scenes"/*.mp4 "$scenes"/*.ogg "$dest/scenes/"
fi
for f in location.json tuya.json; do
  if [ -f "$config/$f" ]; then adb -s "$tv" push "$config/$f" "$dest/$f"; fi
done
adb -s "$tv" shell ls -la "$dest" "$dest/scenes"
