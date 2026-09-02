// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using Microsoft.Extensions.Logging.Abstractions;
using TeamSpeak9.Core.Identity;
using TeamSpeak9.Core.Settings;
using TeamSpeak9.Core.Tests.Fakes;

namespace TeamSpeak9.Core.Tests.Identity;

public class IdentityStoreTests
{
    // Generating an identity costs roughly 2^level milliseconds, so tests stay at the bottom.
    private const int FastLevel = 0;

    private static IdentityStore CreateStore(
        TempDirectory dir,
        out AppPaths paths,
        FakeSecretProtector? protector = null)
    {
        paths = new AppPaths(dir.Path);
        return new IdentityStore(paths, protector ?? new FakeSecretProtector(), NullLogger<IdentityStore>.Instance);
    }

    [Fact]
    public async Task MissingFileYieldsAnEmptyList()
    {
        using var dir = new TempDirectory();
        var store = CreateStore(dir, out _);

        var file = await store.LoadAsync();

        Assert.Empty(file.Identities);
        Assert.Equal(string.Empty, file.DefaultIdentityId);
    }

    [Fact]
    public void CreatedIdentityIsProtectedAndCarriesADerivedUid()
    {
        using var dir = new TempDirectory();
        var store = CreateStore(dir, out _);

        var created = store.Create("我的身份", FastLevel);

        Assert.Equal("我的身份", created.Name);
        Assert.NotEmpty(created.Uid);
        Assert.Equal("fake-v1", created.ProtectionScheme);
        Assert.NotEmpty(created.ProtectedPrivateKey);
        Assert.True(created.SecurityLevel >= FastLevel);
    }

    [Fact]
    public void ProtectedKeyIsNotStoredInTheClear()
    {
        using var dir = new TempDirectory();
        var protector = new FakeSecretProtector();
        var store = CreateStore(dir, out _, protector);

        var created = store.Create("x", FastLevel);
        string plain = protector.Unprotect(created.ProtectedPrivateKey);

        Assert.NotEqual(plain, created.ProtectedPrivateKey);
    }

    [Fact]
    public void UnprotectReturnsAUsableIdentityWithTheSameUid()
    {
        using var dir = new TempDirectory();
        var store = CreateStore(dir, out _);

        var created = store.Create("x", FastLevel);
        var identity = store.Unprotect(created);

        Assert.Equal(created.Uid, identity.ClientUid.Value);
        Assert.Equal(created.KeyOffset, identity.ValidKeyOffset);
    }

    [Fact]
    public void UnprotectFailsClearlyWhenTheKeyCameFromAnotherMachine()
    {
        using var dir = new TempDirectory();
        var protector = new FakeSecretProtector();
        var store = CreateStore(dir, out _, protector);

        var created = store.Create("x", FastLevel);
        protector.FailUnprotect = true;

        var ex = Assert.Throws<IdentityStoreException>(() => store.Unprotect(created));
        Assert.Contains("无法解密", ex.Message);
    }

    [Fact]
    public void UnprotectRejectsAForeignProtectionScheme()
    {
        using var dir = new TempDirectory();
        var store = CreateStore(dir, out _);

        var created = store.Create("x", FastLevel);
        created.ProtectionScheme = "some-other-scheme";

        var ex = Assert.Throws<IdentityStoreException>(() => store.Unprotect(created));
        Assert.Contains("其他保护方式", ex.Message);
    }

    [Fact]
    public void UnprotectRejectsAnIdentityWithoutAKey()
    {
        using var dir = new TempDirectory();
        var store = CreateStore(dir, out _);

        var ex = Assert.Throws<IdentityStoreException>(() => store.Unprotect(new StoredIdentity()));
        Assert.Contains("缺少私钥", ex.Message);
    }

    [Fact]
    public void ImportReadsBackAnExportedPrivateKey()
    {
        using var dir = new TempDirectory();
        var protector = new FakeSecretProtector();
        var store = CreateStore(dir, out _, protector);

        var created = store.Create("original", FastLevel);
        string exported = protector.Unprotect(created.ProtectedPrivateKey);

        var imported = store.Import("imported", exported, created.KeyOffset);

        Assert.Equal(created.Uid, imported.Uid);
        Assert.Equal("imported", imported.Name);
        Assert.NotEqual(created.Id, imported.Id);
    }

    [Fact]
    public void ImportRejectsGarbage()
    {
        using var dir = new TempDirectory();
        var store = CreateStore(dir, out _);

        Assert.Throws<IdentityStoreException>(() => store.Import("bad", "this-is-not-a-key"));
    }

    [Theory]
    [InlineData("")]
    [InlineData("   ")]
    public void ImportRejectsEmptyInput(string key)
    {
        using var dir = new TempDirectory();
        var store = CreateStore(dir, out _);

        Assert.Throws<ArgumentException>(() => store.Import("bad", key));
    }

    [Theory]
    [InlineData(-1)]
    [InlineData(31)]
    public void CreateRejectsAnAbsurdSecurityLevel(int level)
    {
        using var dir = new TempDirectory();
        var store = CreateStore(dir, out _);

        Assert.Throws<ArgumentOutOfRangeException>(() => store.Create("x", level));
    }

    [Fact]
    public async Task SaveAndLoadRoundTripAnIdentity()
    {
        using var dir = new TempDirectory();
        var store = CreateStore(dir, out var paths);

        var created = store.Create("往返", FastLevel);
        var file = new IdentityFile { DefaultIdentityId = created.Id };
        file.Identities.Add(created);

        await store.SaveAsync(file);
        Assert.True(File.Exists(paths.IdentityFile));

        var loaded = await store.LoadAsync();

        Assert.Equal(created.Id, loaded.DefaultIdentityId);
        var single = Assert.Single(loaded.Identities);
        Assert.Equal("往返", single.Name);
        Assert.Equal(created.Uid, single.Uid);
        Assert.Equal(created.ProtectedPrivateKey, single.ProtectedPrivateKey);
        Assert.Equal(created.KeyOffset, single.KeyOffset);
    }

    [Fact]
    public async Task GetOrCreateDefaultCreatesOnceAndThenReuses()
    {
        using var dir = new TempDirectory();
        var store = CreateStore(dir, out _);

        var (firstFile, first) = await store.GetOrCreateDefaultAsync();
        Assert.Equal(first.Id, firstFile.DefaultIdentityId);
        Assert.Single(firstFile.Identities);

        var (secondFile, second) = await store.GetOrCreateDefaultAsync();

        Assert.Equal(first.Id, second.Id);
        Assert.Equal(first.Uid, second.Uid);
        Assert.Single(secondFile.Identities);
    }

    [Fact]
    public async Task AnUnreadableIdentityFileIsReportedAndKept()
    {
        using var dir = new TempDirectory();
        var store = CreateStore(dir, out var paths);

        Directory.CreateDirectory(paths.Root);
        await File.WriteAllTextAsync(paths.IdentityFile, "{ not json");

        await Assert.ThrowsAsync<IdentityStoreException>(() => store.LoadAsync());

        // The file holds the only copy of the private keys; it must survive a parse failure.
        Assert.True(File.Exists(paths.IdentityFile));
    }

    [Fact]
    public void ResolvePrefersTheRequestedIdThenTheDefaultThenAnything()
    {
        var a = new StoredIdentity { Id = "a" };
        var b = new StoredIdentity { Id = "b" };
        var file = new IdentityFile { DefaultIdentityId = "b", Identities = [a, b] };

        Assert.Equal("a", IdentityStore.Resolve(file, "a")!.Id);
        Assert.Equal("b", IdentityStore.Resolve(file, null)!.Id);
        Assert.Equal("b", IdentityStore.Resolve(file, "missing")!.Id);

        file.DefaultIdentityId = "also-missing";
        Assert.Equal("a", IdentityStore.Resolve(file, null)!.Id);

        Assert.Null(IdentityStore.Resolve(new IdentityFile(), null));
    }

    [Fact]
    public async Task SaveRejectsNull()
    {
        using var dir = new TempDirectory();
        var store = CreateStore(dir, out _);

        await Assert.ThrowsAsync<ArgumentNullException>(() => store.SaveAsync(null!));
    }
}
