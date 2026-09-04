// TeamSpeak9 - PC client
// Licensed under the terms in the repository root.

// Not implicit here the way it is in Core.Tests: the WPF SDK drops System.IO from the implicit
// usings because System.Windows.Shapes.Path would collide with System.IO.Path.
using System.IO;
using IoPath = System.IO.Path;

namespace TeamSpeak9.App.Tests.Infrastructure;

/// <summary>
/// A throwaway directory that is deleted when the test finishes.
/// </summary>
internal sealed class TempDirectory : IDisposable
{
    public TempDirectory()
    {
        Path = IoPath.Combine(IoPath.GetTempPath(), "ts9-tests-" + Guid.NewGuid().ToString("N"));
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
