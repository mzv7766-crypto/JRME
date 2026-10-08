# Jeremy smoke test – API 34
  - screen 1080x2400 density 420
- ✅ Install
- ✅ Launch + splash animation, no crash
  - (dismissed a system 'not responding' dialog)
  - start tap not delivered (attempt 1), retrying
  - (dismissed a system 'not responding' dialog)
  - start tap not delivered (attempt 2), retrying
- ✅ Tapped Start drive
- ✅ Foreground camera service running
  -     isForeground=true foregroundId=1001 types=000000C0 foregroundNoti=Notification(channel=jeremy_drive shortcut=null contentView=null vibrate=null sound=null defaults=0x0 flags=0x6a color=0xff22c55e category=service groupKey=silent actions=3 vis=PRIVATE) 
- ✅ Rolling buffer writing segments (2 files after ~20s)
- ✅ Navigation through all tabs while recording
- ✅ Event started and stopped from camera screen
- ✅ Event #1 exported to MP4
- ✅ Discard: event ended without creating a video
- ✅ Recording continues after discard
- ✅ Service stays foreground after leaving app
- ✅ Camera keeps recording in background (seg_1791502271192.mp4 → seg_1791502281672.mp4)
  - bubble tap at 959,1169
- ✅ Floating bubble: event saved while app in background
- ✅ Opened event detail / player
- ✅ Long-press bubble stopped recording
- ✅ Service shut down after finishing pending saves
- ✅ Full-drive recording: 4 trip clips saved separately from events
  - events: 2, trip clips: 4
  - rotation with landscape setting: mRotation=0
- ✅ App locked to landscape: all screens open without crash
- ✅ No crashes in logcat
- ✅ No ANRs
  - app error lines: 0
  - video 1 file: 1887077 bytes
  - video 1: 51.584000s, h264,1280,720,, audio: aac
  - video 2 file: 1131913 bytes
  - video 2: 24.412256s, h264,1280,720,, audio: aac
  - video 3 file: 2285967 bytes
  - video 3: 59.201389s, h264,1280,720,, audio: aac
  - video 4 file: 2712989 bytes
  - video 4: 63.412800s, h264,1280,720,, audio: aac
  - video 5 file: 662679 bytes
  - video 5: 20.480000s, h264,1280,720,, audio: aac
  - video 6 file: 2424971 bytes
  - video 6: 57.344000s, h264,1280,720,, audio: aac
  - segment gaps (ms): 0 746 658 1280 1774 1879 2188 1472 978 616 3401 1951 1064 986 650 1336 398 427 2543 4022 825 
    10-08 23:29:17.526 I/Jeremy  ( 4917): event start trigger=MANUAL
    10-08 23:29:27.397 I/Jeremy  ( 4917): trip clip queued: 6 segments, 56775ms final=false
    10-08 23:29:27.782 I/Jeremy  ( 4917): export level 0, 6 pieces
    10-08 23:29:28.176 I/Jeremy  ( 4917): export pieces=3 mediaMs=20169 spanMs=24146
    10-08 23:29:28.208 I/Jeremy  ( 4917): trip export paused for an event
    10-08 23:29:28.220 I/Jeremy  ( 4917): export level 0, 3 pieces
    10-08 23:30:06.717 I/Jeremy  ( 4917): export level 0 done: 20363ms, 662679 bytes
    10-08 23:30:06.785 I/Jeremy  ( 4917): export level 0, 6 pieces
    10-08 23:30:07.954 I/Jeremy  ( 4917): event start trigger=MANUAL
    10-08 23:30:32.227 I/Jeremy  ( 4917): event discarded
    10-08 23:30:37.754 I/Jeremy  ( 4917): trip clip queued: 4 segments, 62821ms final=false
    10-08 23:31:28.984 I/Jeremy  ( 4917): export level 0 done: 57220ms, 2424971 bytes
    10-08 23:31:29.071 I/Jeremy  ( 4917): export level 0, 4 pieces
    10-08 23:31:31.277 I/Jeremy  ( 4917): event start trigger=FLOATING
    10-08 23:31:42.511 I/Jeremy  ( 4917): trip clip queued: 5 segments, 58844ms final=false
    10-08 23:31:42.511 I/Jeremy  ( 4917): export pieces=2 mediaMs=24332 spanMs=25003
    10-08 23:31:42.561 I/Jeremy  ( 4917): trip export paused for an event
    10-08 23:31:42.579 I/Jeremy  ( 4917): export level 0, 2 pieces
    10-08 23:32:04.319 I/Jeremy  ( 4917): export level 0 done: 24377ms, 1131913 bytes
    10-08 23:32:04.332 I/Jeremy  ( 4917): export level 0, 4 pieces
    10-08 23:32:42.245 I/Jeremy  ( 4917): trip clip queued: 6 segments, 50959ms final=true
    10-08 23:32:48.146 I/Jeremy  ( 4917): export level 0 done: 63376ms, 2712989 bytes
    10-08 23:32:48.227 I/Jeremy  ( 4917): export level 0, 5 pieces
    10-08 23:33:18.362 I/Jeremy  ( 4917): export level 0 done: 59166ms, 2285967 bytes
    10-08 23:33:18.405 I/Jeremy  ( 4917): export level 0, 6 pieces
    10-08 23:33:47.781 I/Jeremy  ( 4917): export level 0 done: 51540ms, 1887077 bytes
done
