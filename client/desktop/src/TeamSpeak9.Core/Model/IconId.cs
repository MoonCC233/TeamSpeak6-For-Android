// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

namespace TeamSpeak9.Core.Model;

/// <summary>
/// A TeamSpeak icon id as it appears on the wire.
/// </summary>
/// <remarks>
/// <para>
/// TeamSpeak stores icon ids as a CRC-32 of the icon file, which routinely exceeds
/// <see cref="int.MaxValue"/>. The server is inconsistent about how it renders that value:
/// </para>
/// <list type="bullet">
/// <item><description><c>channelinfo</c> / <c>channellist -icon</c> / <c>serverinfo</c> report it <b>unsigned</b>.</description></item>
/// <item><description><c>channelpermlist</c> / <c>servergrouplist</c> / <c>channelgrouplist</c> report it <b>signed</b> (two's complement).</description></item>
/// <item><description>Notifications sometimes render it as a 64-bit two's complement value, e.g. <c>18446744072140279122</c>.</description></item>
/// </list>
/// <para>
/// TSLib aliases <c>IconHash</c> to <see cref="int"/>, so signed is the native representation
/// there. This type keeps both views in one place so no call site has to remember which
/// form a given command expects. Writes should always use <see cref="ToWireString"/>, which
/// emits the unsigned form - the only form every write path accepts.
/// </para>
/// <para>Measured against tsserver 6.0.0-beta12.1; see docs/desktop/tslib-ts6-compat.md §4.6.</para>
/// </remarks>
public readonly struct IconId : IEquatable<IconId>
{
    /// <summary>No icon.</summary>
    public static readonly IconId None = new(0u);

    private readonly uint _value;

    private IconId(uint value) => _value = value;

    /// <summary>The unsigned view, as reported by <c>channelinfo</c> and <c>serverinfo</c>.</summary>
    public uint Unsigned => _value;

    /// <summary>The signed view, as reported by <c>channelpermlist</c> and the group list commands, and as TSLib models it.</summary>
    public int Signed => unchecked((int)_value);

    /// <summary>True when this id means "no icon".</summary>
    public bool IsNone => _value == 0u;

    /// <summary>
    /// True when this id is a built-in icon number rather than an uploaded file's CRC-32.
    /// </summary>
    /// <remarks>
    /// Built-in icons are small three digit numbers (observed factory values: 100, 200, 300,
    /// 500, 600). This matters because template and query groups reject
    /// <c>i_icon_id</c> values of 1000 or above - see <see cref="IsAssignableToGroupTemplate"/>.
    /// </remarks>
    public bool IsBuiltIn => _value is > 0u and < GroupTemplateIconLimit;

    /// <summary>
    /// The exclusive upper bound for <c>i_icon_id</c> on group types 0 (template) and 2 (query).
    /// </summary>
    /// <remarks>
    /// Writing 1000 or above to those group types fails with error 2560, which the server
    /// misleadingly reports as <c>invalid group ID</c>. Values 0..999 are accepted.
    /// Type 1 (instance) groups accept the full 32-bit range.
    /// </remarks>
    public const uint GroupTemplateIconLimit = 1000u;

    /// <summary>
    /// Whether this id can be written to a group whose <c>type</c> is 0 (template) or 2 (query).
    /// </summary>
    public bool IsAssignableToGroupTemplate => _value < GroupTemplateIconLimit;

    public static IconId FromUnsigned(uint value) => new(value);

    public static IconId FromSigned(int value) => new(unchecked((uint)value));

    /// <summary>
    /// Parses any of the three forms tsserver emits: unsigned decimal, signed decimal
    /// (leading <c>-</c>), or a 64-bit two's complement value.
    /// </summary>
    public static IconId Parse(string? text)
    {
        if (!TryParse(text, out var id))
            throw new FormatException($"'{text}' is not a valid icon id.");
        return id;
    }

    /// <inheritdoc cref="Parse(string?)"/>
    public static bool TryParse(string? text, out IconId id)
    {
        id = None;
        if (string.IsNullOrWhiteSpace(text))
            return false;

        var span = text.AsSpan().Trim();

        if (span[0] == '-')
        {
            if (!long.TryParse(span, out var signed) || signed < int.MinValue)
                return false;
            id = FromSigned((int)signed);
            return true;
        }

        // Unsigned decimal, or the 64-bit two's complement form the server uses in
        // some notifications (e.g. 18446744072140279122 for -1569272494).
        if (!ulong.TryParse(span, out var raw))
            return false;

        id = FromSigned(unchecked((int)raw));
        return true;
    }

    /// <summary>
    /// The form to use when writing. Unsigned decimal is the only representation accepted by
    /// every write path (<c>serveredit</c> rejects the signed form with error 1540).
    /// </summary>
    public string ToWireString() => _value.ToString(System.Globalization.CultureInfo.InvariantCulture);

    /// <summary>The file transfer path of the uploaded icon, e.g. <c>/icon_3141592653</c>.</summary>
    /// <remarks>Icons live in the virtual server's internal channel (<c>cid=0</c>) under <c>/icons</c>.</remarks>
    public string ToFileName() => "icon_" + ToWireString();

    public bool Equals(IconId other) => _value == other._value;

    public override bool Equals(object? obj) => obj is IconId other && Equals(other);

    public override int GetHashCode() => _value.GetHashCode();

    public override string ToString() => IsNone ? "none" : ToWireString();

    public static bool operator ==(IconId left, IconId right) => left.Equals(right);

    public static bool operator !=(IconId left, IconId right) => !left.Equals(right);
}
