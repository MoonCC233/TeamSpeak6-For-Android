// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

namespace TeamSpeak9.Core.Identity;

/// <summary>
/// One TeamSpeak identity as stored on disk.
/// </summary>
public sealed class StoredIdentity
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");

    /// <summary>User-visible name, e.g. "默认身份".</summary>
    public string Name { get; set; } = string.Empty;

    /// <summary>
    /// The private key, protected by <see cref="Security.ISecretProtector"/>.
    /// </summary>
    /// <remarks>
    /// This is the whole identity: whoever holds it can impersonate the user on any TeamSpeak
    /// server and inherits their server groups. It must never be logged or copied into settings.
    /// </remarks>
    public string ProtectedPrivateKey { get; set; } = string.Empty;

    /// <summary>Which protection scheme produced <see cref="ProtectedPrivateKey"/>.</summary>
    public string ProtectionScheme { get; set; } = string.Empty;

    /// <summary>
    /// Offset that yields the identity's security level; TeamSpeak calls this the key offset.
    /// </summary>
    public ulong KeyOffset { get; set; }

    /// <summary>Highest offset already tried when improving the security level, to resume later.</summary>
    public ulong LastCheckedKeyOffset { get; set; }

    /// <summary>The derived client UID, cached so the identity list renders without unprotecting keys.</summary>
    public string Uid { get; set; } = string.Empty;

    /// <summary>Security level derived from <see cref="KeyOffset"/>, cached for display.</summary>
    public int SecurityLevel { get; set; }

    public DateTimeOffset CreatedAt { get; set; } = DateTimeOffset.Now;
}

/// <summary>
/// The identity file: a list of identities plus which one is the default.
/// </summary>
public sealed class IdentityFile
{
    public int Version { get; set; } = 1;

    public string DefaultIdentityId { get; set; } = string.Empty;

    public List<StoredIdentity> Identities { get; set; } = [];
}
