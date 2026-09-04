// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using System.Runtime.ExceptionServices;

namespace TeamSpeak9.App.Tests.Infrastructure;

/// <summary>
/// Runs a test body on a dedicated STA thread.
/// </summary>
/// <remarks>
/// xunit 2.x runs everything on MTA threads, but every WPF type used here
/// (<see cref="System.Windows.ResourceDictionary" />, <see cref="System.Windows.Media.Imaging.BitmapImage" />
/// and anything else deriving from <c>DispatcherObject</c>) requires STA. Wrapping the
/// body instead of writing a custom <c>FactAttribute</c> keeps this dependency-free.
/// </remarks>
internal static class Sta
{
    /// <summary>Runs <paramref name="action" /> on a fresh STA thread and rethrows its exception in place.</summary>
    internal static void Run(Action action)
    {
        ArgumentNullException.ThrowIfNull(action);

        ExceptionDispatchInfo? failure = null;
        var thread = new Thread(() =>
        {
            try
            {
                action();
            }
            catch (Exception ex)
            {
                failure = ExceptionDispatchInfo.Capture(ex);
            }
        })
        {
            Name = "TeamSpeak9.App.Tests.Sta",
            IsBackground = true,
        };

        thread.SetApartmentState(ApartmentState.STA);
        thread.Start();
        thread.Join();

        failure?.Throw();
    }

    /// <summary>Runs <paramref name="func" /> on a fresh STA thread and returns its result.</summary>
    internal static T Run<T>(Func<T> func)
    {
        ArgumentNullException.ThrowIfNull(func);

        T result = default!;

        // The braces matter: an expression-bodied lambda would bind to this same overload and
        // recurse until the stack runs out.
        Run(() => { result = func(); });
        return result;
    }
}
