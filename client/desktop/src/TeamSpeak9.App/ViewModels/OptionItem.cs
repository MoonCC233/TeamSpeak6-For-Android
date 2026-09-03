// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

namespace TeamSpeak9.App.ViewModels;

/// <summary>A single entry in a <c>ComboBox</c> that maps a value to a localised label.</summary>
/// <remarks>
/// Used instead of binding enums directly so the labels stay in the view model layer. XAML never
/// names the generic type: bind with <c>DisplayMemberPath="Label"</c> and
/// <c>SelectedValuePath="Value"</c>.
/// </remarks>
/// <typeparam name="T">The underlying value type, usually an enum.</typeparam>
public sealed record OptionItem<T>(T Value, string Label)
{
    public override string ToString() => Label;
}
