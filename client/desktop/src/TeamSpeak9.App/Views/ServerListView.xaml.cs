// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using TeamSpeak9.App.ViewModels;

namespace TeamSpeak9.App.Views;

/// <summary>
/// Left column: quick connect, the connected server and the bookmark list.
/// </summary>
public partial class ServerListView : UserControl
{
    public ServerListView()
    {
        InitializeComponent();
    }

    /// <summary>
    /// Opens the settings menu anchored above the gear button.
    /// </summary>
    /// <remarks>
    /// A left click does not open a <c>ContextMenu</c> on its own, and without an explicit
    /// PlacementTarget the menu would appear at the mouse position. <see cref="PlacementMode.Top"/>
    /// keeps it inside the window, since the button sits on the bottom edge.
    /// </remarks>
    private void OnOpenSettingsMenu(object sender, RoutedEventArgs e)
    {
        SettingsMenu.PlacementTarget = SettingsButton;
        SettingsMenu.Placement = PlacementMode.Top;
        SettingsMenu.IsOpen = true;
    }

    private void OnOpenThemeGallery(object sender, RoutedEventArgs e)
    {
        // Routed through the window's command so the single-instance handling lives in one place.
        if (Window.GetWindow(this) is MainWindow main)
            MainWindow.OpenThemeGalleryCommand.Execute(null, main);
    }

    private void OnQuickConnectKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key != Key.Enter)
            return;

        if (DataContext is not ShellViewModel vm)
            return;

        e.Handled = true;

        // Commit the text first: UpdateSourceTrigger=PropertyChanged already did, but an IME
        // composition can still be pending, and the command reads the ViewModel property.
        if (sender is TextBox box)
            box.GetBindingExpression(TextBox.TextProperty)?.UpdateSource();

        if (vm.QuickConnectCommand.CanExecute(null))
            vm.QuickConnectCommand.Execute(null);
    }

    private void OnBookmarkDoubleClick(object sender, MouseButtonEventArgs e)
    {
        if (DataContext is not ShellViewModel vm)
            return;

        if (sender is not ListBoxItem { DataContext: BookmarkViewModel bookmark })
            return;

        e.Handled = true;

        if (vm.ConnectBookmarkCommand.CanExecute(bookmark))
            vm.ConnectBookmarkCommand.Execute(bookmark);
    }
}
