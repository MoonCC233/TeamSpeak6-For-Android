// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using TeamSpeak9.Core.Settings;
using TeamSpeak9.Core.Tests.Fakes;

namespace TeamSpeak9.Core.Tests.Settings;

public class AppPathsTests
{
    [Fact]
    public void AllPathsHangOffTheRoot()
    {
        var paths = new AppPaths(@"C:\ts9");

        Assert.Equal(@"C:\ts9", paths.Root);
        Assert.Equal(@"C:\ts9\settings.json", paths.SettingsFile);
        Assert.Equal(@"C:\ts9\identity.json", paths.IdentityFile);
        Assert.Equal(@"C:\ts9\logs", paths.LogDirectory);
        Assert.Equal(@"C:\ts9\cache\icons", paths.IconCacheDirectory);
        Assert.Equal(@"C:\ts9\cache\banners", paths.BannerCacheDirectory);
    }

    [Theory]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData(null)]
    public void EmptyRootIsRejected(string? root)
    {
        Assert.Throws<ArgumentException>(() => new AppPaths(root!));
    }

    [Fact]
    public void RelativeRootIsMadeAbsolute()
    {
        var paths = new AppPaths("relative-root");

        Assert.True(Path.IsPathRooted(paths.Root));
    }

    [Fact]
    public void DefaultRootLivesUnderApplicationData()
    {
        var paths = AppPaths.CreateDefault();

        Assert.EndsWith("TeamSpeak9", paths.Root);
        Assert.StartsWith(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            paths.Root,
            StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void EnsureCreatedIsIdempotent()
    {
        using var dir = new TempDirectory();
        var paths = new AppPaths(Path.Combine(dir.Path, "state"));

        paths.EnsureCreated();
        paths.EnsureCreated();

        Assert.True(Directory.Exists(paths.Root));
        Assert.True(Directory.Exists(paths.LogDirectory));
        Assert.True(Directory.Exists(paths.IconCacheDirectory));
        Assert.True(Directory.Exists(paths.BannerCacheDirectory));
    }
}
