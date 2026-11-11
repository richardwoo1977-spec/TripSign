#!/bin/bash
# ver 1.1.0 | 2026-11-11 14:16
# Demo drive on the Pleos Virtual Console (adb forward tcp:8088 tcp:8088 must be in place).
#   bash tools/demo/drive.sh 120       -> P→D, 60 km/h for 120 s, odometer +1 km every 60 s (= 2 km), stop, D→P
#   bash tools/demo/drive.sh 120 30    -> same at 30 km/h, odometer +1 km every 120 s
# 1.1.0: the odometer now moves at the speed actually shown (1.0.0 added 1 km every 15 s at 30 km/h = 240 km/h).
# Keeps the console session alive (heartbeat every second) for the whole run.
SECS=${1:-120}
SPEED=${2:-60}
STEP=$((3600 / SPEED))
B=http://127.0.0.1:8088
hb() { curl -s -m 2 -X POST "$B/browser/session?client=tripsign-demo" >/dev/null; }
put() { curl -s -m 3 -o /dev/null -w "%{http_code}" -X PUT "$B$1"; }

if ! curl -s -m 3 -o /dev/null "$B/vehicle/state"; then echo "콘솔 연결 안 됨: adb forward tcp:8088 tcp:8088 를 먼저 실행하세요"; exit 1; fi
hb
ODO=$(curl -s -m 3 "$B/vehicle/state" | grep -o '"traveled_distance_km":[0-9.]*' | head -1 | cut -d: -f2)
ODO=${ODO%.*}; ODO=${ODO:-0}
echo "$(date +%T) 계기판 ${ODO} km · ${SPEED} km/h · ${STEP}초마다 1 km · 출발(D) $(put '/vehicle/gear?gear=Drive')"
for ((i=0; i<SECS; i++)); do
  hb
  (( i % 3 == 0 )) && put "/vehicle/controls?speed=$SPEED" >/dev/null
  if (( i % STEP == STEP - 1 )); then ODO=$((ODO+1)); echo "$(date +%T) 계기판 ${ODO} km $(put "/vehicle/odometer?traveled_distance_km=$ODO")"; fi
  sleep 1
done
put '/vehicle/controls?speed=0' >/dev/null
for i in 1 2 3 4; do hb; sleep 1; done
echo "$(date +%T) 정차·도착(P) $(put '/vehicle/gear?gear=Park')"
for i in $(seq 1 10); do hb; sleep 1; done
echo "$(date +%T) 끝 · 계기판 ${ODO} km"
