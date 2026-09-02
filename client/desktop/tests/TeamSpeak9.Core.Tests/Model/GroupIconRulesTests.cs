// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using TeamSpeak9.Core.Model;

namespace TeamSpeak9.Core.Tests.Model;

public class GroupIconRulesTests
{
    private static readonly IconId Uploaded = IconId.FromUnsigned(2725694802u);
    private static readonly IconId BuiltIn = IconId.FromUnsigned(200u);

    [Theory]
    [InlineData(GroupKind.Regular, true)]
    [InlineData(GroupKind.Template, false)]
    [InlineData(GroupKind.Query, false)]
    public void OnlyRegularGroupsTakeCustomIcons(GroupKind kind, bool expected)
    {
        Assert.Equal(expected, GroupIconRules.AllowsCustomIcon(kind));
        Assert.Equal(expected, GroupIconRules.CanAssign(kind, Uploaded));
    }

    [Theory]
    [InlineData(GroupKind.Regular)]
    [InlineData(GroupKind.Template)]
    [InlineData(GroupKind.Query)]
    public void EveryGroupKindTakesBuiltInIcons(GroupKind kind)
    {
        Assert.True(GroupIconRules.CanAssign(kind, BuiltIn));
        Assert.True(GroupIconRules.CanAssign(kind, IconId.None));
        Assert.Null(GroupIconRules.DescribeRejection(kind, BuiltIn));
    }

    [Theory]
    [InlineData(GroupKind.Template)]
    [InlineData(GroupKind.Query)]
    public void RejectionIsExplainedToTheUser(GroupKind kind)
    {
        var reason = GroupIconRules.DescribeRejection(kind, Uploaded);

        Assert.NotNull(reason);
        Assert.Contains("1000", reason);
    }

    [Fact]
    public void RegularGroupsNeverProduceARejection()
    {
        Assert.Null(GroupIconRules.DescribeRejection(GroupKind.Regular, Uploaded));
    }

    [Fact]
    public void GroupKindValuesMatchTheWireProtocol()
    {
        // sgid 1-2 are query groups, 3-5 template, 6-8 regular on a fresh server.
        Assert.Equal(0, (int)GroupKind.Template);
        Assert.Equal(1, (int)GroupKind.Regular);
        Assert.Equal(2, (int)GroupKind.Query);
    }
}
