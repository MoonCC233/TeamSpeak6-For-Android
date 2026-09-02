// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using Microsoft.Extensions.Logging.Abstractions;
using TeamSpeak9.Core.Settings;
using TeamSpeak9.Core.Tests.Fakes;

namespace TeamSpeak9.Core.Tests.Settings;

public class SettingsStoreTests
{
    private static SettingsStore CreateStore(TempDirectory dir, out AppPaths paths)
    {
        paths = new AppPaths(dir.Path);
        return new SettingsStore(paths, NullLogger<SettingsStore>.Instance);
    }

    [Fact]
    public async Task MissingFileYieldsDefaults()
    {
        using var dir = new TempDirectory();
        var store = CreateStore(dir, out _);

        var settings = await store.LoadAsync();

        Assert.Equal(1, settings.Version);
        Assert.Empty(settings.Bookmarks);
        Assert.Equal(PushToTalkMode.VoiceActivation, settings.Audio.TransmitMode);
        Assert.Equal(StreamModePreference.PreferP2P, settings.Stream.ModePreference);
    }

    [Fact]
    public async Task RoundTripsEveryNestedSection()
    {
        using var dir = new TempDirectory();
        var store = CreateStore(dir, out var paths);

        var saved = new AppSettings
        {
            Nickname = "测试用户",
            LogLevel = "Debug",
        };
        saved.Audio.TransmitMode = PushToTalkMode.PushToTalk;
        saved.Audio.PushToTalkHotkey = "Ctrl+Shift+K";
        saved.Audio.VoiceActivationThresholdDb = -28.5;
        saved.Stream.ModePreference = StreamModePreference.ForceSfu;
        saved.Stream.ManualEndpoint = "wss://stream.example.com:8443/tssp";
        saved.Stream.TrustedEndpoints.Add("wss://stream.example.com:8443/tssp");
        saved.Stream.MaxBitrateKbps = 6000;
        saved.Appearance.ShowChatPanel = false;
        saved.Appearance.SidebarWidth = 300;
        saved.Bookmarks.Add(new BookmarkEntry
        {
            Name = "本地服务器",
            Address = "127.0.0.1:9987",
            Nickname = "我",
            DefaultChannel = "/5",
            Folder = "测试/内网",
            ConnectOnStartup = true,
        });

        await store.SaveAsync(saved);
        Assert.True(File.Exists(paths.SettingsFile));

        var loaded = await store.LoadAsync();

        Assert.Equal("测试用户", loaded.Nickname);
        Assert.Equal("Debug", loaded.LogLevel);
        Assert.Equal(PushToTalkMode.PushToTalk, loaded.Audio.TransmitMode);
        Assert.Equal("Ctrl+Shift+K", loaded.Audio.PushToTalkHotkey);
        Assert.Equal(-28.5, loaded.Audio.VoiceActivationThresholdDb);
        Assert.Equal(StreamModePreference.ForceSfu, loaded.Stream.ModePreference);
        Assert.Equal("wss://stream.example.com:8443/tssp", loaded.Stream.ManualEndpoint);
        Assert.Single(loaded.Stream.TrustedEndpoints);
        Assert.Equal(6000, loaded.Stream.MaxBitrateKbps);
        Assert.False(loaded.Appearance.ShowChatPanel);
        Assert.Equal(300, loaded.Appearance.SidebarWidth);

        var bookmark = Assert.Single(loaded.Bookmarks);
        Assert.Equal("本地服务器", bookmark.Name);
        Assert.Equal("127.0.0.1:9987", bookmark.Address);
        Assert.Equal("/5", bookmark.DefaultChannel);
        Assert.Equal("测试/内网", bookmark.Folder);
        Assert.True(bookmark.ConnectOnStartup);
    }

    [Fact]
    public async Task EnumsArePersistedByNameNotOrdinal()
    {
        using var dir = new TempDirectory();
        var store = CreateStore(dir, out var paths);

        var saved = new AppSettings();
        saved.Stream.ModePreference = StreamModePreference.ForceP2P;
        await store.SaveAsync(saved);

        string json = await File.ReadAllTextAsync(paths.SettingsFile);

        // Names keep the file readable and survive reordering the enum members.
        Assert.Contains("ForceP2P", json);
    }

    [Fact]
    public async Task NonAsciiIsNotEscapedIntoUnicodeSequences()
    {
        using var dir = new TempDirectory();
        var store = CreateStore(dir, out var paths);

        await store.SaveAsync(new AppSettings { Nickname = "月" });

        string json = await File.ReadAllTextAsync(paths.SettingsFile);
        Assert.Contains("月", json);
    }

    [Fact]
    public async Task CorruptFileIsMovedAsideAndDefaultsAreUsed()
    {
        using var dir = new TempDirectory();
        var store = CreateStore(dir, out var paths);

        Directory.CreateDirectory(paths.Root);
        await File.WriteAllTextAsync(paths.SettingsFile, "{ this is not json");

        var settings = await store.LoadAsync();

        Assert.Equal(1, settings.Version);
        Assert.False(File.Exists(paths.SettingsFile));
        Assert.NotEmpty(Directory.GetFiles(paths.Root, "settings.json.corrupt-*"));
    }

    [Fact]
    public async Task SaveCreatesTheDirectoryTreeAndLeavesNoTempFile()
    {
        using var dir = new TempDirectory();
        var nested = Path.Combine(dir.Path, "a", "b");
        var paths = new AppPaths(nested);
        var store = new SettingsStore(paths, NullLogger<SettingsStore>.Instance);

        await store.SaveAsync(new AppSettings());

        Assert.True(Directory.Exists(paths.LogDirectory));
        Assert.True(Directory.Exists(paths.IconCacheDirectory));
        Assert.True(Directory.Exists(paths.BannerCacheDirectory));
        Assert.False(File.Exists(paths.SettingsFile + ".tmp"));
    }

    [Fact]
    public async Task ConcurrentSavesDoNotCorruptTheFile()
    {
        using var dir = new TempDirectory();
        var store = CreateStore(dir, out _);

        var writes = Enumerable.Range(0, 20)
            .Select(i => store.SaveAsync(new AppSettings { Nickname = "user" + i }));
        await Task.WhenAll(writes);

        var loaded = await store.LoadAsync();
        Assert.StartsWith("user", loaded.Nickname);
    }

    [Fact]
    public async Task SaveRejectsNull()
    {
        using var dir = new TempDirectory();
        var store = CreateStore(dir, out _);

        await Assert.ThrowsAsync<ArgumentNullException>(() => store.SaveAsync(null!));
    }
}
