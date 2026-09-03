// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using System.Globalization;
using System.Windows;
using System.Windows.Data;

namespace TeamSpeak9.App.Converters;

/// <summary>Maps <see cref="bool"/> to <see cref="Visibility"/>.</summary>
/// <remarks>
/// WPF ships <c>BooleanToVisibilityConverter</c>, but it cannot invert and cannot use
/// <see cref="Visibility.Hidden"/>, both of which the shell needs.
/// </remarks>
public sealed class BoolToVisibilityConverter : IValueConverter
{
    /// <summary>When true, false maps to visible and true maps to <see cref="FalseVisibility"/>.</summary>
    public bool Invert { get; set; }

    /// <summary>Visibility used for the "off" state. Defaults to <see cref="Visibility.Collapsed"/>.</summary>
    public Visibility FalseVisibility { get; set; } = Visibility.Collapsed;

    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        var flag = value is bool b && b;
        if (Invert)
            flag = !flag;

        return flag ? Visibility.Visible : FalseVisibility;
    }

    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        var visible = value is Visibility v && v == Visibility.Visible;
        return Invert ? !visible : visible;
    }
}

/// <summary>Collapses the element when the bound value is <c>null</c> or an empty/whitespace string.</summary>
public sealed class EmptyToVisibilityConverter : IValueConverter
{
    public bool Invert { get; set; }

    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        var hasValue = value switch
        {
            null => false,
            string s => !string.IsNullOrWhiteSpace(s),
            _ => true,
        };

        if (Invert)
            hasValue = !hasValue;

        return hasValue ? Visibility.Visible : Visibility.Collapsed;
    }

    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture)
        => throw new NotSupportedException();
}

/// <summary>Negates a boolean.</summary>
public sealed class NotConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
        => value is not bool b || !b;

    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture)
        => value is not bool b || !b;
}

/// <summary>
/// Shows the element only when a bound count is zero, for "nothing here yet" placeholders.
/// </summary>
/// <remarks>
/// Set <see cref="Invert"/> to show it only when the count is non-zero. Binding to
/// <c>Collection.Count</c> works even though the property is not observable, because a collection
/// change notification already re-evaluates the binding.
/// </remarks>
public sealed class ZeroToVisibilityConverter : IValueConverter
{
    public bool Invert { get; set; }

    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        // Convert.ToInt32 rather than a cast: the bound property may be int, long or uint.
        long count = value switch
        {
            null => 0,
            IConvertible c => c.ToInt64(CultureInfo.InvariantCulture),
            _ => 0,
        };

        bool show = Invert ? count != 0 : count == 0;
        return show ? Visibility.Visible : Visibility.Collapsed;
    }

    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture)
        => throw new NotSupportedException();
}
