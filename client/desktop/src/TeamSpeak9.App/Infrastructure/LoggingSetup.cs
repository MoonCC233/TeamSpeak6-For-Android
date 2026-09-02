// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using System.Text;
using NLog;
using NLog.Config;
using NLog.Targets;
using TeamSpeak9.Core.Settings;
// The WPF SDK's implicit usings bring in System.Windows.Shapes.Path, which shadows System.IO.Path.
using IoPath = System.IO.Path;

namespace TeamSpeak9.App.Infrastructure;

/// <summary>
/// Configures NLog programmatically.
/// </summary>
/// <remarks>
/// The vendored TSLib logs straight through <c>NLog.LogManager.GetCurrentClassLogger()</c>, so
/// NLog is the sink either way; the app's own <c>ILogger&lt;T&gt;</c> is routed into the same
/// targets so one file holds both sides of a connection problem. The log directory is only known
/// at runtime (<see cref="AppPaths.LogDirectory"/>), which rules out a static NLog.config.
/// </remarks>
internal static class LoggingSetup
{
    private const string Layout =
        "${longdate}|${level:uppercase=true:padding=-5}|${logger:shortName=true}|${message}${onexception:${newline}${exception:format=tostring}}";

    public static void Configure(AppPaths paths, string minimumLevel)
    {
        ArgumentNullException.ThrowIfNull(paths);
        paths.EnsureCreated();

        var config = new LoggingConfiguration();

        var file = new FileTarget("file")
        {
            FileName = IoPath.Combine(paths.LogDirectory, "teamspeak9.log"),
            ArchiveFileName = IoPath.Combine(paths.LogDirectory, "teamspeak9.{#}.log"),
            ArchiveAboveSize = 8 * 1024 * 1024,
            MaxArchiveFiles = 5,
            Layout = Layout,
            Encoding = new UTF8Encoding(false),
            KeepFileOpen = true,
            ConcurrentWrites = false,
        };

        var debug = new DebuggerTarget("debugger") { Layout = Layout };

        config.AddTarget(file);
        config.AddTarget(debug);

        var level = Parse(minimumLevel);
        config.AddRule(level, LogLevel.Fatal, file);
        config.AddRule(level, LogLevel.Fatal, debug);

        // TSLib is chatty at Debug during handshakes; keep it one notch above the app level
        // unless the user explicitly asked for Trace.
        if (level < LogLevel.Debug)
        {
            config.LoggingRules.Insert(0, new LoggingRule("TSLib.*", LogLevel.Trace, LogLevel.Fatal, file));
        }

        LogManager.Configuration = config;
    }

    public static void Shutdown() => LogManager.Shutdown();

    private static LogLevel Parse(string name)
    {
        try
        {
            return LogLevel.FromString(string.IsNullOrWhiteSpace(name) ? "Info" : name);
        }
        catch (ArgumentException)
        {
            return LogLevel.Info;
        }
    }
}
