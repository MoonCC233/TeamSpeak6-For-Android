package com.mooncc.teamspeak6.screenshare.signaling

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Wire format of the screen-share signaling protocol.
 *
 * See `docs/screenshare-protocol.md`. This is our own protocol, not TeamSpeak's
 * — it only interoperates with other clients implementing the same document.
 */
object SignalingProtocol {
    const val VERSION = 1

    /** Placeholder peer id used as `to` when talking to the SFU itself. */
    const val SFU_PEER = "sfu"
}

/** How a share is transported. */
@Serializable
enum class ShareMode {
    @SerialName("p2p")
    P2P,

    @SerialName("sfu")
    SFU,
}

/** Who is allowed to watch a share. */
@Serializable
enum class SharePrivacy {
    @SerialName("public")
    PUBLIC,

    @SerialName("contacts")
    CONTACTS,

    @SerialName("private")
    PRIVATE,
}

@Serializable
data class VideoParams(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int,
)

@Serializable
data class AudioParams(
    val bitrateKbps: Int,
)

@Serializable
data class PeerCapabilities(
    val canPublish: Boolean = true,
    val canSubscribe: Boolean = true,
    val codecs: List<String> = listOf("H264", "VP8"),
)

@Serializable
data class PeerInfo(
    val peerId: String,
    val clientUid: String = "",
    val tsClientId: Int = 0,
    val nickname: String = "",
)

@Serializable
data class ShareInfo(
    val publisherId: String,
    val nickname: String = "",
    val mode: ShareMode = ShareMode.P2P,
    val hasAudio: Boolean = false,
    val video: VideoParams? = null,
    val audio: AudioParams? = null,
)

@Serializable
data class IceServerConfig(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)

/**
 * Messages the client sends. `v` is written on every message so a server can
 * reject an unsupported protocol version on the first frame.
 */
@Serializable
@JsonClassDiscriminator("type")
sealed interface ClientMessage {
    val v: Int

    @Serializable
    @SerialName("hello")
    data class Hello(
        val roomId: String,
        val clientUid: String,
        val tsClientId: Int,
        val nickname: String,
        val capabilities: PeerCapabilities = PeerCapabilities(),
        override val v: Int = SignalingProtocol.VERSION,
    ) : ClientMessage

    @Serializable
    @SerialName("announce")
    data class Announce(
        val mode: ShareMode,
        val privacy: SharePrivacy = SharePrivacy.PUBLIC,
        val hasAudio: Boolean = false,
        val video: VideoParams,
        val audio: AudioParams? = null,
        val allowedUids: List<String> = emptyList(),
        val viewerLimit: Int = 0,
        override val v: Int = SignalingProtocol.VERSION,
    ) : ClientMessage

    @Serializable
    @SerialName("unannounce")
    data class Unannounce(
        override val v: Int = SignalingProtocol.VERSION,
    ) : ClientMessage

    @Serializable
    @SerialName("watch")
    data class Watch(
        val publisherId: String,
        override val v: Int = SignalingProtocol.VERSION,
    ) : ClientMessage

    @Serializable
    @SerialName("unwatch")
    data class Unwatch(
        val publisherId: String,
        override val v: Int = SignalingProtocol.VERSION,
    ) : ClientMessage

    @Serializable
    @SerialName("offer")
    data class Offer(
        val to: String,
        val sdp: String,
        val streamId: String = "screen",
        override val v: Int = SignalingProtocol.VERSION,
    ) : ClientMessage

    @Serializable
    @SerialName("answer")
    data class Answer(
        val to: String,
        val sdp: String,
        override val v: Int = SignalingProtocol.VERSION,
    ) : ClientMessage

    @Serializable
    @SerialName("candidate")
    data class Candidate(
        val to: String,
        val candidate: String,
        val sdpMid: String,
        val sdpMLineIndex: Int,
        override val v: Int = SignalingProtocol.VERSION,
    ) : ClientMessage

    @Serializable
    @SerialName("bye")
    data class Bye(
        val to: String,
        val reason: String = "",
        override val v: Int = SignalingProtocol.VERSION,
    ) : ClientMessage

    @Serializable
    @SerialName("leave")
    data class Leave(
        override val v: Int = SignalingProtocol.VERSION,
    ) : ClientMessage

    @Serializable
    @SerialName("ping")
    data class Ping(
        val nonce: Long,
        override val v: Int = SignalingProtocol.VERSION,
    ) : ClientMessage
}

/**
 * Messages the server sends.
 *
 * Unknown types must be ignored rather than treated as an error so the protocol
 * can grow without breaking older clients; [SignalingCodec] maps them to
 * [ServerMessage.Unknown].
 */
@Serializable
@JsonClassDiscriminator("type")
sealed interface ServerMessage {

    @Serializable
    @SerialName("welcome")
    data class Welcome(
        val peerId: String,
        val roomId: String,
        val sfuAvailable: Boolean = false,
        val iceServers: List<IceServerConfig> = emptyList(),
        val peers: List<PeerInfo> = emptyList(),
        val shares: List<ShareInfo> = emptyList(),
    ) : ServerMessage

    @Serializable
    @SerialName("peer-joined")
    data class PeerJoined(val peer: PeerInfo) : ServerMessage

    @Serializable
    @SerialName("peer-left")
    data class PeerLeft(val peerId: String) : ServerMessage

    @Serializable
    @SerialName("share-started")
    data class ShareStarted(val share: ShareInfo) : ServerMessage

    @Serializable
    @SerialName("share-stopped")
    data class ShareStopped(val publisherId: String) : ServerMessage

    @Serializable
    @SerialName("watch-request")
    data class WatchRequest(
        val from: String,
        val nickname: String = "",
        val clientUid: String = "",
    ) : ServerMessage

    @Serializable
    @SerialName("offer")
    data class Offer(
        val from: String,
        val sdp: String,
        val streamId: String = "screen",
    ) : ServerMessage

    @Serializable
    @SerialName("answer")
    data class Answer(
        val from: String,
        val sdp: String,
    ) : ServerMessage

    @Serializable
    @SerialName("candidate")
    data class Candidate(
        val from: String,
        val candidate: String,
        val sdpMid: String = "",
        val sdpMLineIndex: Int = 0,
    ) : ServerMessage

    @Serializable
    @SerialName("bye")
    data class Bye(
        val from: String,
        val reason: String = "",
    ) : ServerMessage

    @Serializable
    @SerialName("error")
    data class Error(
        val code: String = "",
        val message: String = "",
        val fatal: Boolean = false,
    ) : ServerMessage

    @Serializable
    @SerialName("pong")
    data class Pong(val nonce: Long = 0) : ServerMessage

    /** A message this build does not know about. Never sent, only produced locally. */
    @Serializable
    @SerialName("__unknown")
    data class Unknown(val type: String, val raw: String) : ServerMessage
}
