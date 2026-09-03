// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using System.ComponentModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using TeamSpeak9.App.Controls;
using TeamSpeak9.App.ViewModels;
using TeamSpeak9.App.Views;

namespace TeamSpeak9.App;

public partial class MainWindow : ShellWindow
{
    /// <summary>Opens the theme gallery, bound to <c>Ctrl+Shift+T</c>.</summary>
    public static readonly RoutedUICommand OpenThemeGalleryCommand =
        new("主题预览", nameof(OpenThemeGalleryCommand), typeof(MainWindow));

    private readonly ShellViewModel? shell;
    private ThemeGalleryWindow? gallery;

    /// <summary>Design-time and XAML-loader constructor.</summary>
    public MainWindow()
    {
        InitializeComponent();
        AddThemeGalleryBinding();
    }

    internal MainWindow(ShellViewModel shell)
    {
        ArgumentNullException.ThrowIfNull(shell);

        this.shell = shell;

        InitializeComponent();
        AddThemeGalleryBinding();

        DataContext = shell;

        // Column widths, the window rect and the chat panel are driven imperatively rather than by
        // binding: GridSplitter assigns ColumnDefinition.Width as a local value, which would blow
        // away any binding set on it, and the window rect has to survive a maximize round trip.
        SidebarColumn.Width = new GridLength(shell.SidebarWidth);
        ChannelColumn.Width = new GridLength(shell.ChannelPanelWidth);
        RestoreWindowState();
        ApplyChatPanelVisibility();

        shell.PropertyChanged += OnShellPropertyChanged;
        Closing += OnClosing;
        Closed += OnClosed;
    }

    /// <summary>
    /// Applies the persisted window size, clamped to the virtual screen.
    /// </summary>
    /// <remarks>
    /// A saved size from a larger or differently arranged monitor set would otherwise open the
    /// window bigger than anything currently attached.
    /// </remarks>
    private void RestoreWindowState()
    {
        if (shell is null)
            return;

        double maxWidth = SystemParameters.VirtualScreenWidth;
        double maxHeight = SystemParameters.VirtualScreenHeight;

        if (shell.WindowWidth >= MinWidth && maxWidth > 0)
            Width = Math.Min(shell.WindowWidth, maxWidth);

        if (shell.WindowHeight >= MinHeight && maxHeight > 0)
            Height = Math.Min(shell.WindowHeight, maxHeight);

        if (shell.WindowMaximized)
            WindowState = WindowState.Maximized;
    }

    private void AddThemeGalleryBinding() =>
        CommandBindings.Add(new CommandBinding(OpenThemeGalleryCommand, (_, _) => OpenThemeGallery()));

    private void OpenThemeGallery()
    {
        if (gallery is not null)
        {
            gallery.Activate();
            return;
        }

        gallery = new ThemeGalleryWindow { Owner = this };
        gallery.Closed += (_, _) => gallery = null;
        gallery.Show();
    }

    private void OnShellPropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(ShellViewModel.IsChatPanelVisible))
            ApplyChatPanelVisibility();
    }

    /// <summary>
    /// Hides the chat column together with its splitter, letting the channel column take the space.
    /// </summary>
    /// <remarks>
    /// The column is zeroed as well as the content collapsed, because a star-sized column keeps its
    /// share of the width even when the child inside it is <c>Collapsed</c>. The channel column then
    /// has to become the star column, otherwise nothing claims the freed width and the window ends
    /// in a blank gap. Its <c>MaxWidth</c> is lifted for the same reason.
    /// </remarks>
    private void ApplyChatPanelVisibility()
    {
        bool visible = shell?.IsChatPanelVisible ?? true;

        ChatPanel.Visibility = visible ? Visibility.Visible : Visibility.Collapsed;
        ChannelSplitter.Visibility = ChatPanel.Visibility;
        ChatColumn.MinWidth = visible ? 360 : 0;
        ChatColumn.Width = visible ? new GridLength(1, GridUnitType.Star) : new GridLength(0);

        if (visible)
        {
            ChannelColumn.MaxWidth = 520;
            ChannelColumn.Width = new GridLength(shell?.ChannelPanelWidth ?? 320);
        }
        else
        {
            ChannelColumn.MaxWidth = double.PositiveInfinity;
            ChannelColumn.Width = new GridLength(1, GridUnitType.Star);
        }
    }

    private void OnSidebarSplitterDragCompleted(object sender, DragCompletedEventArgs e)
    {
        if (shell is not null)
            shell.SidebarWidth = SidebarColumn.ActualWidth;
    }

    private void OnChannelSplitterDragCompleted(object sender, DragCompletedEventArgs e)
    {
        if (shell is not null)
            shell.ChannelPanelWidth = ChannelColumn.ActualWidth;
    }

    private void OnClosing(object? sender, CancelEventArgs e)
    {
        if (shell is null)
            return;

        // Also captures column widths changed with the splitter's keyboard resizing, which does not
        // raise DragCompleted.
        shell.SidebarWidth = SidebarColumn.ActualWidth;
        if (shell.IsChatPanelVisible)
            shell.ChannelPanelWidth = ChannelColumn.ActualWidth;

        // RestoreBounds is only meaningful while maximized or minimized; otherwise it is empty and
        // the live Width/Height are the restore size.
        var bounds = RestoreBounds;
        bool useRestore = WindowState != WindowState.Normal && !bounds.IsEmpty;

        shell.SaveWindowState(
            useRestore ? bounds.Width : Width,
            useRestore ? bounds.Height : Height,
            WindowState == WindowState.Maximized);
    }

    private void OnClosed(object? sender, EventArgs e)
    {
        if (shell is not null)
        {
            shell.PropertyChanged -= OnShellPropertyChanged;
            Closing -= OnClosing;
        }
    }
}
