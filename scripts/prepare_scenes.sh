#!/usr/bin/env bash
# Builds MinimalTV's live wallpapers from the Pixabay originals (download them yourself from
# the pages in AGENTS.md; the Pixabay licence doesn't allow redistributing the files here).
#
#   scripts/prepare_scenes.sh <rain-room.mp4> <gentle-rain.mp3> <waterfall.mp4> <waterfall.mp3> [out-dir]
#
# For each scene it writes, into out-dir (default ./scenes):
#   <name>.mp4         1080p30 H.264, lightly graded and sharpened, last 1.5 s crossfaded into the
#                      first so the loop has no seam
#   <name>-blur30.mp4  480x270 pre-blurred copy for StandBy's 30% blur (same length and loop)
#   <name>-blur60.mp4  320x180 pre-blurred copy for 60%
#   <sound>.ogg        Vorbis ambience, crossfaded loop, levelled to the rain track (-27.7 LUFS)
set -euo pipefail

rain_mp4=$1 rain_mp3=$2 fall_mp4=$3 fall_mp3=$4 out=${5:-scenes}
mkdir -p "$out"

# video <in> <out-name> <grade> : 1080p30, graded, seamless loop (1.5 s crossfade), plus blurred copies
video() {
  local in=$1 name=$2 grade=$3
  local len xfade
  len=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$in")
  xfade=$(python3 -c "print(round($len - 3, 3))") # main part = len - 1.5, fade starts 1.5 s before its end
  ffmpeg -v error -y -i "$in" -filter_complex \
    "[0:v]fps=30,scale=1920:1080:flags=lanczos,$grade,split[a][b];[a]trim=start=1.5,setpts=PTS-STARTPTS[m];[b]trim=0:1.5,setpts=PTS-STARTPTS[h];[m][h]xfade=transition=fade:duration=1.5:offset=$xfade,format=yuv420p[v]" \
    -map "[v]" -an -c:v libx264 -preset slow -crf 17 -maxrate 14M -bufsize 28M -profile:v high -level 4.2 -g 60 -movflags +faststart \
    "$out/$name.mp4"
  ffmpeg -v error -y -i "$out/$name.mp4" -vf "scale=480:270:flags=area,gblur=sigma=3,format=yuv420p" \
    -an -c:v libx264 -preset slow -crf 20 -profile:v high -g 60 -movflags +faststart "$out/$name-blur30.mp4"
  ffmpeg -v error -y -i "$out/$name.mp4" -vf "scale=320:180:flags=area,gblur=sigma=4,format=yuv420p" \
    -an -c:v libx264 -preset slow -crf 20 -profile:v high -g 60 -movflags +faststart "$out/$name-blur60.mp4"
}

# sound <in> <out-name> <gain> : seamless Vorbis loop (3 s crossfade)
sound() {
  ffmpeg -v error -y -i "$1" -filter_complex \
    "[0:a]volume=$3,asplit[a][b];[a]atrim=start=3,asetpts=PTS-STARTPTS[m];[b]atrim=0:3,asetpts=PTS-STARTPTS[h];[m][h]acrossfade=d=3[o]" \
    -map "[o]" -c:a libvorbis -q:a 7 "$out/$2.ogg"
}

# The rain room is dark and a little flat: brighten and saturate it. The waterfall needs less.
video "$rain_mp4" rain-room "eq=contrast=1.08:brightness=0.02:saturation=1.2:gamma=1.08,unsharp=5:5:0.5"
video "$fall_mp4" waterfall "eq=contrast=1.05:saturation=1.1,unsharp=5:5:0.35"
sound "$rain_mp3" rain 0dB
sound "$fall_mp3" waterfall -14.3dB # the waterfall recording is ~14 dB hotter than the rain

ls -la "$out"
