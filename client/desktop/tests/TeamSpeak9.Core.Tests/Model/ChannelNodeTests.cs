// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using TeamSpeak9.Core.Model;

namespace TeamSpeak9.Core.Tests.Model;

public class SpacerInfoTests
{
    [Theory]
    [InlineData("[spacer1]---", SpacerAlignment.Left, "---")]
    [InlineData("[cspacer0]中间", SpacerAlignment.Center, "中间")]
    [InlineData("[rspacer2]right", SpacerAlignment.Right, "right")]
    [InlineData("[*spacer]-", SpacerAlignment.Repeat, "-")]
    [InlineData("[spacer]", SpacerAlignment.Left, "")]
    public void ParsesEveryAlignment(string name, SpacerAlignment alignment, string caption)
    {
        var spacer = SpacerInfo.TryParse(name);

        Assert.NotNull(spacer);
        Assert.Equal(alignment, spacer.Alignment);
        Assert.Equal(caption, spacer.Caption);
    }

    [Fact]
    public void UniquifierIsNotPartOfTheCaption()
    {
        // The digits only exist to keep channel names unique and must not be rendered.
        Assert.Equal("Zone A", SpacerInfo.TryParse("[cspacer17]Zone A")!.Caption);
    }

    [Theory]
    [InlineData("普通频道")]
    [InlineData("")]
    [InlineData(null)]
    [InlineData("[spacer1")]          // no closing bracket
    [InlineData("[]abc")]             // empty tag
    [InlineData("[notaspacer]x")]     // tag is not a spacer
    [InlineData("[cnotspacer]x")]     // alignment prefix but wrong keyword
    [InlineData("Lobby [spacer1]")]   // bracket not at the start
    public void RejectsEverythingElse(string? name)
    {
        Assert.Null(SpacerInfo.TryParse(name));
    }
}

public class ChannelLimitTests
{
    [Fact]
    public void UnlimitedAndInheritedCarryNoCount()
    {
        Assert.False(ChannelLimit.Unlimited.IsLimited);
        Assert.False(ChannelLimit.Inherited.IsLimited);
        Assert.Equal(0, ChannelLimit.Unlimited.Count);
    }

    [Fact]
    public void LimitedKeepsItsCount()
    {
        var limit = ChannelLimit.Of(32);

        Assert.True(limit.IsLimited);
        Assert.Equal(32, limit.Count);
        Assert.Equal("32", limit.ToString());
    }

    [Fact]
    public void KindIsPartOfEquality()
    {
        // A limit of 0 is not the same thing as no limit.
        Assert.NotEqual(ChannelLimit.Unlimited, ChannelLimit.Of(0));
        Assert.NotEqual(ChannelLimit.Unlimited, ChannelLimit.Inherited);
    }
}

public class ChannelNodeTests
{
    [Fact]
    public void FlattenIsDepthFirst()
    {
        var tree = Channel(1, children: [
            Channel(2, children: [Channel(3)]),
            Channel(4),
        ]);

        Assert.Equal([1ul, 2ul, 3ul, 4ul], tree.Flatten().Select(c => c.ChannelId));
    }

    [Fact]
    public void FindReachesDescendants()
    {
        var tree = Channel(1, children: [Channel(2, children: [Channel(3)])]);

        Assert.Equal(3ul, tree.Find(3)!.ChannelId);
        Assert.Null(tree.Find(99));
    }

    [Fact]
    public void TotalMemberCountAddsUpTheSubtree()
    {
        var tree = Channel(1, members: 2, children: [
            Channel(2, members: 3),
            Channel(3, members: 0, children: [Channel(4, members: 1)]),
        ]);

        Assert.Equal(2, tree.MemberCount);
        Assert.Equal(6, tree.TotalMemberCount);
    }

    [Fact]
    public void SpacerOnlyCountsAtRootLevel()
    {
        var root = Channel(1) with { Name = "[spacer1]---", Spacer = SpacerInfo.TryParse("[spacer1]---") };
        var nested = root with { ParentId = 5 };

        Assert.True(root.IsSpacer);
        Assert.False(nested.IsSpacer);
    }

    [Fact]
    public void IsFullNeedsAnExplicitLimit()
    {
        var unlimited = Channel(1, members: 50);
        var full = Channel(1, members: 2) with { MaxClients = ChannelLimit.Of(2) };
        var free = Channel(1, members: 1) with { MaxClients = ChannelLimit.Of(2) };

        Assert.False(unlimited.IsFull);
        Assert.True(full.IsFull);
        Assert.False(free.IsFull);
    }

    private static ChannelNode Channel(
        ulong id,
        int members = 0,
        ChannelNode[]? children = null) => new()
        {
            ChannelId = id,
            Name = $"频道 {id}",
            Children = [.. children ?? []],
            Members = [.. Enumerable.Range(0, members).Select(i => new ChannelMember
            {
                ClientId = (ushort)(id * 100 + (ulong)i),
                ChannelId = id,
                Name = $"用户 {i}",
            })],
        };
}
