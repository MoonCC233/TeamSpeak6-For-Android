package com.mooncc.teamspeak6.data.remote

import com.mooncc.teamspeak6.data.remote.dto.QueryEnvelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Thin HTTP client for the TeamSpeak WebQuery interface.
 *
 * The WebQuery interface maps every ServerQuery command onto
 * `GET/POST /<virtualServerId>/<command>?<params>` and answers with a JSON
 * envelope. Authentication uses the `x-api-key` header.
 */
class WebQueryClient(
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val baseUrl: String,
    private val apiKey: String,
) {

    private val parsedBase: HttpUrl = requireNotNull(baseUrl.toHttpUrlOrNull()) {
        "Invalid WebQuery base url: $baseUrl"
    }

    /**
     * Executes [command] against virtual server [virtualServerId] and returns the
     * decoded body rows.
     *
     * @param params key/value query arguments; values are URL encoded by OkHttp.
     * @param flags valueless arguments such as `-continueonerror`.
     */
    suspend fun execute(
        command: String,
        virtualServerId: Int? = null,
        params: Map<String, String> = emptyMap(),
        flags: List<String> = emptyList(),
    ): List<JsonObject> {
        val url = parsedBase.newBuilder().apply {
            if (virtualServerId != null) addPathSegment(virtualServerId.toString())
            command.split('/').filter { it.isNotEmpty() }.forEach { addPathSegment(it) }
            params.forEach { (key, value) -> addQueryParameter(key, value) }
            flags.forEach { flag -> addQueryParameter(flag.removePrefix("-").let { "-$it" }, "") }
        }.build()

        val request = Request.Builder()
            .url(url)
            .header("x-api-key", apiKey)
            .header("Accept", "application/json")
            .get()
            .build()

        val raw = try {
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful && text.isBlank()) {
                    throw TeamSpeakTransportException(
                        "WebQuery HTTP ${response.code} for $command",
                    )
                }
                text
            }
        } catch (io: IOException) {
            throw TeamSpeakTransportException("WebQuery request failed: ${io.message}", io)
        }

        val envelope = try {
            json.decodeFromString(QueryEnvelope.serializer(), raw)
        } catch (t: Throwable) {
            throw TeamSpeakTransportException("Malformed WebQuery response for $command", t)
        }

        if (envelope.status.code != TeamSpeakQueryException.ERROR_OK) {
            if (envelope.status.code == TeamSpeakQueryException.ERROR_DATABASE_EMPTY_RESULT) {
                return emptyList()
            }
            throw TeamSpeakQueryException(
                errorId = envelope.status.code,
                message = envelope.status.message,
                extraMessage = envelope.status.extraMessage,
                failedPermissionId = envelope.status.failedPermissionId,
            )
        }
        return envelope.body
    }

    /** Executes [command] and returns the first row, or null when empty. */
    suspend fun executeSingle(
        command: String,
        virtualServerId: Int? = null,
        params: Map<String, String> = emptyMap(),
        flags: List<String> = emptyList(),
    ): JsonObject? = execute(command, virtualServerId, params, flags).firstOrNull()

    /** Executes [command] ignoring the returned body. */
    suspend fun executeVoid(
        command: String,
        virtualServerId: Int? = null,
        params: Map<String, String> = emptyMap(),
        flags: List<String> = emptyList(),
    ) {
        execute(command, virtualServerId, params, flags)
    }

    /**
     * Verifies that the endpoint answers and the api key is accepted.
     */
    suspend fun whoAmI(): JsonObject? = executeSingle("whoami")

    companion object {
        /** Reads a possibly missing string field. */
        fun JsonObject.str(key: String, default: String = ""): String =
            this[key]?.jsonPrimitive?.contentOrNullSafe() ?: default

        /** Reads a possibly missing int field. */
        fun JsonObject.int(key: String, default: Int = 0): Int =
            str(key).toIntOrNull() ?: default

        /** Reads a possibly missing long field. */
        fun JsonObject.long(key: String, default: Long = 0): Long =
            str(key).toLongOrNull() ?: default

        /** Reads a `0`/`1` flag. */
        fun JsonObject.bool(key: String, default: Boolean = false): Boolean =
            when (str(key)) {
                "1", "true" -> true
                "0", "false" -> false
                else -> default
            }

        /** Reads a comma separated list of ints. */
        fun JsonObject.intList(key: String): List<Int> =
            str(key).split(',').mapNotNull { it.trim().toIntOrNull() }

        private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
            runCatching { content }.getOrNull()
    }
}
