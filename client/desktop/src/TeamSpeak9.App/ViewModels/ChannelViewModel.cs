// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using TeamSpeak9.Core.Model;

namespace TeamSpeak9.App.ViewModels;

/// <summary>
/// One channel row in the tree, plus its sub-channels and clients.
/// </summary>
public sealed class ChannelViewModel : ChannelTreeItem
{
    private readonly ChannelTreeState state;

    public ChannelViewModel(ChannelNode node, ChannelTreeState state, ServerSnapshot snapshot)
        : base(!state.IsCollapsed(node.ChannelId))
    {
        ArgumentNullException.ThrowIfNull(node);
        ArgumentNullException.ThrowIfNull(state);
        ArgumentNullException.ThrowIfNull(snapshot);

        this.state = state;
        Node = node;

        IsCurrent = snapshot.OwnChannelId == node.ChannelId;

        // Children are already in display order (ChannelOrdering ran while building the snapshot),
        // so this only has to concatenate: sub-channels first, then the clients standing here.
        var children = new List<ChannelTreeItem>(node.Children.Length + node.Members.Length);
        foreach (var child in node.Children)
            children.Add(new ChannelViewModel(child, state, snapshot));
        foreach (var member in node.Members)
            children.Add(new MemberViewModel(member, snapshot));
        Children = children;

        // Restore selection without going through the setter, which would write back to the state.
        if (state.IsSelected(node.ChannelId))
            base.IsSelected = true;
    }

    public ChannelNode Node { get; }

    public ulong ChannelId => Node.ChannelId;

    public string Name => Node.Name;

    public override string DisplayName => Node.Name;

    public IconId IconId => Node.IconId;

    public override bool IsSpacer => Node.IsSpacer;

    /// <summary>
    /// A <c>[*spacer]</c> row, which the official client draws as a repeated fill.
    /// </summary>
    /// <remarks>
    /// The caption is not actually tiled here; the row becomes a divider line with the caption on
    /// it, which reads the same at these row heights and avoids a tiling brush per row.
    /// </remarks>
    public bool IsRepeatSpacer => Node.Spacer?.Alignment == SpacerAlignment.Repeat;

    /// <summary>Alignment of a spacer row; <c>Left</c> for everything else.</summary>
    public SpacerAlignment SpacerAlignment => Node.Spacer?.Alignment ?? SpacerAlignment.Left;

    /// <summary>Spacer caption, already stripped of the <c>[…spacer…]</c> prefix.</summary>
    public string SpacerCaption => Node.Spacer?.Caption ?? string.Empty;

    /// <summary>True for the channel we are in. Drives the accent marker.</summary>
    public bool IsCurrent { get; }

    public bool HasPassword => Node.HasPassword;

    public bool IsFull => Node.IsFull;

    public bool IsSubscribed => Node.Subscribed;

    public bool HasTopic => !string.IsNullOrWhiteSpace(Node.Topic);

    public string Topic => Node.Topic;

    /// <summary>Clients standing directly in this channel.</summary>
    public int MemberCount => Node.MemberCount;

    /// <summary>
    /// Right-aligned occupancy badge, e.g. <c>3/10</c>. Empty when there is nothing worth showing.
    /// </summary>
    /// <remarks>
    /// An unsubscribed channel reports no members at all, so the count would read 0 and look like
    /// an empty channel. Only the limit is shown in that case.
    /// </remarks>
    public string OccupancyText
    {
        get
        {
            var limit = Node.MaxClients;
            if (!Node.Subscribed)
                return limit.IsLimited ? $"–/{limit.Count}" : string.Empty;

            if (limit.IsLimited)
                return $"{Node.MemberCount}/{limit.Count}";

            return Node.MemberCount > 0 ? Node.MemberCount.ToString() : string.Empty;
        }
    }

    public bool HasOccupancy => OccupancyText.Length > 0;

    /// <summary>Tooltip text: name, topic and the codec, whichever are present.</summary>
    public string Tooltip
    {
        get
        {
            var parts = new List<string>(3) { Node.Name };
            if (HasTopic)
                parts.Add(Node.Topic);
            if (Node.MaxClients.IsLimited)
                parts.Add($"人数上限 {Node.MaxClients.Count}");
            return string.Join("\n", parts);
        }
    }

    protected override void OnExpandedChanged(bool value) => state.SetCollapsed(ChannelId, !value);

    protected override void OnSelectedChanged(bool value)
    {
        if (value)
            state.Select(ChannelId);
        else
            state.Deselect(ChannelId);
    }
}
