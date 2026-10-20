package com.jeremy.dashcam.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.CompositionSettings
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.jeremy.dashcam.R
import com.jeremy.dashcam.core.DashcamController
import com.jeremy.dashcam.core.DashcamState
import com.jeremy.dashcam.core.DetectionMetrics
import com.jeremy.dashcam.core.detect.ImpactAlgorithm
import com.jeremy.dashcam.core.detect.VisionAlgorithm
import android.os.SystemClock
import com.jeremy.dashcam.core.DrivePhase
import com.jeremy.dashcam.core.EventExporter
import com.jeremy.dashcam.core.MotionAnalyzer
import com.jeremy.dashcam.core.ShockDetector
import com.jeremy.dashcam.core.VoiceCommander
import com.jeremy.dashcam.data.AppSettings
import com.jeremy.dashcam.data.EventRecord
import com.jeremy.dashcam.data.EventRepository
import com.jeremy.dashcam.data.RecordKind
import com.jeremy.dashcam.data.Resolution
import com.jeremy.dashcam.data.SettingsStore
import com.jeremy.dashcam.data.Trigger
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Camera foreground service — the SINGLE OWNER of CameraX.
 *
 * Responsibilities: camera binding, continuous recording into short segments (rolling buffer),
 * event lifecycle, export to one MP4 with burned-in watermark, shock/motion/voice triggers,
 * ongoing notification and the floating button. Everything runs on the main thread except
 * image analysis (own executor) and thumbnail/index IO (worker thread).
 */
class DashcamService : LifecycleService() {

    companion object {
        const val ACTION_START = "com.jeremy.dashcam.START"
        const val ACTION_STOP = "com.jeremy.dashcam.STOP"
        const val ACTION_TOGGLE_EVENT = "com.jeremy.dashcam.TOGGLE_EVENT"
        const val ACTION_DISCARD_EVENT = "com.jeremy.dashcam.DISCARD_EVENT"
        private const val TAG = "DashcamService"
        // Keep audio inside the segments (reliable). Flip on only once on-device audio is verified.
        private const val CONTINUOUS_AUDIO = false
        // Rolling-buffer segment length. Longer segments mean the microphone is re-opened far less often,
        // which is what caused music to stutter every few seconds while recording. Pre-event trimming stays
        // precise because the exporter clips inside the first segment.
        private const val SEGMENT_MS = 60_000L
        private const val LOG = "Jeremy"

        @Volatile
        var instance: DashcamService? = null
            private set
    }

    private data class Segment(val file: File, val startWall: Long, val durationMs: Long) {
        val endWall get() = startWall + durationMs
    }

    private val main = Handler(Looper.getMainLooper())
    private val state get() = DashcamController._state
    private val settings get() = SettingsStore.current

    // Camera
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var analysisExecutor: ExecutorService? = null
    private var appliedVideoKey: String? = null
    private var rebindPending = false
    private var candidateRotation: Int? = null
    private var candidateSince = 0L
    private var physicalRotation: Int? = null
    private var segmentRotation = Surface.ROTATION_0
    private var lastRotationSwitch = 0L
    // The recording orientation is LOCKED once per drive (like a mounted dashcam): decided from how the phone
    // is positioned when the drive starts, then kept stable so the saved video is always upright and never
    // gets black bars from mixing orientations mid-drive. Only applies in AUTO; PORTRAIT/LANDSCAPE stay forced.
    private var driveRotation = Surface.ROTATION_0
    private var driveRotationLocked = false
    private var analysisUseCase: ImageAnalysis? = null

    // Thermal guard: when the battery gets hot the recording is auto-reduced (and warned about), then
    // restored once it cools. Hysteresis stops it flapping around a threshold.
    private var thermalLevel = 0
    private val thermalThrottle get() = thermalLevel >= 1
    private var driveLog: File? = null
    private var eventLog: File? = null
    /** Human-readable log of what happened during the drive: what turned on, what was saved, what failed. */
    fun logEvent(msg: String) {
        val f = eventLog ?: return
        runCatching {
            val st = state.value
            val elapsed = if (st.driveStartTime > 0) (System.currentTimeMillis() - st.driveStartTime) / 1000 else 0
            val iso = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            f.appendText("$iso  (+${elapsed}s)  $msg\n")
        }
    }
    private val thermalTick = object : Runnable {
        override fun run() { if (running) { sampleThermal(); main.postDelayed(this, 8_000) } }
    }
    private fun sampleThermal() {
        val batt = runCatching { registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val t = ((batt?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1)).let { if (it > 0) it / 10f else 0f }
        if (t <= 0f) return
        val level = when {
            thermalLevel >= 1 && t <= 40f -> 0   // cooled: back to full quality
            thermalLevel == 2 && t <= 45f -> 1
            t >= 47f -> 2
            t >= 43f -> maxOf(thermalLevel, 1)
            else -> thermalLevel
        }
        val changed = level != thermalLevel
        thermalLevel = level
        state.update { it.copy(thermalLevel = level, batteryTempC = t) }
        if (changed) {
            Log.i(LOG, "thermal level=$level temp=$t")
            motion.batterySaver = settings.batterySaver || thermalThrottle
            if (appliedVideoKey != null && appliedVideoKey != videoKey(settings) && !state.value.eventActive) requestRebind()
            logEvent(when (level) {
                2 -> "🌡 חם מאוד (${"%.0f".format(t)}°) — אזהרה"
                1 -> "🌡 מגן חום נכנס (${"%.0f".format(t)}°) — איכות הופחתה ל-${effResolution(settings).height}p/${effFps(settings)}"
                else -> "❄ התקרר (${"%.0f".format(t)}°) — איכות מלאה חזרה"
            })
        }
        logDriveRow(batt, t, if (changed) "thermal→$level" else "")
    }

    /** Appends one row of what Jeremy itself knows about the drive, so the user can share it for analysis. */
    private fun logDriveRow(batt: android.content.Intent?, tempC: Float, note: String) {
        val f = driveLog ?: return
        runCatching {
            val s = settings
            val bm = getSystemService(android.os.BatteryManager::class.java)
            val pct = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            val currentMa = (bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0) / 1000
            val status = batt?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL
            val mi = android.app.ActivityManager.MemoryInfo()
            getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(mi)
            val st = state.value
            val elapsed = (System.currentTimeMillis() - st.driveStartTime) / 1000
            val iso = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            f.appendText("$iso,$elapsed,$pct,${"%.1f".format(tempC)},$charging,$currentMa,${mi.availMem / 1048576},$thermalLevel,${effResolution(s).height},${effFps(s)},${st.eventActive},${s.batterySaver},${isPro && s.proAi && !s.postDriveAiScan},$note\n")
        }
    }

    private fun startDriveLog() {
        runCatching {
            val dir = File(filesDir, "drivelogs").apply { mkdirs() }
            dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(4)?.forEach { it.delete() } // keep last ~5
            val ts = System.currentTimeMillis()
            driveLog = File(dir, "drive_${ts}.csv").apply {
                appendText("time,elapsed_s,battery_pct,batt_temp_c,charging,current_mA,avail_mem_mb,thermal_level,rec_height,rec_fps,event_active,battery_saver,ai_live,note\n")
            }
            eventLog = File(dir, "events_${ts}.log").apply {
                appendText("Jeremy — יומן נסיעה ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}\n")
                appendText("גרסה ${com.jeremy.dashcam.BuildConfig.VERSION_NAME} • ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n\n")
            }
        }
    }

    private fun displayRotation(): Int = runCatching {
        getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY).rotation
    }.getOrDefault(Surface.ROTATION_0)

    /** Recording orientation: the phone's physical orientation, constrained by the app's orientation setting. */
    private fun desiredRotation(): Int {
        val base = physicalRotation ?: displayRotation()
        val isLandscape = base == Surface.ROTATION_90 || base == Surface.ROTATION_270
        return when (settings.orientation) {
            com.jeremy.dashcam.data.ScreenOrientation.LANDSCAPE -> if (isLandscape) base else {
                val d = displayRotation(); if (d == Surface.ROTATION_90 || d == Surface.ROTATION_270) d else Surface.ROTATION_90
            }
            com.jeremy.dashcam.data.ScreenOrientation.PORTRAIT -> if (isLandscape) Surface.ROTATION_0 else base
            com.jeremy.dashcam.data.ScreenOrientation.AUTO -> base
        }
    }

    /** The orientation actually used for the recording (locked per-drive in AUTO). */
    private fun recordingRotation(): Int =
        if (settings.orientation == com.jeremy.dashcam.data.ScreenOrientation.AUTO && settings.lockRotation && driveRotationLocked) driveRotation
        else desiredRotation()

    /** Locks the drive orientation to the current physical position (once per drive). */
    private fun lockDriveRotation() {
        if (driveRotationLocked) return
        driveRotation = desiredRotation()
        driveRotationLocked = true
        Log.i(LOG, "drive orientation locked to $driveRotation")
        onOrientationMaybeChanged()
    }

    /** Apply a new orientation quickly: start a fresh segment now (never in the middle of an event). */
    private fun onOrientationMaybeChanged() {
        if (!running) return
        val want = recordingRotation()
        analysisUseCase?.targetRotation = want
        if (want == segmentRotation || (state.value.eventActive && settings.lockRotation)) return
        val now = SystemClock.elapsedRealtime()
        val rec = activeRecording ?: return
        if (now - lastRotationSwitch < 3000 || System.currentTimeMillis() - activeStartWall < 1500) return
        lastRotationSwitch = now
        Log.i(LOG, "orientation changed → new segment with rotation $want")
        main.removeCallbacks(rotateSegment)
        rec.stop()
    }

    // Rolling buffer
    private val bufferDir by lazy { File(filesDir, "buffer").apply { mkdirs() } }
    private val segments = ArrayList<Segment>()
    /** Segments in use by a pending event or trip export (ref-counted: both may use the same file). */
    private val protectedRefs = HashMap<File, Int>()
    private fun protect(files: Collection<File>) = files.forEach { protectedRefs[it] = (protectedRefs[it] ?: 0) + 1 }
    private fun unprotect(files: Collection<File>) = files.forEach { f ->
        val n = (protectedRefs[f] ?: 0) - 1
        if (n <= 0) protectedRefs.remove(f) else protectedRefs[f] = n
    }
    private fun isProtected(f: File) = protectedRefs.containsKey(f)

    // ---- export queue: one export at a time, events before trip clips ----
    private class ExportJob(val isEvent: Boolean, val run: (done: () -> Unit) -> Unit)
    private val exportQueue = ArrayDeque<ExportJob>()
    private var exportBusy = false
    private var pendingTripJobs = 0

    private var runningJob: ExportJob? = null

    private fun enqueueExport(job: ExportJob) {
        val eventsQueued = exportQueue.count { it.isEvent }
        if (job.isEvent) exportQueue.add(eventsQueued, job) else exportQueue.addLast(job)
        // An event must never wait behind a long trip-clip transcode: pause the trip clip and redo it after.
        val running = runningJob
        if (job.isEvent && running != null && !running.isEvent && exporter.cancel()) {
            Log.i(LOG, "trip export paused for an event")
            exportQueue.add(eventsQueued + 1, running)
            runningJob = null
            exportBusy = false
        }
        pumpExports()
    }

    private fun pumpExports() {
        if (exportBusy) return
        val job = exportQueue.removeFirstOrNull() ?: return
        exportBusy = true
        runningJob = job
        job.run {
            main.post {
                if (runningJob === job) { runningJob = null; exportBusy = false }
                pumpExports(); maybeFinish()
            }
        }
    }

    private fun idle() = state.value.savingCount == 0 && pendingTripJobs == 0 && !exportBusy && exportQueue.isEmpty()

    private fun maybeFinish() {
        if (!running && idle()) finishService()
    }

    // ---- full-trip recording ----
    private var tripId = 0L
    private val tripPending = ArrayList<Segment>()
    private var tripClipStart = 0L
    private var activeRecording: Recording? = null
    private var activeStartWall = 0L
    private val finalizeCallbacks = ArrayList<() -> Unit>()
    private var consecutiveFailures = 0
    private var lastSegmentEndWall = 0L
    private val rotateSegment = Runnable { activeRecording?.stop() }
    private val startSegmentRunnable = Runnable { startSegment() }

    // Drive
    private var running = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var appInForeground = true
    private val driveJobs = ArrayList<Job>()

    // Helpers
    private lateinit var exporter: EventExporter
    private lateinit var shock: ShockDetector
    private lateinit var motion: MotionAnalyzer
    private lateinit var voice: VoiceCommander
    private lateinit var floating: FloatingButton
    private lateinit var audioRecorder: com.jeremy.dashcam.core.ContinuousAudioRecorder
    private var orientationListener: OrientationEventListener? = null

    private val processObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) { appInForeground = true; syncFloating() }
        override fun onStop(owner: LifecycleOwner) { appInForeground = false; syncFloating() }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        instance = this
        SettingsStore.init(this)
        EventRepository.init(this)
        exporter = EventExporter(this)
        audioRecorder = com.jeremy.dashcam.core.ContinuousAudioRecorder(this)
        shock = ShockDetector(this) { d -> main.post { onImpact(d) } }
        motion = MotionAnalyzer(this, { f -> main.post { onVision(f) } }, { d -> main.post { onAi(d) } })
        voice = VoiceCommander(
            this,
            onStart = { if (!state.value.eventActive) startEvent(Trigger.VOICE) },
            onStop = { if (state.value.eventActive) stopEvent() },
        )
        floating = FloatingButton(this, onTap = { toggleEvent(Trigger.FLOATING) }, onLongPress = { shutdown() })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> if (!running) beginDrive()
            ACTION_STOP -> shutdown()
            ACTION_TOGGLE_EVENT -> if (running) toggleEvent(Trigger.NOTIFICATION)
            ACTION_DISCARD_EVENT -> if (running) discardEvent()
            else -> if (!running) {
                // Restarted by the system without a UI: Android does not allow re-opening the camera from
                // the background, so make sure we don't linger in a half-alive state.
                state.value = DashcamState(); stopSelf()
            }
        }
        if (!running && idle() && intent?.action != ACTION_START) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (running) teardown()
        instance = null
        voice.release()
        analysisExecutor?.shutdown()
        main.removeCallbacksAndMessages(null)
        if (state.value.driveActive) state.value = DashcamState()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- drive mode

    private fun beginDrive() {
        running = true
        val now = System.currentTimeMillis()
        state.update { it.copy(phase = DrivePhase.STARTING, driveStartTime = now, error = null) }
        try {
            enterForeground()
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            running = false
            state.value = DashcamState(error = e.message)
            stopSelf(); return
        }
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jeremy:drive").apply { setReferenceCounted(false); acquire() }

        bufferDir.listFiles()?.forEach { it.delete() } // leftovers from a previous crash
        segments.clear()
        // Continuous audio (mic opened once for the whole drive, muxed in at export) removes the music
        // stutter entirely, but on real devices it sometimes produced SILENT clips when the mic wasn't
        // captured exactly right — unacceptable for a dashcam. It is OFF until we can verify on-device
        // that sound is always kept. With it off, segments record their own audio (reliable), and the
        // 60 s segment length already keeps music interruptions down to about once a minute.
        if (CONTINUOUS_AUDIO && settings.recordAudio && hasAudioPermission()) runCatching { audioRecorder.start() }
        tripId = now; tripPending.clear(); tripClipStart = 0L
        driveRotationLocked = false; physicalRotation = null
        thermalLevel = 0; startDriveLog(); main.postDelayed(thermalTick, 8_000) // thermal guard + drive log
        logEvent("▶ נסיעה התחילה • שמע=${if (settings.recordAudio) "כן" else "לא"} • חיסכון=${if (settings.batterySaver) "כן" else "לא"} • AI-בסוף=${if (settings.postDriveAiScan) "כן" else "לא"}")
        if (settings.lockRotation) main.postDelayed({ if (running && !driveRotationLocked) lockDriveRotation() }, 2000) // flat phone → lock to the screen

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                // Flat (pointing at the floor/sky) → no physical orientation; fall back to the screen.
                val r = if (o == ORIENTATION_UNKNOWN) null else when (o) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                val now = SystemClock.elapsedRealtime()
                if (r != candidateRotation) { candidateRotation = r; candidateSince = now; return }
                if (now - candidateSince >= 800 && physicalRotation != r) {   // stable for 0.8 s
                    physicalRotation = r
                    if (settings.lockRotation && !driveRotationLocked && r != null) lockDriveRotation() else onOrientationMaybeChanged()
                }
            }
        }.also { if (it.canDetectOrientation()) it.enable() }

        ProcessLifecycleOwner.get().lifecycle.addObserver(processObserver)

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!running) return@addListener
            try {
                provider = future.get()
                bindCamera()
            } catch (e: Exception) {
                onCameraError(e)
            }
        }, ContextCompat.getMainExecutor(this))

        // React to settings while driving.
        driveJobs += lifecycleScope.launch { SettingsStore.state.collect { applySettings(it) } }
        driveJobs += lifecycleScope.launch { com.jeremy.dashcam.data.ProStore.state.collect {
            applyProFeatures()
            motion.aiWanted = isPro && settings.proAi && !settings.postDriveAiScan
            if (appliedVideoKey != null && appliedVideoKey != videoKey(settings) && !state.value.eventActive) requestRebind()
        } }
        // Attach / detach the on-screen preview offered by the UI (never opens the camera itself).
        driveJobs += lifecycleScope.launch {
            DashcamController.previewSurface.collect { sp ->
                runCatching { preview?.setSurfaceProvider(ContextCompat.getMainExecutor(this@DashcamService), sp) }
            }
        }
        // Keep notification + floating button in sync.
        driveJobs += lifecycleScope.launch {
            state.map { Triple(it.eventActive, it.savingCount, it.phase) }.distinctUntilChanged().collect {
                if (running) {
                    getSystemService(NotificationManager::class.java).notify(Notifications.ID_DRIVE, Notifications.drive(this@DashcamService, state.value))
                }
                syncFloating()
            }
        }
    }

    private fun enterForeground() {
        var type = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            // Declare microphone whenever permitted, so toggling audio/voice mid-drive keeps working in background.
            if (hasAudioPermission()) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (locationWanted()) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        ServiceCompat.startForeground(this, Notifications.ID_DRIVE, Notifications.drive(this, state.value), type)
    }

    /** Turns drive mode fully off: saves a running event, stops camera + sensors, removes the floating button. */
    fun shutdown() {
        if (!running) {
            if (idle()) stopSelf()
            return
        }
        if (state.value.eventActive) stopEvent()
        teardown()
        maybeFinish()
        // else: stay alive (with a "saving" notification) until the export completes.
    }

    private fun teardown() {
        logEvent("■ נסיעה הסתיימה")
        running = false
        driveJobs.forEach { it.cancel() }; driveJobs.clear()
        main.removeCallbacks(rotateSegment); main.removeCallbacks(startSegmentRunnable); main.removeCallbacks(autoStopRunnable); main.removeCallbacks(thermalTick)
        shock.stop(); motion.enabled = false; voice.stop()
        motion.aiWanted = false; runCatching { analysisExecutor?.execute { motion.release() } }
        runCatching { Thread { audioRecorder.stop() }.start() } // off the main thread (join can take up to ~1.5 s)
        com.jeremy.dashcam.core.pro.LocationTracker.stop(); sosOverlay.dismiss(); DashcamController.dualActive.value = false
        main.removeCallbacks(metricsTick); DashcamController.metrics.value = null
        floating.hide()
        orientationListener?.disable(); orientationListener = null; analysisUseCase = null
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processObserver)
        val rec = activeRecording
        if (rec != null) {
            finalizeCallbacks += { unbindCamera(); flushTrip(final = true); cleanBuffer() }
            rec.stop()
        } else {
            unbindCamera(); flushTrip(final = true); cleanBuffer()
        }
        maybeEnqueueScan()
        val saving = state.value.savingCount
        val background = pendingTripJobs > 0 || exportBusy || exportQueue.isNotEmpty() || tripPending.isNotEmpty() || activeRecording != null
        state.value = DashcamState(savingCount = saving, backgroundSaving = background)
        if (saving > 0 || background) {
            getSystemService(NotificationManager::class.java).notify(Notifications.ID_DRIVE, Notifications.drive(this, state.value))
        }
    }

    private fun finishService() {
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------------------------------------------------------------- camera

    /** Battery saver (or the thermal guard when the phone is hot) caps the recording so it runs cooler. */
    private fun lightLoad(s: AppSettings) = s.batterySaver || thermalThrottle
    private fun effResolution(s: AppSettings) = if (lightLoad(s) && s.resolution == Resolution.UHD) Resolution.FHD else s.resolution
    private fun effFps(s: AppSettings) = if (lightLoad(s)) minOf(s.fps, 30) else s.fps
    /** The camera analysis stream (and the detectors it feeds) is only worth running when something consumes it. */
    private fun needAnalysis(s: AppSettings) = s.smartDetection

    private fun videoKey(s: AppSettings) = "${effResolution(s)}/${effFps(s)}/${s.recordAudio}/${state.value.lensFacing}/${isPro && s.proDual}/${needAnalysis(s)}"

    @SuppressLint("RestrictedApi")
    private fun bindCamera() {
        val p = provider ?: return
        val s = settings
        p.unbindAll()
        val selector = CameraSelector.Builder().requireLensFacing(state.value.lensFacing).build()
        if (!runCatching { p.hasCamera(selector) }.getOrDefault(false)) {
            state.update { it.copy(lensFacing = CameraSelector.LENS_FACING_BACK) }
        }
        val lensSelector = CameraSelector.Builder().requireLensFacing(state.value.lensFacing).build()

        val quality = when (effResolution(s)) { Resolution.HD -> Quality.HD; Resolution.FHD -> Quality.FHD; Resolution.UHD -> Quality.UHD }
        val qualitySelector = QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
        val fps = effFps(s)

        fun buildVideo(withFps: Boolean): VideoCapture<Recorder> {
            val recorder = Recorder.Builder().setQualitySelector(qualitySelector).build()
            return VideoCapture.Builder(recorder).apply {
                if (withFps) setTargetFrameRate(Range(fps, fps))
            }.build().also { it.targetRotation = recordingRotation() }
        }

        val newPreview = Preview.Builder().build().also { it.targetRotation = recordingRotation() }
        motion.batterySaver = s.batterySaver || thermalThrottle
        // Only build and bind the analysis stream when a detector actually consumes it. With smart
        // detection off, skipping this whole camera stream noticeably cuts CPU, heat and battery.
        val wantAnalysis = needAnalysis(s)
        val analysis = if (wantAnalysis) ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                ).build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.targetRotation = recordingRotation()
                val exec = analysisExecutor ?: Executors.newSingleThreadExecutor().also { e -> analysisExecutor = e }
                it.setAnalyzer(exec, motion)
            } else null
        analysisUseCase = analysis

        // Try the richest configuration first, then degrade gracefully instead of crashing.
        // (fps, withAnalysis) – analysis attempts are dropped entirely when nothing needs it.
        val attempts: List<Pair<Boolean, Boolean>> =
            if (wantAnalysis) listOf(true to true, false to true, true to false, false to false)
            else listOf(true to false, false to false)
        var bound: VideoCapture<Recorder>? = null
        var lastError: Exception? = null
        DashcamController.dualActive.value = false
        // PRO: front + back at once, composed picture-in-picture into ONE recording
        val dualOk = runCatching { p.availableConcurrentCameraInfos.isNotEmpty() }.getOrDefault(false)
        DashcamController.dualSupported.value = dualOk
        if (isPro && s.proDual && dualOk) {
            try {
                val vc = buildVideo(false)
                val group = UseCaseGroup.Builder().addUseCase(newPreview).addUseCase(vc).build()
                val primary = ConcurrentCamera.SingleCameraConfig(
                    CameraSelector.DEFAULT_BACK_CAMERA, group,
                    CompositionSettings.Builder().setAlpha(1f).setOffset(0f, 0f).setScale(1f, 1f).build(), this)
                val secondary = ConcurrentCamera.SingleCameraConfig(
                    CameraSelector.DEFAULT_FRONT_CAMERA, group,
                    CompositionSettings.Builder().setAlpha(1f).setOffset(-0.62f, -0.52f).setScale(0.33f, 0.33f).build(), this)
                p.unbindAll()
                p.bindToLifecycle(listOf(primary, secondary))
                bound = vc
                DashcamController.dualActive.value = true
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "dual bind failed", e)
            }
        }
        if (bound == null) for ((withFps, withAnalysis) in attempts) {
            val vc = buildVideo(withFps)
            val cases = mutableListOf<UseCase>(newPreview, vc)
            if (withAnalysis) analysis?.let { cases += it }
            try {
                p.unbindAll()
                p.bindToLifecycle(this, lensSelector, *cases.toTypedArray())
                bound = vc
                break
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "bind failed fps=$fps analysis=$withAnalysis", e)
            }
        }
        if (bound == null) { onCameraError(lastError ?: IllegalStateException("bind failed")); return }

        preview = newPreview
        videoCapture = bound
        appliedVideoKey = videoKey(s)
        newPreview.setSurfaceProvider(ContextCompat.getMainExecutor(this), DashcamController.previewSurface.value)
        motion.reset()
        consecutiveFailures = 0
        state.update { it.copy(phase = DrivePhase.RUNNING, audioActive = s.recordAudio && hasAudioPermission(), error = null) }
        logEvent("📷 מצלמה פעילה • ${effResolution(s).height}p/${effFps(s)} • ${if (DashcamController.dualActive.value) "מצלמה כפולה" else "מצלמה אחת"} • זיהוי=${if (s.smartDetection) "כן" else "לא"} • AI-חי=${if (motion.aiWanted) "כן" else "לא"}")
        startSegment()
    }

    private fun unbindCamera() {
        runCatching { provider?.unbindAll() }
        preview = null; videoCapture = null; appliedVideoKey = null
    }

    /** Re-binds safely: never while a segment is being written. */
    private fun requestRebind() {
        if (provider == null || !running) return
        val rec = activeRecording
        if (rec != null) { rebindPending = true; rec.stop() } else bindCamera()
    }

    fun switchCamera() {
        if (!running) return
        state.update {
            it.copy(lensFacing = if (it.lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK)
        }
        requestRebind()
    }

    private fun onCameraError(e: Throwable) {
        logEvent("✗ שגיאת מצלמה: ${e.message ?: e.javaClass.simpleName}")
        Log.e(TAG, "camera error", e)
        state.update { it.copy(error = getString(R.string.camera_error, e.message ?: e.javaClass.simpleName)) }
    }

    // ---------------------------------------------------------------- rolling buffer

    @SuppressLint("MissingPermission")
    private fun startSegment() {
        if (!running || activeRecording != null) return
        val vc = videoCapture ?: return
        if (!state.value.eventActive || !settings.lockRotation) { segmentRotation = recordingRotation(); vc.targetRotation = segmentRotation }
        val land = segmentRotation == Surface.ROTATION_90 || segmentRotation == Surface.ROTATION_270
        if (state.value.recordingLandscape != land) state.update { it.copy(recordingLandscape = land) }
        Log.i(LOG, "segment start rotation=$segmentRotation physical=$physicalRotation display=${displayRotation()} setting=${settings.orientation}")
        val file = File(bufferDir, "seg_${System.currentTimeMillis()}.mp4")
        try {
            var pending = vc.output.prepareRecording(this, FileOutputOptions.Builder(file).build())
            // Audio is captured continuously by audioRecorder and muxed in at export, so segments are
            // video-only. Only if that recorder could not open the mic do we fall back to per-segment
            // audio (which brings back the music stutter, but never loses sound).
            if (settings.recordAudio && hasAudioPermission() && !audioRecorder.active) pending = pending.withAudioEnabled()
            activeStartWall = System.currentTimeMillis()
            activeRecording = pending.start(ContextCompat.getMainExecutor(this)) { ev -> onRecordEvent(file, ev) }
            // While an event is running the segment is NOT rotated, so the event itself is one gap-free recording.
            if (!state.value.eventActive) main.postDelayed(rotateSegment, SEGMENT_MS)
        } catch (e: Exception) {
            Log.e(TAG, "start segment failed", e)
            activeRecording = null
            scheduleRetry()
        }
    }

    private fun onRecordEvent(file: File, ev: VideoRecordEvent) {
        when (ev) {
            is VideoRecordEvent.Start -> activeStartWall = System.currentTimeMillis()
            is VideoRecordEvent.Finalize -> {
                main.removeCallbacks(rotateSegment)
                activeRecording = null
                val durMs = ev.recordingStats.recordedDurationNanos / 1_000_000
                val fatal = ev.error in setOf(
                    VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA,
                    VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED,
                    VideoRecordEvent.Finalize.ERROR_INVALID_OUTPUT_OPTIONS,
                    VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR,
                )
                if (!fatal && file.exists() && file.length() > 1024 && durMs > 300) {
                    val gap = if (lastSegmentEndWall > 0) activeStartWall - lastSegmentEndWall else 0
                    Log.i(LOG, "segment ${file.name} dur=${durMs}ms gapBefore=${gap}ms")
                    lastSegmentEndWall = activeStartWall + durMs
                    val seg = Segment(file, activeStartWall, durMs)
                    segments += seg
                    consecutiveFailures = 0
                    onTripSegment(seg)
                } else {
                    file.delete()
                    if (ev.error != VideoRecordEvent.Finalize.ERROR_NONE) {
                        Log.w(TAG, "segment failed: error=${ev.error}", ev.cause)
                        consecutiveFailures++
                    }
                }
                val callbacks = finalizeCallbacks.toList(); finalizeCallbacks.clear()
                callbacks.forEach { runCatching(it).onFailure { t -> Log.e(TAG, "finalize callback", t) } }
                pruneBuffer()
                when {
                    !running -> Unit
                    rebindPending -> { rebindPending = false; bindCamera() }
                    consecutiveFailures > 0 -> scheduleRetry()
                    else -> startSegment()
                }
            }
            else -> Unit
        }
    }

    private fun scheduleRetry() {
        val delay = (1000L * consecutiveFailures.coerceIn(1, 10))
        main.removeCallbacks(startSegmentRunnable)
        main.postDelayed(startSegmentRunnable, delay)
        if (consecutiveFailures >= 5) requestRebind()
    }

    private fun pruneBuffer() {
        val now = System.currentTimeMillis()
        val s = state.value
        val keepFrom = if (s.eventActive) s.eventStartTime - settings.preEventSeconds * 1000L - SEGMENT_MS
        else now - settings.preEventSeconds * 1000L - 2 * SEGMENT_MS
        val it = segments.iterator()
        while (it.hasNext()) {
            val seg = it.next()
            if (seg.endWall < keepFrom && !isProtected(seg.file)) { seg.file.delete(); it.remove() }
        }
        // Keep the continuous-audio chunks in step with the oldest video we still hold.
        val oldestKept = segments.minOfOrNull { it.startWall } ?: keepFrom
        runCatching { audioRecorder.prune(minOf(keepFrom, oldestKept)) }
    }

    private fun cleanBuffer() {
        val it = segments.iterator()
        while (it.hasNext()) { val seg = it.next(); if (!isProtected(seg.file)) { seg.file.delete(); it.remove() } }
    }

    /**
     * The audio slice (a WAV) that lines up exactly with the video [pieces], ready to be muxed into the
     * exported clip. One window per piece, so inter-segment gaps are skipped in the audio just as in the
     * video. Returns null when audio is off or the continuous recorder never ran (then the segments keep
     * their own audio, if any).
     */
    private fun audioWavFor(pieces: List<EventExporter.Piece>): File? {
        if (!settings.recordAudio || pieces.isEmpty()) return null
        val windows = pieces.map { (it.wallStartMs + it.clipStartMs) to (it.wallStartMs + it.durationMs) }
        val wav = File(cacheDir, "aud_exp_${System.currentTimeMillis()}_${pieces.size}.wav")
        return runCatching { audioRecorder.wavForWindows(windows, wav) }.getOrNull()
    }

    // ---------------------------------------------------------------- events

    fun toggleEvent(trigger: Trigger) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { toggleEvent(trigger) }; return }
        if (state.value.eventActive) stopEvent() else startEvent(trigger)
    }

    /**
     * Sensor fusion.
     *  - A real impact from the motion sensors triggers immediately.
     *  - Harsh braking / swerve (sustained horizontal force) triggers its own event type.
     *  - Vision anomalies need confirmation: either the vision signal is very strong, or the motion
     *    sensors felt a noticeable horizontal force (≥ 0.35 g) within ±1.5 s. This removes most false
     *    alarms from passing traffic, shadows and wipers while still catching near-misses.
     */
    // ================= PRO =================
    private val isPro get() = com.jeremy.dashcam.data.ProStore.state.value.isPro
    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    // Speed & location are FREE and on by default; a Pro user may hide either one (burnSpeed / burnLocation).
    private fun effSpeed() = !isPro || settings.burnSpeed
    private fun effLoc() = !isPro || settings.burnLocation
    private fun locationWanted() = (effSpeed() || effLoc()) && hasLocationPermission()
    private val sosOverlay by lazy { EmergencyOverlay(this) }

    private fun applyProFeatures() {
        if (running && locationWanted()) com.jeremy.dashcam.core.pro.LocationTracker.start(this)
        else com.jeremy.dashcam.core.pro.LocationTracker.stop()
        val speed = effSpeed(); val loc = effLoc()
        exporter.infoAt = if (locationWanted() && (speed || loc)) { t ->
            // Bridge short GPS gaps (tunnels, buildings) with the nearest known fix up to ~2 min away, so
            // the speed/location line stays present and the overlay does not jump size as GPS blinks.
            com.jeremy.dashcam.core.pro.LocationTracker.at(t, 120_000)?.let { com.jeremy.dashcam.core.pro.LocationTracker.label(it, speed, loc) }
        } else null
        exporter.showBrand = !(isPro && !settings.burnBrand)
    }

    /** Strong impact → countdown over every app → call the emergency contact unless "I'm OK". */
    private fun maybeSos(d: ImpactAlgorithm.Detection) {
        val s = settings
        if (!isPro || !s.sosEnabled || s.sosNumber.isBlank() || d.type != ImpactAlgorithm.Type.IMPACT || d.peakG < s.sosMinG) return
        Log.i(LOG, "SOS countdown (peak ${d.peakG}g)")
        logEvent("🆘 התראת חירום הופעלה (%.1fg) — ספירה לחיוג".format(d.peakG))
        sosOverlay.show(s.sosSeconds, s.sosName, s.sosNumber, test = false, onSpeak = { voice.initTts(); voice.speak(it) }) {
            logEvent("🆘 חיוג חירום ל-${s.sosNumber}")
            EmergencyOverlay.call(this, s.sosNumber)
        }
    }

    fun testSos(name: String = settings.sosName, number: String = settings.sosNumber) {
        sosOverlay.show(10, name, number, test = true, onSpeak = { voice.initTts(); voice.speak(it) }) {}
    }

    /** After an event is saved: read licence plates in the background (on the phone). */
    private fun readPlatesLater(r: EventRecord) {
        if (!isPro || !settings.proPlates) return
        Thread {
            val trigOffset = (r.triggerTime - r.videoStartTime).coerceAtLeast(0)
            val plates = com.jeremy.dashcam.core.pro.PlateReader.read(applicationContext, r.file, trigOffset - 6000, trigOffset + 12000)
            Log.i(LOG, "plates for ${r.id}: ${plates.size} found")
            EventRepository.setPlates(r.id, plates.map { it.text })
        }.start()
    }

    /**
     * PRO on-device AI: a vehicle / pedestrian in our path is closing in fast (time-to-contact below the
     * threshold, seen twice in a row). That is a real near-miss even without any physical bump, so it
     * starts an event on its own (auto-ends like the other automatic events).
     */
    private fun onAi(d: com.jeremy.dashcam.core.detect.ApproachAlgorithm.Danger) {
        if (!running || !settings.smartDetection || !isPro || !settings.proAi) return
        Log.i(LOG, "AI danger: ${d.label} ttc=${"%.2f".format(d.ttcSec)}s size=${"%.2f".format(d.size)}")
        if (state.value.eventActive) { extendAutoStop(); return }
        startEvent(Trigger.AI)
    }

    private fun onImpact(d: ImpactAlgorithm.Detection) {
        maybeSos(d)
        if (!running || !settings.shockDetection) return
        if (state.value.eventActive) { extendAutoStop(); return }   // still unusual → keep recording a bit longer
        val trigger = if (d.type == ImpactAlgorithm.Type.IMPACT) Trigger.SHOCK else Trigger.HARSH
        startEvent(trigger, d.peakG)
    }

    /**
     * Image detection is only a *hint*: it starts an event only when the motion sensors confirm that
     * something physical happened (≥ 0.45 g within ±1.5 s). Without sensors it needs a very strong,
     * daytime signal. Headlights, flicker and night-time noise therefore never start an event alone.
     */
    private fun onVision(f: VisionAlgorithm.Frame) {
        if (!running || !settings.smartDetection) return
        val now = SystemClock.elapsedRealtime()
        if (f.candidate) lastVisionCandidateAt = now
        if (!f.triggered) return
        val a = shock.algorithm
        val confirmed = settings.shockDetection && a.hadBumpNear(now, 1500) && a.lastBumpG >= 0.45f
        val ok = if (settings.shockDetection) confirmed else (f.strong && !f.night)
        if (!ok) { Log.i(LOG, "vision anomaly ignored (not confirmed by sensors, night=${f.night})"); return }
        if (state.value.eventActive) { extendAutoStop(); return }
        startEvent(Trigger.MOTION, a.lastBumpG)
    }

    private val autoStopRunnable = Runnable {
        val s = state.value
        if (running && s.eventActive && s.autoStopAt > 0) {
            if (System.currentTimeMillis() >= s.autoStopAt - 50) { Log.i(LOG, "auto event ended by timer"); stopEvent() }
            else scheduleAutoStop(s.autoStopAt)
        }
    }

    private fun scheduleAutoStop(at: Long) {
        main.removeCallbacks(autoStopRunnable)
        main.postDelayed(autoStopRunnable, (at - System.currentTimeMillis()).coerceAtLeast(0))
    }

    private fun extendAutoStop() {
        val s = state.value
        if (s.autoStopAt <= 0) return
        val at = maxOf(s.autoStopAt, System.currentTimeMillis() + settings.autoStopSeconds * 1000L)
        state.update { it.copy(autoStopAt = at) }
        scheduleAutoStop(at)
    }

    private var lastVisionCandidateAt = Long.MIN_VALUE

    private fun publishMetrics() {
        if (!running) return
        val a = shock.algorithm; val v = motion.algorithm
        DashcamController.metrics.value = DetectionMetrics(
            horizontalG = a.horizontalG, peakG = a.peakG,
            impactThresholdG = a.impactThresholdG, harshThresholdG = a.harshThresholdG,
            visionScore = v.lastScore, visionThreshold = v.scoreThreshold,
            sensorsOn = settings.shockDetection, visionOn = settings.smartDetection,
        )
        main.postDelayed(metricsTick, 250)
    }

    private val metricsTick = Runnable { publishMetrics() }

    private fun startEvent(trigger: Trigger, peakG: Float? = null) {
        if (!running || state.value.phase != DrivePhase.RUNNING || state.value.eventActive) return
        main.removeCallbacks(rotateSegment) // keep the current segment running for the whole event
        val now = System.currentTimeMillis()
        val auto = trigger == Trigger.SHOCK || trigger == Trigger.HARSH || trigger == Trigger.MOTION || trigger == Trigger.AI
        val stopAt = if (auto && settings.autoStop) now + settings.autoStopSeconds * 1000L else 0L
        state.update { it.copy(eventActive = true, eventStartTime = now, eventTrigger = trigger, eventPeakG = peakG, autoStopAt = stopAt) }
        if (stopAt > 0) scheduleAutoStop(stopAt)
        Log.i(LOG, "event start trigger=$trigger")
        logEvent("● אירוע התחיל • טריגר=$trigger${peakG?.let { " • %.1fg".format(it) } ?: ""}")
        if (settings.voiceFeedback) voice.speak(getString(R.string.tts_started))
    }

    /** Ends the running event WITHOUT saving a video. Recording to the rolling buffer continues. */
    fun discardEvent() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { discardEvent() }; return }
        if (!state.value.eventActive) return
        main.removeCallbacks(autoStopRunnable)
        state.update { it.copy(eventActive = false, eventTrigger = null, autoStopAt = 0L) }
        Log.i(LOG, "event discarded")
        main.post { applyPendingVideoChange() }
        if (activeRecording != null) { main.removeCallbacks(rotateSegment); main.postDelayed(rotateSegment, 2_000) }
        pruneBuffer()
        if (settings.voiceFeedback) voice.speak(getString(R.string.tts_discarded))
    }

    fun isDriving() = running && state.value.phase == DrivePhase.RUNNING
    fun isEventActive() = state.value.eventActive

    /** Volume-key entry point (accessibility service or in-app). Returns true if the key was used. */
    fun onVolumeAction(up: Boolean): Boolean {
        if (!running) return false
        if (up) { toggleEvent(Trigger.VOLUME); return true }
        if (state.value.eventActive) { discardEvent(); return true }
        return false
    }

    /** Ends the event: closes the current segment, then joins pre-event + event segments into ONE MP4. */
    private fun stopEvent() {
        val s = state.value
        if (!s.eventActive) return
        val stopTime = System.currentTimeMillis()
        val eventStart = s.eventStartTime
        val trigger = s.eventTrigger ?: Trigger.MANUAL
        val peakG = s.eventPeakG
        val fixAtEvent = if (locationWanted()) com.jeremy.dashcam.core.pro.LocationTracker.at(s.eventStartTime) else null
        val preMs = settings.preEventSeconds * 1000L
        val showDateTime = settings.showDateTime
        val aiTags = if (isPro && settings.proAi && settings.smartDetection) com.jeremy.dashcam.core.pro.AiSeen.between(eventStart - preMs, stopTime) else emptyList()
        main.removeCallbacks(autoStopRunnable)
        state.update { it.copy(eventActive = false, eventTrigger = null, autoStopAt = 0L, savingCount = it.savingCount + 1) }
        main.postDelayed({ applyPendingVideoChange() }, 1500)  // video settings changed during the event

        val collectAndExport = {
            val from = eventStart - preMs
            val chosen = segments.filter { it.endWall > from && it.startWall < stopTime }.sortedBy { it.startWall }
            if (chosen.isEmpty()) {
                onExportFinished(null, null)
            } else {
                val pieces = chosen.mapIndexed { i, seg ->
                    val clip = if (i == 0) (from - seg.startWall).coerceIn(0, (seg.durationMs - 200).coerceAtLeast(0)) else 0L
                    EventExporter.Piece(seg.file, clip, seg.durationMs, seg.startWall)
                }
                Log.i(LOG, "export pieces=${pieces.size} mediaMs=${pieces.sumOf { it.durationMs - it.clipStartMs }} spanMs=${stopTime - from}")
                val out = EventRepository.newEventFile(eventStart)
                protect(chosen.map { it.file })
                enqueueExport(ExportJob(isEvent = true) { done ->
                    val audioWav = audioWavFor(pieces)
                    exporter.export(pieces, out, showDateTime, audioWav = audioWav) { result ->
                        runCatching { audioWav?.delete() }
                        unprotect(chosen.map { it.file })
                        if (running) pruneBuffer() else cleanBuffer()
                        val record = result?.let {
                            EventRecord(
                                id = UUID.randomUUID().toString(), filePath = it.file.absolutePath, name = null,
                                videoStartTime = it.videoStartWall, triggerTime = eventStart, durationMs = it.durationMs,
                                trigger = trigger, locked = false, sizeBytes = it.sizeBytes, peakG = peakG,
                                lat = fixAtEvent?.lat, lon = fixAtEvent?.lon, speedKmh = fixAtEvent?.speedKmh,
                                aiTags = aiTags,
                            )
                        }
                        onExportFinished(record, out)
                        done()
                    }
                })
            }
        }
        if (activeRecording != null) {
            finalizeCallbacks += collectAndExport
            activeRecording?.stop()
        } else {
            collectAndExport()
        }
    }

    /** Every finished segment joins the current trip clip; when the clip reaches N minutes it is saved. */
    private fun onTripSegment(seg: Segment) {
        if (!settings.tripRecording) { if (tripPending.isNotEmpty()) flushTrip(final = false); return }
        if (tripPending.isEmpty()) tripClipStart = seg.startWall
        tripPending += seg
        protect(listOf(seg.file))
        if (seg.endWall - tripClipStart >= settings.tripClipMinutes * 60_000L) flushTrip(final = false)
    }

    private fun flushTrip(final: Boolean) {
        if (tripPending.isEmpty()) return
        val segs = tripPending.toList(); tripPending.clear()
        val files = segs.map { it.file }
        if (segs.sumOf { it.durationMs } < 1500) { unprotect(files); return } // too short to keep
        val pieces = segs.map { EventExporter.Piece(it.file, 0, it.durationMs, it.startWall) }
        val start = segs.first().startWall
        val out = EventRepository.newTripFile(start)
        val thisTrip = tripId
        val showDateTime = settings.showDateTime
        pendingTripJobs++
        Log.i(LOG, "trip clip queued: ${segs.size} segments, ${segs.sumOf { it.durationMs }}ms final=$final")
        enqueueExport(ExportJob(isEvent = false) { done ->
            // If saving falls behind (slow device), skip burning for the backlog so the drive is never lost.
            val burn = settings.tripBurn && exportQueue.count { !it.isEvent } < 2
            val audioWav = audioWavFor(pieces)
            exporter.export(pieces, out, showDateTime, burn, audioWav) { result ->
                runCatching { audioWav?.delete() }
                unprotect(files)
                if (running) pruneBuffer() else cleanBuffer()
                val record = result?.let {
                    EventRecord(
                        id = UUID.randomUUID().toString(), filePath = it.file.absolutePath, name = null,
                        videoStartTime = it.videoStartWall, triggerTime = start, durationMs = it.durationMs,
                        trigger = Trigger.MANUAL, locked = false, sizeBytes = it.sizeBytes,
                        kind = RecordKind.TRIP, tripId = thisTrip,
                    )
                }
                Thread {
                    if (record != null) EventRepository.add(record) else out.delete()
                    main.post {
                        pendingTripJobs--
                        if (!running && pendingTripJobs == 0) { state.update { it.copy(backgroundSaving = false) }; maybeEnqueueScan() }
                        done()
                    }
                }.start()
            }
        })
    }

    private fun onExportFinished(record: EventRecord?, out: File?) {
        Thread {
            if (record != null) { EventRepository.add(record); readPlatesLater(record) } // thumbnail + auto-delete + plates
            main.post {
                state.update { it.copy(savingCount = (it.savingCount - 1).coerceAtLeast(0)) }
                if (record != null) {
                    Notifications.eventSaved(this, record)
                    DashcamController.lastSavedEventId.value = record.id
                    if (settings.voiceFeedback) voice.speak(getString(R.string.tts_saved))
                    logEvent("✓ אירוע נשמר • ${record.durationMs / 1000}שנ׳ • ${record.sizeBytes / 1048576}MB")
                } else {
                    out?.delete()
                    Notifications.eventFailed(this)
                    logEvent("✗ שמירת אירוע נכשלה")
                }
                maybeFinish()
            }
        }.start()
    }

    // ---------------------------------------------------------------- settings / triggers / overlay

    private fun applySettings(s: AppSettings) {
        if (!running) return
        applyProFeatures()
        if (s.shockDetection) shock.start(s.shockSensitivity) else shock.stop()
        main.removeCallbacks(metricsTick); main.post(metricsTick)
        onOrientationMaybeChanged()
        motion.sensitivity = s.smartSensitivity
        motion.enabled = s.smartDetection
        motion.aiWanted = isPro && s.proAi && !s.postDriveAiScan
        motion.batterySaver = s.batterySaver || thermalThrottle
        if (s.voiceFeedback || s.voiceCommands) voice.initTts()
        if (s.voiceCommands && hasAudioPermission()) voice.start(s.voiceStartPhrase, s.voiceStopPhrase) else voice.stop()
        if (appliedVideoKey != null && appliedVideoKey != videoKey(s) && !state.value.eventActive) requestRebind()
    }

    /** Resolution / fps / audio / dual-camera changes are held back during an event; apply them once it ends. */
    private fun applyPendingVideoChange() {
        if (running && appliedVideoKey != null && appliedVideoKey != videoKey(settings) && !state.value.eventActive) requestRebind()
    }

    private fun syncFloating() {
        val s = state.value
        if (running && s.driveActive && !appInForeground) floating.show(s.eventActive) else floating.hide()
        floating.setEventActive(s.eventActive)
    }

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** When the drive is over and its trips are saved, hand the AI a job to scan them (only while charging). */
    private fun maybeEnqueueScan() {
        if (!running && pendingTripJobs == 0 && settings.postDriveAiScan && isPro && settings.proAi) {
            logEvent("🤖 סריקת AI בסוף הנסיעה תוזמנה (תרוץ בטעינה)")
            runCatching { com.jeremy.dashcam.core.pro.PostDriveAiScan.enqueue(this, tripId, eventLog?.absolutePath) }
        }
    }
}
