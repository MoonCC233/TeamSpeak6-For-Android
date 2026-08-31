package com.mooncc.teamspeak6.data.remote

/**
 * Error thrown when the TeamSpeak query interface returns a non-zero error id.
 */
class TeamSpeakQueryException(
    val errorId: Int,
    override val message: String,
    val extraMessage: String? = null,
    val failedPermissionId: Int? = null,
) : Exception(message) {

    val isPermissionError: Boolean get() = errorId == ERROR_PERMISSIONS
    val isAuthError: Boolean get() = errorId == ERROR_CLIENT_NOT_LOGGED_IN || errorId == ERROR_INVALID_LOGIN
    val isChannelPasswordError: Boolean get() = errorId == ERROR_CHANNEL_INVALID_PASSWORD
    val isServerPasswordError: Boolean get() = errorId == ERROR_SERVER_INVALID_PASSWORD

    companion object {
        const val ERROR_OK = 0
        const val ERROR_DATABASE_EMPTY_RESULT = 1281
        const val ERROR_PERMISSIONS = 2568
        const val ERROR_INVALID_LOGIN = 520
        const val ERROR_CLIENT_NOT_LOGGED_IN = 515
        const val ERROR_CHANNEL_INVALID_PASSWORD = 781
        const val ERROR_SERVER_INVALID_PASSWORD = 3329
        const val ERROR_CHANNEL_ALREADY_IN = 770
        const val ERROR_CHANNEL_MAXCLIENTS_REACHED = 777
        const val ERROR_CHANNEL_MAXFAMILY_REACHED = 778
    }
}

/**
 * Thrown when the query endpoint is unreachable or returns malformed data.
 */
class TeamSpeakTransportException(
    override val message: String,
    override val cause: Throwable? = null,
) : Exception(message, cause)
