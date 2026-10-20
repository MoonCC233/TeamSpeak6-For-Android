package dev.tsdroid.pad.ui

sealed interface ChatTarget {
    data object Channel : ChatTarget
    data class Private(val key: String, val name: String) : ChatTarget
}

data class PrivateThread(
    val key: String,
    val name: String,
    val unread: Int,
)
