#!/bin/bash
# Orientation test: phone physically landscape, app set to "always landscape" → are recordings landscape?
PKG=com.jeremy.dashcam; OUT=results; mkdir -p $OUT; SUM=$OUT/summary.md
echo "# orientation test – API $(adb shell getprop ro.build.version.sdk | tr -d '\r')" > $SUM
info() { echo "- $1" | tee -a $SUM; }
shot() { adb exec-out screencap -p > "$OUT/$1.png"; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml > $OUT/ui.xml 2>/dev/null; }
find_text() { dump; python3 - "$1" <<'PY'
import sys,re,xml.etree.ElementTree as ET
q=sys.argv[1]
try: r=ET.parse('results/ui.xml').getroot()
except Exception: sys.exit(0)
ns=list(r.iter('node'))
m=[n for n in ns if (n.get('text') or '')==q] or [n for n in ns if q in (n.get('text') or '')]
if m:
    x1,y1,x2,y2=map(int,re.findall(r'\d+',m[0].get('bounds'))); print((x1+x2)//2,(y1+y2)//2)
PY
}
tap_text() { local p; p=$(find_text "$1"); [ -n "$p" ] && adb shell input tap $p; }
probe() { ffprobe -v error -select_streams v:0 -show_entries stream=width,height:stream_side_data=rotation -of compact=p=0:nk=1 "$1" | tr '\n' ' '; }

sleep 20
adb install -r -g app-debug.apk >/dev/null && info "installed"
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow
adb shell settings put system accelerometer_rotation 1
run_case() {   # $1 name, $2 orient setting, $3 accel vector
  adb shell am force-stop $PKG
  adb shell "run-as $PKG sh -c 'rm -rf files/events.json files/buffer; mkdir -p shared_prefs && cat > shared_prefs/jeremy_settings.xml'" <<XML
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map><boolean name="trip" value="$4" /><int name="tripMin" value="1" /><boolean name="overlayPrompt" value="true" /><string name="orient">$2</string><boolean name="shock" value="false" /><boolean name="smart" value="false" /></map>
XML
  adb emu sensor set acceleration $3 >/dev/null; sleep 2
  adb logcat -c
  adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 9
  shot "$1_1_home"
  for i in 1 2 3; do tap_text "Start"; sleep 6; adb shell dumpsys activity services $PKG | grep -q isForeground=true && break; done
  sleep 14
  SEG=$(adb shell run-as $PKG ls files/buffer | tr -d '\r' | grep mp4 | sort | head -1)
  adb exec-out run-as $PKG cat files/buffer/$SEG > $OUT/$1_segment.mp4
  info "$1: raw segment (w h rotation): $(probe $OUT/$1_segment.mp4)"
  ffmpeg -v error -y -ss 1 -i $OUT/$1_segment.mp4 -frames:v 1 $OUT/$1_segment_frame.png
  tap_text "Camera"; sleep 3; shot "$1_2_camera"
  B=$(find_text "Save event"); adb shell input tap $B; sleep 7; adb shell input tap $B
  for i in $(seq 1 100); do adb shell run-as $PKG cat files/events.json 2>/dev/null | grep -q '"kind":"EVENT"' && break; sleep 4; done
  [ "$4" = "true" ] && { sleep 60; for i in $(seq 1 60); do adb shell run-as $PKG cat files/events.json 2>/dev/null | grep -q '"kind":"TRIP"' && break; sleep 4; done; }
  adb shell run-as $PKG cat files/events.json > $OUT/$1_events.json
  for kind in EVENT TRIP; do
    P=$(python3 -c "import json;r=[e['path'] for e in json.load(open('$OUT/$1_events.json')) if e.get('kind')=='$kind'];print(r[0] if r else '')")
    [ -z "$P" ] && { info "$1: no $kind file"; continue; }
    adb exec-out run-as $PKG cat "$P" > $OUT/$1_$kind.mp4
    info "$1: exported $kind (w h rotation): $(probe $OUT/$1_$kind.mp4)"
    ffmpeg -v error -y -ss 3 -i $OUT/$1_$kind.mp4 -frames:v 1 $OUT/$1_${kind}_frame.png
  done
  adb logcat -d -s Jeremy:I | grep -E "export" | head -8 | sed 's/^/    /' | tee -a $SUM
  adb logcat -d -s Jeremy:I | grep -E "segment start|orientation" | head -6 | sed 's/^/    /' | tee -a $SUM
  adb shell am force-stop $PKG; rm -f $OUT/*.mp4
}
# emulator: accel x=+9.8 → device rotated so left edge is down (landscape)
run_case landscape_physical_lock LANDSCAPE "9.81:0:0" true
run_case landscape_physical_auto AUTO "9.81:0:0" false
run_case portrait_physical_auto AUTO "0:9.81:0" false
echo done >> $SUM
