#!/bin/bash
# ver 1.4.0 | 2026-11-04 21:20
# TripSign layout check: opens every screen in Korean/English x light/dark (day/night) on the running
# emulator, saves a screenshot of each, and collects the app's CLIP/WRAP text reports.
# Needs the debug build installed (Android Studio ▶ Run). Takes about 5 minutes.
# v0.5: no large text (removed from the app), so only normal size is checked.
# Also resets the x52 sample-distance test (demoScale) by force-stopping the app at the end.
set -u
# adb from PATH, or from the Android Studio SDK folder. A phone may be plugged in as well, so every
# command goes to the emulator only.
ADB_BIN="$(command -v adb || echo "$HOME/Library/Android/sdk/platform-tools/adb")"
SERIAL="$("$ADB_BIN" devices | awk '/^emulator-[0-9]+[[:space:]]+device/{print $1; exit}')"
adbx() { "$ADB_BIN" -s "$SERIAL" "$@"; }
PKG=io.tripsign.app
ACT=$PKG/$PKG.ui.MainActivity
OUT="$HOME/Desktop/tripsign_layout_check"
SHOTS="title intro1 intro2 intro3 permission logbook logbook_unc logbook_unc_filter picker_purpose picker_driver export trips trips_search settings settings_users settings_display settings_integrity settings_perm settings_about arrival splash login login_pin signup"
TOTAL=$(( $(echo $SHOTS | wc -w) * 4 ))

if [ -z "$SERIAL" ] || [ "$(adbx get-state 2>/dev/null)" != "device" ]; then
  echo "에뮬레이터가 연결되어 있지 않습니다. 에뮬레이터를 켠 뒤 다시 실행하세요."
  exit 1
fi

rm -rf "$OUT" && mkdir -p "$OUT/png"
adbx shell am force-stop "$PKG"
adbx logcat -c
adbx shell am start -n "$ACT" > /dev/null 2>&1
sleep 4

n=0
for theme in light dark; do
  for lang in ko en; do
    for size in normal; do
      for s in $SHOTS; do
        n=$((n + 1))
        adbx shell am start -n "$ACT" --es shot "$s" --es size "$size" --es lang "$lang" --es theme "$theme" > /dev/null 2>&1
        sleep 2.5
        adbx exec-out screencap -p > "$OUT/png/${theme}_${lang}_${size}_${s}.png"
        sips -s format jpeg -s formatOptions 80 --resampleWidth 1600 "$OUT/png/${theme}_${lang}_${size}_${s}.png" --out "$OUT/${theme}_${lang}_${size}_${s}.jpg" > /dev/null 2>&1
        echo "[$n/$TOTAL] $theme $lang $size $s"
      done
    done
  done
done

# big numbers (sample distances x52: about 100,000 km a month, 1,000,000 km a year), worst case
for s in logbook export trips; do
  for lang in ko en; do
    adbx shell am start -n "$ACT" --ef demoScale 52 --es shot "$s" --es size normal --es lang "$lang" > /dev/null 2>&1
    sleep 2.5
    adbx exec-out screencap -p > "$OUT/png/x52_${lang}_${s}.png"
    sips -s format jpeg -s formatOptions 80 --resampleWidth 1600 "$OUT/png/x52_${lang}_${s}.png" --out "$OUT/x52_${lang}_${s}.jpg" > /dev/null 2>&1
    echo "[x52] $lang $s"
  done
done

adbx logcat -d -s TripSignLayout:W > "$OUT/overflow.txt"
rm -rf "$OUT/png"
adbx shell am force-stop "$PKG"

cd "$HOME/Desktop" && rm -f tripsign_layout_check.zip && zip -qr tripsign_layout_check.zip tripsign_layout_check
echo ""
echo "완료: 바탕화면의 tripsign_layout_check.zip"
echo "잘림/줄바꿈 보고 줄 수: $(grep -c -E 'CLIP|WRAP' "$OUT/overflow.txt")"
