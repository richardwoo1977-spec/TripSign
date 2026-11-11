#!/bin/bash
# ver 1.0.0 | 2026-11-11 13:10
# Records the emulator screen and sound for one scene (1280x720, ≤150 s).
#   bash tools/demo/rec.sh S3 150    -> _video/S3.webm, stops by itself after 150 s
NAME=${1:?장면 이름을 주세요 (예: S3)}
SECS=${2:-150}
A=~/Library/Android/sdk/platform-tools/adb
OUT="$HOME/AndroidStudioProjects/TripSign/_video/$NAME.webm"
if [ -e "$OUT" ]; then echo "이미 있음: $OUT (다른 이름을 쓰세요, 예: ${NAME}b)"; exit 1; fi
$A -s emulator-5554 emu screenrecord start --size 1280x720 --bit-rate 4000000 --time-limit "$SECS" "$OUT" >/dev/null && echo "$(date +%T) 녹화 시작: $NAME (${SECS}초 뒤 자동 종료)"
