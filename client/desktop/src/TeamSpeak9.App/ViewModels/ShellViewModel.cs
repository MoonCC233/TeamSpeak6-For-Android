// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Microsoft.Extensions.Logging;
using TeamSpeak9.Core.Connection;
using TeamSpeak9.Core.Identity;
using TeamSpeak9.Core.Model;
using TeamSpeak9.Core.Settings;
using TSLib.Commands;

namespace TeamSpeak9.App.ViewModels;

/// <summary>
/// Which tab the right hand panel shows.
/// </summary>
public enum ChatPanelTab
{
    Info,
    Chat,
    Files,
}

/// <summary>
/// State behind the main window: top bar, server list, channel tree and chat panel.
/// </summary>
/// <remarks>
/// <para>
/// Single instance for the whole app, matching the one <see cref="TsConnection"/>. Every
/// <c>TsConnection</c> event already arrives on the UI thread, so nothing here marshals.
/// </para>
/// <para>
/// Settings that the user can change from the shell (mute flags, panel widths, chat visibility) are
/// written back through <see cref="SettingsStore"/> as they change. The store serialises its writes
/// internally, so it is safe to call from a property setter.
/// </para>
/// </remarks>
public sealed partial class ShellViewModel : ObservableObject, IDisposable
{
    private readonly TsConnection connection;
    private readonly AppSettings settings;
    private readonly SettingsStore settingsStore;
    private readonly IdentityStore identityStore;
    private readonly ILogger<ShellViewModel> log;
    private readonly ChannelTreeState treeState = new();

    private bool disposed;
    private bool isAway;

    [ObservableProperty]
    private string statusText = "未连接";

    [ObservableProperty]
    private bool isBusy;

    /// <summary>Text of the left column's connect box. Enter connects to it.</summary>
    [ObservableProperty]
    private string quickConnectAddress = string.Empty;

    /// <summary>Bookmark filter text.</summary>
    [ObservableProperty]
    private string bookmarkFilter = string.Empty;

    /// <summary>Draft message in the chat composer.</summary>
    [ObservableProperty]
    private string messageDraft = string.Empty;

    [ObservableProperty]
    private ChatPanelTab activeTab = ChatPanelTab.Chat;

    public ShellViewModel(
        TsConnection connection,
        AppSettings settings,
        SettingsStore settingsStore,
        IdentityStore identityStore,
        ILogger<ShellViewModel> log)
    {
        ArgumentNullException.ThrowIfNull(connection);
        ArgumentNullException.ThrowIfNull(settings);
        ArgumentNullException.ThrowIfNull(settingsStore);
        ArgumentNullException.ThrowIfNull(identityStore);
        ArgumentNullException.ThrowIfNull(log);

        this.connection = connection;
        this.settings = settings;
        this.settingsStore = settingsStore;
        this.identityStore = identityStore;
        this.log = log;

        Bookmarks = new ObservableCollection<BookmarkViewModel>();
        Channels = new ObservableCollection<ChannelTreeItem>();
        Messages = new ObservableCollection<MessageViewModel>();

        connection.StateChanged += OnStateChanged;
        connection.SnapshotChanged += OnSnapshotChanged;
        connection.MessageReceived += OnMessageReceived;
        connection.Poked += OnPoked;
        connection.ServerError += OnServerError;

        RebuildBookmarks();
    }

    /// <summary>Bookmark rows for the left column, filtered by <see cref="BookmarkFilter"/>.</summary>
    public ObservableCollection<BookmarkViewModel> Bookmarks { get; }

    /// <summary>Root rows of the channel tree.</summary>
    public ObservableCollection<ChannelTreeItem> Channels { get; }

    public ObservableCollection<MessageViewModel> Messages { get; }

    public ConnectionState State => connection.State;

    public bool IsConnected => connection.IsConnected;

    public bool IsDisconnected => connection.State == ConnectionState.Disconnected
        || connection.State == ConnectionState.Failed;

    /// <summary>True while connecting or reconnecting; drives the progress affordance.</summary>
    public bool IsConnecting => connection.State is ConnectionState.Connecting or ConnectionState.Reconnecting;

    public string ServerName => connection.Snapshot.DisplayName;

    public IconId ServerIconId => connection.Snapshot.IconId;

    public string ServerAddress => connection.Snapshot.Address;

    public int ClientCount => connection.Snapshot.ClientCount;

    public int MaxClients => connection.Snapshot.MaxClients;

    /// <summary><c>12/64</c> for the server banner.</summary>
    public string ServerOccupancyText => MaxClients > 0 ? $"{ClientCount}/{MaxClients}" : ClientCount.ToString();

    public bool HasServerBanner => connection.Snapshot.Banner.HasBanner;

    public string ServerBannerUrl => connection.Snapshot.Banner.GfxUrl;

    /// <summary>Own nickname, from the server when connected and from settings otherwise.</summary>
    public string OwnNickname => connection.Snapshot.OwnClient?.Name is { Length: > 0 } name
        ? name
        : settings.Nickname;

    /// <summary>Channel we are in, for the footer's location line.</summary>
    public string OwnChannelName => connection.Snapshot.OwnChannel?.Name ?? string.Empty;

    public string OwnUid => connection.Snapshot.OwnClient?.Uid ?? string.Empty;

    /// <summary>Title of the chat panel: the current channel, or a placeholder.</summary>
    public string ChatTitle => OwnChannelName is { Length: > 0 } channel ? channel : "聊天";

    public bool HasMessages => Messages.Count > 0;

    // The pill toggles bind IsChecked to these, so "checked" means muted, which is what the
    // Toggle.Pill danger styling expects.
    public bool IsInputMuted
    {
        get => settings.Audio.InputMuted;
        set
        {
            if (settings.Audio.InputMuted == value)
                return;

            settings.Audio.InputMuted = value;
            OnPropertyChanged();
            PersistSettings();
            _ = PushMuteStateAsync("client_input_muted", value);
        }
    }

    public bool IsOutputMuted
    {
        get => settings.Audio.OutputMuted;
        set
        {
            if (settings.Audio.OutputMuted == value)
                return;

            settings.Audio.OutputMuted = value;
            OnPropertyChanged();
            PersistSettings();
            _ = PushMuteStateAsync("client_output_muted", value);
        }
    }

    /// <summary>AFK pill. Maps onto the server's away flag.</summary>
    public bool IsAway
    {
        get => isAway;
        set
        {
            if (isAway == value)
                return;

            isAway = value;
            OnPropertyChanged();
            _ = PushAwayStateAsync(value);
        }
    }

    public bool IsChatPanelVisible
    {
        get => settings.Appearance.ShowChatPanel;
        set
        {
            if (settings.Appearance.ShowChatPanel == value)
                return;

            settings.Appearance.ShowChatPanel = value;
            OnPropertyChanged();
            PersistSettings();
        }
    }

    public double SidebarWidth
    {
        get => settings.Appearance.SidebarWidth;
        set
        {
            // GridSplitter drags fire continuously, so ignore sub-pixel noise to avoid a save storm.
            if (Math.Abs(settings.Appearance.SidebarWidth - value) < 0.5)
                return;

            settings.Appearance.SidebarWidth = value;
            OnPropertyChanged();
            PersistSettings();
        }
    }

    public double ChannelPanelWidth
    {
        get => settings.Appearance.ChannelPanelWidth;
        set
        {
            if (Math.Abs(settings.Appearance.ChannelPanelWidth - value) < 0.5)
                return;

            settings.Appearance.ChannelPanelWidth = value;
            OnPropertyChanged();
            PersistSettings();
        }
    }

    public double WindowWidth => settings.Appearance.WindowWidth;

    public double WindowHeight => settings.Appearance.WindowHeight;

    public bool WindowMaximized => settings.Appearance.WindowMaximized;

    /// <summary>
    /// Stores the window size to restore on the next start.
    /// </summary>
    /// <remarks>
    /// Only the restore bounds are kept, never the maximized bounds, so unmaximizing after a
    /// restart lands on the size the user last chose. Called once while closing rather than on every
    /// resize, so no change notification is raised.
    /// </remarks>
    public void SaveWindowState(double restoreWidth, double restoreHeight, bool maximized)
    {
        if (restoreWidth > 0 && restoreHeight > 0)
        {
            settings.Appearance.WindowWidth = restoreWidth;
            settings.Appearance.WindowHeight = restoreHeight;
        }

        settings.Appearance.WindowMaximized = maximized;
        PersistSettings();
    }

    [RelayCommand]
    private async Task QuickConnectAsync()
    {
        string address = QuickConnectAddress.Trim();
        if (address.Length == 0)
            return;

        await ConnectToAsync(address, nickname: string.Empty, serverPassword: string.Empty, bookmarkId: string.Empty);
    }

    [RelayCommand]
    private async Task ConnectBookmarkAsync(BookmarkViewModel? bookmark)
    {
        if (bookmark is null)
            return;

        var entry = bookmark.Entry;
        await ConnectToAsync(entry.Address, entry.Nickname, entry.ServerPassword, entry.Id, entry);
    }

    [RelayCommand]
    private async Task DisconnectAsync()
    {
        await connection.DisconnectAsync();
        Messages.Clear();
        OnPropertyChanged(nameof(HasMessages));
    }

    [RelayCommand]
    private async Task SendMessageAsync()
    {
        string text = MessageDraft.Trim();
        if (text.Length == 0 || !connection.IsConnected)
            return;

        MessageDraft = string.Empty;

        var result = await connection.ExecuteAsync(c => c.SendChannelMessage(text));
        if (!result.Ok)
        {
            log.LogWarning("Sending a channel message failed: {Error}", result.Error?.Message);
            AppendSystemMessage($"消息发送失败：{result.Error?.Message}");
            return;
        }

        // The server does not echo our own channel messages back, so show it locally.
        Append(new ChatMessage
        {
            Target = ChatTarget.Channel,
            SenderId = connection.Snapshot.OwnClientId,
            SenderName = OwnNickname,
            SenderUid = OwnUid,
            Text = text,
        });
    }

    [RelayCommand]
    private void SelectTab(ChatPanelTab tab) => ActiveTab = tab;

    [RelayCommand]
    private void OpenCommunitySite() => OpenUrl("https://www.teamspeak.com/en/more/community/");

    /// <summary>
    /// Opens a URL in the default browser.
    /// </summary>
    /// <remarks>
    /// <c>UseShellExecute</c> is required: without it .NET tries to execute the URL as a file. Only
    /// http/https are allowed, so a hostile server-supplied banner link cannot launch a program.
    /// </remarks>
    public void OpenUrl(string url)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri)
            || (uri.Scheme != Uri.UriSchemeHttp && uri.Scheme != Uri.UriSchemeHttps))
        {
            log.LogWarning("Refusing to open a non-http(s) URL.");
            return;
        }

        try
        {
            Process.Start(new ProcessStartInfo(uri.AbsoluteUri) { UseShellExecute = true });
        }
        catch (Exception ex) when (ex is Win32Exception or InvalidOperationException)
        {
            log.LogError(ex, "Could not open the default browser.");
        }
    }

    [RelayCommand]
    private void ToggleChatPanel() => IsChatPanelVisible = !IsChatPanelVisible;

    [RelayCommand]
    private async Task RefreshAsync() => await connection.RefreshAsync();

    /// <summary>
    /// Wraps the selection in a BBCode tag, or inserts an empty pair at the caret.
    /// </summary>
    /// <remarks>
    /// The composer is a plain <c>TextBox</c>, so selection handling lives in the view; this only
    /// gets the resulting text. Splitting it this way keeps the ViewModel free of control state.
    /// </remarks>
    public static string ApplyBbCode(string text, int selectionStart, int selectionLength, string tag, out int caret)
    {
        ArgumentNullException.ThrowIfNull(text);
        ArgumentException.ThrowIfNullOrWhiteSpace(tag);

        selectionStart = Math.Clamp(selectionStart, 0, text.Length);
        selectionLength = Math.Clamp(selectionLength, 0, text.Length - selectionStart);

        string open = $"[{tag}]";
        string close = $"[/{tag}]";
        string selected = text.Substring(selectionStart, selectionLength);
        string prefix = text[..selectionStart];
        string suffix = text[(selectionStart + selectionLength)..];

        caret = selectionLength == 0
            ? selectionStart + open.Length
            : selectionStart + open.Length + selectionLength + close.Length;

        return string.Concat(prefix, open, selected, close, suffix);
    }

    public void Dispose()
    {
        if (disposed)
            return;

        disposed = true;

        connection.StateChanged -= OnStateChanged;
        connection.SnapshotChanged -= OnSnapshotChanged;
        connection.MessageReceived -= OnMessageReceived;
        connection.Poked -= OnPoked;
        connection.ServerError -= OnServerError;
    }

    private async Task ConnectToAsync(
        string address,
        string nickname,
        string serverPassword,
        string bookmarkId,
        BookmarkEntry? bookmark = null)
    {
        if (IsBusy)
            return;

        IsBusy = true;
        try
        {
            var (_, stored) = await identityStore.GetOrCreateDefaultAsync();
            var identity = identityStore.Unprotect(stored);

            string effectiveNickname = string.IsNullOrWhiteSpace(nickname) ? settings.Nickname : nickname;

            var request = bookmark is not null
                ? ConnectionRequest.FromBookmark(bookmark, identity, settings.Nickname)
                : new ConnectionRequest
                {
                    Address = address,
                    Identity = identity,
                    Nickname = effectiveNickname,
                    ServerPassword = serverPassword,
                    BookmarkId = bookmarkId,
                };

            Messages.Clear();
            OnPropertyChanged(nameof(HasMessages));
            treeState.Reset();

            string? error = await connection.ConnectAsync(request);
            if (error is not null)
            {
                log.LogWarning("Connecting to {Address} failed: {Error}", request.Address, error);
                AppendSystemMessage(error);
            }
        }
        catch (Exception ex) when (ex is IdentityStoreException or ArgumentException)
        {
            log.LogError(ex, "Could not start a connection to {Address}.", address);
            StatusText = ex.Message;
            AppendSystemMessage(ex.Message);
        }
        finally
        {
            IsBusy = false;
        }
    }

    private async Task PushMuteStateAsync(string field, bool muted)
    {
        if (!connection.IsConnected)
            return;

        var result = await connection.ExecuteAsync(c => c.SendVoid(new TsCommand("clientupdate")
        {
            { field, muted },
        }));

        if (!result.Ok)
            log.LogWarning("Updating {Field} failed: {Error}", field, result.Error?.Message);
    }

    private async Task PushAwayStateAsync(bool away)
    {
        if (!connection.IsConnected)
            return;

        var command = new TsCommand("clientupdate") { { "client_away", away } };
        if (away)
            command.Add("client_away_message", "AFK");

        var result = await connection.ExecuteAsync(c => c.SendVoid(command));
        if (!result.Ok)
            log.LogWarning("Updating the away flag failed: {Error}", result.Error?.Message);
    }

    private void OnStateChanged(object? sender, ConnectionStateChangedEventArgs e)
    {
        StatusText = e.Current switch
        {
            ConnectionState.Connecting => $"正在连接 {e.Detail}…",
            ConnectionState.Connected => $"已连接 {ServerName}",
            ConnectionState.Disconnecting => "正在断开…",
            ConnectionState.Reconnecting => e.HasDetail ? $"正在重连（{e.Detail}）" : "正在重连…",
            ConnectionState.Failed => e.HasDetail ? e.Detail : "连接失败",
            _ => "未连接",
        };

        if (e.Current is ConnectionState.Disconnected or ConnectionState.Failed)
        {
            Channels.Clear();
            treeState.Reset();
        }

        if (e.Current == ConnectionState.Failed && e.HasDetail)
            AppendSystemMessage(e.Detail);

        OnPropertyChanged(nameof(State));
        OnPropertyChanged(nameof(IsConnected));
        OnPropertyChanged(nameof(IsDisconnected));
        OnPropertyChanged(nameof(IsConnecting));
        DisconnectCommand.NotifyCanExecuteChanged();
    }

    private void OnSnapshotChanged(object? sender, ServerSnapshot snapshot)
    {
        RebuildTree(snapshot);

        OnPropertyChanged(nameof(ServerName));
        OnPropertyChanged(nameof(ServerIconId));
        OnPropertyChanged(nameof(ServerAddress));
        OnPropertyChanged(nameof(ClientCount));
        OnPropertyChanged(nameof(MaxClients));
        OnPropertyChanged(nameof(ServerOccupancyText));
        OnPropertyChanged(nameof(HasServerBanner));
        OnPropertyChanged(nameof(ServerBannerUrl));
        OnPropertyChanged(nameof(OwnNickname));
        OnPropertyChanged(nameof(OwnChannelName));
        OnPropertyChanged(nameof(OwnUid));
        OnPropertyChanged(nameof(ChatTitle));
    }

    /// <summary>
    /// Rebuilds the whole tree from the snapshot.
    /// </summary>
    /// <remarks>
    /// A diff would avoid re-creating rows, but the snapshot is coalesced to at most one every
    /// 80 ms and the tree is virtualised, so a rebuild is cheap enough and far easier to get right.
    /// User state survives in <see cref="treeState"/>.
    /// </remarks>
    private void RebuildTree(ServerSnapshot snapshot)
    {
        Channels.Clear();
        foreach (var root in snapshot.Channels)
            Channels.Add(new ChannelViewModel(root, treeState, snapshot));
    }

    private void RebuildBookmarks()
    {
        Bookmarks.Clear();

        string filter = BookmarkFilter.Trim();
        foreach (var entry in settings.Bookmarks)
        {
            if (filter.Length > 0
                && !entry.DisplayName.Contains(filter, StringComparison.CurrentCultureIgnoreCase)
                && !entry.Address.Contains(filter, StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }

            Bookmarks.Add(new BookmarkViewModel(entry));
        }
    }

    private void OnMessageReceived(object? sender, ChatMessage e) => Append(e);

    private void OnPoked(object? sender, ChatMessage e) => Append(e);

    private void OnServerError(object? sender, string e) => AppendSystemMessage(e);

    private void AppendSystemMessage(string text)
    {
        if (string.IsNullOrWhiteSpace(text))
            return;

        Append(new ChatMessage
        {
            Target = ChatTarget.Server,
            SenderId = 0,
            SenderName = "服务器",
            Text = text,
        });
    }

    private void Append(ChatMessage message)
    {
        Messages.Add(new MessageViewModel(message, Messages.Count > 0 ? Messages[^1] : null));

        // Keeps memory bounded on a busy server; the official client also does not keep everything.
        const int limit = 500;
        while (Messages.Count > limit)
            Messages.RemoveAt(0);

        OnPropertyChanged(nameof(HasMessages));
    }

    private void PersistSettings()
    {
        // Fire and forget: SettingsStore serialises its own writes, and a failed settings write
        // must not interrupt whatever the user was doing.
        _ = SaveSettingsAsync();
    }

    private async Task SaveSettingsAsync()
    {
        try
        {
            await settingsStore.SaveAsync(settings);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            log.LogError(ex, "Could not save settings.");
        }
    }

    partial void OnBookmarkFilterChanged(string value) => RebuildBookmarks();
}
