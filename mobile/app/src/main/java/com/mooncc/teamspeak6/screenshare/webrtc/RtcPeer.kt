package com.mooncc.teamspeak6.screenshare.webrtc

import android.util.Log
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.VideoTrack

/**
 * Callbacks a [RtcPeer] raises. All fire on WebRTC's signaling thread.
 */
internal interface RtcPeerEvents {
    fun onLocalCandidate(candidate: IceCandidate)
    fun onRemoteVideoTrack(track: VideoTrack)
    fun onConnected()

    /** Terminal: the peer connection failed or was closed by the remote end. */
    fun onClosed(reason: String)
}

/**
 * One peer connection, either publishing or viewing a single screen share.
 *
 * Remote ICE candidates that arrive before the remote description is set are
 * buffered — the signaling server does not order `offer` and `candidate`
 * relative to each other, and adding a candidate too early is a hard error in
 * libwebrtc.
 */
internal class RtcPeer(
    private val core: WebRtcCore,
    rtcConfig: PeerConnection.RTCConfiguration,
    private val events: RtcPeerEvents,
) {

    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()

    @Volatile
    private var remoteDescriptionSet = false

    @Volatile
    private var closed = false

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            if (!closed) events.onLocalCandidate(candidate)
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> events.onConnected()
                PeerConnection.PeerConnectionState.FAILED -> events.onClosed("连接失败")
                PeerConnection.PeerConnectionState.CLOSED -> events.onClosed("连接已关闭")
                else -> Unit
            }
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            val track = transceiver.receiver?.track()
            if (track is VideoTrack && track.kind() == MediaStreamTrack.VIDEO_TRACK_KIND) {
                events.onRemoteVideoTrack(track)
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
    }

    private val connection: PeerConnection = requireNotNull(
        core.factory.createPeerConnection(rtcConfig, observer),
    ) { "createPeerConnection returned null" }

    /** Adds the local screen track. Publisher side only. */
    fun addVideoTrack(track: VideoTrack) {
        connection.addTrack(track, listOf(WebRtcCore.STREAM_ID))
    }

    /** Declares this peer as receive-only. Viewer side only. */
    fun addReceiveOnlyVideo() {
        connection.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
        )
    }

    fun addReceiveOnlyAudio() {
        connection.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
        )
    }

    suspend fun createOffer(bitrateKbps: Int, preferredCodec: String): String {
        val offer = connection.createOfferSuspend()
        val munged = munge(offer.description, bitrateKbps, preferredCodec)
        connection.setLocalDescriptionSuspend(
            org.webrtc.SessionDescription(offer.type, munged),
        )
        return munged
    }

    suspend fun createAnswer(bitrateKbps: Int, preferredCodec: String): String {
        val answer = connection.createAnswerSuspend()
        val munged = munge(answer.description, bitrateKbps, preferredCodec)
        connection.setLocalDescriptionSuspend(
            org.webrtc.SessionDescription(answer.type, munged),
        )
        return munged
    }

    suspend fun acceptOffer(sdp: String) = acceptRemote(
        org.webrtc.SessionDescription(org.webrtc.SessionDescription.Type.OFFER, sdp),
    )

    suspend fun acceptAnswer(sdp: String) = acceptRemote(
        org.webrtc.SessionDescription(org.webrtc.SessionDescription.Type.ANSWER, sdp),
    )

    private suspend fun acceptRemote(description: org.webrtc.SessionDescription) {
        connection.setRemoteDescriptionSuspend(description)
        remoteDescriptionSet = true
        val buffered = synchronized(pendingRemoteCandidates) {
            pendingRemoteCandidates.toList().also { pendingRemoteCandidates.clear() }
        }
        buffered.forEach { connection.addIceCandidate(it) }
    }

    fun addRemoteCandidate(candidate: IceCandidate) {
        if (closed) return
        if (!remoteDescriptionSet) {
            synchronized(pendingRemoteCandidates) { pendingRemoteCandidates += candidate }
            return
        }
        connection.addIceCandidate(candidate)
    }

    /**
     * Caps the outgoing video bitrate. Applies to senders that already exist, so
     * call this after the track has been added.
     */
    fun applySenderBitrate(bitrateKbps: Int) {
        if (bitrateKbps <= 0) return
        connection.senders
            .filter { it.track()?.kind() == MediaStreamTrack.VIDEO_TRACK_KIND }
            .forEach { sender ->
                val params = sender.parameters ?: return@forEach
                params.encodings.forEach { encoding ->
                    encoding.maxBitrateBps = bitrateKbps * 1000
                }
                if (!sender.setParameters(params)) {
                    Log.w(TAG, "setParameters rejected, relying on b=AS")
                }
            }
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { connection.close() }
        runCatching { connection.dispose() }
    }

    private fun munge(sdp: String, bitrateKbps: Int, preferredCodec: String): String {
        val preferred = SdpTransform.preferVideoCodec(sdp, preferredCodec)
        return SdpTransform.applyVideoBitrate(preferred, bitrateKbps)
    }

    private companion object {
        const val TAG = "RtcPeer"
    }
}
