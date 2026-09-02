// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

namespace TeamSpeak9.Core.Settings;

public enum PushToTalkMode
{
    /// <summary>Transmit while the hotkey is held.</summary>
    PushToTalk,

    /// <summary>Transmit when input level crosses the threshold.</summary>
    VoiceActivation,

    /// <summary>Always transmit.</summary>
    Continuous,
}

/// <summary>
/// How a screen share should be published when both modes are available.
/// </summary>
public enum StreamModePreference
{
    /// <summary>Try P2P first, fall back to SFU when hole punching fails.</summary>
    PreferP2P,

    /// <summary>Always publish through the side-car SFU.</summary>
    ForceSfu,

    /// <summary>Only ever publish P2P; fail rather than fall back.</summary>
    ForceP2P,
}

public sealed class AudioSettings
{
    /// <summary>Capture device id, or empty for the system default.</summary>
    public string InputDeviceId { get; set; } = string.Empty;

    /// <summary>Playback device id, or empty for the system default.</summary>
    public string OutputDeviceId { get; set; } = string.Empty;

    /// <summary>0..1 linear gain applied to captured audio.</summary>
    public double InputVolume { get; set; } = 1.0;

    /// <summary>0..1 linear gain applied to played back audio.</summary>
    public double OutputVolume { get; set; } = 1.0;

    public bool InputMuted { get; set; }

    public bool OutputMuted { get; set; }

    public PushToTalkMode TransmitMode { get; set; } = PushToTalkMode.VoiceActivation;

    /// <summary>Voice activation threshold in dBFS. Only used in <see cref="PushToTalkMode.VoiceActivation"/>.</summary>
    public double VoiceActivationThresholdDb { get; set; } = -40.0;

    /// <summary>Keeps transmitting for this long after the level drops, to avoid clipping word endings.</summary>
    public int VoiceActivationHangoverMs { get; set; } = 300;

    public bool EchoCancellation { get; set; } = true;

    public bool NoiseSuppression { get; set; } = true;

    /// <summary>Push-to-talk hotkey, as a <c>Ctrl+Shift+K</c> style gesture string.</summary>
    public string PushToTalkHotkey { get; set; } = string.Empty;
}

public sealed class StreamSettings
{
    public StreamModePreference ModePreference { get; set; } = StreamModePreference.PreferP2P;

    /// <summary>
    /// Address of the side-car service, used when the server does not advertise one.
    /// </summary>
    /// <remarks>
    /// The advertised <c>virtualserver_sfu_endpoint</c> takes priority; see
    /// docs/protocol/tssp-v1.md §2.1. Either way the scheme must be <c>wss</c> and the user is
    /// asked to confirm a host the first time it is used.
    /// </remarks>
    public string ManualEndpoint { get; set; } = string.Empty;

    /// <summary>Endpoints the user has confirmed, so we only prompt once per host.</summary>
    public List<string> TrustedEndpoints { get; set; } = [];

    public int MaxWidth { get; set; } = 1920;

    public int MaxHeight { get; set; } = 1080;

    public int MaxFrameRate { get; set; } = 30;

    public int MaxBitrateKbps { get; set; } = 4000;

    /// <summary>Include system audio with the shared screen when the platform allows it.</summary>
    public bool CaptureAudio { get; set; }

    /// <summary>Draw a highlight around the captured region while sharing.</summary>
    public bool ShowCaptureBorder { get; set; } = true;

    /// <summary>Include the mouse cursor in the captured frames.</summary>
    public bool CaptureCursor { get; set; } = true;
}

public sealed class AppearanceSettings
{
    /// <summary>UI language tag; empty follows the OS.</summary>
    public string Language { get; set; } = string.Empty;

    /// <summary>Reserved for a future light theme; the shipped theme is dark.</summary>
    public string Theme { get; set; } = "dark";

    /// <summary>Show the chat panel as the third column.</summary>
    public bool ShowChatPanel { get; set; } = true;

    public double WindowWidth { get; set; } = 1280;

    public double WindowHeight { get; set; } = 800;

    public bool WindowMaximized { get; set; }

    /// <summary>Width of the left sidebar in device-independent pixels.</summary>
    public double SidebarWidth { get; set; } = 260;

    /// <summary>Width of the chat column in device-independent pixels.</summary>
    public double ChatPanelWidth { get; set; } = 380;
}

/// <summary>
/// Everything the client persists between runs, except identities (see
/// <see cref="Identity.IdentityStore"/>) which need separate at-rest protection.
/// </summary>
public sealed class AppSettings
{
    /// <summary>Schema version, so a future migration can tell old files apart.</summary>
    public int Version { get; set; } = 1;

    public string Nickname { get; set; } = Environment.UserName;

    public AudioSettings Audio { get; set; } = new();

    public StreamSettings Stream { get; set; } = new();

    public AppearanceSettings Appearance { get; set; } = new();

    public List<BookmarkEntry> Bookmarks { get; set; } = [];

    /// <summary>Minimum log level written to the log file: Trace/Debug/Info/Warn/Error.</summary>
    public string LogLevel { get; set; } = "Info";
}
