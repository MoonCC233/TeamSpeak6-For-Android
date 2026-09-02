// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;
using NLog.Extensions.Logging;
using TeamSpeak9.Core.Connection;
using TeamSpeak9.Core.Identity;
using TeamSpeak9.Core.Security;
using TeamSpeak9.Core.Settings;
using TeamSpeak9.Core.Threading;

namespace TeamSpeak9.App.Infrastructure;

/// <summary>
/// Composition root: everything the app resolves is registered here.
/// </summary>
internal static class ServiceRegistration
{
    /// <param name="schedulerLoop">
    /// Already started, because <see cref="TsSchedulerLoop.StartAsync"/> is asynchronous and
    /// nothing may resolve <see cref="TsConnection"/> before the scheduler accepts work.
    /// </param>
    public static IServiceCollection AddTeamSpeak9(
        this IServiceCollection services,
        AppPaths paths,
        AppSettings settings,
        TsSchedulerLoop schedulerLoop)
    {
        ArgumentNullException.ThrowIfNull(services);
        ArgumentNullException.ThrowIfNull(paths);
        ArgumentNullException.ThrowIfNull(settings);
        ArgumentNullException.ThrowIfNull(schedulerLoop);

        services.AddLogging(builder =>
        {
            builder.ClearProviders();
            // Routes ILogger<T> into the same NLog targets TSLib already writes to.
            builder.AddNLog();
        });

        services.AddSingleton(paths);
        services.AddSingleton(settings);
        services.AddSingleton(schedulerLoop);

        services.AddSingleton<ISecretProtector, DpapiSecretProtector>();
        services.AddSingleton<SettingsStore>();
        services.AddSingleton<IdentityStore>();

        services.AddSingleton<IUiDispatcher>(_ => new WpfUiDispatcher(System.Windows.Application.Current.Dispatcher));

        // One connection per app, matching the single-server UI.
        services.AddSingleton<TsConnection>();

        return services;
    }
}
