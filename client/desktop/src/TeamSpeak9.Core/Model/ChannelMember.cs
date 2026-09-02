// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using System.Collections.Immutable;

namespace TeamSpeak9.Core.Model;

/// <summary>
/// How a client connected. Mirrors TSLib's <c>ClientType</c>.
/// </summary>
public enum ClientKind
{
    /// <summary>A normal voice client.</summary>
    Full = 0,

    /// <summary>A ServerQuery connection. Has no voice and is usually hidden from the channel tree.</summary>
    Query = 1,
}

/// <summary>
/// An immutable view of one client inside a channel.
/// </summary>
/// <remarks>
/// <para>
/// Built on the TSLib scheduler thread from <c>TSLib.Full.Book.Client</c>, which is mutable and
/// not thread safe. Everything the UI needs has to be copied out here, because the book may be
/// mutated by the next incoming packet.
/// </para>
/// <para>
/// <see cref="IsTalking"/> is not part of the book - it is derived from the voice packet stream,
/// so it is always <c>false</c> in a freshly built snapshot and is patched in separately.
/// </para>
/// </remarks>
public sealed record ChannelMember
{
    /// <summary>Runtime client id. 16-bit, and only unique for the lifetime of the connection.</summary>
    public required ushort ClientId { get; init; }

    /// <summary>Channel the client currently sits in.</summary>
    public required ulong ChannelId { get; init; }

    public required string Name { get; init; }

    /// <summary>Permanent identity of the client. Empty for ServerQuery clients.</summary>
    public string Uid { get; init; } = string.Empty;

    /// <summary>Persistent per-server database id. Zero when not yet known.</summary>
    public ulong DatabaseId { get; init; }

    public ClientKind Kind { get; init; } = ClientKind.Full;

    /// <summary>
    /// Always <see cref="IconId.None"/> on TS6.
    /// </summary>
    /// <remarks>
    /// <c>client_icon_id</c> is reported as 0 for every client on tsserver 6 and
    /// <c>clientedit client_icon_id</c> is rejected with error 1538, so a per-client icon cannot
    /// be set at all. User icons are only ever the icon of one of their groups. Kept as a field
    /// so the channel tree does not have to special-case members.
    /// See docs/desktop/tslib-ts6-compat.md §4.5.
    /// </remarks>
    public IconId IconId { get; init; } = IconId.None;

    /// <summary>Two letter country code, or empty when the server does not report one.</summary>
    public string CountryCode { get; init; } = string.Empty;

    /// <summary>Avatar file hash; the file lives at <c>/avatar_&lt;uid&gt;</c>. Empty means no avatar.</summary>
    public string AvatarHash { get; init; } = string.Empty;

    /// <summary>Microphone off.</summary>
    public bool InputMuted { get; init; }

    /// <summary>Speakers off. Implies the client cannot hear anything.</summary>
    public bool OutputMuted { get; init; }

    /// <summary>No capture device available (as opposed to a deliberate mute).</summary>
    public bool InputHardwareDisabled { get; init; }

    /// <summary>No playback device available.</summary>
    public bool OutputHardwareDisabled { get; init; }

    /// <summary>Currently transmitting. Derived from voice packets, never from the book.</summary>
    public bool IsTalking { get; init; }

    public bool IsChannelCommander { get; init; }

    public bool IsPrioritySpeaker { get; init; }

    public bool IsRecording { get; init; }

    public int TalkPower { get; init; }

    /// <summary>Talk power was granted individually, overriding <see cref="TalkPower"/>.</summary>
    public bool TalkPowerGranted { get; init; }

    /// <summary>Set while the client is waiting for talk power to be granted.</summary>
    public string? TalkPowerRequestMessage { get; init; }

    /// <summary>
    /// Away message, or <c>null</c> when the client is not away.
    /// </summary>
    /// <remarks>An empty string means "away, no message", which is distinct from not being away.</remarks>
    public string? AwayMessage { get; init; }

    public string Description { get; init; } = string.Empty;

    public string PhoneticName { get; init; } = string.Empty;

    public ulong ChannelGroupId { get; init; }

    /// <summary>Channel the channel group was inherited from. Zero when assigned directly.</summary>
    public ulong ChannelGroupInheritedFrom { get; init; }

    public ImmutableArray<ulong> ServerGroupIds { get; init; } = [];

    /// <summary>True when the client is away, with or without a message.</summary>
    public bool IsAway => AwayMessage is not null;

    /// <summary>True when the client asked for talk power and has not been granted it yet.</summary>
    public bool IsRequestingTalkPower => TalkPowerRequestMessage is not null;

    /// <summary>Whether the client can currently transmit in a moderated channel.</summary>
    public bool CanTalk(int neededTalkPower) => TalkPowerGranted || TalkPower >= neededTalkPower;

    public override string ToString() => $"{Name} (clid {ClientId})";
}
