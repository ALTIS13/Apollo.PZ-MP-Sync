using System.Globalization;
using System.IO;
using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;
using Apollo.NativeAssist.Installer.Core;
using Apollo.NativeAssist.Installer.Infrastructure.Docker;

namespace Apollo.NativeAssist.Installer.Release;

public sealed record EmbeddedInstallerRelease(
    VerifiedReleaseManifest Manifest,
    NativeReleaseBundle Bundle,
    string Version);

public static class EmbeddedReleaseLoader
{
    private const int TextMaximumBytes = 1024 * 1024;
    private const int JarMaximumBytes = 16 * 1024 * 1024;
    private const int MaximumJarEntries = 10_000;
    private const long MaximumExpandedJarEntryBytes = 4L * 1024 * 1024;
    private const long MaximumExpandedJarBytes = 32L * 1024 * 1024;
    private const string ExpectedManifestSha256 = "30fb803a73430ada6297fbcce3328860267202236cd2834197cf7e6056b76b41";
    private const string ExpectedVersion = "0.2.0";
    private const string ExpectedAppId = "380870";
    private const string ExpectedBuildId = "24574884";
    private const string ExpectedGameVersion = "42.20.2";
    private const string ExpectedOs = "linux";
    private const string ExpectedArch = "amd64";
    private const string ExpectedServerJarSha256 = "09a80a46e4febe9b436c0f4ec539bdfe9e9113b673eeaf8db22415ac34bef416";
    private const string ExpectedImageReference = "ghcr.io/renegade-master/zomboid-dedicated-server@sha256:5e3479ea2ef66a4f14686fd3abc3286cf31a82c0e37f737b4b5976ff37da9951";

    private static readonly string[] ResourcePaths =
    [
        "README_EN.md",
        "README_RU.md",
        "companion/apollo-native-agent.jar",
        "companion/apollo-native-entrypoint.sh",
        "companion/fingerprint.properties",
        "companion/native-libraries.sha256",
        "release-manifest.json",
        "templates/apollo-native.env.example",
        "templates/compose.native-assist.override.yaml",
    ];

    private static readonly string[] PayloadPaths = ResourcePaths
        .Where(path => path != "release-manifest.json")
        .ToArray();

    private static readonly HashSet<string> FingerprintScalars = new(StringComparer.Ordinal)
    {
        "appId", "buildId", "gameVersionRevision", "serverJarSha256",
        "nativeLibrarySha256", "agentSha256", "imageReference",
        "originalEntrypointCount", "imageCmd", "runtimeLockMode",
        "fingerprintSha256", "jvmFeature", "os", "arch",
        "workshopId", "luaModId", "bridgeProtocol",
    };

    private static readonly string[] FingerprintBeforeIdentity =
    [
        "appId", "buildId", "gameVersionRevision", "serverJarSha256",
        "nativeLibrarySha256", "agentSha256", "imageReference", "originalEntrypointCount",
    ];

    private static readonly string[] EntrypointFields = ["path", "kind", "mode", "sha256"];

    private static readonly RuntimeFileIdentity[] ExpectedEntrypoint =
    [
        new("/bin/bash", "file", "0755", "7e8d290708f90eec5e87c6715df90140b7b02fb7a4deb3be08b71d201703ae58"),
        new("/home/steam/run_server.sh", "file", "0755", "7a173dfaa49f7270f542ae3ab8e12266a6ee63e07cbabec71d88f8f1e3169be8"),
    ];

    private static readonly JsonSerializerOptions ManifestJsonOptions = new()
    {
        PropertyNameCaseInsensitive = false,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow,
    };

    public static EmbeddedInstallerRelease Load(IReleaseResourceSource source)
    {
        ArgumentNullException.ThrowIfNull(source);
        RequireExactResourceClosure(source.Paths);

        var bytes = new Dictionary<string, ReadOnlyMemory<byte>>(StringComparer.Ordinal);
        foreach (var path in ResourcePaths)
        {
            var maximumBytes = path.EndsWith(".jar", StringComparison.Ordinal) ? JarMaximumBytes : TextMaximumBytes;
            ReadOnlyMemory<byte> content;
            try
            {
                content = source.ReadExact(path, maximumBytes);
            }
            catch (Exception exception) when (exception is not InvalidDataException)
            {
                throw new InvalidDataException($"Could not read exact release resource: {path}", exception);
            }

            if (content.IsEmpty || content.Length > maximumBytes)
            {
                throw new InvalidDataException($"Release resource has an invalid size: {path}");
            }

            bytes.Add(path, content.ToArray());
        }

        RequireEqual(
            Sha256(bytes["release-manifest.json"].Span),
            ExpectedManifestSha256,
            "release manifest reviewed trust anchor");

        var text = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var pair in bytes.Where(pair => !pair.Key.EndsWith(".jar", StringComparison.Ordinal)))
        {
            text.Add(pair.Key, StrictReleaseText(pair.Key, pair.Value.Span));
        }

        var manifest = ParseManifest(text["release-manifest.json"]);
        ValidatePayloadHashes(manifest, bytes);
        ValidateJar(bytes["companion/apollo-native-agent.jar"]);
        ValidateNativeManifest(text["companion/native-libraries.sha256"]);
        var fingerprint = ParseFingerprint(text["companion/fingerprint.properties"]);
        ValidateFingerprint(manifest, fingerprint, bytes);

        var runtime = new RuntimeProbe(
            manifest.Support.AppId,
            manifest.Support.BuildId,
            manifest.Support.GameVersion,
            manifest.Support.JavaFeature,
            manifest.Support.Os,
            manifest.Support.Arch,
            manifest.ImageDigest);
        var verifiedManifest = new VerifiedReleaseManifest(runtime);
        var runtimeContract = new NativeRuntimeContract(
            fingerprint.ServerJarSha256,
            fingerprint.NativeLibrarySha256,
            fingerprint.Entrypoint,
            true,
            fingerprint.RuntimeLockMode);

        try
        {
            var bundle = new NativeReleaseBundle(
            [
                ReleaseFile("companion/apollo-native-agent.jar", UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.GroupRead | UnixFileMode.OtherRead),
                ReleaseFile("companion/apollo-native-entrypoint.sh", UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute | UnixFileMode.GroupRead | UnixFileMode.GroupExecute | UnixFileMode.OtherRead | UnixFileMode.OtherExecute),
                ReleaseFile("companion/fingerprint.properties", UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.GroupRead | UnixFileMode.OtherRead),
                ReleaseFile("companion/native-libraries.sha256", UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.GroupRead | UnixFileMode.OtherRead),
            ],
                bytes["templates/compose.native-assist.override.yaml"].Span,
                runtimeContract);
            return new EmbeddedInstallerRelease(verifiedManifest, bundle, manifest.Version);
        }
        catch (ArgumentException exception)
        {
            throw new InvalidDataException("The verified release cannot be represented as an installer bundle.", exception);
        }

        NativeReleaseFile ReleaseFile(string path, UnixFileMode mode)
        {
            var name = path[(path.LastIndexOf('/') + 1)..];
            return new NativeReleaseFile(name, bytes[path].Span, mode, Sha256(bytes[path].Span));
        }
    }

    private static void RequireExactResourceClosure(IReadOnlyList<string> paths)
    {
        if (paths is null || paths.Count != ResourcePaths.Length ||
            paths.Distinct(StringComparer.Ordinal).Count() != ResourcePaths.Length ||
            !paths.Order(StringComparer.Ordinal).SequenceEqual(ResourcePaths.Order(StringComparer.Ordinal), StringComparer.Ordinal))
        {
            throw new InvalidDataException("Embedded release resources are not the exact nine-file closure.");
        }
    }

    private static ReleaseManifest ParseManifest(string json)
    {
        try
        {
            using var document = JsonDocument.Parse(json, new JsonDocumentOptions
            {
                AllowTrailingCommas = false,
                CommentHandling = JsonCommentHandling.Disallow,
                MaxDepth = 16,
            });
            RejectDuplicateJsonProperties(document.RootElement);
            var manifest = JsonSerializer.Deserialize<ReleaseManifest>(json, ManifestJsonOptions)
                ?? throw new InvalidDataException("Release manifest cannot be null.");

            if (manifest.SchemaVersion != 1 || string.IsNullOrWhiteSpace(manifest.Version) ||
                manifest.Support is null || manifest.Fingerprint is null || manifest.Files is null)
            {
                throw new InvalidDataException("Release manifest has missing or unsupported fields.");
            }

            RequireToken(manifest.Support.AppId, "manifest appId");
            RequireToken(manifest.Support.BuildId, "manifest buildId");
            RequireToken(manifest.Support.GameVersion, "manifest gameVersion");
            RequireToken(manifest.Support.Os, "manifest os");
            RequireToken(manifest.Support.Arch, "manifest arch");
            RequireToken(manifest.Version, "manifest version");
            RequireEqual(manifest.Version, ExpectedVersion, "manifest version");
            RequireEqual(manifest.Support.AppId, ExpectedAppId, "manifest appId");
            RequireEqual(manifest.Support.BuildId, ExpectedBuildId, "manifest buildId");
            RequireEqual(manifest.Support.GameVersion, ExpectedGameVersion, "manifest gameVersion");
            RequireEqual(manifest.Support.Os, ExpectedOs, "manifest os");
            RequireEqual(manifest.Support.Arch, ExpectedArch, "manifest arch");
            if (manifest.Support.JavaFeature != 25)
            {
                throw new InvalidDataException("Manifest javaFeature must match the reviewed runtime.");
            }

            RequireDigest(manifest.ImageDigest, "manifest imageDigest");
            RequireSha256(manifest.AgentSha256, "manifest agentSha256");
            RequireSha256(manifest.Fingerprint.TransportSha256, "manifest fingerprint transportSha256");
            RequireSha256(manifest.Fingerprint.IdentitySha256, "manifest fingerprint identitySha256");
            RequireSha256(manifest.NativeManifestSha256, "manifest nativeManifestSha256");

            if (manifest.Files.Any(file => file is null) ||
                manifest.Files.Count != PayloadPaths.Length ||
                !manifest.Files.Select(file => file.Path).SequenceEqual(PayloadPaths, StringComparer.Ordinal) ||
                manifest.Files.Select(file => file.Path).Distinct(StringComparer.Ordinal).Count() != PayloadPaths.Length)
            {
                throw new InvalidDataException("Release manifest file list is not the exact payload closure.");
            }

            foreach (var file in manifest.Files)
            {
                RequireToken(file.Path, "manifest file path");
                RequireSha256(file.Sha256, $"manifest file hash {file.Path}");
            }

            return manifest;
        }
        catch (Exception exception) when (exception is JsonException or NotSupportedException)
        {
            throw new InvalidDataException("Release manifest is not strict JSON.", exception);
        }
    }

    private static void RejectDuplicateJsonProperties(JsonElement element)
    {
        if (element.ValueKind == JsonValueKind.Object)
        {
            var names = new HashSet<string>(StringComparer.Ordinal);
            foreach (var property in element.EnumerateObject())
            {
                if (!names.Add(property.Name))
                {
                    throw new InvalidDataException($"Release manifest contains duplicate JSON property: {property.Name}");
                }

                RejectDuplicateJsonProperties(property.Value);
            }
        }
        else if (element.ValueKind == JsonValueKind.Array)
        {
            foreach (var item in element.EnumerateArray())
            {
                RejectDuplicateJsonProperties(item);
            }
        }
    }

    private static void ValidatePayloadHashes(
        ReleaseManifest manifest,
        IReadOnlyDictionary<string, ReadOnlyMemory<byte>> bytes)
    {
        foreach (var file in manifest.Files)
        {
            var actual = Sha256(bytes[file.Path].Span);
            if (!string.Equals(actual, file.Sha256, StringComparison.Ordinal))
            {
                throw new InvalidDataException($"Release payload SHA-256 mismatch: {file.Path}");
            }
        }
    }

    private static Fingerprint ParseFingerprint(string contents)
    {
        var lines = contents[..^1].Split('\n');
        if (lines.Length == 0 || lines.Any(line => line.Length == 0 || line[0] is '#' or '!'))
        {
            throw new InvalidDataException("Fingerprint comments and blank lines are forbidden.");
        }

        var values = new Dictionary<string, string>(StringComparer.Ordinal);
        var keys = new List<string>(lines.Length);
        foreach (var line in lines)
        {
            var separator = line.IndexOf('=');
            if (separator < 1 || line.IndexOf('=', separator + 1) >= 0)
            {
                throw new InvalidDataException("Fingerprint must use one canonical key=value per line.");
            }

            var key = line[..separator];
            var value = line[(separator + 1)..];
            if (!IsVisibleAsciiToken(key) || !IsVisibleAsciiToken(value))
            {
                throw new InvalidDataException("Fingerprint keys and values must be canonical ASCII tokens.");
            }

            if (!values.TryAdd(key, value))
            {
                throw new InvalidDataException($"Duplicate fingerprint property: {key}");
            }

            keys.Add(key);
        }

        var countText = Required(values, "originalEntrypointCount");
        if (!Regex.IsMatch(countText, "^(?:[1-9]|[12][0-9]|3[0-2])$", RegexOptions.CultureInvariant))
        {
            throw new InvalidDataException("Fingerprint originalEntrypointCount must be canonical decimal 1..32.");
        }

        var count = int.Parse(countText, NumberStyles.None, CultureInfo.InvariantCulture);
        var entrypointKeys = Enumerable.Range(0, count)
            .SelectMany(index => EntrypointFields.Select(field => $"originalEntrypoint.{index}.{field}"))
            .ToHashSet(StringComparer.Ordinal);
        RejectUnknownFingerprintFields(values.Keys, entrypointKeys);
        var expectedOrder = CanonicalFingerprintOrder(values.Keys, count);
        if (!keys.SequenceEqual(expectedOrder, StringComparer.Ordinal))
        {
            throw new InvalidDataException("Fingerprint properties are not in strict canonical order.");
        }

        foreach (var scalar in FingerprintScalars)
        {
            Required(values, scalar);
        }

        foreach (var key in entrypointKeys)
        {
            Required(values, key);
        }

        if (Required(values, "jvmFeature") != "25")
        {
            throw new InvalidDataException("Fingerprint jvmFeature must use canonical decimal 25.");
        }

        var omittedIdentityLine = string.Join('\n', lines.Where(line => !line.StartsWith("fingerprintSha256=", StringComparison.Ordinal))) + "\n";
        var calculatedIdentity = Sha256(Encoding.UTF8.GetBytes(omittedIdentityLine));
        if (!string.Equals(calculatedIdentity, Required(values, "fingerprintSha256"), StringComparison.Ordinal))
        {
            throw new InvalidDataException("Fingerprint identity SHA-256 is stale.");
        }

        var entrypoint = Enumerable.Range(0, count).Select(index =>
        {
            var prefix = $"originalEntrypoint.{index}.";
            return new RuntimeFileIdentity(
                Required(values, prefix + "path"),
                Required(values, prefix + "kind"),
                Required(values, prefix + "mode"),
                Required(values, prefix + "sha256"));
        }).ToArray();

        return new Fingerprint(
            Required(values, "appId"),
            Required(values, "buildId"),
            Required(values, "gameVersionRevision"),
            Required(values, "serverJarSha256"),
            Required(values, "nativeLibrarySha256"),
            Required(values, "agentSha256"),
            Required(values, "imageReference"),
            entrypoint,
            Required(values, "imageCmd"),
            Required(values, "runtimeLockMode"),
            Required(values, "fingerprintSha256"),
            25,
            Required(values, "os"),
            Required(values, "arch"));
    }

    private static IReadOnlyList<string> CanonicalFingerprintOrder(IEnumerable<string> keys, int count)
    {
        var expected = new List<string>(keys.Count());
        expected.AddRange(FingerprintBeforeIdentity);
        for (var index = 0; index < count; index++)
        {
            expected.AddRange(EntrypointFields.Select(field => $"originalEntrypoint.{index}.{field}"));
        }

        expected.Add("imageCmd");
        expected.Add("runtimeLockMode");
        expected.Add("fingerprintSha256");
        expected.AddRange(["jvmFeature", "os", "arch"]);
        expected.AddRange(keys.Where(key => key.StartsWith("classHashes.", StringComparison.Ordinal)).Order(StringComparer.Ordinal));
        expected.AddRange(keys.Where(key => key.StartsWith("methodDescriptors.", StringComparison.Ordinal)).Order(StringComparer.Ordinal));
        expected.AddRange(["workshopId", "luaModId", "bridgeProtocol"]);
        return expected;
    }

    private static void RejectUnknownFingerprintFields(IEnumerable<string> keys, IReadOnlySet<string> entrypointKeys)
    {
        foreach (var key in keys)
        {
            if (FingerprintScalars.Contains(key) || entrypointKeys.Contains(key))
            {
                continue;
            }

            var prefix = key.StartsWith("classHashes.", StringComparison.Ordinal) ? "classHashes."
                : key.StartsWith("methodDescriptors.", StringComparison.Ordinal) ? "methodDescriptors."
                : null;
            if (prefix is null)
            {
                throw new InvalidDataException($"Unknown fingerprint property: {key}");
            }

            var suffix = key[prefix.Length..];
            if (suffix.Length == 0)
            {
                throw new InvalidDataException($"Empty fingerprint member name: {key}");
            }

            for (var index = 0; index < suffix.Length; index++)
            {
                if (suffix[index] == '%')
                {
                    if (index + 2 >= suffix.Length || suffix[index + 1] != '2' || suffix[index + 2] != '3')
                    {
                        throw new InvalidDataException($"Non-canonical fingerprint member name: {key}");
                    }

                    index += 2;
                }
                else if (suffix[index] is '#' or '=')
                {
                    throw new InvalidDataException($"Non-canonical fingerprint member name: {key}");
                }
            }

        }
    }

    private static void ValidateFingerprint(
        ReleaseManifest manifest,
        Fingerprint fingerprint,
        IReadOnlyDictionary<string, ReadOnlyMemory<byte>> bytes)
    {
        RequireSha256(fingerprint.ServerJarSha256, "fingerprint serverJarSha256");
        RequireSha256(fingerprint.NativeLibrarySha256, "fingerprint nativeLibrarySha256");
        RequireSha256(fingerprint.AgentSha256, "fingerprint agentSha256");
        RequireSha256(fingerprint.IdentitySha256, "fingerprint fingerprintSha256");

        RequireEqual(fingerprint.AppId, manifest.Support.AppId, "fingerprint appId");
        RequireEqual(fingerprint.BuildId, manifest.Support.BuildId, "fingerprint buildId");
        RequireEqual(fingerprint.GameVersion, manifest.Support.GameVersion, "fingerprint gameVersionRevision");
        if (fingerprint.JavaFeature != manifest.Support.JavaFeature)
        {
            throw new InvalidDataException("Fingerprint jvmFeature does not match the manifest.");
        }

        RequireEqual(fingerprint.Os, manifest.Support.Os, "fingerprint os");
        RequireEqual(fingerprint.Arch, manifest.Support.Arch, "fingerprint arch");
        RequireEqual(fingerprint.AgentSha256, manifest.AgentSha256, "fingerprint agentSha256");
        RequireEqual(fingerprint.NativeLibrarySha256, manifest.NativeManifestSha256, "fingerprint nativeLibrarySha256");
        RequireEqual(fingerprint.IdentitySha256, manifest.Fingerprint.IdentitySha256, "fingerprint identitySha256");
        RequireEqual(Sha256(bytes["companion/fingerprint.properties"].Span), manifest.Fingerprint.TransportSha256, "fingerprint transportSha256");
        RequireEqual(Sha256(bytes["companion/apollo-native-agent.jar"].Span), fingerprint.AgentSha256, "agent JAR SHA-256");
        RequireEqual(Sha256(bytes["companion/native-libraries.sha256"].Span), fingerprint.NativeLibrarySha256, "native manifest SHA-256");
        RequireEqual(fingerprint.ServerJarSha256, ExpectedServerJarSha256, "server JAR SHA-256");
        RequireEqual(fingerprint.ImageReference, ExpectedImageReference, "image reference");
        RequireEqual(fingerprint.AppId, ExpectedAppId, "reviewed appId");
        RequireEqual(fingerprint.BuildId, ExpectedBuildId, "reviewed buildId");
        RequireEqual(fingerprint.GameVersion, ExpectedGameVersion, "reviewed gameVersionRevision");
        RequireEqual(fingerprint.Os, ExpectedOs, "reviewed os");
        RequireEqual(fingerprint.Arch, ExpectedArch, "reviewed arch");

        var at = fingerprint.ImageReference.LastIndexOf('@');
        if (at <= 0 || !string.Equals(fingerprint.ImageReference[(at + 1)..], manifest.ImageDigest, StringComparison.Ordinal))
        {
            throw new InvalidDataException("Fingerprint image digest does not match the manifest.");
        }

        if (!fingerprint.Entrypoint.SequenceEqual(ExpectedEntrypoint))
        {
            throw new InvalidDataException("Fingerprint launcher contract does not match the reviewed image.");
        }

        if (fingerprint.ImageCmd != "null")
        {
            throw new InvalidDataException("Fingerprint imageCmd must be canonical null.");
        }

        if (fingerprint.RuntimeLockMode != "none-captured")
        {
            throw new InvalidDataException("Fingerprint runtimeLockMode must be none-captured.");
        }
    }

    private static void ValidateJar(ReadOnlyMemory<byte> bytes)
    {
        if (bytes.Length < 4 || bytes.Span[0] != 0x50 || bytes.Span[1] != 0x4b || bytes.Span[2] != 0x03 || bytes.Span[3] != 0x04)
        {
            throw new InvalidDataException("Agent payload must be a JAR/ZIP file.");
        }

        try
        {
            using var stream = new MemoryStream(bytes.ToArray(), writable: false);
            using var archive = new ZipArchive(stream, ZipArchiveMode.Read, leaveOpen: false);
            if (archive.Entries.Count == 0 || archive.Entries.Count > MaximumJarEntries)
            {
                throw new InvalidDataException($"Agent JAR must contain 1..{MaximumJarEntries} entries.");
            }

            var exactNodes = new Dictionary<string, bool>(StringComparer.Ordinal);
            var portableNodes = new Dictionary<string, bool>(StringComparer.Ordinal);
            var buffer = new byte[64 * 1024];
            long totalExpandedBytes = 0;
            foreach (var entry in archive.Entries)
            {
                var path = CanonicalArchivePath(entry.FullName);
                if (!exactNodes.TryAdd(path.Path, path.IsDirectory) ||
                    !portableNodes.TryAdd(path.PortableKey, path.IsDirectory))
                {
                    throw new InvalidDataException($"Agent JAR contains a duplicate or portable-colliding entry: {entry.FullName}");
                }

                RejectDirectoryFileCollision(exactNodes, path.Path, path.IsDirectory, entry.FullName);
                RejectDirectoryFileCollision(portableNodes, path.PortableKey, path.IsDirectory, entry.FullName);

                long entryExpandedBytes = 0;
                using var entryStream = entry.Open();
                while (true)
                {
                    var count = entryStream.Read(buffer, 0, buffer.Length);
                    if (count == 0)
                    {
                        break;
                    }

                    entryExpandedBytes += count;
                    totalExpandedBytes += count;
                    if (entryExpandedBytes > MaximumExpandedJarEntryBytes)
                    {
                        throw new InvalidDataException($"Agent JAR entry expands beyond 4 MiB: {entry.FullName}");
                    }

                    if (totalExpandedBytes > MaximumExpandedJarBytes)
                    {
                        throw new InvalidDataException("Agent JAR expands beyond 32 MiB in total.");
                    }
                }

                if (path.IsDirectory && entryExpandedBytes != 0)
                {
                    throw new InvalidDataException($"Agent JAR directory entry contains file bytes: {entry.FullName}");
                }
            }
        }
        catch (InvalidDataException exception)
        {
            throw new InvalidDataException("Agent payload is not a structurally valid JAR/ZIP file.", exception);
        }
    }

    private static void ValidateNativeManifest(string contents)
    {
        var exactPaths = new HashSet<string>(StringComparer.Ordinal);
        var portablePaths = new HashSet<string>(StringComparer.Ordinal);
        foreach (var line in contents[..^1].Split('\n'))
        {
            var match = Regex.Match(line, "^([0-9a-f]{64})  ([\\x21-\\x7e]+)$", RegexOptions.CultureInvariant);
            if (!match.Success)
            {
                throw new InvalidDataException("Native library manifest contains a malformed entry.");
            }

            var path = CanonicalRelativePath(match.Groups[2].Value, allowDirectory: false, "native library manifest");
            if (!Regex.IsMatch(path.Path, "^[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*$", RegexOptions.CultureInvariant) ||
                !exactPaths.Add(path.Path) || !portablePaths.Add(path.PortableKey))
            {
                throw new InvalidDataException("Native library manifest contains a malformed, duplicate, or portable-aliased path.");
            }
        }

        if (exactPaths.Count == 0)
        {
            throw new InvalidDataException("Native library manifest must not be empty.");
        }
    }

    private static CanonicalPath CanonicalArchivePath(string value) =>
        CanonicalRelativePath(value, allowDirectory: true, "Agent JAR");

    private static CanonicalPath CanonicalRelativePath(string value, bool allowDirectory, string label)
    {
        if (string.IsNullOrEmpty(value) || value.Contains('\\') || value[0] == '/' ||
            Regex.IsMatch(value, "^[A-Za-z]:", RegexOptions.CultureInvariant) || !value.IsNormalized(NormalizationForm.FormC))
        {
            throw new InvalidDataException($"{label} path is not a canonical forward-slash relative path: {value}");
        }

        var isDirectory = value[^1] == '/';
        if (isDirectory && !allowDirectory)
        {
            throw new InvalidDataException($"{label} path must name a file: {value}");
        }

        var path = isDirectory ? value[..^1] : value;
        var segments = path.Split('/');
        if (path.Length == 0 || segments.Any(segment =>
                segment.Length == 0 || segment is "." or ".." || segment.Contains(':') ||
                segment.EndsWith(' ') || segment.EndsWith('.')))
        {
            throw new InvalidDataException($"{label} path is not canonically normalized: {value}");
        }

        var portableKey = string.Join('/', segments.Select(segment => segment.ToUpperInvariant()));
        return new CanonicalPath(path, portableKey, isDirectory);
    }

    private static void RejectDirectoryFileCollision(
        IReadOnlyDictionary<string, bool> nodes,
        string path,
        bool isDirectory,
        string displayPath)
    {
        for (var separator = path.IndexOf('/'); separator >= 0; separator = path.IndexOf('/', separator + 1))
        {
            if (nodes.TryGetValue(path[..separator], out var parentIsDirectory) && !parentIsDirectory)
            {
                throw new InvalidDataException($"Agent JAR entry has a file as its parent directory: {displayPath}");
            }
        }

        if (!isDirectory && nodes.Keys.Any(candidate => candidate.StartsWith(path + "/", StringComparison.Ordinal)))
        {
            throw new InvalidDataException($"Agent JAR file collides with an existing directory: {displayPath}");
        }
    }

    private static string StrictReleaseText(string path, ReadOnlySpan<byte> bytes)
    {
        string text;
        try
        {
            text = new UTF8Encoding(false, true).GetString(bytes);
        }
        catch (DecoderFallbackException exception)
        {
            throw new InvalidDataException($"Release text is not strict UTF-8: {path}", exception);
        }

        if (text.Length == 0 || text[0] == '\ufeff' || text.Contains('\0') || text.Contains('\r') ||
            !text.EndsWith('\n') || text.EndsWith("\n\n", StringComparison.Ordinal))
        {
            throw new InvalidDataException($"Release text is not canonical LF-terminated UTF-8: {path}");
        }

        return text;
    }

    private static bool IsVisibleAsciiToken(string value) =>
        value.Length > 0 && value.All(character => character is >= (char)0x21 and <= (char)0x7e);

    private static string Required(IReadOnlyDictionary<string, string> values, string key)
    {
        if (!values.TryGetValue(key, out var value) || value.Length == 0)
        {
            throw new InvalidDataException($"Missing fingerprint property: {key}");
        }

        return value;
    }

    private static void RequireToken(string? value, string label)
    {
        if (value is null || !IsVisibleAsciiToken(value) || value.Contains('='))
        {
            throw new InvalidDataException($"{label} must be one canonical ASCII token.");
        }
    }

    private static void RequireDigest(string? value, string label)
    {
        if (value is null || !Regex.IsMatch(value, "^sha256:[0-9a-f]{64}$", RegexOptions.CultureInvariant))
        {
            throw new InvalidDataException($"{label} must be a lowercase SHA-256 digest.");
        }
    }

    private static void RequireSha256(string? value, string label)
    {
        if (value is null || !Regex.IsMatch(value, "^[0-9a-f]{64}$", RegexOptions.CultureInvariant))
        {
            throw new InvalidDataException($"{label} must be lowercase SHA-256.");
        }
    }

    private static void RequireEqual(string actual, string expected, string label)
    {
        if (!string.Equals(actual, expected, StringComparison.Ordinal))
        {
            throw new InvalidDataException($"{label} mismatch.");
        }
    }

    private static string Sha256(ReadOnlySpan<byte> bytes) =>
        Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();

    private sealed class ReleaseManifest
    {
        [JsonRequired]
        public int SchemaVersion { get; init; }

        [JsonRequired]
        public string Version { get; init; } = null!;

        [JsonRequired]
        public ReleaseSupport Support { get; init; } = null!;

        [JsonRequired]
        public string ImageDigest { get; init; } = null!;

        [JsonRequired]
        public string AgentSha256 { get; init; } = null!;

        [JsonRequired]
        public ReleaseFingerprint Fingerprint { get; init; } = null!;

        [JsonRequired]
        public string NativeManifestSha256 { get; init; } = null!;

        [JsonRequired]
        public IReadOnlyList<ReleaseFile> Files { get; init; } = null!;
    }

    private sealed class ReleaseSupport
    {
        [JsonRequired]
        public string AppId { get; init; } = null!;

        [JsonRequired]
        public string BuildId { get; init; } = null!;

        [JsonRequired]
        public string GameVersion { get; init; } = null!;

        [JsonRequired]
        public int JavaFeature { get; init; }

        [JsonRequired]
        public string Os { get; init; } = null!;

        [JsonRequired]
        public string Arch { get; init; } = null!;
    }

    private sealed class ReleaseFingerprint
    {
        [JsonRequired]
        public string TransportSha256 { get; init; } = null!;

        [JsonRequired]
        public string IdentitySha256 { get; init; } = null!;
    }

    private sealed class ReleaseFile
    {
        [JsonRequired]
        public string Path { get; init; } = null!;

        [JsonRequired]
        public string Sha256 { get; init; } = null!;
    }

    private sealed record Fingerprint(
        string AppId,
        string BuildId,
        string GameVersion,
        string ServerJarSha256,
        string NativeLibrarySha256,
        string AgentSha256,
        string ImageReference,
        IReadOnlyList<RuntimeFileIdentity> Entrypoint,
        string ImageCmd,
        string RuntimeLockMode,
        string IdentitySha256,
        int JavaFeature,
        string Os,
        string Arch);

    private sealed record CanonicalPath(string Path, string PortableKey, bool IsDirectory);
}
