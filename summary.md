# Jeremy smoke test – API 30
  - screen 1080x2400 density 420
- ✅ Install
- ✅ Launch + splash animation, no crash
- ✅ Tapped Start drive
- ✅ Foreground camera service running
  -     isForeground=true foregroundId=1001 foregroundNoti=Notification(channel=jeremy_drive shortcut=null contentView=null vibrate=null sound=null defaults=0x0 flags=0x6a color=0xff22c55e category=service groupKey=silent actions=3 vis=PRIVATE) 
- ✅ Rolling buffer writing segments (3 files after ~20s)
- ✅ Navigation through all tabs while recording
- ✅ Event started and stopped from camera screen
- ✅ Event #1 exported to MP4
- ✅ Discard: event ended without creating a video
- ✅ Recording continues after discard
- ✅ Service stays foreground after leaving app
- ✅ Camera keeps recording in background (seg_1791502350002.mp4 → seg_1791502360391.mp4)
  - bubble tap at 959,1169
- ✅ Floating bubble: event saved while app in background
- ✅ Opened event detail / player
- ✅ Long-press bubble stopped recording
- ✅ Service shut down after finishing pending saves
- ✅ Full-drive recording: 7 trip clips saved separately from events
  - events: 2, trip clips: 7
  - rotation with landscape setting: mRotation=1
- ✅ App locked to landscape: all screens open without crash
- ✅ No crashes in logcat
- ✅ No ANRs
  - app error lines: 0
  - video 1 file: 125 bytes
  - video 1: s, , audio: none
  - video 2 file: 125 bytes
  - video 2: s, , audio: none
  - video 3 file: 121 bytes
  - video 3: s, , audio: none
  - video 4 file: 125 bytes
  - video 4: s, , audio: none
  - video 5 file: 125 bytes
  - video 5: s, , audio: none
  - video 6 file: 125 bytes
  - video 6: s, , audio: none
  - video 7 file: 121 bytes
  - video 7: s, , audio: none
  - video 8 file: 125 bytes
  - video 8: s, , audio: none
  - video 9 file: 125 bytes
  - video 9: s, , audio: none
  - segment gaps (ms): 0 519 501 827 1834 1226 614 594 2483 827 1014 1041 1242 473 565 2032 313 504 1268 2168 349 455 855 425 478 479 446 410 437 686 913 599 449 1343 805 938 1631 615 
    10-08 23:29:26.284 I/Jeremy  ( 5064): trip clip queued: 6 segments, 57389ms final=false
    10-08 23:29:26.357 I/Jeremy  ( 5064): export level 0, 6 pieces
    10-08 23:29:42.533 I/Jeremy  ( 5064): event start trigger=MANUAL
    10-08 23:29:58.917 I/Jeremy  ( 5064): export pieces=2 mediaMs=28548 spanMs=30127
    10-08 23:29:59.072 I/Jeremy  ( 5064): trip export paused for an event
    10-08 23:29:59.082 I/Jeremy  ( 5064): export level 0, 2 pieces
    10-08 23:30:30.661 I/Jeremy  ( 5064): trip clip queued: 5 segments, 58247ms final=false
    10-08 23:31:00.986 I/Jeremy  ( 5064): export level 0 done: 29056ms, 741802 bytes
    10-08 23:31:00.994 I/Jeremy  ( 5064): export level 0, 6 pieces
    10-08 23:31:03.413 I/Jeremy  ( 5064): event start trigger=MANUAL
    10-08 23:31:34.252 I/Jeremy  ( 5064): event discarded
    10-08 23:31:36.638 I/Jeremy  ( 5064): trip clip queued: 4 segments, 61665ms final=false
    10-08 23:32:40.391 I/Jeremy  ( 5064): trip clip queued: 6 segments, 58705ms final=false
    10-08 23:32:43.744 I/Jeremy  ( 5064): event start trigger=FLOATING
    10-08 23:33:08.902 I/Jeremy  ( 5064): export pieces=3 mediaMs=38636 spanMs=39728
    10-08 23:33:08.938 I/Jeremy  ( 5064): trip export paused for an event
    10-08 23:33:08.971 I/Jeremy  ( 5064): export level 0, 3 pieces
    10-08 23:33:50.411 I/Jeremy  ( 5064): trip clip queued: 5 segments, 67354ms final=false
    10-08 23:34:52.975 I/Jeremy  ( 5064): trip clip queued: 6 segments, 59044ms final=false
    10-08 23:34:58.466 I/Jeremy  ( 5064): export level 0 done: 38784ms, 1568932 bytes
    10-08 23:34:58.467 W/Jeremy  ( 5064): export: falling back to plain join (no overlays)
    10-08 23:35:00.376 W/Jeremy  ( 5064): export: falling back to plain join (no overlays)
    10-08 23:35:01.107 W/Jeremy  ( 5064): export: falling back to plain join (no overlays)
    10-08 23:35:02.611 W/Jeremy  ( 5064): export: falling back to plain join (no overlays)
    10-08 23:35:05.059 I/Jeremy  ( 5064): export level 0, 5 pieces
    10-08 23:35:55.028 I/Jeremy  ( 5064): trip clip queued: 6 segments, 56122ms final=false
    10-08 23:37:26.298 I/Jeremy  ( 5064): export level 0 done: 67778ms, 3906831 bytes
    10-08 23:37:26.364 I/Jeremy  ( 5064): export level 0, 6 pieces
    10-08 23:39:22.572 I/Jeremy  ( 5064): export level 0 done: 59518ms, 2726624 bytes
    10-08 23:39:22.647 I/Jeremy  ( 5064): export level 0, 6 pieces
    10-08 23:40:58.102 I/Jeremy  ( 5064): export level 0 done: 57216ms, 1710263 bytes
done
