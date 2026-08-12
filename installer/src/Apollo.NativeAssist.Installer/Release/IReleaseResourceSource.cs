using System.IO;
using System.Reflection;

namespace Apollo.NativeAssist.Installer.Release;

public interface IReleaseResourceSource
{
    ReadOnlyMemory<byte> ReadExact(string path, int maximumBytes);
    IReadOnlyList<string> Paths { get; }
}

public sealed class AssemblyReleaseResourceSource : IReleaseResourceSource
{
    private const string ResourcePrefix = "Apollo.NativeAssist.Release.";
    private readonly Assembly assembly;

    public AssemblyReleaseResourceSource(Assembly assembly)
    {
        this.assembly = assembly ?? throw new ArgumentNullException(nameof(assembly));
        Paths = Array.AsReadOnly(assembly.GetManifestResourceNames()
            .Where(name => name.StartsWith(ResourcePrefix, StringComparison.Ordinal))
            .Select(name => name[ResourcePrefix.Length..])
            .Order(StringComparer.Ordinal)
            .ToArray());
    }

    public IReadOnlyList<string> Paths { get; }

    public ReadOnlyMemory<byte> ReadExact(string path, int maximumBytes)
    {
        ArgumentException.ThrowIfNullOrEmpty(path);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(maximumBytes);

        using var stream = assembly.GetManifestResourceStream(ResourcePrefix + path)
            ?? throw new FileNotFoundException("Embedded release resource was not found.", path);
        if (stream.CanSeek && stream.Length > maximumBytes)
        {
            throw new InvalidDataException($"Embedded release resource exceeds {maximumBytes} bytes: {path}");
        }

        using var buffer = new MemoryStream(Math.Min(maximumBytes, 64 * 1024));
        var block = new byte[16 * 1024];
        while (true)
        {
            var count = stream.Read(block, 0, block.Length);
            if (count == 0)
            {
                break;
            }

            if (buffer.Length + count > maximumBytes)
            {
                throw new InvalidDataException($"Embedded release resource exceeds {maximumBytes} bytes: {path}");
            }

            buffer.Write(block, 0, count);
        }

        return buffer.ToArray();
    }
}
