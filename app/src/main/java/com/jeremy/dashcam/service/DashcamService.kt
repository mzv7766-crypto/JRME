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
        private const val SEGMENT_MS = 10_000L
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
    private var targetRotation = Surface.ROTATION_0

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

    private fun enqueueExport(job: ExportJob) {
        if (job.isEvent) {
            val idx = exportQueue.indexOfFirst { !it.isEvent }
            if (idx < 0) exportQueue.addLast(job) else exportQueue.add(idx, job)
        } else exportQueue.addLast(job)
        pumpExports()
    }

    private fun pumpExports() {
        if (exportBusy) return
        val job = exportQueue.removeFirstOrNull() ?: return
        exportBusy = true
        job.run { main.post { exportBusy = false; pumpExports(); maybeFinish() } }
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
        shock = ShockDetector(this) { d -> main.post { onImpact(d) } }
        motion = MotionAnalyzer { f -> main.post { onVision(f) } }
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
        tripId = now; tripPending.clear(); tripClipStart = 0L

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                targetRotation = when (o) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
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
        running = false
        driveJobs.forEach { it.cancel() }; driveJobs.clear()
        main.removeCallbacks(rotateSegment); main.removeCallbacks(startSegmentRunnable)
        shock.stop(); motion.enabled = false; voice.stop()
        main.removeCallbacks(metricsTick); DashcamController.metrics.value = null
        floating.hide()
        orientationListener?.disable(); orientationListener = null
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processObserver)
        val rec = activeRecording
        if (rec != null) {
            finalizeCallbacks += { unbindCamera(); flushTrip(final = true); cleanBuffer() }
            rec.stop()
        } else {
            unbindCamera(); flushTrip(final = true); cleanBuffer()
        }
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

    private fun videoKey(s: AppSettings) = "${s.resolution}/${s.fps}/${s.recordAudio}/${state.value.lensFacing}"

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

        val quality = when (s.resolution) { Resolution.HD -> Quality.HD; Resolution.FHD -> Quality.FHD; Resolution.UHD -> Quality.UHD }
        val qualitySelector = QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))

        fun buildVideo(withFps: Boolean): VideoCapture<Recorder> {
            val recorder = Recorder.Builder().setQualitySelector(qualitySelector).build()
            return VideoCapture.Builder(recorder).apply {
                if (withFps) setTargetFrameRate(Range(s.fps, s.fps))
            }.build().also { it.targetRotation = targetRotation }
        }

        val newPreview = Preview.Builder().build()
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                ).build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        val exec = analysisExecutor ?: Executors.newSingleThreadExecutor().also { analysisExecutor = it }
        analysis.setAnalyzer(exec, motion)

        // Try the richest configuration first, then degrade gracefully instead of crashing.
        val attempts: List<Pair<Boolean, Boolean>> = listOf(true to true, false to true, true to false, false to false) // fps, analysis
        var bound: VideoCapture<Recorder>? = null
        var lastError: Exception? = null
        for ((fps, withAnalysis) in attempts) {
            val vc = buildVideo(fps)
            val cases = mutableListOf<UseCase>(newPreview, vc)
            if (withAnalysis) cases += analysis
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
        Log.e(TAG, "camera error", e)
        state.update { it.copy(error = getString(R.string.camera_error, e.message ?: e.javaClass.simpleName)) }
    }

    // ---------------------------------------------------------------- rolling buffer

    @SuppressLint("MissingPermission")
    private fun startSegment() {
        if (!running || activeRecording != null) return
        val vc = videoCapture ?: return
        if (!state.value.eventActive) vc.targetRotation = targetRotation // keep one orientation inside an event
        val file = File(bufferDir, "seg_${System.currentTimeMillis()}.mp4")
        try {
            var pending = vc.output.prepareRecording(this, FileOutputOptions.Builder(file).build())
            if (settings.recordAudio && hasAudioPermission()) pending = pending.withAudioEnabled()
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
    }

    private fun cleanBuffer() {
        val it = segments.iterator()
        while (it.hasNext()) { val seg = it.next(); if (!isProtected(seg.file)) { seg.file.delete(); it.remove() } }
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
    private fun onImpact(d: ImpactAlgorithm.Detection) {
        if (!running || state.value.eventActive || !settings.shockDetection) return
        val trigger = if (d.type == ImpactAlgorithm.Type.IMPACT) Trigger.SHOCK else Trigger.HARSH
        startEvent(trigger, d.peakG)
    }

    private fun onVision(f: VisionAlgorithm.Frame) {
        if (!running || state.value.eventActive || !settings.smartDetection) return
        val now = SystemClock.elapsedRealtime()
        if (f.candidate) lastVisionCandidateAt = now
        if (!f.triggered) return
        val confirmedBySensors = settings.shockDetection && shock.algorithm.hadBumpNear(now, 1500)
        val sensorsUnavailable = !settings.shockDetection
        if (f.strong || confirmedBySensors || sensorsUnavailable) startEvent(Trigger.MOTION, if (confirmedBySensors) shock.algorithm.lastBumpG else null)
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
        state.update { it.copy(eventActive = true, eventStartTime = System.currentTimeMillis(), eventTrigger = trigger, eventPeakG = peakG) }
        Log.i(LOG, "event start trigger=$trigger")
        if (settings.voiceFeedback) voice.speak(getString(R.string.tts_started))
    }

    /** Ends the running event WITHOUT saving a video. Recording to the rolling buffer continues. */
    fun discardEvent() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { discardEvent() }; return }
        if (!state.value.eventActive) return
        state.update { it.copy(eventActive = false, eventTrigger = null) }
        Log.i(LOG, "event discarded")
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
        val preMs = settings.preEventSeconds * 1000L
        val showDateTime = settings.showDateTime
        state.update { it.copy(eventActive = false, eventTrigger = null, savingCount = it.savingCount + 1) }

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
                    exporter.export(pieces, out, showDateTime) { result ->
                        unprotect(chosen.map { it.file })
                        if (running) pruneBuffer() else cleanBuffer()
                        val record = result?.let {
                            EventRecord(
                                id = UUID.randomUUID().toString(), filePath = it.file.absolutePath, name = null,
                                videoStartTime = it.videoStartWall, triggerTime = eventStart, durationMs = it.durationMs,
                                trigger = trigger, locked = false, sizeBytes = it.sizeBytes, peakG = peakG,
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
            exporter.export(pieces, out, showDateTime, burn) { result ->
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
                        if (!running && pendingTripJobs == 0) state.update { it.copy(backgroundSaving = false) }
                        done()
                    }
                }.start()
            }
        })
    }

    private fun onExportFinished(record: EventRecord?, out: File?) {
        Thread {
            if (record != null) EventRepository.add(record) // also creates thumbnail + applies auto-delete
            main.post {
                state.update { it.copy(savingCount = (it.savingCount - 1).coerceAtLeast(0)) }
                if (record != null) {
                    Notifications.eventSaved(this, record)
                    DashcamController.lastSavedEventId.value = record.id
                    if (settings.voiceFeedback) voice.speak(getString(R.string.tts_saved))
                } else {
                    out?.delete()
                    Notifications.eventFailed(this)
                }
                maybeFinish()
            }
        }.start()
    }

    // ---------------------------------------------------------------- settings / triggers / overlay

    private fun applySettings(s: AppSettings) {
        if (!running) return
        if (s.shockDetection) shock.start(s.shockSensitivity) else shock.stop()
        main.removeCallbacks(metricsTick); main.post(metricsTick)
        motion.sensitivity = s.smartSensitivity
        motion.enabled = s.smartDetection
        if (s.voiceFeedback || s.voiceCommands) voice.initTts()
        if (s.voiceCommands && hasAudioPermission()) voice.start(s.voiceStartPhrase, s.voiceStopPhrase) else voice.stop()
        if (appliedVideoKey != null && appliedVideoKey != videoKey(s) && !state.value.eventActive) requestRebind()
    }

    private fun syncFloating() {
        val s = state.value
        if (running && s.driveActive && !appInForeground) floating.show(s.eventActive) else floating.hide()
        floating.setEventActive(s.eventActive)
    }

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}
