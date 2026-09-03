// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using Microsoft.Extensions.Logging;
using TeamSpeak9.Core.Connection;
using TeamSpeak9.Core.Model;
using TSLib;
using TSLib.Commands;
using TSLib.Full;
using TSLib.Helper;
using TSLib.Messages;

namespace TeamSpeak9.Core.Management;

/// <summary>
/// Creating, editing, moving and deleting channels.
/// </summary>
/// <remarks>
/// <para>
/// Most commands are hand-built <see cref="TsCommand"/> instances rather than TSLib's
/// <c>ChannelEdit</c>. Two reasons, both verified against a live TS6 server (see
/// <c>docs/desktop/tslib-ts6-compat.md</c> §5):
/// </para>
/// <list type="number">
/// <item>
/// TSLib has no parameters for <c>channel_banner_gfx_url</c>, <c>channel_banner_mode</c> or
/// <c>channel_flag_default</c>.
/// </item>
/// <item>
/// TSLib's <c>channel_delete_delay</c> is unconditional, but the server only accepts it on a
/// temporary channel. See <see cref="AppendKindFields"/>.
/// </item>
/// </list>
/// <para>
/// One edit is one command. Both type flags travel together with everything else: the server
/// requires the pair (see <see cref="AppendKindFields"/>) and happily takes all nineteen editable
/// properties at once.
/// </para>
/// <para>
/// Success is decided solely by the command's error code. The server emits
/// <c>notifychanneledited</c> even for edits it rejected, so notifications are not evidence.
/// </para>
/// </remarks>
public sealed class ChannelService
{
    private readonly TsConnection connection;
    private readonly ILogger<ChannelService> log;

    public ChannelService(TsConnection connection, ILogger<ChannelService> log)
    {
        ArgumentNullException.ThrowIfNull(connection);
        ArgumentNullException.ThrowIfNull(log);

        this.connection = connection;
        this.log = log;
    }

    /// <summary>Creates a channel below <paramref name="parentId"/> and returns its new id.</summary>
    /// <param name="draft">Validated by the caller; validated again here to be safe.</param>
    /// <param name="parentId">0 for a top level channel.</param>
    /// <param name="orderId">
    /// The channel this one is placed *below*, or 0 for first position. TeamSpeak's ordering is a
    /// linked list, not an index.
    /// </param>
    public async Task<CommandOutcome<ulong>> CreateAsync(ChannelDraft draft, ulong parentId = 0, ulong orderId = 0)
    {
        ArgumentNullException.ThrowIfNull(draft);

        if (draft.Validate() is { } invalid)
            return CommandOutcome<ulong>.Fail(invalid);

        var command = BuildCreate(draft, parentId, orderId);

        // channelcreate answers with notifychannelcreated rather than a plain error line, so this
        // one command cannot go through SendVoid.
        var result = await connection.ExecuteAsync(
            client => client.SendNotifyCommand(command, NotificationType.ChannelCreated),
            R<LazyNotification, CommandError>.Err(CommandError.ConnectionClosed)).ConfigureAwait(false);

        var created = result.UnwrapNotification<ChannelCreated>().MapToSingle();
        if (!created.Ok)
        {
            log.LogWarning("创建频道失败：{Error}", created.Error.ErrorFormat());
            return CommandOutcome<ulong>.Fail(CommandErrorText.Describe(created.Error));
        }

        var newId = created.Value.ChannelId.Value;
        log.LogInformation("已创建频道 {Name}（cid={Cid}）", draft.Name, newId);

        // Banner fields are not accepted by channelcreate, so they need a follow-up edit. A
        // failure here is reported but does not undo the creation.
        var banner = await ApplyBannerAsync(newId, draft).ConfigureAwait(false);
        if (!banner.Ok)
            return new CommandOutcome<ulong>(true, newId, banner.Message);

        return CommandOutcome<ulong>.Success(newId);
    }

    /// <summary>
    /// Applies <paramref name="draft"/> to an existing channel.
    /// </summary>
    /// <param name="hasPassword">
    /// Whether the channel currently has a password. Needed because the server never discloses the
    /// old value: an empty draft password means "keep" for a protected channel and "nothing to do"
    /// for an unprotected one. Clearing is an explicit operation.
    /// </param>
    /// <param name="currentName">
    /// The channel's name before the edit, so an unchanged name can be left out of the command.
    /// See <see cref="BuildEdit"/>.
    /// </param>
    public async Task<CommandOutcome> EditAsync(
        ulong channelId,
        ChannelDraft draft,
        bool hasPassword = false,
        string? currentName = null)
    {
        ArgumentNullException.ThrowIfNull(draft);

        if (draft.Validate() is { } invalid)
            return CommandOutcome.Fail(invalid);

        var command = BuildEdit(channelId, draft, hasPassword, currentName);

        var main = CommandOutcome.From(await connection.ExecuteAsync(client => client.SendVoid(command)).ConfigureAwait(false));
        if (!main.Ok)
        {
            log.LogWarning("编辑频道 {Cid} 失败：{Message}", channelId, main.Message);
            return main;
        }

        log.LogInformation("已编辑频道 {Cid}", channelId);
        return CommandOutcome.Success;
    }

    /// <summary>Renames a channel without touching anything else.</summary>
    public async Task<CommandOutcome> RenameAsync(ulong channelId, string name)
    {
        if (string.IsNullOrWhiteSpace(name))
            return CommandOutcome.Fail("频道名称不能为空。");

        var command = new TsCommand("channeledit")
        {
            new CommandParameter("cid", channelId),
            new CommandParameter("channel_name", name),
        };

        return CommandOutcome.From(await connection.ExecuteAsync(client => client.SendVoid(command)).ConfigureAwait(false));
    }

    /// <summary>Clears a channel's password.</summary>
    public async Task<CommandOutcome> ClearPasswordAsync(ulong channelId)
    {
        var command = new TsCommand("channeledit")
        {
            new CommandParameter("cid", channelId),
            new CommandParameter("channel_password", string.Empty),
        };

        return CommandOutcome.From(await connection.ExecuteAsync(client => client.SendVoid(command)).ConfigureAwait(false));
    }

    /// <summary>Deletes a channel.</summary>
    /// <param name="force">Also delete when clients are still inside.</param>
    public async Task<CommandOutcome> DeleteAsync(ulong channelId, bool force = false)
    {
        var result = await connection.ExecuteAsync(
            client => client.ChannelDelete(new ChannelId(channelId), force)).ConfigureAwait(false);

        var outcome = CommandOutcome.From(result);
        if (outcome.Ok)
            log.LogInformation("已删除频道 {Cid}（force={Force}）", channelId, force);
        else
            log.LogWarning("删除频道 {Cid} 失败：{Message}", channelId, outcome.Message);

        return outcome;
    }

    /// <summary>
    /// Reparents and/or reorders a channel.
    /// </summary>
    /// <param name="parentId">New parent, 0 for top level.</param>
    /// <param name="orderId">
    /// The channel to sit below within the new parent, 0 for first position. Note the wire key here
    /// is a bare <c>order</c>, unlike <c>channel_order</c> used by create/edit.
    /// </param>
    public async Task<CommandOutcome> MoveAsync(ulong channelId, ulong parentId, ulong orderId = 0)
    {
        var command = new TsCommand("channelmove")
        {
            new CommandParameter("cid", channelId),
            new CommandParameter("cpid", parentId),
            new CommandParameter("order", orderId),
        };

        var result = await connection.ExecuteAsync(client => client.SendVoid(command)).ConfigureAwait(false);

        // "already there" is what a drag that ended where it started looks like.
        var outcome = CommandOutcome.From(result);
        if (!outcome.Ok)
            log.LogWarning("移动频道 {Cid} 失败：{Message}", channelId, outcome.Message);

        return outcome;
    }

    /// <summary>
    /// Reads the fields <c>channellist</c> does not return: description and the two banner fields.
    /// </summary>
    /// <remarks>
    /// The response has no channel id of its own, so the caller must remember which channel it
    /// asked about.
    /// </remarks>
    public async Task<CommandOutcome<ChannelDetails>> GetDetailsAsync(ulong channelId)
    {
        var result = await connection.ExecuteAsync(
            client => client.ChannelInfo(new ChannelId(channelId)),
            R<ChannelInfoResponse[], CommandError>.Err(CommandError.ConnectionClosed)).ConfigureAwait(false);

        var info = result.MapToSingle();
        if (!info.Ok)
            return CommandOutcome<ChannelDetails>.Fail(CommandErrorText.Describe(info.Error));

        var value = info.Value;
        return CommandOutcome<ChannelDetails>.Success(new ChannelDetails
        {
            ChannelId = channelId,
            Description = value.Description ?? string.Empty,
            BannerGfxUrl = value.BannerGfxUrl ?? string.Empty,
            BannerMode = ParseBannerMode(value.BannerMode),
            HasPassword = value.HasPassword,
            FilePath = value.FilePath ?? string.Empty,
            IconId = IconId.FromSigned(value.IconId),
        });
    }

    /// <summary>
    /// Fills a draft with the fields only <c>channelinfo</c> knows, so an edit dialog opens with
    /// the real current values.
    /// </summary>
    public async Task<CommandOutcome<ChannelDraft>> LoadDraftAsync(ChannelNode node)
    {
        ArgumentNullException.ThrowIfNull(node);

        var draft = ChannelDraft.FromNode(node);
        var details = await GetDetailsAsync(node.ChannelId).ConfigureAwait(false);
        if (!details.Ok)
            return CommandOutcome<ChannelDraft>.Fail(details.Message);

        var value = details.Value!;
        return CommandOutcome<ChannelDraft>.Success(draft with
        {
            Description = value.Description,
            BannerGfxUrl = value.BannerGfxUrl,
            BannerMode = value.BannerMode,
        });
    }

    /// <summary>Makes a channel the server's default channel.</summary>
    public async Task<CommandOutcome> SetDefaultAsync(ulong channelId)
    {
        var command = new TsCommand("channeledit")
        {
            new CommandParameter("cid", channelId),
            new CommandParameter("channel_flag_default", true),
        };

        return CommandOutcome.From(await connection.ExecuteAsync(client => client.SendVoid(command)).ConfigureAwait(false));
    }

    /// <summary>
    /// Builds the <c>channelcreate</c> command. Exposed for tests, which assert the wire form
    /// without needing a server.
    /// </summary>
    internal static TsCommand BuildCreate(ChannelDraft draft, ulong parentId, ulong orderId)
    {
        var command = new TsCommand("channelcreate");
        AppendCommonFields(command, draft, includePassword: true);
        AppendKindFields(command, draft);

        if (parentId != 0)
            command.Add(new CommandParameter("cpid", parentId));

        command.Add(new CommandParameter("channel_order", orderId));
        return command;
    }

    /// <inheritdoc cref="BuildCreate"/>
    /// <param name="currentName">
    /// The channel's present name, or <see langword="null"/> when it is unknown. The server treats
    /// <c>channel_name</c> as a rename request and answers <c>channel_name_inuse</c> when the value
    /// equals the channel's own name, so an unchanged name has to be omitted entirely. Verified
    /// against TS6: no other field behaves this way. The comparison is ordinal because the server
    /// only rejects an exact match — <c>ceshi</c> to <c>CESHI</c> is a legal rename.
    /// </param>
    internal static TsCommand BuildEdit(
        ulong channelId,
        ChannelDraft draft,
        bool hasPassword,
        string? currentName = null)
    {
        var command = new TsCommand("channeledit");
        command.Add(new CommandParameter("cid", channelId));
        AppendCommonFields(
            command,
            draft,
            includePassword: false,
            includeName: !string.Equals(draft.Name, currentName, StringComparison.Ordinal));
        AppendKindFields(command, draft);
        AppendBannerFields(command, draft);

        if (draft.Password.Length > 0)
            command.Add(new CommandParameter("channel_password", TsCrypt.HashPassword(draft.Password)));
        else if (!hasPassword)
            command.Add(new CommandParameter("channel_password", string.Empty));

        return command;
    }

    /// <summary>
    /// Everything both <c>channelcreate</c> and <c>channeledit</c> accept, minus the type flags.
    /// </summary>
    private static void AppendCommonFields(
        TsCommand command,
        ChannelDraft draft,
        bool includePassword,
        bool includeName = true)
    {
        if (includeName)
            command.Add(new CommandParameter("channel_name", draft.Name));

        command.Add(new CommandParameter("channel_name_phonetic", draft.PhoneticName));
        command.Add(new CommandParameter("channel_topic", draft.Topic));
        command.Add(new CommandParameter("channel_description", draft.Description));

        command.Add(new CommandParameter("channel_codec", (byte)draft.Codec));
        command.Add(new CommandParameter("channel_codec_quality", draft.CodecQuality));
        command.Add(new CommandParameter("channel_codec_latency_factor", draft.CodecLatencyFactor));
        command.Add(new CommandParameter("channel_codec_is_unencrypted", draft.IsUnencrypted));

        AppendLimits(command, draft);
        command.Add(new CommandParameter("channel_needed_talk_power", draft.NeededTalkPower));

        if (draft.IsDefault)
            command.Add(new CommandParameter("channel_flag_default", true));

        if (includePassword && draft.Password.Length > 0)
            command.Add(new CommandParameter("channel_password", TsCrypt.HashPassword(draft.Password)));
    }

    private static void AppendLimits(TsCommand command, ChannelDraft draft)
    {
        var maxClientsUnlimited = draft.MaxClients.Kind != ChannelLimitKind.Limited;
        command.Add(new CommandParameter("channel_flag_maxclients_unlimited", maxClientsUnlimited));
        command.Add(new CommandParameter("channel_maxclients", maxClientsUnlimited ? 0 : draft.MaxClients.Count));

        var familyInherited = draft.MaxFamilyClients.Kind == ChannelLimitKind.Inherited;
        var familyUnlimited = draft.MaxFamilyClients.Kind == ChannelLimitKind.Unlimited;
        command.Add(new CommandParameter("channel_flag_maxfamilyclients_inherited", familyInherited));
        command.Add(new CommandParameter("channel_flag_maxfamilyclients_unlimited", familyUnlimited));
        command.Add(new CommandParameter(
            "channel_maxfamilyclients",
            draft.MaxFamilyClients.Kind == ChannelLimitKind.Limited ? draft.MaxFamilyClients.Count : 0));
    }

    /// <summary>
    /// The channel type, which both <c>channelcreate</c> and <c>channeledit</c> express as a pair of
    /// booleans.
    /// </summary>
    /// <remarks>
    /// <para>
    /// Both flags are always sent. Verified against TS6: a <c>channeledit</c> carrying only one of
    /// them is rejected with <c>channel_invalid_flags</c> unless it happens to agree with the current
    /// state, so the pair is the only reliable way to change a type. <c>1</c>/<c>1</c> is the single
    /// illegal combination and cannot be produced from a <see cref="ChannelKind"/>. Re-sending the
    /// type a channel already has is a no-op.
    /// </para>
    /// <para>
    /// <c>channel_delete_delay</c> is only legal on a temporary channel — on any other type the
    /// server answers <c>parameter_invalid</c>, even for a delay of zero.
    /// </para>
    /// </remarks>
    private static void AppendKindFields(TsCommand command, ChannelDraft draft)
    {
        command.Add(new CommandParameter("channel_flag_permanent", draft.Kind == ChannelKind.Permanent));
        command.Add(new CommandParameter("channel_flag_semi_permanent", draft.Kind == ChannelKind.SemiPermanent));

        if (draft.SupportsDeleteDelay)
            command.Add(new CommandParameter("channel_delete_delay", (ulong)draft.DeleteDelay.TotalSeconds));
    }

    private static void AppendBannerFields(TsCommand command, ChannelDraft draft)
    {
        command.Add(new CommandParameter("channel_banner_gfx_url", draft.BannerGfxUrl));
        command.Add(new CommandParameter("channel_banner_mode", (int)draft.BannerMode));
    }

    private async Task<CommandOutcome> ApplyBannerAsync(ulong channelId, ChannelDraft draft)
    {
        if (draft.BannerGfxUrl.Length == 0)
            return CommandOutcome.Success;

        var command = new TsCommand("channeledit");
        command.Add(new CommandParameter("cid", channelId));
        AppendBannerFields(command, draft);

        var outcome = CommandOutcome.From(await connection.ExecuteAsync(client => client.SendVoid(command)).ConfigureAwait(false));
        if (!outcome.Ok)
            log.LogWarning("设置频道 {Cid} 横幅失败：{Message}", channelId, outcome.Message);

        return outcome;
    }

    /// <summary>
    /// <c>channelinfo</c> returns the banner mode as a string, unlike every other numeric field.
    /// </summary>
    internal static HostBannerScaling ParseBannerMode(string? value) =>
        int.TryParse(value, System.Globalization.NumberStyles.Integer, System.Globalization.CultureInfo.InvariantCulture, out var mode)
        && Enum.IsDefined(typeof(HostBannerScaling), mode)
            ? (HostBannerScaling)mode
            : HostBannerScaling.KeepAspect;
}

/// <summary>The parts of a channel that only <c>channelinfo</c> reports.</summary>
public sealed record ChannelDetails
{
    public required ulong ChannelId { get; init; }

    public string Description { get; init; } = string.Empty;

    public string BannerGfxUrl { get; init; } = string.Empty;

    public HostBannerScaling BannerMode { get; init; } = HostBannerScaling.KeepAspect;

    public bool HasPassword { get; init; }

    /// <summary>The channel's file transfer directory, e.g. <c>/</c>.</summary>
    public string FilePath { get; init; } = string.Empty;

    public IconId IconId { get; init; } = IconId.None;
}
