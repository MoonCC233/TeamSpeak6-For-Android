// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using System.Globalization;
using System.IO;
using System.Windows.Media.Imaging;
using TeamSpeak9.App.Converters;
using TeamSpeak9.App.Tests.Infrastructure;
using TeamSpeak9.Core.Model;
using IoPath = System.IO.Path;

namespace TeamSpeak9.App.Tests.Converters;

/// <summary>
/// Covers the icon cache against a real directory on disk.
/// </summary>
/// <remarks>
/// <c>CacheDirectory</c> and the bitmap cache are static, so these tests share process-wide state
/// and must run one at a time. <see cref="Sta" /> is required because <c>BitmapImage</c> derives
/// from <c>DispatcherObject</c>.
/// </remarks>
[Collection(nameof(IconIdToImageConverterTests))]
[CollectionDefinition(nameof(IconIdToImageConverterTests), DisableParallelization = true)]
public class IconIdToImageConverterTests : IDisposable
{
    /// <summary>A 1x1 opaque PNG.</summary>
    private static readonly byte[] OnePixelPng = System.Convert.FromBase64String(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==");

    private static readonly CultureInfo Culture = CultureInfo.InvariantCulture;

    private readonly string? _previousDirectory = IconIdToImageConverter.CacheDirectory;

    public void Dispose()
    {
        IconIdToImageConverter.CacheDirectory = _previousDirectory;
        IconIdToImageConverter.InvalidateAll();
        GC.SuppressFinalize(this);
    }

    private static object? Convert(object? value) =>
        new IconIdToImageConverter().Convert(value, typeof(object), null, Culture);

    private static void WriteIcon(TempDirectory directory, uint id, byte[] bytes) =>
        File.WriteAllBytes(IoPath.Combine(directory.Path, IconId.FromUnsigned(id).ToFileName()), bytes);

    private static void Use(TempDirectory directory)
    {
        IconIdToImageConverter.CacheDirectory = directory.Path;
        IconIdToImageConverter.InvalidateAll();
    }

    [Fact]
    public void AnIconOnDiskIsDecoded()
    {
        Sta.Run(() =>
        {
            using var directory = new TempDirectory();
            Use(directory);
            WriteIcon(directory, 1234u, OnePixelPng);

            var image = Assert.IsType<BitmapImage>(Convert(IconId.FromUnsigned(1234u)));

            Assert.Equal(1, image.PixelWidth);
            Assert.Equal(1, image.PixelHeight);
        });
    }

    [Fact]
    public void ADecodedIconIsFrozenSoAnyThreadCanBindIt()
    {
        // Notifications arrive on the TSLib scheduler thread; an unfrozen bitmap would throw when
        // the binding touched it from the UI thread.
        Sta.Run(() =>
        {
            using var directory = new TempDirectory();
            Use(directory);
            WriteIcon(directory, 1234u, OnePixelPng);

            var image = Assert.IsType<BitmapImage>(Convert(IconId.FromUnsigned(1234u)));

            Assert.True(image.IsFrozen);
        });
    }

    [Fact]
    public void TheFileIsNotHeldOpenAfterDecoding()
    {
        // OnLoad decoding is what lets the downloader replace an icon that is already on screen.
        Sta.Run(() =>
        {
            using var directory = new TempDirectory();
            Use(directory);
            WriteIcon(directory, 1234u, OnePixelPng);

            Assert.NotNull(Convert(IconId.FromUnsigned(1234u)));

            string path = IoPath.Combine(directory.Path, "icon_1234");
            File.WriteAllBytes(path, OnePixelPng);
            File.Delete(path);
        });
    }

    [Fact]
    public void TheSameIdIsOnlyDecodedOnce()
    {
        Sta.Run(() =>
        {
            using var directory = new TempDirectory();
            Use(directory);
            WriteIcon(directory, 1234u, OnePixelPng);

            var first = Convert(IconId.FromUnsigned(1234u));
            var second = Convert(IconId.FromUnsigned(1234u));

            Assert.Same(first, second);
        });
    }

    [Fact]
    public void AMissingIconIsRememberedAsMissing()
    {
        // Caching the null keeps a missing icon from hitting the disk on every tree redraw.
        Sta.Run(() =>
        {
            using var directory = new TempDirectory();
            Use(directory);

            Assert.Null(Convert(IconId.FromUnsigned(4321u)));

            WriteIcon(directory, 4321u, OnePixelPng);
            Assert.Null(Convert(IconId.FromUnsigned(4321u)));
        });
    }

    [Fact]
    public void InvalidateMakesTheNextLookupHitTheDiskAgain()
    {
        Sta.Run(() =>
        {
            using var directory = new TempDirectory();
            Use(directory);

            Assert.Null(Convert(IconId.FromUnsigned(4321u)));

            WriteIcon(directory, 4321u, OnePixelPng);
            IconIdToImageConverter.Invalidate(IconId.FromUnsigned(4321u));

            Assert.NotNull(Convert(IconId.FromUnsigned(4321u)));
        });
    }

    [Fact]
    public void InvalidateOnlyDropsTheIdItIsGiven()
    {
        Sta.Run(() =>
        {
            using var directory = new TempDirectory();
            Use(directory);
            WriteIcon(directory, 1u, OnePixelPng);
            WriteIcon(directory, 2u, OnePixelPng);

            var keep = Convert(IconId.FromUnsigned(1u));
            Assert.NotNull(Convert(IconId.FromUnsigned(2u)));

            IconIdToImageConverter.Invalidate(IconId.FromUnsigned(2u));

            Assert.Same(keep, Convert(IconId.FromUnsigned(1u)));
        });
    }

    [Fact]
    public void ATruncatedFileYieldsNothingInsteadOfThrowing()
    {
        // A partially written file is normal while a download is in flight.
        Sta.Run(() =>
        {
            using var directory = new TempDirectory();
            Use(directory);
            WriteIcon(directory, 1234u, OnePixelPng[..20]);

            Assert.Null(Convert(IconId.FromUnsigned(1234u)));
        });
    }

    [Fact]
    public void AFileThatIsNotAnImageYieldsNothing()
    {
        Sta.Run(() =>
        {
            using var directory = new TempDirectory();
            Use(directory);
            WriteIcon(directory, 1234u, "not an icon"u8.ToArray());

            Assert.Null(Convert(IconId.FromUnsigned(1234u)));
        });
    }

    [Fact]
    public void NothingIsLoadedBeforeTheCacheDirectoryIsKnown()
    {
        Sta.Run(() =>
        {
            IconIdToImageConverter.CacheDirectory = null;
            IconIdToImageConverter.InvalidateAll();

            Assert.Null(Convert(IconId.FromUnsigned(1234u)));
        });
    }

    [Fact]
    public void TheEmptyIconIsNotLookedUp()
    {
        Sta.Run(() =>
        {
            using var directory = new TempDirectory();
            Use(directory);

            Assert.Null(Convert(IconId.None));
            Assert.Null(Convert(0));
            Assert.Null(Convert("0"));
        });
    }

    [Fact]
    public void ANegativeIdReachesTheSameFileAsItsUnsignedForm()
    {
        // tsserver reports custom icons as signed ints but stores them under the unsigned name.
        Sta.Run(() =>
        {
            using var directory = new TempDirectory();
            Use(directory);
            WriteIcon(directory, unchecked((uint)-1569272494), OnePixelPng);

            Assert.NotNull(Convert(-1569272494));
        });
    }

    [Fact]
    public void EveryFormTheBindingsProduceResolvesToTheSameIcon()
    {
        // Channel icons arrive as IconId, ServerQuery replies as strings, and TSLib surfaces raw
        // integers; all of them have to land on one file.
        Sta.Run(() =>
        {
            using var directory = new TempDirectory();
            Use(directory);
            WriteIcon(directory, 2725694802u, OnePixelPng);

            object?[] forms =
            [
                IconId.FromUnsigned(2725694802u),
                -1569272494,
                2725694802u,
                18446744072140279122UL,
                -1569272494L,
                "2725694802",
                "-1569272494",
                "18446744072140279122",
            ];

            foreach (object? form in forms)
                Assert.NotNull(Convert(form));
        });
    }

    [Fact]
    public void AValueThatIsNotAnIconIdIsIgnored()
    {
        Sta.Run(() =>
        {
            using var directory = new TempDirectory();
            Use(directory);
            WriteIcon(directory, 1234u, OnePixelPng);

            Assert.Null(Convert(null));
            Assert.Null(Convert("not a number"));
            Assert.Null(Convert(1234.5));
            Assert.Null(Convert(true));
        });
    }

    [Fact]
    public void ItIsAOneWayConverter()
    {
        var converter = new IconIdToImageConverter();

        Assert.Throws<NotSupportedException>(
            () => converter.ConvertBack(null, typeof(IconId), null, Culture));
    }
}
