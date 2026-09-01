package com.mooncc.teamspeak9.domain.model

/**
 * Assembles the flat channel/client lists returned by the server into the
 * nested structure the UI renders.
 */
object ChannelTreeBuilder {

    /**
     * @param channels flat channel list (any order)
     * @param clients flat client list
     * @return root level channels with [Channel.subChannels] and [Channel.clients] filled
     */
    fun build(channels: List<Channel>, clients: List<Client>): List<Channel> {
        if (channels.isEmpty()) return emptyList()

        val clientsByChannel = clients
            .filterNot { it.isQuery }
            .groupBy { it.channelId }
            .mapValues { (_, list) -> list.sortedWith(clientComparator) }

        val childrenByParent = channels.groupBy { it.parentId }
        val roots = childrenByParent[0].orEmpty()

        return buildLevel(roots, childrenByParent, clientsByChannel, depth = 0)
    }

    /**
     * Flattens a channel tree into render rows, skipping children of collapsed channels.
     */
    fun flatten(
        tree: List<Channel>,
        collapsedChannelIds: Set<Int> = emptySet(),
    ): List<ChannelTreeRow> {
        val rows = mutableListOf<ChannelTreeRow>()
        fun visit(channel: Channel) {
            val collapsed = channel.id in collapsedChannelIds
            rows += ChannelTreeRow.ChannelRow(
                channel = channel,
                isCollapsed = collapsed,
                hasChildren = channel.subChannels.isNotEmpty() || channel.clients.isNotEmpty(),
            )
            if (collapsed) return
            channel.clients.forEach { client ->
                rows += ChannelTreeRow.ClientRow(client, channel.depth + 1)
            }
            channel.subChannels.forEach(::visit)
        }
        tree.forEach(::visit)
        return rows
    }

    /** Finds a channel anywhere in the tree. */
    fun findChannel(tree: List<Channel>, channelId: Int): Channel? {
        tree.forEach { channel ->
            if (channel.id == channelId) return channel
            findChannel(channel.subChannels, channelId)?.let { return it }
        }
        return null
    }

    /** Collects the ids of a channel and every ancestor, used to auto-expand. */
    fun pathToChannel(tree: List<Channel>, channelId: Int): List<Int> {
        fun search(channels: List<Channel>, acc: List<Int>): List<Int>? {
            channels.forEach { channel ->
                val next = acc + channel.id
                if (channel.id == channelId) return next
                search(channel.subChannels, next)?.let { return it }
            }
            return null
        }
        return search(tree, emptyList()).orEmpty()
    }

    private fun buildLevel(
        level: List<Channel>,
        childrenByParent: Map<Int, List<Channel>>,
        clientsByChannel: Map<Int, List<Client>>,
        depth: Int,
    ): List<Channel> {
        val ordered = orderSiblings(level)
        return ordered.map { channel ->
            val children = buildLevel(
                level = childrenByParent[channel.id].orEmpty(),
                childrenByParent = childrenByParent,
                clientsByChannel = clientsByChannel,
                depth = depth + 1,
            )
            val own = clientsByChannel[channel.id].orEmpty()
            val familyCount = own.size + children.sumOf { it.totalClientsFamily }
            channel.copy(
                depth = depth,
                subChannels = children,
                clients = own,
                totalClients = own.size,
                totalClientsFamily = familyCount,
            )
        }
    }

    /**
     * TeamSpeak orders siblings via a linked list: `channel_order` points at the
     * id of the previous sibling, `0` marks the first one. Falls back to name
     * ordering when the chain is broken.
     */
    private fun orderSiblings(siblings: List<Channel>): List<Channel> {
        if (siblings.size <= 1) return siblings
        val byPrevious = siblings.groupBy { it.order }
        val result = mutableListOf<Channel>()
        val visited = mutableSetOf<Int>()
        var cursor = 0
        while (true) {
            val next = byPrevious[cursor]?.firstOrNull { it.id !in visited } ?: break
            result += next
            visited += next.id
            cursor = next.id
        }
        val leftovers = siblings.filter { it.id !in visited }.sortedBy { it.name.lowercase() }
        return result + leftovers
    }

    /** Talk power descending, then nickname, matching desktop ordering. */
    private val clientComparator = compareByDescending<Client> { it.talkPower }
        .thenBy { it.nickname.lowercase() }
}

/**
 * A single row of the rendered channel tree.
 */
sealed interface ChannelTreeRow {
    val depth: Int
    val key: String

    data class ChannelRow(
        val channel: Channel,
        val isCollapsed: Boolean,
        val hasChildren: Boolean,
    ) : ChannelTreeRow {
        override val depth: Int get() = channel.depth
        override val key: String get() = "channel-${channel.id}"
    }

    data class ClientRow(
        val client: Client,
        override val depth: Int,
    ) : ChannelTreeRow {
        override val key: String get() = "client-${client.id}"
    }
}
