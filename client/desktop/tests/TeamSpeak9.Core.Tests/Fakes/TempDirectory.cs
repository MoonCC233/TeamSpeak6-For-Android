// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

namespace TeamSpeak9.Core.Tests.Fakes;

/// <summary>
/// A throwaway directory that is deleted when the test finishes.
/// </summary>
internal sealed class TempDirectory : IDisposable
{
    public TempDirectory()
    {
        Path = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "ts9-tests-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(Path);
    }

    public string Path { get; }

    public void Dispose()
    {
        try
        {
            if (Directory.Exists(Path))
                Directory.Delete(Path, recursive: true);
        }
        catch (IOException)
        {
            // A leftover temp directory must never fail a test run.
        }
    }
}
