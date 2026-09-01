package com.mooncc.teamspeak9.voice.client

/**
 * Server notifications, normalised away from ts3j's event classes.
 *
 * ts3j hands out one class per notification with an inconsistent getter surface
 * (some expose typed accessors, others only the raw property map). Collapsing
 * them here keeps the repository layer free of ts3j types and gives every event
 * the same shape: an id plus the raw properties the server actually sent.
 */
internal sealed interface Ts3Event {
    data class Connected(val properties: Map<String, String>) : Ts3Event

    data class Disconnected(val reasonId: Int, val reasonMessage: String) : Ts3Event

    data class ChannelListReceived(val properties: Map<String, String>) : Ts3Event

    data class ChannelCreated(val channelId: Int, val properties: Map<String, String>) : Ts3Event

    data class ChannelEdited(val channelId: Int, val properties: Map<String, String>) : Ts3Event

    data class ChannelMoved(
        val channelId: Int,
        val parentId: Int,
        val order: Int,
    ) : Ts3Event

    data class ChannelDeleted(val channelId: Int) : Ts3Event

    data class ClientJoined(
        val clientId: Int,
        val channelId: Int,
        val properties: Map<String, String>,
    ) : Ts3Event

    data class ClientLeft(
        val clientId: Int,
        val reasonId: Int,
        val reasonMessage: String,
        val invokerName: String,
    ) : Ts3Event

    data class ClientMoved(
        val clientId: Int,
        val targetChannelId: Int,
        val invokerName: String,
        val reasonId: Int,
        val reasonMessage: String,
    ) : Ts3Event

    data class ClientUpdated(
        val clientId: Int,
        val properties: Map<String, String>,
    ) : Ts3Event

    data class ClientChannelGroupChanged(
        val clientId: Int,
        val channelGroupId: Int,
        val channelId: Int,
    ) : Ts3Event

    data class ClientServerGroupChanged(
        val clientId: Int,
        val serverGroupId: Int,
        val added: Boolean,
    ) : Ts3Event

    data class TextMessage(
        val targetMode: Int,
        val message: String,
        val invokerId: Int,
        val invokerName: String,
        val invokerUniqueId: String,
        val targetClientId: Int,
    ) : Ts3Event

    data class Poked(
        val invokerId: Int,
        val invokerName: String,
        val message: String,
    ) : Ts3Event

    data class ServerEdited(val properties: Map<String, String>) : Ts3Event

    data class ServerGroupListReceived(val properties: Map<String, String>) : Ts3Event

    data class ChannelGroupListReceived(val properties: Map<String, String>) : Ts3Event

    data class Failure(val message: String) : Ts3Event

    companion object {
        /** `notifytextmessage` target modes, mirroring `TextMessageTargetMode`. */
        const val TARGET_CLIENT = 1
        const val TARGET_CHANNEL = 2
        const val TARGET_SERVER = 3
    }
}
