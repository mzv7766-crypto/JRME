#!/usr/bin/env python3
"""Settings sweep for the emulator smoke test.

Scrolls through the Settings tab and taps every row once:
  - switches are tapped twice (on/off back to the original value),
  - rows that open a dialog / another screen are opened and closed with BACK,
  - rows that would change the app language / layout direction / orientation are skipped
    (they are tested elsewhere and would break the text-based navigation).
After every tap it checks that the app is still in front and that nothing crashed,
saves a screenshot, and writes a report to results/settings_sweep.md.
"""
import re
import subprocess
import time
import xml.etree.ElementTree as ET

PKG = "com.jeremy.dashcam"
OUT = "results"
SKIP = ("Language", "שפה", "Screen orientation", "כיוון מסך", "Layout", "כיוון תצוגה", "Auto by language",
        "Settings", "Home", "Events", "Camera", "Guide", "הגדרות", "ראשי", "אירועים", "מצלמה", "הדרכה")


def sh(*a, timeout=30):
    try:
        return subprocess.run(["adb", *a], capture_output=True, text=True, timeout=timeout).stdout
    except Exception:
        return ""


def dump():
    sh("shell", "uiautomator", "dump", "/sdcard/sweep.xml")
    xml = sh("shell", "cat", "/sdcard/sweep.xml")
    try:
        return ET.fromstring(xml[xml.index("<?xml"):] if "<?xml" in xml else xml)
    except Exception:
        return None


def bounds(n):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
    return x1, y1, x2, y2


def label_of(n):
    texts = [c.get("text") for c in n.iter("node") if c.get("text")]
    return " | ".join(texts)[:80]


def foreground_ok():
    out = sh("shell", "dumpsys", "activity", "activities")
    m = re.search(r"mResumedActivity.*?\{[^}]*\s([\w.]+)/", out) or re.search(r"topResumedActivity.*?\{[^}]*\s([\w.]+)/", out)
    return bool(m and m.group(1) == PKG)


def crashes():
    return sh("logcat", "-d", "-b", "crash").count(PKG)


def system_dialog_open(root):
    """Permission dialogs and other apps' windows (Play, Google sign-in) – close with BACK."""
    if root is None:
        return False
    pkgs = {n.get("package") for n in root.iter("node")}
    return any(p and p != PKG for p in pkgs)


def main():
    W, H = map(int, re.findall(r"(\d+)x(\d+)", sh("shell", "wm", "size"))[-1])
    nav_top = int(H * 0.88)          # bottom navigation bar starts about here
    report = ["# Settings sweep", ""]
    seen = set()
    shots = 0
    base_crash = crashes()
    problems = 0
    for page in range(14):
        root = dump()
        if root is None:
            break
        rows = []
        for n in root.iter("node"):
            if n.get("clickable") != "true" or n.get("package") != PKG:
                continue
            x1, y1, x2, y2 = bounds(n)
            if y1 < int(H * 0.08) or y2 > nav_top or (x2 - x1) < W * 0.5:
                continue
            lab = label_of(n)
            if not lab or lab in seen or any(s in lab for s in SKIP):
                continue
            is_switch = any(c.get("class", "").endswith("Switch") or c.get("checkable") == "true" for c in n.iter("node"))
            rows.append((lab, (x1 + x2) // 2, (y1 + y2) // 2, is_switch))
        if not rows and page > 0:
            # nothing new on this screen – scroll; stop when the screen no longer changes
            pass
        for lab, x, y, is_switch in rows:
            seen.add(lab)
            sh("shell", "input", "tap", str(x), str(y))
            time.sleep(1.6)
            r = dump()
            shots += 1
            sh("exec-out", "screencap", "-p")  # warm up
            png = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True).stdout
            open(f"{OUT}/sweep_{shots:02d}.png", "wb").write(png)
            what = "switch" if is_switch else "row"
            if system_dialog_open(r):
                what += " → system/other app (closed)"
                sh("shell", "input", "keyevent", "KEYCODE_BACK"); time.sleep(1.2)
            elif is_switch:
                sh("shell", "input", "tap", str(x), str(y)); time.sleep(1.2)   # back to the original value
                r2 = dump()
                t2 = {c.get("text") for c in (r2.iter("node") if r2 is not None else [])}
                if system_dialog_open(r2) or t2 & {"Cancel", "ביטול", "Close", "סגור"}:
                    sh("shell", "input", "keyevent", "KEYCODE_BACK"); time.sleep(1.2)
            else:
                # a dialog or another screen opened → close it
                texts = {c.get("text") for c in (r.iter("node") if r is not None else [])}
                if texts & {"Cancel", "ביטול", "Close", "סגור", "OK"} or "Jeremy Pro" in texts:
                    what += " → dialog/screen (closed)"
                sh("shell", "input", "keyevent", "KEYCODE_BACK"); time.sleep(1.2)
            r3 = dump()
            if system_dialog_open(r3):
                sh("shell", "input", "keyevent", "KEYCODE_BACK"); time.sleep(1.0)
            ok_fg = foreground_ok()
            c = crashes() - base_crash
            status = "✅" if ok_fg and c == 0 else "❌"
            if status == "❌":
                problems += 1
                # get back into the app and on the settings tab
                sh("shell", "am", "start", "-n", f"{PKG}/.MainActivity"); time.sleep(3)
            report.append(f"- {status} [{shots:02d}] {what}: {lab}" + ("" if ok_fg else " (app left the foreground)") + (f" (+{c} crash lines)" if c else ""))
            # make sure we are still on the settings screen (a row may have opened the Pro screen)
            r4 = dump()
            if r4 is not None and not any((n.get("text") or "") in ("Settings", "הגדרות") for n in r4.iter("node")):
                sh("shell", "input", "keyevent", "KEYCODE_BACK"); time.sleep(1.2)
        sh("shell", "input", "swipe", str(W // 2), str(int(H * 0.75)), str(W // 2), str(int(H * 0.35)), "500")
        time.sleep(1.2)
    report.insert(1, f"rows tapped: {len(seen)}, problems: {problems}")
    open(f"{OUT}/settings_sweep.md", "w").write("\n".join(report) + "\n")
    print(f"SWEEP rows={len(seen)} problems={problems}")


if __name__ == "__main__":
    main()
