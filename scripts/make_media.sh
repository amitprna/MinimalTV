#!/usr/bin/env bash
# Builds the README's night and day clips: the launcher's interface (rendered by its capture
# command on a transparent background) over the scene video, with the scene's sound.
#
#   scripts/make_media.sh <scenes-dir> <captures-dir> [out-dir]
#
# captures-dir needs ui-night.png, ui-day.png and standby.png, from:
#   adb shell am start -n io.github.amitprna.minimaltv/.MainActivity --es capture ui-night --es layer ui
#   adb shell am start -n io.github.amitprna.minimaltv/.MainActivity --es capture standby --es layer standby
# (then adb pull /sdcard/Android/data/io.github.amitprna.minimaltv/files/captures)
set -euo pipefail
scenes=$1 caps=$2 out=${3:-docs/media}
mkdir -p "$out"
enc="-c:v libx264 -preset slow -crf 25 -pix_fmt yuv420p -movflags +faststart -c:a aac -b:a 128k"

# Night: Home over the rain room for 5 s, then StandBy (blurred clip, veil, flip clock) for 4 s.
ffmpeg -v error -y \
  -ss 4 -t 6 -i "$scenes/rain-room.mp4" -loop 1 -t 6 -i "$caps/ui-night.png" \
  -ss 9 -t 4 -i "$scenes/rain-room-blur30.mp4" -loop 1 -t 4 -i "$caps/standby.png" \
  -ss 20 -t 9 -i "$scenes/rain.ogg" \
  -filter_complex "[0:v][1:v]overlay,scale=1280:720,setsar=1,fps=30[home];
                   [2:v]scale=1920:1080:flags=bicubic[blur];[blur][3:v]overlay,scale=1280:720,setsar=1,fps=30[sb];
                   [home][sb]xfade=transition=fade:duration=1:offset=5[v];
                   [4:a]afade=t=in:d=0.8,afade=t=out:st=8:d=1,volume=2.5,alimiter=limit=0.8:level=false[a]" \
  -map "[v]" -map "[a]" -t 9 $enc "$out/night.mp4"

# Day: Home over the waterfall, with its sound.
ffmpeg -v error -y \
  -ss 2 -t 8 -i "$scenes/waterfall.mp4" -loop 1 -t 8 -i "$caps/ui-day.png" -ss 30 -t 8 -i "$scenes/waterfall.ogg" \
  -filter_complex "[0:v][1:v]overlay,scale=1280:720,setsar=1,fps=30[v];[2:a]afade=t=in:d=0.8,afade=t=out:st=7:d=1,volume=2.5[a]" \
  -map "[v]" -map "[a]" -t 8 $enc "$out/day.mp4"

# Silent GIFs that play inline in the README.
for n in night day; do
  ffmpeg -v error -y -i "$out/$n.mp4" -vf "fps=10,scale=560:-2:flags=lanczos,split[a][b];[a]palettegen=max_colors=96[p];[b][p]paletteuse=dither=bayer:bayer_scale=4" \
    "$out/$n.gif"
done
ls -la "$out"
