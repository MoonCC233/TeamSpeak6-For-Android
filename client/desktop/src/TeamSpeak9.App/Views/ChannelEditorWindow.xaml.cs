// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using System.Windows;
using System.Windows.Controls;
using TeamSpeak9.App.Controls;
using TeamSpeak9.App.ViewModels;

namespace TeamSpeak9.App.Views;

/// <summary>Create/edit channel dialog.</summary>
public partial class ChannelEditorWindow : ShellWindow
{
    private readonly ChannelEditorViewModel viewModel;

    internal ChannelEditorWindow(ChannelEditorViewModel viewModel)
    {
        ArgumentNullException.ThrowIfNull(viewModel);
        this.viewModel = viewModel;

        InitializeComponent();
        DataContext = viewModel;

        viewModel.Saved += OnSaved;
        Closed += (_, _) => viewModel.Saved -= OnSaved;
        Loaded += (_, _) => NameBox.Focus();
    }

    private void OnSaved(object? sender, EventArgs e)
    {
        DialogResult = true;
    }

    /// <remarks>
    /// <see cref="PasswordBox.Password"/> is not a dependency property, so it cannot be bound. Pushing
    /// it into the view model by hand is the standard workaround, and keeping the value out of the
    /// binding engine also keeps it out of WPF's internal caches.
    /// </remarks>
    private void OnPasswordChanged(object sender, RoutedEventArgs e)
    {
        if (sender is PasswordBox box)
            viewModel.Password = box.Password;
    }
}
