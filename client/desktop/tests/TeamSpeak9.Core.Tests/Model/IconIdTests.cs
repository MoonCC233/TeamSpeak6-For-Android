// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using TeamSpeak9.Core.Model;

namespace TeamSpeak9.Core.Tests.Model;

public class IconIdTests
{
    // The value observed on the live TS6 server for the probe's uploaded icon:
    // channelinfo reported 2725694802, channelpermlist reported -1569272494, and one
    // notification rendered it as 18446744072140279122.
    private const uint LiveUnsigned = 2725694802u;
    private const int LiveSigned = -1569272494;
    private const string LiveWire64 = "18446744072140279122";

    [Fact]
    public void SignedAndUnsignedAreTwoViewsOfOneValue()
    {
        var id = IconId.FromUnsigned(LiveUnsigned);

        Assert.Equal(LiveUnsigned, id.Unsigned);
        Assert.Equal(LiveSigned, id.Signed);
        Assert.Equal(id, IconId.FromSigned(LiveSigned));
    }

    [Theory]
    [InlineData("2725694802")]
    [InlineData("-1569272494")]
    [InlineData(LiveWire64)]
    [InlineData("  2725694802  ")]
    public void ParseAcceptsEveryFormTheServerEmits(string wire)
    {
        Assert.Equal(IconId.FromUnsigned(LiveUnsigned), IconId.Parse(wire));
    }

    [Fact]
    public void WritesUseTheUnsignedForm()
    {
        // serveredit rejects the signed form with error 1540, so this must never be negative.
        Assert.Equal("2725694802", IconId.FromSigned(LiveSigned).ToWireString());
        Assert.DoesNotContain('-', IconId.FromSigned(int.MinValue).ToWireString());
    }

    [Theory]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData(null)]
    [InlineData("abc")]
    [InlineData("12.5")]
    [InlineData("-99999999999")]
    public void TryParseRejectsGarbage(string? wire)
    {
        Assert.False(IconId.TryParse(wire, out _));
    }

    [Fact]
    public void ParseThrowsOnGarbage()
    {
        Assert.Throws<FormatException>(() => IconId.Parse("not-a-number"));
    }

    [Fact]
    public void NoneIsZeroInBothViews()
    {
        Assert.True(IconId.None.IsNone);
        Assert.Equal(0u, IconId.None.Unsigned);
        Assert.Equal(0, IconId.None.Signed);
        Assert.False(IconId.None.IsBuiltIn);
        Assert.Equal("none", IconId.None.ToString());
    }

    [Theory]
    // The factory icon numbers read off servergrouplist / channelgrouplist.
    [InlineData(100u, true)]
    [InlineData(200u, true)]
    [InlineData(300u, true)]
    [InlineData(500u, true)]
    [InlineData(600u, true)]
    [InlineData(999u, true)]
    [InlineData(0u, false)]
    [InlineData(1000u, false)]
    [InlineData(LiveUnsigned, false)]
    public void BuiltInIconsAreThreeDigitNumbers(uint value, bool expected)
    {
        Assert.Equal(expected, IconId.FromUnsigned(value).IsBuiltIn);
    }

    [Theory]
    [InlineData(0u, true)]
    [InlineData(999u, true)]
    [InlineData(1000u, false)]
    [InlineData(LiveUnsigned, false)]
    public void TemplateGroupLimitSitsBetween999And1000(uint value, bool expected)
    {
        Assert.Equal(expected, IconId.FromUnsigned(value).IsAssignableToGroupTemplate);
    }

    [Fact]
    public void NegativeValuesAreNotAssignableToTemplateGroups()
    {
        Assert.False(IconId.FromSigned(LiveSigned).IsAssignableToGroupTemplate);
    }

    [Fact]
    public void FileNameMatchesTheFileTransferConvention()
    {
        Assert.Equal("icon_2725694802", IconId.FromUnsigned(LiveUnsigned).ToFileName());
    }

    [Fact]
    public void EqualityIgnoresWhichViewBuiltTheValue()
    {
        Assert.True(IconId.FromSigned(LiveSigned) == IconId.FromUnsigned(LiveUnsigned));
        Assert.False(IconId.FromUnsigned(1u) == IconId.None);
        Assert.True(IconId.FromUnsigned(1u) != IconId.None);
        Assert.Equal(
            IconId.FromUnsigned(LiveUnsigned).GetHashCode(),
            IconId.FromSigned(LiveSigned).GetHashCode());
    }
}
