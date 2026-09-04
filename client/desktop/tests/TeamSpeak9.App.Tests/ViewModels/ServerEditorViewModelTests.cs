// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using Microsoft.Extensions.Logging.Abstractions;
using TeamSpeak9.App.ViewModels;
using TeamSpeak9.Core.Connection;
using TeamSpeak9.Core.Management;
using TeamSpeak9.Core.Model;
using TeamSpeak9.Core.Threading;

namespace TeamSpeak9.App.Tests.ViewModels;

/// <summary>
/// Covers the mapping between the dialog's bindable scalars and <see cref="ServerDraft" />.
/// </summary>
/// <remarks>
/// The view model needs a real <see cref="ServerService" />, which needs a <see cref="TsConnection" />.
/// Nothing here connects: <c>ExecuteAsync</c> short-circuits to <c>CommandError.ConnectionClosed</c>
/// while disconnected, which is exactly what the failure-path assertions want.
/// </remarks>
public class ServerEditorViewModelTests
{
    private static int loopCounter;

    private sealed class Fixture : IAsyncDisposable
    {
        private Fixture(TsSchedulerLoop loop, TsConnection connection, ServerEditorViewModel editor)
        {
            Loop = loop;
            Connection = connection;
            Editor = editor;
        }

        private TsSchedulerLoop Loop { get; }

        private TsConnection Connection { get; }

        public ServerEditorViewModel Editor { get; }

        public static async Task<Fixture> CreateAsync()
        {
            int index = Interlocked.Increment(ref loopCounter);
            var loop = await TsSchedulerLoop.StartAsync($"app-tests-server-editor-{index}");
            var connection = new TsConnection(loop, ImmediateUiDispatcher.Instance, NullLogger<TsConnection>.Instance);
            var service = new ServerService(connection, NullLogger<ServerService>.Instance);
            return new Fixture(loop, connection, new ServerEditorViewModel(service));
        }

        public async ValueTask DisposeAsync()
        {
            await Connection.DisposeAsync();
            await Loop.DisposeAsync();
        }
    }

    private static async Task WithEditor(Func<ServerEditorViewModel, Task> body)
    {
        await using var fixture = await Fixture.CreateAsync();
        await body(fixture.Editor);
    }

    // ----- Construction -----

    [Fact]
    public void AServiceIsRequired()
    {
        Assert.Throws<ArgumentNullException>(() => new ServerEditorViewModel(null!));
    }

    [Fact]
    public async Task TheDialogOpensOnTheGeneralPage()
    {
        await WithEditor(editor =>
        {
            Assert.Equal(ServerEditorPage.General, editor.ActivePage);
            return Task.CompletedTask;
        });
    }

    [Fact]
    public async Task NothingIsLimitedUntilAServerSaysOtherwise()
    {
        // Defaulting to unlimited matches a fresh tsserver and keeps the four number boxes disabled.
        await WithEditor(editor =>
        {
            Assert.True(editor.DownloadBandwidthUnlimited);
            Assert.True(editor.UploadBandwidthUnlimited);
            Assert.True(editor.DownloadQuotaUnlimited);
            Assert.True(editor.UploadQuotaUnlimited);

            Assert.False(editor.IsDownloadBandwidthLimited);
            Assert.False(editor.IsUploadBandwidthLimited);
            Assert.False(editor.IsDownloadQuotaLimited);
            Assert.False(editor.IsUploadQuotaLimited);
            return Task.CompletedTask;
        });
    }

    [Fact]
    public async Task TheStatusLabelStartsEmpty()
    {
        await WithEditor(editor =>
        {
            Assert.False(editor.HasStatus);
            Assert.Equal(string.Empty, editor.StatusText);
            return Task.CompletedTask;
        });
    }

    [Fact]
    public async Task TheOptionListsCoverEveryEnumValue()
    {
        await WithEditor(editor =>
        {
            Assert.Equal(
                Enum.GetValues<HostMessageDisplay>(),
                editor.MessageDisplayOptions.Select(o => o.Value));
            Assert.Equal(
                Enum.GetValues<VoiceEncryptionMode>(),
                editor.EncryptionOptions.Select(o => o.Value));
            Assert.Equal(
                Enum.GetValues<HostBannerScaling>(),
                editor.BannerScalingOptions.Select(o => o.Value));
            return Task.CompletedTask;
        });
    }

    [Fact]
    public async Task EveryOptionIsLabelled()
    {
        await WithEditor(editor =>
        {
            foreach (string label in editor.MessageDisplayOptions.Select(o => o.Label)
                .Concat(editor.EncryptionOptions.Select(o => o.Label))
                .Concat(editor.BannerScalingOptions.Select(o => o.Label)))
            {
                Assert.False(string.IsNullOrWhiteSpace(label));
            }

            return Task.CompletedTask;
        });
    }

    // ----- Command enablement -----

    [Fact]
    public async Task NeitherCommandRunsWithoutAName()
    {
        // The dialog opens with an empty name until LoadAsync fills it in.
        await WithEditor(editor =>
        {
            Assert.False(editor.SaveCommand.CanExecute(null));
            Assert.False(editor.ClearPasswordCommand.CanExecute(null));
            return Task.CompletedTask;
        });
    }

    [Theory]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData("\t")]
    public async Task ABlankNameKeepsBothCommandsOff(string name)
    {
        await WithEditor(editor =>
        {
            editor.Name = name;

            Assert.False(editor.SaveCommand.CanExecute(null));
            Assert.False(editor.ClearPasswordCommand.CanExecute(null));
            return Task.CompletedTask;
        });
    }

    [Fact]
    public async Task ANameEnablesBothCommands()
    {
        await WithEditor(editor =>
        {
            editor.Name = "TeamSpeak9";

            Assert.True(editor.SaveCommand.CanExecute(null));
            Assert.True(editor.ClearPasswordCommand.CanExecute(null));
            return Task.CompletedTask;
        });
    }

    [Fact]
    public async Task BusyKeepsBothCommandsOff()
    {
        await WithEditor(editor =>
        {
            editor.Name = "TeamSpeak9";
            editor.IsBusy = true;

            Assert.False(editor.SaveCommand.CanExecute(null));
            Assert.False(editor.ClearPasswordCommand.CanExecute(null));
            return Task.CompletedTask;
        });
    }

    [Fact]
    public async Task RenamingRefreshesBothButtons()
    {
        // Regression: ClearPasswordCommand was missing from Name's NotifyCanExecuteChangedFor list,
        // so the button stayed greyed out after LoadAsync filled the name in.
        await WithEditor(editor =>
        {
            int save = 0;
            int clear = 0;
            editor.SaveCommand.CanExecuteChanged += (_, _) => save++;
            editor.ClearPasswordCommand.CanExecuteChanged += (_, _) => clear++;

            editor.Name = "TeamSpeak9";

            Assert.Equal(1, save);
            Assert.Equal(1, clear);
            return Task.CompletedTask;
        });
    }

    [Fact]
    public async Task GoingBusyRefreshesBothButtons()
    {
        await WithEditor(editor =>
        {
            int save = 0;
            int clear = 0;
            editor.SaveCommand.CanExecuteChanged += (_, _) => save++;
            editor.ClearPasswordCommand.CanExecuteChanged += (_, _) => clear++;

            editor.IsBusy = true;

            Assert.Equal(1, save);
            Assert.Equal(1, clear);
            return Task.CompletedTask;
        });
    }

    [Fact]
    public async Task TheStatusLabelAppearsAsSoonAsThereIsText()
    {
        await WithEditor(editor =>
        {
            var changed = new List<string>();
            editor.PropertyChanged += (_, e) => changed.Add(e.PropertyName ?? string.Empty);

            editor.StatusText = "出错了";

            Assert.True(editor.HasStatus);
            Assert.Contains(nameof(editor.HasStatus), changed);
            return Task.CompletedTask;
        });
    }

    [Theory]
    [InlineData(nameof(ServerEditorViewModel.DownloadBandwidthUnlimited), nameof(ServerEditorViewModel.IsDownloadBandwidthLimited))]
    [InlineData(nameof(ServerEditorViewModel.UploadBandwidthUnlimited), nameof(ServerEditorViewModel.IsUploadBandwidthLimited))]
    [InlineData(nameof(ServerEditorViewModel.DownloadQuotaUnlimited), nameof(ServerEditorViewModel.IsDownloadQuotaLimited))]
    [InlineData(nameof(ServerEditorViewModel.UploadQuotaUnlimited), nameof(ServerEditorViewModel.IsUploadQuotaLimited))]
    public async Task UncheckingUnlimitedEnablesItsNumberBox(string unlimited, string limited)
    {
        await WithEditor(editor =>
        {
            var changed = new List<string>();
            editor.PropertyChanged += (_, e) => changed.Add(e.PropertyName ?? string.Empty);

            var property = typeof(ServerEditorViewModel).GetProperty(unlimited)!;
            property.SetValue(editor, false);

            Assert.Contains(limited, changed);
            Assert.True((bool)typeof(ServerEditorViewModel).GetProperty(limited)!.GetValue(editor)!);
            return Task.CompletedTask;
        });
    }

    [Fact]
    public async Task ThePasswordBoxExplainsThatBlankMeansKeep()
    {
        await WithEditor(editor =>
        {
            Assert.False(string.IsNullOrWhiteSpace(editor.PasswordHint));
            return Task.CompletedTask;
        });
    }

    // ----- ToDraft -----

    [Fact]
    public async Task TheDraftCarriesTheGeneralPage()
    {
        await WithEditor(editor =>
        {
            editor.Name = "TeamSpeak9";
            editor.PhoneticName = "tim speak nine";
            editor.MaxClients = 512;
            editor.ReservedSlots = 8;
            editor.WeblistEnabled = true;
            editor.TempChannelDeleteDelaySeconds = 90;

            var draft = editor.ToDraft();

            Assert.Equal("TeamSpeak9", draft.Name);
            Assert.Equal("tim speak nine", draft.PhoneticName);
            Assert.Equal(512, draft.MaxClients);
            Assert.Equal(8, draft.ReservedSlots);
            Assert.True(draft.WeblistEnabled);
            Assert.Equal(TimeSpan.FromSeconds(90), draft.TempChannelDefaultDeleteDelay);
            return Task.CompletedTask;
        });
    }

    [Fact]
    public async Task TheNameAndTheBannerUrlsAreTrimmed()
    {
        // Trailing whitespace in a name is invisible in the tree and breaks bookmark matching.
        await WithEditor(editor =>
        {
            editor.Name = "  TeamSpeak9  ";
            editor.PhoneticName = "  tim  ";
            editor.BannerGfxUrl = "  https://example.invalid/b.png  ";
            editor.BannerLinkUrl = "  https://example.invalid/  ";
            editor.ButtonGfxUrl = "  https://example.invalid/x.png  ";
            editor.ButtonUrl = "  https://example.invalid/x  ";

            var draft = editor.ToDraft();

            Assert.Equal("TeamSpeak9", draft.Name);
            Assert.Equal("tim", draft.PhoneticName);
            Assert.Equal("https://example.invalid/b.png", draft.Banner.GfxUrl);
            Assert.Equal("https://example.invalid/", draft.Banner.LinkUrl);
            Assert.Equal("https://example.invalid/x.png", draft.Banner.ButtonGfxUrl);
            Assert.Equal("https://example.invalid/x", draft.Banner.ButtonUrl);
            return Task.CompletedTask;
        });
    }

    [Fact]
    public async Task TheFreeTextFieldsAreLeftAlone()
    {
        // These are shown verbatim, so leading blank lines and indentation are the author's choice.
        await WithEditor(editor =>
        {
            editor.Name = "TeamSpeak9";
            editor.WelcomeMessage = "\n  欢迎  \n";
            editor.Hostmessage = "  公告  ";
            editor.ButtonTooltip = "  提示  ";
            editor.Password = "  secret  ";

            var draft = editor.ToDraft();

            Assert.Equal("\n  欢迎  \n", draft.WelcomeMessage);
            Assert.Equal("  公告  ", draft.Hostmessage);
            Assert.Equal("  提示  ", draft.Banner.ButtonTooltip);
            Assert.Equal("  secret  ", draft.Password);
            return Task.CompletedTask;
        });
    }

    [Theory]
    [InlineData(-1, 0)]
    [InlineData(0, 0)]
    [InlineData(65535, 65535)]
    [InlineData(70000, 65535)]
    public async Task SlotCountsAreClampedToTheWireWidth(int typed, int expected)
    {
        // The number boxes are ints but the wire fields are ushort.
        await WithEditor(editor =>
        {
            editor.Name = "TeamSpeak9";
            editor.MaxClients = typed;
            editor.ReservedSlots = typed;

            var draft = editor.ToDraft();

            Assert.Equal(expected, draft.MaxClients);
            Assert.Equal(expected, draft.ReservedSlots);
            return Task.CompletedTask;
        });
    }

    [Theory]
    [InlineData(-3, 0)]
    [InlineData(0, 0)]
    [InlineData(8, 8)]
    [InlineData(20, 8)]
    public async Task TheSecurityLevelIsClampedToTheRangeTheServerAccepts(int typed, byte expected)
    {
        await WithEditor(editor =>
        {
            editor.Name = "TeamSpeak9";
            editor.IdentitySecurityLevel = typed;

            Assert.Equal(expected, editor.ToDraft().IdentitySecurityLevel);
            return Task.CompletedTask;
        });
    }

    [Theory]
    [InlineData(-1d, 0f)]
    [InlineData(0d, 0f)]
    [InlineData(0.5d, 0.5f)]
    [InlineData(1d, 1f)]
    [InlineData(4d, 1f)]
    public async Task TheDimmModificatorIsClampedToAFraction(double typed, float expected)
    {
        await WithEditor(editor =>
        {
            editor.Name = "TeamSpeak9";
            editor.PrioritySpeakerDimm = typed;

            Assert.Equal(expected, editor.ToDraft().PrioritySpeakerDimmModificator);
            return Task.CompletedTask;
        });
    }

    [Theory]
    [InlineData(-30)]
    [InlineData(0)]
    public async Task NegativeDurationsBecomeZero(int typed)
    {
        // TimeSpan would happily go negative, and Validate rejects that on the delete delay.
        await WithEditor(editor =>
        {
            editor.Name = "TeamSpeak9";
            editor.TempChannelDeleteDelaySeconds = typed;
            editor.BannerRefreshSeconds = typed;
            editor.ComplaintsAutobanMinutes = typed;
            editor.ComplaintsRemoveMinutes = typed;
            editor.MinClientsBeforeForcedSilence = typed;

            var draft = editor.ToDraft();

            Assert.Equal(TimeSpan.Zero, draft.TempChannelDefaultDeleteDelay);
            Assert.Equal(TimeSpan.Zero, draft.Banner.RefreshInterval);
            Assert.Equal(TimeSpan.Zero, draft.Complaints.AutobanTime);
            Assert.Equal(TimeSpan.Zero, draft.Complaints.RemoveTime);
            Assert.Equal(0u, draft.MinClientsInChannelBeforeForcedSilence);
            return Task.CompletedTask;
        });
    }

    [Fact]
    public async Task UnlimitedBandwidthIsTheMaximumValue()
    {
        await WithEditor(editor =>
        {
            editor.Name = "TeamSpeak9";
            editor.DownloadBandwidthUnlimited = true;
            editor.UploadBandwidthUnlimited = true;
            editor.DownloadBandwidthKbps = 42;
            editor.UploadBandwidthKbps = 42;

            var draft = editor.ToDraft();

            Assert.Equal(ulong.MaxValue, draft.Transfers.MaxDownloadBandwidth);
            Assert.Equal(ulong.MaxValue, draft.Transfers.MaxUploadBandwidth);
            return Task.CompletedTask;
        });
    }

    [Fact]
    public async Task BandwidthIsSentInBytesPerSecond()
    {
        // The UI is in KiB/s because that is what the official client shows.
        await WithEditor(editor =>
        {
            editor.Name = "TeamSpeak9";
            editor.DownloadBandwidthUnlimited = false;
            editor.DownloadBandwidthKbps = 1024;
            editor.UploadBandwidthUnlimited = false;
            editor.UploadBandwidthKbps = 0.5;

            var draft = editor.ToDraft();

            Assert.Equal(1024ul * 1024ul, draft.Transfers.MaxDownloadBandwidth);
            Assert.Equal(512ul, draft.Transfers.MaxUploadBandwidth);
            return Task.CompletedTask;
        });
    }

    [Fact]
    public async Task NegativeBandwidthBecomesZeroRatherThanWrappingAround()
    {
        // Casting a negative double straight to ulong is undefined and would read as unlimited.
        await WithEditor(editor =>
        {
            editor.Name = "TeamSpeak9";
            editor.DownloadBandwidthUnlimited = false;
            editor.DownloadBandwidthKbps = -1;

            Assert.Equal(0ul, editor.ToDraft().Transfers.MaxDownloadBandwidth);
            return Task.CompletedTask;
        });
    }

    [Fact]
    public async Task UnlimitedQuotaIsNotTheSameSentinelAsUnlimitedBandwidth()
    {
        // Regression: the quota fields are 32-bit on the wire, so ulong.MaxValue is rejected.
        await WithEditor(editor =>
        {
            editor.Name = "TeamSpeak9";
            editor.DownloadQuotaUnlimited = true;
            editor.UploadQuotaUnlimited = true;

            var draft = editor.ToDraft();

            Assert.Equal(ServerTransferLimits.UnlimitedQuota, draft.Transfers.DownloadQuota);
            Assert.Equal(ServerTransferLimits.UnlimitedQuota, draft.Transfers.UploadQuota);
            Assert.NotEqual(ulong.MaxValue, draft.Transfers.DownloadQuota);
            return Task.CompletedTask;
        });
    }

    [Theory]
    [InlineData(-5d, 0ul)]
    [InlineData(0d, 0ul)]
    [InlineData(10240d, 10240ul)]
    public async Task ALimitedQuotaIsSentInMegabytes(double typed, ulong expected)
    {
        await WithEditor(editor =>
        {
            editor.Name = "TeamSpeak9";
            editor.DownloadQuotaUnlimited = false;
            editor.DownloadQuotaMb = typed;

            Assert.Equal(expected, editor.ToDraft().Transfers.DownloadQuota);
            return Task.CompletedTask;
        });
    }

    [Fact]
    public async Task ALimitedQuotaStaysBelowTheUnlimitedSentinel()
    {
        // Otherwise typing a huge number would silently mean "unlimited".
        await WithEditor(editor =>
        {
            editor.Name = "TeamSpeak9";
            editor.DownloadQuotaUnlimited = false;
            editor.DownloadQuotaMb = 1e12;

            Assert.True(editor.ToDraft().Transfers.DownloadQuota < ServerTransferLimits.UnlimitedQuota);
            return Task.CompletedTask;
        });
    }

    [Fact]
    public async Task TheDraftCarriesTheLoggingAndAntifloodPages()
    {
        await WithEditor(editor =>
        {
            editor.Name = "TeamSpeak9";
            editor.LogClient = false;
            editor.LogQuery = true;
            editor.LogChannel = false;
            editor.LogPermissions = true;
            editor.LogServer = false;
            editor.LogFileTransfer = true;
            editor.FloodPointsTickReduce = 7;
            editor.FloodPointsCommandBlock = 111;
            editor.FloodPointsPluginBlock = 222;
            editor.FloodPointsIpBlock = 333;

            var draft = editor.ToDraft();

            Assert.False(draft.Logging.Client);
            Assert.True(draft.Logging.Query);
            Assert.False(draft.Logging.Channel);
            Assert.True(draft.Logging.Permissions);
            Assert.False(draft.Logging.Server);
            Assert.True(draft.Logging.FileTransfer);
            Assert.Equal(7u, draft.Antiflood.PointsTickReduce);
            Assert.Equal(111u, draft.Antiflood.PointsNeededCommandBlock);
            Assert.Equal(222u, draft.Antiflood.PointsNeededPluginBlock);
            Assert.Equal(333u, draft.Antiflood.PointsNeededIpBlock);
            return Task.CompletedTask;
        });
    }

    [Fact]
    public async Task TheEnumsGoStraightThrough()
    {
        await WithEditor(editor =>
        {
            editor.Name = "TeamSpeak9";
            editor.HostmessageDisplay = HostMessageDisplay.ModalQuit;
            editor.VoiceEncryption = VoiceEncryptionMode.Enabled;
            editor.BannerScaling = HostBannerScaling.KeepAspect;

            var draft = editor.ToDraft();

            Assert.Equal(HostMessageDisplay.ModalQuit, draft.WelcomeMessageDisplay);
            Assert.Equal(VoiceEncryptionMode.Enabled, draft.VoiceEncryption);
            Assert.Equal(HostBannerScaling.KeepAspect, draft.Banner.Scaling);
            return Task.CompletedTask;
        });
    }

    // ----- ApplyDraft -----

    private static ServerDraft SampleDraft() => new()
    {
        Name = "示例服务器",
        PhoneticName = "shi li",
        WelcomeMessage = "欢迎",
        WelcomeMessageDisplay = HostMessageDisplay.Modal,
        Hostmessage = "公告",
        MaxClients = 128,
        ReservedSlots = 4,
        Password = "should-not-surface",
        VoiceEncryption = VoiceEncryptionMode.Disabled,
        WeblistEnabled = true,
        IdentitySecurityLevel = 6,
        TempChannelDefaultDeleteDelay = TimeSpan.FromSeconds(120),
        PrioritySpeakerDimmModificator = 0.25f,
        MinClientsInChannelBeforeForcedSilence = 12,
        Banner = new HostBannerInfo
        {
            GfxUrl = "https://example.invalid/b.png",
            LinkUrl = "https://example.invalid/",
            Scaling = HostBannerScaling.IgnoreAspect,
            RefreshInterval = TimeSpan.FromSeconds(60),
            ButtonGfxUrl = "https://example.invalid/x.png",
            ButtonUrl = "https://example.invalid/x",
            ButtonTooltip = "提示",
        },
        Logging = new ServerLogging { Client = false, Query = false, Channel = false, Permissions = false, Server = false, FileTransfer = false },
        Antiflood = new ServerAntiflood { PointsTickReduce = 9, PointsNeededCommandBlock = 91, PointsNeededPluginBlock = 92, PointsNeededIpBlock = 93 },
        Complaints = new ServerComplaints { AutobanCount = 3, AutobanTime = TimeSpan.FromMinutes(30), RemoveTime = TimeSpan.FromMinutes(45) },
        Transfers = new ServerTransferLimits
        {
            MaxDownloadBandwidth = 2048ul * 1024ul,
            MaxUploadBandwidth = 4096ul * 1024ul,
            DownloadQuota = 500,
            UploadQuota = 600,
        },
    };

    [Fact]
    public async Task ALoadedDraftFillsTheForm()
    {
        await WithEditor(editor =>
        {
            editor.ApplyDraft(SampleDraft());

            Assert.Equal("示例服务器", editor.Name);
            Assert.Equal("shi li", editor.PhoneticName);
            Assert.Equal(128, editor.MaxClients);
            Assert.Equal(4, editor.ReservedSlots);
            Assert.True(editor.WeblistEnabled);
            Assert.Equal(120, editor.TempChannelDeleteDelaySeconds);
            Assert.Equal(HostMessageDisplay.Modal, editor.HostmessageDisplay);
            Assert.Equal(VoiceEncryptionMode.Disabled, editor.VoiceEncryption);
            Assert.Equal(6, editor.IdentitySecurityLevel);
            Assert.Equal(0.25d, editor.PrioritySpeakerDimm, 3);
            Assert.Equal(12, editor.MinClientsBeforeForcedSilence);
            Assert.Equal(HostBannerScaling.IgnoreAspect, editor.BannerScaling);
            Assert.Equal(60, editor.BannerRefreshSeconds);
            Assert.Equal(30, editor.ComplaintsAutobanMinutes);
            Assert.Equal(45, editor.ComplaintsRemoveMinutes);
            Assert.Equal(3u, editor.ComplaintsAutobanCount);
            return Task.CompletedTask;
        });
    }

    [Fact]
    public async Task ALoadedPasswordIsNeverShown()
    {
        // tsserver does not hand out the password, and an empty box is what "keep it" looks like.
        await WithEditor(editor =>
        {
            editor.Password = "typed-earlier";

            editor.ApplyDraft(SampleDraft());

            Assert.Equal(string.Empty, editor.Password);
            return Task.CompletedTask;
        });
    }

    [Fact]
    public async Task ALoadedLimitFillsBothTheCheckboxAndTheNumber()
    {
        await WithEditor(editor =>
        {
            editor.ApplyDraft(SampleDraft());

            Assert.False(editor.DownloadBandwidthUnlimited);
            Assert.Equal(2048d, editor.DownloadBandwidthKbps, 3);
            Assert.False(editor.UploadBandwidthUnlimited);
            Assert.Equal(4096d, editor.UploadBandwidthKbps, 3);
            Assert.False(editor.DownloadQuotaUnlimited);
            Assert.Equal(500d, editor.DownloadQuotaMb, 3);
            Assert.False(editor.UploadQuotaUnlimited);
            Assert.Equal(600d, editor.UploadQuotaMb, 3);
            return Task.CompletedTask;
        });
    }

    [Fact]
    public async Task AnUnlimitedLimitLeavesTheNumberBoxAlone()
    {
        // Zeroing the box would lose the value the user is about to switch back to.
        await WithEditor(editor =>
        {
            editor.DownloadBandwidthUnlimited = false;
            editor.DownloadBandwidthKbps = 777;
            editor.DownloadQuotaUnlimited = false;
            editor.DownloadQuotaMb = 888;

            editor.ApplyDraft(SampleDraft() with { Transfers = new ServerTransferLimits() });

            Assert.True(editor.DownloadBandwidthUnlimited);
            Assert.Equal(777d, editor.DownloadBandwidthKbps, 3);
            Assert.True(editor.DownloadQuotaUnlimited);
            Assert.Equal(888d, editor.DownloadQuotaMb, 3);
            return Task.CompletedTask;
        });
    }

    [Fact]
    public async Task AQuotaAtTheSentinelReadsAsUnlimited()
    {
        await WithEditor(editor =>
        {
            editor.ApplyDraft(SampleDraft() with
            {
                Transfers = new ServerTransferLimits
                {
                    DownloadQuota = ServerTransferLimits.UnlimitedQuota,
                    UploadQuota = ServerTransferLimits.UnlimitedQuota,
                },
            });

            Assert.True(editor.DownloadQuotaUnlimited);
            Assert.True(editor.UploadQuotaUnlimited);
            return Task.CompletedTask;
        });
    }

    [Fact]
    public async Task EverythingButThePasswordSurvivesARoundTrip()
    {
        await WithEditor(editor =>
        {
            var original = SampleDraft();

            editor.ApplyDraft(original);
            var again = editor.ToDraft();

            Assert.Equal(original with { Password = string.Empty }, again);
            return Task.CompletedTask;
        });
    }

    [Fact]
    public async Task ALoadedDraftEnablesTheButtons()
    {
        await WithEditor(editor =>
        {
            editor.ApplyDraft(SampleDraft());

            Assert.True(editor.SaveCommand.CanExecute(null));
            Assert.True(editor.ClearPasswordCommand.CanExecute(null));
            return Task.CompletedTask;
        });
    }

    // ----- Statistics -----

    [Fact]
    public async Task TheStatisticsPageIsEmptyUntilItIsLoaded()
    {
        await WithEditor(editor =>
        {
            Assert.Empty(editor.Statistics);
            return Task.CompletedTask;
        });
    }

    [Fact]
    public async Task EveryStatisticIsLabelledAndFilled()
    {
        await WithEditor(editor =>
        {
            editor.ApplyStatistics(new ServerStatistics());

            Assert.NotEmpty(editor.Statistics);
            foreach (var row in editor.Statistics)
            {
                Assert.False(string.IsNullOrWhiteSpace(row.Label));
                Assert.False(string.IsNullOrWhiteSpace(row.Value));
            }

            return Task.CompletedTask;
        });
    }

    [Fact]
    public async Task ReloadingStatisticsReplacesThemRatherThanAppending()
    {
        await WithEditor(editor =>
        {
            editor.ApplyStatistics(new ServerStatistics());
            int count = editor.Statistics.Count;

            editor.ApplyStatistics(new ServerStatistics());

            Assert.Equal(count, editor.Statistics.Count);
            return Task.CompletedTask;
        });
    }

    [Fact]
    public async Task AnEmptyMachineIdIsShownAsADash()
    {
        await WithEditor(editor =>
        {
            editor.ApplyStatistics(new ServerStatistics { MachineId = string.Empty });

            Assert.Contains(editor.Statistics, row => row.Value == "—");
            return Task.CompletedTask;
        });
    }

    [Fact]
    public async Task ByteCountsPickAUnit()
    {
        await WithEditor(editor =>
        {
            editor.ApplyStatistics(new ServerStatistics
            {
                BytesDownloadedTotal = 5ul * 1024 * 1024 * 1024,
                BytesUploadedTotal = 512,
                BytesDownloadedMonth = 3ul * 1024 * 1024,
                BytesUploadedMonth = 2048,
            });

            Assert.Contains(editor.Statistics, row => row.Value == "5 GB");
            Assert.Contains(editor.Statistics, row => row.Value == "512 B");
            Assert.Contains(editor.Statistics, row => row.Value == "3 MB");
            Assert.Contains(editor.Statistics, row => row.Value == "2 KB");
            return Task.CompletedTask;
        });
    }

    // ----- Save -----

    [Fact]
    public async Task SavingAnInvalidFormReportsWithoutTouchingTheServer()
    {
        // Reserved slots have to stay below the maximum, and no round trip should be attempted.
        await WithEditor(async editor =>
        {
            editor.Name = "TeamSpeak9";
            editor.MaxClients = 4;
            editor.ReservedSlots = 8;

            bool saved = false;
            editor.Saved += (_, _) => saved = true;

            await editor.SaveCommand.ExecuteAsync(null);

            Assert.True(editor.HasStatus);
            Assert.True(editor.StatusIsError);
            Assert.False(saved);
            Assert.False(editor.IsBusy);
        });
    }

    [Fact]
    public async Task AFailedValidationLeavesTheFormUsable()
    {
        // Regression: an early return inside the try/finally would have left IsBusy stuck on.
        await WithEditor(async editor =>
        {
            editor.Name = "TeamSpeak9";
            editor.MaxClients = 0;

            await editor.SaveCommand.ExecuteAsync(null);

            Assert.False(editor.IsBusy);
            Assert.True(editor.SaveCommand.CanExecute(null));
        });
    }

    [Fact]
    public async Task SavingWithoutAConnectionReportsAnError()
    {
        await WithEditor(async editor =>
        {
            editor.ApplyDraft(SampleDraft());

            bool saved = false;
            editor.Saved += (_, _) => saved = true;

            await editor.SaveCommand.ExecuteAsync(null);

            Assert.True(editor.HasStatus);
            Assert.True(editor.StatusIsError);
            Assert.False(saved);
            Assert.False(editor.IsBusy);
        });
    }

    [Fact]
    public async Task ClearingThePasswordWithoutAConnectionReportsAnError()
    {
        // Regression: the outcome was reported as a success because isError was hard-coded to false.
        await WithEditor(async editor =>
        {
            editor.ApplyDraft(SampleDraft());
            editor.Password = "typed";

            await editor.ClearPasswordCommand.ExecuteAsync(null);

            Assert.True(editor.HasStatus);
            Assert.True(editor.StatusIsError);
            Assert.Equal("typed", editor.Password);
            Assert.False(editor.IsBusy);
        });
    }

    [Fact]
    public async Task LoadingWithoutAConnectionReportsAnErrorAndStopsBeingBusy()
    {
        await WithEditor(async editor =>
        {
            await editor.LoadAsync();

            Assert.True(editor.HasStatus);
            Assert.True(editor.StatusIsError);
            Assert.False(editor.IsBusy);
        });
    }
}
