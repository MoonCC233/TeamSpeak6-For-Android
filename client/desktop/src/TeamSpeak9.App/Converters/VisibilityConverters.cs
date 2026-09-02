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
