// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using System.Globalization;
using System.Windows.Data;

namespace TeamSpeak9.App.Converters;

/// <summary>
/// True when the bound value equals the converter parameter, compared by name.
/// </summary>
/// <remarks>
/// <para>
/// Used to drive tab-style <c>RadioButton</c>s from a single enum property. Comparison is by name
/// rather than by value so the parameter can stay a plain string in XAML; <c>{x:Static}</c> would
/// work too but needs the enum type imported into every view that uses it.
/// </para>
/// <para>
/// <see cref="ConvertBack"/> returns <see cref="Binding.DoNothing"/> for the unchecked case: a
/// radio group unchecks the old button before checking the new one, and writing back there would
/// clobber the value the new button is about to set.
/// </para>
/// </remarks>
public sealed class EnumMatchConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        if (value is null || parameter is null)
            return false;

        return string.Equals(value.ToString(), parameter.ToString(), StringComparison.Ordinal);
    }

    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        if (value is not bool checked_ || !checked_ || parameter is null)
            return Binding.DoNothing;

        Type target = targetType;
        if (Nullable.GetUnderlyingType(target) is { } underlying)
            target = underlying;

        if (!target.IsEnum)
            return Binding.DoNothing;

        return Enum.TryParse(target, parameter.ToString(), ignoreCase: false, out object? parsed)
            ? parsed!
            : Binding.DoNothing;
    }
}
