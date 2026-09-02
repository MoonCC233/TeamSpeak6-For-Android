// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using System.Collections.Immutable;
using TeamSpeak9.Core.Model;

namespace TeamSpeak9.Core.Tests.Model;

public class ServerSnapshotTests
{
    private static ServerSnapshot Sample() => new()
    {
        Name = "测试服务器",
        Nickname = "我",
        Address = "ts.example.com",
        OwnClientId = 5,
        OwnChannelId = 2,
        Channels =
        [
            Channel(1, members: [Member(1, "甲", 1)]),
            Channel(2, members: [Member(5, "我", 2), Member(6, "乙", 2)], children:
            [
                Channel(3, members: [Member(7, "丙", 3)]),
            ]),
        ],
        Groups = ImmutableDictionary<ulong, ServerGroupInfo>.Empty
            .Add(6, new ServerGroupInfo { GroupId = 6, Name = "Server Admin", Kind = GroupKind.Regular }),
    };

    [Fact]
    public void EmptyIsSafeToBindBeforeConnecting()
    {
        var empty = ServerSnapshot.Empty;

        Assert.Empty(empty.Name);
        Assert.Empty(empty.AllChannels());
        Assert.Equal(0, empty.ClientCount);
        Assert.Null(empty.OwnChannel);
        Assert.Null(empty.OwnClient);
        Assert.Null(empty.FindChannel(1));
        Assert.Null(empty.FindMember(1));
    }

    [Fact]
    public void AllChannelsWalksTheWholeTreeDepthFirst()
    {
        Assert.Equal([1ul, 2ul, 3ul], Sample().AllChannels().Select(c => c.ChannelId));
    }

    [Fact]
    public void FindChannelReachesNestedChannels()
    {
        var snapshot = Sample();

        Assert.Equal(3ul, snapshot.FindChannel(3)!.ChannelId);
        Assert.Null(snapshot.FindChannel(99));
    }

    [Fact]
    public void ChannelIdZeroIsNeverAMatch()
    {
        // 0 means "unknown" throughout the protocol, so it must not accidentally resolve.
        Assert.Null(Sample().FindChannel(0));
    }

    [Fact]
    public void OwnChannelAndOwnClientResolveFromTheIds()
    {
        var snapshot = Sample();

        Assert.Equal(2ul, snapshot.OwnChannel!.ChannelId);
        Assert.Equal("我", snapshot.OwnClient!.Name);
    }

    [Fact]
    public void FindMemberSearchesEveryChannel()
    {
        var snapshot = Sample();

        Assert.Equal("丙", snapshot.FindMember(7)!.Name);
        Assert.Null(snapshot.FindMember(200));
        Assert.Null(snapshot.FindMember(0));
    }

    [Fact]
    public void ClientCountAddsUpEveryChannel()
    {
        Assert.Equal(4, Sample().ClientCount);
    }

    [Fact]
    public void FindGroupUsesTheDictionary()
    {
        var snapshot = Sample();

        Assert.Equal("Server Admin", snapshot.FindGroup(6)!.Name);
        Assert.Null(snapshot.FindGroup(1));
    }

    [Fact]
    public void DisplayNameFallsBackToTheAddress()
    {
        var unnamed = Sample() with { Name = string.Empty };

        Assert.Equal("测试服务器", Sample().DisplayName);
        Assert.Equal("ts.example.com", unnamed.DisplayName);
    }

    private static ChannelNode Channel(
        ulong id,
        ChannelMember[]? members = null,
        ChannelNode[]? children = null) => new()
        {
            ChannelId = id,
            Name = $"频道 {id}",
            Subscribed = true,
            Members = [.. members ?? []],
            Children = [.. children ?? []],
        };

    private static ChannelMember Member(ushort clientId, string name, ulong channelId) => new()
    {
        ClientId = clientId,
        ChannelId = channelId,
        Name = name,
    };
}

public class ServerGroupInfoTests
{
    [Fact]
    public void TemplateAndQueryGroupsCannotCarryACustomIcon()
    {
        // Types 0 and 2 only accept icon ids 0..999 (error 2560 otherwise), which rules out any
        // uploaded icon since those are CRC32 values.
        Assert.False(Group(GroupKind.Template).AllowsCustomIcon);
        Assert.False(Group(GroupKind.Query).AllowsCustomIcon);
        Assert.True(Group(GroupKind.Regular).AllowsCustomIcon);
    }

    private static ServerGroupInfo Group(GroupKind kind) => new()
    {
        GroupId = 1,
        Name = "组",
        Kind = kind,
    };
}

public class HostBannerInfoTests
{
    [Fact]
    public void EmptyHasNeitherBannerNorButton()
    {
        Assert.False(HostBannerInfo.Empty.HasBanner);
        Assert.False(HostBannerInfo.Empty.HasButton);
    }

    [Fact]
    public void ABannerNeedsAnImageButAButtonDoesNot()
    {
        // A host button can be a bare link with no image.
        Assert.True((HostBannerInfo.Empty with { GfxUrl = "https://x/y.png" }).HasBanner);
        Assert.False((HostBannerInfo.Empty with { LinkUrl = "https://x" }).HasBanner);
        Assert.True((HostBannerInfo.Empty with { ButtonUrl = "https://x" }).HasButton);
    }
}
