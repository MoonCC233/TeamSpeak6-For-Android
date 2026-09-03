// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using System.Globalization;
using System.IO;
using System.Windows.Data;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using TeamSpeak9.Core.Model;
// System.Windows.Shapes.Path arrives via the WPF SDK's implicit usings and shadows System.IO.Path.
using IoPath = System.IO.Path;

namespace TeamSpeak9.App.Converters;

/// <summary>
/// Turns a TeamSpeak icon id into a cached <see cref="ImageSource"/>.
/// </summary>
/// <remarks>
/// <para>
/// Accepts anything <see cref="IconId.TryParse"/> understands plus the numeric forms TSLib
/// exposes, because the same id arrives signed from <c>channelpermlist</c> and unsigned from
/// <c>channelinfo</c>.
/// </para>
/// <para>
/// Icons are files in the virtual server's internal channel, so they cannot be fetched
/// synchronously here. The converter only maps an id to the on-disk cache path that the icon
/// downloader writes to; a missing file yields <c>null</c> and the view falls back to its
/// placeholder. Once the download completes the binding is refreshed by the owning view model.
/// </para>
/// </remarks>
public sealed class IconIdToImageConverter : IValueConverter
{
    private static readonly Dictionary<uint, BitmapImage?> Cache = [];

    /// <summary>Directory holding downloaded icons. Set once during startup.</summary>
    /// <remarks>Points at <c>%APPDATA%\TeamSpeak9\cache\icons</c> in normal runs.</remarks>
    public static string? CacheDirectory { get; set; }

    /// <summary>Drops memoized bitmaps, e.g. after an icon is replaced on the server.</summary>
    public static void Invalidate(IconId id)
    {
        lock (Cache)
            Cache.Remove(id.Unsigned);
    }

    /// <summary>Drops every memoized bitmap.</summary>
    public static void InvalidateAll()
    {
        lock (Cache)
            Cache.Clear();
    }

    public object? Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        if (!TryGetIconId(value, out var id) || id.IsNone || CacheDirectory is null)
            return null;

        lock (Cache)
        {
            if (Cache.TryGetValue(id.Unsigned, out var cached))
                return cached;

            var bitmap = Load(IoPath.Combine(CacheDirectory, id.ToFileName()));
            Cache[id.Unsigned] = bitmap;
            return bitmap;
        }
    }

    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture)
        => throw new NotSupportedException();

    private static bool TryGetIconId(object? value, out IconId id)
    {
        switch (value)
        {
            case IconId direct:
                id = direct;
                return true;
            case int signed:
                id = IconId.FromSigned(signed);
                return true;
            case uint unsigned:
                id = IconId.FromUnsigned(unsigned);
                return true;
            case long or ulong:
                id = IconId.FromSigned(unchecked((int)System.Convert.ToUInt64(value, CultureInfo.InvariantCulture)));
                return true;
            case string text:
                return IconId.TryParse(text, out id);
            default:
                id = IconId.None;
                return false;
        }
    }

    private static BitmapImage? Load(string path)
    {
        if (!File.Exists(path))
            return null;

        try
        {
            var bitmap = new BitmapImage();
            bitmap.BeginInit();
            // OnLoad decodes right here so the MemoryStream can go away and the file is never
            // held open, which lets the downloader overwrite it.
            //
            // IgnoreImageCache must NOT be set: WPF's image cache is keyed by UriSource, and with
            // a StreamSource there is no Uri, so FinalizeCreation ends up calling
            // ImagingCache.RemoveFromCache(null) and throws ArgumentNullException("key"). A stream
            // source is not cached in the first place, so the option would buy nothing anyway.
            bitmap.CacheOption = BitmapCacheOption.OnLoad;
            bitmap.StreamSource = new MemoryStream(File.ReadAllBytes(path));
            bitmap.EndInit();
            bitmap.Freeze();
            return bitmap;
        }
        catch (Exception ex) when (ex is IOException or NotSupportedException or ArgumentException)
        {
            // A truncated or non-image file is expected while a download is in flight.
            return null;
        }
    }
}
