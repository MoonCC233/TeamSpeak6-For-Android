// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using System.Collections.Immutable;

namespace TeamSpeak9.Core.Model;

/// <summary>How long a channel survives. Mirrors TSLib's <c>ChannelType</c>.</summary>
public enum ChannelKind
{
    /// <summary>Deleted as soon as the last client leaves (subject to the delete delay).</summary>
    Temporary = 0,

    /// <summary>Survives while the server runs, but is not written to the database.</summary>
    SemiPermanent = 1,

    /// <summary>Stored in the database.</summary>
    Permanent = 2,
}

/// <summary>Voice codec of a channel. Mirrors TSLib's <c>Codec</c>.</summary>
public enum AudioCodec
{
    SpeexNarrowband = 0,
    SpeexWideband = 1,
    SpeexUltraWideband = 2,
    CeltMono = 3,
    OpusVoice = 4,
    OpusMusic = 5,

    /// <summary>A TSLib extension. Not understood by official clients.</summary>
    Raw = 127,
}

/// <summary>How a channel's client limit is expressed.</summary>
public enum ChannelLimitKind
{
    Unlimited,

    /// <summary>Family limit only: takes the value from the parent channel.</summary>
    Inherited,

    Limited,
}

/// <summary>
/// A channel's client limit. The wire form is a flag plus a number, so both are kept.
/// </summary>
public readonly record struct ChannelLimit(ChannelLimitKind Kind, ushort Count)
{
    public static readonly ChannelLimit Unlimited = new(ChannelLimitKind.Unlimited, 0);

    public static readonly ChannelLimit Inherited = new(ChannelLimitKind.Inherited, 0);

    public static ChannelLimit Of(ushort count) => new(ChannelLimitKind.Limited, count);

    public bool IsLimited => Kind == ChannelLimitKind.Limited;

    public override string ToString() => Kind switch
    {
        ChannelLimitKind.Limited => Count.ToString(System.Globalization.CultureInfo.InvariantCulture),
        ChannelLimitKind.Inherited => "继承",
        _ => "不限",
    };
}

/// <summary>Horizontal alignment of a spacer channel's caption.</summary>
public enum SpacerAlignment
{
    Left,
    Center,
    Right,

    /// <summary>The caption is repeated to fill the available width.</summary>
    Repeat,
}

/// <summary>
/// The decoded form of a TS3 spacer channel name.
/// </summary>
/// <remarks>
/// Spacers are an ordinary channel whose <i>name</i> follows the convention
/// <c>[&lt;alignment&gt;spacer&lt;id&gt;]&lt;caption&gt;</c>, where alignment is empty (left),
/// <c>c</c>, <c>r</c> or <c>*</c>. The id only has to make the channel name unique on the server;
/// it is not rendered. Only root level channels are drawn as spacers.
/// </remarks>
public sealed record SpacerInfo(SpacerAlignment Alignment, string Caption)
{
    /// <summary>
    /// Parses a spacer name, or returns <c>null</c> when <paramref name="channelName"/> is a
    /// normal channel name.
    /// </summary>
    public static SpacerInfo? TryParse(string? channelName)
    {
        if (string.IsNullOrEmpty(channelName) || channelName[0] != '[')
            return null;

        int close = channelName.IndexOf(']');
        if (close < 0)
            return null;

        var tag = channelName.AsSpan(1, close - 1);
        if (tag.IsEmpty)
            return null;

        var alignment = SpacerAlignment.Left;
        switch (tag[0])
        {
            case 'c':
                alignment = SpacerAlignment.Center;
                tag = tag[1..];
                break;
            case 'r':
                alignment = SpacerAlignment.Right;
                tag = tag[1..];
                break;
            case '*':
                alignment = SpacerAlignment.Repeat;
                tag = tag[1..];
                break;
        }

        if (!tag.StartsWith("spacer", StringComparison.Ordinal))
            return null;

        // Whatever follows "spacer" is the uniquifier; the official client accepts anything,
        // including nothing at all.
        return new SpacerInfo(alignment, channelName[(close + 1)..]);
    }
}

/// <summary>
/// An immutable node of the channel tree, with its members and children attached.
/// </summary>
/// <remarks>
/// <para>
/// Built on the TSLib scheduler thread from <c>TSLib.Full.Book.Channel</c> by
/// <c>ServerSnapshotBuilder</c>. See <see cref="ChannelOrdering"/> for why the ordering cannot be
/// done by sorting.
/// </para>
/// <para>
/// Most fields are nullable in the book because a channel that was never subscribed to only has
/// the handful of fields <c>channellist</c> sends. They are flattened to sensible defaults here so
/// the UI does not have to null-check every one; <see cref="Subscribed"/> tells you whether
/// <see cref="Members"/> is meaningful.
/// </para>
/// </remarks>
public sealed record ChannelNode
{
    public required ulong ChannelId { get; init; }

    /// <summary>Parent channel, or 0 for a root level channel.</summary>
    public ulong ParentId { get; init; }

    public required string Name { get; init; }

    public string Topic { get; init; } = string.Empty;

    /// <summary>
    /// The channel icon.
    /// </summary>
    /// <remarks>
    /// Written through <c>channeladdperm i_icon_id</c>, not <c>channeledit</c>, but the server
    /// mirrors the permission value back into <c>channel_icon_id</c>, so reading the book is
    /// enough. See docs/desktop/tslib-ts6-compat.md §4.1.
    /// </remarks>
    public IconId IconId { get; init; } = IconId.None;

    public ChannelKind Kind { get; init; } = ChannelKind.Permanent;

    public bool HasPassword { get; init; }

    /// <summary>The channel new clients land in when they do not ask for a specific one.</summary>
    public bool IsDefault { get; init; }

    /// <summary>Voice is not encrypted in this channel, overriding the server setting.</summary>
    public bool IsUnencrypted { get; init; }

    /// <summary>Nobody may talk here, regardless of talk power.</summary>
    public bool ForcedSilence { get; init; }

    public ChannelLimit MaxClients { get; init; } = ChannelLimit.Unlimited;

    public ChannelLimit MaxFamilyClients { get; init; } = ChannelLimit.Unlimited;

    public int NeededTalkPower { get; init; }

    public AudioCodec Codec { get; init; } = AudioCodec.OpusVoice;

    /// <summary>Codec quality, 0-10. Higher costs more bandwidth.</summary>
    public byte CodecQuality { get; init; }

    /// <summary>How many voice frames are packed per packet. Higher adds latency but saves overhead.</summary>
    public int CodecLatencyFactor { get; init; } = 1;

    /// <summary>Grace period before an empty temporary channel is deleted.</summary>
    public TimeSpan DeleteDelay { get; init; }

    /// <summary>
    /// Whether we receive client updates for this channel.
    /// </summary>
    /// <remarks>
    /// <see cref="Members"/> is empty for unsubscribed channels. Note that unsubscribing makes
    /// TSLib drop those clients from its book entirely, so members disappear rather than going stale.
    /// </remarks>
    public bool Subscribed { get; init; }

    /// <summary>Long description. Only populated after a <c>channelinfo</c> for this channel.</summary>
    public string Description { get; init; } = string.Empty;

    public string PhoneticName { get; init; } = string.Empty;

    /// <summary>Sub channels, already in display order.</summary>
    public ImmutableArray<ChannelNode> Children { get; init; } = [];

    /// <summary>Clients in this channel, already in display order.</summary>
    public ImmutableArray<ChannelMember> Members { get; init; } = [];

    /// <summary>Spacer metadata when the name follows the spacer convention, else <c>null</c>.</summary>
    public SpacerInfo? Spacer { get; init; }

    /// <summary>
    /// True when this node should be drawn as a divider instead of a channel.
    /// </summary>
    /// <remarks>The official client only honours spacers on root level channels.</remarks>
    public bool IsSpacer => Spacer is not null && ParentId == 0;

    public bool HasChildren => !Children.IsDefaultOrEmpty;

    public int MemberCount => Members.IsDefaultOrEmpty ? 0 : Members.Length;

    /// <summary>Total member count of this channel and everything below it.</summary>
    public int TotalMemberCount
    {
        get
        {
            int total = MemberCount;
            if (!Children.IsDefaultOrEmpty)
            {
                foreach (var child in Children)
                    total += child.TotalMemberCount;
            }

            return total;
        }
    }

    /// <summary>True when the channel is full and cannot be joined.</summary>
    public bool IsFull => MaxClients.IsLimited && MemberCount >= MaxClients.Count;

    /// <summary>Depth first walk over this node and all of its descendants.</summary>
    public IEnumerable<ChannelNode> Flatten()
    {
        yield return this;
        if (Children.IsDefaultOrEmpty)
            yield break;

        foreach (var child in Children)
        {
            foreach (var node in child.Flatten())
                yield return node;
        }
    }

    /// <summary>Finds a channel by id in this subtree, or <c>null</c>.</summary>
    public ChannelNode? Find(ulong channelId)
    {
        if (ChannelId == channelId)
            return this;

        if (Children.IsDefaultOrEmpty)
            return null;

        foreach (var child in Children)
        {
            var hit = child.Find(channelId);
            if (hit is not null)
                return hit;
        }

        return null;
    }

    public override string ToString() => $"{Name} (cid {ChannelId})";
}
