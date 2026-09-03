// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using Microsoft.Extensions.Logging;
using TeamSpeak9.Core.Connection;
using TeamSpeak9.Core.Model;
using TSLib.Commands;
using TSLib.Full;
using TSLib.Helper;
using TSLib.Messages;

namespace TeamSpeak9.Core.Management;

/// <summary>
/// The editable state of the virtual server, as the server settings dialog collects it.
/// </summary>
/// <remarks>
/// Wider than TSLib's <c>ServerEdit</c> message class, which lacks <c>virtualserver_port</c>,
/// <c>_autostart</c>, the three <c>_min_*_version</c> fields and
/// <c>_antiflood_points_needed_plugin_block</c>. That is why <see cref="ServerService"/> builds the
/// command by hand.
/// </remarks>
public sealed record ServerDraft
{
    public string Name { get; init; } = string.Empty;

    public string PhoneticName { get; init; } = string.Empty;

    public string WelcomeMessage { get; init; } = string.Empty;

    public HostMessageDisplay WelcomeMessageDisplay { get; init; } = HostMessageDisplay.None;

    public string Hostmessage { get; init; } = string.Empty;

    public ushort MaxClients { get; init; }

    public ushort ReservedSlots { get; init; }

    /// <summary>Plain text; empty means "leave alone". Clearing is a separate operation.</summary>
    public string Password { get; init; } = string.Empty;

    public VoiceEncryptionMode VoiceEncryption { get; init; } = VoiceEncryptionMode.Individual;

    public bool WeblistEnabled { get; init; }

    /// <summary>0..8. The number of leading zero bits an identity's hash must have.</summary>
    public byte IdentitySecurityLevel { get; init; }

    public TimeSpan TempChannelDefaultDeleteDelay { get; init; }

    /// <summary>0.0 .. 1.0.</summary>
    public float PrioritySpeakerDimmModificator { get; init; }

    public uint MinClientsInChannelBeforeForcedSilence { get; init; }

    public HostBannerInfo Banner { get; init; } = HostBannerInfo.Empty;

    public ServerLogging Logging { get; init; } = new();

    public ServerAntiflood Antiflood { get; init; } = new();

    public ServerComplaints Complaints { get; init; } = new();

    public ServerTransferLimits Transfers { get; init; } = new();

    /// <summary>Basic checks that do not need a round trip.</summary>
    public string? Validate()
    {
        if (string.IsNullOrWhiteSpace(Name))
            return "服务器名称不能为空。";

        if (Name.Length > 64)
            return "服务器名称不能超过 64 个字符。";

        if (MaxClients == 0)
            return "最大客户端数必须大于 0。";

        if (ReservedSlots >= MaxClients)
            return "保留槽位必须小于最大客户端数。";

        if (IdentitySecurityLevel > 8)
            return "所需身份安全等级必须在 0 到 8 之间。";

        if (PrioritySpeakerDimmModificator is < 0f or > 1f)
            return "优先发言者音量衰减必须在 0 到 1 之间。";

        if (TempChannelDefaultDeleteDelay < TimeSpan.Zero)
            return "临时频道默认删除延迟不能为负数。";

        return null;
    }
}

/// <summary>Which event categories the server writes to its log.</summary>
public sealed record ServerLogging
{
    public bool Client { get; init; } = true;

    public bool Query { get; init; } = true;

    public bool Channel { get; init; } = true;

    public bool Permissions { get; init; } = true;

    public bool Server { get; init; } = true;

    public bool FileTransfer { get; init; } = true;
}

/// <summary>The flood protection thresholds.</summary>
public sealed record ServerAntiflood
{
    /// <summary>Points refunded per tick.</summary>
    public uint PointsTickReduce { get; init; } = 5;

    public uint PointsNeededCommandBlock { get; init; } = 150;

    public uint PointsNeededPluginBlock { get; init; } = 150;

    public uint PointsNeededIpBlock { get; init; } = 250;
}

/// <summary>How complaints escalate to a ban.</summary>
public sealed record ServerComplaints
{
    public uint AutobanCount { get; init; } = 5;

    public TimeSpan AutobanTime { get; init; } = TimeSpan.FromMinutes(5);

    public TimeSpan RemoveTime { get; init; } = TimeSpan.FromMinutes(15);
}

/// <summary>File transfer bandwidth and quota limits.</summary>
public sealed record ServerTransferLimits
{
    /// <summary>Bytes per second. <see cref="ulong.MaxValue"/> means unlimited.</summary>
    public ulong MaxDownloadBandwidth { get; init; } = ulong.MaxValue;

    /// <inheritdoc cref="MaxDownloadBandwidth"/>
    public ulong MaxUploadBandwidth { get; init; } = ulong.MaxValue;

    /// <summary>MBytes per month. <see cref="UnlimitedQuota"/> means unlimited.</summary>
    /// <remarks>
    /// The quota fields are 32-bit on the wire even though the bandwidth ones are 64-bit, so the
    /// unlimited sentinel differs: sending <see cref="ulong.MaxValue"/> here makes tsserver answer
    /// <c>1540 convert error</c> and reject the whole <c>serveredit</c>.
    /// </remarks>
    public ulong DownloadQuota { get; init; } = UnlimitedQuota;

    /// <inheritdoc cref="DownloadQuota"/>
    public ulong UploadQuota { get; init; } = UnlimitedQuota;

    /// <summary>The value tsserver itself reports for an unlimited monthly quota.</summary>
    public const ulong UnlimitedQuota = uint.MaxValue;
}

/// <summary>
/// The read-only counters and runtime facts that only <c>servergetvariables</c> reports.
/// </summary>
public sealed record ServerStatistics
{
    public ushort Port { get; init; }

    public bool Autostart { get; init; }

    public TimeSpan Uptime { get; init; }

    public ushort ClientsOnline { get; init; }

    public ushort QueriesOnline { get; init; }

    public ulong ChannelsOnline { get; init; }

    public ulong ClientConnections { get; init; }

    public ulong QueryConnections { get; init; }

    public float PingTotal { get; init; }

    public float PacketlossTotal { get; init; }

    public ulong BytesDownloadedTotal { get; init; }

    public ulong BytesUploadedTotal { get; init; }

    public ulong BytesDownloadedMonth { get; init; }

    public ulong BytesUploadedMonth { get; init; }

    public string MachineId { get; init; } = string.Empty;

    public bool HasPassword { get; init; }

    /// <summary>Encoded client version, as <c>virtualserver_min_client_version</c> reports it.</summary>
    public uint MinClientVersion { get; init; }

    public uint MinAndroidVersion { get; init; }

    public uint MinIosVersion { get; init; }
}

/// <summary>
/// Reading and writing virtual server settings.
/// </summary>
/// <remarks>
/// <para>
/// Every write is a hand-built <c>serveredit</c>. TSLib exposes no <c>ServerEdit()</c> method at
/// all, and its generated <c>ServerEdit</c> message class is missing several fields the official
/// command accepts.
/// </para>
/// <para>
/// Reading needs two sources, because neither is complete: <c>servergetvariables</c> returns the
/// counters and most settings but not the server name, icon, phonetic name or default groups, while
/// the connection book (populated from <c>initserver</c>) has exactly those.
/// </para>
/// </remarks>
public sealed class ServerService
{
    private readonly TsConnection connection;
    private readonly ILogger<ServerService> log;

    public ServerService(TsConnection connection, ILogger<ServerService> log)
    {
        ArgumentNullException.ThrowIfNull(connection);
        ArgumentNullException.ThrowIfNull(log);

        this.connection = connection;
        this.log = log;
    }

    /// <summary>
    /// Loads the current settings for an edit dialog, merging the book snapshot with
    /// <c>servergetvariables</c>.
    /// </summary>
    public async Task<CommandOutcome<ServerDraft>> LoadDraftAsync()
    {
        var snapshot = connection.Snapshot;

        var variables = await GetVariablesAsync().ConfigureAwait(false);
        if (!variables.Ok)
            return CommandOutcome<ServerDraft>.Fail(variables.Message);

        var v = variables.Value!;

        return CommandOutcome<ServerDraft>.Success(new ServerDraft
        {
            // Book-only fields.
            Name = snapshot.Name,
            PhoneticName = snapshot.PhoneticName,

            // servergetvariables fields.
            WelcomeMessage = v.WelcomeMessage ?? string.Empty,
            WelcomeMessageDisplay = (HostMessageDisplay)(int)v.HostmessageMode,
            Hostmessage = v.Hostmessage ?? string.Empty,
            MaxClients = v.MaxClients,
            ReservedSlots = v.ReservedSlots,
            WeblistEnabled = v.WeblistEnabled,
            IdentitySecurityLevel = v.IdentitySecurityLevel,
            MinClientsInChannelBeforeForcedSilence = v.MinClientsInChannelBeforeForcedSilence,

            // Neither servergetvariables nor ServerUpdated carries these two; the book does.
            VoiceEncryption = snapshot.VoiceEncryption,
            TempChannelDefaultDeleteDelay = snapshot.TempChannelDefaultDeleteDelay,
            PrioritySpeakerDimmModificator = snapshot.PrioritySpeakerDimmModificator,
            Banner = snapshot.Banner,

            Logging = new ServerLogging
            {
                Client = v.LogClient,
                Query = v.LogQuery,
                Channel = v.LogChannel,
                Permissions = v.LogPermissions,
                Server = v.LogServer,
                FileTransfer = v.LogFileTransfer,
            },
            Antiflood = new ServerAntiflood
            {
                PointsTickReduce = v.AntifloodPointsTickReduce,
                PointsNeededCommandBlock = v.AntifloodPointsToCommandBlock,
                PointsNeededPluginBlock = v.AntifloodPointsToPluginBlock,
                PointsNeededIpBlock = v.AntifloodPointsToIpBlock,
            },
            Complaints = new ServerComplaints
            {
                AutobanCount = v.ComplainAutobanCount,
                AutobanTime = v.ComplainAutobanTime,
                RemoveTime = v.ComplainRemoveTime,
            },
            Transfers = new ServerTransferLimits
            {
                MaxDownloadBandwidth = v.MaxDownloadTotalBandwidth,
                MaxUploadBandwidth = v.MaxUploadTotalBandwidth,
                DownloadQuota = v.DownloadQuota,
                UploadQuota = v.UploadQuota,
            },
        });
    }

    /// <summary>Reads the counters and runtime facts for the server info panel.</summary>
    public async Task<CommandOutcome<ServerStatistics>> GetStatisticsAsync()
    {
        var variables = await GetVariablesAsync().ConfigureAwait(false);
        if (!variables.Ok)
            return CommandOutcome<ServerStatistics>.Fail(variables.Message);

        var v = variables.Value!;
        return CommandOutcome<ServerStatistics>.Success(new ServerStatistics
        {
            Port = v.VirtualServerPort,
            Autostart = v.Autostart,
            Uptime = v.Uptime,
            ClientsOnline = v.ClientsOnline,
            QueriesOnline = v.QueriesOnline,
            ChannelsOnline = v.ChannelsOnline,
            ClientConnections = v.ClientConnections,
            QueryConnections = v.QueryConnections,
            PingTotal = v.PingTotal,
            PacketlossTotal = v.PacketlossTotal,
            BytesDownloadedTotal = v.BytesDownloadedTotal,
            BytesUploadedTotal = v.BytesUploadedTotal,
            BytesDownloadedMonth = v.BytesDownloadedMonth,
            BytesUploadedMonth = v.BytesUploadedMonth,
            MachineId = v.MachineId ?? string.Empty,
            HasPassword = v.HasPassword,
            MinClientVersion = v.MinClientVersion,
            MinAndroidVersion = v.MinAndroidVersion,
            MinIosVersion = v.MinIosVersion,
        });
    }

    /// <summary>Applies a draft with one <c>serveredit</c>.</summary>
    public async Task<CommandOutcome> EditAsync(ServerDraft draft)
    {
        ArgumentNullException.ThrowIfNull(draft);

        if (draft.Validate() is { } invalid)
            return CommandOutcome.Fail(invalid);

        var command = BuildEdit(draft);

        var outcome = CommandOutcome.From(await connection.ExecuteAsync(client => client.SendVoid(command)).ConfigureAwait(false));
        if (!outcome.Ok)
        {
            log.LogWarning("编辑服务器失败：{Message}", outcome.Message);
            return outcome;
        }

        log.LogInformation("已保存服务器设置");
        return CommandOutcome.Success;
    }

    /// <summary>
    /// Builds the <c>serveredit</c> command. Exposed for tests, which assert the wire form without
    /// needing a server.
    /// </summary>
    internal static TsCommand BuildEdit(ServerDraft draft)
    {
        var command = new TsCommand("serveredit")
        {
            new CommandParameter("virtualserver_name", draft.Name),
            new CommandParameter("virtualserver_name_phonetic", draft.PhoneticName),
            new CommandParameter("virtualserver_welcomemessage", draft.WelcomeMessage),
            new CommandParameter("virtualserver_hostmessage", draft.Hostmessage),
            new CommandParameter("virtualserver_hostmessage_mode", (int)draft.WelcomeMessageDisplay),
            new CommandParameter("virtualserver_maxclients", draft.MaxClients),
            new CommandParameter("virtualserver_reserved_slots", draft.ReservedSlots),
            new CommandParameter("virtualserver_codec_encryption_mode", (int)draft.VoiceEncryption),
            new CommandParameter("virtualserver_weblist_enabled", draft.WeblistEnabled),
            new CommandParameter("virtualserver_needed_identity_security_level", draft.IdentitySecurityLevel),
            new CommandParameter(
                "virtualserver_channel_temp_delete_delay_default",
                (ulong)draft.TempChannelDefaultDeleteDelay.TotalSeconds),
            new CommandParameter("virtualserver_priority_speaker_dimm_modificator", draft.PrioritySpeakerDimmModificator),
            new CommandParameter(
                "virtualserver_min_clients_in_channel_before_forced_silence",
                draft.MinClientsInChannelBeforeForcedSilence),
        };

        AppendLogging(command, draft.Logging);
        AppendAntiflood(command, draft.Antiflood);
        AppendComplaints(command, draft.Complaints);
        AppendTransfers(command, draft.Transfers);
        AppendBanner(command, draft.Banner);

        if (draft.Password.Length > 0)
            command.Add(new CommandParameter("virtualserver_password", TsCrypt.HashPassword(draft.Password)));

        return command;
    }

    /// <summary>Renames the server without touching anything else.</summary>
    public async Task<CommandOutcome> RenameAsync(string name)
    {
        if (string.IsNullOrWhiteSpace(name))
            return CommandOutcome.Fail("服务器名称不能为空。");

        var command = new TsCommand("serveredit")
        {
            new CommandParameter("virtualserver_name", name),
        };

        return CommandOutcome.From(await connection.ExecuteAsync(client => client.SendVoid(command)).ConfigureAwait(false));
    }

    /// <summary>Clears the server password.</summary>
    public async Task<CommandOutcome> ClearPasswordAsync()
    {
        var command = new TsCommand("serveredit")
        {
            new CommandParameter("virtualserver_password", string.Empty),
        };

        return CommandOutcome.From(await connection.ExecuteAsync(client => client.SendVoid(command)).ConfigureAwait(false));
    }

    /// <summary>Sets the welcome message and how it is displayed.</summary>
    public async Task<CommandOutcome> SetWelcomeMessageAsync(string message, HostMessageDisplay display)
    {
        var command = new TsCommand("serveredit")
        {
            new CommandParameter("virtualserver_welcomemessage", message ?? string.Empty),
            new CommandParameter("virtualserver_hostmessage_mode", (int)display),
        };

        return CommandOutcome.From(await connection.ExecuteAsync(client => client.SendVoid(command)).ConfigureAwait(false));
    }

    /// <summary>Sets the host banner and host button block on its own.</summary>
    public async Task<CommandOutcome> SetBannerAsync(HostBannerInfo banner)
    {
        ArgumentNullException.ThrowIfNull(banner);

        var command = new TsCommand("serveredit");
        AppendBanner(command, banner);

        return CommandOutcome.From(await connection.ExecuteAsync(client => client.SendVoid(command)).ConfigureAwait(false));
    }

    /// <summary>
    /// Points the client at the screen sharing relay for this server.
    /// </summary>
    /// <remarks>
    /// <c>virtualserver_sfu_endpoint</c> is a custom property. tsserver stores and echoes back
    /// unknown <c>virtualserver_*</c> keys across sessions, which lets a server operator advertise
    /// the companion stream service without any server-side plugin. An empty value disables it.
    /// </remarks>
    public async Task<CommandOutcome> SetStreamEndpointAsync(string endpoint)
    {
        var command = new TsCommand("serveredit")
        {
            new CommandParameter("virtualserver_sfu_endpoint", endpoint ?? string.Empty),
        };

        var outcome = CommandOutcome.From(await connection.ExecuteAsync(client => client.SendVoid(command)).ConfigureAwait(false));
        if (outcome.Ok)
            log.LogInformation("已设置屏幕共享服务地址：{Endpoint}", endpoint);

        return outcome;
    }

    private async Task<CommandOutcome<ServerUpdated>> GetVariablesAsync()
    {
        var result = await connection.ExecuteAsync(
            client => client.GetServerVariables(),
            R<ServerUpdated, CommandError>.Err(CommandError.ConnectionClosed)).ConfigureAwait(false);

        return CommandOutcome<ServerUpdated>.From(result);
    }

    private static void AppendLogging(TsCommand command, ServerLogging logging)
    {
        command.Add(new CommandParameter("virtualserver_log_client", logging.Client));
        command.Add(new CommandParameter("virtualserver_log_query", logging.Query));
        command.Add(new CommandParameter("virtualserver_log_channel", logging.Channel));
        command.Add(new CommandParameter("virtualserver_log_permissions", logging.Permissions));
        command.Add(new CommandParameter("virtualserver_log_server", logging.Server));
        command.Add(new CommandParameter("virtualserver_log_filetransfer", logging.FileTransfer));
    }

    private static void AppendAntiflood(TsCommand command, ServerAntiflood antiflood)
    {
        command.Add(new CommandParameter("virtualserver_antiflood_points_tick_reduce", antiflood.PointsTickReduce));
        command.Add(new CommandParameter("virtualserver_antiflood_points_needed_command_block", antiflood.PointsNeededCommandBlock));
        command.Add(new CommandParameter("virtualserver_antiflood_points_needed_plugin_block", antiflood.PointsNeededPluginBlock));
        command.Add(new CommandParameter("virtualserver_antiflood_points_needed_ip_block", antiflood.PointsNeededIpBlock));
    }

    private static void AppendComplaints(TsCommand command, ServerComplaints complaints)
    {
        command.Add(new CommandParameter("virtualserver_complain_autoban_count", complaints.AutobanCount));
        command.Add(new CommandParameter("virtualserver_complain_autoban_time", (ulong)complaints.AutobanTime.TotalSeconds));
        command.Add(new CommandParameter("virtualserver_complain_remove_time", (ulong)complaints.RemoveTime.TotalSeconds));
    }

    private static void AppendTransfers(TsCommand command, ServerTransferLimits transfers)
    {
        command.Add(new CommandParameter("virtualserver_max_download_total_bandwidth", transfers.MaxDownloadBandwidth));
        command.Add(new CommandParameter("virtualserver_max_upload_total_bandwidth", transfers.MaxUploadBandwidth));

        // Clamped because the quota fields are 32-bit server-side; anything larger is a convert error.
        command.Add(new CommandParameter("virtualserver_download_quota", ClampQuota(transfers.DownloadQuota)));
        command.Add(new CommandParameter("virtualserver_upload_quota", ClampQuota(transfers.UploadQuota)));
    }

    private static ulong ClampQuota(ulong megabytes) =>
        Math.Min(megabytes, ServerTransferLimits.UnlimitedQuota);

    private static void AppendBanner(TsCommand command, HostBannerInfo banner)
    {
        command.Add(new CommandParameter("virtualserver_hostbanner_gfx_url", banner.GfxUrl));
        command.Add(new CommandParameter("virtualserver_hostbanner_url", banner.LinkUrl));
        command.Add(new CommandParameter("virtualserver_hostbanner_mode", (int)banner.Scaling));
        command.Add(new CommandParameter("virtualserver_hostbanner_gfx_interval", (ulong)banner.RefreshInterval.TotalSeconds));
        command.Add(new CommandParameter("virtualserver_hostbutton_gfx_url", banner.ButtonGfxUrl));
        command.Add(new CommandParameter("virtualserver_hostbutton_url", banner.ButtonUrl));
        command.Add(new CommandParameter("virtualserver_hostbutton_tooltip", banner.ButtonTooltip));
    }
}
