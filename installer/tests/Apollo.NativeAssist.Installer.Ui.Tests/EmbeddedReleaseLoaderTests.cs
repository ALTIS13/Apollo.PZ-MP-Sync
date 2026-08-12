using System.Diagnostics;
using System.IO.Compression;
using System.Reflection;
using System.Runtime.ExceptionServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using Apollo.NativeAssist.Installer.Release;
using Xunit;

namespace Apollo.NativeAssist.Installer.Ui.Tests;

public sealed class EmbeddedReleaseLoaderTests
{
    private const string AgentSha256 = "ca42552aab0dd2021ff11e9b46b15d03fe48102aed6bddf28f3e183a32b2917f";
    private const string NativeManifestSha256 = "86dcfd62671e7a8618c9bbba8433a82425b9c2e896a635c4f21aa70de17108ba";
    private const string ImageDigest = "sha256:5e3479ea2ef66a4f14686fd3abc3286cf31a82c0e37f737b4b5976ff37da9951";

    private static readonly IReadOnlyDictionary<string, byte[]> ExactFiles = LoadExactFiles();

    [Fact]
    public void Exact_staged_release_loads_and_cross_checks_every_identity()
    {
        var release = EmbeddedReleaseLoader.Load(new DictionaryReleaseSource(ExactFiles));

        Assert.Equal("0.2.0", release.Version);
        Assert.Equal("24574884", release.Manifest.Runtime.BuildId);
        Assert.Equal(ImageDigest, release.Manifest.Runtime.ImageDigest);
        Assert.Equal(AgentSha256, release.Bundle.Files["apollo-native-agent.jar"].Sha256);
        Assert.Equal(NativeManifestSha256, release.Bundle.Runtime.NativeManifestSha256);
        Assert.Equal(2, release.Bundle.Runtime.Entrypoint.Count);
        Assert.True(release.Bundle.Runtime.ImageCmdIsNull);
        Assert.Equal("none-captured", release.Bundle.Runtime.RuntimeLockMode);
    }

    public static TheoryData<string> ExpectedResourcePaths => new()
    {
        "README_EN.md",
        "README_RU.md",
        "companion/apollo-native-agent.jar",
        "companion/apollo-native-entrypoint.sh",
        "companion/fingerprint.properties",
        "companion/native-libraries.sha256",
        "release-manifest.json",
        "templates/apollo-native.env.example",
        "templates/compose.native-assist.override.yaml",
    };

    [Theory]
    [MemberData(nameof(ExpectedResourcePaths))]
    public void Every_missing_resource_is_rejected(string missingPath)
    {
        var files = CloneExactFiles();
        files.Remove(missingPath);

        Assert.Throws<InvalidDataException>(() => Load(files));
    }

    [Fact]
    public void Extra_resource_is_rejected()
    {
        var files = CloneExactFiles();
        files.Add("unexpected.txt", []);

        Assert.Throws<InvalidDataException>(() => Load(files));
    }

    [Fact]
    public void Manifest_unknown_field_is_rejected()
    {
        var files = CloneExactFiles();
        var text = Utf8(files["release-manifest.json"]);
        files["release-manifest.json"] = Encoding.UTF8.GetBytes(text.Replace(
            "  \"schemaVersion\": 1,",
            "  \"schemaVersion\": 1,\n  \"unexpected\": true,",
            StringComparison.Ordinal));

        Assert.Throws<InvalidDataException>(() => Load(files));
    }

    [Fact]
    public void Manifest_missing_field_is_rejected()
    {
        var files = CloneExactFiles();
        var text = Utf8(files["release-manifest.json"]);
        files["release-manifest.json"] = Encoding.UTF8.GetBytes(Regex.Replace(
            text,
            "^  \\\"version\\\": \\\"[^\\\"]+\\\",\\n",
            string.Empty,
            RegexOptions.Multiline));

        Assert.Throws<InvalidDataException>(() => Load(files));
    }

    [Fact]
    public void Duplicate_json_property_is_rejected()
    {
        var files = CloneExactFiles();
        var text = Utf8(files["release-manifest.json"]);
        files["release-manifest.json"] = Encoding.UTF8.GetBytes(text.Replace(
            "  \"schemaVersion\": 1,",
            "  \"schemaVersion\": 1,\n  \"schemaVersion\": 1,",
            StringComparison.Ordinal));

        Assert.Throws<InvalidDataException>(() => Load(files));
    }

    [Fact]
    public void Duplicate_fingerprint_property_is_rejected()
    {
        var files = MutateFingerprintRaw(text => text.Replace(
            "buildId=24574884\n",
            "buildId=24574884\nbuildId=24574884\n",
            StringComparison.Ordinal));

        Assert.Throws<InvalidDataException>(() => Load(files));
    }

    public static TheoryData<string, Func<string, string>> NonCanonicalFingerprintMutations => new()
    {
        { "comment", text => "# forbidden\n" + text },
        { "blank line", text => text.Replace("buildId=", "\nbuildId=", StringComparison.Ordinal) },
        { "multiple separators", text => text.Replace("appId=380870", "appId=380870=ambiguous", StringComparison.Ordinal) },
        { "CRLF", text => text.Replace("\n", "\r\n", StringComparison.Ordinal) },
        { "missing final LF", text => text[..^1] },
        {
            "out-of-order properties",
            text => text.Replace(
                "appId=380870\nbuildId=24574884",
                "buildId=24574884\nappId=380870",
                StringComparison.Ordinal)
        },
        {
            "unknown property",
            text => text.Replace("workshopId=", "unknown=forbidden\nworkshopId=", StringComparison.Ordinal)
        },
    };

    [Theory]
    [MemberData(nameof(NonCanonicalFingerprintMutations))]
    public void Non_canonical_fingerprint_properties_are_rejected(string _, Func<string, string> mutate)
    {
        Assert.Throws<InvalidDataException>(() => Load(MutateFingerprintRaw(mutate)));
    }

    [Fact]
    public void Invalid_utf8_is_rejected_even_when_the_manifest_hash_matches()
    {
        var files = CloneExactFiles();
        files["README_EN.md"] = [0xc3, 0x28];
        UpdateManifestFileHash(files, "README_EN.md");

        Assert.Throws<InvalidDataException>(() => Load(files));
    }

    [Fact]
    public void Resource_over_its_loader_size_limit_is_rejected_even_if_the_source_ignores_the_limit()
    {
        var files = CloneExactFiles();
        files["release-manifest.json"] = new byte[1024 * 1024 + 1];
        var source = new DictionaryReleaseSource(files, ignoreMaximumBytes: true);

        Assert.Throws<InvalidDataException>(() => EmbeddedReleaseLoader.Load(source));
    }

    [Fact]
    public void Payload_sha_mismatch_is_rejected()
    {
        var files = CloneExactFiles();
        files["README_EN.md"][0] ^= 0x01;

        Assert.Throws<InvalidDataException>(() => Load(files));
    }

    [Fact]
    public void Agent_and_fingerprint_hash_mismatch_is_rejected()
    {
        var files = MutateFingerprint(text => text.Replace(
            $"agentSha256={AgentSha256}",
            $"agentSha256={new string('0', 64)}",
            StringComparison.Ordinal));

        Assert.Throws<InvalidDataException>(() => Load(files));
    }

    [Fact]
    public void Stale_fingerprint_identity_is_rejected()
    {
        var files = CloneExactFiles();
        var text = Utf8(files["companion/fingerprint.properties"]);
        files["companion/fingerprint.properties"] = Encoding.UTF8.GetBytes(Regex.Replace(
            text,
            "^fingerprintSha256=[0-9a-f]{64}$",
            $"fingerprintSha256={new string('0', 64)}",
            RegexOptions.Multiline));
        UpdateFingerprintManifestHashes(files, refreshIdentity: false);

        Assert.Throws<InvalidDataException>(() => Load(files));
    }

    [Fact]
    public void Native_manifest_hash_mismatch_is_rejected()
    {
        var files = CloneExactFiles();
        files["companion/native-libraries.sha256"][0] ^= 0x01;
        UpdateManifestFileHash(files, "companion/native-libraries.sha256");

        Assert.Throws<InvalidDataException>(() => Load(files));
    }

    [Fact]
    public void Malformed_native_manifest_is_rejected_even_when_all_hashes_match()
    {
        var files = CloneExactFiles();
        files["companion/native-libraries.sha256"] = Encoding.UTF8.GetBytes("not-a-native-manifest\n");
        var hash = Sha256(files["companion/native-libraries.sha256"]);
        files = MutateFingerprint(files, text => text.Replace(
            $"nativeLibrarySha256={NativeManifestSha256}",
            $"nativeLibrarySha256={hash}",
            StringComparison.Ordinal));
        UpdateManifest(files, root =>
        {
            root["nativeManifestSha256"] = hash;
            SetManifestFileHash(root, "companion/native-libraries.sha256", hash);
        });

        Assert.Throws<InvalidDataException>(() => Load(files));
    }

    [Fact]
    public void Non_jar_payload_is_rejected_even_when_all_hashes_match()
    {
        var files = CloneExactFiles();
        files["companion/apollo-native-agent.jar"][0] = (byte)'N';
        var hash = Sha256(files["companion/apollo-native-agent.jar"]);
        files = MutateFingerprint(files, text => text.Replace(
            $"agentSha256={AgentSha256}",
            $"agentSha256={hash}",
            StringComparison.Ordinal));
        UpdateManifest(files, root =>
        {
            root["agentSha256"] = hash;
            SetManifestFileHash(root, "companion/apollo-native-agent.jar", hash);
        });

        Assert.Throws<InvalidDataException>(() => Load(files));
    }

    [Fact]
    public void Image_digest_mismatch_is_rejected()
    {
        var files = MutateFingerprint(text => text.Replace(
            ImageDigest,
            $"sha256:{new string('0', 64)}",
            StringComparison.Ordinal));

        Assert.Throws<InvalidDataException>(() => Load(files));
    }

    public static TheoryData<string, Func<string, string>> HostileLauncherMutations => new()
    {
        {
            "count",
            text => Regex.Replace(
                text.Replace("originalEntrypointCount=2", "originalEntrypointCount=1", StringComparison.Ordinal),
                "^originalEntrypoint\\.1\\.(?:path|kind|mode|sha256)=.*\\n",
                string.Empty,
                RegexOptions.Multiline)
        },
        { "path", text => text.Replace("originalEntrypoint.0.path=/bin/bash", "originalEntrypoint.0.path=/bin/sh", StringComparison.Ordinal) },
        { "kind", text => text.Replace("originalEntrypoint.0.kind=file", "originalEntrypoint.0.kind=directory", StringComparison.Ordinal) },
        { "mode", text => text.Replace("originalEntrypoint.0.mode=0755", "originalEntrypoint.0.mode=0644", StringComparison.Ordinal) },
        {
            "hash",
            text => Regex.Replace(
                text,
                "^originalEntrypoint\\.0\\.sha256=[0-9a-f]{64}$",
                $"originalEntrypoint.0.sha256={new string('0', 64)}",
                RegexOptions.Multiline)
        },
    };

    [Theory]
    [MemberData(nameof(HostileLauncherMutations))]
    public void Launcher_contract_mismatch_is_rejected(string _, Func<string, string> mutate)
    {
        Assert.Throws<InvalidDataException>(() => Load(MutateFingerprint(mutate)));
    }

    [Fact]
    public void Non_null_image_cmd_is_rejected()
    {
        var files = MutateFingerprint(text => text.Replace("imageCmd=null", "imageCmd=/bin/bash", StringComparison.Ordinal));

        Assert.Throws<InvalidDataException>(() => Load(files));
    }

    [Fact]
    public void Runtime_lock_mode_mismatch_is_rejected()
    {
        var files = MutateFingerprint(text => text.Replace(
            "runtimeLockMode=none-captured",
            "runtimeLockMode=active",
            StringComparison.Ordinal));

        Assert.Throws<InvalidDataException>(() => Load(files));
    }

    public static TheoryData<string, Func<Dictionary<string, byte[]>, Dictionary<string, byte[]>>> CoordinatedReleaseMutations => new()
    {
        {
            "agent JAR",
            files =>
            {
                files["companion/apollo-native-agent.jar"] = CreateZip(("META-INF/MANIFEST.MF", 32), ("agent.class", 64));
                var hash = Sha256(files["companion/apollo-native-agent.jar"]);
                files = MutateFingerprint(files, text => Regex.Replace(
                    text,
                    "^agentSha256=[0-9a-f]{64}$",
                    $"agentSha256={hash}",
                    RegexOptions.Multiline));
                ResignManifest(files);
                return files;
            }
        },
        {
            "native manifest",
            files =>
            {
                files["companion/native-libraries.sha256"] = Encoding.UTF8.GetBytes($"{new string('1', 64)}  media/new-native.so\n");
                var hash = Sha256(files["companion/native-libraries.sha256"]);
                files = MutateFingerprint(files, text => Regex.Replace(
                    text,
                    "^nativeLibrarySha256=[0-9a-f]{64}$",
                    $"nativeLibrarySha256={hash}",
                    RegexOptions.Multiline));
                ResignManifest(files);
                return files;
            }
        },
        {
            "launcher",
            files =>
            {
                files = MutateFingerprint(files, text => text.Replace(
                    "originalEntrypoint.0.path=/bin/bash",
                    "originalEntrypoint.0.path=/bin/sh",
                    StringComparison.Ordinal));
                ResignManifest(files);
                return files;
            }
        },
        {
            "template",
            files =>
            {
                files["templates/apollo-native.env.example"] = Encoding.UTF8.GetBytes("APOLLO_CHANGED=1\n");
                ResignManifest(files);
                return files;
            }
        },
        {
            "README",
            files =>
            {
                files["README_EN.md"] = Encoding.UTF8.GetBytes("# Altered reviewed instructions\n");
                ResignManifest(files);
                return files;
            }
        },
        {
            "fingerprint",
            files =>
            {
                files = MutateFingerprint(files, text => text.Replace(
                    "workshopId=3780069702",
                    "workshopId=3780069703",
                    StringComparison.Ordinal));
                ResignManifest(files);
                return files;
            }
        },
    };

    [Theory]
    [MemberData(nameof(CoordinatedReleaseMutations))]
    public void Coordinated_resigning_cannot_replace_any_reviewed_release_identity(
        string _,
        Func<Dictionary<string, byte[]>, Dictionary<string, byte[]>> mutate)
    {
        Assert.Throws<InvalidDataException>(() => Load(mutate(CloneExactFiles())));
    }

    [Fact]
    public void Coordinated_manifest_version_change_is_rejected()
    {
        var files = CloneExactFiles();
        UpdateManifest(files, root => root["version"] = "0.2.1");

        Assert.Throws<InvalidDataException>(() => Load(files));
    }

    public static TheoryData<string, string, string, string> CoordinatedRuntimeMutations => new()
    {
        { "appId", "appId", "380871", "appId" },
        { "buildId", "buildId", "24574885", "buildId" },
        { "gameVersion", "gameVersionRevision", "42.20.3", "gameVersion" },
        { "os", "os", "windows", "os" },
        { "arch", "arch", "arm64", "arch" },
    };

    [Theory]
    [MemberData(nameof(CoordinatedRuntimeMutations))]
    public void Coordinated_runtime_document_change_is_rejected(
        string _,
        string fingerprintKey,
        string replacement,
        string manifestKey)
    {
        var files = MutateFingerprint(text => Regex.Replace(
            text,
            $"^{Regex.Escape(fingerprintKey)}=[^\\n]+$",
            $"{fingerprintKey}={replacement}",
            RegexOptions.Multiline));
        UpdateManifest(files, root => root["support"]![manifestKey] = replacement);
        ResignManifest(files);

        Assert.Throws<InvalidDataException>(() => Load(files));
    }

    [Fact]
    public void Real_agent_jar_satisfies_bounded_canonical_archive_validation()
    {
        InvokeLoaderValidator("ValidateJar", new ReadOnlyMemory<byte>(ExactFiles["companion/apollo-native-agent.jar"]));
    }

    public static TheoryData<string, string> UnsafeArchivePaths => new()
    {
        { "absolute", "/absolute.class" },
        { "drive", "C:/drive.class" },
        { "backslash", "a\\b.class" },
        { "empty segment", "a//b.class" },
        { "dot segment", "a/./b.class" },
        { "dotdot segment", "a/../b.class" },
    };

    [Theory]
    [MemberData(nameof(UnsafeArchivePaths))]
    public void Agent_jar_rejects_unsafe_entry_paths(string _, string path)
    {
        var bytes = CreateZip((path, 1));

        Assert.Throws<InvalidDataException>(() => InvokeLoaderValidator("ValidateJar", new ReadOnlyMemory<byte>(bytes)));
    }

    [Fact]
    public void Agent_jar_rejects_duplicate_entry_paths()
    {
        var bytes = CreateZip(("same.class", 1), ("same.class", 1));

        Assert.Throws<InvalidDataException>(() => InvokeLoaderValidator("ValidateJar", new ReadOnlyMemory<byte>(bytes)));
    }

    [Fact]
    public void Agent_jar_rejects_portable_case_collisions()
    {
        var bytes = CreateZip(("Agent.class", 1), ("agent.class", 1));

        Assert.Throws<InvalidDataException>(() => InvokeLoaderValidator("ValidateJar", new ReadOnlyMemory<byte>(bytes)));
    }

    [Fact]
    public void Agent_jar_rejects_directory_file_collisions()
    {
        var bytes = CreateZip(("parent", 1), ("parent/child.class", 1));

        Assert.Throws<InvalidDataException>(() => InvokeLoaderValidator("ValidateJar", new ReadOnlyMemory<byte>(bytes)));
    }

    [Fact]
    public void Agent_jar_rejects_more_than_10000_entries()
    {
        var entries = Enumerable.Range(0, 10_001).Select(index => ($"entry-{index}.class", 0)).ToArray();
        var bytes = CreateZip(entries);

        Assert.Throws<InvalidDataException>(() => InvokeLoaderValidator("ValidateJar", new ReadOnlyMemory<byte>(bytes)));
    }

    [Fact]
    public void Agent_jar_rejects_a_file_expanding_over_4_mib()
    {
        var bytes = CreateZip(("oversized.class", 4 * 1024 * 1024 + 1));

        Assert.Throws<InvalidDataException>(() => InvokeLoaderValidator("ValidateJar", new ReadOnlyMemory<byte>(bytes)));
    }

    [Fact]
    public void Agent_jar_rejects_total_expansion_over_32_mib()
    {
        var entries = Enumerable.Range(0, 9).Select(index => ($"large-{index}.class", 4 * 1024 * 1024)).ToArray();
        var bytes = CreateZip(entries);

        Assert.Throws<InvalidDataException>(() => InvokeLoaderValidator("ValidateJar", new ReadOnlyMemory<byte>(bytes)));
    }

    public static TheoryData<string, string> UnsafeNativeManifestPaths => new()
    {
        { "absolute", "/absolute.so" },
        { "drive", "C:/drive.so" },
        { "backslash", "a\\b.so" },
        { "empty segment", "a//b.so" },
        { "dot segment", "a/./b.so" },
        { "dotdot segment", "a/../b.so" },
        { "trailing dot alias", "a./native.so" },
    };

    [Theory]
    [MemberData(nameof(UnsafeNativeManifestPaths))]
    public void Native_manifest_rejects_noncanonical_paths(string _, string path)
    {
        var contents = $"{new string('1', 64)}  {path}\n";

        Assert.Throws<InvalidDataException>(() => InvokeLoaderValidator("ValidateNativeManifest", contents));
    }

    [Fact]
    public void Native_manifest_rejects_portable_case_aliases()
    {
        var contents = $"{new string('1', 64)}  lib/Native.so\n{new string('2', 64)}  lib/native.so\n";

        Assert.Throws<InvalidDataException>(() => InvokeLoaderValidator("ValidateNativeManifest", contents));
    }

    [Fact]
    public void Native_manifest_rejects_exact_duplicate_paths()
    {
        var contents = $"{new string('1', 64)}  lib/native.so\n{new string('2', 64)}  lib/native.so\n";

        Assert.Throws<InvalidDataException>(() => InvokeLoaderValidator("ValidateNativeManifest", contents));
    }

    [Fact]
    public async Task Publish_rejects_a_directory_staged_input_with_a_truthful_diagnostic()
    {
        await AssertPublishRejectsNonRegularInputAsync((_, input) =>
        {
            File.Delete(input);
            Directory.CreateDirectory(input);
        });
    }

    [Fact]
    public async Task Publish_rejects_a_real_symbolic_link_staged_input_with_a_truthful_diagnostic()
    {
        await AssertPublishRejectsNonRegularInputAsync((temporaryRoot, input) =>
        {
            File.Delete(input);
            var target = Path.Combine(temporaryRoot, "link-target.md");
            File.WriteAllText(target, "linked bytes\n", Encoding.UTF8);
            File.CreateSymbolicLink(input, target);
        });
    }

    private static void Load(IReadOnlyDictionary<string, byte[]> files) =>
        EmbeddedReleaseLoader.Load(new DictionaryReleaseSource(files));

    private static Dictionary<string, byte[]> MutateFingerprint(Func<string, string> mutate) =>
        MutateFingerprint(CloneExactFiles(), mutate);

    private static Dictionary<string, byte[]> MutateFingerprintRaw(Func<string, string> mutate)
    {
        var files = CloneExactFiles();
        files["companion/fingerprint.properties"] = Encoding.UTF8.GetBytes(
            mutate(Utf8(files["companion/fingerprint.properties"])));
        UpdateFingerprintManifestHashes(files, refreshIdentity: false);
        return files;
    }

    private static Dictionary<string, byte[]> MutateFingerprint(
        Dictionary<string, byte[]> files,
        Func<string, string> mutate)
    {
        var text = mutate(Utf8(files["companion/fingerprint.properties"]));
        files["companion/fingerprint.properties"] = Encoding.UTF8.GetBytes(RefreshFingerprintIdentity(text));
        UpdateFingerprintManifestHashes(files, refreshIdentity: true);
        return files;
    }

    private static string RefreshFingerprintIdentity(string text)
    {
        var omitted = Regex.Replace(
            text,
            "^fingerprintSha256=[0-9a-f]{64}\\n",
            string.Empty,
            RegexOptions.Multiline);
        var identity = Sha256(Encoding.UTF8.GetBytes(omitted));
        return Regex.Replace(
            text,
            "^fingerprintSha256=[0-9a-f]{64}$",
            $"fingerprintSha256={identity}",
            RegexOptions.Multiline);
    }

    private static void UpdateFingerprintManifestHashes(Dictionary<string, byte[]> files, bool refreshIdentity)
    {
        var text = Utf8(files["companion/fingerprint.properties"]);
        var identity = Regex.Match(text, "^fingerprintSha256=([0-9a-f]{64})$", RegexOptions.Multiline).Groups[1].Value;
        var transport = Sha256(files["companion/fingerprint.properties"]);
        UpdateManifest(files, root =>
        {
            var fingerprint = root["fingerprint"]!.AsObject();
            fingerprint["transportSha256"] = transport;
            if (refreshIdentity)
            {
                fingerprint["identitySha256"] = identity;
            }

            SetManifestFileHash(root, "companion/fingerprint.properties", transport);
        });
    }

    private static void UpdateManifestFileHash(Dictionary<string, byte[]> files, string path)
    {
        var hash = Sha256(files[path]);
        UpdateManifest(files, root => SetManifestFileHash(root, path, hash));
    }

    private static void SetManifestFileHash(JsonObject root, string path, string hash)
    {
        var entry = root["files"]!.AsArray()
            .Select(node => node!.AsObject())
            .Single(node => node["path"]!.GetValue<string>() == path);
        entry["sha256"] = hash;
    }

    private static void UpdateManifest(Dictionary<string, byte[]> files, Action<JsonObject> mutate)
    {
        var root = JsonNode.Parse(files["release-manifest.json"])!.AsObject();
        mutate(root);
        var json = root.ToJsonString(new JsonSerializerOptions
        {
            WriteIndented = true,
        });
        var canonicalLf = json.Replace("\r\n", "\n", StringComparison.Ordinal).Replace('\r', '\n');
        files["release-manifest.json"] = Encoding.UTF8.GetBytes(canonicalLf.TrimEnd('\n') + "\n");
    }

    private static void ResignManifest(Dictionary<string, byte[]> files)
    {
        UpdateManifest(files, root =>
        {
            foreach (var path in files.Keys.Where(path => path != "release-manifest.json"))
            {
                SetManifestFileHash(root, path, Sha256(files[path]));
            }

            root["agentSha256"] = Sha256(files["companion/apollo-native-agent.jar"]);
            root["nativeManifestSha256"] = Sha256(files["companion/native-libraries.sha256"]);
            var fingerprintText = Utf8(files["companion/fingerprint.properties"]);
            var fingerprint = root["fingerprint"]!.AsObject();
            fingerprint["transportSha256"] = Sha256(files["companion/fingerprint.properties"]);
            fingerprint["identitySha256"] = Regex.Match(
                fingerprintText,
                "^fingerprintSha256=([0-9a-f]{64})$",
                RegexOptions.Multiline).Groups[1].Value;
        });
    }

    private static byte[] CreateZip(params (string Path, int ExpandedBytes)[] entries)
    {
        using var stream = new MemoryStream();
        using (var archive = new ZipArchive(stream, ZipArchiveMode.Create, leaveOpen: true))
        {
            var block = new byte[64 * 1024];
            foreach (var (path, expandedBytes) in entries)
            {
                var entry = archive.CreateEntry(path, CompressionLevel.SmallestSize);
                using var output = entry.Open();
                var remaining = expandedBytes;
                while (remaining > 0)
                {
                    var count = Math.Min(remaining, block.Length);
                    output.Write(block, 0, count);
                    remaining -= count;
                }
            }
        }

        return stream.ToArray();
    }

    private static void InvokeLoaderValidator(string name, object argument)
    {
        var method = typeof(EmbeddedReleaseLoader).GetMethod(name, BindingFlags.NonPublic | BindingFlags.Static)
            ?? throw new MissingMethodException(typeof(EmbeddedReleaseLoader).FullName, name);
        try
        {
            method.Invoke(null, [argument]);
        }
        catch (TargetInvocationException exception) when (exception.InnerException is not null)
        {
            ExceptionDispatchInfo.Capture(exception.InnerException).Throw();
        }
    }

    private static async Task AssertPublishRejectsNonRegularInputAsync(Action<string, string> makeHostile)
    {
        var temporaryRoot = Path.Combine(Path.GetTempPath(), $"apollo-native-publish-gate-{Guid.NewGuid():N}");
        var staged = Path.Combine(temporaryRoot, "staging");
        try
        {
            foreach (var pair in ExactFiles)
            {
                var destination = Path.Combine(staged, pair.Key.Replace('/', Path.DirectorySeparatorChar));
                Directory.CreateDirectory(Path.GetDirectoryName(destination)!);
                File.WriteAllBytes(destination, pair.Value);
            }

            var hostileInput = Path.Combine(staged, "README_EN.md");
            makeHostile(temporaryRoot, hostileInput);
            var project = Path.Combine(
                FindProjectRoot(),
                "installer",
                "src",
                "Apollo.NativeAssist.Installer",
                "Apollo.NativeAssist.Installer.csproj");
            var startInfo = new ProcessStartInfo("dotnet")
            {
                WorkingDirectory = FindProjectRoot(),
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
            };
            startInfo.ArgumentList.Add("publish");
            startInfo.ArgumentList.Add(project);
            startInfo.ArgumentList.Add("-c");
            startInfo.ArgumentList.Add("Release");
            startInfo.ArgumentList.Add("--no-restore");
            startInfo.ArgumentList.Add($"-p:NativeReleaseDir={staged}");
            startInfo.ArgumentList.Add($"-p:PublishDir={Path.Combine(temporaryRoot, "publish")}");

            using var process = Process.Start(startInfo) ?? throw new InvalidOperationException("Could not start dotnet publish.");
            var standardOutput = process.StandardOutput.ReadToEndAsync();
            var standardError = process.StandardError.ReadToEndAsync();
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(60));
            await process.WaitForExitAsync(timeout.Token);
            var output = (await standardOutput) + (await standardError);

            Assert.NotEqual(0, process.ExitCode);
            Assert.Contains("regular non-link staged release file", output, StringComparison.OrdinalIgnoreCase);
            Assert.Contains(hostileInput, output, StringComparison.OrdinalIgnoreCase);
        }
        finally
        {
            if (Directory.Exists(temporaryRoot))
            {
                Directory.Delete(temporaryRoot, recursive: true);
            }
        }
    }

    private static Dictionary<string, byte[]> CloneExactFiles() => ExactFiles.ToDictionary(
        pair => pair.Key,
        pair => pair.Value.ToArray(),
        StringComparer.Ordinal);

    private static IReadOnlyDictionary<string, byte[]> LoadExactFiles()
    {
        var root = FindProjectRoot();
        var staging = Path.Combine(root, "build", "native-release", "staging");
        return Directory.GetFiles(staging, "*", SearchOption.AllDirectories).ToDictionary(
            path => Path.GetRelativePath(staging, path).Replace('\\', '/'),
            File.ReadAllBytes,
            StringComparer.Ordinal);
    }

    private static string FindProjectRoot()
    {
        foreach (var start in new[] { Directory.GetCurrentDirectory(), AppContext.BaseDirectory })
        {
            for (var directory = new DirectoryInfo(start); directory is not null; directory = directory.Parent)
            {
                if (Directory.Exists(Path.Combine(directory.FullName, "build", "native-release", "staging")))
                {
                    return directory.FullName;
                }
            }
        }

        throw new DirectoryNotFoundException("Could not locate build/native-release/staging.");
    }

    private static string Utf8(byte[] bytes) => new UTF8Encoding(false, true).GetString(bytes);

    private static string Sha256(byte[] bytes) => Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();

    private sealed class DictionaryReleaseSource(
        IReadOnlyDictionary<string, byte[]> files,
        bool ignoreMaximumBytes = false) : IReleaseResourceSource
    {
        public IReadOnlyList<string> Paths { get; } = files.Keys.ToArray();

        public ReadOnlyMemory<byte> ReadExact(string path, int maximumBytes)
        {
            if (!files.TryGetValue(path, out var bytes))
            {
                throw new FileNotFoundException("Missing release resource.", path);
            }

            if (!ignoreMaximumBytes && bytes.Length > maximumBytes)
            {
                throw new InvalidDataException("Release resource exceeds the requested size limit.");
            }

            return bytes;
        }
    }
}
