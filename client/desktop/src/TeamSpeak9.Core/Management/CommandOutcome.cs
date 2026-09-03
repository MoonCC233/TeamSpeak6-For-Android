// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using TeamSpeak9.Core.Connection;
using TSLib.Helper;
using TSLib.Messages;

namespace TeamSpeak9.Core.Management;

/// <summary>
/// Result of a management command: either success, or a message ready to be shown to a user.
/// </summary>
/// <remarks>
/// The management layer never surfaces <see cref="CommandError"/> to the UI, because TSLib's
/// messages are English and terse. Everything funnels through
/// <see cref="CommandErrorText.Describe(CommandError?)"/> instead.
/// </remarks>
public readonly record struct CommandOutcome(bool Ok, string Message)
{
    public static CommandOutcome Success => new(true, string.Empty);

    public static CommandOutcome Fail(string message) => new(false, message ?? string.Empty);

    /// <summary>
    /// Wraps a TSLib result. "Nothing changed" answers count as success, because a user who
    /// re-saves an unmodified dialog has not made a mistake.
    /// </summary>
    public static CommandOutcome From(E<CommandError> result)
    {
        if (result.Ok)
            return Success;

        var error = result.Error;
        return CommandErrorText.IsNoOp(error) ? Success : Fail(CommandErrorText.Describe(error));
    }
}

/// <summary>A <see cref="CommandOutcome"/> that also carries a value on success.</summary>
public readonly record struct CommandOutcome<T>(bool Ok, T? Value, string Message)
    where T : notnull
{
    public static CommandOutcome<T> Success(T value) => new(true, value, string.Empty);

    public static CommandOutcome<T> Fail(string message) => new(false, default, message ?? string.Empty);

    public static CommandOutcome<T> From(R<T, CommandError> result) =>
        result.Ok ? Success(result.Value) : Fail(CommandErrorText.Describe(result.Error));

    /// <summary>Drops the value, e.g. to report a failed lookup from a void-returning method.</summary>
    public CommandOutcome WithoutValue() => Ok ? CommandOutcome.Success : CommandOutcome.Fail(Message);
}
