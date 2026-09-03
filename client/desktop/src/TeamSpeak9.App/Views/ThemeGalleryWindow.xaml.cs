// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using TeamSpeak9.App.Controls;

namespace TeamSpeak9.App.Views;

/// <summary>
/// Debug-only host for <see cref="ThemeGalleryView"/>.
/// </summary>
/// <remarks>
/// The gallery is the living sample used for the style regression pass described in
/// <c>docs/desktop/ui-spec.md</c> §8, so it needs a way in even though the shell replaced it as the
/// main window content. It is opened with <c>Ctrl+Shift+T</c> from the shell.
/// </remarks>
public partial class ThemeGalleryWindow : ShellWindow
{
    public ThemeGalleryWindow()
    {
        InitializeComponent();
    }
}
