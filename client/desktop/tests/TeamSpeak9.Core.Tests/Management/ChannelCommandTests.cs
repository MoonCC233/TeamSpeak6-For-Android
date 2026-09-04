// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using TeamSpeak9.Core.Management;
using TeamSpeak9.Core.Model;

namespace TeamSpeak9.Core.Tests.Management;

public class ChannelCommandTests
{
    private static ChannelDraft Draft() => new()
    {
        Name = "大厅",
        CodecQuality = 6,
        CodecLatencyFactor = 1,
        MaxClients = ChannelLimit.Unlimited,
        MaxFamilyClients = ChannelLimit.Inherited,
    };

    [Fact]
    public void CreateSendsTheNameEscaped()
    {
        var wire = ChannelService.BuildCreate(Draft() with { Name = "语音 一" }, 0, 0).ToString();

        Assert.StartsWith("channelcreate ", wire);
        Assert.Contains(@"channel_name=语音\s一", wire);
    }

    [Fact]
    public void CreateOmitsTheParentForATopLevelChannel()
    {
        Assert.DoesNotContain("cpid=", ChannelService.BuildCreate(Draft(), 0, 0).ToString());
        Assert.Contains("cpid=5", ChannelService.BuildCreate(Draft(), 5, 0).ToString());
    }

    [Fact]
    public void CreateAlwaysSendsTheOrderBecauseZeroMeansFirst()
    {
        Assert.Contains("channel_order=0", ChannelService.BuildCreate(Draft(), 0, 0).ToString());
        Assert.Contains("channel_order=9", ChannelService.BuildCreate(Draft(), 0, 9).ToString());
    }

    [Theory]
    [InlineData(ChannelKind.Permanent, "channel_flag_permanent=1", "channel_flag_semi_permanent=0")]
    [InlineData(ChannelKind.SemiPermanent, "channel_flag_permanent=0", "channel_flag_semi_permanent=1")]
    [InlineData(ChannelKind.Temporary, "channel_flag_permanent=0", "channel_flag_semi_permanent=0")]
    public void CreateSendsBothTypeFlagsAsAPair(ChannelKind kind, string permanent, string semi)
    {
        var wire = ChannelService.BuildCreate(Draft() with { Kind = kind }, 0, 0).ToString();

        Assert.Contains(permanent, wire);
        Assert.Contains(semi, wire);
    }

    [Fact]
    public void CreateOnlySendsTheDeleteDelayOnATemporaryChannel()
    {
        var temporary = Draft() with { Kind = ChannelKind.Temporary, DeleteDelay = TimeSpan.FromSeconds(30) };
        Assert.Contains("channel_delete_delay=30", ChannelService.BuildCreate(temporary, 0, 0).ToString());

        // The server answers parameter_invalid on any other type, even for a delay of zero.
        foreach (var kind in new[] { ChannelKind.Permanent, ChannelKind.SemiPermanent })
        {
            var other = temporary with { Kind = kind };
            Assert.DoesNotContain("channel_delete_delay", ChannelService.BuildCreate(other, 0, 0).ToString());
        }
    }

    [Fact]
    public void CreateHashesThePasswordRatherThanSendingItPlain()
    {
        var wire = ChannelService.BuildCreate(Draft() with { Password = "hunter2" }, 0, 0).ToString();

        Assert.Contains("channel_password=", wire);
        Assert.DoesNotContain("hunter2", wire);
    }

    [Fact]
    public void CreateOmitsThePasswordWhenThereIsNone()
    {
        Assert.DoesNotContain("channel_password", ChannelService.BuildCreate(Draft(), 0, 0).ToString());
    }

    [Fact]
    public void CreateOnlyClaimsDefaultWhenAsked()
    {
        Assert.DoesNotContain("channel_flag_default", ChannelService.BuildCreate(Draft(), 0, 0).ToString());
        Assert.Contains(
            "channel_flag_default=1",
            ChannelService.BuildCreate(Draft() with { IsDefault = true }, 0, 0).ToString());
    }

    [Fact]
    public void CreateDoesNotCarryBannerFieldsBecauseTheyNeedAFollowUpEdit()
    {
        var draft = Draft() with { BannerGfxUrl = "https://example.invalid/b.png" };

        Assert.DoesNotContain("channel_banner", ChannelService.BuildCreate(draft, 0, 0).ToString());
    }

    [Fact]
    public void EditIdentifiesTheChannelAndSendsBannerFields()
    {
        var draft = Draft() with
        {
            BannerGfxUrl = "https://example.invalid/b.png",
            BannerMode = HostBannerScaling.IgnoreAspect,
        };

        var wire = ChannelService.BuildEdit(42, draft, hasPassword: false).ToString();

        Assert.StartsWith("channeledit ", wire);
        Assert.Contains("cid=42", wire);
        Assert.Contains(@"channel_banner_gfx_url=https:\/\/example.invalid\/b.png", wire);
        Assert.Contains("channel_banner_mode=1", wire);
    }

    [Fact]
    public void EditKeepsTheTypeFlagsAndTheRestOfTheFieldsInOneCommand()
    {
        // Verified against TS6: all nineteen editable properties, both type flags and the delete delay
        // are accepted by a single channeledit, so there is no reason to split the edit up.
        var wire = ChannelService.BuildEdit(42, Draft(), hasPassword: false).ToString();

        Assert.Single(wire.Split(' '), part => part == "channeledit");
        Assert.Contains("channel_flag_permanent=", wire);
        Assert.Contains("channel_codec_quality=", wire);
        Assert.Contains("channel_banner_mode=", wire);
    }

    [Fact]
    public void EditClearsThePasswordOfAnUnprotectedChannel()
    {
        // Sending the empty value keeps a formerly protected channel in sync when the dialog was
        // opened before someone else set a password.
        var wire = ChannelService.BuildEdit(1, Draft(), hasPassword: false).ToString();

        Assert.Contains("channel_password=", wire);
    }

    [Fact]
    public void EditLeavesAnExistingPasswordAloneWhenTheFieldWasNotTouched()
    {
        var wire = ChannelService.BuildEdit(1, Draft(), hasPassword: true).ToString();

        Assert.DoesNotContain("channel_password", wire);
    }

    [Fact]
    public void EditHashesANewPasswordRegardlessOfTheOldState()
    {
        foreach (var had in new[] { false, true })
        {
            var wire = ChannelService.BuildEdit(1, Draft() with { Password = "hunter2" }, had).ToString();

            Assert.Contains("channel_password=", wire);
            Assert.DoesNotContain("hunter2", wire);
        }
    }

    [Fact]
    public void EditOmitsAnUnchangedNameBecauseTheServerReadsItAsARename()
    {
        // TS6 answers channel_name_inuse when channel_name equals the channel's own name, so an
        // untouched name field has to stay out of the command entirely.
        var wire = ChannelService.BuildEdit(1, Draft(), hasPassword: false, currentName: "大厅").ToString();

        Assert.DoesNotContain("channel_name=", wire);

        // The phonetic name shares the prefix, so it must survive the omission.
        Assert.Contains("channel_name_phonetic=", wire);
    }

    [Fact]
    public void EditSendsAChangedName()
    {
        var wire = ChannelService.BuildEdit(1, Draft(), hasPassword: false, currentName: "旧名字").ToString();

        Assert.Contains("channel_name=大厅", wire);
    }

    [Fact]
    public void EditSendsTheNameWhenTheOldOneIsUnknown()
    {
        Assert.Contains("channel_name=大厅", ChannelService.BuildEdit(1, Draft(), hasPassword: false).ToString());
    }

    [Fact]
    public void EditTreatsACaseOnlyDifferenceAsARename()
    {
        // The server only rejects an exact match; ceshi -> CESHI is accepted.
        var wire = ChannelService.BuildEdit(1, Draft() with { Name = "CESHI" }, false, "ceshi").ToString();

        Assert.Contains("channel_name=CESHI", wire);
    }

    [Theory]
    [InlineData(ChannelKind.Permanent, "channel_flag_permanent=1", "channel_flag_semi_permanent=0")]
    [InlineData(ChannelKind.SemiPermanent, "channel_flag_permanent=0", "channel_flag_semi_permanent=1")]
    [InlineData(ChannelKind.Temporary, "channel_flag_permanent=0", "channel_flag_semi_permanent=0")]
    public void EditSendsBothTypeFlagsBecauseTheServerRequiresThePair(
        ChannelKind kind,
        string permanent,
        string semi)
    {
        // Verified against TS6: a single flag is answered with channel_invalid_flags unless it happens
        // to match the channel's current type, so the pair travels with every edit.
        var wire = ChannelService.BuildEdit(42, Draft() with { Kind = kind }, hasPassword: false).ToString();

        Assert.Contains(permanent, wire);
        Assert.Contains(semi, wire);

        // 1/1 is the one combination the server refuses, so it must be unreachable.
        Assert.DoesNotContain("channel_flag_permanent=1 channel_flag_semi_permanent=1", wire);
    }

    [Fact]
    public void EditOnlySendsTheDeleteDelayOnATemporaryChannel()
    {
        var temporary = Draft() with { Kind = ChannelKind.Temporary, DeleteDelay = TimeSpan.FromSeconds(45) };
        Assert.Contains("channel_delete_delay=45", ChannelService.BuildEdit(3, temporary, false).ToString());

        foreach (var kind in new[] { ChannelKind.Permanent, ChannelKind.SemiPermanent })
        {
            var other = temporary with { Kind = kind };
            Assert.DoesNotContain("channel_delete_delay", ChannelService.BuildEdit(3, other, false).ToString());
        }
    }

    [Fact]
    public void TheClientLimitFlagAndCountStayConsistent()
    {
        var limited = ChannelService.BuildCreate(Draft() with { MaxClients = ChannelLimit.Of(16) }, 0, 0).ToString();
        Assert.Contains("channel_flag_maxclients_unlimited=0", limited);
        Assert.Contains("channel_maxclients=16", limited);

        var unlimited = ChannelService.BuildCreate(Draft(), 0, 0).ToString();
        Assert.Contains("channel_flag_maxclients_unlimited=1", unlimited);
        Assert.Contains("channel_maxclients=0", unlimited);
    }

    [Theory]
    [InlineData(ChannelLimitKind.Inherited, "channel_flag_maxfamilyclients_inherited=1", "channel_flag_maxfamilyclients_unlimited=0")]
    [InlineData(ChannelLimitKind.Unlimited, "channel_flag_maxfamilyclients_inherited=0", "channel_flag_maxfamilyclients_unlimited=1")]
    [InlineData(ChannelLimitKind.Limited, "channel_flag_maxfamilyclients_inherited=0", "channel_flag_maxfamilyclients_unlimited=0")]
    public void TheFamilyLimitHasThreeStates(ChannelLimitKind kind, string inherited, string unlimited)
    {
        var limit = kind == ChannelLimitKind.Limited ? ChannelLimit.Of(8) : new ChannelLimit(kind, 0);
        var wire = ChannelService.BuildCreate(Draft() with { MaxFamilyClients = limit }, 0, 0).ToString();

        Assert.Contains(inherited, wire);
        Assert.Contains(unlimited, wire);
        Assert.Contains(kind == ChannelLimitKind.Limited ? "channel_maxfamilyclients=8" : "channel_maxfamilyclients=0", wire);
    }

    [Fact]
    public void TheCodecEncryptionFlagIsSentInverted()
    {
        // The wire field is channel_codec_is_unencrypted, the opposite of "encrypted".
        Assert.Contains(
            "channel_codec_is_unencrypted=0",
            ChannelService.BuildCreate(Draft(), 0, 0).ToString());

        Assert.Contains(
            "channel_codec_is_unencrypted=1",
            ChannelService.BuildCreate(Draft() with { IsUnencrypted = true }, 0, 0).ToString());
    }

    [Fact]
    public void TheCodecIsSentAsItsNumericValue()
    {
        var wire = ChannelService.BuildCreate(Draft() with { Codec = AudioCodec.OpusMusic, CodecQuality = 10 }, 0, 0).ToString();

        Assert.Contains("channel_codec=5", wire);
        Assert.Contains("channel_codec_quality=10", wire);
    }

    [Theory]
    [InlineData("0", HostBannerScaling.NoAdjust)]
    [InlineData("1", HostBannerScaling.IgnoreAspect)]
    [InlineData("2", HostBannerScaling.KeepAspect)]
    public void TheBannerModeIsParsedFromTheStringChannelinfoReturns(string value, HostBannerScaling expected)
    {
        Assert.Equal(expected, ChannelService.ParseBannerMode(value));
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData("abc")]
    [InlineData("7")]
    [InlineData("-1")]
    public void AnUnparseableBannerModeFallsBackToKeepAspect(string? value)
    {
        Assert.Equal(HostBannerScaling.KeepAspect, ChannelService.ParseBannerMode(value));
    }
}
