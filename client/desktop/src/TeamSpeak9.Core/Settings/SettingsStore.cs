// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.Extensions.Logging;

namespace TeamSpeak9.Core.Settings;

/// <summary>
/// Loads and saves <see cref="AppSettings"/> as JSON.
/// </summary>
/// <remarks>
/// A corrupt or unreadable file is never fatal: it is moved aside and defaults are used, so a
/// bad write can't lock the user out of the client.
/// </remarks>
public sealed class SettingsStore
{
    private static readonly JsonSerializerOptions SerializerOptions = new()
    {
        WriteIndented = true,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        Converters = { new JsonStringEnumConverter() },
        // Keeps non-ASCII server names and nicknames readable in the file.
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
    };

    private readonly AppPaths paths;
    private readonly ILogger<SettingsStore> log;
    private readonly SemaphoreSlim writeLock = new(1, 1);

    public SettingsStore(AppPaths paths, ILogger<SettingsStore> log)
    {
        this.paths = paths;
        this.log = log;
    }

    public async Task<AppSettings> LoadAsync(CancellationToken cancel = default)
    {
        string file = paths.SettingsFile;
        if (!File.Exists(file))
        {
            log.LogInformation("No settings file at {Path}, starting from defaults.", file);
            return new AppSettings();
        }

        try
        {
            await using var stream = File.Open(file, FileMode.Open, FileAccess.Read, FileShare.Read);
            var loaded = await JsonSerializer.DeserializeAsync<AppSettings>(stream, SerializerOptions, cancel);
            if (loaded is not null)
                return loaded;

            log.LogWarning("Settings file {Path} deserialized to null.", file);
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (Exception ex) when (ex is JsonException or IOException or UnauthorizedAccessException)
        {
            log.LogError(ex, "Could not read settings from {Path}; falling back to defaults.", file);
            QuarantineCorruptFile(file);
            return new AppSettings();
        }

        QuarantineCorruptFile(file);
        return new AppSettings();
    }

    /// <summary>
    /// Writes settings to a temp file and then replaces the target, so an interrupted write
    /// cannot leave a truncated settings file behind.
    /// </summary>
    public async Task SaveAsync(AppSettings settings, CancellationToken cancel = default)
    {
        ArgumentNullException.ThrowIfNull(settings);

        await writeLock.WaitAsync(cancel);
        try
        {
            paths.EnsureCreated();

            string file = paths.SettingsFile;
            string temp = file + ".tmp";

            await using (var stream = File.Create(temp))
            {
                await JsonSerializer.SerializeAsync(stream, settings, SerializerOptions, cancel);
            }

            // File.Move with overwrite is atomic enough here: both paths are on the same volume.
            File.Move(temp, file, overwrite: true);
        }
        finally
        {
            writeLock.Release();
        }
    }

    private void QuarantineCorruptFile(string file)
    {
        try
        {
            string backup = file + ".corrupt-" + DateTime.Now.ToString("yyyyMMdd-HHmmss");
            File.Move(file, backup, overwrite: true);
            log.LogWarning("Moved unreadable settings file to {Path}.", backup);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            log.LogError(ex, "Could not move the unreadable settings file aside.");
        }
    }
}
