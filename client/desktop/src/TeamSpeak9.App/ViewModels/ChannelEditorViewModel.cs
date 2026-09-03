// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using System.Collections.Generic;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using TeamSpeak9.Core.Management;
using TeamSpeak9.Core.Model;

namespace TeamSpeak9.App.ViewModels;

/// <summary>
/// Backs the create/edit channel dialog.
/// </summary>
/// <remarks>
/// <para>
/// The dialog binds to flattened scalars rather than to <see cref="ChannelDraft"/> directly, because
/// the draft is an immutable record and its two <see cref="ChannelLimit"/> fields do not map onto
/// controls one-to-one. <see cref="ToDraft"/> reassembles it on save.
/// </para>
/// <para>
/// Resolved from the container per dialog, then initialised with
/// <see cref="LoadForCreateAsync"/> or <see cref="LoadForEditAsync"/>.
/// </para>
/// </remarks>
public sealed partial class ChannelEditorViewModel : ObservableObject
{
    private readonly ChannelService channels;

    /// <summary>The channel being edited, or 0 while creating.</summary>
    private ulong channelId;

    /// <summary>Parent for a new channel; ignored when editing.</summary>
    private ulong parentId;

    /// <summary>
    /// Whether the channel already has a password, which decides what an empty password box means.
    /// See <see cref="ChannelService.EditAsync"/>.
    /// </summary>
    private bool hasPassword;

    /// <summary>
    /// The name the channel had when the dialog opened. An unchanged name must not be sent, because
    /// the server answers <c>channel_name_inuse</c> for it. See <see cref="ChannelService.BuildEdit"/>.
    /// </summary>
    private string? originalName;

    [ObservableProperty]
    [NotifyCanExecuteChangedFor(nameof(SaveCommand))]
    private string name = string.Empty;

    [ObservableProperty]
    private string phoneticName = string.Empty;

    [ObservableProperty]
    private string topic = string.Empty;

    [ObservableProperty]
    private string description = string.Empty;

    [ObservableProperty]
    private string password = string.Empty;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(SupportsDeleteDelay))]
    private ChannelKind kind = ChannelKind.Permanent;

    [ObservableProperty]
    private int deleteDelaySeconds;

    [ObservableProperty]
    private bool isDefault;

    [ObservableProperty]
    private AudioCodec codec = AudioCodec.OpusVoice;

    [ObservableProperty]
    private int codecQuality = 6;

    [ObservableProperty]
    private int codecLatencyFactor = 1;

    [ObservableProperty]
    private bool isUnencrypted;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsMaxClientsLimited))]
    private bool maxClientsUnlimited = true;

    [ObservableProperty]
    private int maxClientsCount = 16;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsMaxFamilyLimited))]
    private ChannelLimitKind maxFamilyMode = ChannelLimitKind.Inherited;

    [ObservableProperty]
    private int maxFamilyCount = 16;

    [ObservableProperty]
    private int neededTalkPower;

    [ObservableProperty]
    private string bannerGfxUrl = string.Empty;

    [ObservableProperty]
    private HostBannerScaling bannerMode = HostBannerScaling.KeepAspect;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasError))]
    private string errorText = string.Empty;

    [ObservableProperty]
    [NotifyCanExecuteChangedFor(nameof(SaveCommand))]
    private bool isBusy;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(Title))]
    [NotifyPropertyChangedFor(nameof(SaveLabel))]
    [NotifyPropertyChangedFor(nameof(PasswordHint))]
    private bool isCreate = true;

    public ChannelEditorViewModel(ChannelService channels)
    {
        ArgumentNullException.ThrowIfNull(channels);
        this.channels = channels;
    }

    /// <summary>Raised once the change has been applied, so the dialog can close.</summary>
    public event EventHandler? Saved;

    public string Title => IsCreate ? "创建频道" : "编辑频道";

    public string SaveLabel => IsCreate ? "创建" : "保存";

    /// <summary>Explains what leaving the password box empty does, which differs per mode.</summary>
    public string PasswordHint => IsCreate
        ? "留空表示不设密码。"
        : hasPassword
            ? "留空表示保持现有密码不变。"
            : "留空表示不设密码。";

    public bool HasError => ErrorText.Length > 0;

    /// <summary>Only temporary channels take a delete delay; see <see cref="ChannelDraft.SupportsDeleteDelay"/>.</summary>
    public bool SupportsDeleteDelay => Kind == ChannelKind.Temporary;

    public bool IsMaxClientsLimited => !MaxClientsUnlimited;

    public bool IsMaxFamilyLimited => MaxFamilyMode == ChannelLimitKind.Limited;

    public IReadOnlyList<OptionItem<ChannelKind>> KindOptions { get; } =
    [
        new(ChannelKind.Permanent, "永久"),
        new(ChannelKind.SemiPermanent, "半永久"),
        new(ChannelKind.Temporary, "临时"),
    ];

    /// <remarks>
    /// The legacy Speex and CELT codecs are kept because existing channels may still use them and the
    /// dialog must be able to round-trip such a channel without silently changing its codec.
    /// <see cref="AudioCodec.Raw"/> is deliberately absent: it is a TSLib extension that official
    /// clients cannot decode.
    /// </remarks>
    public IReadOnlyList<OptionItem<AudioCodec>> CodecOptions { get; } =
    [
        new(AudioCodec.OpusVoice, "Opus 语音"),
        new(AudioCodec.OpusMusic, "Opus 音乐"),
        new(AudioCodec.CeltMono, "CELT 单声道"),
        new(AudioCodec.SpeexNarrowband, "Speex 窄带"),
        new(AudioCodec.SpeexWideband, "Speex 宽带"),
        new(AudioCodec.SpeexUltraWideband, "Speex 超宽带"),
    ];

    public IReadOnlyList<OptionItem<ChannelLimitKind>> FamilyModeOptions { get; } =
    [
        new(ChannelLimitKind.Inherited, "继承父频道"),
        new(ChannelLimitKind.Unlimited, "不限"),
        new(ChannelLimitKind.Limited, "指定人数"),
    ];

    public IReadOnlyList<OptionItem<HostBannerScaling>> BannerModeOptions { get; } =
    [
        new(HostBannerScaling.NoAdjust, "原始尺寸"),
        new(HostBannerScaling.IgnoreAspect, "拉伸填充"),
        new(HostBannerScaling.KeepAspect, "等比缩放"),
    ];

    /// <summary>Prepares the dialog for a new channel under <paramref name="parent"/>.</summary>
    /// <param name="parent">The parent channel, or <c>null</c> for a root level channel.</param>
    public Task LoadForCreateAsync(ChannelNode? parent)
    {
        IsCreate = true;
        channelId = 0;
        parentId = parent?.ChannelId ?? 0;
        hasPassword = false;
        originalName = null;
        ErrorText = string.Empty;

        ApplyDraft(new ChannelDraft());
        OnPropertyChanged(nameof(PasswordHint));
        return Task.CompletedTask;
    }

    /// <summary>
    /// Prepares the dialog for an existing channel, pulling the fields only <c>channelinfo</c>
    /// reports.
    /// </summary>
    /// <remarks>
    /// A failed <c>channelinfo</c> is not fatal: the book already carries most fields, so the dialog
    /// opens with the description and banner blank and says so.
    /// </remarks>
    public async Task LoadForEditAsync(ChannelNode node)
    {
        ArgumentNullException.ThrowIfNull(node);

        IsCreate = false;
        channelId = node.ChannelId;
        parentId = node.ParentId;
        hasPassword = node.HasPassword;
        originalName = node.Name;
        ErrorText = string.Empty;

        IsBusy = true;
        try
        {
            var loaded = await channels.LoadDraftAsync(node).ConfigureAwait(true);
            if (loaded.Ok && loaded.Value is { } draft)
            {
                ApplyDraft(draft);
            }
            else
            {
                ApplyDraft(ChannelDraft.FromNode(node));
                ErrorText = $"无法读取频道详情（{loaded.Message}），描述与横幅未载入。";
            }
        }
        finally
        {
            IsBusy = false;
        }

        OnPropertyChanged(nameof(PasswordHint));
    }

    private void ApplyDraft(ChannelDraft draft)
    {
        Name = draft.Name;
        PhoneticName = draft.PhoneticName;
        Topic = draft.Topic;
        Description = draft.Description;
        Password = string.Empty;
        Kind = draft.Kind;
        DeleteDelaySeconds = (int)Math.Clamp(draft.DeleteDelay.TotalSeconds, 0, int.MaxValue);
        IsDefault = draft.IsDefault;
        Codec = draft.Codec;
        CodecQuality = draft.CodecQuality;
        CodecLatencyFactor = draft.CodecLatencyFactor;
        IsUnencrypted = draft.IsUnencrypted;

        MaxClientsUnlimited = !draft.MaxClients.IsLimited;
        if (draft.MaxClients.IsLimited)
            MaxClientsCount = draft.MaxClients.Count;

        MaxFamilyMode = draft.MaxFamilyClients.Kind;
        if (draft.MaxFamilyClients.IsLimited)
            MaxFamilyCount = draft.MaxFamilyClients.Count;

        NeededTalkPower = draft.NeededTalkPower;
        BannerGfxUrl = draft.BannerGfxUrl;
        BannerMode = draft.BannerMode;
    }

    private ChannelDraft ToDraft() => new()
    {
        Name = Name.Trim(),
        PhoneticName = PhoneticName.Trim(),
        Topic = Topic,
        Description = Description,
        Password = Password,
        Kind = Kind,
        DeleteDelay = TimeSpan.FromSeconds(Math.Max(0, DeleteDelaySeconds)),
        IsDefault = IsDefault,
        Codec = Codec,
        CodecQuality = (byte)Math.Clamp(CodecQuality, 0, 10),
        CodecLatencyFactor = CodecLatencyFactor,
        IsUnencrypted = IsUnencrypted,
        MaxClients = MaxClientsUnlimited ? ChannelLimit.Unlimited : ChannelLimit.Of(ToCount(MaxClientsCount)),
        MaxFamilyClients = MaxFamilyMode switch
        {
            ChannelLimitKind.Limited => ChannelLimit.Of(ToCount(MaxFamilyCount)),
            ChannelLimitKind.Unlimited => ChannelLimit.Unlimited,
            _ => ChannelLimit.Inherited,
        },
        NeededTalkPower = NeededTalkPower,
        BannerGfxUrl = BannerGfxUrl.Trim(),
        BannerMode = BannerMode,
    };

    private static ushort ToCount(int value) => (ushort)Math.Clamp(value, 1, ushort.MaxValue);

    private bool CanSave => !IsBusy && !string.IsNullOrWhiteSpace(Name);

    [RelayCommand(CanExecute = nameof(CanSave))]
    private async Task SaveAsync()
    {
        ErrorText = string.Empty;

        var draft = ToDraft();
        if (draft.Validate() is { } invalid)
        {
            ErrorText = invalid;
            return;
        }

        // A default channel must be permanent and unprotected; catching it here avoids a round trip
        // that only comes back as a generic parameter error.
        if (draft.IsDefault && draft.Kind != ChannelKind.Permanent)
        {
            ErrorText = "默认频道必须是永久频道。";
            return;
        }

        IsBusy = true;
        try
        {
            var outcome = IsCreate
                ? (await channels.CreateAsync(draft, parentId).ConfigureAwait(true)).WithoutValue()
                : await channels.EditAsync(channelId, draft, hasPassword, originalName).ConfigureAwait(true);

            if (!outcome.Ok)
            {
                ErrorText = outcome.Message;
                return;
            }

            // A second save must not resend the name that the first one just applied.
            originalName = draft.Name;
        }
        finally
        {
            IsBusy = false;
        }

        Saved?.Invoke(this, EventArgs.Empty);
    }
}
