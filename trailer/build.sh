#!/usr/bin/env bash
# Builds the trailer end to end: score -> picture -> mux.
#   ./build.sh                  full render (≈1 h on 4 CPU cores, no GPU needed)
#   ./build.sh --workers 2      passed through to render.mjs
set -euo pipefail
cd "$(dirname "$0")"

npm install --no-audit --no-fund
python3 -m pip install -q -r audio/requirements.txt

python3 audio/score.py                      # -> out/audio.wav
node render.mjs --out video.mp4 "$@"        # -> out/video.mp4

ffmpeg -y -loglevel error -i out/video.mp4 -i out/audio.wav \
  -map 0:v -map 1:a -c:v copy -c:a aac -b:a 320k -ar 48000 -shortest \
  -movflags +faststart distant-frontiers-trailer.mp4
echo "wrote distant-frontiers-trailer.mp4"
