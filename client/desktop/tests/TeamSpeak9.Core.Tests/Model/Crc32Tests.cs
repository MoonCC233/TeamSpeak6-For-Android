// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using TeamSpeak9.Core.Model;

namespace TeamSpeak9.Core.Tests.Model;

public class Crc32Tests
{
    [Fact]
    public void MatchesTheKnownCheckValue()
    {
        // The standard CRC-32 check value: "123456789" -> 0xCBF43926.
        var data = System.Text.Encoding.ASCII.GetBytes("123456789");
        Assert.Equal(0xCBF43926u, Crc32.Compute(data));
    }

    [Fact]
    public void EmptyInputIsZero()
    {
        Assert.Equal(0u, Crc32.Compute(ReadOnlySpan<byte>.Empty));
    }

    [Fact]
    public void ComputeIconIdFeedsStraightIntoTheFileName()
    {
        var data = System.Text.Encoding.ASCII.GetBytes("123456789");
        var id = Crc32.ComputeIconId(data);

        Assert.Equal(0xCBF43926u, id.Unsigned);
        Assert.Equal("icon_3421780262", id.ToFileName());
    }
}
