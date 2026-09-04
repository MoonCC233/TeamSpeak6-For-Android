// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using TeamSpeak9.App.ViewModels;

namespace TeamSpeak9.App.Tests.ViewModels;

public class ApplyBbCodeTests
{
    [Fact]
    public void AnEmptySelectionInsertsAnEmptyPairAndPutsTheCaretInside()
    {
        var result = ShellViewModel.ApplyBbCode("你好", 2, 0, "b", out var caret);

        Assert.Equal("你好[b][/b]", result);
        Assert.Equal(5, caret);
        Assert.Equal("[/b]", result[caret..]);
    }

    [Fact]
    public void ASelectionIsWrappedAndTheCaretLandsAfterTheClosingTag()
    {
        var result = ShellViewModel.ApplyBbCode("你好世界", 1, 2, "i", out var caret);

        Assert.Equal("你[i]好世[/i]界", result);
        Assert.Equal(10, caret);
        Assert.Equal("界", result[caret..]);
    }

    [Fact]
    public void AnEmptyComposerStillProducesATagPair()
    {
        var result = ShellViewModel.ApplyBbCode(string.Empty, 0, 0, "spoiler", out var caret);

        Assert.Equal("[spoiler][/spoiler]", result);
        Assert.Equal(9, caret);
    }

    [Fact]
    public void TheWholeTextCanBeWrapped()
    {
        var result = ShellViewModel.ApplyBbCode("code", 0, 4, "code", out var caret);

        Assert.Equal("[code]code[/code]", result);
        Assert.Equal(result.Length, caret);
    }

    [Theory]
    [InlineData(-5, 0)]
    [InlineData(99, 0)]
    public void AnOutOfRangeCaretIsClamped(int selectionStart, int selectionLength)
    {
        // The composer reports its own selection, and a stale value can arrive after the text has
        // already been replaced; clamping keeps that from throwing in a click handler.
        var result = ShellViewModel.ApplyBbCode("abc", selectionStart, selectionLength, "b", out var caret);

        Assert.Contains("[b][/b]", result, StringComparison.Ordinal);
        Assert.InRange(caret, 0, result.Length);
    }

    [Fact]
    public void ASelectionRunningPastTheEndIsTruncated()
    {
        var result = ShellViewModel.ApplyBbCode("abc", 1, 99, "b", out var caret);

        Assert.Equal("a[b]bc[/b]", result);
        Assert.Equal(result.Length, caret);
    }

    [Fact]
    public void ANegativeSelectionLengthCollapsesToAnInsert()
    {
        var result = ShellViewModel.ApplyBbCode("abc", 1, -4, "b", out var caret);

        Assert.Equal("a[b][/b]bc", result);
        Assert.Equal(4, caret);
    }

    [Fact]
    public void NoTextIsRejected()
    {
        Assert.Throws<ArgumentNullException>(
            () => ShellViewModel.ApplyBbCode(null!, 0, 0, "b", out _));
    }

    [Fact]
    public void NoTagIsRejected()
    {
        Assert.Throws<ArgumentNullException>(
            () => ShellViewModel.ApplyBbCode("abc", 0, 0, null!, out _));
    }

    [Theory]
    [InlineData("")]
    [InlineData("   ")]
    public void ABlankTagIsRejected(string tag)
    {
        Assert.Throws<ArgumentException>(
            () => ShellViewModel.ApplyBbCode("abc", 0, 0, tag, out _));
    }

    [Theory]
    [InlineData("b")]
    [InlineData("i")]
    [InlineData("u")]
    [InlineData("s")]
    [InlineData("url")]
    [InlineData("quote")]
    [InlineData("list")]
    [InlineData("code")]
    [InlineData("spoiler")]
    [InlineData("h1")]
    public void EveryTagTheToolbarSendsIsHandled(string tag)
    {
        var result = ShellViewModel.ApplyBbCode("文本", 0, 2, tag, out var caret);

        Assert.Equal($"[{tag}]文本[/{tag}]", result);
        Assert.Equal(result.Length, caret);
    }

    [Fact]
    public void WrappingTwiceNests()
    {
        var once = ShellViewModel.ApplyBbCode("hi", 0, 2, "b", out _);
        var twice = ShellViewModel.ApplyBbCode(once, 0, once.Length, "i", out var caret);

        Assert.Equal("[i][b]hi[/b][/i]", twice);
        Assert.Equal(twice.Length, caret);
    }
}
