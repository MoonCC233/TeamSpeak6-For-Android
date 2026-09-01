package com.mooncc.teamspeak9.domain.model

/**
 * A TeamSpeak virtual server as exposed through the query interface.
 */
data class VirtualServer(
    val id: Int,
    val uniqueIdentifier: String = "",
    val name: String,
    val welcomeMessage: String = "",
    val platform: String = "",
    val version: String = "",
    val clientsOnline: Int = 0,
    val queryClientsOnline: Int = 0,
    val maxClients: Int = 0,
    val channelsOnline: Int = 0,
    val uptimeSeconds: Long = 0,
    val hostMessage: String = "",
    val hostBannerUrl: String = "",
    val hostBannerGfxUrl: String = "",
    val iconId: Long = 0,
) {
    val voiceClientsOnline: Int get() = (clientsOnline - queryClientsOnline).coerceAtLeast(0)
}
