// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

namespace TeamSpeak9.Core.Model;

/// <summary>
/// CRC-32 (IEEE 802.3, reversed polynomial <c>0xEDB88320</c>) - the checksum TeamSpeak uses to
/// name uploaded icons.
/// </summary>
/// <remarks>
/// An icon uploaded as <c>/icon_&lt;crc32&gt;</c> in the virtual server's internal channel is
/// referenced by that same number in <c>i_icon_id</c> and <c>virtualserver_icon_id</c>.
/// Verified round-trip against tsserver 6.0.0-beta12.1.
/// </remarks>
public static class Crc32
{
    private const uint Polynomial = 0xEDB88320u;
    private const uint Seed = 0xFFFFFFFFu;

    private static readonly uint[] Table = BuildTable();

    private static uint[] BuildTable()
    {
        var table = new uint[256];
        for (uint i = 0; i < 256; i++)
        {
            uint c = i;
            for (int k = 0; k < 8; k++)
                c = (c & 1) != 0 ? Polynomial ^ (c >> 1) : c >> 1;
            table[i] = c;
        }

        return table;
    }

    /// <summary>Computes the CRC-32 of <paramref name="data"/>.</summary>
    public static uint Compute(ReadOnlySpan<byte> data)
    {
        uint crc = Seed;
        foreach (byte b in data)
            crc = Table[(crc ^ b) & 0xFF] ^ (crc >> 8);
        return crc ^ Seed;
    }

    /// <summary>Computes the icon id TeamSpeak will use for the given icon file contents.</summary>
    public static IconId ComputeIconId(ReadOnlySpan<byte> iconFile) => IconId.FromUnsigned(Compute(iconFile));
}
