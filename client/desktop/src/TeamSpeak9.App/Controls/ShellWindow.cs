// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using System.Windows;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Shell;

namespace TeamSpeak9.App.Controls;

/// <summary>
/// Base class for the app's borderless windows.
/// </summary>
/// <remarks>
/// The official client draws its own title bar, so every window here uses
/// <see cref="WindowChrome"/> with a zero caption height and supplies the caption content
/// itself. This class wires up the system commands and the maximized-bounds fix, so views only
/// have to bind buttons to <see cref="SystemCommands"/> and mark their drag area with
/// <c>WindowChrome.IsHitTestVisibleInChrome</c>.
/// </remarks>
public class ShellWindow : Window
{
    /// <summary>Maximizes or restores, for the caption's double click.</summary>
    public static readonly RoutedUICommand ToggleMaximizeCommand =
        new("切换最大化", nameof(ToggleMaximizeCommand), typeof(ShellWindow));

    /// <summary>Opens the native window menu, normally on right-clicking the caption.</summary>
    public static readonly RoutedUICommand ShowSystemMenuCommand =
        new("窗口菜单", nameof(ShowSystemMenuCommand), typeof(ShellWindow));

    private static readonly DependencyPropertyKey IsMaximizedPropertyKey =
        DependencyProperty.RegisterReadOnly(
            nameof(IsMaximized),
            typeof(bool),
            typeof(ShellWindow),
            new PropertyMetadata(false));

    public static readonly DependencyProperty IsMaximizedProperty = IsMaximizedPropertyKey.DependencyProperty;

    protected ShellWindow()
    {
        // A resource reference rather than DefaultStyleKey: an implicit style would only match
        // the exact runtime type, so subclasses would lose the chrome. This still loses to an
        // explicit Style set in XAML, which is applied after the base constructor runs.
        SetResourceReference(StyleProperty, "Window.Shell");

        WindowChromeHelper.Attach(this);

        CommandBindings.Add(new CommandBinding(
            SystemCommands.MinimizeWindowCommand,
            (_, _) => SystemCommands.MinimizeWindow(this)));

        CommandBindings.Add(new CommandBinding(
            SystemCommands.MaximizeWindowCommand,
            (_, _) => SystemCommands.MaximizeWindow(this),
            (_, e) => e.CanExecute = CanResize));

        CommandBindings.Add(new CommandBinding(
            SystemCommands.RestoreWindowCommand,
            (_, _) => SystemCommands.RestoreWindow(this),
            (_, e) => e.CanExecute = CanResize));

        CommandBindings.Add(new CommandBinding(
            SystemCommands.CloseWindowCommand,
            (_, _) => Close()));

        CommandBindings.Add(new CommandBinding(
            ToggleMaximizeCommand,
            (_, _) => ToggleMaximize(),
            (_, e) => e.CanExecute = CanResize));

        CommandBindings.Add(new CommandBinding(
            ShowSystemMenuCommand,
            (_, e) => ShowSystemMenu(e.Parameter)));
    }

    /// <summary>True while the window is maximized. Templates use it to drop the outer border.</summary>
    /// <remarks>
    /// <see cref="Window.WindowState"/> is bindable, but comparing an enum in a trigger needs a
    /// converter; exposing a bool keeps the templates declarative.
    /// </remarks>
    public bool IsMaximized
    {
        get => (bool)GetValue(IsMaximizedProperty);
        private set => SetValue(IsMaximizedPropertyKey, value);
    }

    private bool CanResize => ResizeMode is ResizeMode.CanResize or ResizeMode.CanResizeWithGrip;

    protected override void OnStateChanged(EventArgs e)
    {
        base.OnStateChanged(e);
        IsMaximized = WindowState == WindowState.Maximized;
    }

    private void ToggleMaximize()
    {
        if (WindowState == WindowState.Maximized)
            SystemCommands.RestoreWindow(this);
        else
            SystemCommands.MaximizeWindow(this);
    }

    private void ShowSystemMenu(object? parameter)
    {
        var local = parameter is Point p ? p : Mouse.GetPosition(this);

        // SystemCommands.ShowSystemMenu expects device-independent screen coordinates, while
        // PointToScreen returns physical pixels, so the window's DPI scale has to be undone.
        var screen = PointToScreen(local);
        if (PresentationSource.FromVisual(this)?.CompositionTarget is { } target)
            screen = target.TransformFromDevice.Transform(screen);

        SystemCommands.ShowSystemMenu(this, screen);
    }
}
