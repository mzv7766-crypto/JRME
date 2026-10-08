#!/bin/bash
# Jeremy smoke test – runs on an emulator via adb. Never aborts; records PASS/FAIL lines.
PKG=com.jeremy.dashcam
OUT=results
mkdir -p $OUT
SUM=$OUT/summary.md
echo "# Jeremy smoke test – API $(adb shell getprop ro.build.version.sdk | tr -d '\r')" > $SUM
pass() { echo "- ✅ $1" | tee -a $SUM; }
fail() { echo "- ❌ $1" | tee -a $SUM; }
info() { echo "  - $1" | tee -a $SUM; }
shot() { adb exec-out screencap -p > "$OUT/$1.png"; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml > $OUT/ui.xml 2>/dev/null; }
# prints "x y" of first node whose text/content-desc contains $1
find_text() { dump; python3 - "$1" <<'PY'
import sys,re,xml.etree.ElementTree as ET
q=sys.argv[1]
try: root=ET.parse('results/ui.xml').getroot()
except Exception: sys.exit(0)
for n in root.iter('node'):
    t=(n.get('text') or '')+' '+(n.get('content-desc') or '')
    if q in t:
        x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')))
        print((x1+x2)//2,(y1+y2)//2); break
PY
}
tap_text() { local p; p=$(find_text "$1"); if [ -n "$p" ]; then adb shell input tap $p; echo "$p"; return 0; fi; return 1; }
svc_fg() { adb shell dumpsys activity services $PKG | grep -q "isForeground=true"; }
crashes() { adb logcat -d -b crash 2>/dev/null | grep -c "$PKG"; }
seg_count() { adb shell run-as $PKG ls files/buffer 2>/dev/null | grep -c mp4; }
seg_newest() { adb shell run-as $PKG ls files/buffer 2>/dev/null | grep mp4 | sort | tail -1 | tr -d '\r'; }
EVDIR=/sdcard/Android/data/$PKG/files/Movies/events
ev_count() { adb shell ls $EVDIR 2>/dev/null | grep -c mp4; }

adb logcat -c
read W H <<< "$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | tr 'x' ' ')"
D=$(adb shell wm density | grep -oE '[0-9]+' | tail -1); DP=$(python3 -c "print($D/160)")
info "screen ${W}x${H} density $D"

# 1. install + permissions
adb install -r -g app-debug.apk > $OUT/install.txt 2>&1 && pass "Install" || { fail "Install: $(cat $OUT/install.txt)"; exit 0; }
adb shell pm grant $PKG android.permission.CAMERA
adb shell pm grant $PKG android.permission.RECORD_AUDIO
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow
adb shell settings put system accelerometer_rotation 0

# 2. launch + splash
adb shell am start -W -n $PKG/.MainActivity > $OUT/launch.txt
sleep 1.2; shot 01_splash
sleep 3; shot 02_home_idle
[ "$(crashes)" = "0" ] && pass "Launch + splash animation, no crash" || fail "Crash on launch"

# 3. start drive
if tap_text "Start" >/dev/null; then pass "Tapped Start drive"; else fail "Start drive button not found"; fi
sleep 6; shot 03_home_active
svc_fg && pass "Foreground camera service running" || fail "Foreground service NOT running"
adb shell dumpsys activity services $PKG | grep -E "foregroundServiceType|isForeground" | head -3 > $OUT/fgs.txt
info "$(tr '\n' ' ' < $OUT/fgs.txt)"
sleep 14
N=$(seg_count); [ "$N" -ge 2 ] && pass "Rolling buffer writing segments ($N files after ~20s)" || fail "Rolling buffer: only $N segments"

# 4. tabs
for t in Events Guide Settings; do tap_text "$t" >/dev/null && sleep 2 && shot "04_tab_$t" || fail "Tab $t not found"; done
tap_text "Camera" >/dev/null; sleep 3; shot 05_camera_portrait
svc_fg && pass "Navigation through all tabs while recording" || fail "Service died while navigating"

# 5. in-app event save (pre-event buffer)
BTN=$(find_text "Save event")
if [ -n "$BTN" ]; then
  adb shell input tap $BTN; sleep 2; shot 06_event_running
  sleep 6; adb shell input tap $BTN; pass "Event started and stopped from camera screen"
else fail "Save event button not found"; fi
for i in $(seq 1 30); do [ "$(ev_count)" -ge 1 ] && break; sleep 3; done
[ "$(ev_count)" -ge 1 ] && pass "Event #1 exported to MP4" || fail "Event #1 not exported within 90s"

# 6. discard
adb shell input tap $BTN; sleep 4
tap_text "Home" >/dev/null; sleep 2; shot 07_home_event_active
if tap_text "End without saving" >/dev/null; then sleep 6
  [ "$(ev_count)" -eq 1 ] && pass "Discard: event ended without creating a video" || fail "Discard created a video"
else fail "Discard button not found on home"; fi
svc_fg && pass "Recording continues after discard" || fail "Service stopped after discard"

# 7. landscape camera
tap_text "Camera" >/dev/null; sleep 1
adb shell settings put system user_rotation 1; sleep 4; shot 08_camera_landscape
adb shell settings put system user_rotation 0; sleep 3

# 8. background + floating bubble
adb shell input keyevent KEYCODE_HOME; sleep 4; shot 09_background_bubble
svc_fg && pass "Service stays foreground after leaving app" || fail "Service stopped when leaving app"
S1=$(seg_newest); sleep 12; S2=$(seg_newest)
[ "$S1" != "$S2" ] && pass "Camera keeps recording in background ($S1 → $S2)" || fail "No new segments in background"
adb shell dumpsys window windows | grep -iE "jeremy|$PKG" | grep -i -m3 "overlay\|Window{" > $OUT/overlay.txt
BX=$(python3 -c "print(int($W - 12*$DP - 34*$DP))"); BY=$(python3 -c "print(int($H*0.45 + 34*$DP))")
info "bubble tap at $BX,$BY"
adb shell input tap $BX $BY; sleep 2; shot 10_bubble_event
sleep 6; adb shell input tap $BX $BY
for i in $(seq 1 30); do [ "$(ev_count)" -ge 2 ] && break; sleep 3; done
[ "$(ev_count)" -ge 2 ] && pass "Floating bubble: event saved while app in background" || fail "Bubble event not saved (overlay: $(head -c 200 $OUT/overlay.txt))"
adb shell cmd statusbar expand-notifications; sleep 2; shot 11_notification; adb shell cmd statusbar collapse

# 9. reopen app, events list + detail
adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 3
tap_text "Events" >/dev/null; sleep 3; shot 12_events
dump; FIRST=$(python3 - <<'PY'
import re,xml.etree.ElementTree as ET
r=ET.parse('results/ui.xml').getroot()
for n in r.iter('node'):
    t=n.get('text') or ''
    if re.fullmatch(r'\d\d:\d\d',t):
        x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds'))); print((x1+x2)//2,(y1+y2)//2); break
PY
)
[ -n "$FIRST" ] && adb shell input tap $FIRST && sleep 4 && shot 13_event_detail && pass "Opened event detail / player" || fail "No event row found"
adb shell input keyevent KEYCODE_BACK; sleep 2

# 10. long-press bubble to stop everything
adb shell input keyevent KEYCODE_HOME; sleep 3
adb shell input swipe $BX $BY $BX $BY 1600; sleep 5; shot 14_after_longpress
svc_fg && fail "Long-press bubble did NOT stop drive mode" || pass "Long-press bubble stopped camera + service"

# 11. crash / error scan
adb logcat -d > $OUT/logcat.txt
adb logcat -d -b crash > $OUT/crash.txt 2>/dev/null
C=$(grep -c "$PKG" $OUT/crash.txt); A=$(grep -c "ANR in $PKG" $OUT/logcat.txt)
[ "$C" = "0" ] && pass "No crashes in logcat" || fail "$C crash lines (see crash.txt)"
[ "$A" = "0" ] && pass "No ANRs" || fail "$A ANR(s)"
grep -E "DashcamService|EventExporter|Camera2CameraImpl.*ERROR|Recorder.*error" $OUT/logcat.txt | grep -iE " E |error|fail" | head -40 > $OUT/app_errors.txt
info "app error lines: $(wc -l < $OUT/app_errors.txt)"

# 12. videos
adb shell ls -l $EVDIR > $OUT/events_ls.txt 2>&1
mkdir -p $OUT/videos; i=0
for f in $(adb shell ls $EVDIR | tr -d '\r' | grep mp4); do
  i=$((i+1)); adb pull $EVDIR/$f $OUT/videos/ev$i.mp4 >/dev/null 2>&1
  DUR=$(ffprobe -v error -show_entries format=duration -of csv=p=0 $OUT/videos/ev$i.mp4)
  RES=$(ffprobe -v error -select_streams v:0 -show_entries stream=width,height,codec_name -of csv=p=0 $OUT/videos/ev$i.mp4)
  AUD=$(ffprobe -v error -select_streams a -show_entries stream=codec_name -of csv=p=0 $OUT/videos/ev$i.mp4)
  info "video $i: ${DUR}s, $RES, audio: ${AUD:-none}"
  ffmpeg -v error -y -ss 1 -i $OUT/videos/ev$i.mp4 -frames:v 1 $OUT/video${i}_frame_1s.png
  ffmpeg -v error -y -sseof -1.5 -i $OUT/videos/ev$i.mp4 -frames:v 1 $OUT/video${i}_frame_end.png
done
rm -rf $OUT/videos
adb shell run-as $PKG cat files/events.json > $OUT/events.json 2>/dev/null
echo "done" >> $SUM
