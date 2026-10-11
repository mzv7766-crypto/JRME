package com.shomer.bodycam.cloud

import android.content.Context
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Shared WebRTC plumbing for live video (phone → control room). Peer-to-peer, signalled through Firestore;
 * public STUN servers find a direct route. Video only – the microphone stays with the evidence recording.
 */
object Rtc {
    private var factory: PeerConnectionFactory? = null
    val egl: EglBase by lazy { EglBase.create() }

    val iceServers = listOf(
        PeerConnection.IceServer.builder(listOf("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302")).createIceServer(),
    )

    @Synchronized
    fun factory(c: Context): PeerConnectionFactory {
        factory?.let { return it }
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(c.applicationContext).createInitializationOptions())
        val f = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        factory = f
        return f
    }

    fun config() = PeerConnection.RTCConfiguration(iceServers).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
    }

    fun candidateMap(c: IceCandidate, session: String) = mapOf(
        "session" to session, "candidate" to c.sdp, "sdpMid" to c.sdpMid, "sdpMLineIndex" to c.sdpMLineIndex,
    )

    fun candidateFrom(m: Map<String, Any?>): IceCandidate? {
        val sdp = m["candidate"] as? String ?: return null
        return IceCandidate(m["sdpMid"] as? String ?: "0", (m["sdpMLineIndex"] as? Number)?.toInt() ?: 0, sdp)
    }

    fun sdpMap(d: SessionDescription) = mapOf("type" to d.type.canonicalForm(), "sdp" to d.description)

    fun sdpFrom(m: Any?): SessionDescription? {
        @Suppress("UNCHECKED_CAST") val map = m as? Map<String, Any?> ?: return null
        val type = when (map["type"]) { "offer" -> SessionDescription.Type.OFFER; "answer" -> SessionDescription.Type.ANSWER; else -> return null }
        return SessionDescription(type, map["sdp"] as? String ?: return null)
    }

    // ---- coroutine wrappers for the callback-style SDP API ----

    suspend fun PeerConnection.setRemote(d: SessionDescription) = suspendCoroutine { cont ->
        setRemoteDescription(object : SdpAdapter() {
            override fun onSetSuccess() = cont.resume(Unit)
            override fun onSetFailure(e: String?) = cont.resumeWithException(IllegalStateException("setRemote: $e"))
        }, d)
    }

    suspend fun PeerConnection.setLocal(d: SessionDescription) = suspendCoroutine { cont ->
        setLocalDescription(object : SdpAdapter() {
            override fun onSetSuccess() = cont.resume(Unit)
            override fun onSetFailure(e: String?) = cont.resumeWithException(IllegalStateException("setLocal: $e"))
        }, d)
    }

    suspend fun PeerConnection.answer(): SessionDescription = suspendCoroutine { cont ->
        createAnswer(object : SdpAdapter() {
            override fun onCreateSuccess(d: SessionDescription?) = if (d != null) cont.resume(d) else cont.resumeWithException(IllegalStateException("no answer"))
            override fun onCreateFailure(e: String?) = cont.resumeWithException(IllegalStateException("createAnswer: $e"))
        }, org.webrtc.MediaConstraints())
    }

    suspend fun PeerConnection.offer(): SessionDescription = suspendCoroutine { cont ->
        createOffer(object : SdpAdapter() {
            override fun onCreateSuccess(d: SessionDescription?) = if (d != null) cont.resume(d) else cont.resumeWithException(IllegalStateException("no offer"))
            override fun onCreateFailure(e: String?) = cont.resumeWithException(IllegalStateException("createOffer: $e"))
        }, org.webrtc.MediaConstraints())
    }

    open class SdpAdapter : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(e: String?) = Unit
        override fun onSetFailure(e: String?) = Unit
    }

    open class PcObserver : PeerConnection.Observer {
        override fun onSignalingChange(s: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) = Unit
        override fun onIceConnectionReceivingChange(b: Boolean) = Unit
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) = Unit
        override fun onIceCandidate(c: IceCandidate?) = Unit
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) = Unit
        override fun onAddStream(s: org.webrtc.MediaStream?) = Unit
        override fun onRemoveStream(s: org.webrtc.MediaStream?) = Unit
        override fun onDataChannel(d: org.webrtc.DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(r: org.webrtc.RtpReceiver?, s: Array<out org.webrtc.MediaStream>?) = Unit
        override fun onTrack(t: org.webrtc.RtpTransceiver?) = Unit
    }
}
