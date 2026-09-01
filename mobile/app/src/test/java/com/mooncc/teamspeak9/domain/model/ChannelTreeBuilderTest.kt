package com.mooncc.teamspeak9.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelTreeBuilderTest {

    private fun channel(id: Int, parentId: Int, order: Int, name: String) = Channel(
        id = id,
        parentId = parentId,
        order = order,
        name = name,
    )

    private fun client(id: Int, channelId: Int, nickname: String, talkPower: Int = 0) = Client(
        id = id,
        channelId = channelId,
        nickname = nickname,
        talkPower = talkPower,
    )

    @Test
    fun `orders siblings using the previous-sibling chain`() {
        val channels = listOf(
            channel(3, 0, 2, "Third"),
            channel(1, 0, 0, "First"),
            channel(2, 0, 1, "Second"),
        )

        val tree = ChannelTreeBuilder.build(channels, emptyList())

        assertEquals(listOf("First", "Second", "Third"), tree.map { it.name })
    }

    @Test
    fun `falls back to name ordering for broken chains`() {
        val channels = listOf(
            channel(5, 0, 99, "Zulu"),
            channel(6, 0, 98, "alpha"),
        )

        val tree = ChannelTreeBuilder.build(channels, emptyList())

        assertEquals(listOf("alpha", "Zulu"), tree.map { it.name })
    }

    @Test
    fun `nests sub channels and assigns depth`() {
        val channels = listOf(
            channel(1, 0, 0, "Root"),
            channel(2, 1, 0, "Child"),
            channel(3, 2, 0, "Grandchild"),
        )

        val tree = ChannelTreeBuilder.build(channels, emptyList())

        val root = tree.single()
        assertEquals(0, root.depth)
        val child = root.subChannels.single()
        assertEquals(1, child.depth)
        assertEquals(2, child.subChannels.single().depth)
    }

    @Test
    fun `counts family clients across sub channels`() {
        val channels = listOf(
            channel(1, 0, 0, "Root"),
            channel(2, 1, 0, "Child"),
        )
        val clients = listOf(
            client(10, 1, "a"),
            client(11, 2, "b"),
            client(12, 2, "c"),
        )

        val tree = ChannelTreeBuilder.build(channels, clients)

        val root = tree.single()
        assertEquals(1, root.totalClients)
        assertEquals(3, root.totalClientsFamily)
        assertEquals(2, root.subChannels.single().totalClientsFamily)
    }

    @Test
    fun `sorts clients by talk power then nickname`() {
        val channels = listOf(channel(1, 0, 0, "Root"))
        val clients = listOf(
            client(1, 1, "zoe", talkPower = 10),
            client(2, 1, "adam", talkPower = 50),
            client(3, 1, "beth", talkPower = 10),
        )

        val tree = ChannelTreeBuilder.build(channels, clients)

        assertEquals(listOf("adam", "beth", "zoe"), tree.single().clients.map { it.nickname })
    }

    @Test
    fun `excludes query clients from the tree`() {
        val channels = listOf(channel(1, 0, 0, "Root"))
        val clients = listOf(
            client(1, 1, "voice"),
            client(2, 1, "query").copy(type = ClientType.QUERY),
        )

        val tree = ChannelTreeBuilder.build(channels, clients)

        assertEquals(listOf("voice"), tree.single().clients.map { it.nickname })
    }

    @Test
    fun `flatten skips children of collapsed channels`() {
        val channels = listOf(
            channel(1, 0, 0, "Root"),
            channel(2, 1, 0, "Child"),
        )
        val clients = listOf(client(9, 2, "inside"))
        val tree = ChannelTreeBuilder.build(channels, clients)

        val expanded = ChannelTreeBuilder.flatten(tree)
        val collapsed = ChannelTreeBuilder.flatten(tree, collapsedChannelIds = setOf(1))

        assertEquals(3, expanded.size)
        assertEquals(1, collapsed.size)
        assertTrue(collapsed.single() is ChannelTreeRow.ChannelRow)
    }

    @Test
    fun `pathToChannel returns ancestors including target`() {
        val channels = listOf(
            channel(1, 0, 0, "Root"),
            channel(2, 1, 0, "Child"),
            channel(3, 2, 0, "Grandchild"),
        )
        val tree = ChannelTreeBuilder.build(channels, emptyList())

        assertEquals(listOf(1, 2, 3), ChannelTreeBuilder.pathToChannel(tree, 3))
    }

    @Test
    fun `findChannel searches nested levels`() {
        val channels = listOf(
            channel(1, 0, 0, "Root"),
            channel(2, 1, 0, "Child"),
        )
        val tree = ChannelTreeBuilder.build(channels, emptyList())

        assertEquals("Child", ChannelTreeBuilder.findChannel(tree, 2)?.name)
    }
}
