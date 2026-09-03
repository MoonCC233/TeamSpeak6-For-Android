// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using System.Collections.ObjectModel;
using System.Globalization;
using System.IO;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using TeamSpeak9.App.Converters;
using TeamSpeak9.Core.Management;
using TeamSpeak9.Core.Model;

namespace TeamSpeak9.App.ViewModels;

/// <summary>One icon in the browser's grid.</summary>
public sealed partial class IconTileViewModel : ObservableObject
{
    public IconTileViewModel(IconEntry entry)
    {
        ArgumentNullException.ThrowIfNull(entry);
        Id = entry.Id;
        SizeText = FormatSize(entry.Size);
        Uploaded = entry.Uploaded;
    }

    /// <summary>Bound to by the image, via <see cref="IconIdToImageConverter"/>.</summary>
    [ObservableProperty]
    private IconId id;

    public string SizeText { get; }

    public DateTime Uploaded { get; }

    public string Label => Id.ToWireString();

    public string ToolTipText => Uploaded == default
        ? $"图标 {Id.ToWireString()}\n{SizeText}"
        : $"图标 {Id.ToWireString()}\n{SizeText}\n上传于 {Uploaded:yyyy-MM-dd HH:mm}";

    /// <summary>
    /// Re-reads the bitmap after the file appears on disk.
    /// </summary>
    /// <remarks>
    /// <see cref="IconIdToImageConverter"/> memoizes misses as <c>null</c>, so a freshly downloaded
    /// icon stays blank until the entry is dropped and the binding re-evaluated.
    /// </remarks>
    public void RefreshImage()
    {
        IconIdToImageConverter.Invalidate(Id);
        OnPropertyChanged(nameof(Id));
    }

    private static string FormatSize(ulong bytes) => bytes < 1024
        ? $"{bytes} 字节"
        : string.Create(CultureInfo.CurrentCulture, $"{bytes / 1024.0:0.#} KB");
}

/// <summary>
/// Backs the icon browser: lists, uploads and deletes server icons, and assigns one to a channel.
/// </summary>
public sealed partial class IconBrowserViewModel : ObservableObject
{
    private readonly IconService icons;

    /// <summary>The channel the chosen icon is applied to.</summary>
    private ulong channelId;

    public IconBrowserViewModel(IconService icons)
    {
        ArgumentNullException.ThrowIfNull(icons);
        this.icons = icons;
    }

    /// <summary>Raised once an icon has been assigned or cleared, so the dialog can close.</summary>
    public event EventHandler? Applied;

    /// <summary>Asks the view for a file to upload. <c>null</c> means the user cancelled.</summary>
    public Func<string?>? PickFile { get; set; }

    /// <summary>Asks the view to confirm a destructive action.</summary>
    public Func<string, bool>? ConfirmDelete { get; set; }

    public ObservableCollection<IconTileViewModel> Icons { get; } = [];

    [ObservableProperty]
    [NotifyCanExecuteChangedFor(nameof(ApplyCommand))]
    [NotifyCanExecuteChangedFor(nameof(DeleteCommand))]
    private IconTileViewModel? selected;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasStatus))]
    private string statusText = string.Empty;

    [ObservableProperty]
    private bool statusIsError;

    [ObservableProperty]
    [NotifyCanExecuteChangedFor(nameof(ApplyCommand))]
    [NotifyCanExecuteChangedFor(nameof(DeleteCommand))]
    [NotifyCanExecuteChangedFor(nameof(UploadCommand))]
    [NotifyCanExecuteChangedFor(nameof(RefreshCommand))]
    [NotifyCanExecuteChangedFor(nameof(ClearIconCommand))]
    private bool isBusy;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(Title))]
    private string channelName = string.Empty;

    /// <summary>The icon the channel currently has, shown as the initial selection.</summary>
    [ObservableProperty]
    private IconId currentIcon = IconId.None;

    public string Title => ChannelName.Length == 0 ? "频道图标" : $"频道图标 — {ChannelName}";

    public bool HasStatus => StatusText.Length > 0;

    public bool IsEmpty => Icons.Count == 0;

    /// <summary>Loads the icon list for a channel.</summary>
    public async Task LoadForChannelAsync(ChannelNode node)
    {
        ArgumentNullException.ThrowIfNull(node);

        channelId = node.ChannelId;
        ChannelName = node.Name;
        CurrentIcon = node.IconId;

        await RefreshAsync().ConfigureAwait(true);
    }

    private void SetStatus(string text, bool isError = false)
    {
        StatusText = text;
        StatusIsError = isError;
    }

    private bool CanInteract => !IsBusy;

    private bool CanUseSelection => !IsBusy && Selected is not null;

    [RelayCommand(CanExecute = nameof(CanInteract))]
    private async Task RefreshAsync()
    {
        IsBusy = true;
        SetStatus("正在读取图标列表…");
        try
        {
            var listed = await icons.ListAsync().ConfigureAwait(true);
            if (!listed.Ok)
            {
                SetStatus(listed.Message, isError: true);
                return;
            }

            var entries = listed.Value!;
            Icons.Clear();
            foreach (var entry in entries.OrderByDescending(e => e.Uploaded))
                Icons.Add(new IconTileViewModel(entry));

            OnPropertyChanged(nameof(IsEmpty));

            Selected = Icons.FirstOrDefault(t => t.Id == CurrentIcon);

            // Icons live in the internal channel, so each one is a separate file transfer; do it after
            // the grid is already populated so the user sees something immediately.
            int fetched = await DownloadMissingAsync().ConfigureAwait(true);

            SetStatus(entries.Count == 0
                ? "服务器上还没有自定义图标。"
                : fetched > 0
                    ? $"共 {entries.Count} 个图标，已下载 {fetched} 个。"
                    : $"共 {entries.Count} 个图标。");
        }
        finally
        {
            IsBusy = false;
        }
    }

    private async Task<int> DownloadMissingAsync()
    {
        int fetched = 0;
        foreach (var tile in Icons)
        {
            if (icons.IsCached(tile.Id))
            {
                // Already on disk, but the converter may hold a miss from before the download.
                tile.RefreshImage();
                continue;
            }

            var outcome = await icons.DownloadAsync(tile.Id).ConfigureAwait(true);
            if (!outcome.Ok)
                continue;

            tile.RefreshImage();
            fetched++;
        }

        return fetched;
    }

    [RelayCommand(CanExecute = nameof(CanInteract))]
    private async Task UploadAsync()
    {
        var path = PickFile?.Invoke();
        if (string.IsNullOrEmpty(path))
            return;

        byte[] content;
        try
        {
            content = await File.ReadAllBytesAsync(path).ConfigureAwait(true);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            SetStatus($"无法读取文件：{ex.Message}", isError: true);
            return;
        }

        // Checked locally first: the server's only reply to an oversized icon is a generic transfer
        // failure, which tells the user nothing.
        if (IconService.ValidateIcon(content) is { } complaint)
        {
            SetStatus(complaint, isError: true);
            return;
        }

        var predicted = IconService.PredictId(content);

        IsBusy = true;
        SetStatus($"正在上传图标 {predicted.ToWireString()}…");
        try
        {
            var uploaded = await icons.UploadAsync(content).ConfigureAwait(true);
            if (!uploaded.Ok)
            {
                SetStatus(uploaded.Message, isError: true);
                return;
            }

            var id = uploaded.Value!;
            IconIdToImageConverter.Invalidate(id);

            var existing = Icons.FirstOrDefault(t => t.Id == id);
            if (existing is not null)
            {
                existing.RefreshImage();
                Selected = existing;
                SetStatus($"图标 {id.ToWireString()} 已存在，内容已覆盖。");
                return;
            }

            var tile = new IconTileViewModel(new IconEntry
            {
                Id = id,
                Size = (ulong)content.Length,
                Uploaded = DateTime.Now,
                CachePath = icons.CachePathFor(id),
            });

            Icons.Insert(0, tile);
            OnPropertyChanged(nameof(IsEmpty));
            Selected = tile;
            SetStatus($"已上传图标 {id.ToWireString()}。");
        }
        finally
        {
            IsBusy = false;
        }
    }

    [RelayCommand(CanExecute = nameof(CanUseSelection))]
    private async Task DeleteAsync()
    {
        if (Selected is not { } tile)
            return;

        if (ConfirmDelete is { } confirm &&
            !confirm($"确定要从服务器删除图标 {tile.Id.ToWireString()} 吗？引用该图标的频道和组会失去图标。"))
        {
            return;
        }

        IsBusy = true;
        try
        {
            var outcome = await icons.DeleteAsync(tile.Id).ConfigureAwait(true);
            if (!outcome.Ok)
            {
                SetStatus(outcome.Message, isError: true);
                return;
            }

            IconIdToImageConverter.Invalidate(tile.Id);
            Icons.Remove(tile);
            OnPropertyChanged(nameof(IsEmpty));
            Selected = null;
            SetStatus($"已删除图标 {tile.Id.ToWireString()}。");
        }
        finally
        {
            IsBusy = false;
        }
    }

    [RelayCommand(CanExecute = nameof(CanUseSelection))]
    private Task ApplyAsync() => AssignAsync(Selected!.Id);

    [RelayCommand(CanExecute = nameof(CanInteract))]
    private Task ClearIconAsync() => AssignAsync(IconId.None);

    private async Task AssignAsync(IconId icon)
    {
        IsBusy = true;
        try
        {
            var outcome = await icons.AssignToChannelAsync(channelId, icon).ConfigureAwait(true);
            if (!outcome.Ok)
            {
                SetStatus(outcome.Message, isError: true);
                return;
            }
        }
        finally
        {
            IsBusy = false;
        }

        Applied?.Invoke(this, EventArgs.Empty);
    }
}
