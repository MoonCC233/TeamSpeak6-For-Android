package com.mooncc.teamspeak6.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Envelope returned by the TeamSpeak WebQuery interface.
 *
 * ```json
 * { "body": [ { ... } ], "status": { "code": 0, "message": "ok" } }
 * ```
 */
@Serializable
data class QueryEnvelope(
    val body: List<JsonObject> = emptyList(),
    val status: QueryStatus = QueryStatus(),
)

@Serializable
data class QueryStatus(
    val code: Int = 0,
    val message: String = "ok",
    @SerialName("extra_msg") val extraMessage: String? = null,
    @SerialName("failed_permid") val failedPermissionId: Int? = null,
)

/**
 * Some endpoints answer with a single object rather than a list.
 */
@Serializable
data class QuerySingleEnvelope(
    val body: JsonElement? = null,
    val status: QueryStatus = QueryStatus(),
)
