// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using System.Runtime.Versioning;
using System.Security.Cryptography;
using System.Text;

namespace TeamSpeak9.Core.Security;

/// <summary>
/// DPAPI-backed <see cref="ISecretProtector"/> scoped to the current Windows user.
/// </summary>
/// <remarks>
/// The blob can only be read back by the same user on the same machine, which is what we want
/// for an identity key: copying <c>identity.json</c> to another machine yields an unusable file
/// rather than a stolen identity.
/// </remarks>
[SupportedOSPlatform("windows")]
public sealed class DpapiSecretProtector : ISecretProtector
{
    // Ties the blob to this application, so another program's DPAPI blob can't be swapped in.
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("TeamSpeak9.Identity.v1");

    public string SchemeId => "dpapi-user-v1";

    public string Protect(string plaintext)
    {
        ArgumentNullException.ThrowIfNull(plaintext);

        byte[] bytes = Encoding.UTF8.GetBytes(plaintext);
        try
        {
            byte[] blob = ProtectedData.Protect(bytes, Entropy, DataProtectionScope.CurrentUser);
            return Convert.ToBase64String(blob);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(bytes);
        }
    }

    public string Unprotect(string protectedBase64)
    {
        ArgumentNullException.ThrowIfNull(protectedBase64);

        byte[] blob = Convert.FromBase64String(protectedBase64);
        byte[]? plain = null;
        try
        {
            plain = ProtectedData.Unprotect(blob, Entropy, DataProtectionScope.CurrentUser);
            return Encoding.UTF8.GetString(plain);
        }
        finally
        {
            if (plain is not null)
                CryptographicOperations.ZeroMemory(plain);
        }
    }
}
