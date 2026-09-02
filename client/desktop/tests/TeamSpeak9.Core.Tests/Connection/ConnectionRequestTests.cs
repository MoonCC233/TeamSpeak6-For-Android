// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using Microsoft.Extensions.Logging.Abstractions;
using TeamSpeak9.Core.Connection;
using TeamSpeak9.Core.Identity;
using TeamSpeak9.Core.Settings;
using TeamSpeak9.Core.Tests.Fakes;
using TSLib.Full;

namespace TeamSpeak9.Core.Tests.Connection;

public class ConnectionRequestTests
{
    /// <summary>
    /// Identity generation is the slow part of these tests, so one is shared. Level 0 keeps it
    /// well under a millisecond.
    /// </summary>
    private static readonly IdentityData SharedIdentity = CreateIdentity();

    private static IdentityData CreateIdentity()
    {
        using var dir = new TempDirectory();
        var store = new IdentityStore(
            new AppPaths(dir.Path),
            new FakeSecretProtector(),
            NullLogger<IdentityStore>.Instance);

        return store.Unprotect(store.Create("测试身份", securityLevel: 0));
    }

    private static ConnectionRequest Request(string address = "localhost", string nickname = "昵称") => new()
    {
        Address = address,
        Identity = SharedIdentity,
        Nickname = nickname,
    };

    [Fact]
    public void DefaultsAreConnectReady()
    {
        var request = Request();

        Assert.Equal(ConnectionRequest.DefaultTimeout, request.Timeout);
        Assert.True(request.AutoReconnect);
        Assert.Empty(request.ServerPassword);
        Assert.Empty(request.DefaultChannel);
        Assert.Null(request.VersionSign);
    }

    [Fact]
    public void TimeoutStaysWellUnderTsLibsOwn()
    {
        // TSLib's packet timeout is 30 s and its connect task can fail to complete at all, so ours
        // has to fire first for a failed attempt to ever end.
        Assert.True(ConnectionRequest.DefaultTimeout < TimeSpan.FromSeconds(30));
    }

    [Fact]
    public void FromBookmarkCopiesEveryConnectField()
    {
        var bookmark = new BookmarkEntry
        {
            Name = "我的服务器",
            Address = "ts.example.com:9987",
            Nickname = "书签昵称",
            ServerPassword = "sp",
            DefaultChannel = "Lobby/Home",
            DefaultChannelPassword = "cp",
        };

        var request = ConnectionRequest.FromBookmark(bookmark, SharedIdentity, "回落昵称");

        Assert.Equal("ts.example.com:9987", request.Address);
        Assert.Equal("书签昵称", request.Nickname);
        Assert.Equal("sp", request.ServerPassword);
        Assert.Equal("Lobby/Home", request.DefaultChannel);
        Assert.Equal("cp", request.DefaultChannelPassword);
        Assert.Equal(bookmark.Id, request.BookmarkId);
    }

    [Theory]
    [InlineData("")]
    [InlineData("   ")]
    public void FromBookmarkFallsBackToTheGlobalNickname(string bookmarkNickname)
    {
        var bookmark = new BookmarkEntry { Address = "localhost", Nickname = bookmarkNickname };

        var request = ConnectionRequest.FromBookmark(bookmark, SharedIdentity, "回落昵称");

        Assert.Equal("回落昵称", request.Nickname);
    }

    [Fact]
    public void ValidateRejectsAnEmptyAddress()
    {
        Assert.Throws<ArgumentException>(() => Request(address: "  ").Validate());
    }

    [Fact]
    public void ValidateRejectsAnEmptyNickname()
    {
        Assert.Throws<ArgumentException>(() => Request(nickname: "").Validate());
    }

    [Fact]
    public void ValidateRejectsANonPositiveTimeout()
    {
        var request = Request() with { Timeout = TimeSpan.Zero };

        Assert.Throws<ArgumentOutOfRangeException>(request.Validate);
    }

    [Fact]
    public void ToConnectionDataTrimsTheAddressAndKeepsTheNickname()
    {
        var request = Request(address: "  localhost:9987  ");

        var data = request.ToConnectionData();

        Assert.Equal("localhost:9987", data.Address);
        Assert.Equal("昵称", data.Username);
        Assert.Same(SharedIdentity, data.Identity);
    }

    [Fact]
    public void PasswordsAreHashedExactlyOnce()
    {
        var request = Request() with { ServerPassword = "secret" };

        var data = request.ToConnectionData();

        // FromPlain hashes; hashing an already hashed value would give something else, so matching
        // a single FromPlain proves there is no second round.
        Assert.Equal(
            TSLib.Password.FromPlain("secret").HashedPassword,
            data.ServerPassword.HashedPassword);
        Assert.NotEqual("secret", data.ServerPassword.HashedPassword);
    }

    [Fact]
    public void AnEmptyPasswordMatchesTsLibsEmpty()
    {
        var data = Request().ToConnectionData();

        Assert.Equal(TSLib.Password.Empty.HashedPassword, data.ServerPassword.HashedPassword);
        Assert.Equal(TSLib.Password.Empty.HashedPassword, data.DefaultChannelPassword.HashedPassword);
    }

    [Fact]
    public void ToConnectionDataValidatesFirst()
    {
        Assert.Throws<ArgumentException>(() => Request(nickname: " ").ToConnectionData());
    }

    [Fact]
    public void ToStringNeverLeaksAPassword()
    {
        var request = Request() with { ServerPassword = "topsecret", DefaultChannelPassword = "alsosecret" };

        string text = request.ToString();

        Assert.Equal("昵称@localhost", text);
        Assert.DoesNotContain("topsecret", text, StringComparison.Ordinal);
        Assert.DoesNotContain("alsosecret", text, StringComparison.Ordinal);
    }
}
