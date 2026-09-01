package com.mooncc.teamspeak9.voice.session

/**
 * Decides whether a dropped connection should be retried and how long to wait.
 *
 * Kept free of Android and coroutine dependencies so the backoff schedule can be
 * unit tested instead of only being observable on a device with flaky Wi-Fi.
 */
object ReconnectPolicy {

    /** Attempts after which we give up and leave the user to reconnect manually. */
    const val MAX_ATTEMPTS = 6

    private const val BASE_DELAY_MS = 2_000L
    private const val MAX_DELAY_MS = 30_000L

    /**
     * `reasonid` values that mean the server does not want us back. Retrying a
     * ban just spams the server until the ban expires, and reconnecting after a
     * kick fights the moderator who issued it.
     */
    private val NON_RETRYABLE_REASONS = setOf(5, 6)

    /**
     * @param attempt 1-based index of the retry that is about to be scheduled.
     * @return delay before that attempt, doubling from 2s and capped at 30s.
     */
    fun delayMsFor(attempt: Int): Long {
        if (attempt <= 1) return BASE_DELAY_MS
        val capped = attempt.coerceAtMost(MAX_SHIFT)
        val scaled = BASE_DELAY_MS shl (capped - 1)
        return scaled.coerceAtMost(MAX_DELAY_MS)
    }

    /**
     * @param attempt 1-based index of the retry being considered.
     * @param reasonId `reasonid` from the server's disconnect notification.
     * @param userInitiated whether the local user asked to disconnect.
     */
    fun shouldRetry(attempt: Int, reasonId: Int, userInitiated: Boolean): Boolean {
        if (userInitiated) return false
        if (reasonId in NON_RETRYABLE_REASONS) return false
        return attempt in 1..MAX_ATTEMPTS
    }

    /** Beyond this the shift would overflow long before the cap matters. */
    private const val MAX_SHIFT = 16
}
