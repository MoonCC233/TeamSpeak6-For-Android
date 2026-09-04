// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using TeamSpeak9.Core.Model;

namespace TeamSpeak9.Core.Management;

/// <summary>
/// The editable state of a channel, as a create/edit dialog collects it.
/// </summary>
/// <remarks>
/// <para>
/// Deliberately not a mirror of <see cref="ChannelNode"/>: it drops everything the server
/// computes (members, children, spacer parse result) and adds the two banner fields, which
/// <see cref="ChannelNode"/> does not carry because <c>channellist</c> does not return them.
/// </para>
/// <para>
/// <c>channel_forced_silence</c> is intentionally absent. The official <c>channeledit</c>
/// documentation does not list it, so it is treated as read-only.
/// </para>
/// </remarks>
public sealed record ChannelDraft
{
    public string Name { get; init; } = string.Empty;

    public string PhoneticName { get; init; } = string.Empty;

    public string Topic { get; init; } = string.Empty;

    public string Description { get; init; } = string.Empty;

    /// <summary>
    /// Plain text. Empty means "no password"; on edit that clears an existing one.
    /// Hashing happens in <see cref="ChannelService"/>, never in the UI.
    /// </summary>
    public string Password { get; init; } = string.Empty;

    public ChannelKind Kind { get; init; } = ChannelKind.Permanent;

    /// <summary>Only sent for <see cref="ChannelKind.Temporary"/>; see <see cref="SupportsDeleteDelay"/>.</summary>
    public TimeSpan DeleteDelay { get; init; } = TimeSpan.Zero;

    public bool IsDefault { get; init; }

    public AudioCodec Codec { get; init; } = AudioCodec.OpusVoice;

    /// <summary>0..10.</summary>
    public byte CodecQuality { get; init; } = 6;

    public int CodecLatencyFactor { get; init; } = 1;

    /// <summary>Sent inverted as <c>channel_codec_is_unencrypted</c>.</summary>
    public bool IsUnencrypted { get; init; }

    public ChannelLimit MaxClients { get; init; } = ChannelLimit.Unlimited;

    public ChannelLimit MaxFamilyClients { get; init; } = ChannelLimit.Inherited;

    public int NeededTalkPower { get; init; }

    public string BannerGfxUrl { get; init; } = string.Empty;

    public HostBannerScaling BannerMode { get; init; } = HostBannerScaling.KeepAspect;

    /// <summary>Reads back the fields a dialog can edit, for the "edit existing channel" case.</summary>
    public static ChannelDraft FromNode(ChannelNode node)
    {
        ArgumentNullException.ThrowIfNull(node);

        return new ChannelDraft
        {
            Name = node.Name,
            PhoneticName = node.PhoneticName,
            Topic = node.Topic,
            Description = node.Description,

            // The server never hands back a channel password, not even hashed. An edit dialog
            // therefore starts empty, and an empty value is treated as "leave alone" by
            // ChannelService.EditAsync when the channel already has one.
            Password = string.Empty,

            Kind = node.Kind,
            DeleteDelay = node.DeleteDelay,
            IsDefault = node.IsDefault,
            Codec = node.Codec,
            CodecQuality = node.CodecQuality,
            CodecLatencyFactor = node.CodecLatencyFactor,
            IsUnencrypted = node.IsUnencrypted,
            MaxClients = node.MaxClients,
            MaxFamilyClients = node.MaxFamilyClients,
            NeededTalkPower = node.NeededTalkPower,
        };
    }

    /// <summary>
    /// Whether <see cref="DeleteDelay"/> applies. Verified against TS6: the field is only accepted on
    /// a temporary channel. On a permanent or semi-permanent one the server answers
    /// <c>parameter_invalid</c> even for a delay of zero, so both the dialog and the command builder
    /// need this.
    /// </summary>
    public bool SupportsDeleteDelay => Kind == ChannelKind.Temporary;

    /// <summary>Basic checks that do not need a round trip.</summary>
    public string? Validate()
    {
        if (string.IsNullOrWhiteSpace(Name))
            return "频道名称不能为空。";

        if (Name.Length > 40)
            return "频道名称不能超过 40 个字符。";

        if (CodecQuality > 10)
            return "编解码质量必须在 0 到 10 之间。";

        if (CodecLatencyFactor is < 1 or > 10)
            return "延迟因数必须在 1 到 10 之间。";

        if (NeededTalkPower < 0)
            return "所需谈话权限不能为负数。";

        if (MaxClients.Kind == ChannelLimitKind.Inherited)
            return "频道人数上限不支持“继承”。";

        if (SupportsDeleteDelay && DeleteDelay < TimeSpan.Zero)
            return "删除延迟不能为负数。";

        return null;
    }
}
