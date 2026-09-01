package com.mooncc.teamspeak9.ui.screen.server

import androidx.compose.runtime.Composable
import com.mooncc.teamspeak9.domain.model.Channel
import com.mooncc.teamspeak9.domain.model.ChatTarget
import com.mooncc.teamspeak9.domain.model.ServerGroup

/**
 * Renders whichever dialog the [ServerViewModel] currently requests.
 */
@Composable
fun ServerDialogHost(
    state: ServerUiState,
    serverGroups: List<ServerGroup>,
    allChannels: List<Channel>,
    viewModel: ServerViewModel,
) {
    val dismiss = viewModel::dismissDialog
    when (val dialog = state.dialog) {
        ServerDialog.None -> Unit

        is ServerDialog.ChannelPassword -> ChannelPasswordDialog(
            channel = dialog.channel,
            onDismiss = dismiss,
            onConfirm = { password ->
                viewModel.joinChannelWithPassword(dialog.channel.id, password)
            },
        )

        is ServerDialog.ClientActions -> ClientActionsDialog(
            client = dialog.client,
            isLocal = dialog.client.id == state.connection.localClientId,
            onDismiss = dismiss,
            onOpenChat = {
                dismiss()
                viewModel.openConversation(
                    ChatTarget.CLIENT,
                    dialog.client.id,
                    dialog.client.nickname,
                )
            },
            onPoke = { viewModel.showPoke(dialog.client) },
            onInfo = { viewModel.showClientInfo(dialog.client) },
            onServerGroups = { viewModel.showServerGroups(dialog.client) },
            onMove = { viewModel.showMoveClient(dialog.client) },
            onKickChannel = { viewModel.showKick(dialog.client, fromServer = false) },
            onKickServer = { viewModel.showKick(dialog.client, fromServer = true) },
            onBan = { viewModel.showBan(dialog.client) },
        )

        is ServerDialog.ClientInfo -> ClientInfoDialog(
            client = dialog.client,
            onDismiss = dismiss,
        )

        is ServerDialog.ChannelActions -> ChannelActionsDialog(
            channel = dialog.channel,
            canManage = true,
            onDismiss = dismiss,
            onJoin = {
                dismiss()
                viewModel.joinChannel(dialog.channel)
            },
            onOpenChat = {
                dismiss()
                viewModel.openConversation(
                    ChatTarget.CHANNEL,
                    dialog.channel.id,
                    dialog.channel.displayName,
                )
            },
            onInfo = { viewModel.showChannelInfo(dialog.channel) },
            onCreateSub = { viewModel.showCreateChannel(dialog.channel.id) },
            onEdit = { viewModel.showEditChannel(dialog.channel) },
            onDelete = { viewModel.deleteChannel(dialog.channel.id) },
        )

        is ServerDialog.ChannelInfo -> ChannelInfoDialog(
            channel = dialog.channel,
            onDismiss = dismiss,
        )

        is ServerDialog.CreateChannel -> ChannelEditorDialog(
            existing = null,
            parentId = dialog.parentId,
            onDismiss = dismiss,
            onCreate = { name, password, topic, description, permanent, semi, maxClients ->
                viewModel.createChannel(
                    name = name,
                    parentId = dialog.parentId,
                    password = password,
                    topic = topic,
                    description = description,
                    permanent = permanent,
                    semiPermanent = semi,
                    maxClients = maxClients,
                )
            },
            onEdit = {},
        )

        is ServerDialog.EditChannel -> ChannelEditorDialog(
            existing = dialog.channel,
            parentId = dialog.channel.parentId,
            onDismiss = dismiss,
            onCreate = { _, _, _, _, _, _, _ -> },
            onEdit = { properties -> viewModel.editChannel(dialog.channel.id, properties) },
        )

        is ServerDialog.Poke -> PokeDialog(
            client = dialog.client,
            onDismiss = dismiss,
            onConfirm = { message -> viewModel.poke(dialog.client.id, message) },
        )

        is ServerDialog.Kick -> KickDialog(
            client = dialog.client,
            fromServer = dialog.fromServer,
            onDismiss = dismiss,
            onConfirm = { reason ->
                viewModel.kick(dialog.client.id, reason, dialog.fromServer)
            },
        )

        is ServerDialog.Ban -> BanDialog(
            client = dialog.client,
            onDismiss = dismiss,
            onConfirm = { duration, reason ->
                viewModel.ban(dialog.client.id, duration, reason)
            },
        )

        is ServerDialog.ServerGroups -> ServerGroupsDialog(
            client = dialog.client,
            groups = serverGroups,
            onDismiss = dismiss,
            onToggle = { groupId, add ->
                viewModel.toggleServerGroup(dialog.client, groupId, add)
            },
        )

        is ServerDialog.MoveClient -> MoveClientDialog(
            client = dialog.client,
            channels = flattenChannels(allChannels),
            onDismiss = dismiss,
            onConfirm = { channelId -> viewModel.moveClient(dialog.client.id, channelId) },
        )

        ServerDialog.Nickname -> TextInputDialog(
            title = "修改昵称",
            label = "昵称",
            initialValue = state.connection.bookmark?.nickname.orEmpty(),
            onDismiss = dismiss,
            onConfirm = viewModel::setNickname,
        )

        ServerDialog.AwayMessage -> AwayDialog(
            initialAway = state.media.isAway,
            initialMessage = state.media.awayMessage,
            onDismiss = dismiss,
            onConfirm = viewModel::setAway,
        )

        ServerDialog.ScreenShareOptions -> ScreenShareOptionsDialog(
            config = state.screenShare.config,
            serverModeAvailable = state.screenShare.serverModeAvailable,
            onDismiss = dismiss,
            onConfirm = viewModel::applyScreenShareConfig,
        )
    }
}

/** Depth-first flatten so the move dialog lists every channel with indent-free names. */
private fun flattenChannels(tree: List<Channel>): List<Channel> {
    val out = mutableListOf<Channel>()
    fun visit(channels: List<Channel>) {
        channels.forEach { channel ->
            out += channel
            visit(channel.subChannels)
        }
    }
    visit(tree)
    return out
}
