// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

namespace TeamSpeak9.Core.Settings;

/// <summary>
/// Where the client keeps its per-user state on disk.
/// </summary>
public sealed class AppPaths
{
    public AppPaths(string root)
    {
        if (string.IsNullOrWhiteSpace(root))
            throw new ArgumentException("Root path must not be empty.", nameof(root));

        Root = Path.GetFullPath(root);
    }

    /// <summary>Root of all user state, by default <c>%APPDATA%\TeamSpeak9</c>.</summary>
    public string Root { get; }

    public string SettingsFile => Path.Combine(Root, "settings.json");

    /// <summary>Holds the TeamSpeak identity, including a DPAPI-protected private key.</summary>
    public string IdentityFile => Path.Combine(Root, "identity.json");

    public string LogDirectory => Path.Combine(Root, "logs");

    public string CacheDirectory => Path.Combine(Root, "cache");

    /// <summary>Downloaded server / channel / group icons, named <c>icon_&lt;crc32&gt;</c>.</summary>
    public string IconCacheDirectory => Path.Combine(CacheDirectory, "icons");

    /// <summary>Cached server and channel banners.</summary>
    public string BannerCacheDirectory => Path.Combine(CacheDirectory, "banners");

    public static AppPaths CreateDefault()
    {
        string appData = Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
        return new AppPaths(Path.Combine(appData, "TeamSpeak9"));
    }

    /// <summary>Creates every directory the client writes to. Safe to call repeatedly.</summary>
    public void EnsureCreated()
    {
        Directory.CreateDirectory(Root);
        Directory.CreateDirectory(LogDirectory);
        Directory.CreateDirectory(IconCacheDirectory);
        Directory.CreateDirectory(BannerCacheDirectory);
    }
}
