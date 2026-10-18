package com.shomer.bodycam.cloud

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.ListenerRegistration
import com.shomer.bodycam.cloud.Rtc.answer
import com.shomer.bodycam.cloud.Rtc.setLocal
import com.shomer.bodycam.cloud.Rtc.setRemote
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.webrtc.CapturerObserver
import org.webrtc.IceCandidate
import org.webrtc.JavaI420Buffer
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RtpTransceiver
import org.webrtc.VideoFrame
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.nio.ByteBuffer

/**
 * The guard's side of live video. The control room writes an offer into live/{email}; this answers it and
 * sends the camera picture (taken from CameraX analysis frames, so the evidence recording is never touched).
 * One viewer at a time; the session ends when the viewer leaves or stops sending its heartbeat.
 */
object LiveSender {
    private const val TAG = "ShomerLive"
    private const val MAX_FPS = 15
    private const val ECO_FPS = 8

    /** Power saving: fewer live frames. */
    @Volatile var eco = false

    /** E-mail of the person watching right now (shown to the guard), null when nobody is. */
    val watcher = MutableStateFlow<String?>(null)

    private var app: Context? = null
    private var doc: DocumentReference? = null
    private var docListener: ListenerRegistration? = null
    private var candListener: ListenerRegistration? = null
    private var pc: PeerConnection? = null
    private var source: VideoSource? = null
    private var track: VideoTrack? = null
    @Volatile private var observer: CapturerObserver? = null
    private var session: String? = null
    private var lastBeat = 0L
    private var watchdog: Job? = null
    @Volatile private var lastFrameAt = 0L

    /** True while someone is watching → the camera service feeds analysis frames. */
    @Volatile var wantsFrames = false; private set

    fun start(c: Context, orgId: String, email: String) {
        stop()
        app = c.applicationContext
        val d = Cloud.org(orgId).collection("live").document(email)
        doc = d
        docListener = d.addSnapshotListener { s, e -> if (e == null && s != null) onDoc(s) }
        watchdog = Cloud.scope.launch {
            while (true) {
                delay(5_000)
                if (session != null && System.currentTimeMillis() - lastBeat > 25_000) { Log.i(TAG, "viewer gone"); endSession(write = true) }
            }
        }
    }

    fun stop() {
        watchdog?.cancel(); watchdog = null
        docListener?.remove(); docListener = null
        endSession(write = session != null)
        doc = null
    }

    private fun onDoc(s: DocumentSnapshot) {
        val state = s.getString("state")
        val id = s.getString("session")
        lastBeat = s.getLong("beat") ?: lastBeat
        when {
            state == "request" && id != null && id != session -> {
                val offer = Rtc.sdpFrom(s.get("offer")) ?: return
                Cloud.scope.launch { answer(id, offer, s.getString("viewer")) }
            }
            state == "end" && id == session -> endSession(write = false)
        }
    }

    private suspend fun answer(id: String, offer: org.webrtc.SessionDescription, viewer: String?) {
        val c = app ?: return
        val d = doc ?: return
        endSession(write = false)
        session = id
        lastBeat = System.currentTimeMillis()
        try {
            val f = Rtc.factory(c)
            val src = f.createVideoSource(false).also { source = it }
            observer = src.capturerObserver.also { it.onCapturerStarted(true) }
            val t = f.createVideoTrack("shomer-cam", src).also { track = it }
            val p = f.createPeerConnection(Rtc.config(), object : Rtc.PcObserver() {
                override fun onIceCandidate(cand: IceCandidate?) {
                    if (cand != null && session == id) d.collection("dc").add(Rtc.candidateMap(cand, id))
                }
                override fun onIceConnectionChange(st: PeerConnection.IceConnectionState?) {
                    Log.i(TAG, "ice: $st")
                    if (st == PeerConnection.IceConnectionState.FAILED || st == PeerConnection.IceConnectionState.CLOSED) {
                        Cloud.scope.launch { if (session == id) endSession(write = true) }
                    }
                }
            }) ?: throw IllegalStateException("no peer connection")
            pc = p
            p.setRemote(offer)
            // the viewer asked for "receive video only": attach our camera track to that transceiver
            val tr = p.transceivers.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }
            if (tr != null) { tr.sender.setTrack(t, false); tr.direction = RtpTransceiver.RtpTransceiverDirection.SEND_ONLY }
            else p.addTrack(t, listOf("shomer"))
            val ans = p.answer()
            p.setLocal(ans)
            d.update(mapOf("state" to "answer", "answer" to Rtc.sdpMap(ans), "answeredAt" to System.currentTimeMillis()))
            candListener = d.collection("vc").whereEqualTo("session", id).addSnapshotListener { qs, _ ->
                qs?.documentChanges?.forEach { ch ->
                    Rtc.candidateFrom(ch.document.data)?.let { pc?.addIceCandidate(it) }
                }
            }
            wantsFrames = true
            watcher.value = viewer ?: "?"
            Presence.onLiveChanged(true, viewer)
        } catch (e: Exception) {
            Log.w(TAG, "answer failed", e)
            endSession(write = true)
        }
    }

    private fun endSession(write: Boolean) {
        val was = session
        wantsFrames = false
        candListener?.remove(); candListener = null
        runCatching { pc?.dispose() }; pc = null
        runCatching { observer?.onCapturerStopped() }; observer = null
        runCatching { track?.dispose() }; track = null
        runCatching { source?.dispose() }; source = null
        session = null
        if (was != null) {
            if (write) doc?.update(mapOf("state" to "end", "endedBy" to "device"))
            watcher.value = null
            Presence.onLiveChanged(false, null)
        }
    }

    /** Called on the camera analysis thread. Always closes the image. */
    fun onFrame(img: ImageProxy) {
        try {
            val obs = observer ?: return
            if (!wantsFrames) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameAt < 1000L / (if (eco) ECO_FPS else MAX_FPS)) return
            lastFrameAt = now
            val buffer = toI420(img)
            val frame = VideoFrame(buffer, img.imageInfo.rotationDegrees, SystemClock.elapsedRealtimeNanos())
            obs.onFrameCaptured(frame)
            frame.release()
        } catch (e: Exception) {
            Log.w(TAG, "frame", e)
        } finally {
            img.close()
        }
    }

    private var row = ByteArray(0)

    /** YUV_420_888 (any strides / pixel strides) → I420. */
    fun toI420(img: ImageProxy): JavaI420Buffer {
        val w = img.width; val h = img.height
        val out = JavaI420Buffer.allocate(w, h)
        val p = img.planes
        copyPlane(p[0].buffer, p[0].rowStride, p[0].pixelStride, w, h, out.dataY, out.strideY)
        copyPlane(p[1].buffer, p[1].rowStride, p[1].pixelStride, (w + 1) / 2, (h + 1) / 2, out.dataU, out.strideU)
        copyPlane(p[2].buffer, p[2].rowStride, p[2].pixelStride, (w + 1) / 2, (h + 1) / 2, out.dataV, out.strideV)
        return out
    }

    private fun copyPlane(src: ByteBuffer, rowStride: Int, pixelStride: Int, w: Int, h: Int, dst: ByteBuffer, dstStride: Int) {
        val need = (w - 1) * pixelStride + 1
        if (row.size < need) row = ByteArray(need)
        for (y in 0 until h) {
            val start = y * rowStride
            if (start + need > src.limit()) break
            src.position(start)
            src.get(row, 0, need)
            dst.position(y * dstStride)
            if (pixelStride == 1) dst.put(row, 0, w)
            else for (x in 0 until w) dst.put(row[x * pixelStride])
        }
        src.rewind(); dst.rewind()
    }
}
