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
- ❌ AI detector not loaded: 10-20 23:27:22.999  5673  6412 W dex2oat32: Verification of java.lang.String com.google.mediapipe.tasks.components.containers.Landmark.toString() took 104.021ms (721.01 bytecodes/s) (1272B approximate peak alloc)
10-20 23:27:23.248  5673  6412 W dex2oat32: Verification of int com.google.mediapipe.ta
- ✅ AI runs on camera frames without errors
- ✅ Event started and stopped from camera screen
- ✅ Event #1 exported to MP4
- ✅ Discard: event ended without creating a video
- ✅ Recording continues after discard
- ✅ Service stays foreground after leaving app
- ❌ No new segments in background
  - bubble tap at 959,1169
- ✅ Floating bubble: event saved while app in background
- ✅ Opened event detail / player
- ✅ Settings sweep: 39 settings tapped, no crash
- ✅ Recording survived the settings sweep
- ✅ Long-press bubble stopped recording
- ✅ Service shut down after finishing pending saves
- ✅ Full-drive recording: 16 trip clips saved separately from events
  - events: 2, trip clips: 16
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
  - segment gaps (ms): 0 1833 1682 852 924 470 747 506 1572 1522 463 671 875 448 1254 1505 2032 1033 1167 1749 1838 759 982 307 941 661 739 492 1128 809 745 326 
    10-20 23:28:47.282 I/Jeremy  ( 5241): event start trigger=MANUAL
    10-20 23:29:01.691 I/Jeremy  ( 5241): trip clip queued: 2 segments, 126456ms final=false
    10-20 23:29:01.885 I/Jeremy  ( 5241): export level 0, 2 pieces
    10-20 23:29:01.961 I/Jeremy  ( 5241): export pieces=1 mediaMs=28118 spanMs=28105
    10-20 23:29:01.972 I/Jeremy  ( 5241): trip export paused for an event
    10-20 23:29:01.979 I/Jeremy  ( 5241): export level 0, 1 pieces
    10-20 23:30:47.269 I/Jeremy  ( 5241): export level 0 done: 28288ms, 1044199 bytes
    10-20 23:30:47.322 I/Jeremy  ( 5241): export level 0, 2 pieces
    10-20 23:30:53.327 I/Jeremy  ( 5241): event start trigger=MANUAL
    10-20 23:31:28.365 I/Jeremy  ( 5241): event discarded
    10-20 23:31:30.733 I/Jeremy  ( 5241): trip clip queued: 2 segments, 146943ms final=false
    10-20 23:32:47.485 I/Jeremy  ( 5241): event start trigger=FLOATING
    10-20 23:33:11.396 I/Jeremy  ( 5241): trip clip queued: 2 segments, 99539ms final=false
    10-20 23:33:11.397 I/Jeremy  ( 5241): export pieces=1 mediaMs=38270 spanMs=38410
    10-20 23:33:11.461 I/Jeremy  ( 5241): trip export paused for an event
    10-20 23:33:11.469 I/Jeremy  ( 5241): export level 0, 1 pieces
    10-20 23:34:31.099 I/Jeremy  ( 5241): export level 0 done: 38272ms, 1794679 bytes
    10-20 23:34:31.100 W/Jeremy  ( 5241): export: falling back to plain join (no overlays)
    10-20 23:34:33.715 I/Jeremy  ( 5241): export level 0, 2 pieces
    10-20 23:35:13.257 I/Jeremy  ( 5241): trip clip queued: 2 segments, 119707ms final=false
    10-20 23:37:14.229 I/Jeremy  ( 5241): trip clip queued: 2 segments, 118968ms final=false
    10-20 23:39:14.996 I/Jeremy  ( 5241): trip clip queued: 2 segments, 119270ms final=false
    10-20 23:40:21.815 I/Jeremy  ( 5241): export level 0 done: 147094ms, 5871120 bytes
    10-20 23:40:22.427 W/Jeremy  ( 5241): export: falling back to plain join (no overlays)
    10-20 23:40:23.928 W/Jeremy  ( 5241): export: falling back to plain join (no overlays)
    10-20 23:40:25.979 I/Jeremy  ( 5241): export level 0, 2 pieces
    10-20 23:41:02.726 I/Jeremy  ( 5241): trip clip queued: 2 segments, 106401ms final=false
    10-20 23:42:16.497 I/Jeremy  ( 5241): trip clip queued: 2 segments, 69823ms final=false
    10-20 23:43:45.440 I/Jeremy  ( 5241): trip clip queued: 2 segments, 87023ms final=false
    10-20 23:44:57.902 I/Jeremy  ( 5241): trip clip queued: 2 segments, 68595ms final=false
    10-20 23:46:24.523 I/Jeremy  ( 5241): export level 0 done: 119111ms, 5319902 bytes
    10-20 23:46:24.764 W/Jeremy  ( 5241): export: falling back to plain join (no overlays)
    10-20 23:46:25.814 W/Jeremy  ( 5241): export: falling back to plain join (no overlays)
    10-20 23:46:27.253 W/Jeremy  ( 5241): export: falling back to plain join (no overlays)
    10-20 23:46:29.010 I/Jeremy  ( 5241): export level 0, 2 pieces
    10-20 23:46:58.727 I/Jeremy  ( 5241): trip clip queued: 2 segments, 119073ms final=false
    10-20 23:48:59.645 I/Jeremy  ( 5241): trip clip queued: 2 segments, 119761ms final=false
    10-20 23:51:00.710 I/Jeremy  ( 5241): trip clip queued: 2 segments, 119593ms final=false
    10-20 23:51:59.270 I/Jeremy  ( 5241): export level 0 done: 87117ms, 4443373 bytes
    10-20 23:51:59.531 W/Jeremy  ( 5241): export: falling back to plain join (no overlays)
    10-20 23:52:01.447 W/Jeremy  ( 5241): export: falling back to plain join (no overlays)
    10-20 23:52:03.094 I/Jeremy  ( 5241): export level 0, 2 pieces
    10-20 23:53:01.579 I/Jeremy  ( 5241): trip clip queued: 2 segments, 119346ms final=false
    10-20 23:55:02.581 I/Jeremy  ( 5241): trip clip queued: 2 segments, 119355ms final=false
    10-20 23:56:23.327 I/Jeremy  ( 5241): trip clip queued: 2 segments, 79917ms final=false
    10-20 23:57:00.342 I/Jeremy  ( 5241): export level 0 done: 119936ms, 5773315 bytes
    10-20 23:57:00.395 W/Jeremy  ( 5241): export: falling back to plain join (no overlays)
    10-20 23:57:00.631 W/Jeremy  ( 5241): export: falling back to plain join (no overlays)
    10-20 23:57:00.884 I/Jeremy  ( 5241): export level 0, 2 pieces
    10-20 23:58:30.026 I/Jeremy  ( 5241): export level 0 done: 119424ms, 6175836 bytes
    10-20 23:58:30.089 I/Jeremy  ( 5241): export level 0, 2 pieces
    10-20 23:59:36.381 I/Jeremy  ( 5241): export level 0 done: 79951ms, 4067842 bytes
done
