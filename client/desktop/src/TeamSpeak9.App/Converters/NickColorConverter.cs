// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using System.Globalization;
using System.Windows;
using System.Windows.Data;
using System.Windows.Media;

namespace TeamSpeak9.App.Converters;

/// <summary>
/// Tints a chat author by hashing their identity into the <c>Brush.Nick0..7</c> palette.
/// </summary>
/// <remarks>
/// Bind the client uid when available - it is stable across reconnects, so a person keeps the
/// same colour for the whole session and across servers. Falls back to the nickname when the
/// uid is unknown (e.g. server messages).
/// </remarks>
public sealed class NickColorConverter : IValueConverter
{
    /// <summary>Number of entries in the nickname palette.</summary>
    public const int PaletteSize = 8;

    private static readonly SolidColorBrush Fallback = new(Color.FromRgb(0xE6, 0xE9, 0xEF));

    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        var key = value as string;
        if (string.IsNullOrEmpty(key))
            return Fallback;

        var index = IndexOf(key);
        return Application.Current?.TryFindResource($"Brush.Nick{index}") as Brush ?? Fallback;
    }

    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture)
        => throw new NotSupportedException();

    /// <summary>Stable palette index for an identity string.</summary>
    /// <remarks>
    /// FNV-1a rather than <see cref="string.GetHashCode()"/>: .NET randomizes string hash codes
    /// per process, which would give a person a different colour on every launch.
    /// </remarks>
    public static int IndexOf(string key)
    {
        const uint offsetBasis = 2166136261u;
        const uint prime = 16777619u;

        var hash = offsetBasis;
        foreach (var c in key)
        {
            hash ^= c;
            hash *= prime;
        }

        return (int)(hash % PaletteSize);
    }
}
