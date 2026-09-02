// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using TeamSpeak9.Core.Security;

namespace TeamSpeak9.Core.Tests.Fakes;

/// <summary>
/// Reversible stand-in for DPAPI so identity tests don't touch the machine key store.
/// </summary>
internal sealed class FakeSecretProtector : ISecretProtector
{
    private const string Prefix = "fake:";

    public string SchemeId => "fake-v1";

    /// <summary>Set to make <see cref="Unprotect"/> behave like a blob from another machine.</summary>
    public bool FailUnprotect { get; set; }

    public string Protect(string plaintext)
        => Convert.ToBase64String(System.Text.Encoding.UTF8.GetBytes(Prefix + plaintext));

    public string Unprotect(string protectedBase64)
    {
        if (FailUnprotect)
            throw new System.Security.Cryptography.CryptographicException("simulated DPAPI failure");

        string decoded = System.Text.Encoding.UTF8.GetString(Convert.FromBase64String(protectedBase64));
        if (!decoded.StartsWith(Prefix, StringComparison.Ordinal))
            throw new System.Security.Cryptography.CryptographicException("not produced by this protector");

        return decoded[Prefix.Length..];
    }
}
