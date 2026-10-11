package com.shomer.bodycam.cloud

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.ListenerRegistration
import com.shomer.bodycam.cloud.Rtc.offer
import com.shomer.bodycam.cloud.Rtc.setLocal
import com.shomer.bodycam.cloud.Rtc.setRemote
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RtpTransceiver
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.util.UUID

/** Manager's side of live video inside the app (the web dashboard does the same in the browser). */
class LiveViewer(private val context: Context, orgId: String, guard: String, private val sink: VideoSink) {
    enum class Status { CONNECTING, LIVE, ENDED, FAILED }

    val status = MutableStateFlow(Status.CONNECTING)
    private val doc = Cloud.org(orgId).collection("live").document(guard)
    private val session = UUID.randomUUID().toString()
    private var pc: PeerConnection? = null
    private var docL: ListenerRegistration? = null
    private var candL: ListenerRegistration? = null
    private var beat: Job? = null
    private var answered = false

    fun start() {
        Cloud.scope.launch {
            try {
                val f = Rtc.factory(context)
                val p = f.createPeerConnection(Rtc.config(), object : Rtc.PcObserver() {
                    override fun onIceCandidate(c: IceCandidate?) { if (c != null) doc.collection("vc").add(Rtc.candidateMap(c, session)) }
                    override fun onTrack(t: RtpTransceiver?) {
                        (t?.receiver?.track() as? VideoTrack)?.addSink(sink)
                    }
                    override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                        when (s) {
                            PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> status.value = Status.LIVE
                            PeerConnection.IceConnectionState.FAILED -> status.value = Status.FAILED
                            else -> Unit
                        }
                    }
                }) ?: throw IllegalStateException("no peer connection")
                pc = p
                p.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
                val o = p.offer()
                p.setLocal(o)
                doc.set(mapOf("state" to "request", "session" to session, "offer" to Rtc.sdpMap(o), "viewer" to Cloud.email(), "beat" to System.currentTimeMillis())).await()
                docL = doc.addSnapshotListener { s, _ ->
                    if (s == null || s.getString("session") != session) return@addSnapshotListener
                    when (s.getString("state")) {
                        "answer" -> if (!answered) {
                            answered = true
                            Rtc.sdpFrom(s.get("answer"))?.let { a -> Cloud.scope.launch { runCatching { pc?.setRemote(a) }.onFailure { status.value = Status.FAILED } } }
                        }
                        "end" -> { status.value = Status.ENDED; stop(write = false) }
                    }
                }
                candL = doc.collection("dc").whereEqualTo("session", session).addSnapshotListener { qs, _ ->
                    qs?.documentChanges?.forEach { ch -> Rtc.candidateFrom(ch.document.data)?.let { pc?.addIceCandidate(it) } }
                }
                beat = Cloud.scope.launch { while (true) { delay(5_000); doc.update("beat", System.currentTimeMillis()) } }
                // no answer in 20 s → the phone is offline or the app was closed by the system
                Cloud.scope.launch { delay(20_000); if (!answered && status.value == Status.CONNECTING) { status.value = Status.FAILED; stop(write = true) } }
            } catch (e: Exception) {
                Log.w(Cloud.TAG, "live view", e)
                status.value = Status.FAILED
            }
        }
    }

    fun stop(write: Boolean = true) {
        beat?.cancel(); beat = null
        docL?.remove(); docL = null
        candL?.remove(); candL = null
        runCatching { pc?.dispose() }; pc = null
        if (write) doc.update(mapOf("state" to "end", "endedBy" to "viewer"))
    }
}
