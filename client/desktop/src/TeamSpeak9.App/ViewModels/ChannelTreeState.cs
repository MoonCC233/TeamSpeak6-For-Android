// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

namespace TeamSpeak9.App.ViewModels;

/// <summary>
/// View state of the channel tree that has to outlive the tree itself.
/// </summary>
/// <remarks>
/// <see cref="TeamSpeak9.Core.Model.ServerSnapshot"/> is immutable and replaced wholesale on every
/// server event, so the tree view models are rebuilt from scratch each time. Expansion and
/// selection are user state, not server state, and would be lost on every rebuild if they lived on
/// the nodes. Keeping them here - keyed by channel id - makes a rebuild invisible to the user.
/// </remarks>
public sealed class ChannelTreeState
{
    // Collapsed rather than expanded ids: the default in the TreeViewItem style is expanded, so an
    // empty set means "everything open", which is also what a fresh connection should look like.
    private readonly HashSet<ulong> collapsed = [];

    /// <summary>Channel whose row is highlighted, or 0 to follow the channel we are in.</summary>
    public ulong SelectedChannelId { get; private set; }

    public bool IsCollapsed(ulong channelId) => collapsed.Contains(channelId);

    public void SetCollapsed(ulong channelId, bool value)
    {
        if (value)
            collapsed.Add(channelId);
        else
            collapsed.Remove(channelId);
    }

    public bool IsSelected(ulong channelId) => channelId != 0 && SelectedChannelId == channelId;

    public void Select(ulong channelId) => SelectedChannelId = channelId;

    /// <summary>
    /// Clears the selection, but only if <paramref name="channelId"/> is the selected one.
    /// </summary>
    /// <remarks>
    /// A TreeView deselects the old container before selecting the new one, so an unconditional
    /// clear would race with the incoming selection and leave nothing highlighted.
    /// </remarks>
    public void Deselect(ulong channelId)
    {
        if (SelectedChannelId == channelId)
            SelectedChannelId = 0;
    }

    /// <summary>Drops all state, for a new connection.</summary>
    public void Reset()
    {
        collapsed.Clear();
        SelectedChannelId = 0;
    }
}
