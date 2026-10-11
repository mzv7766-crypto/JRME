#!/bin/bash
# End-to-end: Firebase emulators + this Android emulator (the guard) + headless browser (the control room).
PKG=com.shomer.bodycam
export OUT=$PWD/results; mkdir -p $OUT
SUM=$OUT/summary.md
echo "# Shomer Team end-to-end – API $(adb shell getprop ro.build.version.sdk | tr -d '\r')" > $SUM
pass() { echo "- ✅ $1" | tee -a $SUM; }
fail() { echo "- ❌ $1" | tee -a $SUM; }
info() { echo "  - $1" | tee -a $SUM; }
shot() { adb exec-out screencap -p > "$OUT/$1.png"; }

sleep 20
adb logcat -c; adb logcat -G 16M 2>/dev/null
(cd e2e && node fs.js seed) && pass "company seeded (1 real phone + 2 simulated)" || fail "seed"
adb install -r -g app.apk >/dev/null 2>&1 && pass "installed" || fail "install"
for p in CAMERA RECORD_AUDIO POST_NOTIFICATIONS ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION; do adb shell pm grant $PKG android.permission.$p 2>/dev/null; done
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow
adb shell dumpsys deviceidle whitelist +$PKG >/dev/null 2>&1
adb emu geo fix 34.7818 32.0853 >/dev/null

adb shell am start -n $PKG/.MainActivity --es e2e_email guard@acme.co.il >/dev/null
sleep 12
shot phone_00_signed_in
adb shell am start -n $PKG/.MainActivity --ez e2e_shift true >/dev/null
for i in 1 2 3; do sleep 3; adb emu geo fix 34.7818 32.0853 >/dev/null; done
(cd e2e && node fs.js wait-shift) > $OUT/device.json && pass "phone reports on shift to Firestore" || fail "phone did not report"
info "$(cat $OUT/device.json | head -c 400)"
grep -q '"lat"' $OUT/device.json && pass "location reported" || fail "no location in report"
grep -q '"name":"דנה לוי"' $OUT/device.json && pass "name/badge from company" || info "name not in report"
shot phone_00b_on_shift

(cd e2e && node control.js)

# manager mode on the phone
adb shell am start -n $PKG/.MainActivity --es e2e_email boss@acme.co.il >/dev/null
sleep 10
adb shell am start -n $PKG/.MainActivity --es route control >/dev/null
sleep 6
shot phone_03_manager_control
c=$(adb logcat -d -b crash 2>/dev/null | grep -c "$PKG"); [ "$c" -eq 0 ] && pass "no crashes" || fail "$c crash lines"
adb logcat -d -b crash > $OUT/crash.txt 2>/dev/null
adb logcat -d | grep -E "Shomer|ShomerCloud|ShomerLive|AndroidRuntime|$PKG|webrtc|WebRTC" | tail -500 > $OUT/log.txt
exit 0
