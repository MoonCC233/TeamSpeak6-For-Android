package com.mooncc.teamspeak9.screenshare.webrtc

import android.content.Context
import com.mooncc.teamspeak9.screenshare.signaling.IceServerConfig
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory

/**
 * Owns the process-wide WebRTC objects.
 *
 * [PeerConnectionFactory.initialize] must run exactly once per process and the
 * factory plus its [EglBase] are expensive, so both are created lazily here and
 * shared by every screen-share session.
 */
class WebRtcCore(private val context: Context) {

    val eglBase: EglBase by lazy { EglBase.create() }

    val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions(),
        )
        PeerConnectionFactory.builder()
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(
                    eglBase.eglBaseContext,
                    /* enableIntelVp8Encoder = */ true,
                    /* enableH264HighProfile = */ false,
                ),
            )
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    /**
     * Builds the RTC configuration used for every session.
     *
     * Unified Plan is required; falling back to Plan B would break interop with
     * browser and desktop implementations of the protocol. DTLS-SRTP is always on
     * in this WebRTC build, so there is no switch for it.
     */
    fun rtcConfig(iceServers: List<IceServerConfig>): PeerConnection.RTCConfiguration {
        val servers = iceServers.map { config ->
            PeerConnection.IceServer.builder(config.urls)
                .setUsername(config.username.orEmpty())
                .setPassword(config.credential.orEmpty())
                .createIceServer()
        }
        return PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            keyType = PeerConnection.KeyType.ECDSA
        }
    }

    fun release() {
        runCatching { factory.dispose() }
        runCatching { eglBase.release() }
    }

    companion object {
        /** Stream id both ends agree on, per `docs/screenshare-protocol.md` §7. */
        const val STREAM_ID = "screen"
        const val VIDEO_TRACK_ID = "screen-video"
    }
}
