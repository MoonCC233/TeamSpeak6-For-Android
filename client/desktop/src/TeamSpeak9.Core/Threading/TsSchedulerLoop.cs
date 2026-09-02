// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using TSLib.Scheduler;

namespace TeamSpeak9.Core.Threading;

/// <summary>
/// Owns the dedicated thread that every TSLib call has to run on.
/// </summary>
/// <remarks>
/// <para>
/// TSLib is single threaded by design: <c>TsFullClient</c> calls
/// <c>DedicatedTaskScheduler.VerifyOwnThread()</c> and throws
/// <see cref="TaskSchedulerException"/> when touched from anywhere else. The scheduler is also
/// its own message loop, so it must be created by
/// <see cref="DedicatedTaskScheduler.FromCurrentThread(Action)"/> on a thread we dedicate to it,
/// and that call only returns once the scheduler is disposed.
/// </para>
/// <para>
/// Forgetting to dispose leaves the loop spinning and keeps the process alive, which is why
/// <see cref="DisposeAsync"/> both stops the loop and waits for the thread to exit.
/// </para>
/// </remarks>
public sealed class TsSchedulerLoop : IAsyncDisposable
{
    private static readonly TimeSpan ShutdownTimeout = TimeSpan.FromSeconds(5);

    private readonly Thread thread;
    private readonly TaskCompletionSource<DedicatedTaskScheduler> ready =
        new(TaskCreationOptions.RunContinuationsAsynchronously);
    private readonly TaskCompletionSource stopped =
        new(TaskCreationOptions.RunContinuationsAsynchronously);

    private DedicatedTaskScheduler? scheduler;
    private int disposed;

    private TsSchedulerLoop(string threadName)
    {
        thread = new Thread(RunLoop)
        {
            Name = threadName,
            IsBackground = true,
        };
    }

    /// <summary>Starts the loop and returns once the scheduler is accepting work.</summary>
    public static async Task<TsSchedulerLoop> StartAsync(string threadName = "TeamSpeak9.TsScheduler")
    {
        var loop = new TsSchedulerLoop(threadName);
        loop.thread.Start();
        loop.scheduler = await loop.ready.Task.ConfigureAwait(false);
        return loop;
    }

    /// <summary>The scheduler itself, for the few TSLib APIs that take one.</summary>
    public DedicatedTaskScheduler Scheduler =>
        scheduler ?? throw new InvalidOperationException("调度器尚未启动。");

    public bool IsRunning => disposed == 0 && !stopped.Task.IsCompleted;

    /// <summary>True when the caller already is the scheduler thread.</summary>
    public bool IsOnSchedulerThread => Thread.CurrentThread == thread;

    public Task InvokeAsync(Action action)
    {
        ArgumentNullException.ThrowIfNull(action);
        return Active().Invoke(action);
    }

    public Task<T> InvokeAsync<T>(Func<T> action)
    {
        ArgumentNullException.ThrowIfNull(action);
        return Active().Invoke(action);
    }

    public Task InvokeAsync(Func<Task> action)
    {
        ArgumentNullException.ThrowIfNull(action);
        return Active().InvokeAsync(action);
    }

    public Task<T> InvokeAsync<T>(Func<Task<T>> action)
    {
        ArgumentNullException.ThrowIfNull(action);
        return Active().InvokeAsync(action);
    }

    public async ValueTask DisposeAsync()
    {
        if (Interlocked.Exchange(ref disposed, 1) != 0)
        {
            await stopped.Task.ConfigureAwait(false);
            return;
        }

        var sched = scheduler;
        if (sched is null)
        {
            // Never became ready; nothing is spinning.
            return;
        }

        // Queued rather than called directly so pending work drains first. Dispose only
        // completes the queue, so the loop exits after the last item.
        _ = sched.Invoke(sched.Dispose);

        var timeout = Task.Delay(ShutdownTimeout);
        if (await Task.WhenAny(stopped.Task, timeout).ConfigureAwait(false) == timeout)
        {
            // A stuck TSLib call would otherwise hang shutdown; the thread is a background
            // thread, so the process can still exit.
            sched.Dispose();
        }
    }

    private DedicatedTaskScheduler Active()
    {
        ObjectDisposedException.ThrowIf(disposed != 0, this);
        return Scheduler;
    }

    private void RunLoop()
    {
        try
        {
            DedicatedTaskScheduler.FromCurrentThread(() =>
                ready.TrySetResult((DedicatedTaskScheduler)TaskScheduler.Current));
        }
        catch (Exception ex)
        {
            ready.TrySetException(ex);
        }
        finally
        {
            // Guards against the loop exiting before the root action ever ran, which would
            // otherwise leave StartAsync awaiting forever.
            if (!ready.Task.IsCompleted)
                ready.TrySetException(new InvalidOperationException("调度器循环在就绪前退出。"));

            stopped.TrySetResult();
        }
    }
}
