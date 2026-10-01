#!/usr/bin/env bash
# One command: synthesize the soundtrack, render every frame, encode the video.
set -euo pipefail
cd "$(dirname "$0")"
WORK="${WORK:-.work}"
mkdir -p "$WORK/frames" out
node audio/synth.js "$WORK/soundtrack.wav"
node render.js --frames="0-$(node -p "require('./src/shared.js').FRAMES - 1")" --dir="$WORK/frames"
node render.js --encode --dir="$WORK/frames" --audio="$WORK/soundtrack.wav" --out=out/showreel.mp4
ffmpeg -y -hide_banner -loglevel error -i "$WORK/soundtrack.wav" -c:a libmp3lame -b:a 192k out/soundtrack.mp3
