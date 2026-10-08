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
nodes=list(root.iter('node'))
def c(n):
    x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds'))); return f"{(x1+x2)//2} {(y1+y2)//2}"
exact=[n for n in nodes if (n.get('text') or '')==q or (n.get('content-desc') or '')==q]
part=[n for n in nodes if q in (n.get('text') or '')]
if exact: print(c(exact[0]))
elif part: print(c(part[0]))
PY
}
# dismiss system "X isn't responding" dialogs from the slow emulator (not our app)
dismiss_sys() { dump; if grep -q "isn&apos;t responding\|isn't responding" $OUT/ui.xml; then
    local w; w=$(python3 - <<'PY'
import re,xml.etree.ElementTree as ET
r=ET.parse('results/ui.xml').getroot()
for n in r.iter('node'):
    if (n.get('text') or '')=='Wait':
        x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds'))); print((x1+x2)//2,(y1+y2)//2); break
PY
); [ -n "$w" ] && adb shell input tap $w && echo "  - (dismissed a system 'not responding' dialog)" >> $SUM; sleep 2; fi; }
tap_text() { dismiss_sys; local p; p=$(find_text "$1"); if [ -n "$p" ]; then adb shell input tap $p; echo "$p"; return 0; fi; return 1; }
svc_fg() { adb shell dumpsys activity services $PKG | grep -q "isForeground=true"; }
crashes() { adb logcat -d -b crash 2>/dev/null | grep -c "$PKG"; }
seg_count() { adb shell run-as $PKG ls files/buffer 2>/dev/null | grep -c mp4; }
seg_newest() { adb shell run-as $PKG ls files/buffer 2>/dev/null | grep mp4 | sort | tail -1 | tr -d '\r'; }
EVDIR=/sdcard/Android/data/$PKG/files/Movies/events
ev_count() { adb shell run-as $PKG cat files/events.json 2>/dev/null | grep -o '"id"' | wc -l; }
wait_events() { for i in $(seq 1 60); do [ "$(ev_count)" -ge "$1" ] && return 0; sleep 4; done; return 1; }
saving_done() { for i in $(seq 1 60); do svc_fg || return 0; sleep 4; done; return 1; }

sleep 25   # let the emulator's launcher finish starting
adb logcat -c
adb logcat -G 16M 2>/dev/null
read W H <<< "$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | tr 'x' ' ')"
D=$(adb shell wm density | grep -oE '[0-9]+' | tail -1); DP=$(python3 -c "print($D/160)")
info "screen ${W}x${H} density $D"

# 1. install + permissions
adb install -r -g app-debug.apk > $OUT/install.txt 2>&1 && pass "Install" || { fail "Install: $(cat $OUT/install.txt)"; exit 0; }
APPUID=$(adb shell dumpsys package $PKG | grep -oE "userId=[0-9]+" | head -1 | cut -d= -f2)
adb logcat -P "$APPUID" 2>/dev/null   # don't let 'chatty' drop our app's log lines
adb logcat -v time -s Jeremy:V > $OUT/jeremy_log.txt 2>/dev/null &
LOGPID=$!
adb shell pm grant $PKG android.permission.CAMERA
adb shell pm grant $PKG android.permission.RECORD_AUDIO
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow
adb shell settings put system accelerometer_rotation 0
set_prefs() {  # $1 = extra xml lines
  adb shell am force-stop $PKG
  adb shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cat > shared_prefs/jeremy_settings.xml'" <<XML
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="trip" value="true" />
    <int name="tripMin" value="1" />
    <boolean name="overlayPrompt" value="true" />
    $1
</map>
XML
}
set_prefs ""

# 2. launch + splash
adb shell am start -W -n $PKG/.MainActivity > $OUT/launch.txt
sleep 0.3; shot 01_splash_a
sleep 1.2; shot 01_splash_b
sleep 5; shot 02_home_idle
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
wait_events 1 && pass "Event #1 exported to MP4" || fail "Event #1 not exported within 4 min"

# 6. discard
adb shell input tap $BTN; sleep 4
tap_text "Home" >/dev/null; sleep 2; shot 07_home_event_active
if tap_text "End without saving" >/dev/null; then sleep 6
  sleep 20; [ "$(ev_count)" -eq 1 ] && pass "Discard: event ended without creating a video" || fail "Discard created a video"
else fail "Discard button not found on home"; fi
svc_fg && pass "Recording continues after discard" || fail "Service stopped after discard"

# 7. landscape camera
tap_text "Camera" >/dev/null; sleep 2
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
wait_events 2 && pass "Floating bubble: event saved while app in background" || fail "Bubble event not saved (overlay: $(head -c 200 $OUT/overlay.txt))"
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
N1=$(seg_count); sleep 12; N2=$(seg_count)
[ "$N2" = "0" ] || [ "$N1" = "$N2" ] && pass "Long-press bubble stopped recording" || fail "Recording continued after long-press ($N1 → $N2 segments)"
saving_done && pass "Service shut down after finishing pending saves" || fail "Service still running 4 min after long-press"

# 10b. full-drive clips (1-minute clips during this ~5 minute drive, last one flushed on stop)
T=$(adb shell run-as $PKG cat files/events.json 2>/dev/null | grep -o '"kind":"TRIP"' | wc -l)
[ "$T" -ge 3 ] && pass "Full-drive recording: $T trip clips saved separately from events" || fail "Full-drive recording: only $T trip clips"
E=$(adb shell run-as $PKG cat files/events.json 2>/dev/null | grep -o '"kind":"EVENT"' | wc -l)
info "events: $E, trip clips: $T"
adb shell run-as $PKG ls -l /sdcard/Android/data/$PKG/files/Movies/trips > $OUT/trips_ls.txt 2>&1
adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 4
tap_text "Events" >/dev/null; sleep 2; tap_text "Full drive" >/dev/null; sleep 3; shot 15_full_drive_tab

# 10c. whole app locked to landscape
set_prefs '<string name="orient">LANDSCAPE</string>'
adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 9; shot 16_landscape_home
R=$(adb shell dumpsys window | grep -oE "mCurrentRotation=ROTATION_[0-9]+|mRotation=[0-9]" | head -1)
info "rotation with landscape setting: $R"
tap_text "Events" >/dev/null; sleep 3; shot 17_landscape_events
tap_text "Settings" >/dev/null; sleep 3; shot 18_landscape_settings
[ "$(crashes)" = "0" ] && pass "App locked to landscape: all screens open without crash" || fail "Crash in landscape mode"

# 11. crash / error scan
adb logcat -d > $OUT/logcat.txt
adb logcat -d -b crash > $OUT/crash.txt 2>/dev/null
C=$(grep -c "$PKG" $OUT/crash.txt); A=$(grep -c "ANR in $PKG" $OUT/logcat.txt)
[ "$C" = "0" ] && pass "No crashes in logcat" || fail "$C crash lines (see crash.txt)"
[ "$A" = "0" ] && pass "No ANRs" || fail "$A ANR(s)"
grep -E "DashcamService|EventExporter|Camera2CameraImpl.*ERROR|Recorder.*error" $OUT/logcat.txt | grep -iE " E |error|fail" | head -40 > $OUT/app_errors.txt
info "app error lines: $(wc -l < $OUT/app_errors.txt)"

# 12. videos
adb shell run-as $PKG cat files/events.json > $OUT/events.json 2>/dev/null
mkdir -p $OUT/videos; i=0
for f in $(python3 -c "import json;[print(e['path']) for e in json.load(open('$OUT/events.json'))]" 2>/dev/null); do
  i=$((i+1)); adb pull "$f" $OUT/videos/ev$i.mp4 >/dev/null 2>&1
  [ -s $OUT/videos/ev$i.mp4 ] || adb exec-out run-as $PKG sh -c "cat '$f'" > $OUT/videos/ev$i.mp4
  info "video $i file: $(stat -c %s $OUT/videos/ev$i.mp4) bytes"
  DUR=$(ffprobe -v error -show_entries format=duration -of csv=p=0 $OUT/videos/ev$i.mp4)
  RES=$(ffprobe -v error -select_streams v:0 -show_entries stream=width,height,codec_name -of csv=p=0 $OUT/videos/ev$i.mp4)
  AUD=$(ffprobe -v error -select_streams a -show_entries stream=codec_name -of csv=p=0 $OUT/videos/ev$i.mp4)
  info "video $i: ${DUR}s, $RES, audio: ${AUD:-none}"
  ffmpeg -v error -y -ss 1 -i $OUT/videos/ev$i.mp4 -frames:v 1 $OUT/video${i}_frame_1s.png
  ffmpeg -v error -y -sseof -1.5 -i $OUT/videos/ev$i.mp4 -frames:v 1 $OUT/video${i}_frame_end.png
done
rm -rf $OUT/videos
kill $LOGPID 2>/dev/null
grep -oE "gapBefore=[0-9-]+" $OUT/jeremy_log.txt | cut -d= -f2 > $OUT/gaps.txt
info "segment gaps (ms): $(tr '\n' ' ' < $OUT/gaps.txt)"
grep -E "export|event |trip" $OUT/jeremy_log.txt | sed 's/^/    /' | tee -a $SUM
echo "done" >> $SUM
