package dev.tsdroid.screen

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import dev.tsdroid.bridge.ScreenShareEvent
import dev.tsdroid.bridge.ScreenShareSignal
import dev.tsdroid.bridge.TsClient
import dev.tsdroid.service.ScreenShareService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.Executors

/**
 * 屏幕共享媒体层控制器（A3 发布端 / A4 观看端）。
 *
 * 协议面（命令与通知）由 Rust 侧 `screen_share` 模块 + JNI 完成；
 * 本控制器负责 WebRTC 媒体会话与信令编排：
 *
 * - **发布端**：MediaProjection 采集 → 每收到一个 `JoinRequest` 为观看者创建
 *   PeerConnection 并生成 offer（`respondjoinstreamrequest` 携带 offer）；
 * - **观看端**：`joinstreamrequest` → 收到 `RespondJoinRequest`（offer）→ 回 answer；
 * - ICE 候选经 `streamsignaling` 双向中转（`iceCandidate` 信令）。
 *
 * ICE 服务器与 WebSpeak 默认一致：TS 公共 STUN（媒体保持 P2P，不经服务器中转）。
 * 协议规格见 `docs/A2-screen-share-protocol.md`。
 */
object ScreenShareController {

    private const val TAG = "ScreenShare"

    /** 与 WebSpeak 默认一致（STUN-only，P2P 直连） */
    private val ICE_SERVER_URLS = listOf(
        "stun:turn.teamspeak.com:3478",
        "stun:turn2.teamspeak.com:3478",
    )

    private const val VIDEO_STREAM_LABEL = "screen"
    private const val MAX_CAPTURE_WIDTH = 1280
    private const val MAX_CAPTURE_HEIGHT = 720
    private const val CAPTURE_FPS = 30

    /** 所有 WebRTC 对象操作串行化到单线程（信令线程语义） */
    private val signaling = Executors.newSingleThreadExecutor { Thread(it, "ScreenShareSignal") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var tsClient: TsClient? = null

    @Volatile
    private var factory: PeerConnectionFactory? = null

    @Volatile
    private var eglBase: EglBase? = null

    private var eventJob: Job? = null

    /** 是否正在发布（含等待服务端回执阶段） */
    private val _publishing = MutableStateFlow(false)
    val publishing: StateFlow<Boolean> = _publishing.asStateFlow()

    /** 当前已知的远端直播流 */
    private val _remoteStreams = MutableStateFlow<Map<String, RemoteStream>>(emptyMap())
    val remoteStreams: StateFlow<Map<String, RemoteStream>> = _remoteStreams.asStateFlow()

    /** 最近一次错误的资源标识（UI 取用后应调用 [clearError]） */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** 远端直播流描述 */
    data class RemoteStream(
        val streamId: String,
        val clientId: Int,
        val name: String,
        val audio: Boolean,
    )

    private class PublisherState(val streamName: String, val withAudio: Boolean) {
        @Volatile
        var streamId: String? = null
        var capturer: ScreenCapturerAndroid? = null
        var surfaceHelper: SurfaceTextureHelper? = null
        var videoSource: VideoSource? = null
        var videoTrack: VideoTrack? = null

        /** viewerId -> PeerConnection */
        val peers = mutableMapOf<Int, PeerConnection>()
        val remoteDescriptionSet = mutableMapOf<Int, Boolean>()
        val pendingCandidates = mutableMapOf<Int, MutableList<IceCandidate>>()
    }

    private class ViewerState(val streamId: String, val sourceClientId: Int) {
        var peer: PeerConnection? = null
        var renderer: SurfaceViewRenderer? = null
        var remoteVideoTrack: VideoTrack? = null
        var sinkAttached = false
        var remoteDescriptionSet = false
        val pendingCandidates = mutableListOf<IceCandidate>()
    }

    @Volatile
    private var publisher: PublisherState? = null
    private val viewers = mutableMapOf<String, ViewerState>()

    // ==================== 初始化与事件订阅 ====================

    /** 预热：提前初始化 PeerConnectionFactory（首次共享时减少卡顿） */
    fun warmUp(context: Context) {
        val app = context.applicationContext
        appContext = app
        signaling.execute { ensureFactory(app) }
    }

    /** 连接成功后绑定 TsClient，开始消费屏幕共享事件 */
    fun attach(client: TsClient) {
        tsClient = client
        eventJob?.cancel()
        eventJob = scope.launch {
            client.screenShareEvents.collect { event -> onScreenShareEvent(event) }
        }
        Log.i(TAG, "Attached to TsClient")
    }

    /** 断开连接时解绑（停止发布/观看并释放资源） */
    fun detach() {
        eventJob?.cancel()
        eventJob = null
        tsClient = null
        signaling.execute {
            val wasPublishing = publisher != null
            cleanupPublisher("disconnected")
            viewers.keys.toList().forEach { streamId ->
                viewers.remove(streamId)?.let { closeViewer(it) }
            }
            _remoteStreams.value = emptyMap()
            // 若仍在共享，顺带关闭前台服务（幂等）
            if (wasPublishing) appContext?.let { ScreenShareService.stop(it) }
        }
    }

    // ==================== 发布端（A3） ====================

    /**
     * 开始发布屏幕共享。必须在用户同意 MediaProjection 且
     * [ScreenShareService] 已启动（mediaProjection 类型 FGS）之后调用。
     */
    fun startPublisher(
        context: Context,
        tsClient: TsClient,
        resultCode: Int,
        resultData: Intent,
        streamName: String,
        withAudio: Boolean,
    ) {
        this.tsClient = tsClient
        val app = context.applicationContext
        appContext = app
        signaling.execute {
            try {
                val f = ensureFactory(app)
                val egl = eglBase ?: throw IllegalStateException("EglBase unavailable")
                val state = PublisherState(streamName, withAudio)

                val capturer = ScreenCapturerAndroid(
                    resultData,
                    object : MediaProjection.Callback() {
                        override fun onStop() {
                            Log.i(TAG, "MediaProjection stopped by user or system")
                            // 统一走服务停止路径（ACTION_STOP → stopPublishing）：
                            // 先向服务器发送 stopstream 再清理资源，避免留下幽灵流会话。
                            ScreenShareService.stop(app)
                        }
                    },
                )
                val helper = SurfaceTextureHelper.create("ScreenShareCapture", egl.eglBaseContext)
                val source = f.createVideoSource(/* isScreencast = */ true)
                capturer.initialize(helper, app, source.capturerObserver)

                val metrics = app.resources.displayMetrics
                val (width, height) = scaleToFit(
                    metrics.widthPixels,
                    metrics.heightPixels,
                    MAX_CAPTURE_WIDTH,
                    MAX_CAPTURE_HEIGHT,
                )
                capturer.startCapture(width, height, CAPTURE_FPS)

                state.capturer = capturer
                state.surfaceHelper = helper
                state.videoSource = source
                state.videoTrack = f.createVideoTrack("screen0", source)
                publisher = state
                _publishing.value = true

                tsClient.setupScreenShare(streamName, withAudio)
                Log.i(TAG, "Publisher started: $streamName (${width}x$height @${CAPTURE_FPS}fps)")
            } catch (e: Throwable) {
                Log.e(TAG, "startPublisher failed", e)
                _lastError.value = "start_failed"
                cleanupPublisher("start failure")
                ScreenShareService.stop(app)
            }
        }
    }

    /**
     * 停止发布：先向服务器发送 stopstream，再清理本地采集与 PeerConnection。
     *
     * ⚠️ 这里**不能**回调 [ScreenShareService.stop]——否则会与
     * `onStartCommand(ACTION_STOP) → stopPublishing()` 形成主线程无限递归
     * （「Stop requested from notification」风暴 bug）。停止前台服务的
     * 唯一入口是 [ScreenShareService.stop]：UI 直接调用它，通知栏按钮
     * 走 PendingIntent → onStartCommand(ACTION_STOP) → stopSelf()。
     */
    fun stopPublishing() {
        signaling.execute {
            val streamId = publisher?.streamId
            if (streamId != null) {
                tsClient?.stopScreenShare(streamId)
            }
            cleanupPublisher("user stop")
        }
    }

    /** [ScreenShareService] 销毁时的兜底清理（不回调 stopService，避免循环） */
    fun onServiceDestroyed() {
        signaling.execute { cleanupPublisher("service destroyed") }
    }

    private fun cleanupPublisher(reason: String) {
        val state = publisher ?: return
        publisher = null
        _publishing.value = false

        state.peers.values.forEach { runCatching { it.close() } }
        state.peers.clear()
        state.pendingCandidates.clear()
        state.remoteDescriptionSet.clear()
        state.capturer?.let {
            runCatching { it.stopCapture() }
            runCatching { it.dispose() }
        }
        state.videoTrack?.let { runCatching { it.dispose() } }
        state.videoSource?.let { runCatching { it.dispose() } }
        state.surfaceHelper?.let { runCatching { it.dispose() } }
        Log.i(TAG, "Publisher cleaned up: $reason")
    }

    /** 观看者请求观看：为其创建 PeerConnection 并生成 offer（发布端方向） */
    private fun handleJoinRequest(streamId: String, viewerId: Int) {
        val state = publisher ?: return
        if (state.streamId != streamId || state.peers.containsKey(viewerId)) return
        val f = factory ?: return

        val pc = f.createPeerConnection(rtcConfig(), publisherObserver(state, viewerId))
        if (pc == null) {
            Log.e(TAG, "createPeerConnection failed for viewer $viewerId")
            return
        }
        state.peers[viewerId] = pc
        // 发布端视频方向必须是 sendonly：TS6 客户端对收到的 offer 执行
        // 「会话版本改写 + SDP 校验」管线（StreamP2P::HandleRemoteOffer →
        // MangleSessionVersion），其中 a=sendrecv 会被静默拒收、不产生任何
        // 错误回复。变体矩阵 W2 与 V6/V7 对照已证实（docs/A3 §6）。
        // 用 addTransceiver 在创建时即指定 SEND_ONLY：addTrack 默认 sendrecv，
        // 且事后 setDirection 在首轮真机实测中未生效（offer 仍为 sendrecv）。
        state.videoTrack?.let { track ->
            pc.addTransceiver(
                track,
                RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,
                    listOf(VIDEO_STREAM_LABEL),
                ),
            )
        }
        // 占位音频 m= 线：TS6 客户端要求 offer 至少包含两条媒体线
        // （m=video + m=audio）。缺失音频线时同样被静默拒收（V5 ❌ / V6 ✅）。
        // inactive 表示不发送、也不接收音频；只是满足协议结构要求。
        pc.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.INACTIVE),
        )

        pc.createOffer(
            object : SdpObserverAdapter() {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    // 兜底：确保视频段为 a=sendonly——若引擎方向设置仍未生效，
                    // 在视频段内原位改写；setLocalDescription 采用改写后的描述，
                    // 保证线上字节与信令内容一致。
                    val fixed = ensureVideoSendOnly(sdp.description)
                    val finalDesc = SessionDescription(sdp.type, fixed)
                    // 联调留档：将最终 offer 原样写入应用外部目录，便于 adb pull
                    // 做字节级核对（/sdcard/Android/data/<包名>/files/screenshare/last-offer.sdp）
                    runCatching {
                        val dir = java.io.File(appContext?.getExternalFilesDir(null), "screenshare")
                        dir.mkdirs()
                        java.io.File(dir, "last-offer.sdp").writeText(fixed)
                    }
                    Log.i(
                        TAG,
                        "Offer for viewer $viewerId: ${fixed.length}B" +
                            " sendonly=${fixed.contains("a=sendonly")}" +
                            " audioLine=${fixed.contains("m=audio")}" +
                            " crlfEnd=${fixed.endsWith("\r\n")}" +
                            " rewritten=${fixed != sdp.description}",
                    )
                    Log.d(TAG, "Offer SDP (viewer $viewerId):\n$fixed")
                    pc.setLocalDescription(
                        object : SdpObserverAdapter() {
                            override fun onSetSuccess() {
                                tsClient?.respondScreenShareJoin(streamId, viewerId, finalDesc.description)
                                Log.i(TAG, "Offer sent to viewer $viewerId (stream $streamId)")
                            }
                        },
                        finalDesc,
                    )
                }
            },
            MediaConstraints(),
        )
    }

    private fun closePublisherPeer(streamId: String?, viewerId: Int) {
        val state = publisher ?: return
        if (streamId != null && state.streamId != streamId) return
        state.peers.remove(viewerId)?.let { runCatching { it.close() } }
        state.pendingCandidates.remove(viewerId)
        state.remoteDescriptionSet.remove(viewerId)
        Log.i(TAG, "Viewer peer closed: $viewerId")
    }

    // ==================== 观看端（A4） ====================

    /** 加入观看他人的流；renderer 由 UI 提供（已 init） */
    fun joinStream(streamId: String, sourceClientId: Int, renderer: SurfaceViewRenderer) {
        signaling.execute {
            val app = appContext ?: return@execute
            val client = tsClient ?: return@execute
            val existing = viewers[streamId]
            if (existing != null) {
                existing.renderer = renderer
                attachSinkIfReady(existing)
                return@execute
            }
            val f = ensureFactory(app)
            val state = ViewerState(streamId, sourceClientId)
            state.renderer = renderer
            val pc = f.createPeerConnection(rtcConfig(), viewerObserver(state))
            if (pc == null) {
                Log.e(TAG, "createPeerConnection failed for stream $streamId")
                return@execute
            }
            state.peer = pc
            viewers[streamId] = state
            client.joinScreenShare(streamId, sourceClientId)
            Log.i(TAG, "Join requested: stream $streamId from client $sourceClientId")
        }
    }

    /** 离开观看（UI 关闭播放页时调用） */
    fun leaveStream(streamId: String) {
        signaling.execute {
            viewers.remove(streamId)?.let {
                closeViewer(it)
                Log.i(TAG, "Left stream $streamId")
            }
        }
    }

    private fun closeViewer(state: ViewerState) {
        runCatching {
            if (state.sinkAttached) state.remoteVideoTrack?.removeSink(state.renderer)
        }
        state.sinkAttached = false
        state.remoteVideoTrack = null
        state.renderer = null
        state.peer?.let { runCatching { it.close() } }
        state.peer = null
        state.pendingCandidates.clear()
    }

    /** 观看端收到 offer（`notifyrespondjoinstreamrequest`）：回 answer */
    private fun handleViewerOffer(streamId: String, offerSdp: String) {
        val state = viewers[streamId] ?: run {
            Log.w(TAG, "Offer for unknown stream $streamId")
            return
        }
        val pc = state.peer ?: return
        pc.setRemoteDescription(
            object : SdpObserverAdapter() {
                override fun onSetSuccess() {
                    state.remoteDescriptionSet = true
                    state.pendingCandidates.forEach { runCatching { pc.addIceCandidate(it) } }
                    state.pendingCandidates.clear()
                    pc.createAnswer(
                        object : SdpObserverAdapter() {
                            override fun onCreateSuccess(sdp: SessionDescription) {
                                pc.setLocalDescription(
                                    object : SdpObserverAdapter() {
                                        override fun onSetSuccess() {
                                            tsClient?.sendScreenShareAnswer(
                                                streamId,
                                                state.sourceClientId,
                                                sdp.description,
                                            )
                                            Log.i(TAG, "Answer sent for stream $streamId")
                                        }
                                    },
                                    sdp,
                                )
                            }
                        },
                        MediaConstraints(),
                    )
                }
            },
            SessionDescription(SessionDescription.Type.OFFER, offerSdp),
        )
    }

    private fun attachSinkIfReady(state: ViewerState) {
        val track = state.remoteVideoTrack ?: return
        val renderer = state.renderer ?: return
        if (state.sinkAttached) return
        track.addSink(renderer)
        state.sinkAttached = true
        Log.i(TAG, "Video sink attached for stream ${state.streamId}")
    }

    // ==================== 事件分发 ====================

    private fun onScreenShareEvent(event: ScreenShareEvent) {
        when (event) {
            is ScreenShareEvent.StreamStarted -> {
                val state = publisher
                if (state != null && state.streamId == null) {
                    state.streamId = event.streamId
                    Log.i(TAG, "Our stream started: ${event.streamId}")
                } else {
                    upsertRemote(
                        RemoteStream(event.streamId, event.clientId, event.name, event.audio),
                    )
                }
            }

            is ScreenShareEvent.StreamInfo -> upsertRemote(
                RemoteStream(event.streamId, event.clientId, event.name, event.audio),
            )

            is ScreenShareEvent.StreamStopped -> {
                removeRemote(event.streamId)
                if (publisher?.streamId == event.streamId) {
                    signaling.execute { cleanupPublisher("stream stopped by server") }
                    appContext?.let { ScreenShareService.stop(it) }
                }
            }

            is ScreenShareEvent.JoinRequest -> {
                if (publisher?.streamId == event.streamId) {
                    signaling.execute { handleJoinRequest(event.streamId, event.viewerId) }
                }
            }

            is ScreenShareEvent.RespondJoinRequest -> {
                signaling.execute { handleViewerOffer(event.streamId, event.offer) }
            }

            is ScreenShareEvent.Signal -> {
                signaling.execute { handleSignal(event) }
            }

            is ScreenShareEvent.ViewerLeft -> {
                signaling.execute { closePublisherPeer(event.streamId, event.viewerId) }
            }
        }
    }

    private fun handleSignal(event: ScreenShareEvent.Signal) {
        when (val signal = event.signal) {
            is ScreenShareSignal.Answer -> {
                val state = publisher ?: return
                val pc = state.peers[event.peerId] ?: return
                pc.setRemoteDescription(
                    SdpObserverAdapter(),
                    SessionDescription(SessionDescription.Type.ANSWER, signal.sdp),
                )
                state.remoteDescriptionSet[event.peerId] = true
                state.pendingCandidates.remove(event.peerId)?.forEach {
                    runCatching { pc.addIceCandidate(it) }
                }
            }

            is ScreenShareSignal.IceCandidate -> {
                val candidate = IceCandidate(
                    signal.mid ?: "",
                    signal.mLine ?: 0,
                    signal.candidate,
                )
                val state = publisher
                val publisherPc = state?.peers?.get(event.peerId)
                if (state != null && publisherPc != null) {
                    if (state.remoteDescriptionSet[event.peerId] == true) {
                        runCatching { publisherPc.addIceCandidate(candidate) }
                    } else {
                        state.pendingCandidates
                            .getOrPut(event.peerId) { mutableListOf() }
                            .add(candidate)
                    }
                } else {
                    val viewer = viewers[event.streamId] ?: return
                    if (viewer.remoteDescriptionSet) {
                        runCatching { viewer.peer?.addIceCandidate(candidate) }
                    } else {
                        viewer.pendingCandidates.add(candidate)
                    }
                }
            }

            is ScreenShareSignal.JoinResponse -> {
                if (!signal.decision) {
                    Log.w(TAG, "Join rejected for stream ${event.streamId}")
                    _lastError.value = "join_rejected"
                    signaling.execute {
                        viewers.remove(event.streamId)?.let { closeViewer(it) }
                    }
                }
            }

            is ScreenShareSignal.Offer -> {
                signaling.execute { handleViewerOffer(event.streamId, signal.sdp) }
            }

            is ScreenShareSignal.Unknown -> Log.w(TAG, "Unknown signal: ${signal.cmd}")

            null -> Unit
        }
    }

    private fun upsertRemote(stream: RemoteStream) {
        _remoteStreams.value = _remoteStreams.value + (stream.streamId to stream)
    }

    private fun removeRemote(streamId: String) {
        _remoteStreams.value = _remoteStreams.value - streamId
    }

    fun clearError() {
        _lastError.value = null
    }

    // ==================== PeerConnection 观察者 ====================

    private fun publisherObserver(state: PublisherState, viewerId: Int): PeerConnection.Observer =
        object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                val streamId = state.streamId ?: return
                tsClient?.sendScreenShareIceCandidate(
                    streamId,
                    viewerId,
                    candidate.sdp,
                    candidate.sdpMid,
                    candidate.sdpMLineIndex ?: -1,
                )
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                if (newState == PeerConnection.IceConnectionState.FAILED) {
                    Log.w(TAG, "ICE failed for viewer $viewerId")
                    closePublisherPeer(state.streamId, viewerId)
                }
            }

            override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) = Unit
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
            override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
            override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
            override fun onDataChannel(dataChannel: org.webrtc.DataChannel) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(
                receiver: org.webrtc.RtpReceiver,
                mediaStreams: Array<out org.webrtc.MediaStream>,
            ) = Unit
        }

    private fun viewerObserver(state: ViewerState): PeerConnection.Observer =
        object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                tsClient?.sendScreenShareIceCandidate(
                    state.streamId,
                    state.sourceClientId,
                    candidate.sdp,
                    candidate.sdpMid,
                    candidate.sdpMLineIndex ?: -1,
                )
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                when (newState) {
                    PeerConnection.IceConnectionState.CONNECTED -> Log.i(TAG, "Viewer ICE connected: ${state.streamId}")
                    PeerConnection.IceConnectionState.FAILED -> Log.w(TAG, "Viewer ICE failed: ${state.streamId}")
                    PeerConnection.IceConnectionState.DISCONNECTED -> Log.w(TAG, "Viewer ICE disconnected: ${state.streamId}")
                    else -> Unit
                }
            }

            override fun onAddTrack(
                receiver: org.webrtc.RtpReceiver,
                mediaStreams: Array<out org.webrtc.MediaStream>,
            ) {
                val track = receiver.track()
                if (track is VideoTrack) {
                    state.remoteVideoTrack = track
                    signaling.execute { attachSinkIfReady(state) }
                }
            }

            override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) = Unit
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
            override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
            override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
            override fun onDataChannel(dataChannel: org.webrtc.DataChannel) = Unit
            override fun onRenegotiationNeeded() = Unit
        }

    // ==================== 工具 ====================

    private fun ensureFactory(context: Context): PeerConnectionFactory {
        factory?.let { return it }
        synchronized(this) {
            factory?.let { return it }
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions(),
            )
            val egl = EglBase.create()
            val created = PeerConnectionFactory.builder()
                .setOptions(PeerConnectionFactory.Options())
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                .createPeerConnectionFactory()
            eglBase = egl
            factory = created
            Log.i(TAG, "PeerConnectionFactory initialized")
            return created
        }
    }

    private fun rtcConfig(): PeerConnection.RTCConfiguration {
        val config = PeerConnection.RTCConfiguration(
            ICE_SERVER_URLS.map { PeerConnection.IceServer.builder(it).createIceServer() },
        )
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        config.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        return config
    }

    /**
     * 将 offer 中视频媒体段内的首个 a=sendrecv 原位改写为 a=sendonly（兜底）。
     *
     * 仅处理 m=video 起始的媒体段，避免误伤其它段（当前音频段为 inactive）。
     * 视频段本就不含 sendrecv 时原样返回，保证幂等。
     */
    private fun ensureVideoSendOnly(sdp: String): String {
        val videoStart = sdp.indexOf("m=video")
        if (videoStart < 0) return sdp
        val nextMedia = sdp.indexOf("\r\nm=", videoStart)
        val videoEnd = if (nextMedia < 0) sdp.length else nextMedia + 2
        val section = sdp.substring(videoStart, videoEnd)
        if (!section.contains("a=sendrecv")) return sdp
        val fixedSection = section.replaceFirst("a=sendrecv", "a=sendonly")
        return sdp.substring(0, videoStart) + fixedSection + sdp.substring(videoEnd)
    }

    /** 等比缩放到上限内，并对宽高取偶数（编码器要求） */
    private fun scaleToFit(
        sourceWidth: Int,
        sourceHeight: Int,
        maxWidth: Int,
        maxHeight: Int,
    ): Pair<Int, Int> {
        var width = sourceWidth
        var height = sourceHeight
        if (width > maxWidth || height > maxHeight) {
            val ratio = minOf(maxWidth.toFloat() / width, maxHeight.toFloat() / height)
            width = (width * ratio).toInt()
            height = (height * ratio).toInt()
        }
        return (width / 2 * 2).coerceAtLeast(2) to (height / 2 * 2).coerceAtLeast(2)
    }

    /** SdpObserver 空实现适配器 */
    private open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) {
            Log.e(TAG, "SDP create failure: $error")
        }

        override fun onSetFailure(error: String) {
            Log.e(TAG, "SDP set failure: $error")
        }
    }
}
