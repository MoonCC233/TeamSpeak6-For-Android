// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.Extensions.Logging;
using TeamSpeak9.Core.Security;
using TeamSpeak9.Core.Settings;
using TSLib.Full;

namespace TeamSpeak9.Core.Identity;

/// <summary>
/// Loads, creates and persists TeamSpeak identities.
/// </summary>
/// <remarks>
/// Private keys are written protected (DPAPI on Windows) and only unprotected when a connection
/// actually needs them. A key that cannot be unprotected - typically because the file was copied
/// from another machine or user - is reported rather than silently replaced, so the user has a
/// chance to restore a backup instead of losing their server groups.
/// </remarks>
public sealed class IdentityStore
{
    /// <summary>
    /// Security level requested for new identities. Servers can demand a minimum
    /// (<c>virtualserver_needed_identity_security_level</c>, default 8), and raising the level
    /// later costs roughly 2^level milliseconds.
    /// </summary>
    public const int DefaultSecurityLevel = 8;

    private static readonly JsonSerializerOptions SerializerOptions = new()
    {
        WriteIndented = true,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
    };

    private readonly AppPaths paths;
    private readonly ISecretProtector protector;
    private readonly ILogger<IdentityStore> log;
    private readonly SemaphoreSlim writeLock = new(1, 1);

    public IdentityStore(AppPaths paths, ISecretProtector protector, ILogger<IdentityStore> log)
    {
        this.paths = paths;
        this.protector = protector;
        this.log = log;
    }

    public async Task<IdentityFile> LoadAsync(CancellationToken cancel = default)
    {
        string file = paths.IdentityFile;
        if (!File.Exists(file))
            return new IdentityFile();

        try
        {
            await using var stream = File.Open(file, FileMode.Open, FileAccess.Read, FileShare.Read);
            return await JsonSerializer.DeserializeAsync<IdentityFile>(stream, SerializerOptions, cancel)
                ?? new IdentityFile();
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (Exception ex) when (ex is JsonException or IOException or UnauthorizedAccessException)
        {
            // Deliberately not overwriting: the file holds the only copy of the private keys.
            log.LogError(ex, "Could not read identities from {Path}. Not overwriting it.", file);
            throw new IdentityStoreException($"身份文件无法读取：{file}", ex);
        }
    }

    public async Task SaveAsync(IdentityFile identities, CancellationToken cancel = default)
    {
        ArgumentNullException.ThrowIfNull(identities);

        await writeLock.WaitAsync(cancel);
        try
        {
            paths.EnsureCreated();

            string file = paths.IdentityFile;
            string temp = file + ".tmp";

            await using (var stream = File.Create(temp))
            {
                await JsonSerializer.SerializeAsync(stream, identities, SerializerOptions, cancel);
            }

            File.Move(temp, file, overwrite: true);
        }
        finally
        {
            writeLock.Release();
        }
    }

    /// <summary>
    /// Returns the default identity, creating one on first run.
    /// </summary>
    public async Task<(IdentityFile File, StoredIdentity Identity)> GetOrCreateDefaultAsync(
        CancellationToken cancel = default)
    {
        var file = await LoadAsync(cancel);

        var existing = Resolve(file, file.DefaultIdentityId);
        if (existing is not null)
            return (file, existing);

        log.LogInformation("Creating a new identity at security level {Level}.", DefaultSecurityLevel);
        var created = Create("默认身份", DefaultSecurityLevel);

        file.Identities.Add(created);
        file.DefaultIdentityId = created.Id;
        await SaveAsync(file, cancel);

        return (file, created);
    }

    /// <summary>
    /// Generates a new identity. This is CPU-bound and takes roughly 2^<paramref name="securityLevel"/>
    /// milliseconds, so callers should run it off the UI thread.
    /// </summary>
    public StoredIdentity Create(string name, int securityLevel = DefaultSecurityLevel)
    {
        if (securityLevel is < 0 or > 30)
            throw new ArgumentOutOfRangeException(nameof(securityLevel), securityLevel, "安全级别应在 0..30 之间。");

        var identity = TsCrypt.GenerateNewIdentity(securityLevel);
        return Wrap(name, identity);
    }

    /// <summary>
    /// Imports an identity from an exported private key string.
    /// </summary>
    /// <param name="keyOffset">
    /// The offset that yields the identity's security level. Passing the wrong value does not
    /// corrupt the key; it only understates the level until the offset is recomputed.
    /// </param>
    public StoredIdentity Import(string name, string privateKey, ulong keyOffset = 0)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(privateKey);

        var loaded = TsCrypt.LoadIdentityDynamic(privateKey.Trim(), keyOffset, keyOffset);
        if (!loaded.Ok)
            throw new IdentityStoreException($"无法导入身份：{loaded.Error}");

        return Wrap(name, loaded.Value);
    }

    /// <summary>
    /// Unprotects <paramref name="stored"/> into a usable TSLib identity.
    /// </summary>
    public IdentityData Unprotect(StoredIdentity stored)
    {
        ArgumentNullException.ThrowIfNull(stored);

        if (string.IsNullOrEmpty(stored.ProtectedPrivateKey))
            throw new IdentityStoreException("身份缺少私钥。");

        if (!string.Equals(stored.ProtectionScheme, protector.SchemeId, StringComparison.Ordinal))
        {
            throw new IdentityStoreException(
                $"身份使用了其他保护方式（{stored.ProtectionScheme}），当前环境无法解密。");
        }

        string privateKey;
        try
        {
            privateKey = protector.Unprotect(stored.ProtectedPrivateKey);
        }
        catch (Exception ex) when (ex is System.Security.Cryptography.CryptographicException or FormatException)
        {
            // Most likely the file was copied from another Windows user or machine.
            throw new IdentityStoreException("身份私钥无法解密，可能来自其他账户或计算机。", ex);
        }

        var loaded = TsCrypt.LoadIdentityDynamic(privateKey, stored.KeyOffset, stored.LastCheckedKeyOffset);
        if (!loaded.Ok)
            throw new IdentityStoreException($"身份私钥无效：{loaded.Error}");

        return loaded.Value;
    }

    /// <summary>Finds an identity by id, falling back to the default and then to any entry.</summary>
    public static StoredIdentity? Resolve(IdentityFile file, string? id)
    {
        ArgumentNullException.ThrowIfNull(file);

        if (!string.IsNullOrEmpty(id))
        {
            var match = file.Identities.Find(x => string.Equals(x.Id, id, StringComparison.Ordinal));
            if (match is not null)
                return match;
        }

        if (!string.IsNullOrEmpty(file.DefaultIdentityId))
        {
            var fallback = file.Identities.Find(
                x => string.Equals(x.Id, file.DefaultIdentityId, StringComparison.Ordinal));
            if (fallback is not null)
                return fallback;
        }

        return file.Identities.Count > 0 ? file.Identities[0] : null;
    }

    private StoredIdentity Wrap(string name, IdentityData identity)
    {
        return new StoredIdentity
        {
            Name = string.IsNullOrWhiteSpace(name) ? "身份" : name.Trim(),
            ProtectedPrivateKey = protector.Protect(identity.PrivateKeyString),
            ProtectionScheme = protector.SchemeId,
            KeyOffset = identity.ValidKeyOffset,
            LastCheckedKeyOffset = identity.LastCheckedKeyOffset,
            Uid = identity.ClientUid.Value,
            SecurityLevel = TsCrypt.GetSecurityLevel(identity),
        };
    }
}

public sealed class IdentityStoreException : Exception
{
    public IdentityStoreException(string message)
        : base(message)
    {
    }

    public IdentityStoreException(string message, Exception inner)
        : base(message, inner)
    {
    }
}
