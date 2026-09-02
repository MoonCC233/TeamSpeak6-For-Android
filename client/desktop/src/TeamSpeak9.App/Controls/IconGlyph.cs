// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace TeamSpeak9.App.Controls;

/// <summary>
/// Renders one of the geometries from Themes/Icons.xaml at the requested size.
/// </summary>
/// <remarks>
/// A <see cref="Control"/> rather than a styled <see cref="System.Windows.Shapes.Path"/> because
/// <see cref="Control.Foreground"/> is an inherited property: an icon inside a button picks up
/// that button's foreground, including the hover and pressed states, without any binding at the
/// call site. <see cref="System.Windows.Shapes.Path.Stroke"/> does not inherit.
/// </remarks>
public class IconGlyph : Control
{
    public static readonly DependencyProperty DataProperty = DependencyProperty.Register(
        nameof(Data),
        typeof(Geometry),
        typeof(IconGlyph),
        new FrameworkPropertyMetadata(null, FrameworkPropertyMetadataOptions.AffectsRender));

    public static readonly DependencyProperty StrokeThicknessProperty = DependencyProperty.Register(
        nameof(StrokeThickness),
        typeof(double),
        typeof(IconGlyph),
        new FrameworkPropertyMetadata(1.6, FrameworkPropertyMetadataOptions.AffectsRender));

    public static readonly DependencyProperty IsFilledProperty = DependencyProperty.Register(
        nameof(IsFilled),
        typeof(bool),
        typeof(IconGlyph),
        new FrameworkPropertyMetadata(false, FrameworkPropertyMetadataOptions.AffectsRender));

    static IconGlyph()
    {
        DefaultStyleKeyProperty.OverrideMetadata(
            typeof(IconGlyph),
            new FrameworkPropertyMetadata(typeof(IconGlyph)));
    }

    /// <summary>The geometry to draw, e.g. <c>{StaticResource Icon.Mic}</c>.</summary>
    public Geometry? Data
    {
        get => (Geometry?)GetValue(DataProperty);
        set => SetValue(DataProperty, value);
    }

    /// <summary>
    /// Stroke width in the icon's own 24x24 coordinate space, before the uniform stretch.
    /// </summary>
    public double StrokeThickness
    {
        get => (double)GetValue(StrokeThicknessProperty);
        set => SetValue(StrokeThicknessProperty, value);
    }

    /// <summary>Fill the geometry instead of stroking it. Only for closed figures.</summary>
    public bool IsFilled
    {
        get => (bool)GetValue(IsFilledProperty);
        set => SetValue(IsFilledProperty, value);
    }
}
