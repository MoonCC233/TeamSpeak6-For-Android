package dev.tsdroid.bridge

import org.json.JSONException
import org.json.JSONObject

/**
 * 屏幕共享信令（对应 Rust 侧 `ScreenShareSignal`，见 docs/A2-screen-share-protocol.md §5）。
 */
sealed interface ScreenShareSignal {
    /** 对端下发 offer（观看者视角） */
    data class Offer(val sdp: String) : ScreenShareSignal

    /** 来源对加入请求的答复（decision=false 为拒绝） */
    data class JoinResponse(val decision: Boolean, val offer: String?) : ScreenShareSignal

    /** 对端应答（发布者视角） */
    data class Answer(val sdp: String) : ScreenShareSignal

    /** ICE 候选 */
    data class IceCandidate(
        val candidate: String,
        val mid: String?,
        val mLine: Int?,
    ) : ScreenShareSignal

    /** 未识别的信令（原样保留 cmd 名，供上层扩展） */
    data class Unknown(val cmd: String) : ScreenShareSignal
}

/**
 * 屏幕共享事件（对应 Rust 侧 `ScreenShareEvent`）。
 *
 * 由 JNI `screen_share` 事件的 `json` 字段反序列化而来（Serde 外部标签枚举，
 * 形如 `{"StreamStarted":{"stream_id":"7",...}}`）。
 */
sealed interface ScreenShareEvent {
    /** 新流开始（也是 setupstream 的回执，携带流 id） */
    data class StreamStarted(
        val streamId: String,
        val clientId: Int,
        val name: String,
        val audio: Boolean,
    ) : ScreenShareEvent

    /** 流信息应答（requeststreaminfo 的结果） */
    data class StreamInfo(
        val streamId: String,
        val clientId: Int,
        val name: String,
        val audio: Boolean,
    ) : ScreenShareEvent

    /** 流结束 */
    data class StreamStopped(val streamId: String) : ScreenShareEvent

    /** 有人请求观看我的流（发布者视角，需创建 offer 并回复） */
    data class JoinRequest(val streamId: String, val viewerId: Int) : ScreenShareEvent

    /** 来源接受了观看请求（观看者视角，offer 为 SDP） */
    data class RespondJoinRequest(val streamId: String, val offer: String) : ScreenShareEvent

    /** 对端信令（answer / ICE 候选） */
    data class Signal(
        val streamId: String,
        val peerId: Int,
        val json: String,
        val signal: ScreenShareSignal?,
    ) : ScreenShareEvent

    /** 观看者离开（发布者视角） */
    data class ViewerLeft(val streamId: String, val viewerId: Int) : ScreenShareEvent
}

/**
 * `ScreenShareEvent` JSON 解析器（宽松解析：无法识别时返回 null，不抛异常）。
 */
object ScreenShareEventParser {

    fun parse(json: String): ScreenShareEvent? = try {
        parseEvent(JSONObject(json))
    } catch (_: JSONException) {
        null
    }

    private fun parseEvent(root: JSONObject): ScreenShareEvent? {
        val key = root.keys().asSequence().firstOrNull() ?: return null
        val body = root.optJSONObject(key) ?: JSONObject()
        return when (key) {
            "StreamStarted" -> ScreenShareEvent.StreamStarted(
                streamId = body.optString("stream_id"),
                clientId = body.optInt("client_id"),
                name = body.optString("name"),
                audio = body.optBoolean("audio"),
            )
            "StreamInfo" -> ScreenShareEvent.StreamInfo(
                streamId = body.optString("stream_id"),
                clientId = body.optInt("client_id"),
                name = body.optString("name"),
                audio = body.optBoolean("audio"),
            )
            "StreamStopped" -> ScreenShareEvent.StreamStopped(
                streamId = body.optString("stream_id"),
            )
            "JoinRequest" -> ScreenShareEvent.JoinRequest(
                streamId = body.optString("stream_id"),
                viewerId = body.optInt("viewer_id"),
            )
            "RespondJoinRequest" -> ScreenShareEvent.RespondJoinRequest(
                streamId = body.optString("stream_id"),
                offer = body.optString("offer"),
            )
            "Signal" -> ScreenShareEvent.Signal(
                streamId = body.optString("stream_id"),
                peerId = body.optInt("peer_id"),
                json = body.optString("json"),
                signal = body.optJSONObject("signal")?.let { parseSignal(it) },
            )
            "ViewerLeft" -> ScreenShareEvent.ViewerLeft(
                streamId = body.optString("stream_id"),
                viewerId = body.optInt("viewer_id"),
            )
            else -> null
        }
    }

    private fun parseSignal(root: JSONObject): ScreenShareSignal? {
        val key = root.keys().asSequence().firstOrNull() ?: return null
        val body = root.optJSONObject(key) ?: JSONObject()
        return when (key) {
            "Offer" -> ScreenShareSignal.Offer(body.optString("sdp"))
            "JoinResponse" -> ScreenShareSignal.JoinResponse(
                decision = body.optBoolean("decision", true),
                offer = if (body.isNull("offer")) null else body.optString("offer"),
            )
            "Answer" -> ScreenShareSignal.Answer(body.optString("sdp"))
            "IceCandidate" -> ScreenShareSignal.IceCandidate(
                candidate = body.optString("candidate"),
                mid = if (body.isNull("mid")) null else body.optString("mid"),
                mLine = if (body.isNull("m_line")) null else body.optInt("m_line"),
            )
            "Unknown" -> ScreenShareSignal.Unknown(body.optString("cmd"))
            else -> null
        }
    }
}
