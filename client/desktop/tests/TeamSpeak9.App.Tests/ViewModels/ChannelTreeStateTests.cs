// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using TeamSpeak9.App.ViewModels;

namespace TeamSpeak9.App.Tests.ViewModels;

public class ChannelTreeStateTests
{
    [Fact]
    public void AFreshTreeIsFullyExpandedWithNothingSelected()
    {
        // The TreeViewItem style defaults to expanded, so an empty set has to mean "everything
        // open" rather than "everything closed".
        var state = new ChannelTreeState();

        Assert.False(state.IsCollapsed(1));
        Assert.Equal(0ul, state.SelectedChannelId);
    }

    [Fact]
    public void CollapsingAndExpandingRoundTrips()
    {
        var state = new ChannelTreeState();

        state.SetCollapsed(3, true);
        Assert.True(state.IsCollapsed(3));

        state.SetCollapsed(3, false);
        Assert.False(state.IsCollapsed(3));
    }

    [Fact]
    public void SettingTheSameStateTwiceIsHarmless()
    {
        var state = new ChannelTreeState();

        state.SetCollapsed(3, true);
        state.SetCollapsed(3, true);
        Assert.True(state.IsCollapsed(3));

        state.SetCollapsed(3, false);
        state.SetCollapsed(3, false);
        Assert.False(state.IsCollapsed(3));
    }

    [Fact]
    public void ChannelsAreTrackedIndependently()
    {
        var state = new ChannelTreeState();

        state.SetCollapsed(3, true);

        Assert.True(state.IsCollapsed(3));
        Assert.False(state.IsCollapsed(4));
    }

    [Fact]
    public void SelectingAChannelHighlightsOnlyThatRow()
    {
        var state = new ChannelTreeState();

        state.Select(5);

        Assert.Equal(5ul, state.SelectedChannelId);
        Assert.True(state.IsSelected(5));
        Assert.False(state.IsSelected(6));
    }

    [Fact]
    public void SelectingAnotherChannelMovesTheHighlight()
    {
        var state = new ChannelTreeState();

        state.Select(5);
        state.Select(6);

        Assert.False(state.IsSelected(5));
        Assert.True(state.IsSelected(6));
    }

    [Fact]
    public void ChannelZeroIsNeverHighlighted()
    {
        // 0 is the "follow the channel we are in" sentinel, and it is also the id of the tree root,
        // so it must not light up when nothing is selected.
        var state = new ChannelTreeState();

        Assert.False(state.IsSelected(0));

        state.Select(0);
        Assert.False(state.IsSelected(0));
    }

    [Fact]
    public void DeselectOnlyClearsTheChannelItIsGiven()
    {
        // A TreeView deselects the old container before selecting the new one; clearing
        // unconditionally would race with the incoming selection and leave nothing highlighted.
        var state = new ChannelTreeState();

        state.Select(5);
        state.Deselect(4);
        Assert.True(state.IsSelected(5));

        state.Deselect(5);
        Assert.Equal(0ul, state.SelectedChannelId);
    }

    [Fact]
    public void ResetDropsExpansionAndSelectionTogether()
    {
        var state = new ChannelTreeState();

        state.SetCollapsed(3, true);
        state.Select(5);

        state.Reset();

        Assert.False(state.IsCollapsed(3));
        Assert.Equal(0ul, state.SelectedChannelId);
    }

    [Fact]
    public void ChannelIdsAreFullWidth()
    {
        // Channel ids are ulong on the wire; a 32-bit key would collide on a long-lived server.
        var state = new ChannelTreeState();

        state.SetCollapsed(ulong.MaxValue, true);
        state.Select(ulong.MaxValue);

        Assert.True(state.IsCollapsed(ulong.MaxValue));
        Assert.True(state.IsSelected(ulong.MaxValue));
        Assert.False(state.IsCollapsed(uint.MaxValue));
    }
}
