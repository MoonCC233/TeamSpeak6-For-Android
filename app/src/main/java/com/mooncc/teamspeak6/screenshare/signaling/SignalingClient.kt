package com.mooncc.teamspeak6.screenshare.signaling

import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Lifecycle of the signaling socket, as far as the UI cares. */
enum class SignalingStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    FAILED,
}

/**
 * WebSocket transport for the screen-share signaling protocol.
 *
 * Owns reconnection: on an unexpected close it re-dials with exponential backoff
 * and replays [ClientMessage.Hello], because the server treats every socket as a
 * fresh peer. Callers therefore see a new `welcome` (and a new `peerId`) after a
 * reconnect and must tear down their peer connections — reconnection restores
 * the socket, not the sessions.
 */
class SignalingClient(
    private val httpClient: OkHttpClient,
    private val scope: CoroutineScope,
) {

    private val _status = MutableStateFlow(SignalingStatus.DISCONNECTED)
    val status: StateFlow<SignalingStatus> = _status.asStateFlow()

    private val _messages = MutableSharedFlow<ServerMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<ServerMessage> = _messages.asSharedFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private var socket: WebSocket? = null
    private var url: String? = null
    private var hello: ClientMessage.Hello? = null
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var attempt = 0

    /** Set to false by [disconnect] so a pending close callback stops re-dialing. */
    @Volatile
    private var wantConnection = false

    private val nonce = AtomicLong(0)

    /**
     * Dials [url] and sends [hello] once open. Replaces any existing connection.
     */
    fun connect(url: String, hello: ClientMessage.Hello) {
        disconnect()
        this.url = url
        this.hello = hello
        wantConnection = true
        attempt = 0
        openSocket()
    }

    fun disconnect() {
        wantConnection = false
        reconnectJob?.cancel()
        reconnectJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        socket?.let { active ->
            runCatching { active.send(SignalingCodec.encode(ClientMessage.Leave())) }
            runCatching { active.close(CLOSE_NORMAL, "leaving") }
        }
        socket = null
        _status.value = SignalingStatus.DISCONNECTED
    }

    /** @return false when the socket is not open, in which case nothing was sent. */
    fun send(message: ClientMessage): Boolean {
        val active = socket ?: return false
        return runCatching { active.send(SignalingCodec.encode(message)) }.getOrDefault(false)
    }

    private fun openSocket() {
        val target = url ?: return
        val request = Request.Builder().url(target).build()
        _status.value = if (attempt == 0) {
            SignalingStatus.CONNECTING
        } else {
            SignalingStatus.RECONNECTING
        }
        socket = httpClient.newWebSocket(request, Listener())
    }

    private fun scheduleReconnect() {
        if (!wantConnection) return
        if (attempt >= MAX_ATTEMPTS) {
            _status.value = SignalingStatus.FAILED
            scope.launch { _errors.emit("信令服务连接失败，已停止重试") }
            return
        }
        val delayMs = min(BASE_BACKOFF_MS shl attempt, MAX_BACKOFF_MS)
        attempt += 1
        _status.value = SignalingStatus.RECONNECTING
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (wantConnection) openSocket()
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (true) {
                delay(HEARTBEAT_MS)
                if (!send(ClientMessage.Ping(nonce.incrementAndGet()))) break
            }
        }
    }

    private inner class Listener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!wantConnection) {
                runCatching { webSocket.close(CLOSE_NORMAL, "cancelled") }
                return
            }
            attempt = 0
            _status.value = SignalingStatus.CONNECTED
            hello?.let { webSocket.send(SignalingCodec.encode(it)) }
            startHeartbeat()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = SignalingCodec.decode(text)
            if (message == null) {
                Log.w(TAG, "dropping unparsable signaling frame")
                return
            }
            if (message is ServerMessage.Unknown) {
                Log.d(TAG, "ignoring unknown signaling type: ${message.type}")
                return
            }
            if (message is ServerMessage.Pong) return
            scope.launch { _messages.emit(message) }
            if (message is ServerMessage.Error && message.fatal) {
                wantConnection = false
                scope.launch { _errors.emit(message.message.ifBlank { message.code }) }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            runCatching { webSocket.close(CLOSE_NORMAL, null) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== socket) return
            heartbeatJob?.cancel()
            socket = null
            if (wantConnection) scheduleReconnect() else _status.value = SignalingStatus.DISCONNECTED
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== socket) return
            heartbeatJob?.cancel()
            socket = null
            Log.w(TAG, "signaling socket failed", t)
            if (wantConnection) {
                scheduleReconnect()
            } else {
                _status.value = SignalingStatus.DISCONNECTED
            }
        }
    }

    private companion object {
        const val TAG = "SignalingClient"
        const val CLOSE_NORMAL = 1000
        const val HEARTBEAT_MS = 20_000L
        const val BASE_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
        const val MAX_ATTEMPTS = 8
    }
}
