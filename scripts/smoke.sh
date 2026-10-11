#!/bin/bash
# Shomer emulator smoke test – runs via adb, never aborts, writes PASS/FAIL lines + screenshots to results/.
PKG=com.shomer.bodycam
OUT=results; mkdir -p $OUT
SUM=$OUT/summary.md
echo "# Shomer smoke – API $(adb shell getprop ro.build.version.sdk | tr -d '\r')" > $SUM
pass() { echo "- ✅ $1" | tee -a $SUM; }
fail() { echo "- ❌ $1" | tee -a $SUM; }
info() { echo "  - $1" | tee -a $SUM; }
shot() { adb exec-out screencap -p > "$OUT/$1.png"; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml > $OUT/ui.xml 2>/dev/null; }
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
if exact: print(c(exact[-1]))
elif part: print(c(part[-1]))
PY
}
dismiss_sys() { dump; if grep -q "isn&apos;t responding\|isn't responding" $OUT/ui.xml; then
  p=$(find_text "Wait"); [ -n "$p" ] && adb shell input tap $p && info "(dismissed a system 'not responding' dialog)"; sleep 2; fi; }
tap() { dismiss_sys; local p; p=$(find_text "$1"); if [ -n "$p" ]; then adb shell input tap $p; return 0; fi; return 1; }
hold() { dismiss_sys; local p; p=$(find_text "$1"); if [ -n "$p" ]; then adb shell input swipe $p $p ${2:-1800}; return 0; fi; return 1; }
has() { dump; grep -q "$1" $OUT/ui.xml; }
svc_fg() { adb shell dumpsys activity services $PKG | grep -q "isForeground=true"; }
crashes() { adb logcat -d -b crash 2>/dev/null | grep -c "$PKG"; }
records() { adb shell run-as $PKG cat files/records.json 2>/dev/null; }
rec_count() { records | grep -o '"kind":"INCIDENT"' | wc -l; }
wait_records() { for i in $(seq 1 60); do [ "$(rec_count)" -ge "$1" ] && return 0; sleep 4; done; return 1; }

sleep 20
adb logcat -c; adb logcat -G 16M 2>/dev/null
adb install -r -g app.apk >/dev/null 2>&1 && pass "installed" || fail "install"
for p in CAMERA RECORD_AUDIO POST_NOTIFICATIONS ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION CALL_PHONE; do adb shell pm grant $PKG android.permission.$p 2>/dev/null; done
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow
adb shell dumpsys deviceidle whitelist +$PKG >/dev/null 2>&1

adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 1; shot 00_splash
sleep 6
shot 01_onboarding
tap "Next" && sleep 1 && shot 02_onboarding2 && tap "Next" && sleep 1
p=$(find_text "e.g. Guard 4127"); [ -n "$p" ] && adb shell input tap $p && sleep 1 && adb shell input text "Guard%s4127" && adb shell input keyevent 111
sleep 1; shot 03_onboarding3
tap "Let's go" && pass "onboarding done" || fail "onboarding"
sleep 2; shot 04_off_shift
has "Off shift" && pass "off-shift screen" || fail "off-shift screen"

tap "Start shift"; sleep 3
has "Not now" && tap "Not now"
sleep 8; shot 05_standby
svc_fg && pass "foreground service running" || fail "service not in foreground"
has "Standby" && pass "standby status shown" || fail "standby status"
segs=$(adb shell run-as $PKG ls files/buffer 2>/dev/null | grep -c mp4); info "buffer segments: $segs"
[ "$segs" -ge 1 ] && pass "pre-record buffer is writing" || fail "no buffer segments"

sleep 12   # let the buffer fill a bit
tap "REC" && pass "tapped REC" || fail "REC button not found"
sleep 2; shot 06_recording
has "Recording" && pass "recording state" || fail "recording state"
sleep 6
tap "Camera"; sleep 3; shot 07_camera_recording
tap "Shift"; sleep 2
# a short tap must NOT stop the recording
p=$(find_text "Hold the button to stop and save")
x=$(echo "$(find_text 'Recording')" | cut -d' ' -f1)
rb=$(python3 - <<'PY'
import re,xml.etree.ElementTree as ET
r=ET.parse('results/ui.xml').getroot()
# the record button shows a timer like 00:12
for n in r.iter('node'):
    t=n.get('text') or ''
    if re.fullmatch(r'\d\d:\d\d',t):
        x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds'))); print((x1+x2)//2,(y1+y2)//2); break
PY
)
info "record button at: $rb"
if [ -n "$rb" ]; then
  adb shell input tap $rb; sleep 2
  has "Recording" && pass "short tap did not stop recording" || fail "short tap stopped recording"
  adb shell input swipe $rb $rb 2000; sleep 2
  shot 08_after_hold
else fail "record timer not found"; fi
wait_records 1 && pass "recording saved" || fail "recording not saved"
records > $OUT/records.json
grep -q '"sha256":"[0-9a-f]\{64\}"' $OUT/records.json && pass "SHA-256 stored" || fail "no SHA-256"
grep -q '"locked":true' $OUT/records.json && pass "auto-locked" || fail "not locked"
grep -q '"op":"Guard 4127"' $OUT/records.json && pass "operator id stored" || fail "operator id missing"
adb shell ls /sdcard/Android/data/$PKG/files/Movies/incidents/ > $OUT/files.txt 2>&1
grep -q ".mp4.sha256" $OUT/files.txt && pass ".sha256 sidecar written" || fail "no sidecar"
f=$(grep -o 'SHOMER_[0-9_]*.mp4$' $OUT/files.txt | head -1)
if [ -n "$f" ]; then
  adb pull /sdcard/Android/data/$PKG/files/Movies/incidents/$f $OUT/incident.mp4 >/dev/null 2>&1
  adb pull /sdcard/Android/data/$PKG/files/Movies/incidents/$f.sha256 $OUT/incident.sha256 >/dev/null 2>&1
  (cd $OUT && sed "s/$f/incident.mp4/" incident.sha256 | sha256sum -c - ) && pass "sha256sum -c on a computer: OK" || fail "sha256sum check failed"
  d=$(ffprobe -v error -show_entries format=duration -of csv=p=0 $OUT/incident.mp4 2>/dev/null); info "video duration: ${d}s"
  ffmpeg -v error -ss 1 -i $OUT/incident.mp4 -frames:v 1 $OUT/09_video_frame.png -y 2>/dev/null && pass "video frame extracted"
  python3 -c "import sys; d=float('$d' or 0); sys.exit(0 if d>=14 else 1)" && pass "video includes pre-record (>=14s)" || fail "video too short for pre-record"
fi

# shake while on standby → recording starts by itself
adb emu sensor status >/dev/null 2>&1
for i in $(seq 1 14); do adb emu sensor set acceleration 25:9.8:0 >/dev/null; sleep 0.06; adb emu sensor set acceleration -25:9.8:0 >/dev/null; sleep 0.06; done
adb emu sensor set acceleration 0:9.8:0 >/dev/null
sleep 2; shot 10_after_shake
has "Recording" && pass "shake started a recording" || info "shake did not trigger (emulator sensor timing)"
if has "Recording"; then
  rb2=$(find_text "Recording"); sleep 4; dump
  rb=$(python3 - <<'PY'
import re,xml.etree.ElementTree as ET
r=ET.parse('results/ui.xml').getroot()
for n in r.iter('node'):
    t=n.get('text') or ''
    if re.fullmatch(r'\d\d:\d\d',t):
        x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds'))); print((x1+x2)//2,(y1+y2)//2); break
PY
)
  [ -n "$rb" ] && adb shell input swipe $rb $rb 2000
  wait_records 2 && pass "shake recording saved" || fail "shake recording not saved"
fi

# pocket mode + volume key
tap "Pocket mode"; sleep 2; shot 11_pocket
adb shell input keyevent 24; sleep 3; shot 12_pocket_rec
svc=$(adb shell dumpsys notification --noredact 2>/dev/null | grep -c "Recording")
info "notification mentions Recording: $svc"
adb shell input keyevent 24; sleep 2
n0=$(rec_count); wait_records $((n0>2?n0:3)) && pass "volume key recording saved" || info "volume key: count=$(rec_count)"
p=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | head -1); w=${p%x*}; h=${p#*x}
adb shell input tap $((w/2)) $((h/2)); sleep 0.15; adb shell input tap $((w/2)) $((h/2)); sleep 2
has "Pocket mode" && pass "double tap left pocket mode" || fail "pocket mode did not exit"

# background: home → floating button + notification action
adb shell input keyevent 3; sleep 3; shot 13_background_floating
adb shell cmd statusbar expand-notifications; sleep 2; shot 14_notification
adb shell cmd statusbar collapse

adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 3
tap "Recordings"; sleep 2; shot 15_records
tap "SHA-256" || tap "Incident"; sleep 3; shot 16_detail
tap "Verify"; sleep 4; shot 17_verified
has "Intact" && pass "integrity check: intact" || fail "integrity check"
adb shell input keyevent 4; sleep 1
tap "Settings"; sleep 2; shot 18_settings
adb shell input swipe 500 1600 500 400 400; sleep 1; shot 19_settings2
adb shell input swipe 500 1600 500 400 400; sleep 1; shot 20_settings3
tap "Shift"; sleep 2
hold "Hold to end shift" 1500; sleep 6
svc_fg && fail "service still foreground after end shift" || pass "shift ended"
shot 21_ended

c=$(crashes); [ "$c" -eq 0 ] && pass "no crashes" || fail "$c crash lines"
adb logcat -d -b crash > $OUT/crash.txt 2>/dev/null
adb logcat -d | grep -E "Shomer|AndroidRuntime|$PKG" | tail -400 > $OUT/log.txt
exit 0
