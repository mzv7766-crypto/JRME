# Jeremy smoke test – API 30
  - screen 1080x2400 density 420
- ✅ Install
- ✅ Launch + splash animation, no crash
- ✅ Tapped Start drive
- ✅ Foreground camera service running
  -     isForeground=true foregroundId=1001 foregroundNoti=Notification(channel=jeremy_drive shortcut=null contentView=null vibrate=null sound=null defaults=0x0 flags=0x6a color=0xff22c55e category=service groupKey=silent actions=3 vis=PRIVATE) 
- ❌ Rolling buffer: only 1 segments
- ✅ Navigation through all tabs while recording
- ✅ Pro screen opens
- ✅ On-device AI detector loaded (MediaPipe, shrunk build)
- ✅ AI runs on camera frames without errors
- ✅ Event started and stopped from camera screen
- ✅ Event #1 exported to MP4
- ✅ Discard: event ended without creating a video
- ✅ Recording continues after discard
- ✅ Service stays foreground after leaving app
- ✅ Camera keeps recording in background (seg_1792259204705.mp4 → seg_1792259264839.mp4)
  - bubble tap at 959,1169
- ✅ Floating bubble: event saved while app in background
- ✅ Opened event detail / player
- ✅ Settings sweep: 37 settings tapped, no crash
- ✅ Recording survived the settings sweep
- ✅ Long-press bubble stopped recording
- ✅ Service shut down after finishing pending saves
- ✅ Full-drive recording: 17 trip clips saved separately from events
  - events: 2, trip clips: 17
  - rotation with landscape setting: mRotation=1
- ✅ App locked to landscape: all screens open without crash
- ✅ No crashes in logcat
- ✅ No ANRs
  - app error lines: 0
  - video 1 file: 125 bytes
  - video 1: s, , audio: none
  - video 2 file: 125 bytes
  - video 2: s, , audio: none
  - video 3 file: 125 bytes
  - video 3: s, , audio: none
  - video 4 file: 125 bytes
  - video 4: s, , audio: none
  - video 5 file: 125 bytes
  - video 5: s, , audio: none
  - video 6 file: 125 bytes
  - video 6: s, , audio: none
  - video 7 file: 125 bytes
  - video 7: s, , audio: none
  - video 8 file: 125 bytes
  - video 8: s, , audio: none
  - video 9 file: 125 bytes
  - video 9: s, , audio: none
  - video 10 file: 125 bytes
  - video 10: s, , audio: none
  - video 11 file: 125 bytes
  - video 11: s, , audio: none
  - video 12 file: 125 bytes
  - video 12: s, , audio: none
  - video 13 file: 125 bytes
  - video 13: s, , audio: none
  - video 14 file: 121 bytes
  - video 14: s, , audio: none
  - video 15 file: 125 bytes
  - video 15: s, , audio: none
  - video 16 file: 125 bytes
  - video 16: s, , audio: none
  - video 17 file: 121 bytes
  - video 17: s, , audio: none
  - video 18 file: 125 bytes
  - video 18: s, , audio: none
  - video 19 file: 125 bytes
  - video 19: s, , audio: none
  - segment gaps (ms): 0 491 1063 1852 736 243 275 4412 428 7124 167 673 300 516 588 1039 1520 437 596 1145 1382 526 340 456 638 1137 500 1204 751 440 744 539 399 
    10-17 17:43:49.101 I/Jeremy  ( 3946): trip clip queued: 2 segments, 119133ms final=false
    10-17 17:43:49.451 I/Jeremy  ( 3946): export level 0, 2 pieces
    10-17 17:43:56.969 I/Jeremy  ( 3946): event start trigger=MANUAL
    10-17 17:44:14.073 I/Jeremy  ( 3946): export pieces=2 mediaMs=29670 spanMs=31435
    10-17 17:44:14.153 I/Jeremy  ( 3946): trip export paused for an event
    10-17 17:44:14.328 I/Jeremy  ( 3946): export level 0, 2 pieces
    10-17 17:45:14.895 I/Jeremy  ( 3946): trip clip queued: 2 segments, 82741ms final=false
    10-17 17:45:56.419 I/Jeremy  ( 3946): export level 0 done: 29737ms, 1139781 bytes
    10-17 17:45:56.744 I/Jeremy  ( 3946): export level 0, 2 pieces
    10-17 17:46:02.740 I/Jeremy  ( 3946): event start trigger=MANUAL
    10-17 17:46:42.567 I/Jeremy  ( 3946): event discarded
    10-17 17:46:44.704 I/Jeremy  ( 3946): trip clip queued: 1 segments, 89566ms final=false
    10-17 17:47:56.019 I/Jeremy  ( 3946): event start trigger=FLOATING
    10-17 17:48:20.378 I/Jeremy  ( 3946): trip clip queued: 2 segments, 94922ms final=false
    10-17 17:48:20.378 I/Jeremy  ( 3946): export pieces=2 mediaMs=38617 spanMs=38911
    10-17 17:48:20.498 I/Jeremy  ( 3946): trip export paused for an event
    10-17 17:48:24.242 I/Jeremy  ( 3946): export level 0, 2 pieces
    10-17 17:50:13.452 I/Jeremy  ( 3946): export level 0 done: 38684ms, 2290573 bytes
    10-17 17:50:13.740 W/Jeremy  ( 3946): export: falling back to plain join (no overlays)
    10-17 17:50:15.921 W/Jeremy  ( 3946): export: falling back to plain join (no overlays)
    10-17 17:50:24.890 I/Jeremy  ( 3946): export level 0, 1 pieces
    10-17 17:50:25.839 I/Jeremy  ( 3946): trip clip queued: 2 segments, 113970ms final=false
    10-17 17:52:26.350 I/Jeremy  ( 3946): trip clip queued: 2 segments, 119674ms final=false
    10-17 17:53:59.677 I/Jeremy  ( 3946): export level 0 done: 89566ms, 4630393 bytes
    10-17 17:54:01.731 W/Jeremy  ( 3946): export: falling back to plain join (no overlays)
    10-17 17:54:03.194 I/Jeremy  ( 3946): export level 0, 2 pieces
    10-17 17:54:26.662 I/Jeremy  ( 3946): trip clip queued: 2 segments, 119505ms final=false
    10-17 17:55:38.420 I/Jeremy  ( 3946): trip clip queued: 2 segments, 70559ms final=false
    10-17 17:56:51.976 I/Jeremy  ( 3946): trip clip queued: 2 segments, 71168ms final=false
    10-17 17:58:12.822 I/Jeremy  ( 3946): trip clip queued: 2 segments, 79298ms final=false
    10-17 17:59:22.374 I/Jeremy  ( 3946): trip clip queued: 2 segments, 67471ms final=false
    10-17 18:01:22.992 I/Jeremy  ( 3946): trip clip queued: 2 segments, 119810ms final=false
    10-17 18:03:23.876 I/Jeremy  ( 3946): trip clip queued: 2 segments, 119104ms final=false
    10-17 18:04:55.892 I/Jeremy  ( 3946): export level 0 done: 114010ms, 8225700 bytes
    10-17 18:04:58.368 W/Jeremy  ( 3946): export: falling back to plain join (no overlays)
    10-17 18:04:59.616 W/Jeremy  ( 3946): export: falling back to plain join (no overlays)
    10-17 18:05:01.013 W/Jeremy  ( 3946): export: falling back to plain join (no overlays)
    10-17 18:05:02.603 W/Jeremy  ( 3946): export: falling back to plain join (no overlays)
    10-17 18:05:03.763 W/Jeremy  ( 3946): export: falling back to plain join (no overlays)
    10-17 18:05:06.608 W/Jeremy  ( 3946): export: falling back to plain join (no overlays)
    10-17 18:05:07.756 I/Jeremy  ( 3946): export level 0, 2 pieces
    10-17 18:05:25.106 I/Jeremy  ( 3946): trip clip queued: 2 segments, 119519ms final=false
    10-17 18:07:25.606 I/Jeremy  ( 3946): trip clip queued: 2 segments, 119307ms final=false
    10-17 18:09:26.162 I/Jeremy  ( 3946): trip clip queued: 2 segments, 119272ms final=false
    10-17 18:10:53.601 I/Jeremy  ( 3946): trip clip queued: 2 segments, 86535ms final=false
    10-17 18:11:25.664 I/Jeremy  ( 3946): export level 0 done: 120016ms, 7366472 bytes
    10-17 18:11:27.395 W/Jeremy  ( 3946): export: falling back to plain join (no overlays)
    10-17 18:11:29.373 W/Jeremy  ( 3946): export: falling back to plain join (no overlays)
    10-17 18:11:33.120 W/Jeremy  ( 3946): export: falling back to plain join (no overlays)
    10-17 18:11:35.168 I/Jeremy  ( 3946): export level 0, 2 pieces
    10-17 18:13:24.393 I/Jeremy  ( 3946): export level 0 done: 119413ms, 7478843 bytes
    10-17 18:13:24.477 I/Jeremy  ( 3946): export level 0, 2 pieces
    10-17 18:14:47.066 I/Jeremy  ( 3946): export level 0 done: 86603ms, 5283306 bytes
done
