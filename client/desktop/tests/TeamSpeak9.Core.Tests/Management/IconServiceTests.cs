// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using TeamSpeak9.Core.Management;
using TeamSpeak9.Core.Model;

namespace TeamSpeak9.Core.Tests.Management;

public class IconServiceTests
{
    private static readonly byte[] Png =
    [
        0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01,
    ];

    private static readonly byte[] Jpeg = [0xFF, 0xD8, 0xFF, 0xE0, 0x00];

    private static readonly byte[] Gif87 = [(byte)'G', (byte)'I', (byte)'F', (byte)'8', (byte)'7', (byte)'a', 0x00];

    private static readonly byte[] Gif89 = [(byte)'G', (byte)'I', (byte)'F', (byte)'8', (byte)'9', (byte)'a', 0x00];

    private static readonly byte[] Bmp = [(byte)'B', (byte)'M', 0x00, 0x00];

    [Fact]
    public void EveryFormatTeamSpeakRendersIsAccepted()
    {
        Assert.True(IconService.IsSupportedImage(Png));
        Assert.True(IconService.IsSupportedImage(Jpeg));
        Assert.True(IconService.IsSupportedImage(Gif87));
        Assert.True(IconService.IsSupportedImage(Gif89));
        Assert.True(IconService.IsSupportedImage(Bmp));
    }

    [Fact]
    public void AnIcoFileIsRejected()
    {
        // .ico would upload fine and then never render, which is worse than a clear refusal.
        byte[] ico = [0x00, 0x00, 0x01, 0x00, 0x01, 0x00];

        Assert.False(IconService.IsSupportedImage(ico));
    }

    [Fact]
    public void TextIsRejected()
    {
        Assert.False(IconService.IsSupportedImage("not an image at all"u8));
    }

    [Fact]
    public void ATruncatedMagicNumberIsRejected()
    {
        Assert.False(IconService.IsSupportedImage(Png.AsSpan(0, 4)));
        Assert.False(IconService.IsSupportedImage(Jpeg.AsSpan(0, 2)));
        Assert.False(IconService.IsSupportedImage(Gif89.AsSpan(0, 5)));
        Assert.False(IconService.IsSupportedImage([]));
    }

    [Fact]
    public void AGifWithAWrongVersionDigitIsRejected()
    {
        byte[] gif88 = [(byte)'G', (byte)'I', (byte)'F', (byte)'8', (byte)'8', (byte)'a', 0x00];

        Assert.False(IconService.IsSupportedImage(gif88));
    }

    [Fact]
    public void AValidIconPassesValidation()
    {
        Assert.Null(IconService.ValidateIcon(Png));
    }

    [Fact]
    public void AnEmptyFileIsRefusedBeforeUpload()
    {
        Assert.NotNull(IconService.ValidateIcon([]));
    }

    [Fact]
    public void TheServerSizeLimitIsCheckedLocally()
    {
        // i_max_icon_filesize defaults to 8192. The server only rejects after the whole file has
        // been pushed, so the check has to happen here.
        Assert.Equal(8192, IconService.MaxIconBytes);

        var atLimit = new byte[IconService.MaxIconBytes];
        Png.CopyTo(atLimit.AsSpan());
        Assert.Null(IconService.ValidateIcon(atLimit));

        var overLimit = new byte[IconService.MaxIconBytes + 1];
        Png.CopyTo(overLimit.AsSpan());
        var complaint = IconService.ValidateIcon(overLimit);

        Assert.NotNull(complaint);
        Assert.Contains("8192", complaint);
    }

    [Fact]
    public void ANonImageIsRefusedEvenWhenSmallEnough()
    {
        var complaint = IconService.ValidateIcon("hello"u8);

        Assert.NotNull(complaint);
        Assert.Contains("PNG", complaint);
    }

    [Fact]
    public void ThePredictedIdIsTheFilesCrc32()
    {
        // The id is the checksum, which is what makes re-uploading identical content idempotent.
        Assert.Equal(Crc32.ComputeIconId(Png), IconService.PredictId(Png));
        Assert.Equal(Crc32.Compute(Png), IconService.PredictId(Png).Unsigned);
    }

    [Fact]
    public void DifferentContentPredictsDifferentIds()
    {
        Assert.NotEqual(IconService.PredictId(Png), IconService.PredictId(Bmp));
    }

    [Fact]
    public void AChannelIconIsWrittenAsAPermission()
    {
        // TS6 refuses channeledit channel_icon_id in every value form.
        var wire = IconService.BuildChannelIconCommand(42, IconId.FromUnsigned(2725694802u)).ToString();

        Assert.StartsWith("channeladdperm ", wire);
        Assert.Contains("cid=42", wire);
        Assert.Contains("permsid=i_icon_id", wire);
        Assert.Contains("permvalue=2725694802", wire);
    }

    [Fact]
    public void ClearingAChannelIconDeletesThePermission()
    {
        var wire = IconService.BuildChannelIconCommand(42, IconId.None).ToString();

        Assert.StartsWith("channeldelperm ", wire);
        Assert.Contains("cid=42", wire);
        Assert.Contains("permsid=i_icon_id", wire);
        Assert.DoesNotContain("permvalue", wire);
    }

    [Fact]
    public void ServerGroupWritesCarryTheTwoExtraFieldsOnlyThatCommandNeeds()
    {
        var wire = IconService.BuildGroupIconCommand(7, IconId.FromUnsigned(200u), isServerGroup: true).ToString();

        Assert.StartsWith("servergroupaddperm ", wire);
        Assert.Contains("sgid=7", wire);
        Assert.Contains("permvalue=200", wire);
        Assert.Contains("permnegated=0", wire);
        Assert.Contains("permskip=0", wire);
    }

    [Fact]
    public void ChannelGroupWritesUseTheirOwnKeyAndNoExtraFields()
    {
        var wire = IconService.BuildGroupIconCommand(7, IconId.FromUnsigned(200u), isServerGroup: false).ToString();

        Assert.StartsWith("channelgroupaddperm ", wire);
        Assert.Contains("cgid=7", wire);
        Assert.DoesNotContain("permnegated", wire);
        Assert.DoesNotContain("permskip", wire);
    }

    [Theory]
    [InlineData(true, "servergroupdelperm")]
    [InlineData(false, "channelgroupdelperm")]
    public void ClearingAGroupIconDeletesThePermission(bool isServerGroup, string expectedCommand)
    {
        var wire = IconService.BuildGroupIconCommand(7, IconId.None, isServerGroup).ToString();

        Assert.StartsWith(expectedCommand + " ", wire);
        Assert.DoesNotContain("permvalue", wire);
    }

    [Fact]
    public void IconIdsAreAlwaysWrittenUnsigned()
    {
        // serveredit answers 1540 "convert error" for the signed form, and the group commands are
        // kept consistent with it.
        var high = IconId.FromSigned(-1569272494);

        Assert.Equal(2725694802u, high.Unsigned);
        Assert.Contains("permvalue=2725694802", IconService.BuildChannelIconCommand(1, high).ToString());
        Assert.Contains("permvalue=2725694802", IconService.BuildGroupIconCommand(1, high, true).ToString());
    }
}
