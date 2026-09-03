// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using TeamSpeak9.Core.Management;
using TeamSpeak9.Core.Model;

namespace TeamSpeak9.Core.Tests.Management;

public class ChannelDraftTests
{
    private static ChannelDraft Valid() => new()
    {
        Name = "大厅",
        CodecQuality = 6,
        CodecLatencyFactor = 1,
        MaxClients = ChannelLimit.Unlimited,
        MaxFamilyClients = ChannelLimit.Inherited,
    };

    [Fact]
    public void AMinimalDraftValidates()
    {
        Assert.Null(Valid().Validate());
    }

    [Fact]
    public void AnEmptyNameIsRejected()
    {
        Assert.NotNull(new ChannelDraft().Validate());
        Assert.NotNull((Valid() with { Name = "   " }).Validate());
    }

    [Theory]
    [InlineData(40, true)]
    [InlineData(41, false)]
    public void TheNameLengthLimitIsFortyCharacters(int length, bool expectedValid)
    {
        var draft = Valid() with { Name = new string('a', length) };

        Assert.Equal(expectedValid, draft.Validate() is null);
    }

    [Theory]
    [InlineData(0, true)]
    [InlineData(10, true)]
    [InlineData(11, false)]
    public void CodecQualityIsCappedAtTen(byte quality, bool expectedValid)
    {
        Assert.Equal(expectedValid, (Valid() with { CodecQuality = quality }).Validate() is null);
    }

    [Theory]
    [InlineData(0, false)]
    [InlineData(1, true)]
    [InlineData(10, true)]
    [InlineData(11, false)]
    public void LatencyFactorMustBeOneToTen(int factor, bool expectedValid)
    {
        Assert.Equal(expectedValid, (Valid() with { CodecLatencyFactor = factor }).Validate() is null);
    }

    [Fact]
    public void NegativeTalkPowerIsRejected()
    {
        Assert.NotNull((Valid() with { NeededTalkPower = -1 }).Validate());
        Assert.Null((Valid() with { NeededTalkPower = 0 }).Validate());
    }

    [Fact]
    public void TheClientLimitCannotBeInherited()
    {
        // Only channel_flag_maxfamilyclients_inherited exists; there is no such flag for the
        // channel's own limit.
        Assert.NotNull((Valid() with { MaxClients = ChannelLimit.Inherited }).Validate());
        Assert.Null((Valid() with { MaxClients = ChannelLimit.Of(16) }).Validate());
    }

    [Fact]
    public void ANegativeDeleteDelayIsOnlyRejectedWhereItApplies()
    {
        var temporary = Valid() with
        {
            Kind = ChannelKind.Temporary,
            DeleteDelay = TimeSpan.FromSeconds(-1),
        };

        Assert.NotNull(temporary.Validate());

        // The field is never sent for the other types, so its value is irrelevant there.
        Assert.Null((temporary with { Kind = ChannelKind.Permanent }).Validate());
        Assert.Null((temporary with { Kind = ChannelKind.SemiPermanent }).Validate());
    }

    [Theory]
    [InlineData(ChannelKind.Temporary, true)]
    [InlineData(ChannelKind.SemiPermanent, false)]
    [InlineData(ChannelKind.Permanent, false)]
    public void OnlyTemporaryChannelsTakeADeleteDelay(ChannelKind kind, bool expected)
    {
        // TS6 answers parameter_invalid for channel_delete_delay on a semi-permanent channel too,
        // even when the delay is zero.
        Assert.Equal(expected, (Valid() with { Kind = kind }).SupportsDeleteDelay);
    }

    [Fact]
    public void FromNodeCopiesEveryEditableField()
    {
        var node = new ChannelNode
        {
            ChannelId = 7,
            ParentId = 3,
            Name = "语音一",
            Topic = "话题",
            Description = "描述",
            PhoneticName = "yu yin yi",
            Kind = ChannelKind.SemiPermanent,
            DeleteDelay = TimeSpan.FromSeconds(45),
            IsDefault = true,
            Codec = AudioCodec.OpusMusic,
            CodecQuality = 9,
            CodecLatencyFactor = 2,
            IsUnencrypted = true,
            MaxClients = ChannelLimit.Of(16),
            MaxFamilyClients = ChannelLimit.Inherited,
            NeededTalkPower = 25,
        };

        var draft = ChannelDraft.FromNode(node);

        Assert.Equal("语音一", draft.Name);
        Assert.Equal("话题", draft.Topic);
        Assert.Equal("描述", draft.Description);
        Assert.Equal("yu yin yi", draft.PhoneticName);
        Assert.Equal(ChannelKind.SemiPermanent, draft.Kind);
        Assert.Equal(TimeSpan.FromSeconds(45), draft.DeleteDelay);
        Assert.True(draft.IsDefault);
        Assert.Equal(AudioCodec.OpusMusic, draft.Codec);
        Assert.Equal(9, draft.CodecQuality);
        Assert.Equal(2, draft.CodecLatencyFactor);
        Assert.True(draft.IsUnencrypted);
        Assert.Equal(ChannelLimit.Of(16), draft.MaxClients);
        Assert.Equal(ChannelLimit.Inherited, draft.MaxFamilyClients);
        Assert.Equal(25, draft.NeededTalkPower);
    }

    [Fact]
    public void FromNodeNeverInventsAPassword()
    {
        // The server does not disclose channel passwords, not even hashed, so an edit dialog has to
        // start empty and treat "empty" as "leave alone".
        var node = new ChannelNode { ChannelId = 1, Name = "受保护", HasPassword = true };

        Assert.Equal(string.Empty, ChannelDraft.FromNode(node).Password);
    }

    [Fact]
    public void FromNodeRejectsNull()
    {
        Assert.Throws<ArgumentNullException>(() => ChannelDraft.FromNode(null!));
    }
}
