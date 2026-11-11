#!/bin/bash
# ver 1.0.1 | 2026-11-11 13:44
# Set the Pleos Virtual Console odometer to an exact whole-km value through the console API.
# The console screen only moves in 10 km steps; whether the API takes other values is being checked (13:42 test: 400 while the console sat at 1,000,000 km).
# (adb forward tcp:8088 tcp:8088 must be in place)
#   bash tools/demo/odo.sh 12340    -> odometer = 12,340 km, then prints the value the console reports
KM=${1:?사용법: bash tools/demo/odo.sh 12340}
B=http://127.0.0.1:8088
if ! curl -s -m 3 -o /dev/null "$B/vehicle/state"; then echo "콘솔 연결 안 됨: adb forward tcp:8088 tcp:8088 를 먼저 실행하세요"; exit 1; fi
curl -s -m 2 -X POST "$B/browser/session?client=tripsign-demo" >/dev/null
RES=$(curl -s -m 3 -w " [%{http_code}]" -X PUT "$B/vehicle/odometer?traveled_distance_km=$KM")
CODE=${RES##* }
sleep 1
NOW=$(curl -s -m 3 "$B/vehicle/state" | grep -o '"traveled_distance_km":[0-9.]*' | head -1 | cut -d: -f2)
echo "$(date +%T) 요청 ${KM} km · 응답 ${CODE} · 콘솔 값 ${NOW} km"
echo "응답 내용: ${RES% *}" | cut -c1-300
