// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using TeamSpeak9.Core.Management;
using TeamSpeak9.Core.Model;

namespace TeamSpeak9.Core.Tests.Management;

public class ServerDraftTests
{
    private static ServerDraft Valid() => new()
    {
        Name = "TeamSpeak9 测试服",
        MaxClients = 32,
        ReservedSlots = 0,
    };

    [Fact]
    public void AMinimalDraftValidates()
    {
        Assert.Null(Valid().Validate());
    }

    [Fact]
    public void AnEmptyNameIsRejected()
    {
        Assert.NotNull((Valid() with { Name = string.Empty }).Validate());
        Assert.NotNull((Valid() with { Name = "  " }).Validate());
    }

    [Theory]
    [InlineData(64, true)]
    [InlineData(65, false)]
    public void TheNameLengthLimitIsSixtyFourCharacters(int length, bool expectedValid)
    {
        Assert.Equal(expectedValid, (Valid() with { Name = new string('a', length) }).Validate() is null);
    }

    [Fact]
    public void AServerWithNoSlotsIsRejected()
    {
        Assert.NotNull((Valid() with { MaxClients = 0 }).Validate());
    }

    [Theory]
    [InlineData(31, true)]
    [InlineData(32, false)]
    [InlineData(33, false)]
    public void ReservedSlotsMustLeaveRoomForSomebody(ushort reserved, bool expectedValid)
    {
        var draft = Valid() with { MaxClients = 32, ReservedSlots = reserved };

        Assert.Equal(expectedValid, draft.Validate() is null);
    }

    [Theory]
    [InlineData(0, true)]
    [InlineData(8, true)]
    [InlineData(9, false)]
    public void TheIdentitySecurityLevelIsCappedAtEight(byte level, bool expectedValid)
    {
        Assert.Equal(expectedValid, (Valid() with { IdentitySecurityLevel = level }).Validate() is null);
    }

    [Theory]
    [InlineData(0f, true)]
    [InlineData(0.5f, true)]
    [InlineData(1f, true)]
    [InlineData(-0.1f, false)]
    [InlineData(1.1f, false)]
    public void ThePrioritySpeakerDimmIsAFraction(float value, bool expectedValid)
    {
        var draft = Valid() with { PrioritySpeakerDimmModificator = value };

        Assert.Equal(expectedValid, draft.Validate() is null);
    }

    [Fact]
    public void ANegativeTempChannelDelayIsRejected()
    {
        Assert.NotNull((Valid() with { TempChannelDefaultDeleteDelay = TimeSpan.FromSeconds(-1) }).Validate());
    }

    [Fact]
    public void TheDefaultsMatchAStockServer()
    {
        var antiflood = new ServerAntiflood();
        Assert.Equal(5u, antiflood.PointsTickReduce);
        Assert.Equal(150u, antiflood.PointsNeededCommandBlock);
        Assert.Equal(150u, antiflood.PointsNeededPluginBlock);
        Assert.Equal(250u, antiflood.PointsNeededIpBlock);

        var complaints = new ServerComplaints();
        Assert.Equal(5u, complaints.AutobanCount);
        Assert.Equal(TimeSpan.FromMinutes(5), complaints.AutobanTime);
        Assert.Equal(TimeSpan.FromMinutes(15), complaints.RemoveTime);

        var transfers = new ServerTransferLimits();
        Assert.Equal(ulong.MaxValue, transfers.MaxDownloadBandwidth);
        Assert.Equal(ulong.MaxValue, transfers.MaxUploadBandwidth);
        Assert.Equal(ServerTransferLimits.UnlimitedQuota, transfers.DownloadQuota);
        Assert.Equal(ServerTransferLimits.UnlimitedQuota, transfers.UploadQuota);

        var logging = new ServerLogging();
        Assert.True(logging.Client && logging.Query && logging.Channel);
        Assert.True(logging.Permissions && logging.Server && logging.FileTransfer);
    }
}

public class ServerCommandTests
{
    private static ServerDraft Draft() => new()
    {
        Name = "TeamSpeak9 测试服",
        MaxClients = 32,
    };

    [Fact]
    public void EditSendsTheNameEscaped()
    {
        var wire = ServerService.BuildEdit(Draft()).ToString();

        Assert.StartsWith("serveredit ", wire);
        Assert.Contains(@"virtualserver_name=TeamSpeak9\s测试服", wire);
    }

    [Fact]
    public void EditCoversTheFieldsTslibsMessageClassIsMissing()
    {
        // TSLib's ServerEdit lacks the plugin block field, which is the reason this command is built
        // by hand at all.
        var wire = ServerService.BuildEdit(Draft()).ToString();

        Assert.Contains("virtualserver_antiflood_points_needed_plugin_block=150", wire);
    }

    [Fact]
    public void EditHashesThePasswordRatherThanSendingItPlain()
    {
        var wire = ServerService.BuildEdit(Draft() with { Password = "hunter2" }).ToString();

        Assert.Contains("virtualserver_password=", wire);
        Assert.DoesNotContain("hunter2", wire);
    }

    [Fact]
    public void EditOmitsThePasswordWhenTheFieldWasNotTouched()
    {
        // Empty means "leave alone"; clearing goes through ClearPasswordAsync.
        Assert.DoesNotContain("virtualserver_password", ServerService.BuildEdit(Draft()).ToString());
    }

    [Fact]
    public void DurationsAreSentAsWholeSeconds()
    {
        var draft = Draft() with
        {
            TempChannelDefaultDeleteDelay = TimeSpan.FromMinutes(2),
            Complaints = new ServerComplaints
            {
                AutobanTime = TimeSpan.FromMinutes(30),
                RemoveTime = TimeSpan.FromHours(1),
            },
        };

        var wire = ServerService.BuildEdit(draft).ToString();

        Assert.Contains("virtualserver_channel_temp_delete_delay_default=120", wire);
        Assert.Contains("virtualserver_complain_autoban_time=1800", wire);
        Assert.Contains("virtualserver_complain_remove_time=3600", wire);
    }

    [Theory]
    [InlineData(HostMessageDisplay.None, 0)]
    [InlineData(HostMessageDisplay.Log, 1)]
    [InlineData(HostMessageDisplay.Modal, 2)]
    [InlineData(HostMessageDisplay.ModalQuit, 3)]
    public void TheHostmessageModeMatchesTheDocumentedNumbering(HostMessageDisplay display, int expected)
    {
        var wire = ServerService.BuildEdit(Draft() with { WelcomeMessageDisplay = display }).ToString();

        Assert.Contains($"virtualserver_hostmessage_mode={expected}", wire);
    }

    [Theory]
    [InlineData(VoiceEncryptionMode.Individual, 0)]
    [InlineData(VoiceEncryptionMode.Disabled, 1)]
    [InlineData(VoiceEncryptionMode.Enabled, 2)]
    public void TheEncryptionModeMatchesTheWireNumbering(VoiceEncryptionMode mode, int expected)
    {
        var wire = ServerService.BuildEdit(Draft() with { VoiceEncryption = mode }).ToString();

        Assert.Contains($"virtualserver_codec_encryption_mode={expected}", wire);
    }

    [Fact]
    public void TheBannerBlockIsSentInFull()
    {
        var draft = Draft() with
        {
            Banner = new HostBannerInfo
            {
                GfxUrl = "https://example.invalid/b.png",
                LinkUrl = "https://example.invalid/",
                Scaling = HostBannerScaling.KeepAspect,
                RefreshInterval = TimeSpan.FromMinutes(5),
                ButtonGfxUrl = "https://example.invalid/btn.png",
                ButtonUrl = "https://example.invalid/go",
                ButtonTooltip = "点我",
            },
        };

        var wire = ServerService.BuildEdit(draft).ToString();

        Assert.Contains("virtualserver_hostbanner_gfx_url=", wire);
        Assert.Contains("virtualserver_hostbanner_url=", wire);
        Assert.Contains("virtualserver_hostbanner_mode=2", wire);
        Assert.Contains("virtualserver_hostbanner_gfx_interval=300", wire);
        Assert.Contains("virtualserver_hostbutton_gfx_url=", wire);
        Assert.Contains("virtualserver_hostbutton_url=", wire);
        Assert.Contains("virtualserver_hostbutton_tooltip=点我", wire);
    }

    [Fact]
    public void EveryLogCategoryIsSent()
    {
        var draft = Draft() with { Logging = new ServerLogging { Query = false, FileTransfer = false } };
        var wire = ServerService.BuildEdit(draft).ToString();

        Assert.Contains("virtualserver_log_client=1", wire);
        Assert.Contains("virtualserver_log_query=0", wire);
        Assert.Contains("virtualserver_log_channel=1", wire);
        Assert.Contains("virtualserver_log_permissions=1", wire);
        Assert.Contains("virtualserver_log_server=1", wire);
        Assert.Contains("virtualserver_log_filetransfer=0", wire);
    }

    [Fact]
    public void UnlimitedBandwidthIsSentAsTheUInt64Maximum()
    {
        var wire = ServerService.BuildEdit(Draft()).ToString();

        Assert.Contains($"virtualserver_max_download_total_bandwidth={ulong.MaxValue}", wire);
        Assert.Contains($"virtualserver_max_upload_total_bandwidth={ulong.MaxValue}", wire);
    }

    /// <remarks>
    /// The quota fields are 32-bit server-side, so <c>ulong.MaxValue</c> earns a <c>1540 convert
    /// error</c> that rejects the entire command. Verified against tsserver 6.
    /// </remarks>
    [Fact]
    public void UnlimitedQuotasAreSentAsTheUInt32Maximum()
    {
        var wire = ServerService.BuildEdit(Draft()).ToString();

        Assert.Contains($"virtualserver_download_quota={uint.MaxValue}", wire);
        Assert.Contains($"virtualserver_upload_quota={uint.MaxValue}", wire);
        Assert.DoesNotContain($"quota={ulong.MaxValue}", wire);
    }

    [Fact]
    public void AnOversizedQuotaIsClampedRatherThanRejected()
    {
        var draft = Draft() with
        {
            Transfers = new ServerTransferLimits
            {
                DownloadQuota = ulong.MaxValue,
                UploadQuota = (ulong)uint.MaxValue + 1,
            },
        };

        var wire = ServerService.BuildEdit(draft).ToString();

        Assert.Contains($"virtualserver_download_quota={uint.MaxValue}", wire);
        Assert.Contains($"virtualserver_upload_quota={uint.MaxValue}", wire);
    }

    [Fact]
    public void TheFloatFieldUsesTheInvariantCulture()
    {
        var wire = ServerService.BuildEdit(Draft() with { PrioritySpeakerDimmModificator = 0.5f }).ToString();

        Assert.Contains("virtualserver_priority_speaker_dimm_modificator=0.5", wire);
    }
}
