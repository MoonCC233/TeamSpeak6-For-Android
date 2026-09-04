// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using System.Windows;
using System.Windows.Input;
using Microsoft.Win32;
using TeamSpeak9.App.Controls;
using TeamSpeak9.App.ViewModels;

namespace TeamSpeak9.App.Views;

/// <summary>Browses, uploads and assigns server icons.</summary>
public partial class IconBrowserWindow : ShellWindow
{
    private readonly IconBrowserViewModel viewModel;

    internal IconBrowserWindow(IconBrowserViewModel viewModel)
    {
        ArgumentNullException.ThrowIfNull(viewModel);
        this.viewModel = viewModel;

        InitializeComponent();
        DataContext = viewModel;

        // File and confirmation dialogs are view concerns, so the view model asks for them instead of
        // referencing WPF itself. Keeps it testable.
        viewModel.PickFile = PickIconFile;
        viewModel.ConfirmDelete = ConfirmDelete;
        viewModel.Applied += OnApplied;
        Closed += (_, _) => viewModel.Applied -= OnApplied;
    }

    private void OnApplied(object? sender, EventArgs e)
    {
        DialogResult = true;
    }

    private string? PickIconFile()
    {
        var dialog = new OpenFileDialog
        {
            Title = "选择图标文件",
            Filter = "图片 (*.png;*.jpg;*.jpeg;*.gif;*.bmp)|*.png;*.jpg;*.jpeg;*.gif;*.bmp|所有文件 (*.*)|*.*",
            CheckFileExists = true,
            Multiselect = false,
        };

        return dialog.ShowDialog(this) == true ? dialog.FileName : null;
    }

    private bool ConfirmDelete(string message) => MessageBox.Show(
        this,
        message,
        "删除图标",
        MessageBoxButton.OKCancel,
        MessageBoxImage.Warning,
        MessageBoxResult.Cancel) == MessageBoxResult.OK;

    /// <remarks>
    /// Double-clicking anywhere in the list box applies the selection, matching how the official
    /// client's icon picker behaves. The handler checks for a selection because the empty area of the
    /// panel raises the event too.
    /// </remarks>
    private void OnGridDoubleClick(object sender, MouseButtonEventArgs e)
    {
        if (viewModel.ApplyCommand.CanExecute(null))
            viewModel.ApplyCommand.Execute(null);
    }
}
