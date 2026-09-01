package com.mooncc.teamspeak9.screenshare.signaling

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Encodes and decodes signaling frames.
 *
 * Decoding is deliberately lenient: a frame with an unrecognised `type` becomes
 * [ServerMessage.Unknown] instead of throwing, so a newer server can add
 * messages without breaking this client.
 */
object SignalingCodec {

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
        explicitNulls = false
    }

    fun encode(message: ClientMessage): String =
        json.encodeToString(ClientMessage.serializer(), message)

    /**
     * @return the decoded message, [ServerMessage.Unknown] for an unrecognised
     *   `type`, or `null` when the frame is not usable JSON at all.
     */
    fun decode(text: String): ServerMessage? {
        val type = runCatching {
            val root = json.parseToJsonElement(text) as? JsonObject ?: return null
            root["type"]?.jsonPrimitive?.content
        }.getOrNull() ?: return null

        if (type !in KNOWN_SERVER_TYPES) return ServerMessage.Unknown(type, text)

        return runCatching {
            json.decodeFromString(ServerMessage.serializer(), text)
        }.getOrElse { ServerMessage.Unknown(type, text) }
    }

    private val KNOWN_SERVER_TYPES = setOf(
        "welcome",
        "peer-joined",
        "peer-left",
        "share-started",
        "share-stopped",
        "watch-request",
        "offer",
        "answer",
        "candidate",
        "bye",
        "error",
        "ping",
        "pong",
    )
}
