// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using System.Windows;
using TeamSpeak9.App.Controls;
using TeamSpeak9.App.ViewModels;

namespace TeamSpeak9.App.Views;

/// <summary>Edits the virtual server configuration.</summary>
public partial class ServerEditorWindow : ShellWindow
{
    private readonly ServerEditorViewModel viewModel;

    internal ServerEditorWindow(ServerEditorViewModel viewModel)
    {
        ArgumentNullException.ThrowIfNull(viewModel);
        this.viewModel = viewModel;

        InitializeComponent();
        DataContext = viewModel;

        viewModel.Saved += OnSaved;
        Closed += (_, _) => viewModel.Saved -= OnSaved;
        Loaded += OnLoaded;
    }

    private void OnSaved(object? sender, EventArgs e)
    {
        DialogResult = true;
    }

    private async void OnLoaded(object sender, RoutedEventArgs e)
    {
        Loaded -= OnLoaded;
        NameBox.Focus();
        await viewModel.LoadAsync();
    }

    /// <remarks>
    /// <see cref="System.Windows.Controls.PasswordBox.Password"/> is not a dependency property, so it
    /// cannot be bound. Pushing it by hand also keeps the password out of WPF's binding caches.
    /// </remarks>
    private void OnPasswordChanged(object sender, RoutedEventArgs e)
    {
        viewModel.Password = ServerPasswordBox.Password;
    }
}
