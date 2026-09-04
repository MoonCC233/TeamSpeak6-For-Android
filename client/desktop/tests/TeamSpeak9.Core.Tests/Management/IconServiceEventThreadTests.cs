// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using Microsoft.Extensions.Logging.Abstractions;
using TeamSpeak9.Core.Connection;
using TeamSpeak9.Core.Management;
using TeamSpeak9.Core.Model;
using TeamSpeak9.Core.Settings;
using TeamSpeak9.Core.Tests.Fakes;
using TeamSpeak9.Core.Threading;

namespace TeamSpeak9.Core.Tests.Management;

/// <summary>
/// Which thread <see cref="IconService.IconCached"/> reaches its handlers on.
/// </summary>
/// <remarks>
/// Cache writes finish on whatever thread completed the file transfer, and every handler in the app
/// walks WPF-bound collections. Raising the event there throws
/// <see cref="InvalidOperationException"/> from WPF, so the marshalling is a correctness
/// requirement, not a nicety.
/// </remarks>
public class IconServiceEventThreadTests
{
    private static readonly IconId SomeIcon = IconId.FromUnsigned(2725694802u);

    private sealed class Fixture : IAsyncDisposable
    {
        private static int loopCounter;

        private readonly TempDirectory root;
        private readonly TsSchedulerLoop loop;
        private readonly TsConnection connection;

        private Fixture(TempDirectory root, TsSchedulerLoop loop, TsConnection connection, RecordingUiDispatcher ui)
        {
            this.root = root;
            this.loop = loop;
            this.connection = connection;

            Ui = ui;
            Service = new IconService(connection, new AppPaths(root.Path), ui, NullLogger<IconService>.Instance);
        }

        public RecordingUiDispatcher Ui { get; }

        public IconService Service { get; }

        /// <param name="uiThread">
        /// The thread the dispatcher treats as the UI thread. Defaults to the calling thread, i.e.
        /// the test thread stands in for the dispatcher thread.
        /// </param>
        public static async Task<Fixture> CreateAsync(Thread? uiThread = null)
        {
            var root = new TempDirectory();
            var loop = await TsSchedulerLoop.StartAsync(
                $"core-tests-icon-events-{Interlocked.Increment(ref loopCounter)}");

            var ui = new RecordingUiDispatcher(uiThread);
            var connection = new TsConnection(loop, ui, NullLogger<TsConnection>.Instance);

            return new Fixture(root, loop, connection, ui);
        }

        public async ValueTask DisposeAsync()
        {
            await connection.DisposeAsync();
            await loop.DisposeAsync();
            root.Dispose();
        }
    }

    private static async Task WithService(Func<Fixture, Task> body, Thread? uiThread = null)
    {
        await using var fixture = await Fixture.CreateAsync(uiThread);

        // Decided here rather than inside CreateAsync: an async test resumes on whatever pool
        // thread the runner hands it, so the thread that built the fixture is not necessarily the
        // one that runs the assertions.
        fixture.Ui.UiThread = uiThread ?? Thread.CurrentThread;

        await body(fixture);
    }

    [Fact]
    public async Task RaisingFromTheUiThreadRunsHandlersInline()
    {
        await WithService(fixture =>
        {
            var seen = new List<IconId>();
            fixture.Service.IconCached += (_, id) => seen.Add(id);

            fixture.Service.RaiseIconCached(SomeIcon);

            Assert.Equal([SomeIcon], seen);
            Assert.Equal(0, fixture.Ui.PostCount);
            return Task.CompletedTask;
        });
    }

    [Fact]
    public async Task RaisingFromAnotherThreadDefersToTheDispatcher()
    {
        // A thread that is not the current one, so IsOnUiThread is false without needing to
        // actually run on a second thread.
        var otherThread = new Thread(() => { });

        await WithService(fixture =>
        {
            var seen = new List<IconId>();
            fixture.Service.IconCached += (_, id) => seen.Add(id);

            fixture.Service.RaiseIconCached(SomeIcon);

            Assert.Empty(seen);
            Assert.Equal(1, fixture.Ui.PostCount);

            fixture.Ui.Drain();

            Assert.Equal([SomeIcon], seen);
            return Task.CompletedTask;
        }, otherThread);
    }

    /// <remarks>
    /// The point of the whole exercise: the handler must observe the dispatcher thread, not the
    /// transfer thread.
    /// </remarks>
    [Fact]
    public async Task TheHandlerObservesTheThreadThatDrainsTheDispatcher()
    {
        await using var fixture = await Fixture.CreateAsync();
        fixture.Ui.UiThread = Thread.CurrentThread;
        var uiThreadId = Environment.CurrentManagedThreadId;

        int? observed = null;
        fixture.Service.IconCached += (_, _) => observed = Environment.CurrentManagedThreadId;

        // Raise from a genuine second thread, the way a completed download does.
        var raiser = new Thread(() => fixture.Service.RaiseIconCached(SomeIcon));
        raiser.Start();
        raiser.Join();

        Assert.Null(observed);
        Assert.Equal(1, fixture.Ui.PostCount);

        fixture.Ui.Drain();

        Assert.Equal(uiThreadId, observed);
    }

    [Fact]
    public async Task NothingIsQueuedWhenNobodyIsSubscribed()
    {
        var otherThread = new Thread(() => { });

        await WithService(fixture =>
        {
            fixture.Service.RaiseIconCached(SomeIcon);

            Assert.Equal(0, fixture.Ui.PostCount);
            Assert.Empty(fixture.Ui.Pending);
            return Task.CompletedTask;
        }, otherThread);
    }

    [Fact]
    public async Task UnsubscribingBeforeTheDispatcherRunsSuppressesTheCallback()
    {
        var otherThread = new Thread(() => { });

        await WithService(fixture =>
        {
            var seen = 0;
            void Handler(object? sender, IconId id) => seen++;

            fixture.Service.IconCached += Handler;
            fixture.Service.RaiseIconCached(SomeIcon);

            // Closing the shell between a download finishing and the dispatcher draining is normal;
            // the queued callback must not resurrect a detached handler.
            fixture.Service.IconCached -= Handler;
            fixture.Ui.Drain();

            Assert.Equal(0, seen);
            return Task.CompletedTask;
        }, otherThread);
    }

    [Fact]
    public async Task EveryRaiseIsDeliveredOnce()
    {
        var otherThread = new Thread(() => { });

        await WithService(fixture =>
        {
            var seen = new List<IconId>();
            fixture.Service.IconCached += (_, id) => seen.Add(id);

            var first = IconId.FromUnsigned(1u);
            var second = IconId.FromUnsigned(2u);

            fixture.Service.RaiseIconCached(first);
            fixture.Service.RaiseIconCached(second);
            fixture.Service.RaiseIconCached(first);

            Assert.Equal(3, fixture.Ui.PostCount);

            fixture.Ui.Drain();

            Assert.Equal([first, second, first], seen);
            return Task.CompletedTask;
        }, otherThread);
    }

    [Fact]
    public async Task ADispatcherIsRequired()
    {
        await using var loop = await TsSchedulerLoop.StartAsync("core-tests-icon-events-null-ui");
        await using var connection = new TsConnection(loop, ImmediateUiDispatcher.Instance, NullLogger<TsConnection>.Instance);
        using var root = new TempDirectory();

        Assert.Throws<ArgumentNullException>(() => new IconService(
            connection,
            new AppPaths(root.Path),
            null!,
            NullLogger<IconService>.Instance));
    }
}
