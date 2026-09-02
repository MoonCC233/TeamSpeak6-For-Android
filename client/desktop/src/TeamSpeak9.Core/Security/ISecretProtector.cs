// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

namespace TeamSpeak9.Core.Security;

/// <summary>
/// Protects small secrets at rest, currently only the identity private key.
/// </summary>
/// <remarks>
/// Kept as an interface so <see cref="Identity.IdentityStore"/> stays testable without touching
/// the machine's real key store.
/// </remarks>
public interface ISecretProtector
{
    /// <summary>Identifier written alongside the ciphertext so a future scheme can be told apart.</summary>
    string SchemeId { get; }

    /// <summary>Protects <paramref name="plaintext"/> and returns a base64 blob.</summary>
    string Protect(string plaintext);

    /// <summary>Reverses <see cref="Protect"/>. Throws when the blob was produced elsewhere.</summary>
    string Unprotect(string protectedBase64);
}
