// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using TeamSpeak9.Core.Model;

namespace TeamSpeak9.Core.Tests.Model;

public class ChannelOrderingTests
{
    /// <summary>A sibling as the book stores it: its own id plus its predecessor's id.</summary>
    private sealed record Sib(ulong Id, ulong Order);

    private static IReadOnlyList<Sib> Sort(params Sib[] input) =>
        ChannelOrdering.SortSiblings(input, s => s.Id, s => s.Order);

    private static ulong[] Ids(IReadOnlyList<Sib> sorted) => sorted.Select(s => s.Id).ToArray();

    [Fact]
    public void EmptyAndSingleAreReturnedAsIs()
    {
        Assert.Empty(Sort());
        Assert.Equal([7ul], Ids(Sort(new Sib(7, 0))));
    }

    [Fact]
    public void FollowsTheChainRatherThanTheNumericValue()
    {
        // 3 -> 1 -> 2: the order values are ids, so sorting by them numerically would give 1,2,3.
        var sorted = Sort(
            new Sib(Id: 1, Order: 3),
            new Sib(Id: 2, Order: 1),
            new Sib(Id: 3, Order: 0));

        Assert.Equal([3ul, 1ul, 2ul], Ids(sorted));
    }

    [Fact]
    public void InputOrderDoesNotAffectTheResult()
    {
        var forward = Sort(
            new Sib(10, 0),
            new Sib(20, 10),
            new Sib(30, 20));

        var shuffled = Sort(
            new Sib(30, 20),
            new Sib(10, 0),
            new Sib(20, 10));

        Assert.Equal(Ids(forward), Ids(shuffled));
        Assert.Equal([10ul, 20ul, 30ul], Ids(forward));
    }

    [Fact]
    public void ChannelsBehindAMissingPredecessorAreKept()
    {
        // 40 points at 99, which is not in this sibling group (unsubscribed or filtered out).
        // It must still show up rather than vanish from the tree.
        var sorted = Sort(
            new Sib(10, 0),
            new Sib(20, 10),
            new Sib(40, 99));

        Assert.Equal(3, sorted.Count);
        Assert.Equal([10ul, 20ul], Ids(sorted).Take(2));
        Assert.Contains(sorted, s => s.Id == 40);
    }

    [Fact]
    public void MissingHeadStillReturnsEveryChannel()
    {
        // Nothing points at 0, so the walk cannot even start.
        var sorted = Sort(
            new Sib(20, 10),
            new Sib(30, 20));

        Assert.Equal([20ul, 30ul], Ids(sorted));
    }

    [Fact]
    public void CycleDoesNotHangAndLosesNothing()
    {
        // 10 -> 20 -> 10 is a closed loop with no head.
        var sorted = Sort(
            new Sib(10, 20),
            new Sib(20, 10),
            new Sib(30, 0));

        Assert.Equal(3, sorted.Count);
        Assert.Equal(30ul, sorted[0].Id);
        Assert.Contains(sorted, s => s.Id == 10);
        Assert.Contains(sorted, s => s.Id == 20);
    }

    [Fact]
    public void SelfReferenceIsTolerated()
    {
        var sorted = Sort(
            new Sib(10, 0),
            new Sib(20, 20));

        Assert.Equal(2, sorted.Count);
        Assert.Equal(10ul, sorted[0].Id);
        Assert.Equal(20ul, sorted[1].Id);
    }

    [Fact]
    public void DuplicateOrderKeepsBothChannels()
    {
        // Two channels claiming the same slot. Neither may be dropped.
        var sorted = Sort(
            new Sib(10, 0),
            new Sib(20, 0),
            new Sib(30, 10));

        Assert.Equal(3, sorted.Count);
        Assert.Equal(3, sorted.Select(s => s.Id).Distinct().Count());
    }

    [Fact]
    public void MembersSortByTalkPowerThenName()
    {
        var members = new[]
        {
            Member(1, "zoe", talkPower: 10),
            Member(2, "Alice", talkPower: 10),
            Member(3, "moderator", talkPower: 75),
        };

        var sorted = ChannelOrdering.SortMembers(members);

        Assert.Equal([3ul, 2ul, 1ul], sorted.Select(m => (ulong)m.ClientId));
    }

    [Fact]
    public void MemberSortIsCaseInsensitive()
    {
        var members = new[]
        {
            Member(1, "bob"),
            Member(2, "Anna"),
        };

        var sorted = ChannelOrdering.SortMembers(members);

        Assert.Equal("Anna", sorted[0].Name);
    }

    [Fact]
    public void EqualNamesFallBackToClientId()
    {
        var members = new[]
        {
            Member(9, "同名"),
            Member(4, "同名"),
        };

        var sorted = ChannelOrdering.SortMembers(members);

        Assert.Equal([4, 9], sorted.Select(m => m.ClientId));
    }

    private static ChannelMember Member(ushort id, string name, int talkPower = 0) => new()
    {
        ClientId = id,
        ChannelId = 1,
        Name = name,
        TalkPower = talkPower,
    };
}
