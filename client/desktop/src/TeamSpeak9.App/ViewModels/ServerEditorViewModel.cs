// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using System.Collections.Generic;
using System.Globalization;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using TeamSpeak9.Core.Management;
using TeamSpeak9.Core.Model;

namespace TeamSpeak9.App.ViewModels;

/// <summary>Sections of the server editor, shown as tabs.</summary>
public enum ServerEditorPage
{
    General,
    Messages,
    Security,
    Banner,
    Logging,
    Antiflood,
    Transfers,
    Statistics,
}

/// <summary>
/// Backs the "edit virtual server" dialog, i.e. the <c>serveredit</c> surface.
/// </summary>
/// <remarks>
/// Like <see cref="ChannelEditorViewModel"/> this flattens <see cref="ServerDraft"/> into bindable
/// scalars, because the draft is immutable and its four nested records would each need their own
/// change notifications. <see cref="ToDraft"/> reassembles everything on save.
/// </remarks>
public sealed partial class ServerEditorViewModel : ObservableObject
{
    private readonly ServerService servers;

    public ServerEditorViewModel(ServerService servers)
    {
        ArgumentNullException.ThrowIfNull(servers);
        this.servers = servers;
    }

    /// <summary>Raised once the change has been applied, so the dialog can close.</summary>
    public event EventHandler? Saved;

    [ObservableProperty]
    private ServerEditorPage activePage = ServerEditorPage.General;

    // ----- General -----

    [ObservableProperty]
    [NotifyCanExecuteChangedFor(nameof(SaveCommand))]
    [NotifyCanExecuteChangedFor(nameof(ClearPasswordCommand))]
    private string name = string.Empty;

    [ObservableProperty]
    private string phoneticName = string.Empty;

    [ObservableProperty]
    private int maxClients = 32;

    [ObservableProperty]
    private int reservedSlots;

    [ObservableProperty]
    private bool weblistEnabled;

    [ObservableProperty]
    private int tempChannelDeleteDelaySeconds;

    // ----- Messages -----

    [ObservableProperty]
    private string welcomeMessage = string.Empty;

    [ObservableProperty]
    private string hostmessage = string.Empty;

    [ObservableProperty]
    private HostMessageDisplay hostmessageDisplay = HostMessageDisplay.None;

    // ----- Security -----

    [ObservableProperty]
    private string password = string.Empty;

    [ObservableProperty]
    private VoiceEncryptionMode voiceEncryption = VoiceEncryptionMode.Individual;

    [ObservableProperty]
    private int identitySecurityLevel;

    [ObservableProperty]
    private double prioritySpeakerDimm;

    [ObservableProperty]
    private int minClientsBeforeForcedSilence;

    [ObservableProperty]
    private uint complaintsAutobanCount = 5;

    [ObservableProperty]
    private int complaintsAutobanMinutes = 5;

    [ObservableProperty]
    private int complaintsRemoveMinutes = 15;

    // ----- Banner -----

    [ObservableProperty]
    private string bannerGfxUrl = string.Empty;

    [ObservableProperty]
    private string bannerLinkUrl = string.Empty;

    [ObservableProperty]
    private HostBannerScaling bannerScaling = HostBannerScaling.NoAdjust;

    [ObservableProperty]
    private int bannerRefreshSeconds;

    [ObservableProperty]
    private string buttonGfxUrl = string.Empty;

    [ObservableProperty]
    private string buttonUrl = string.Empty;

    [ObservableProperty]
    private string buttonTooltip = string.Empty;

    // ----- Logging -----

    [ObservableProperty]
    private bool logClient = true;

    [ObservableProperty]
    private bool logQuery = true;

    [ObservableProperty]
    private bool logChannel = true;

    [ObservableProperty]
    private bool logPermissions = true;

    [ObservableProperty]
    private bool logServer = true;

    [ObservableProperty]
    private bool logFileTransfer = true;

    // ----- Antiflood -----

    [ObservableProperty]
    private uint floodPointsTickReduce = 5;

    [ObservableProperty]
    private uint floodPointsCommandBlock = 150;

    [ObservableProperty]
    private uint floodPointsPluginBlock = 150;

    [ObservableProperty]
    private uint floodPointsIpBlock = 250;

    // ----- Transfers -----

    /// <remarks>
    /// The wire fields are bytes per second and megabytes per month with
    /// <see cref="ulong.MaxValue"/> meaning "unlimited"; the UI splits that into a checkbox plus a
    /// number in friendlier units.
    /// </remarks>
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsDownloadBandwidthLimited))]
    private bool downloadBandwidthUnlimited = true;

    [ObservableProperty]
    private double downloadBandwidthKbps = 1024;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsUploadBandwidthLimited))]
    private bool uploadBandwidthUnlimited = true;

    [ObservableProperty]
    private double uploadBandwidthKbps = 1024;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsDownloadQuotaLimited))]
    private bool downloadQuotaUnlimited = true;

    [ObservableProperty]
    private double downloadQuotaMb = 10240;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsUploadQuotaLimited))]
    private bool uploadQuotaUnlimited = true;

    [ObservableProperty]
    private double uploadQuotaMb = 10240;

    // ----- Statistics -----

    /// <summary>Read-only server facts, as label/value pairs so the view needs no per-field markup.</summary>
    public System.Collections.ObjectModel.ObservableCollection<StatisticRow> Statistics { get; } = [];

    // ----- Status -----

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasStatus))]
    private string statusText = string.Empty;

    /// <summary>Distinguishes a failure from a confirmation, because both land in the same label.</summary>
    [ObservableProperty]
    private bool statusIsError = true;

    [ObservableProperty]
    [NotifyCanExecuteChangedFor(nameof(SaveCommand))]
    [NotifyCanExecuteChangedFor(nameof(ClearPasswordCommand))]
    private bool isBusy;

    public bool HasStatus => StatusText.Length > 0;

    public bool IsDownloadBandwidthLimited => !DownloadBandwidthUnlimited;

    public bool IsUploadBandwidthLimited => !UploadBandwidthUnlimited;

    public bool IsDownloadQuotaLimited => !DownloadQuotaUnlimited;

    public bool IsUploadQuotaLimited => !UploadQuotaUnlimited;

    /// <summary>Explains that an empty password box keeps the current one.</summary>
    public string PasswordHint => "留空表示保持现有服务器密码不变。";

    public IReadOnlyList<OptionItem<HostMessageDisplay>> MessageDisplayOptions { get; } =
    [
        new(HostMessageDisplay.None, "不显示"),
        new(HostMessageDisplay.Log, "显示在聊天记录中"),
        new(HostMessageDisplay.Modal, "弹窗显示"),
        new(HostMessageDisplay.ModalQuit, "弹窗显示并断开连接"),
    ];

    public IReadOnlyList<OptionItem<VoiceEncryptionMode>> EncryptionOptions { get; } =
    [
        new(VoiceEncryptionMode.Individual, "由各频道决定"),
        new(VoiceEncryptionMode.Disabled, "全服关闭加密"),
        new(VoiceEncryptionMode.Enabled, "全服强制加密"),
    ];

    public IReadOnlyList<OptionItem<HostBannerScaling>> BannerScalingOptions { get; } =
    [
        new(HostBannerScaling.NoAdjust, "原始尺寸"),
        new(HostBannerScaling.IgnoreAspect, "拉伸填充"),
        new(HostBannerScaling.KeepAspect, "等比缩放"),
    ];

    /// <summary>Reads the current server configuration, then its statistics.</summary>
    public async Task LoadAsync()
    {
        IsBusy = true;
        SetStatus(string.Empty);
        try
        {
            var loaded = await servers.LoadDraftAsync().ConfigureAwait(true);
            if (!loaded.Ok || loaded.Value is null)
            {
                SetStatus(loaded.Message);
                return;
            }

            ApplyDraft(loaded.Value);

            // Separate command, and a failure here must not block editing.
            var stats = await servers.GetStatisticsAsync().ConfigureAwait(true);
            if (stats.Ok && stats.Value is { } value)
                ApplyStatistics(value);
        }
        finally
        {
            IsBusy = false;
        }
    }

    /// <summary>
    /// Copies a loaded draft into the bindable scalars.
    /// </summary>
    /// <remarks>Internal rather than private so tests can round-trip a draft without a server.</remarks>
    internal void ApplyDraft(ServerDraft draft)
    {
        Name = draft.Name;
        PhoneticName = draft.PhoneticName;
        MaxClients = draft.MaxClients;
        ReservedSlots = draft.ReservedSlots;
        WeblistEnabled = draft.WeblistEnabled;
        TempChannelDeleteDelaySeconds = ToSeconds(draft.TempChannelDefaultDeleteDelay);

        WelcomeMessage = draft.WelcomeMessage;
        Hostmessage = draft.Hostmessage;
        HostmessageDisplay = draft.WelcomeMessageDisplay;

        // Never prefilled: the server does not hand out the password, and an empty box means "keep".
        Password = string.Empty;
        VoiceEncryption = draft.VoiceEncryption;
        IdentitySecurityLevel = draft.IdentitySecurityLevel;
        PrioritySpeakerDimm = draft.PrioritySpeakerDimmModificator;
        MinClientsBeforeForcedSilence = (int)Math.Min(draft.MinClientsInChannelBeforeForcedSilence, int.MaxValue);

        ComplaintsAutobanCount = draft.Complaints.AutobanCount;
        ComplaintsAutobanMinutes = ToMinutes(draft.Complaints.AutobanTime);
        ComplaintsRemoveMinutes = ToMinutes(draft.Complaints.RemoveTime);

        BannerGfxUrl = draft.Banner.GfxUrl;
        BannerLinkUrl = draft.Banner.LinkUrl;
        BannerScaling = draft.Banner.Scaling;
        BannerRefreshSeconds = ToSeconds(draft.Banner.RefreshInterval);
        ButtonGfxUrl = draft.Banner.ButtonGfxUrl;
        ButtonUrl = draft.Banner.ButtonUrl;
        ButtonTooltip = draft.Banner.ButtonTooltip;

        LogClient = draft.Logging.Client;
        LogQuery = draft.Logging.Query;
        LogChannel = draft.Logging.Channel;
        LogPermissions = draft.Logging.Permissions;
        LogServer = draft.Logging.Server;
        LogFileTransfer = draft.Logging.FileTransfer;

        FloodPointsTickReduce = draft.Antiflood.PointsTickReduce;
        FloodPointsCommandBlock = draft.Antiflood.PointsNeededCommandBlock;
        FloodPointsPluginBlock = draft.Antiflood.PointsNeededPluginBlock;
        FloodPointsIpBlock = draft.Antiflood.PointsNeededIpBlock;

        ApplyBandwidth(draft.Transfers.MaxDownloadBandwidth,
            out bool dlUnlimited, out double dlKbps);
        DownloadBandwidthUnlimited = dlUnlimited;
        if (!dlUnlimited)
            DownloadBandwidthKbps = dlKbps;

        ApplyBandwidth(draft.Transfers.MaxUploadBandwidth,
            out bool ulUnlimited, out double ulKbps);
        UploadBandwidthUnlimited = ulUnlimited;
        if (!ulUnlimited)
            UploadBandwidthKbps = ulKbps;

        DownloadQuotaUnlimited = IsUnlimitedQuota(draft.Transfers.DownloadQuota);
        if (!DownloadQuotaUnlimited)
            DownloadQuotaMb = draft.Transfers.DownloadQuota;

        UploadQuotaUnlimited = IsUnlimitedQuota(draft.Transfers.UploadQuota);
        if (!UploadQuotaUnlimited)
            UploadQuotaMb = draft.Transfers.UploadQuota;
    }

    /// <remarks>
    /// tsserver reports an unlimited quota as <see cref="ServerTransferLimits.UnlimitedQuota"/>, not
    /// as <see cref="ulong.MaxValue"/> like the bandwidth fields, so the checkbox has to match on it.
    /// </remarks>
    private static bool IsUnlimitedQuota(ulong megabytes) =>
        megabytes >= ServerTransferLimits.UnlimitedQuota;

    private static void ApplyBandwidth(ulong bytesPerSecond, out bool unlimited, out double kbps)
    {
        unlimited = bytesPerSecond == ulong.MaxValue;
        kbps = unlimited ? 0 : bytesPerSecond / 1024.0;
    }

    /// <remarks>Internal rather than private so tests can check the 19 rows without a server.</remarks>
    internal void ApplyStatistics(ServerStatistics stats)
    {
        Statistics.Clear();
        void Add(string label, string value) => Statistics.Add(new StatisticRow(label, value));

        Add("语音端口", stats.Port.ToString(CultureInfo.CurrentCulture));
        Add("随实例自启", stats.Autostart ? "是" : "否");
        Add("运行时长", FormatUptime(stats.Uptime));
        Add("在线客户端", stats.ClientsOnline.ToString(CultureInfo.CurrentCulture));
        Add("在线查询连接", stats.QueriesOnline.ToString(CultureInfo.CurrentCulture));
        Add("频道数", stats.ChannelsOnline.ToString(CultureInfo.CurrentCulture));
        Add("累计客户端连接", stats.ClientConnections.ToString(CultureInfo.CurrentCulture));
        Add("累计查询连接", stats.QueryConnections.ToString(CultureInfo.CurrentCulture));
        Add("平均延迟", string.Create(CultureInfo.CurrentCulture, $"{stats.PingTotal:0.#} ms"));
        Add("平均丢包率", string.Create(CultureInfo.CurrentCulture, $"{stats.PacketlossTotal:0.##} %"));
        Add("累计下载", FormatBytes(stats.BytesDownloadedTotal));
        Add("累计上传", FormatBytes(stats.BytesUploadedTotal));
        Add("本月下载", FormatBytes(stats.BytesDownloadedMonth));
        Add("本月上传", FormatBytes(stats.BytesUploadedMonth));
        Add("机器标识", stats.MachineId.Length == 0 ? "—" : stats.MachineId);
        Add("已设置密码", stats.HasPassword ? "是" : "否");
        Add("最低客户端版本", FormatVersion(stats.MinClientVersion));
        Add("最低 Android 版本", FormatVersion(stats.MinAndroidVersion));
        Add("最低 iOS 版本", FormatVersion(stats.MinIosVersion));
    }

    /// <remarks>Internal rather than private so tests can assert the clamping without a server.</remarks>
    internal ServerDraft ToDraft() => new()
    {
        Name = Name.Trim(),
        PhoneticName = PhoneticName.Trim(),
        WelcomeMessage = WelcomeMessage,
        WelcomeMessageDisplay = HostmessageDisplay,
        Hostmessage = Hostmessage,
        MaxClients = ToUInt16(MaxClients),
        ReservedSlots = ToUInt16(ReservedSlots),
        Password = Password,
        VoiceEncryption = VoiceEncryption,
        WeblistEnabled = WeblistEnabled,
        IdentitySecurityLevel = (byte)Math.Clamp(IdentitySecurityLevel, 0, 8),
        TempChannelDefaultDeleteDelay = TimeSpan.FromSeconds(Math.Max(0, TempChannelDeleteDelaySeconds)),
        PrioritySpeakerDimmModificator = (float)Math.Clamp(PrioritySpeakerDimm, 0d, 1d),
        MinClientsInChannelBeforeForcedSilence = (uint)Math.Max(0, MinClientsBeforeForcedSilence),
        Banner = new HostBannerInfo
        {
            GfxUrl = BannerGfxUrl.Trim(),
            LinkUrl = BannerLinkUrl.Trim(),
            Scaling = BannerScaling,
            RefreshInterval = TimeSpan.FromSeconds(Math.Max(0, BannerRefreshSeconds)),
            ButtonGfxUrl = ButtonGfxUrl.Trim(),
            ButtonUrl = ButtonUrl.Trim(),
            ButtonTooltip = ButtonTooltip,
        },
        Logging = new ServerLogging
        {
            Client = LogClient,
            Query = LogQuery,
            Channel = LogChannel,
            Permissions = LogPermissions,
            Server = LogServer,
            FileTransfer = LogFileTransfer,
        },
        Antiflood = new ServerAntiflood
        {
            PointsTickReduce = FloodPointsTickReduce,
            PointsNeededCommandBlock = FloodPointsCommandBlock,
            PointsNeededPluginBlock = FloodPointsPluginBlock,
            PointsNeededIpBlock = FloodPointsIpBlock,
        },
        Complaints = new ServerComplaints
        {
            AutobanCount = ComplaintsAutobanCount,
            AutobanTime = TimeSpan.FromMinutes(Math.Max(0, ComplaintsAutobanMinutes)),
            RemoveTime = TimeSpan.FromMinutes(Math.Max(0, ComplaintsRemoveMinutes)),
        },
        Transfers = new ServerTransferLimits
        {
            MaxDownloadBandwidth = FromKbps(DownloadBandwidthUnlimited, DownloadBandwidthKbps),
            MaxUploadBandwidth = FromKbps(UploadBandwidthUnlimited, UploadBandwidthKbps),
            DownloadQuota = FromQuota(DownloadQuotaUnlimited, DownloadQuotaMb),
            UploadQuota = FromQuota(UploadQuotaUnlimited, UploadQuotaMb),
        },
    };

    private bool CanSave => !IsBusy && !string.IsNullOrWhiteSpace(Name);

    [RelayCommand(CanExecute = nameof(CanSave))]
    private async Task SaveAsync()
    {
        SetStatus(string.Empty);

        var draft = ToDraft();
        if (draft.Validate() is { } invalid)
        {
            SetStatus(invalid);
            return;
        }

        IsBusy = true;
        try
        {
            var outcome = await servers.EditAsync(draft).ConfigureAwait(true);
            if (!outcome.Ok)
            {
                SetStatus(outcome.Message);
                return;
            }
        }
        finally
        {
            IsBusy = false;
        }

        Saved?.Invoke(this, EventArgs.Empty);
    }

    /// <summary>Clears the server password outright, which an empty password box cannot express.</summary>
    [RelayCommand(CanExecute = nameof(CanSave))]
    private async Task ClearPasswordAsync()
    {
        IsBusy = true;
        SetStatus(string.Empty);
        try
        {
            var outcome = await servers.ClearPasswordAsync().ConfigureAwait(true);
            SetStatus(outcome.Ok ? "已清除服务器密码。" : outcome.Message, isError: !outcome.Ok);
            if (outcome.Ok)
                Password = string.Empty;
        }
        finally
        {
            IsBusy = false;
        }
    }

    private void SetStatus(string text, bool isError = true)
    {
        StatusText = text;
        StatusIsError = isError;
    }

    private static ushort ToUInt16(int value) => (ushort)Math.Clamp(value, 0, ushort.MaxValue);

    private static int ToSeconds(TimeSpan value) => (int)Math.Clamp(value.TotalSeconds, 0, int.MaxValue);

    private static int ToMinutes(TimeSpan value) => (int)Math.Clamp(value.TotalMinutes, 0, int.MaxValue);

    private static ulong FromKbps(bool unlimited, double kbps) => unlimited
        ? ulong.MaxValue
        : (ulong)Math.Clamp(kbps * 1024d, 0d, ulong.MaxValue);

    private static ulong FromQuota(bool unlimited, double megabytes) => unlimited
        ? ServerTransferLimits.UnlimitedQuota
        : (ulong)Math.Clamp(megabytes, 0d, ServerTransferLimits.UnlimitedQuota - 1d);

    private static string FormatUptime(TimeSpan uptime) => uptime.TotalDays >= 1
        ? string.Create(CultureInfo.CurrentCulture, $"{(int)uptime.TotalDays} 天 {uptime.Hours} 小时 {uptime.Minutes} 分")
        : string.Create(CultureInfo.CurrentCulture, $"{uptime.Hours} 小时 {uptime.Minutes} 分");

    private static string FormatBytes(ulong bytes)
    {
        string[] units = ["B", "KB", "MB", "GB", "TB"];
        double value = bytes;
        int unit = 0;
        while (value >= 1024d && unit < units.Length - 1)
        {
            value /= 1024d;
            unit++;
        }

        return string.Create(CultureInfo.CurrentCulture, $"{value:0.##} {units[unit]}");
    }

    /// <remarks>
    /// <c>virtualserver_min_client_version</c> is an encoded build timestamp rather than a dotted
    /// version, so it is shown raw with 0 meaning "no minimum".
    /// </remarks>
    private static string FormatVersion(uint encoded) => encoded == 0
        ? "无限制"
        : encoded.ToString(CultureInfo.CurrentCulture);
}

/// <summary>One read-only server statistic.</summary>
public sealed record StatisticRow(string Label, string Value);
