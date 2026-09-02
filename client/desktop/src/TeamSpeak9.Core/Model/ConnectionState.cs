// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

namespace TeamSpeak9.Core.Model;

/// <summary>
/// The connection lifecycle as the UI sees it.
/// </summary>
/// <remarks>
/// This is deliberately wider than TSLib's internal four-state machine
/// (<c>Disconnected / Connecting / Connected / Disconnecting</c>): the extra
/// <see cref="Reconnecting"/> and <see cref="Failed"/> states belong to our own retry loop,
/// which TSLib knows nothing about.
/// </remarks>
public enum ConnectionState
{
    /// <summary>No session, and none is being attempted.</summary>
    Disconnected,

    /// <summary>A first connection attempt is in flight.</summary>
    Connecting,

    /// <summary>Handshake finished and <c>initserver</c> was received.</summary>
    Connected,

    /// <summary>A graceful <c>clientdisconnect</c> is in flight.</summary>
    Disconnecting,

    /// <summary>The session dropped unexpectedly and the retry loop is running.</summary>
    Reconnecting,

    /// <summary>The retry loop gave up. Requires an explicit user action to leave this state.</summary>
    Failed,
}

/// <summary>Raised whenever <see cref="ConnectionState"/> changes.</summary>
public sealed class ConnectionStateChangedEventArgs(
    ConnectionState previous,
    ConnectionState current,
    string? detail = null) : EventArgs
{
    public ConnectionState Previous { get; } = previous;

    public ConnectionState Current { get; } = current;

    /// <summary>A human readable reason, suitable for the status bar. May be empty.</summary>
    public string Detail { get; } = detail ?? string.Empty;

    public bool HasDetail => Detail.Length > 0;

    public override string ToString() =>
        HasDetail ? $"{Previous} -> {Current}: {Detail}" : $"{Previous} -> {Current}";
}
