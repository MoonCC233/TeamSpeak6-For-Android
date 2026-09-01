package com.mooncc.teamspeak6.screenshare

import android.content.Context
import android.content.Intent
import android.util.Log
import com.mooncc.teamspeak6.di.ApplicationScope
import com.mooncc.teamspeak6.di.SignalingHttpClient
import com.mooncc.teamspeak6.domain.model.RemoteShare
import com.mooncc.teamspeak6.domain.model.ScreenShareConfig
import com.mooncc.teamspeak6.domain.model.ScreenShareMode
import com.mooncc.teamspeak6.domain.model.ScreenSharePrivacy
import com.mooncc.teamspeak6.domain.model.ScreenShareResolution
import com.mooncc.teamspeak6.domain.model.ScreenShareSignalingState
import com.mooncc.teamspeak6.domain.model.ScreenShareState
import com.mooncc.teamspeak6.domain.model.ViewerRequest
import com.mooncc.teamspeak6.screenshare.service.ScreenShareService
import com.mooncc.teamspeak6.screenshare.signaling.ClientMessage
import com.mooncc.teamspeak6.screenshare.signaling.IceServerConfig
import com.mooncc.teamspeak6.screenshare.signaling.RoomId
import com.mooncc.teamspeak6.screenshare.signaling.ServerMessage
import com.mooncc.teamspeak6.screenshare.signaling.ShareInfo
import com.mooncc.teamspeak6.screenshare.signaling.ShareMode
import com.mooncc.teamspeak6.screenshare.signaling.SharePrivacy
import com.mooncc.teamspeak6.screenshare.signaling.SignalingClient
import com.mooncc.teamspeak6.screenshare.signaling.SignalingProtocol
import com.mooncc.teamspeak6.screenshare.signaling.SignalingStatus
import com.mooncc.teamspeak6.screenshare.signaling.VideoParams
import com.mooncc.teamspeak6.screenshare.signaling.AudioParams
import com.mooncc.teamspeak6.screenshare.webrtc.RtcPeer
import com.mooncc.teamspeak6.screenshare.webrtc.RtcPeerEvents
import com.mooncc.teamspeak6.screenshare.webrtc.ScreenCaptureSource
import com.mooncc.teamspeak6.screenshare.webrtc.WebRtcCore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.webrtc.IceCandidate
import org.webrtc.VideoTrack

/**
 * Drives screen sharing: the signaling socket, one peer connection per viewer or
 * per watched share, and the local [ScreenCaptureSource].
 *
 * This implements our own protocol (`docs/screenshare-protocol.md`), so it only
 * interoperates with other clients built against that document — not with the
 * official TeamSpeak desktop client. Voice is unaffected and stays fully
 * interoperable.
 *
 * Room membership follows the TeamSpeak channel: [enterRoom] is called again on
 * every channel change, which tears down all sessions and re-dials.
 */
@Singleton
class ScreenShareManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @SignalingHttpClient httpClient: OkHttpClient,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val core = WebRtcCore(context)
    private val capture = ScreenCaptureSource(context, core)
    private val signaling = SignalingClient(httpClient, scope)

    private val _state = MutableStateFlow(ScreenShareState())
    val state: StateFlow<ScreenShareState> = _state.asStateFlow()

    /** Remote tracks handed to the UI for rendering, keyed by publisher id. */
    private val _remoteTracks = MutableStateFlow<Map<String, VideoTrack>>(emptyMap())
    val remoteTracks: StateFlow<Map<String, VideoTrack>> = _remoteTracks.asStateFlow()

    /** The local preview track while sharing, null otherwise. */
    private val _localTrack = MutableStateFlow<VideoTrack?>(null)
    val localTrack: StateFlow<VideoTrack?> = _localTrack.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Emitted when the UI must show the MediaProjection consent dialog. */
    private val _permissionRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val permissionRequests: SharedFlow<Unit> = _permissionRequests.asSharedFlow()

    val eglBaseContext get() = core.eglBase.eglBaseContext

    /** Peer connections we publish to, keyed by viewer peer id (p2p) or [SFU_KEY]. */
    private val publishPeers = mutableMapOf<String, RtcPeer>()

    /** Peer connections we watch through, keyed by publisher peer id. */
    private val viewPeers = mutableMapOf<String, RtcPeer>()

    private val peerLock = Mutex()

    private var iceServers: List<IceServerConfig> = emptyList()
    private var localPeerId: String = ""
    private var roomId: String = ""
    private var nickname: String = ""
    private var clientUid: String = ""
    private var tsClientId: Int = 0
    private var permissionIntent: Intent? = null
    private var collectJob: Job? = null

    /**
     * Joins the room for a TeamSpeak channel.
     *
     * Any share or view in progress is stopped first: the signaling server scopes
     * peers to a room, so sessions cannot survive the move.
     */
    fun enterRoom(
        signalingUrl: String,
        serverUid: String,
        channelId: Int,
        clientUid: String,
        tsClientId: Int,
        nickname: String,
    ) {
        val normalizedUrl = normalizeSignalUrl(signalingUrl)
        if (normalizedUrl.isBlank()) {
            leaveRoom()
            _state.value = _state.value.copy(
                signalingUrl = "",
                signaling = ScreenShareSignalingState.OFFLINE,
            )
            return
        }

        scope.launch { stopEverything() }

        this.clientUid = clientUid
        this.tsClientId = tsClientId
        this.nickname = nickname
        roomId = RoomId.forChannel(serverUid, channelId)

        _state.value = _state.value.copy(
            signalingUrl = normalizedUrl,
            signaling = ScreenShareSignalingState.CONNECTING,
            remoteShares = emptyList(),
            errorMessage = null,
        )

        observeSignaling()
        signaling.connect(
            url = normalizedUrl,
            hello = ClientMessage.Hello(
                roomId = roomId,
                clientUid = clientUid,
                tsClientId = tsClientId,
                nickname = nickname,
            ),
        )
    }

    fun leaveRoom() {
        scope.launch { stopEverything() }
        collectJob?.cancel()
        collectJob = null
        signaling.disconnect()
        _state.value = _state.value.copy(
            signaling = ScreenShareSignalingState.OFFLINE,
            remoteShares = emptyList(),
            viewerCount = 0,
            pendingViewerRequests = emptyList(),
            isSharing = false,
        )
    }

    private fun observeSignaling() {
        collectJob?.cancel()
        collectJob = scope.launch {
            launch {
                signaling.status.collect { status ->
                    _state.value = _state.value.copy(
                        signaling = when (status) {
                            SignalingStatus.DISCONNECTED -> ScreenShareSignalingState.OFFLINE
                            SignalingStatus.CONNECTING -> ScreenShareSignalingState.CONNECTING
                            SignalingStatus.CONNECTED -> ScreenShareSignalingState.ONLINE
                            SignalingStatus.RECONNECTING -> ScreenShareSignalingState.RECONNECTING
                            SignalingStatus.FAILED -> ScreenShareSignalingState.FAILED
                        },
                    )
                    // A reconnect means a new peer id server-side, so nothing we
                    // negotiated before is still valid.
                    if (status == SignalingStatus.RECONNECTING) stopEverything()
                }
            }
            launch { signaling.errors.collect { emitMessage(it) } }
            launch { signaling.messages.collect { handle(it) } }
        }
    }

    // ------------------------------------------------------------- publishing

    /** Stores the MediaProjection consent result so [startSharing] can use it. */
    fun setPermissionIntent(intent: Intent?) {
        permissionIntent = intent
    }

    /** Called by the UI once the user granted screen capture; starts immediately. */
    fun onPermissionGranted(intent: Intent) {
        permissionIntent = intent
        startSharing(_state.value.config)
    }

    fun onPermissionDenied() {
        permissionIntent = null
        _state.value = _state.value.copy(isStarting = false)
        ScreenShareService.stop(context)
        emitMessage("已取消屏幕录制授权")
    }

    fun updateConfig(transform: (ScreenShareConfig) -> ScreenShareConfig) {
        _state.value = _state.value.copy(config = transform(_state.value.config))
    }

    /**
     * Entry point for the UI's share button: stops an active share, otherwise asks
     * the Activity to show the system consent dialog.
     *
     * The consent token is single-use from Android 11 on, so it is always requested
     * anew rather than cached.
     */
    fun requestSharing() {
        if (_state.value.isSharing) {
            stopSharing()
            return
        }
        if (!_state.value.isConfigured) {
            emitMessage("请先在设置中填写屏幕共享信令服务地址")
            return
        }
        if (_state.value.signaling != ScreenShareSignalingState.ONLINE) {
            emitMessage("信令服务未连接，无法共享屏幕")
            return
        }
        _state.value = _state.value.copy(isStarting = true)
        // Android 14+ requires the mediaProjection foreground service to already be
        // running when the projection is acquired.
        ScreenShareService.start(context)
        scope.launch { _permissionRequests.emit(Unit) }
    }

    /**
     * Begins capturing and announces the share.
     *
     * Requires [setPermissionIntent] to have been called with a granted consent
     * intent and the projection foreground service to be running.
     */
    fun startSharing(config: ScreenShareConfig) {
        val intent = permissionIntent
        if (intent == null) {
            emitMessage("未获得屏幕录制授权")
            return
        }
        if (_state.value.signaling != ScreenShareSignalingState.ONLINE) {
            emitMessage("信令服务未连接，无法共享屏幕")
            return
        }
        if (_state.value.isSharing) return

        val mode = if (
            config.mode == ScreenShareMode.SERVER && !_state.value.serverModeAvailable
        ) {
            emitMessage("信令服务未启用服务器中转，已回退到 P2P")
            ScreenShareMode.P2P
        } else {
            config.mode
        }
        // Device audio capture is not wired up yet, so never announce audio we
        // cannot actually send.
        val captureAudio = false
        if (config.captureAudio) {
            emitMessage("设备音频共享尚未实现，本次仅共享画面")
        }
        val effective = config.copy(mode = mode, captureAudio = captureAudio)

        _state.value = _state.value.copy(isStarting = true, config = effective, errorMessage = null)

        val format = if (effective.resolution == ScreenShareResolution.SOURCE) {
            capture.formatFor(Int.MAX_VALUE, effective.fps)
        } else {
            capture.formatFor(effective.resolution.targetHeight, effective.fps)
        }

        val track = capture.start(intent, format) {
            scope.launch {
                emitMessage("屏幕录制已被系统停止")
                stopSharing()
            }
        }
        if (track == null) {
            _state.value = _state.value.copy(isStarting = false)
            ScreenShareService.stop(context)
            emitMessage("屏幕采集启动失败")
            return
        }

        _localTrack.value = track
        _state.value = _state.value.copy(isSharing = true, isStarting = false, viewerCount = 0)

        signaling.send(
            ClientMessage.Announce(
                mode = effective.mode.toWire(),
                privacy = effective.privacy.toWire(),
                hasAudio = effective.captureAudio,
                video = VideoParams(
                    width = format.width,
                    height = format.height,
                    fps = format.fps,
                    bitrateKbps = effective.videoBitrateKbps,
                ),
                audio = if (effective.captureAudio) {
                    AudioParams(effective.audioBitrateKbps)
                } else {
                    null
                },
                viewerLimit = effective.viewerLimit,
            ),
        )

        if (effective.mode == ScreenShareMode.SERVER) {
            scope.launch { publishToSfu(track, effective) }
        }
    }

    fun stopSharing() {
        if (!_state.value.isSharing && !capture.isCapturing) return
        signaling.send(ClientMessage.Unannounce())
        scope.launch {
            peerLock.withLock {
                publishPeers.values.forEach { it.close() }
                publishPeers.clear()
            }
            capture.stop()
            permissionIntent = null
            _localTrack.value = null
            ScreenShareService.stop(context)
            _state.value = _state.value.copy(
                isSharing = false,
                isStarting = false,
                viewerCount = 0,
                pendingViewerRequests = emptyList(),
            )
        }
    }

    /** Applies a live bitrate / resolution change without renegotiating. */
    fun applyLiveConfig(config: ScreenShareConfig) {
        _state.value = _state.value.copy(config = config)
        if (!capture.isCapturing) return
        val format = if (config.resolution == ScreenShareResolution.SOURCE) {
            capture.formatFor(Int.MAX_VALUE, config.fps)
        } else {
            capture.formatFor(config.resolution.targetHeight, config.fps)
        }
        capture.changeFormat(format)
        scope.launch {
            peerLock.withLock {
                publishPeers.values.forEach { it.applySenderBitrate(config.videoBitrateKbps) }
            }
        }
    }

    private suspend fun publishToSfu(track: VideoTrack, config: ScreenShareConfig) {
        val peer = createPeer(SFU_KEY) ?: return
        peer.addVideoTrack(track)
        peerLock.withLock { publishPeers[SFU_KEY] = peer }
        runCatching {
            val sdp = peer.createOffer(config.videoBitrateKbps, PREFERRED_CODEC)
            peer.applySenderBitrate(config.videoBitrateKbps)
            signaling.send(
                ClientMessage.Offer(to = SignalingProtocol.SFU_PEER, sdp = sdp),
            )
        }.onFailure {
            Log.w(TAG, "sfu publish failed", it)
            emitMessage("服务器中转协商失败：${it.message.orEmpty()}")
        }
    }

    private suspend fun offerToViewer(viewerId: String) {
        val track = _localTrack.value ?: return
        val config = _state.value.config
        val peer = createPeer(viewerId) ?: return
        peer.addVideoTrack(track)
        peerLock.withLock {
            publishPeers[viewerId]?.close()
            publishPeers[viewerId] = peer
        }
        runCatching {
            val sdp = peer.createOffer(config.videoBitrateKbps, PREFERRED_CODEC)
            peer.applySenderBitrate(config.videoBitrateKbps)
            signaling.send(ClientMessage.Offer(to = viewerId, sdp = sdp))
        }.onFailure {
            Log.w(TAG, "offer to viewer failed", it)
            peerLock.withLock { publishPeers.remove(viewerId)?.close() }
        }
        refreshViewerCount()
    }

    // ---------------------------------------------------------------- viewing

    fun watch(publisherId: String) {
        if (_state.value.signaling != ScreenShareSignalingState.ONLINE) {
            emitMessage("信令服务未连接")
            return
        }
        updateRemoteShare(publisherId) { it.copy(isConnecting = true) }
        signaling.send(ClientMessage.Watch(publisherId))
    }

    fun stopWatching(publisherId: String) {
        signaling.send(ClientMessage.Unwatch(publisherId))
        scope.launch {
            peerLock.withLock { viewPeers.remove(publisherId)?.close() }
            _remoteTracks.value = _remoteTracks.value - publisherId
            updateRemoteShare(publisherId) { it.copy(isWatching = false, isConnecting = false) }
        }
    }

    fun approveViewer(peerId: String) {
        _state.value = _state.value.copy(
            pendingViewerRequests = _state.value.pendingViewerRequests.filterNot {
                it.peerId == peerId
            },
        )
        scope.launch { offerToViewer(peerId) }
    }

    fun denyViewer(peerId: String) {
        _state.value = _state.value.copy(
            pendingViewerRequests = _state.value.pendingViewerRequests.filterNot {
                it.peerId == peerId
            },
        )
        signaling.send(ClientMessage.Bye(to = peerId, reason = "declined"))
    }

    // --------------------------------------------------------------- messages

    private suspend fun handle(message: ServerMessage) {
        when (message) {
            is ServerMessage.Welcome -> {
                localPeerId = message.peerId
                iceServers = message.iceServers
                _state.value = _state.value.copy(
                    serverModeAvailable = message.sfuAvailable,
                    remoteShares = message.shares.map { it.toRemoteShare() },
                )
            }

            is ServerMessage.PeerJoined -> Unit

            is ServerMessage.PeerLeft -> {
                peerLock.withLock {
                    publishPeers.remove(message.peerId)?.close()
                    viewPeers.remove(message.peerId)?.close()
                }
                _remoteTracks.value = _remoteTracks.value - message.peerId
                _state.value = _state.value.copy(
                    remoteShares = _state.value.remoteShares.filterNot {
                        it.publisherId == message.peerId
                    },
                    pendingViewerRequests = _state.value.pendingViewerRequests.filterNot {
                        it.peerId == message.peerId
                    },
                )
                refreshViewerCount()
            }

            is ServerMessage.ShareStarted -> {
                val share = message.share.toRemoteShare()
                if (share.publisherId == localPeerId) return
                val existing = _state.value.remoteShares.filterNot {
                    it.publisherId == share.publisherId
                }
                _state.value = _state.value.copy(remoteShares = existing + share)
            }

            is ServerMessage.ShareStopped -> {
                peerLock.withLock { viewPeers.remove(message.publisherId)?.close() }
                _remoteTracks.value = _remoteTracks.value - message.publisherId
                _state.value = _state.value.copy(
                    remoteShares = _state.value.remoteShares.filterNot {
                        it.publisherId == message.publisherId
                    },
                )
            }

            is ServerMessage.WatchRequest -> {
                if (!_state.value.isSharing) return
                if (_state.value.config.privacy == ScreenSharePrivacy.PRIVATE) {
                    val request = ViewerRequest(
                        peerId = message.from,
                        nickname = message.nickname,
                        clientUid = message.clientUid,
                    )
                    _state.value = _state.value.copy(
                        pendingViewerRequests = _state.value.pendingViewerRequests
                            .filterNot { it.peerId == request.peerId } + request,
                    )
                    return
                }
                offerToViewer(message.from)
            }

            is ServerMessage.Offer -> acceptIncomingOffer(message)

            is ServerMessage.Answer -> {
                val peer = peerLock.withLock {
                    publishPeers[message.from] ?: publishPeers[SFU_KEY]
                } ?: return
                runCatching { peer.acceptAnswer(message.sdp) }
                    .onFailure { Log.w(TAG, "acceptAnswer failed", it) }
            }

            is ServerMessage.Candidate -> {
                val candidate = IceCandidate(
                    message.sdpMid,
                    message.sdpMLineIndex,
                    message.candidate,
                )
                val peer = peerLock.withLock {
                    viewPeers[message.from]
                        ?: publishPeers[message.from]
                        ?: publishPeers[SFU_KEY]
                }
                peer?.addRemoteCandidate(candidate)
            }

            is ServerMessage.Bye -> {
                peerLock.withLock {
                    publishPeers.remove(message.from)?.close()
                    viewPeers.remove(message.from)?.close()
                }
                _remoteTracks.value = _remoteTracks.value - message.from
                updateRemoteShare(message.from) {
                    it.copy(isWatching = false, isConnecting = false)
                }
                refreshViewerCount()
            }

            is ServerMessage.Error -> {
                val text = message.message.ifBlank { message.code }
                _state.value = _state.value.copy(errorMessage = text)
                emitMessage("屏幕共享：$text")
            }

            is ServerMessage.Pong, is ServerMessage.Unknown -> Unit
        }
    }

    /**
     * Handles an incoming offer. In p2p the publisher offers to us; in server mode
     * the relay offers on the publisher's behalf, and `from` is the publisher id
     * so the track still lands under the right share.
     */
    private suspend fun acceptIncomingOffer(message: ServerMessage.Offer) {
        val publisherId = message.from
        val peer = createPeer(publisherId) ?: return
        peer.addReceiveOnlyVideo()
        val expectsAudio = _state.value.remoteShares
            .firstOrNull { it.publisherId == publisherId }?.hasAudio ?: false
        if (expectsAudio) peer.addReceiveOnlyAudio()

        peerLock.withLock {
            viewPeers[publisherId]?.close()
            viewPeers[publisherId] = peer
        }

        runCatching {
            peer.acceptOffer(message.sdp)
            val sdp = peer.createAnswer(bitrateKbps = 0, preferredCodec = PREFERRED_CODEC)
            signaling.send(ClientMessage.Answer(to = publisherId, sdp = sdp))
            updateRemoteShare(publisherId) { it.copy(isConnecting = true) }
        }.onFailure {
            Log.w(TAG, "answer failed", it)
            peerLock.withLock { viewPeers.remove(publisherId)?.close() }
            updateRemoteShare(publisherId) { it.copy(isConnecting = false) }
            emitMessage("屏幕共享协商失败：${it.message.orEmpty()}")
        }
    }

    private fun createPeer(remoteId: String): RtcPeer? = runCatching {
        RtcPeer(
            core = core,
            rtcConfig = core.rtcConfig(iceServers),
            events = object : RtcPeerEvents {
                override fun onLocalCandidate(candidate: IceCandidate) {
                    val to = if (remoteId == SFU_KEY) SignalingProtocol.SFU_PEER else remoteId
                    signaling.send(
                        ClientMessage.Candidate(
                            to = to,
                            candidate = candidate.sdp,
                            sdpMid = candidate.sdpMid.orEmpty(),
                            sdpMLineIndex = candidate.sdpMLineIndex,
                        ),
                    )
                }

                override fun onRemoteVideoTrack(track: VideoTrack) {
                    track.setEnabled(true)
                    _remoteTracks.value = _remoteTracks.value + (remoteId to track)
                    updateRemoteShare(remoteId) {
                        it.copy(isWatching = true, isConnecting = false)
                    }
                }

                override fun onConnected() {
                    updateRemoteShare(remoteId) { it.copy(isConnecting = false) }
                    scope.launch { refreshViewerCount() }
                }

                override fun onClosed(reason: String) {
                    scope.launch {
                        peerLock.withLock {
                            publishPeers.remove(remoteId)?.close()
                            viewPeers.remove(remoteId)?.close()
                        }
                        _remoteTracks.value = _remoteTracks.value - remoteId
                        updateRemoteShare(remoteId) {
                            it.copy(isWatching = false, isConnecting = false)
                        }
                        refreshViewerCount()
                    }
                }
            },
        )
    }.getOrElse {
        Log.w(TAG, "createPeer failed", it)
        emitMessage("WebRTC 初始化失败：${it.message.orEmpty()}")
        null
    }

    private suspend fun stopEverything() {
        peerLock.withLock {
            publishPeers.values.forEach { it.close() }
            publishPeers.clear()
            viewPeers.values.forEach { it.close() }
            viewPeers.clear()
        }
        _remoteTracks.value = emptyMap()
        if (capture.isCapturing) {
            capture.stop()
            permissionIntent = null
            _localTrack.value = null
            ScreenShareService.stop(context)
        }
        _state.value = _state.value.copy(
            isSharing = false,
            isStarting = false,
            viewerCount = 0,
            pendingViewerRequests = emptyList(),
        )
    }

    private suspend fun refreshViewerCount() {
        val count = peerLock.withLock { publishPeers.keys.count { it != SFU_KEY } }
        _state.value = _state.value.copy(viewerCount = count)
    }

    private fun updateRemoteShare(publisherId: String, transform: (RemoteShare) -> RemoteShare) {
        val shares = _state.value.remoteShares
        if (shares.none { it.publisherId == publisherId }) return
        _state.value = _state.value.copy(
            remoteShares = shares.map {
                if (it.publisherId == publisherId) transform(it) else it
            },
        )
    }

    private fun normalizeSignalUrl(raw: String): String = normalizeSignalUrlStatic(raw)

    private fun emitMessage(text: String) {
        scope.launch { _messages.emit(text) }
    }

    private fun ShareInfo.toRemoteShare() = RemoteShare(
        publisherId = publisherId,
        nickname = nickname,
        mode = if (mode == ShareMode.SFU) ScreenShareMode.SERVER else ScreenShareMode.P2P,
        hasAudio = hasAudio,
        width = video?.width ?: 0,
        height = video?.height ?: 0,
        fps = video?.fps ?: 0,
        bitrateKbps = video?.bitrateKbps ?: 0,
    )

    private fun ScreenShareMode.toWire(): ShareMode = when (this) {
        ScreenShareMode.P2P -> ShareMode.P2P
        ScreenShareMode.SERVER -> ShareMode.SFU
    }

    private fun ScreenSharePrivacy.toWire(): SharePrivacy = when (this) {
        ScreenSharePrivacy.PUBLIC -> SharePrivacy.PUBLIC
        ScreenSharePrivacy.CONTACTS -> SharePrivacy.CONTACTS
        ScreenSharePrivacy.PRIVATE -> SharePrivacy.PRIVATE
    }

    companion object {
        const val TAG = "ScreenShareManager"

        /** Key for the single publish peer used in server-relay mode. */
        const val SFU_KEY = "__sfu__"

        /** Best hardware support on Android and widest desktop interop. */
        const val PREFERRED_CODEC = "H264"

        @JvmStatic
        internal fun normalizeSignalUrlStatic(raw: String): String {
            val candidate = raw.trim()
            if (candidate.isEmpty()) return ""
            return when {
                candidate.startsWith("ws://", ignoreCase = true) || candidate.startsWith("wss://", ignoreCase = true) -> candidate
                candidate.startsWith("http://", ignoreCase = true) -> "ws" + candidate.removePrefix("http")
                candidate.startsWith("https://", ignoreCase = true) -> "wss" + candidate.removePrefix("https")
                else -> candidate
            }
        }
    }
}
