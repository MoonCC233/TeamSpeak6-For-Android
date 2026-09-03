// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using TeamSpeak9.Core.Management;
using TSLib;
using TSLib.Messages;

namespace TeamSpeak9.Core.Tests.Management;

public class CommandOutcomeTests
{
    private static CommandError Error(TsErrorCode id, string message = "boom") =>
        new() { Id = id, Message = message };

    [Fact]
    public void SuccessCarriesNoMessage()
    {
        Assert.True(CommandOutcome.Success.Ok);
        Assert.Equal(string.Empty, CommandOutcome.Success.Message);
    }

    [Fact]
    public void FailureKeepsItsMessage()
    {
        var outcome = CommandOutcome.Fail("坏了");

        Assert.False(outcome.Ok);
        Assert.Equal("坏了", outcome.Message);
    }

    [Fact]
    public void FailureTolueratesANullMessage()
    {
        Assert.Equal(string.Empty, CommandOutcome.Fail(null!).Message);
    }

    [Fact]
    public void AnOkResultBecomesSuccess()
    {
        Assert.True(CommandOutcome.From(E<CommandError>.OkR).Ok);
    }

    [Theory]
    [InlineData(TsErrorCode.channel_already_in)]
    [InlineData(TsErrorCode.database_no_modifications)]
    public void NoOpErrorsAreFoldedIntoSuccess(TsErrorCode id)
    {
        // Re-saving an unmodified dialog, or moving a channel to where it already is, is not a
        // mistake the user should be told about.
        var outcome = CommandOutcome.From(E<CommandError>.Err(Error(id)));

        Assert.True(outcome.Ok);
        Assert.Equal(string.Empty, outcome.Message);
    }

    [Fact]
    public void RealErrorsAreTranslated()
    {
        var outcome = CommandOutcome.From(E<CommandError>.Err(Error(TsErrorCode.channel_name_inuse)));

        Assert.False(outcome.Ok);
        Assert.Contains("已被占用", outcome.Message);
    }

    [Fact]
    public void GenericSuccessCarriesTheValue()
    {
        var outcome = CommandOutcome<ulong>.Success(42);

        Assert.True(outcome.Ok);
        Assert.Equal(42u, outcome.Value);
        Assert.Equal(string.Empty, outcome.Message);
    }

    [Fact]
    public void GenericFailureHasNoValue()
    {
        var outcome = CommandOutcome<ulong>.Fail("失败");

        Assert.False(outcome.Ok);
        Assert.Equal(default, outcome.Value);
        Assert.Equal("失败", outcome.Message);
    }

    [Fact]
    public void GenericFromUnwrapsATslibResult()
    {
        Assert.Equal(7u, CommandOutcome<ulong>.From(R<ulong, CommandError>.OkR(7u)).Value);

        var failed = CommandOutcome<ulong>.From(
            R<ulong, CommandError>.Err(Error(TsErrorCode.channel_invalid_id)));

        Assert.False(failed.Ok);
        Assert.Contains("频道不存在", failed.Message);
    }

    [Fact]
    public void WithoutValueKeepsTheOutcomeButDropsThePayload()
    {
        Assert.True(CommandOutcome<ulong>.Success(1).WithoutValue().Ok);

        var failed = CommandOutcome<ulong>.Fail("没了").WithoutValue();

        Assert.False(failed.Ok);
        Assert.Equal("没了", failed.Message);
    }

    [Fact]
    public void ACreateThatSucceedsWithAWarningStaysSuccessful()
    {
        // ChannelService.CreateAsync uses this shape when the channel was created but the follow-up
        // banner edit failed.
        var outcome = new CommandOutcome<ulong>(true, 99u, "横幅设置失败");

        Assert.True(outcome.Ok);
        Assert.Equal(99u, outcome.Value);
        Assert.NotEqual(string.Empty, outcome.Message);
    }
}
