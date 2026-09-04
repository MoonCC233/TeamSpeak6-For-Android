// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using System.Globalization;
using System.Windows;
using System.Windows.Data;
using System.Windows.Media;
using TeamSpeak9.App.Converters;
using TeamSpeak9.App.ViewModels;

namespace TeamSpeak9.App.Tests.Converters;

public class BoolToVisibilityConverterTests
{
    private static readonly CultureInfo Culture = CultureInfo.InvariantCulture;

    [Theory]
    [InlineData(true, Visibility.Visible)]
    [InlineData(false, Visibility.Collapsed)]
    public void ABooleanMapsToVisibility(bool value, Visibility expected)
    {
        var converter = new BoolToVisibilityConverter();
        Assert.Equal(expected, converter.Convert(value, typeof(Visibility), null, Culture));
    }

    [Fact]
    public void AnythingThatIsNotABooleanCountsAsFalse()
    {
        var converter = new BoolToVisibilityConverter();

        Assert.Equal(Visibility.Collapsed, converter.Convert(null, typeof(Visibility), null, Culture));
        Assert.Equal(Visibility.Collapsed, converter.Convert("true", typeof(Visibility), null, Culture));
    }

    [Fact]
    public void InvertSwapsBothDirections()
    {
        var converter = new BoolToVisibilityConverter { Invert = true };

        Assert.Equal(Visibility.Collapsed, converter.Convert(true, typeof(Visibility), null, Culture));
        Assert.Equal(Visibility.Visible, converter.Convert(false, typeof(Visibility), null, Culture));
        Assert.False(Assert.IsType<bool>(converter.ConvertBack(Visibility.Visible, typeof(bool), null, Culture)));
        Assert.True(Assert.IsType<bool>(converter.ConvertBack(Visibility.Collapsed, typeof(bool), null, Culture)));
    }

    [Fact]
    public void TheOffStateVisibilityIsConfigurable()
    {
        var converter = new BoolToVisibilityConverter { FalseVisibility = Visibility.Hidden };

        Assert.Equal(Visibility.Hidden, converter.Convert(false, typeof(Visibility), null, Culture));
        Assert.Equal(Visibility.Visible, converter.Convert(true, typeof(Visibility), null, Culture));
    }

    [Theory]
    [InlineData(Visibility.Visible, true)]
    [InlineData(Visibility.Collapsed, false)]
    [InlineData(Visibility.Hidden, false)]
    public void OnlyVisibleConvertsBackToTrue(Visibility value, bool expected)
    {
        var converter = new BoolToVisibilityConverter();
        Assert.Equal(expected, Assert.IsType<bool>(converter.ConvertBack(value, typeof(bool), null, Culture)));
    }
}

public class EmptyToVisibilityConverterTests
{
    private static readonly CultureInfo Culture = CultureInfo.InvariantCulture;

    [Fact]
    public void BlankStringsAndNullsCollapse()
    {
        var converter = new EmptyToVisibilityConverter();

        Assert.Equal(Visibility.Collapsed, converter.Convert(null, typeof(Visibility), null, Culture));
        Assert.Equal(Visibility.Collapsed, converter.Convert(string.Empty, typeof(Visibility), null, Culture));
        Assert.Equal(Visibility.Collapsed, converter.Convert("   ", typeof(Visibility), null, Culture));
    }

    [Fact]
    public void AnyOtherValueIsVisible()
    {
        var converter = new EmptyToVisibilityConverter();

        Assert.Equal(Visibility.Visible, converter.Convert("你好", typeof(Visibility), null, Culture));
        Assert.Equal(Visibility.Visible, converter.Convert(0, typeof(Visibility), null, Culture));
    }

    [Fact]
    public void InvertShowsThePlaceholderInstead()
    {
        var converter = new EmptyToVisibilityConverter { Invert = true };

        Assert.Equal(Visibility.Visible, converter.Convert(null, typeof(Visibility), null, Culture));
        Assert.Equal(Visibility.Collapsed, converter.Convert("你好", typeof(Visibility), null, Culture));
    }

    [Fact]
    public void ItIsAOneWayConverter()
    {
        var converter = new EmptyToVisibilityConverter();

        Assert.Throws<NotSupportedException>(
            () => converter.ConvertBack(Visibility.Visible, typeof(string), null, Culture));
    }
}

public class NotConverterTests
{
    private static readonly CultureInfo Culture = CultureInfo.InvariantCulture;

    [Theory]
    [InlineData(true, false)]
    [InlineData(false, true)]
    public void ABooleanIsNegated(bool value, bool expected)
    {
        var converter = new NotConverter();

        Assert.Equal(expected, Assert.IsType<bool>(converter.Convert(value, typeof(bool), null, Culture)));
        Assert.Equal(expected, Assert.IsType<bool>(converter.ConvertBack(value, typeof(bool), null, Culture)));
    }

    [Fact]
    public void AnUnsetBindingReadsAsTrue()
    {
        // A binding that has not produced a value yet must not disable a control that guards on
        // "not busy", so anything other than true has to fall on the true side.
        var converter = new NotConverter();

        Assert.True(Assert.IsType<bool>(converter.Convert(null, typeof(bool), null, Culture)));
        Assert.True(Assert.IsType<bool>(
            converter.Convert(DependencyProperty.UnsetValue, typeof(bool), null, Culture)));
    }
}

public class ZeroToVisibilityConverterTests
{
    private static readonly CultureInfo Culture = CultureInfo.InvariantCulture;

    [Fact]
    public void AnEmptyCollectionShowsThePlaceholder()
    {
        var converter = new ZeroToVisibilityConverter();

        Assert.Equal(Visibility.Visible, converter.Convert(0, typeof(Visibility), null, Culture));
        Assert.Equal(Visibility.Collapsed, converter.Convert(1, typeof(Visibility), null, Culture));
    }

    [Fact]
    public void EveryIntegerWidthTheShellBindsToIsAccepted()
    {
        var converter = new ZeroToVisibilityConverter();

        Assert.Equal(Visibility.Collapsed, converter.Convert(3L, typeof(Visibility), null, Culture));
        Assert.Equal(Visibility.Collapsed, converter.Convert(3u, typeof(Visibility), null, Culture));
        Assert.Equal(Visibility.Collapsed, converter.Convert((ushort)3, typeof(Visibility), null, Culture));
    }

    [Fact]
    public void AValueThatCarriesNoCountReadsAsZero()
    {
        var converter = new ZeroToVisibilityConverter();

        Assert.Equal(Visibility.Visible, converter.Convert(null, typeof(Visibility), null, Culture));
        Assert.Equal(Visibility.Visible, converter.Convert(new object(), typeof(Visibility), null, Culture));
    }

    [Fact]
    public void InvertShowsTheContentInstead()
    {
        var converter = new ZeroToVisibilityConverter { Invert = true };

        Assert.Equal(Visibility.Collapsed, converter.Convert(0, typeof(Visibility), null, Culture));
        Assert.Equal(Visibility.Visible, converter.Convert(2, typeof(Visibility), null, Culture));
    }

    [Fact]
    public void ItIsAOneWayConverter()
    {
        var converter = new ZeroToVisibilityConverter();

        Assert.Throws<NotSupportedException>(
            () => converter.ConvertBack(Visibility.Visible, typeof(int), null, Culture));
    }
}

public class EnumMatchConverterTests
{
    private static readonly CultureInfo Culture = CultureInfo.InvariantCulture;

    [Theory]
    [InlineData(ServerEditorPage.General, "General", true)]
    [InlineData(ServerEditorPage.General, "Security", false)]
    [InlineData(ServerEditorPage.Antiflood, "Antiflood", true)]
    public void TheActiveTabMatchesByName(ServerEditorPage page, string parameter, bool expected)
    {
        var converter = new EnumMatchConverter();
        Assert.Equal(expected, Assert.IsType<bool>(converter.Convert(page, typeof(bool), parameter, Culture)));
    }

    [Fact]
    public void TheComparisonIsCaseSensitive()
    {
        // Ordinal on purpose: a typo in a XAML parameter should leave the tab dead rather than
        // half-work.
        var converter = new EnumMatchConverter();

        Assert.False(Assert.IsType<bool>(
            converter.Convert(ServerEditorPage.General, typeof(bool), "general", Culture)));
    }

    [Fact]
    public void AMissingValueOrParameterNeverMatches()
    {
        var converter = new EnumMatchConverter();

        Assert.False(Assert.IsType<bool>(converter.Convert(null, typeof(bool), "General", Culture)));
        Assert.False(Assert.IsType<bool>(
            converter.Convert(ServerEditorPage.General, typeof(bool), null, Culture)));
    }

    [Fact]
    public void CheckingARadioButtonWritesTheEnumBack()
    {
        var converter = new EnumMatchConverter();

        Assert.Equal(
            ServerEditorPage.Banner,
            converter.ConvertBack(true, typeof(ServerEditorPage), "Banner", Culture));
    }

    [Fact]
    public void ANullableTargetIsUnwrapped()
    {
        var converter = new EnumMatchConverter();

        Assert.Equal(
            ServerEditorPage.Banner,
            converter.ConvertBack(true, typeof(ServerEditorPage?), "Banner", Culture));
    }

    [Theory]
    [InlineData(false, typeof(ServerEditorPage), "Banner")]
    [InlineData(null, typeof(ServerEditorPage), "Banner")]
    [InlineData(true, typeof(ServerEditorPage), null)]
    [InlineData(true, typeof(int), "Banner")]
    [InlineData(true, typeof(ServerEditorPage), "NoSuchPage")]
    [InlineData(true, typeof(ServerEditorPage), "banner")]
    public void EverythingElseLeavesTheSourceAlone(object? value, Type targetType, string? parameter)
    {
        // Unchecking in particular must not write back: a radio group clears the old button before
        // setting the new one, and writing there would clobber the value about to arrive.
        var converter = new EnumMatchConverter();

        Assert.Same(Binding.DoNothing, converter.ConvertBack(value, targetType, parameter, Culture));
    }
}

public class NickColorConverterTests
{
    private static readonly CultureInfo Culture = CultureInfo.InvariantCulture;

    [Theory]
    [InlineData("")]
    [InlineData("serveradmin")]
    [InlineData("0KAWtL7XmPtvBAoIcgVSZ2/8/wE=")]
    [InlineData("阿花")]
    public void EveryIdentityLandsInThePalette(string key)
    {
        Assert.InRange(NickColorConverter.IndexOf(key), 0, NickColorConverter.PaletteSize - 1);
    }

    [Fact]
    public void TheSameIdentityAlwaysGetsTheSameSlot()
    {
        // FNV-1a rather than string.GetHashCode, which .NET randomises per process: a person has to
        // keep their colour across launches, so these indices are part of the contract.
        Assert.Equal(1, NickColorConverter.IndexOf("teamspeak9"));
        Assert.Equal(7, NickColorConverter.IndexOf("0KAWtL7XmPtvBAoIcgVSZ2/8/wE="));
        Assert.Equal(5, NickColorConverter.IndexOf(string.Empty));
    }

    [Fact]
    public void DifferentIdentitiesSpreadAcrossTheWholePalette()
    {
        var used = new HashSet<int>();
        for (var i = 0; i < 200; i++)
            used.Add(NickColorConverter.IndexOf("client-" + i.ToString(Culture)));

        Assert.Equal(NickColorConverter.PaletteSize, used.Count);
    }

    [Fact]
    public void WithoutTheThemeLoadedEveryNameFallsBackToOneBrush()
    {
        // No Application, so TryFindResource cannot see Brush.Nick0..7. Callers have to get a
        // readable default rather than a null brush, which would paint the text invisible.
        var converter = new NickColorConverter();
        var blank = converter.Convert(null, typeof(Brush), null, Culture);
        var named = converter.Convert("阿花", typeof(Brush), null, Culture);

        Assert.IsType<SolidColorBrush>(blank);
        Assert.Same(blank, named);
    }

    [Fact]
    public void ItIsAOneWayConverter()
    {
        var converter = new NickColorConverter();

        Assert.Throws<NotSupportedException>(
            () => converter.ConvertBack(Brushes.Red, typeof(string), null, Culture));
    }
}
