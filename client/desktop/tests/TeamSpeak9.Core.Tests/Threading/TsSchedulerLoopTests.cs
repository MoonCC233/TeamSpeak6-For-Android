// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using TeamSpeak9.Core.Threading;

namespace TeamSpeak9.Core.Tests.Threading;

public class TsSchedulerLoopTests
{
    [Fact]
    public async Task StartsAndReportsRunning()
    {
        await using var loop = await TsSchedulerLoop.StartAsync("test-scheduler-1");

        Assert.True(loop.IsRunning);
        Assert.NotNull(loop.Scheduler);
        Assert.False(loop.IsOnSchedulerThread);
    }

    [Fact]
    public async Task WorkRunsOnASingleDedicatedThread()
    {
        await using var loop = await TsSchedulerLoop.StartAsync("test-scheduler-2");

        int first = await loop.InvokeAsync(() => Environment.CurrentManagedThreadId);
        int second = await loop.InvokeAsync(() => Environment.CurrentManagedThreadId);

        Assert.Equal(first, second);
        Assert.NotEqual(Environment.CurrentManagedThreadId, first);
    }

    [Fact]
    public async Task VerifyOwnThreadPassesInsideTheLoop()
    {
        await using var loop = await TsSchedulerLoop.StartAsync("test-scheduler-3");

        // This is the invariant every TSLib call depends on.
        await loop.InvokeAsync(() => loop.Scheduler.VerifyOwnThread());
    }

    [Fact]
    public async Task IsOnSchedulerThreadIsTrueInsideTheLoop()
    {
        await using var loop = await TsSchedulerLoop.StartAsync("test-scheduler-4");

        bool inside = await loop.InvokeAsync(() => loop.IsOnSchedulerThread);

        Assert.True(inside);
    }

    [Fact]
    public async Task AsyncWorkIsAwaited()
    {
        await using var loop = await TsSchedulerLoop.StartAsync("test-scheduler-5");

        int value = await loop.InvokeAsync(async () =>
        {
            await Task.Delay(10);
            return 42;
        });

        Assert.Equal(42, value);
    }

    [Fact]
    public async Task ExceptionsPropagateToTheCaller()
    {
        await using var loop = await TsSchedulerLoop.StartAsync("test-scheduler-6");

        await Assert.ThrowsAsync<InvalidOperationException>(
            () => loop.InvokeAsync(() => throw new InvalidOperationException("boom")));
    }

    [Fact]
    public async Task WorkIsExecutedInOrder()
    {
        await using var loop = await TsSchedulerLoop.StartAsync("test-scheduler-7");

        var order = new List<int>();
        var tasks = Enumerable.Range(0, 50)
            .Select(i => loop.InvokeAsync(() => order.Add(i)))
            .ToArray();
        await Task.WhenAll(tasks);

        Assert.Equal(Enumerable.Range(0, 50), order);
    }

    [Fact]
    public async Task DisposeStopsTheLoopAndDrainsQueuedWork()
    {
        var loop = await TsSchedulerLoop.StartAsync("test-scheduler-8");
        bool ran = false;

        var queued = loop.InvokeAsync(() => ran = true);
        await loop.DisposeAsync();
        await queued;

        Assert.True(ran);
        Assert.False(loop.IsRunning);
    }

    [Fact]
    public async Task DisposeIsIdempotent()
    {
        var loop = await TsSchedulerLoop.StartAsync("test-scheduler-9");

        await loop.DisposeAsync();
        await loop.DisposeAsync();
    }

    [Fact]
    public async Task InvokingAfterDisposeThrowsObjectDisposed()
    {
        var loop = await TsSchedulerLoop.StartAsync("test-scheduler-10");
        await loop.DisposeAsync();

        Assert.Throws<ObjectDisposedException>(() => { _ = loop.InvokeAsync(() => { }); });
    }

    [Fact]
    public async Task NullWorkIsRejected()
    {
        await using var loop = await TsSchedulerLoop.StartAsync("test-scheduler-11");

        Assert.Throws<ArgumentNullException>(() => { _ = loop.InvokeAsync((Action)null!); });
        Assert.Throws<ArgumentNullException>(() => { _ = loop.InvokeAsync((Func<Task>)null!); });
    }
}
