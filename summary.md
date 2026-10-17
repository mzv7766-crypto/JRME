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
- ✅ On-device AI detector loaded (MediaPipe, shrunk build)
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
- ✅ Settings sweep: 33 settings tapped, no crash
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
  - video 1 file: 1487533 bytes
  - video 1: 24.243867s, h264,1280,720,, audio: aac
  - video 2 file: 7679797 bytes
  - video 2: 119.847211s, h264,1280,720,, audio: aac
  - video 3 file: 7363304 bytes
  - video 3: 119.254089s, h264,1280,720,, audio: aac
  - video 4 file: 7522660 bytes
  - video 4: 119.840633s, h264,1280,720,, audio: aac
  - video 5 file: 5539806 bytes
  - video 5: 84.546122s, h264,1280,720,, audio: aac
  - video 6 file: 4662097 bytes
  - video 6: 76.243567s, h264,1280,720,, audio: aac
  - video 7 file: 6955060 bytes
  - video 7: 120.904500s, h264,1280,720,, audio: aac
  - video 8 file: 1779444 bytes
  - video 8: 25.790567s, h264,1280,720,, audio: aac
  - video 9 file: 4198448 bytes
  - video 9: 66.741522s, h264,1280,720,, audio: aac
  - video 10 file: 4642028 bytes
  - video 10: 82.690700s, h264,1280,720,, audio: aac
  - video 11 file: 1371764 bytes
  - video 11: 23.971600s, h264,1280,720,, audio: aac
  - video 12 file: 1584786 bytes
  - video 12: 93.740711s, h264,720,1280, audio: none
  - segment gaps (ms): 0 816 2435 1522 2241 1216 1609 2620 3605 1807 3048 2783 1902 1564 814 1852 2865 1626 2267 
    10-17 17:41:43.955 I/Jeremy  ( 4565): event start trigger=MANUAL
    10-17 17:41:53.976 I/Jeremy  ( 4565): trip clip queued: 2 segments, 93604ms final=false
    10-17 17:41:54.517 I/Jeremy  ( 4565): export level 0, 2 pieces
    10-17 17:41:54.699 I/Jeremy  ( 4565): export pieces=1 mediaMs=23868 spanMs=24136
    10-17 17:41:54.779 I/Jeremy  ( 4565): trip export paused for an event
    10-17 17:41:54.793 I/Jeremy  ( 4565): export level 0, 1 pieces
    10-17 17:42:48.500 I/Jeremy  ( 4565): export level 0 done: 23868ms, 1371764 bytes
    10-17 17:42:48.641 I/Jeremy  ( 4565): export level 0, 2 pieces
    10-17 17:42:50.821 I/Jeremy  ( 4565): event start trigger=MANUAL
    10-17 17:43:15.878 I/Jeremy  ( 4565): event discarded
    10-17 17:43:19.376 I/Jeremy  ( 4565): trip clip queued: 1 segments, 82657ms final=false
    10-17 17:44:15.396 I/Jeremy  ( 4565): event start trigger=FLOATING
    10-17 17:44:27.243 I/Jeremy  ( 4565): trip clip queued: 1 segments, 66619ms final=false
    10-17 17:44:27.245 I/Jeremy  ( 4565): export pieces=1 mediaMs=25668 spanMs=25798
    10-17 17:44:27.794 I/Jeremy  ( 4565): trip export paused for an event
    10-17 17:44:28.226 I/Jeremy  ( 4565): export level 0, 1 pieces
    10-17 17:44:54.641 I/Jeremy  ( 4565): export level 0 done: 25668ms, 1779444 bytes
    10-17 17:44:54.808 W/Jeremy  ( 4565): export: falling back to plain join (no overlays)
    10-17 17:44:58.529 I/Jeremy  ( 4565): export level 0, 1 pieces
    10-17 17:46:09.206 I/Jeremy  ( 4565): export level 0 done: 82657ms, 4642028 bytes
    10-17 17:46:09.696 I/Jeremy  ( 4565): export level 0, 1 pieces
    10-17 17:46:31.729 I/Jeremy  ( 4565): trip clip queued: 2 segments, 120637ms final=false
    10-17 17:47:44.198 I/Jeremy  ( 4565): export level 0 done: 66619ms, 4198448 bytes
    10-17 17:47:45.040 I/Jeremy  ( 4565): export level 0, 2 pieces
    10-17 17:47:55.582 I/Jeremy  ( 4565): trip clip queued: 3 segments, 75987ms final=false
    10-17 17:49:27.807 I/Jeremy  ( 4565): trip clip queued: 3 segments, 84368ms final=false
    10-17 17:50:13.329 I/Jeremy  ( 4565): export level 0 done: 120704ms, 6955060 bytes
    10-17 17:50:13.717 I/Jeremy  ( 4565): export level 0, 3 pieces
    10-17 17:51:29.686 I/Jeremy  ( 4565): trip clip queued: 2 segments, 119500ms final=false
    10-17 17:51:44.720 I/Jeremy  ( 4565): export level 0 done: 76078ms, 4662097 bytes
    10-17 17:51:45.325 I/Jeremy  ( 4565): export level 0, 3 pieces
    10-17 17:53:16.161 I/Jeremy  ( 4565): export level 0 done: 84444ms, 5539806 bytes
    10-17 17:53:17.154 I/Jeremy  ( 4565): export level 0, 2 pieces
    10-17 17:53:33.512 I/Jeremy  ( 4565): trip clip queued: 2 segments, 119093ms final=false
    10-17 17:55:24.264 I/Jeremy  ( 4565): export level 0 done: 119807ms, 7522660 bytes
    10-17 17:55:24.531 I/Jeremy  ( 4565): export level 0, 2 pieces
    10-17 17:55:36.977 I/Jeremy  ( 4565): trip clip queued: 2 segments, 119540ms final=false
    10-17 17:56:01.948 I/Jeremy  ( 4565): trip clip queued: 1 segments, 24198ms final=true
    10-17 17:56:31.310 I/Jeremy  ( 4565): export level 0 done: 119214ms, 7363304 bytes
    10-17 17:56:32.074 I/Jeremy  ( 4565): export level 0, 2 pieces
    10-17 17:57:16.665 I/Jeremy  ( 4565): export level 0 done: 119708ms, 7679797 bytes
    10-17 17:57:16.730 I/Jeremy  ( 4565): export level 0, 1 pieces
    10-17 17:57:27.631 I/Jeremy  ( 4565): export level 0 done: 24198ms, 1487533 bytes
done
