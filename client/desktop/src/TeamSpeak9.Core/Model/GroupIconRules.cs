// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

namespace TeamSpeak9.Core.Model;

/// <summary>
/// The <c>type</c> field of a server group or channel group.
/// </summary>
/// <remarks>
/// This is not cosmetic: it decides whether a custom (uploaded) icon can be assigned to the
/// group at all. See <see cref="GroupIconRules"/>.
/// </remarks>
public enum GroupKind
{
    /// <summary>Template group. Copied into new virtual servers; on a fresh server these are sgid 3-5 and cgid 1-4.</summary>
    Template = 0,

    /// <summary>Regular (instance) group. On a fresh server these are sgid 6-8 and cgid 5-8.</summary>
    Regular = 1,

    /// <summary>ServerQuery group. On a fresh server these are sgid 1-2.</summary>
    Query = 2,
}

/// <summary>
/// Which icon values a group will actually accept, by group kind.
/// </summary>
/// <remarks>
/// <para>
/// Writing <c>i_icon_id</c> &gt;= 1000 (or any negative value) to a template or query group fails
/// with error 2560. The server reports that as <c>invalid group ID</c>, which is misleading -
/// the group id is fine, the <i>value</i> is out of range. Binary search put the boundary
/// cleanly between 999 and 1000, and the same limit applies to both channel groups and server
/// groups of those kinds. Regular groups accept the full 32-bit range.
/// </para>
/// <para>
/// The practical reading is that template and query groups may only reference built-in icon
/// numbers, so an uploaded icon (whose id is a CRC-32, essentially always &gt;= 1000) can only be
/// attached to a <see cref="GroupKind.Regular"/> group.
/// </para>
/// <para>Measured against tsserver 6.0.0-beta12.1; see docs/desktop/tslib-ts6-compat.md §4.4.</para>
/// </remarks>
public static class GroupIconRules
{
    /// <summary>Whether groups of this kind accept arbitrary (uploaded) icon ids.</summary>
    public static bool AllowsCustomIcon(GroupKind kind) => kind == GroupKind.Regular;

    /// <summary>Whether <paramref name="icon"/> can be written to a group of the given kind.</summary>
    public static bool CanAssign(GroupKind kind, IconId icon)
        => AllowsCustomIcon(kind) || icon.IsAssignableToGroupTemplate;

    /// <summary>
    /// A user-facing reason why <paramref name="icon"/> cannot be assigned, or <c>null</c> when it can.
    /// </summary>
    public static string? DescribeRejection(GroupKind kind, IconId icon)
    {
        if (CanAssign(kind, icon))
            return null;

        string kindName = kind switch
        {
            GroupKind.Template => "模板组",
            GroupKind.Query => "ServerQuery 组",
            _ => "该组",
        };

        return $"{kindName}只能使用编号小于 {IconId.GroupTemplateIconLimit} 的内置图标，无法使用上传的自定义图标。";
    }
}
