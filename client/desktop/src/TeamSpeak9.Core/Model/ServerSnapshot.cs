// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using System.Collections.Immutable;

namespace TeamSpeak9.Core.Model;

/// <summary>Where a group name is shown relative to the nickname.</summary>
public enum GroupNaming
{
    /// <summary>Not shown.</summary>
    None = 0,

    Before = 1,

    After = 2,
}

/// <summary>Licence tier of the virtual server. Mirrors TSLib's <c>LicenseType</c>.</summary>
public enum ServerLicense
{
    NoLicense = 0,
    Athp = 1,
    Lan = 2,
    Npl = 3,
    Unknown = 4,
}

/// <summary>How the welcome message is presented on connect.</summary>
public enum HostMessageDisplay
{
    None = 0,

    /// <summary>Written to the server chat tab.</summary>
    Log = 1,

    /// <summary>Shown in a dialog.</summary>
    Modal = 2,

    /// <summary>Shown in a dialog, then the client disconnects.</summary>
    ModalQuit = 3,
}

/// <summary>How the host banner image is scaled.</summary>
public enum HostBannerScaling
{
    NoAdjust = 0,
    IgnoreAspect = 1,
    KeepAspect = 2,
}

/// <summary>Whether voice encryption is per channel or forced server wide.</summary>
public enum VoiceEncryptionMode
{
    /// <summary>Each channel decides.</summary>
    Individual = 0,

    Disabled = 1,

    Enabled = 2,
}

/// <summary>
/// An immutable view of one server group.
/// </summary>
public sealed record ServerGroupInfo
{
    public required ulong GroupId { get; init; }

    public required string Name { get; init; }

    /// <summary>
    /// Decides whether an uploaded icon may be assigned to this group.
    /// </summary>
    /// <remarks>See <see cref="GroupIconRules"/>; template and query groups reject icon ids &gt;= 1000.</remarks>
    public GroupKind Kind { get; init; } = GroupKind.Regular;

    public IconId IconId { get; init; } = IconId.None;

    public GroupNaming Naming { get; init; } = GroupNaming.None;

    /// <summary>Order hint used by the server. Lower sorts first.</summary>
    public int SortId { get; init; }

    /// <summary>Groups that cannot be deleted, e.g. the default guest and admin groups.</summary>
    public bool IsPermanent { get; init; }

    public int NeededMemberAddPower { get; init; }

    public int NeededMemberRemovePower { get; init; }

    public int NeededModifyPower { get; init; }

    /// <summary>Whether an uploaded icon can be assigned to this group at all.</summary>
    public bool AllowsCustomIcon => GroupIconRules.AllowsCustomIcon(Kind);

    public override string ToString() => $"{Name} (sgid {GroupId})";
}

/// <summary>
/// The host banner and host button configuration, which the UI renders as one card.
/// </summary>
public sealed record HostBannerInfo
{
    public static readonly HostBannerInfo Empty = new();

    /// <summary>Image URL. Empty means no banner.</summary>
    public string GfxUrl { get; init; } = string.Empty;

    /// <summary>Link the banner opens when clicked.</summary>
    public string LinkUrl { get; init; } = string.Empty;

    public HostBannerScaling Scaling { get; init; } = HostBannerScaling.NoAdjust;

    /// <summary>How often the image is refetched. <see cref="TimeSpan.Zero"/> means never.</summary>
    public TimeSpan RefreshInterval { get; init; }

    /// <summary>Host button image URL.</summary>
    public string ButtonGfxUrl { get; init; } = string.Empty;

    public string ButtonUrl { get; init; } = string.Empty;

    public string ButtonTooltip { get; init; } = string.Empty;

    public bool HasBanner => GfxUrl.Length > 0;

    public bool HasButton => ButtonGfxUrl.Length > 0 || ButtonUrl.Length > 0;
}

/// <summary>
/// A complete, immutable picture of the connected server: channel tree, members and metadata.
/// </summary>
/// <remarks>
/// <para>
/// Rebuilt on the TSLib scheduler thread whenever the book changes, then handed to the UI thread.
/// Because it is a value snapshot, the UI can hold on to it and diff against the next one without
/// any locking.
/// </para>
/// <para>
/// Fields the book does not carry are absent here too. In particular the per-channel host banner
/// (<c>channel_banner_gfx_url</c> / <c>channel_banner_mode</c>) only appears on
/// <c>channelinfo</c> / <c>channellist</c> responses, so it has to be fetched separately.
/// </para>
/// </remarks>
public sealed record ServerSnapshot
{
    public static readonly ServerSnapshot Empty = new()
    {
        Name = string.Empty,
        Nickname = string.Empty,
    };

    public required string Name { get; init; }

    /// <summary>Our own nickname on this server, which the server may have uniquified.</summary>
    public required string Nickname { get; init; }

    public IconId IconId { get; init; } = IconId.None;

    /// <summary>Address exactly as the user typed it, so it can be reconnected to or bookmarked.</summary>
    public string Address { get; init; } = string.Empty;

    public string WelcomeMessage { get; init; } = string.Empty;

    public HostMessageDisplay WelcomeMessageDisplay { get; init; } = HostMessageDisplay.None;

    public string PhoneticName { get; init; } = string.Empty;

    /// <summary>Server operating system string, e.g. <c>Windows</c>.</summary>
    public string Platform { get; init; } = string.Empty;

    /// <summary>Server version string, e.g. <c>6.0.0-beta12.1</c>.</summary>
    public string Version { get; init; } = string.Empty;

    /// <summary>Voice protocol version the server speaks.</summary>
    public ushort ProtocolVersion { get; init; }

    public ushort MaxClients { get; init; }

    public ServerLicense License { get; init; } = ServerLicense.NoLicense;

    public ulong VirtualServerId { get; init; }

    /// <summary>Server identity, used to key per-server local state such as chat history.</summary>
    public string PublicKeyUid { get; init; } = string.Empty;

    public DateTime Created { get; init; }

    public VoiceEncryptionMode VoiceEncryption { get; init; } = VoiceEncryptionMode.Individual;

    public HostBannerInfo Banner { get; init; } = HostBannerInfo.Empty;

    /// <summary>Default group new clients are put into.</summary>
    public ulong DefaultServerGroupId { get; init; }

    public ulong DefaultChannelGroupId { get; init; }

    /// <summary>Delete delay applied to temporary channels that do not set their own.</summary>
    public TimeSpan TempChannelDefaultDeleteDelay { get; init; }

    /// <summary>How much other speakers are attenuated while a priority speaker talks, 0..1.</summary>
    public float PrioritySpeakerDimmModificator { get; init; }

    /// <summary>Set when the server still expects an admin privilege key to be redeemed.</summary>
    public bool AskForPrivilegeKey { get; init; }

    /// <summary>Root level channels, in display order.</summary>
    public ImmutableArray<ChannelNode> Channels { get; init; } = [];

    /// <summary>Server groups, keyed by group id.</summary>
    public ImmutableDictionary<ulong, ServerGroupInfo> Groups { get; init; } =
        ImmutableDictionary<ulong, ServerGroupInfo>.Empty;

    /// <summary>Our own runtime client id, or 0 before <c>initserver</c> arrives.</summary>
    public ushort OwnClientId { get; init; }

    /// <summary>Channel we are in, or 0 when not known yet.</summary>
    public ulong OwnChannelId { get; init; }

    /// <summary>When this snapshot was taken. Useful for stale-snapshot diagnostics.</summary>
    public DateTimeOffset Timestamp { get; init; } = DateTimeOffset.UtcNow;

    /// <summary>Every channel, depth first, in display order.</summary>
    public IEnumerable<ChannelNode> AllChannels()
    {
        if (Channels.IsDefaultOrEmpty)
            yield break;

        foreach (var root in Channels)
        {
            foreach (var node in root.Flatten())
                yield return node;
        }
    }

    public ChannelNode? FindChannel(ulong channelId)
    {
        if (Channels.IsDefaultOrEmpty || channelId == 0)
            return null;

        foreach (var root in Channels)
        {
            var hit = root.Find(channelId);
            if (hit is not null)
                return hit;
        }

        return null;
    }

    /// <summary>The channel we are currently in, or <c>null</c>.</summary>
    public ChannelNode? OwnChannel => FindChannel(OwnChannelId);

    /// <summary>Looks up a client by runtime id across the whole tree.</summary>
    public ChannelMember? FindMember(ushort clientId)
    {
        if (clientId == 0)
            return null;

        foreach (var channel in AllChannels())
        {
            if (channel.Members.IsDefaultOrEmpty)
                continue;

            foreach (var member in channel.Members)
            {
                if (member.ClientId == clientId)
                    return member;
            }
        }

        return null;
    }

    /// <summary>Our own client entry, or <c>null</c> before it is in the book.</summary>
    public ChannelMember? OwnClient => FindMember(OwnClientId);

    /// <summary>Total clients across all subscribed channels.</summary>
    public int ClientCount
    {
        get
        {
            int total = 0;
            foreach (var channel in AllChannels())
                total += channel.MemberCount;
            return total;
        }
    }

    public ServerGroupInfo? FindGroup(ulong groupId) =>
        Groups.TryGetValue(groupId, out var group) ? group : null;

    /// <summary>Display name, falling back to the address for servers with an empty name.</summary>
    public string DisplayName => Name.Length > 0 ? Name : Address;

    public override string ToString() => $"{DisplayName} ({ClientCount} clients)";
}
