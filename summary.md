# Jeremy smoke test – API 34
  - screen 1080x2400 density 420
- ✅ Install
- ✅ Launch + splash animation, no crash
- ✅ Tapped Start drive
- ✅ Foreground camera service running
  -     isForeground=true foregroundId=1001 types=000000C8 foregroundNoti=Notification(channel=jeremy_drive shortcut=null contentView=null vibrate=null sound=null defaults=0x0 flags=0x6a color=0xff22c55e category=service groupKey=silent actions=3 vis=PRIVATE) 
- ❌ Rolling buffer: only 1 segments
- ✅ Navigation through all tabs while recording
- ✅ Pro screen opens
- ❌ AI detector not loaded: 
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
- ✅ Settings sweep: 36 settings tapped, no crash
- ✅ Recording survived the settings sweep
- ✅ Long-press bubble stopped recording
- ✅ Service shut down after finishing pending saves
- ✅ Full-drive recording: 10 trip clips saved separately from events
  - events: 2, trip clips: 10
  - rotation with landscape setting: mRotation=1
- ✅ App locked to landscape: all screens open without crash
- ✅ No crashes in logcat
- ✅ No ANRs
  - app error lines: 0
  - video 1 file: 1990937 bytes
  - video 1: 38.400000s, h264,1280,720,, audio: aac
  - video 2 file: 5789302 bytes
  - video 2: 120.004600s, h264,1280,720,, audio: aac
  - video 3 file: 6337654 bytes
  - video 3: 119.552000s, h264,1280,720,, audio: aac
  - video 4 file: 6345448 bytes
  - video 4: 119.111367s, h264,1280,720,, audio: aac
  - video 5 file: 4461599 bytes
  - video 5: 81.025889s, h264,1280,720,, audio: aac
  - video 6 file: 4569097 bytes
  - video 6: 84.753500s, h264,1280,720,, audio: aac
  - video 7 file: 5981387 bytes
  - video 7: 119.296000s, h264,1280,720,, audio: aac
  - video 8 file: 1391172 bytes
  - video 8: 25.088000s, h264,1280,720,, audio: aac
  - video 9 file: 3483395 bytes
  - video 9: 65.536000s, h264,1280,720,, audio: aac
  - video 10 file: 3699908 bytes
  - video 10: 77.184000s, h264,1280,720,, audio: aac
  - video 11 file: 1167160 bytes
  - video 11: 24.320000s, h264,1280,720,, audio: aac
  - video 12 file: 1706518 bytes
  - video 12: 92.365367s, h264,720,1280, audio: none
  - segment gaps (ms): 0 185 1487 1581 1509 1627 2291 2619 4284 1496 2749 3084 740 2967 2653 1424 2624 1450 2136 
    10-20 23:26:39.601 I/Jeremy  ( 4947): event start trigger=MANUAL
    10-20 23:26:49.966 I/Jeremy  ( 4947): trip clip queued: 2 segments, 92294ms final=false
    10-20 23:26:50.162 I/Jeremy  ( 4947): export level 0, 2 pieces
    10-20 23:26:50.246 I/Jeremy  ( 4947): export pieces=1 mediaMs=24256 spanMs=24405
    10-20 23:26:50.258 I/Jeremy  ( 4947): trip export paused for an event
    10-20 23:26:50.272 I/Jeremy  ( 4947): export level 0, 1 pieces
    10-20 23:27:35.099 I/Jeremy  ( 4947): export level 0 done: 24256ms, 1167160 bytes
    10-20 23:27:35.178 I/Jeremy  ( 4947): export level 0, 2 pieces
    10-20 23:27:38.506 I/Jeremy  ( 4947): event start trigger=MANUAL
    10-20 23:28:05.604 I/Jeremy  ( 4947): event discarded
    10-20 23:28:08.884 I/Jeremy  ( 4947): trip clip queued: 1 segments, 77043ms final=false
    10-20 23:29:04.461 I/Jeremy  ( 4947): event start trigger=FLOATING
    10-20 23:29:15.774 I/Jeremy  ( 4947): trip clip queued: 1 segments, 65451ms final=false
    10-20 23:29:15.774 I/Jeremy  ( 4947): export pieces=1 mediaMs=24945 spanMs=25368
    10-20 23:29:15.856 I/Jeremy  ( 4947): trip export paused for an event
    10-20 23:29:15.871 I/Jeremy  ( 4947): export level 0, 1 pieces
    10-20 23:29:38.522 I/Jeremy  ( 4947): export level 0 done: 24960ms, 1391172 bytes
    10-20 23:29:38.523 W/Jeremy  ( 4947): export: falling back to plain join (no overlays)
    10-20 23:29:39.086 I/Jeremy  ( 4947): export level 0, 1 pieces
    10-20 23:30:48.362 I/Jeremy  ( 4947): export level 0 done: 77056ms, 3699908 bytes
    10-20 23:30:48.907 I/Jeremy  ( 4947): export level 0, 1 pieces
    10-20 23:31:18.799 I/Jeremy  ( 4947): trip clip queued: 2 segments, 119036ms final=false
    10-20 23:32:18.869 I/Jeremy  ( 4947): export level 0 done: 65450ms, 3483395 bytes
    10-20 23:32:19.051 I/Jeremy  ( 4947): export level 0, 2 pieces
    10-20 23:32:51.643 I/Jeremy  ( 4947): trip clip queued: 3 segments, 84436ms final=false
    10-20 23:34:17.212 I/Jeremy  ( 4947): export level 0 done: 119176ms, 5981387 bytes
    10-20 23:34:17.388 I/Jeremy  ( 4947): export level 0, 3 pieces
    10-20 23:34:18.875 I/Jeremy  ( 4947): trip clip queued: 3 segments, 80685ms final=false
    10-20 23:35:40.114 I/Jeremy  ( 4947): export level 0 done: 84651ms, 4569097 bytes
    10-20 23:35:40.312 I/Jeremy  ( 4947): export level 0, 3 pieces
    10-20 23:36:23.536 I/Jeremy  ( 4947): trip clip queued: 2 segments, 119015ms final=false
    10-20 23:37:06.841 I/Jeremy  ( 4947): export level 0 done: 80896ms, 4461599 bytes
    10-20 23:37:06.986 I/Jeremy  ( 4947): export level 0, 2 pieces
    10-20 23:38:26.843 I/Jeremy  ( 4947): trip clip queued: 2 segments, 119252ms final=false
    10-20 23:38:59.089 I/Jeremy  ( 4947): export level 0 done: 119077ms, 6345448 bytes
    10-20 23:38:59.195 I/Jeremy  ( 4947): export level 0, 2 pieces
    10-20 23:40:30.027 I/Jeremy  ( 4947): trip clip queued: 2 segments, 119590ms final=false
    10-20 23:41:06.872 I/Jeremy  ( 4947): export level 0 done: 119424ms, 6337654 bytes
    10-20 23:41:06.958 I/Jeremy  ( 4947): export level 0, 2 pieces
    10-20 23:41:09.036 I/Jeremy  ( 4947): trip clip queued: 1 segments, 38354ms final=true
    10-20 23:41:53.956 I/Jeremy  ( 4947): export level 0 done: 119808ms, 5789302 bytes
    10-20 23:41:54.005 I/Jeremy  ( 4947): export level 0, 1 pieces
    10-20 23:42:09.562 I/Jeremy  ( 4947): export level 0 done: 38354ms, 1990937 bytes
done
