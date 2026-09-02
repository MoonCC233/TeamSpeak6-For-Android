// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

namespace TeamSpeak9.Core.Settings;

/// <summary>
/// A saved server entry shown in the bookmark list.
/// </summary>
public sealed class BookmarkEntry
{
    /// <summary>Stable id, so renaming or re-addressing a bookmark keeps its place in a folder.</summary>
    public string Id { get; set; } = Guid.NewGuid().ToString("N");

    /// <summary>Display name. Falls back to <see cref="Address"/> when empty.</summary>
    public string Name { get; set; } = string.Empty;

    /// <summary>Hostname or IP, optionally with <c>:port</c>. Also accepts a TSDNS name.</summary>
    public string Address { get; set; } = string.Empty;

    public string Nickname { get; set; } = string.Empty;

    /// <summary>Server password. Empty means none; never logged.</summary>
    public string ServerPassword { get; set; } = string.Empty;

    /// <summary>Channel to join on connect: either a name path (<c>Lobby/Home</c>) or <c>/&lt;id&gt;</c>.</summary>
    public string DefaultChannel { get; set; } = string.Empty;

    public string DefaultChannelPassword { get; set; } = string.Empty;

    /// <summary>Id of the identity to connect with; empty means the default identity.</summary>
    public string IdentityId { get; set; } = string.Empty;

    /// <summary>Folder path in the bookmark tree, <c>/</c>-separated. Empty means the root.</summary>
    public string Folder { get; set; } = string.Empty;

    public bool ConnectOnStartup { get; set; }

    public string DisplayName => string.IsNullOrWhiteSpace(Name) ? Address : Name;
}
