// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using Microsoft.Extensions.Logging;
using TeamSpeak9.Core.Connection;
using TeamSpeak9.Core.Model;
using TeamSpeak9.Core.Settings;
using TSLib;
using TSLib.Commands;
using TSLib.Helper;
using TSLib.Messages;

namespace TeamSpeak9.Core.Management;

/// <summary>An icon stored on the server.</summary>
public sealed record IconEntry
{
    public required IconId Id { get; init; }

    /// <summary>Size in bytes as reported by <c>ftgetfilelist</c>.</summary>
    public required ulong Size { get; init; }

    public DateTime Uploaded { get; init; }

    /// <summary>Absolute path of the local cache copy, whether or not it exists yet.</summary>
    public required string CachePath { get; init; }
}

/// <summary>A group that can carry an icon.</summary>
public sealed record IconGroup
{
    public required ulong GroupId { get; init; }

    public required string Name { get; init; }

    public required GroupKind Kind { get; init; }

    public IconId IconId { get; init; } = IconId.None;

    /// <summary>Server groups and channel groups live in different namespaces and use different commands.</summary>
    public required bool IsServerGroup { get; init; }

    public bool AllowsCustomIcon => GroupIconRules.AllowsCustomIcon(Kind);
}

/// <summary>
/// Uploading, listing, caching, deleting and assigning icons.
/// </summary>
/// <remarks>
/// <para>
/// Icons are files named <c>icon_&lt;crc32&gt;</c> in the virtual server's internal channel
/// (<c>cid=0</c>). Assigning one is a permission write, not a property write:
/// </para>
/// <list type="bullet">
/// <item>Channel: <c>channeladdperm cid=N permsid=i_icon_id permvalue=&lt;unsigned&gt;</c>. TS6 refuses
/// <c>channeledit channel_icon_id</c> in every value form.</item>
/// <item>Server group / channel group: <c>servergroupaddperm</c> / <c>channelgroupaddperm</c>. Only
/// <c>servergroupaddperm</c> additionally requires <c>permnegated</c> and <c>permskip</c>.</item>
/// <item>Virtual server: <c>serveredit virtualserver_icon_id</c>, the one property write that works.</item>
/// <item>Client icons are not supported by the server at all and are therefore not offered.</item>
/// </list>
/// <para>All measurements against tsserver 6.0.0-beta12.1; see docs/desktop/tslib-ts6-compat.md §4.</para>
/// </remarks>
public sealed class IconService
{
    /// <summary>The internal channel that holds a virtual server's icons.</summary>
    private static readonly ChannelId IconChannel = ChannelId.Null;

    /// <summary>
    /// The directory to list. Listing <c>/</c> only reports the <c>icons</c> directory entry itself.
    /// </summary>
    private const string IconDirectory = "/icons";

    /// <summary>
    /// <c>i_max_icon_filesize</c> on a stock server. Checked locally because the server's rejection
    /// arrives only after the whole file has been pushed.
    /// </summary>
    public const int MaxIconBytes = 8192;

    private readonly TsConnection connection;
    private readonly AppPaths paths;
    private readonly ILogger<IconService> log;

    public IconService(TsConnection connection, AppPaths paths, ILogger<IconService> log)
    {
        ArgumentNullException.ThrowIfNull(connection);
        ArgumentNullException.ThrowIfNull(paths);
        ArgumentNullException.ThrowIfNull(log);

        this.connection = connection;
        this.paths = paths;
        this.log = log;
    }

    /// <summary>Raised after an icon's cache file changes, so views can drop memoized bitmaps.</summary>
    public event EventHandler<IconId>? IconCached;

    /// <summary>Where <see cref="DownloadAsync"/> writes.</summary>
    public string CacheDirectory => paths.IconCacheDirectory;

    /// <summary>The local cache path for an icon, whether or not it has been downloaded.</summary>
    public string CachePathFor(IconId id) => Path.Combine(paths.IconCacheDirectory, id.ToFileName());

    /// <summary>Whether the icon is already in the local cache.</summary>
    public bool IsCached(IconId id) => !id.IsNone && File.Exists(CachePathFor(id));

    /// <summary>
    /// Checks an icon file before it is offered for upload.
    /// </summary>
    /// <returns>A user-facing complaint, or <c>null</c> when the file is acceptable.</returns>
    public static string? ValidateIcon(ReadOnlySpan<byte> content)
    {
        if (content.Length == 0)
            return "图标文件为空。";

        if (content.Length > MaxIconBytes)
            return $"图标文件为 {content.Length} 字节，超过服务器上限 {MaxIconBytes} 字节。";

        return IsSupportedImage(content) ? null : "图标必须是 PNG、JPEG、GIF 或 BMP 图片。";
    }

    /// <summary>The icon id a file will get once uploaded, without contacting the server.</summary>
    public static IconId PredictId(ReadOnlySpan<byte> content) => Crc32.ComputeIconId(content);

    /// <summary>
    /// Uploads an icon and returns its id.
    /// </summary>
    /// <remarks>
    /// The id is the file's CRC-32, so re-uploading identical content is idempotent and overwriting
    /// is always safe.
    /// </remarks>
    public async Task<CommandOutcome<IconId>> UploadAsync(byte[] content, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(content);

        if (ValidateIcon(content) is { } complaint)
            return CommandOutcome<IconId>.Fail(complaint);

        var id = PredictId(content);

        var result = await connection.ExecuteAsync(
            client => client.UploadFile(
                new MemoryStream(content, writable: false),
                IconChannel,
                "/" + id.ToFileName(),
                overwrite: true),
            R<FileTransferToken, CommandError>.Err(CommandError.ConnectionClosed)).ConfigureAwait(false);

        if (!result.Ok)
        {
            log.LogWarning("上传图标失败：{Error}", result.Error.ErrorFormat());
            return CommandOutcome<IconId>.Fail(CommandErrorText.Describe(result.Error));
        }

        if (result.Value.Status != TransferStatus.Done)
            return CommandOutcome<IconId>.Fail($"图标上传未完成（状态：{result.Value.Status}）。");

        // Seed the cache from what we already have in memory, so the UI can show the icon without
        // a round trip.
        await WriteCacheAsync(id, content, cancellationToken).ConfigureAwait(false);

        log.LogInformation("已上传图标 {Icon}（{Bytes} 字节）", id, content.Length);
        return CommandOutcome<IconId>.Success(id);
    }

    /// <summary>Lists the icons stored on the server.</summary>
    public async Task<CommandOutcome<IReadOnlyList<IconEntry>>> ListAsync()
    {
        var result = await connection.ExecuteAsync(
            client => client.FileTransferGetFileList(IconChannel, IconDirectory),
            R<FileList[], CommandError>.Err(CommandError.ConnectionClosed)).ConfigureAwait(false);

        if (!result.Ok)
        {
            // An empty icon directory has no file list to send, which surfaces as "database empty".
            if (result.Error.Id == TsErrorCode.database_empty_result)
                return CommandOutcome<IReadOnlyList<IconEntry>>.Success([]);

            log.LogWarning("列出图标失败：{Error}", result.Error.ErrorFormat());
            return CommandOutcome<IReadOnlyList<IconEntry>>.Fail(CommandErrorText.Describe(result.Error));
        }

        var icons = new List<IconEntry>();
        foreach (var file in result.Value)
        {
            if (!file.IsFile || file.Name is null)
                continue;

            if (!file.Name.StartsWith("icon_", StringComparison.Ordinal))
                continue;

            if (!IconId.TryParse(file.Name.AsSpan("icon_".Length).ToString(), out var id) || id.IsNone)
                continue;

            icons.Add(new IconEntry
            {
                Id = id,
                Size = file.Size,
                Uploaded = file.DateTime,
                CachePath = CachePathFor(id),
            });
        }

        return CommandOutcome<IReadOnlyList<IconEntry>>.Success(icons);
    }

    /// <summary>
    /// Downloads an icon into the local cache, unless it is already there.
    /// </summary>
    /// <param name="force">Re-download even when a cache file exists.</param>
    public async Task<CommandOutcome> DownloadAsync(IconId id, bool force = false, CancellationToken cancellationToken = default)
    {
        if (id.IsNone)
            return CommandOutcome.Success;

        // Built-in icons ship with the client; they are not files on the server.
        if (id.IsBuiltIn)
            return CommandOutcome.Success;

        var target = CachePathFor(id);
        if (!force && File.Exists(target))
            return CommandOutcome.Success;

        Directory.CreateDirectory(paths.IconCacheDirectory);

        using var buffer = new MemoryStream();
        var result = await connection.ExecuteAsync(
            client => client.DownloadFile(buffer, IconChannel, "/" + id.ToFileName(), closeStream: false),
            R<FileTransferToken, CommandError>.Err(CommandError.ConnectionClosed)).ConfigureAwait(false);

        if (!result.Ok)
        {
            log.LogDebug("下载图标 {Icon} 失败：{Error}", id, result.Error.ErrorFormat());
            return CommandOutcome.Fail(CommandErrorText.Describe(result.Error));
        }

        if (result.Value.Status != TransferStatus.Done)
            return CommandOutcome.Fail($"图标下载未完成（状态：{result.Value.Status}）。");

        await WriteCacheAsync(id, buffer.ToArray(), cancellationToken).ConfigureAwait(false);
        return CommandOutcome.Success;
    }

    /// <summary>
    /// Downloads every icon referenced by a snapshot that is not cached yet.
    /// </summary>
    /// <remarks>
    /// Sequential on purpose: each transfer opens its own TCP connection to port 30033, and the
    /// server's transfer slots are limited.
    /// </remarks>
    public async Task<int> PrefetchAsync(ServerSnapshot snapshot, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(snapshot);

        var wanted = new HashSet<uint>();
        void Want(IconId id)
        {
            if (!id.IsNone && !id.IsBuiltIn && !IsCached(id))
                wanted.Add(id.Unsigned);
        }

        Want(snapshot.IconId);
        foreach (var channel in snapshot.AllChannels())
            Want(channel.IconId);
        foreach (var group in snapshot.Groups.Values)
            Want(group.IconId);

        var fetched = 0;
        foreach (var raw in wanted)
        {
            cancellationToken.ThrowIfCancellationRequested();

            if ((await DownloadAsync(IconId.FromUnsigned(raw), force: false, cancellationToken).ConfigureAwait(false)).Ok)
                fetched++;
        }

        if (fetched > 0)
            log.LogInformation("已缓存 {Count} 个图标", fetched);

        return fetched;
    }

    /// <summary>Deletes an icon from the server and from the local cache.</summary>
    public async Task<CommandOutcome> DeleteAsync(IconId id)
    {
        if (id.IsNone)
            return CommandOutcome.Fail("未选择图标。");

        var result = await connection.ExecuteAsync(
            client => client.FileTransferDeleteFile(IconChannel, ["/" + id.ToFileName()])).ConfigureAwait(false);

        var outcome = CommandOutcome.From(result);
        if (!outcome.Ok)
        {
            log.LogWarning("删除图标 {Icon} 失败：{Message}", id, outcome.Message);
            return outcome;
        }

        TryDeleteCache(id);
        log.LogInformation("已删除图标 {Icon}", id);
        return CommandOutcome.Success;
    }

    /// <summary>
    /// Sets or clears a channel's icon.
    /// </summary>
    /// <remarks>
    /// Written through the channel's permission list; the value read back by <c>channelinfo</c> and
    /// <c>channellist -icon</c> mirrors it, so the tree needs no permission lookups.
    /// </remarks>
    public async Task<CommandOutcome> AssignToChannelAsync(ulong channelId, IconId icon)
    {
        var command = BuildChannelIconCommand(channelId, icon);

        var result = await connection.ExecuteAsync(client => client.SendVoid(command)).ConfigureAwait(false);
        var outcome = CommandOutcome.From(result);

        // Clearing an icon that was never set is not an error worth showing.
        if (!outcome.Ok && icon.IsNone && !result.Ok && result.Error.Id == TsErrorCode.database_empty_result)
            outcome = CommandOutcome.Success;

        if (outcome.Ok)
        {
            log.LogInformation("已将频道 {Cid} 的图标设为 {Icon}", channelId, icon);

            // channeladdperm is a permission write; the server does not send a channel update for
            // it, so the tree needs an explicit refresh.
            await connection.RefreshAsync().ConfigureAwait(false);
        }

        return outcome;
    }

    /// <summary>Sets or clears a server group's icon.</summary>
    public Task<CommandOutcome> AssignToServerGroupAsync(ulong groupId, GroupKind kind, IconId icon) =>
        AssignToGroupAsync(groupId, kind, icon, isServerGroup: true);

    /// <summary>Sets or clears a channel group's icon.</summary>
    public Task<CommandOutcome> AssignToChannelGroupAsync(ulong groupId, GroupKind kind, IconId icon) =>
        AssignToGroupAsync(groupId, kind, icon, isServerGroup: false);

    /// <summary>Sets or clears the virtual server's icon.</summary>
    public async Task<CommandOutcome> AssignToServerAsync(IconId icon)
    {
        var command = new TsCommand("serveredit")
        {
            // The unsigned form is mandatory here: the signed form fails with a convert error.
            new CommandParameter("virtualserver_icon_id", icon.Unsigned),
        };

        var outcome = CommandOutcome.From(await connection.ExecuteAsync(client => client.SendVoid(command)).ConfigureAwait(false));
        if (outcome.Ok)
            log.LogInformation("已将服务器图标设为 {Icon}", icon);

        return outcome;
    }

    /// <summary>Lists both group namespaces, so an icon editor can offer them in one place.</summary>
    public async Task<CommandOutcome<IReadOnlyList<IconGroup>>> ListGroupsAsync()
    {
        var serverGroups = await connection.ExecuteAsync(
            client => client.SendHybrid<ServerGroupList>(new TsCommand("servergrouplist"), NotificationType.ServerGroupList),
            R<ServerGroupList[], CommandError>.Err(CommandError.ConnectionClosed)).ConfigureAwait(false);

        if (!serverGroups.Ok)
            return CommandOutcome<IReadOnlyList<IconGroup>>.Fail(CommandErrorText.Describe(serverGroups.Error));

        var channelGroups = await connection.ExecuteAsync(
            client => client.SendHybrid<ChannelGroupList>(new TsCommand("channelgrouplist"), NotificationType.ChannelGroupList),
            R<ChannelGroupList[], CommandError>.Err(CommandError.ConnectionClosed)).ConfigureAwait(false);

        if (!channelGroups.Ok)
            return CommandOutcome<IReadOnlyList<IconGroup>>.Fail(CommandErrorText.Describe(channelGroups.Error));

        var groups = new List<IconGroup>(serverGroups.Value.Length + channelGroups.Value.Length);

        foreach (var group in serverGroups.Value)
        {
            groups.Add(new IconGroup
            {
                GroupId = group.ServerGroupId.Value,
                Name = group.Name ?? string.Empty,
                Kind = (GroupKind)(int)group.GroupType,
                IconId = IconId.FromSigned(group.IconId),
                IsServerGroup = true,
            });
        }

        foreach (var group in channelGroups.Value)
        {
            groups.Add(new IconGroup
            {
                GroupId = group.ChannelGroup.Value,
                Name = group.Name ?? string.Empty,
                Kind = (GroupKind)(int)group.GroupType,
                IconId = IconId.FromSigned(group.IconId),
                IsServerGroup = false,
            });
        }

        return CommandOutcome<IReadOnlyList<IconGroup>>.Success(groups);
    }

    /// <summary>
    /// Builds the channel icon command. Exposed for tests, which assert the wire form without
    /// needing a server.
    /// </summary>
    internal static TsCommand BuildChannelIconCommand(ulong channelId, IconId icon) =>
        icon.IsNone
            ? new TsCommand("channeldelperm")
            {
                new CommandParameter("cid", channelId),
                new CommandParameter("permsid", "i_icon_id"),
            }
            : new TsCommand("channeladdperm")
            {
                new CommandParameter("cid", channelId),
                new CommandParameter("permsid", "i_icon_id"),
                new CommandParameter("permvalue", icon.Unsigned),
            };

    /// <inheritdoc cref="BuildChannelIconCommand"/>
    internal static TsCommand BuildGroupIconCommand(ulong groupId, IconId icon, bool isServerGroup)
    {
        var key = isServerGroup ? "sgid" : "cgid";

        if (icon.IsNone)
        {
            return new TsCommand(isServerGroup ? "servergroupdelperm" : "channelgroupdelperm")
            {
                new CommandParameter(key, groupId),
                new CommandParameter("permsid", "i_icon_id"),
            };
        }

        var command = new TsCommand(isServerGroup ? "servergroupaddperm" : "channelgroupaddperm")
        {
            new CommandParameter(key, groupId),
            new CommandParameter("permsid", "i_icon_id"),
            new CommandParameter("permvalue", icon.Unsigned),
        };

        // Only servergroupaddperm demands these two.
        if (isServerGroup)
        {
            command.Add(new CommandParameter("permnegated", false));
            command.Add(new CommandParameter("permskip", false));
        }

        return command;
    }

    private async Task<CommandOutcome> AssignToGroupAsync(ulong groupId, GroupKind kind, IconId icon, bool isServerGroup)
    {
        // Template and query groups only accept built-in icon numbers. Catch that here rather than
        // letting the server answer with a misleading "invalid group ID".
        if (!icon.IsNone && GroupIconRules.DescribeRejection(kind, icon) is { } rejection)
            return CommandOutcome.Fail(rejection);

        var command = BuildGroupIconCommand(groupId, icon, isServerGroup);

        var result = await connection.ExecuteAsync(client => client.SendVoid(command)).ConfigureAwait(false);
        var outcome = CommandOutcome.From(result);

        if (!outcome.Ok && icon.IsNone && !result.Ok && result.Error.Id == TsErrorCode.database_empty_result)
            outcome = CommandOutcome.Success;

        if (outcome.Ok)
        {
            log.LogInformation(
                "已将{Kind} {Gid} 的图标设为 {Icon}",
                isServerGroup ? "服务器组" : "频道组",
                groupId,
                icon);

            await connection.RefreshAsync().ConfigureAwait(false);
        }

        return outcome;
    }

    private async Task WriteCacheAsync(IconId id, byte[] content, CancellationToken cancellationToken)
    {
        try
        {
            Directory.CreateDirectory(paths.IconCacheDirectory);
            await File.WriteAllBytesAsync(CachePathFor(id), content, cancellationToken).ConfigureAwait(false);
            IconCached?.Invoke(this, id);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // A failed cache write costs a placeholder icon, not correctness.
            log.LogWarning(ex, "写入图标缓存 {Icon} 失败", id);
        }
    }

    private void TryDeleteCache(IconId id)
    {
        try
        {
            var path = CachePathFor(id);
            if (File.Exists(path))
                File.Delete(path);

            IconCached?.Invoke(this, id);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            log.LogDebug(ex, "删除图标缓存 {Icon} 失败", id);
        }
    }

    /// <summary>
    /// Magic-number sniff for the formats TeamSpeak clients render. Guards against a user picking
    /// an .ico or a text file, which would upload fine and then never display.
    /// </summary>
    internal static bool IsSupportedImage(ReadOnlySpan<byte> content)
    {
        if (content.Length >= 8 &&
            content[0] == 0x89 && content[1] == 0x50 && content[2] == 0x4E && content[3] == 0x47 &&
            content[4] == 0x0D && content[5] == 0x0A && content[6] == 0x1A && content[7] == 0x0A)
            return true;

        if (content.Length >= 3 && content[0] == 0xFF && content[1] == 0xD8 && content[2] == 0xFF)
            return true;

        if (content.Length >= 6 &&
            content[0] == (byte)'G' && content[1] == (byte)'I' && content[2] == (byte)'F' &&
            content[3] == (byte)'8' && (content[4] == (byte)'7' || content[4] == (byte)'9') && content[5] == (byte)'a')
            return true;

        if (content.Length >= 2 && content[0] == (byte)'B' && content[1] == (byte)'M')
            return true;

        return false;
    }
}
