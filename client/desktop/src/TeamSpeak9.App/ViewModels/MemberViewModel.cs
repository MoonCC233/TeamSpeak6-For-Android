// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using TeamSpeak9.Core.Model;

namespace TeamSpeak9.App.ViewModels;

/// <summary>
/// One client row in the channel tree.
/// </summary>
public sealed class MemberViewModel : ChannelTreeItem
{
    public MemberViewModel(ChannelMember member, ServerSnapshot snapshot)
        : base(expanded: false)
    {
        ArgumentNullException.ThrowIfNull(member);
        ArgumentNullException.ThrowIfNull(snapshot);

        Member = member;
        IsSelf = snapshot.OwnClientId != 0 && snapshot.OwnClientId == member.ClientId;

        var channel = snapshot.FindChannel(member.ChannelId);
        CanTalk = channel is null || member.CanTalk(channel.NeededTalkPower);
    }

    public ChannelMember Member { get; }

    public ushort ClientId => Member.ClientId;

    public string Name => Member.Name;

    public override string DisplayName => Member.Name;

    /// <summary>Stable across reconnects, so nickname colouring does not change. May be empty.</summary>
    public string Uid => Member.Uid;

    /// <summary>True for our own row, which the tree renders in bold.</summary>
    public bool IsSelf { get; }

    /// <summary>Query clients get a muted style; they have no voice.</summary>
    public bool IsQuery => Member.Kind == ClientKind.Query;

    public bool IsTalking => Member.IsTalking;

    public bool IsAway => Member.IsAway;

    public string AwayMessage => Member.AwayMessage ?? string.Empty;

    public bool HasAwayMessage => !string.IsNullOrWhiteSpace(Member.AwayMessage);

    /// <summary>
    /// Microphone state as one of three glyphs: hardware off, muted, or normal.
    /// </summary>
    /// <remarks>
    /// The hardware flag wins because it means "no capture device at all", which the official
    /// client shows differently from a deliberate mute.
    /// </remarks>
    public string InputIcon => Member.InputHardwareDisabled || Member.InputMuted
        ? "Icon.MicOff"
        : "Icon.Mic";

    public bool ShowInputIcon => Member.InputMuted || Member.InputHardwareDisabled;

    /// <summary>Output muted covers both flags: either way the client hears nothing.</summary>
    public bool ShowOutputIcon => Member.OutputMuted || Member.OutputHardwareDisabled;

    public bool IsChannelCommander => Member.IsChannelCommander;

    public bool IsPrioritySpeaker => Member.IsPrioritySpeaker;

    public bool IsRecording => Member.IsRecording;

    public bool IsRequestingTalkPower => Member.IsRequestingTalkPower;

    /// <summary>False when the channel's talk power requirement is not met.</summary>
    public bool CanTalk { get; }

    /// <summary>Two-letter country code, or empty. Used for the flag badge.</summary>
    public string CountryCode => Member.CountryCode;

    public bool HasCountry => Member.CountryCode.Length == 2;

    public string Tooltip
    {
        get
        {
            var parts = new List<string>(4) { Member.Name };

            if (IsAway)
                parts.Add(HasAwayMessage ? $"离开：{Member.AwayMessage}" : "离开");
            if (Member.IsRecording)
                parts.Add("正在录音");
            if (Member.IsChannelCommander)
                parts.Add("频道指挥");
            if (Member.IsPrioritySpeaker)
                parts.Add("优先发言者");
            if (IsRequestingTalkPower)
                parts.Add(string.IsNullOrWhiteSpace(Member.TalkPowerRequestMessage)
                    ? "请求发言权限"
                    : $"请求发言权限：{Member.TalkPowerRequestMessage}");
            else if (!CanTalk)
                parts.Add("无发言权限");
            if (!string.IsNullOrWhiteSpace(Member.Description))
                parts.Add(Member.Description);

            return string.Join("\n", parts);
        }
    }
}
