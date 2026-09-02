// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using TeamSpeak9.Core.Settings;
using TSLib;
using TSLib.Full;

namespace TeamSpeak9.Core.Connection;

/// <summary>
/// Everything needed for one connection attempt.
/// </summary>
/// <remarks>
/// This exists so callers never have to build a <see cref="ConnectionDataFull"/> by hand. That type
/// takes pre-hashed <see cref="Password"/> values and has an implicit <c>string</c> conversion that
/// silently hashes, which makes it easy to double-hash a password by accident. Here the passwords
/// are plain text and the hashing happens once, in <see cref="ToConnectionData"/>.
/// </remarks>
public sealed record ConnectionRequest
{
    /// <summary>
    /// How long to wait for the handshake before giving up.
    /// </summary>
    /// <remarks>
    /// TSLib's own packet timeout is 30 s, which is far too long to leave a user staring at a
    /// spinner. More importantly TSLib can fail to complete its connect task at all (see
    /// <c>TsConnection</c>), so this timeout is what actually ends a failed attempt.
    /// </remarks>
    public static readonly TimeSpan DefaultTimeout = TimeSpan.FromSeconds(12);

    /// <summary>Hostname or IP with optional <c>:port</c>. A TSDNS name also works.</summary>
    public required string Address { get; init; }

    /// <summary>Identity to connect with. Obtained from <c>IdentityStore.Unprotect</c>.</summary>
    public required IdentityData Identity { get; init; }

    /// <summary>Nickname to request. The server may uniquify it.</summary>
    public required string Nickname { get; init; }

    /// <summary>Plain text server password. Empty means none.</summary>
    public string ServerPassword { get; init; } = string.Empty;

    /// <summary>
    /// Channel to join on connect: a name path such as <c>Lobby/Home</c>, or <c>/&lt;id&gt;</c>.
    /// </summary>
    public string DefaultChannel { get; init; } = string.Empty;

    /// <summary>Plain text password for <see cref="DefaultChannel"/>.</summary>
    public string DefaultChannelPassword { get; init; } = string.Empty;

    public TimeSpan Timeout { get; init; } = DefaultTimeout;

    /// <summary>
    /// Version triple to present, or <c>null</c> to let TSLib pick the platform default.
    /// </summary>
    /// <remarks>
    /// The default (<c>VER_WIN_3_X_X</c>) is accepted by tsserver 6.0.0-beta12.1. A server with a
    /// raised <c>virtualserver_min_client_version</c> may reject it, which is the only reason to
    /// override this.
    /// </remarks>
    public TsVersionSigned? VersionSign { get; init; }

    /// <summary>Bookmark this request came from, for reconnect and UI attribution. May be empty.</summary>
    public string BookmarkId { get; init; } = string.Empty;

    /// <summary>Automatically retry when the connection drops unexpectedly.</summary>
    public bool AutoReconnect { get; init; } = true;

    /// <summary>Builds a request straight from a bookmark.</summary>
    /// <param name="bookmark">The saved server entry.</param>
    /// <param name="identity">Identity resolved from <see cref="BookmarkEntry.IdentityId"/>.</param>
    /// <param name="fallbackNickname">Used when the bookmark has no nickname of its own.</param>
    public static ConnectionRequest FromBookmark(
        BookmarkEntry bookmark,
        IdentityData identity,
        string fallbackNickname)
    {
        ArgumentNullException.ThrowIfNull(bookmark);
        ArgumentNullException.ThrowIfNull(identity);

        string nickname = string.IsNullOrWhiteSpace(bookmark.Nickname)
            ? fallbackNickname
            : bookmark.Nickname;

        return new ConnectionRequest
        {
            Address = bookmark.Address,
            Identity = identity,
            Nickname = nickname,
            ServerPassword = bookmark.ServerPassword ?? string.Empty,
            DefaultChannel = bookmark.DefaultChannel ?? string.Empty,
            DefaultChannelPassword = bookmark.DefaultChannelPassword ?? string.Empty,
            BookmarkId = bookmark.Id,
        };
    }

    /// <summary>Validates the request, throwing with a user-facing message on the first problem.</summary>
    public void Validate()
    {
        if (string.IsNullOrWhiteSpace(Address))
            throw new ArgumentException("服务器地址不能为空。", nameof(Address));

        if (string.IsNullOrWhiteSpace(Nickname))
            throw new ArgumentException("昵称不能为空。", nameof(Nickname));

        if (Timeout <= TimeSpan.Zero)
            throw new ArgumentOutOfRangeException(nameof(Timeout), Timeout, "连接超时必须为正值。");
    }

    /// <summary>
    /// Converts to the shape TSLib wants.
    /// </summary>
    /// <remarks>
    /// Passwords go through <see cref="Password.FromPlain"/> explicitly. Relying on the implicit
    /// <c>string</c> conversion would work here but hides the hashing, and an already-hashed value
    /// passed in by mistake would be hashed a second time and silently rejected by the server.
    /// </remarks>
    public ConnectionDataFull ToConnectionData()
    {
        Validate();

        return new ConnectionDataFull(
            address: Address.Trim(),
            identity: Identity,
            versionSign: VersionSign,
            username: Nickname,
            serverPassword: Password.FromPlain(ServerPassword ?? string.Empty),
            defaultChannel: DefaultChannel ?? string.Empty,
            defaultChannelPassword: Password.FromPlain(DefaultChannelPassword ?? string.Empty));
    }

    /// <summary>Never includes the passwords.</summary>
    public override string ToString() => $"{Nickname}@{Address}";
}
