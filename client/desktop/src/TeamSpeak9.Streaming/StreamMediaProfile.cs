// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

namespace TeamSpeak9.Streaming;

/// <summary>
/// Video codec used for a screen share.
/// </summary>
/// <remarks>
/// The side-car SFU forwards by payload type without transcoding, so both ends must agree.
/// H.264 is preferred because hardware encoders are widely available on Windows and hardware
/// decoders on Android; VP8 exists as the fallback when H.264 negotiation fails.
/// </remarks>
public enum VideoCodec
{
    H264,
    Vp8,
}

/// <summary>
/// Negotiated media parameters for one screen share.
/// </summary>
public sealed record StreamMediaProfile
{
    public required VideoCodec Codec { get; init; }

    public required int Width { get; init; }

    public required int Height { get; init; }

    public required int FrameRate { get; init; }

    public required int BitrateKbps { get; init; }

    /// <summary>Whether system audio is published alongside the video track.</summary>
    public bool HasAudio { get; init; }

    /// <summary>
    /// Scales the profile down to fit within the given caps, preserving aspect ratio.
    /// </summary>
    public StreamMediaProfile ClampTo(int maxWidth, int maxHeight, int maxFrameRate, int maxBitrateKbps)
    {
        if (maxWidth <= 0 || maxHeight <= 0)
            throw new ArgumentOutOfRangeException(nameof(maxWidth), "分辨率上限必须为正数。");

        int width = Width;
        int height = Height;

        if (width > maxWidth || height > maxHeight)
        {
            double scale = Math.Min((double)maxWidth / width, (double)maxHeight / height);
            width = Math.Max(2, (int)Math.Round(width * scale));
            height = Math.Max(2, (int)Math.Round(height * scale));
        }

        // Most hardware H.264 encoders require even dimensions (4:2:0 chroma subsampling).
        width -= width % 2;
        height -= height % 2;

        return this with
        {
            Width = width,
            Height = height,
            FrameRate = Math.Clamp(FrameRate, 1, Math.Max(1, maxFrameRate)),
            BitrateKbps = Math.Clamp(BitrateKbps, 100, Math.Max(100, maxBitrateKbps)),
        };
    }
}
