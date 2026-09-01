package com.mooncc.teamspeak6.domain.model

/**
 * A server group (global role) definition.
 */
data class ServerGroup(
    val id: Int,
    val name: String,
    val type: GroupType = GroupType.REGULAR,
    val iconId: Long = 0,
    val sortId: Int = 0,
    val savedb: Boolean = true,
    val memberAddPower: Int = 0,
    val memberRemovePower: Int = 0,
    val nameMode: Int = 0,
)

/**
 * A channel group definition (role scoped to a channel).
 */
data class ChannelGroup(
    val id: Int,
    val name: String,
    val type: GroupType = GroupType.REGULAR,
    val iconId: Long = 0,
    val sortId: Int = 0,
    val savedb: Boolean = true,
)

enum class GroupType(val id: Int) {
    TEMPLATE(0),
    REGULAR(1),
    SERVER_QUERY(2),
    ;

    companion object {
        fun fromId(id: Int): GroupType = entries.firstOrNull { it.id == id } ?: REGULAR
    }
}

/**
 * A single permission assignment.
 */
data class Permission(
    val id: Int,
    val name: String,
    val value: Int,
    val negated: Boolean = false,
    val skipped: Boolean = false,
    val grantValue: Int = 0,
)
