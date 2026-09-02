// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using System.Collections.Immutable;
using TeamSpeak9.Core.Model;
using BookChannel = TSLib.Full.Book.Channel;
using BookClient = TSLib.Full.Book.Client;
using BookConnection = TSLib.Full.Book.Connection;
using BookMaxClients = TSLib.Full.Book.MaxClients;
using BookServerGroup = TSLib.Full.Book.ServerGroup;

namespace TeamSpeak9.Core.Connection;

/// <summary>
/// Turns TSLib's live, mutable book into an immutable <see cref="ServerSnapshot"/>.
/// </summary>
/// <remarks>
/// <para>
/// <b>Must be called on the TSLib scheduler thread.</b> <c>TSLib.Full.Book.Connection</c> holds
/// plain <see cref="Dictionary{TKey,TValue}"/> instances that the packet loop mutates in place, so
/// reading it from anywhere else races with the next incoming packet.
/// </para>
/// <para>
/// Everything the UI touches is copied here, which is why this is a full rebuild rather than an
/// incremental patch: the book gives no change granularity we could trust, and a whole rebuild of a
/// few hundred channels is cheap compared to a subtle staleness bug.
/// </para>
/// </remarks>
public static class ServerSnapshotBuilder
{
    /// <summary>
    /// Builds a snapshot from the book.
    /// </summary>
    /// <param name="book">TSLib's connection book. Read only, but must not be mutated concurrently.</param>
    /// <param name="address">Address as the user typed it, which the book does not record.</param>
    public static ServerSnapshot Build(BookConnection book, string address = "")
    {
        ArgumentNullException.ThrowIfNull(book);

        var server = book.Server;
        var members = GroupMembersByChannel(book);
        var channels = BuildTree(book, members);

        return new ServerSnapshot
        {
            Name = server?.Name ?? string.Empty,
            Nickname = server?.Nickname ?? string.Empty,
            Address = address,
            IconId = server is null ? IconId.None : IconId.FromSigned(server.IconId),
            WelcomeMessage = server?.WelcomeMessage ?? string.Empty,
            WelcomeMessageDisplay = (HostMessageDisplay)(int)(server?.HostmessageMode ?? default),
            PhoneticName = server?.PhoneticName ?? string.Empty,
            Platform = server?.Platform ?? string.Empty,
            Version = server?.Version ?? string.Empty,
            ProtocolVersion = server?.ProtocolVersion ?? 0,
            MaxClients = server?.MaxClients ?? 0,
            License = (ServerLicense)(int)(server?.License ?? default),
            VirtualServerId = server?.VirtualServerId ?? 0,
            PublicKeyUid = server?.PublicKey.Value ?? string.Empty,
            Created = server?.Created ?? default,
            VoiceEncryption = (VoiceEncryptionMode)(int)(server?.CodecEncryptionMode ?? default),
            Banner = BuildBanner(server),
            DefaultServerGroupId = server?.DefaultServerGroup.Value ?? 0,
            DefaultChannelGroupId = server?.DefaultChannelGroup.Value ?? 0,
            TempChannelDefaultDeleteDelay = server?.TempChannelDefaultDeleteDelay ?? TimeSpan.Zero,
            PrioritySpeakerDimmModificator = server?.PrioritySpeakerDimmModificator ?? 0f,
            AskForPrivilegeKey = server?.AskForPrivilegekey ?? false,
            Channels = channels,
            Groups = BuildGroups(book),
            OwnClientId = book.OwnClient.Value,
            OwnChannelId = OwnChannelId(book),
        };
    }

    private static HostBannerInfo BuildBanner(TSLib.Full.Book.Server? server)
    {
        if (server is null)
            return HostBannerInfo.Empty;

        return new HostBannerInfo
        {
            GfxUrl = server.HostbannerGfxUrl ?? string.Empty,
            LinkUrl = server.HostbannerUrl ?? string.Empty,
            Scaling = (HostBannerScaling)(int)server.HostbannerMode,
            RefreshInterval = server.HostbannerGfxInterval,
            ButtonGfxUrl = server.HostbuttonGfxUrl ?? string.Empty,
            ButtonUrl = server.HostbuttonUrl ?? string.Empty,
            ButtonTooltip = server.HostbuttonTooltip ?? string.Empty,
        };
    }

    private static ulong OwnChannelId(BookConnection book)
    {
        var own = book.OwnClient;
        return own != TSLib.ClientId.Null && book.Clients.TryGetValue(own, out var self)
            ? self.Channel.Value
            : 0;
    }

    private static ImmutableDictionary<ulong, ServerGroupInfo> BuildGroups(BookConnection book)
    {
        if (book.Groups.Count == 0)
            return ImmutableDictionary<ulong, ServerGroupInfo>.Empty;

        var builder = ImmutableDictionary.CreateBuilder<ulong, ServerGroupInfo>();
        foreach (var (id, group) in book.Groups)
            builder[id.Value] = ToGroupInfo(id.Value, group);

        return builder.ToImmutable();
    }

    private static ServerGroupInfo ToGroupInfo(ulong id, BookServerGroup group) => new()
    {
        GroupId = id,
        Name = group.Name ?? string.Empty,
        Kind = (GroupKind)(int)group.GroupType,
        IconId = IconId.FromSigned(group.IconId),
        Naming = (GroupNaming)(int)group.NamingMode,
        SortId = group.SortId,
        IsPermanent = group.IsPermanent,
        NeededMemberAddPower = group.NeededMemberAddPower,
        NeededMemberRemovePower = group.NeededMemberRemovePower ?? 0,
        NeededModifyPower = group.NeededModifyPower,
    };

    /// <summary>
    /// Buckets clients by channel so the tree walk does not rescan the client list per channel.
    /// </summary>
    /// <remarks>
    /// ServerQuery clients are dropped: they have no voice, cannot be interacted with from a voice
    /// client, and the official client hides them too.
    /// </remarks>
    private static Dictionary<ulong, List<ChannelMember>> GroupMembersByChannel(BookConnection book)
    {
        var byChannel = new Dictionary<ulong, List<ChannelMember>>();
        foreach (var (id, client) in book.Clients)
        {
            if (client.ClientType == TSLib.ClientType.Query)
                continue;

            ulong channelId = client.Channel.Value;
            if (!byChannel.TryGetValue(channelId, out var bucket))
            {
                bucket = [];
                byChannel[channelId] = bucket;
            }

            bucket.Add(ToMember(id.Value, channelId, client));
        }

        return byChannel;
    }

    private static ChannelMember ToMember(ushort clientId, ulong channelId, BookClient client) => new()
    {
        ClientId = clientId,
        ChannelId = channelId,
        Name = client.Name ?? string.Empty,
        Uid = client.Uid?.Value ?? string.Empty,
        DatabaseId = client.DatabaseId.Value,
        Kind = (ClientKind)(int)client.ClientType,

        // Always 0 on TS6; see ChannelMember.IconId.
        IconId = IconId.FromSigned(client.IconId),
        CountryCode = client.CountryCode ?? string.Empty,
        AvatarHash = client.AvatarHash ?? string.Empty,
        InputMuted = client.InputMuted,
        OutputMuted = client.OutputMuted || client.OutputOnlyMuted,
        InputHardwareDisabled = !client.InputHardwareEnabled,
        OutputHardwareDisabled = !client.OutputHardwareEnabled,
        IsChannelCommander = client.IsChannelCommander,
        IsPrioritySpeaker = client.IsPrioritySpeaker,
        IsRecording = client.IsRecording,
        TalkPower = client.TalkPower,
        TalkPowerGranted = client.TalkPowerGranted,
        TalkPowerRequestMessage = client.TalkPowerRequest?.Message,
        AwayMessage = client.AwayMessage,
        Description = client.Description ?? string.Empty,
        PhoneticName = client.PhoneticName ?? string.Empty,
        ChannelGroupId = client.ChannelGroup.Value,
        ChannelGroupInheritedFrom = client.InheritedChannelGroupFromChannel.Value,
        ServerGroupIds = ToGroupIds(client.ServerGroups),
    };

    private static ImmutableArray<ulong> ToGroupIds(HashSet<TSLib.ServerGroupId>? groups)
    {
        if (groups is null || groups.Count == 0)
            return [];

        // Sorted so two snapshots of an unchanged client compare equal - HashSet order is not stable.
        var ids = new ulong[groups.Count];
        int i = 0;
        foreach (var group in groups)
            ids[i++] = group.Value;

        Array.Sort(ids);
        return [.. ids];
    }

    /// <summary>
    /// Builds the channel forest, following the <c>channel_order</c> chain at every level.
    /// </summary>
    private static ImmutableArray<ChannelNode> BuildTree(
        BookConnection book,
        Dictionary<ulong, List<ChannelMember>> membersByChannel)
    {
        if (book.Channels.Count == 0)
            return [];

        var childrenByParent = new Dictionary<ulong, List<BookChannel>>();
        foreach (var channel in book.Channels.Values)
        {
            ulong parent = channel.Parent.Value;
            if (!childrenByParent.TryGetValue(parent, out var bucket))
            {
                bucket = [];
                childrenByParent[parent] = bucket;
            }

            bucket.Add(channel);
        }

        // A channel whose parent was never received would otherwise be invisible. Treating those
        // as roots keeps them reachable; this can happen transiently while channellist streams in.
        var known = book.Channels.Keys.Select(static id => id.Value).ToHashSet();
        var roots = new List<BookChannel>();
        foreach (var (parent, bucket) in childrenByParent)
        {
            if (parent == 0 || !known.Contains(parent))
                roots.AddRange(bucket);
        }

        var visiting = new HashSet<ulong>();
        return BuildLevel(roots, childrenByParent, membersByChannel, visiting);
    }

    private static ImmutableArray<ChannelNode> BuildLevel(
        List<BookChannel> siblings,
        Dictionary<ulong, List<BookChannel>> childrenByParent,
        Dictionary<ulong, List<ChannelMember>> membersByChannel,
        HashSet<ulong> visiting)
    {
        if (siblings.Count == 0)
            return [];

        var ordered = ChannelOrdering.SortSiblings(
            siblings,
            static c => c.Id.Value,
            static c => c.Order.Value);

        var nodes = ImmutableArray.CreateBuilder<ChannelNode>(ordered.Length);
        foreach (var channel in ordered)
            nodes.Add(ToNode(channel, childrenByParent, membersByChannel, visiting));

        return nodes.MoveToImmutable();
    }

    private static ChannelNode ToNode(
        BookChannel channel,
        Dictionary<ulong, List<BookChannel>> childrenByParent,
        Dictionary<ulong, List<ChannelMember>> membersByChannel,
        HashSet<ulong> visiting)
    {
        ulong id = channel.Id.Value;

        // A parent cycle would recurse until the stack blows. The book should never contain one,
        // but it is server-provided data, so it is not trusted.
        var children = ImmutableArray<ChannelNode>.Empty;
        if (visiting.Add(id))
        {
            if (childrenByParent.TryGetValue(id, out var childBucket))
                children = BuildLevel(childBucket, childrenByParent, membersByChannel, visiting);

            visiting.Remove(id);
        }

        var members = membersByChannel.TryGetValue(id, out var bucket)
            ? ChannelOrdering.SortMembers(bucket)
            : [];

        string name = channel.Name ?? string.Empty;

        return new ChannelNode
        {
            ChannelId = id,
            ParentId = channel.Parent.Value,
            Name = name,
            Topic = channel.Topic ?? string.Empty,

            // IconHash is signed in TSLib but the wire value is a CRC-32.
            IconId = channel.IconId is { } icon ? IconId.FromSigned(icon) : IconId.None,
            Kind = (ChannelKind)(int)channel.ChannelType,
            HasPassword = channel.HasPassword ?? false,
            IsDefault = channel.IsDefault ?? false,
            IsUnencrypted = channel.IsUnencrypted ?? false,
            ForcedSilence = channel.ForcedSilence,
            MaxClients = ToLimit(channel.MaxClients),
            MaxFamilyClients = ToLimit(channel.MaxFamilyClients),
            NeededTalkPower = channel.NeededTalkPower ?? 0,
            Codec = (AudioCodec)(int)(channel.Codec ?? TSLib.Codec.OpusVoice),
            CodecQuality = channel.CodecQuality ?? 0,
            CodecLatencyFactor = channel.CodecLatencyFactor ?? 1,
            DeleteDelay = channel.DeleteDelay ?? TimeSpan.Zero,
            Subscribed = channel.Subscribed,
            Description = channel.OptionalData?.Description ?? string.Empty,
            PhoneticName = channel.PhoneticName ?? string.Empty,
            Children = children,
            Members = members,
            Spacer = SpacerInfo.TryParse(name),
        };
    }

    private static ChannelLimit ToLimit(BookMaxClients? limit) => limit switch
    {
        null => ChannelLimit.Unlimited,
        { LimitKind: TSLib.Full.Book.MaxClientsKind.Limited } value => ChannelLimit.Of(value.Count),
        { LimitKind: TSLib.Full.Book.MaxClientsKind.Inherited } => ChannelLimit.Inherited,
        _ => ChannelLimit.Unlimited,
    };
}
