// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using System.Collections.Specialized;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;
using TeamSpeak9.App.ViewModels;

namespace TeamSpeak9.App.Views;

/// <summary>
/// Right column: message list plus the composer.
/// </summary>
public partial class ChatPanelView : UserControl
{
    private INotifyCollectionChanged? subscribed;
    private bool scrollPending;

    public ChatPanelView()
    {
        InitializeComponent();
        DataContextChanged += OnDataContextChanged;
    }

    private void OnDataContextChanged(object sender, DependencyPropertyChangedEventArgs e)
    {
        if (subscribed is not null)
        {
            subscribed.CollectionChanged -= OnMessagesChanged;
            subscribed = null;
        }

        if (e.NewValue is ShellViewModel vm)
        {
            subscribed = vm.Messages;
            subscribed.CollectionChanged += OnMessagesChanged;
        }
    }

    /// <summary>
    /// Keeps the newest message in view.
    /// </summary>
    /// <remarks>
    /// Only auto-scrolls when the list is already at the bottom, so reading back through history is
    /// not interrupted by incoming messages. The decision is made here, while the extent still
    /// describes the list without the new row, but the scroll itself is deferred: see
    /// <see cref="ScrollToNewest"/>.
    /// </remarks>
    private void OnMessagesChanged(object? sender, NotifyCollectionChangedEventArgs e)
    {
        if (e.Action != NotifyCollectionChangedAction.Add)
            return;

        var scroller = FindScrollViewer(MessageList);
        if (scroller is not null)
        {
            // ExtentHeight - ViewportHeight is the maximum offset; a couple of pixels of slack
            // covers the partially visible last row.
            double bottom = scroller.ExtentHeight - scroller.ViewportHeight;
            if (bottom - scroller.VerticalOffset > 24)
                return;
        }

        // Bursts of messages otherwise queue one scroll each; only the last one would matter.
        if (scrollPending)
            return;

        scrollPending = true;
        Dispatcher.BeginInvoke(ScrollToNewest, DispatcherPriority.Background);
    }

    /// <remarks>
    /// <see cref="ItemsControl.ScrollIntoView"/> forces a measure pass on the virtualizing panel.
    /// Calling it straight out of the collection event can beat the ListBox's own handler to it, and
    /// the generator then verifies itself against a count that has not caught up yet, which throws
    /// "ItemsControl is inconsistent with its items source". Running at
    /// <see cref="DispatcherPriority.Background"/> puts the scroll after the generator and layout
    /// have settled.
    /// </remarks>
    private void ScrollToNewest()
    {
        scrollPending = false;

        if (MessageList.Items.Count > 0)
            MessageList.ScrollIntoView(MessageList.Items[^1]);
    }

    private void OnComposerKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key != Key.Enter && e.Key != Key.Return)
            return;

        // Shift+Enter is a newline, which AcceptsReturn already handles.
        if ((Keyboard.Modifiers & ModifierKeys.Shift) == ModifierKeys.Shift)
            return;

        // An active IME composition sends Key.ImeProcessed instead, so this cannot swallow the
        // Enter that commits a candidate.
        if (DataContext is not ShellViewModel vm)
            return;

        e.Handled = true;

        if (vm.SendMessageCommand.CanExecute(null))
            vm.SendMessageCommand.Execute(null);
    }

    private void OnFormatClick(object sender, RoutedEventArgs e)
    {
        if (sender is not FrameworkElement { Tag: string tag } || tag.Length == 0)
            return;

        string updated = ShellViewModel.ApplyMarkdown(
            Composer.Text,
            Composer.SelectionStart,
            Composer.SelectionLength,
            tag,
            out int caret);

        Composer.Text = updated;
        Composer.CaretIndex = Math.Min(caret, updated.Length);
        Composer.Focus();
    }

    /// <summary>
    /// Opens a clicked link. Routed through the ViewModel so the http(s) check and the logging stay
    /// in one place.
    /// </summary>
    private void OnLinkClicked(object? sender, string url)
    {
        if (DataContext is ShellViewModel vm)
            vm.OpenUrl(url);
    }

    private static ScrollViewer? FindScrollViewer(DependencyObject root)
    {
        if (root is ScrollViewer viewer)
            return viewer;

        int count = VisualTreeHelper.GetChildrenCount(root);
        for (int i = 0; i < count; i++)
        {
            if (FindScrollViewer(VisualTreeHelper.GetChild(root, i)) is { } found)
                return found;
        }

        return null;
    }
}
