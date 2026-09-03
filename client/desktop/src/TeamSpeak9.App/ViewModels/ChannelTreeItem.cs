// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using CommunityToolkit.Mvvm.ComponentModel;

namespace TeamSpeak9.App.ViewModels;

/// <summary>
/// Base for anything that can appear as a row in the channel tree.
/// </summary>
/// <remarks>
/// Channels and clients share one tree because that is how the official client renders them: a
/// client is a leaf under the channel it sits in. The view picks a template per concrete type,
/// so this base only carries what the <c>TreeViewItem</c> style binds to.
/// </remarks>
public abstract class ChannelTreeItem : ObservableObject
{
    private bool isExpanded;
    private bool isSelected;

    protected ChannelTreeItem(bool expanded)
    {
        isExpanded = expanded;
    }

    /// <summary>Child rows: sub-channels followed by the channel's clients.</summary>
    public IReadOnlyList<ChannelTreeItem> Children { get; protected init; } = [];

    /// <summary>Text used for keyboard search and accessibility.</summary>
    public abstract string DisplayName { get; }

    /// <summary>
    /// True for a TS3 spacer, which renders as a divider instead of a normal row.
    /// </summary>
    /// <remarks>
    /// Declared on the base rather than only on <c>ChannelViewModel</c> so the container style can
    /// bind to it for every row; a <c>DataTrigger</c> against a missing property would still work
    /// but would log a binding error for every client row.
    /// </remarks>
    public virtual bool IsSpacer => false;

    public bool IsExpanded
    {
        get => isExpanded;
        set
        {
            if (SetProperty(ref isExpanded, value))
                OnExpandedChanged(value);
        }
    }

    public bool IsSelected
    {
        get => isSelected;
        set
        {
            if (SetProperty(ref isSelected, value))
                OnSelectedChanged(value);
        }
    }

    /// <summary>Called after <see cref="IsExpanded"/> actually changed, to persist it.</summary>
    protected virtual void OnExpandedChanged(bool value)
    {
    }

    /// <summary>Called after <see cref="IsSelected"/> actually changed, to persist it.</summary>
    protected virtual void OnSelectedChanged(bool value)
    {
    }
}
