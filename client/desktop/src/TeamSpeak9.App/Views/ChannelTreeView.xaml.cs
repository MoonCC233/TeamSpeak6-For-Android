// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;

namespace TeamSpeak9.App.Views;

/// <summary>
/// Middle column: server banner, channel tree and the action bar.
/// </summary>
public partial class ChannelTreeView : UserControl
{
    public ChannelTreeView()
    {
        InitializeComponent();
    }

    /// <remarks>
    /// A right click does not move the selection on its own, so without this the context menu would
    /// act on whatever was selected before. Clicking empty space clears the selection, which is what
    /// turns "create sub-channel" into "create root channel".
    /// </remarks>
    private void OnTreePreviewRightButtonDown(object sender, MouseButtonEventArgs e)
    {
        var item = FindAncestor<TreeViewItem>(e.OriginalSource as DependencyObject);
        if (item is not null)
        {
            item.IsSelected = true;
            return;
        }

        if (Tree.SelectedItem is ViewModels.ChannelTreeItem selected)
            selected.IsSelected = false;
    }

    private static T? FindAncestor<T>(DependencyObject? node) where T : DependencyObject
    {
        while (node is not null)
        {
            if (node is T match)
                return match;

            node = node is Visual or System.Windows.Media.Media3D.Visual3D
                ? VisualTreeHelper.GetParent(node)
                : LogicalTreeHelper.GetParent(node);
        }

        return null;
    }
}
