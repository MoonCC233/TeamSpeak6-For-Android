// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;

namespace TeamSpeak9.App.Views;

/// <summary>
/// Middle segment of the caption strip.
/// </summary>
public partial class TopBarView : UserControl
{
    public TopBarView()
    {
        InitializeComponent();
    }

    private void OnOpenInputMenu(object sender, RoutedEventArgs e) => OpenMenu(InputMenu, sender);

    private void OnOpenOutputMenu(object sender, RoutedEventArgs e) => OpenMenu(OutputMenu, sender);

    /// <summary>
    /// Opens a button's menu anchored to the button.
    /// </summary>
    /// <remarks>
    /// A left click does not open a <c>ContextMenu</c> on its own, and without an explicit
    /// PlacementTarget the menu would appear at the mouse position instead of under the arrow.
    /// </remarks>
    private static void OpenMenu(ContextMenu menu, object sender)
    {
        if (sender is not UIElement anchor)
            return;

        menu.PlacementTarget = anchor;
        menu.Placement = PlacementMode.Bottom;
        menu.IsOpen = true;
    }
}
