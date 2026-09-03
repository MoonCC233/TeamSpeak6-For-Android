// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using TeamSpeak9.Core.Settings;

namespace TeamSpeak9.App.ViewModels;

/// <summary>
/// One bookmark row in the left column.
/// </summary>
public sealed class BookmarkViewModel
{
    public BookmarkViewModel(BookmarkEntry entry)
    {
        ArgumentNullException.ThrowIfNull(entry);
        Entry = entry;
    }

    public BookmarkEntry Entry { get; }

    public string Id => Entry.Id;

    public string DisplayName => Entry.DisplayName;

    public string Address => Entry.Address;

    /// <summary>Folder path, <c>/</c>-separated. Empty for a root level bookmark.</summary>
    public string Folder => Entry.Folder;

    public string Tooltip => string.IsNullOrWhiteSpace(Entry.Nickname)
        ? Entry.Address
        : $"{Entry.Address}\n昵称：{Entry.Nickname}";
}
