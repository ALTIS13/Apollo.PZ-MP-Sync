using System.Globalization;
using System.Collections.ObjectModel;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using Apollo.NativeAssist.Installer.Core;
using Apollo.NativeAssist.Installer.Infrastructure.Ssh;

namespace Apollo.NativeAssist.Installer.Infrastructure.Docker;

public sealed record RuntimeFileIdentity(string Path, string Kind, string Mode, string Sha256);

public sealed record NativeRuntimeContract(
    string ServerJarSha256,
    string NativeManifestSha256,
    IReadOnlyList<RuntimeFileIdentity> Entrypoint,
    bool ImageCmdIsNull,
    string RuntimeLockMode);

public sealed class NativeReleaseFile
{
    public NativeReleaseFile(string name, ReadOnlySpan<byte> content, UnixFileMode mode, string sha256)
    {
        Name = RemoteOperation.ValidateRelativePath(name, nameof(name));
        if (Name.Contains('/', StringComparison.Ordinal))
        {
            throw new ArgumentException("Companion files must be direct children.", nameof(name));
        }

        Content = content.ToArray();
        Mode = mode;
        Sha256 = ValidateSha256(sha256, nameof(sha256));
        var actual = Convert.ToHexString(SHA256.HashData(Content.Span)).ToLowerInvariant();
        if (!string.Equals(actual, Sha256, StringComparison.Ordinal))
        {
            throw new ArgumentException("Release file hash mismatch.", nameof(sha256));
        }
    }

    public string Name { get; }
    public ReadOnlyMemory<byte> Content { get; }
    public UnixFileMode Mode { get; }
    public string Sha256 { get; }

    internal static string ValidateSha256(string value, string parameterName)
    {
        if (value is null || value.Length != 64 || value.Any(character => character is not (>= '0' and <= '9') and not (>= 'a' and <= 'f')))
        {
            throw new ArgumentException("Lowercase SHA-256 is required.", parameterName);
        }

        return value;
    }
}

public sealed class NativeReleaseBundle
{
    private static readonly string[] RequiredCompanionFiles =
    [
        "apollo-native-agent.jar",
        "apollo-native-entrypoint.sh",
        "fingerprint.properties",
        "native-libraries.sha256",
    ];

    public NativeReleaseBundle(
        IReadOnlyList<NativeReleaseFile> companionFiles,
        ReadOnlySpan<byte> overrideBytes,
        NativeRuntimeContract runtime)
    {
        ArgumentNullException.ThrowIfNull(companionFiles);
        ArgumentNullException.ThrowIfNull(runtime);
        if (overrideBytes.IsEmpty || overrideBytes.Length > 1024 * 1024)
        {
            throw new ArgumentException("Reviewed override bytes are required.", nameof(overrideBytes));
        }

        var files = companionFiles.ToDictionary(file => file.Name, StringComparer.Ordinal);
        if (files.Count != RequiredCompanionFiles.Length ||
            !RequiredCompanionFiles.SequenceEqual(files.Keys.Order(StringComparer.Ordinal), StringComparer.Ordinal))
        {
            throw new ArgumentException("Companion file set is not closed.", nameof(companionFiles));
        }

        if (runtime.Entrypoint is null or { Count: < 1 or > 32 } || runtime.ImageCmdIsNull is false)
        {
            throw new ArgumentException("Runtime launcher contract is invalid.", nameof(runtime));
        }

        NativeReleaseFile.ValidateSha256(runtime.ServerJarSha256, nameof(runtime));
        NativeReleaseFile.ValidateSha256(runtime.NativeManifestSha256, nameof(runtime));
        foreach (var entry in runtime.Entrypoint)
        {
            ArgumentNullException.ThrowIfNull(entry);
            RemoteOperation.ValidateAbsolutePath(entry.Path, nameof(runtime));
            if (entry.Kind != "file" || entry.Mode.Length != 4 || entry.Mode[0] != '0' ||
                entry.Mode.Skip(1).Any(character => character is < '0' or > '7'))
            {
                throw new ArgumentException("Runtime launcher identity is invalid.", nameof(runtime));
            }

            NativeReleaseFile.ValidateSha256(entry.Sha256, nameof(runtime));
        }

        ImageReference = ReadImageReference(files["fingerprint.properties"].Content.Span);

        Files = new ReadOnlyDictionary<string, NativeReleaseFile>(files);
        OverrideBytes = overrideBytes.ToArray();
        Runtime = runtime with { Entrypoint = Array.AsReadOnly(runtime.Entrypoint.ToArray()) };
    }

    public IReadOnlyDictionary<string, NativeReleaseFile> Files { get; }
    public ReadOnlyMemory<byte> OverrideBytes { get; }
    public NativeRuntimeContract Runtime { get; }
    public string ImageReference { get; }

    private static string ReadImageReference(ReadOnlySpan<byte> bytes)
    {
        string text;
        try
        {
            text = new UTF8Encoding(false, true).GetString(bytes);
        }
        catch (DecoderFallbackException exception)
        {
            throw new ArgumentException("Fingerprint properties are not strict UTF-8.", nameof(bytes), exception);
        }

        var matches = text.Split('\n')
            .Select(line => line.TrimEnd('\r'))
            .Where(line => line.StartsWith("imageReference=", StringComparison.Ordinal))
            .ToArray();
        if (matches.Length != 1)
        {
            throw new ArgumentException("Fingerprint imageReference is missing or ambiguous.", nameof(bytes));
        }

        var value = matches[0]["imageReference=".Length..];
        var separator = value.LastIndexOf("@sha256:", StringComparison.Ordinal);
        if (separator <= 0 || value.Length != separator + 8 + 64)
        {
            throw new ArgumentException("Fingerprint imageReference is not digest pinned.", nameof(bytes));
        }

        NativeReleaseFile.ValidateSha256(value[(separator + 8)..], nameof(bytes));
        return value;
    }
}

public sealed class DockerComposeConnector : IServerConnector
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNameCaseInsensitive = false,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow,
    };

    private readonly IRemoteSession session;
    private readonly VerifiedReleaseManifest manifest;
    private readonly NativeReleaseBundle bundle;
    private readonly string deploymentDirectory;
    private readonly IReadOnlyList<string> composeFiles;
    private readonly IReadOnlyList<string> rolloutComposeFiles;
    private readonly string serviceName;
    private ServiceSnapshot? snapshot;
    private bool enabled;

    public DockerComposeConnector(
        IRemoteSession session,
        VerifiedReleaseManifest manifest,
        NativeReleaseBundle bundle,
        string deploymentDirectory,
        IReadOnlyList<string> composeFiles,
        string serviceName)
    {
        this.session = session ?? throw new ArgumentNullException(nameof(session));
        this.manifest = manifest ?? throw new ArgumentNullException(nameof(manifest));
        this.bundle = bundle ?? throw new ArgumentNullException(nameof(bundle));
        if (!bundle.ImageReference.EndsWith("@" + manifest.Runtime.ImageDigest, StringComparison.Ordinal))
        {
            throw new ArgumentException("Release image reference and verified manifest digest disagree.", nameof(bundle));
        }
        var validated = RemoteOperation.Discover(deploymentDirectory, composeFiles, serviceName);
        this.deploymentDirectory = validated.DeploymentDirectory;
        this.composeFiles = validated.ComposeFiles;
        this.serviceName = validated.ServiceName;
        var overridePath = this.deploymentDirectory + "/compose.apollo-native.yaml";
        if (this.composeFiles.Contains(overridePath, StringComparer.Ordinal))
        {
            throw new ArgumentException("The generated Apollo override cannot be supplied as a base Compose file.", nameof(composeFiles));
        }

        rolloutComposeFiles = RemoteOperation.Discover(
            this.deploymentDirectory,
            [.. this.composeFiles, overridePath],
            this.serviceName).ComposeFiles;
    }

    public async Task<RuntimeProbe> ProbeAsync(CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        var result = await session.RunAsync(
            RemoteOperation.Discover(deploymentDirectory, composeFiles, serviceName),
            cancellationToken).ConfigureAwait(false);
        EnsureSuccess(result, "discovery");

        DiscoveryDocument? document;
        try
        {
            document = JsonSerializer.Deserialize<DiscoveryDocument>(result.StandardOutput, JsonOptions);
        }
        catch (JsonException exception)
        {
            throw new InvalidDataException("Discovery JSON is invalid.", exception);
        }

        if (document is null || document.SchemaVersion != 1 || document.Services is null)
        {
            throw new InvalidDataException("Discovery schema is invalid.");
        }

        var matches = document.Services.Where(service => service.Name == serviceName).ToArray();
        if (matches.Length != 1)
        {
            throw new InvalidDataException("Selected service is missing or ambiguous.");
        }

        try
        {
            ValidateSnapshot(matches[0]);
        }
        catch (ArgumentException exception)
        {
            throw new InvalidDataException("Discovery data is malformed.", exception);
        }
        snapshot = matches[0];
        return new RuntimeProbe(
            snapshot.AppId,
            snapshot.BuildId,
            snapshot.GameVersion,
            snapshot.JavaFeature,
            snapshot.Os,
            snapshot.Arch,
            snapshot.ImageDigest);
    }

    public IReadOnlyList<PlannedAction> DescribePlan()
    {
        RequireSnapshot();
        return Array.AsReadOnly(
            new[]
            {
                new PlannedAction("backup", deploymentDirectory + "/.apollo-backups", true, false),
                new PlannedAction("write-companion", deploymentDirectory + "/.apollo-native", true, false),
                new PlannedAction("write-environment", deploymentDirectory + "/.apollo-native.env", true, false),
                new PlannedAction("write-override", deploymentDirectory + "/compose.apollo-native.yaml", true, false),
                new PlannedAction("restart-disabled", serviceName + ":restart-disabled", true, true),
                new PlannedAction("restart-enabled", serviceName + ":restart-enabled", true, true),
                new PlannedAction("rollback-available", deploymentDirectory + "/.apollo-backups", false, false),
            });
    }

    public async Task<string> BackupAsync(CancellationToken cancellationToken)
    {
        RequireSnapshot();
        var result = await RunAsync(RemoteOperation.Backup(deploymentDirectory, composeFiles, serviceName), cancellationToken).ConfigureAwait(false);
        var path = result.StandardOutput.TrimEnd('\r', '\n');
        RemoteOperation.ValidateAbsolutePath(path, "backupPath");
        var expectedPrefix = deploymentDirectory + "/.apollo-backups/";
        if (!path.StartsWith(expectedPrefix, StringComparison.Ordinal))
        {
            throw new InvalidDataException("Backup path escaped its bounded directory.");
        }

        return path;
    }

    public async Task StageDisabledAsync(CancellationToken cancellationToken)
    {
        var current = RequireSnapshot();
        await RunAsync(RemoteOperation.PrepareStaging(deploymentDirectory, composeFiles, serviceName), cancellationToken).ConfigureAwait(false);

        foreach (var name in new[]
                 {
                     "apollo-native-agent.jar",
                     "apollo-native-entrypoint.sh",
                     "fingerprint.properties",
                     "native-libraries.sha256",
                 })
        {
            var file = bundle.Files[name];
            await using var content = new MemoryStream(file.Content.ToArray(), writable: false);
            await session.UploadAsync(
                content,
                current.CompanionHostDirectory + "/" + file.Name,
                file.Mode,
                cancellationToken).ConfigureAwait(false);
        }

        var environmentBytes = Encoding.UTF8.GetBytes(RenderEnvironment(current));
        await using (var environment = new MemoryStream(environmentBytes, writable: false))
        {
            await session.UploadAsync(
                environment,
                deploymentDirectory + "/.apollo-native.env",
                UnixFileMode.UserRead | UnixFileMode.UserWrite,
                cancellationToken).ConfigureAwait(false);
        }

        await using var composeOverride = new MemoryStream(bundle.OverrideBytes.ToArray(), writable: false);
        await session.UploadAsync(
            composeOverride,
            deploymentDirectory + "/compose.apollo-native.yaml",
            UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.GroupRead | UnixFileMode.OtherRead,
            cancellationToken).ConfigureAwait(false);
    }

    public async Task ValidateMergedConfigurationAsync(CancellationToken cancellationToken)
    {
        var current = RequireSnapshot();
        var result = await RunAsync(
            RemoteOperation.ValidateMergedConfiguration(deploymentDirectory, rolloutComposeFiles, serviceName, "off"),
            cancellationToken).ConfigureAwait(false);
        ComposeValidationDocument? document;
        try
        {
            document = JsonSerializer.Deserialize<ComposeValidationDocument>(result.StandardOutput, JsonOptions);
        }
        catch (JsonException exception)
        {
            throw new InvalidDataException("Merged Compose validation JSON is invalid.", exception);
        }

        var expectedEnvironment = ContainerEnvironment(current, "off");
        var expectedMounts = new HashSet<ComposeMountIdentity>
        {
            new(current.CompanionHostDirectory, "/opt/apollo-native", true),
            new(current.ServerHostDirectory, current.ServerRoot, true),
            new(current.WorkshopHostDirectory, current.ServerRoot + "/steamapps/workshop", false),
        };
        if (document is null || document.SchemaVersion != 1 || document.ServiceName != serviceName ||
            document.ComposeInvariantSha256 != current.ComposeInvariantSha256 ||
            document.OtherServicesSha256 != current.OtherServicesSha256 ||
            document.ImageReference != bundle.ImageReference || document.ExpectedPhase != "off" ||
            document.ApolloEnvironment is null || !DictionaryEquals(document.ApolloEnvironment, expectedEnvironment) ||
            document.ApolloMounts is null || document.ApolloMounts.Count != expectedMounts.Count ||
            !expectedMounts.SetEquals(document.ApolloMounts) || !document.ApolloOverridePresent)
        {
            throw new InvalidDataException("Merged Compose model drifted outside the reviewed Apollo delta.");
        }
    }

    public Task RestartAsync(CancellationToken cancellationToken)
        => RunWithoutResultAsync(
            RemoteOperation.RecreateService(deploymentDirectory, rolloutComposeFiles, serviceName, enabled ? "on" : "off"),
            cancellationToken);

    public Task VerifyVanillaAsync(CancellationToken cancellationToken)
        => RunWithoutResultAsync(RemoteOperation.VerifyVanilla(
            deploymentDirectory, rolloutComposeFiles, serviceName, RequireSnapshot().HealthcheckConfigured), cancellationToken);

    public async Task EnableAsync(CancellationToken cancellationToken)
    {
        await RunWithoutResultAsync(RemoteOperation.SetKillSwitch(deploymentDirectory, rolloutComposeFiles, serviceName, "on"), cancellationToken)
            .ConfigureAwait(false);
        enabled = true;
    }

    public async Task DisableAsync(CancellationToken cancellationToken)
    {
        await RunWithoutResultAsync(RemoteOperation.SetKillSwitch(deploymentDirectory, rolloutComposeFiles, serviceName, "off"), cancellationToken)
            .ConfigureAwait(false);
        enabled = false;
    }

    public Task VerifyReadyAsync(CancellationToken cancellationToken)
        => RunWithoutResultAsync(RemoteOperation.VerifyReady(
            deploymentDirectory, rolloutComposeFiles, serviceName, RequireSnapshot().HealthcheckConfigured), cancellationToken);

    public Task RollbackAsync(string backupPath, CancellationToken cancellationToken)
        => RunWithoutResultAsync(
            RemoteOperation.RestoreBackup(deploymentDirectory, composeFiles, serviceName, backupPath),
            cancellationToken);

    public ValueTask DisposeAsync() => session.DisposeAsync();

    private async Task RunWithoutResultAsync(RemoteOperation operation, CancellationToken cancellationToken)
        => _ = await RunAsync(operation, cancellationToken).ConfigureAwait(false);

    private async Task<RemoteResult> RunAsync(RemoteOperation operation, CancellationToken cancellationToken)
    {
        RequireSnapshot();
        cancellationToken.ThrowIfCancellationRequested();
        var result = await session.RunAsync(operation, cancellationToken).ConfigureAwait(false);
        EnsureSuccess(result, operation.Kind.ToString());
        return result;
    }

    private ServiceSnapshot RequireSnapshot()
        => snapshot ?? throw new InvalidOperationException("A successful read-only probe is required.");

    private static void EnsureSuccess(RemoteResult result, string operation)
    {
        ArgumentNullException.ThrowIfNull(result);
        if (result.ExitCode != 0 || result.Truncated)
        {
            throw new InvalidDataException($"Remote {operation} failed closed.");
        }
    }

    private void ValidateSnapshot(ServiceSnapshot candidate)
    {
        if (candidate.Name != serviceName || candidate.Entrypoint is null || candidate.Cmd is not null ||
            candidate.Entrypoint.Count != bundle.Runtime.Entrypoint.Count ||
            candidate.ImageReference != bundle.ImageReference ||
            candidate.RuntimeLockMode != bundle.Runtime.RuntimeLockMode ||
            candidate.ServerJarSha256 != bundle.Runtime.ServerJarSha256 ||
            candidate.NativeManifestSha256 != bundle.Runtime.NativeManifestSha256 ||
            !candidate.NativeLibrariesVerified || candidate.ServerRootIsSymlink || !candidate.ServerRootReadOnly ||
            !candidate.WorkshopWritable || candidate.CompanionParentIsSymlink)
        {
            throw new InvalidDataException("Discovered service violates the exact runtime contract.");
        }

        for (var index = 0; index < candidate.Entrypoint.Count; index++)
        {
            if (candidate.Entrypoint[index] != bundle.Runtime.Entrypoint[index])
            {
                throw new InvalidDataException("ENTRYPOINT identity mismatch.");
            }
        }

        RemoteOperation.ValidateAbsolutePath(candidate.ServerHostDirectory, nameof(candidate.ServerHostDirectory));
        RemoteOperation.ValidateAbsolutePath(candidate.ServerRoot, nameof(candidate.ServerRoot));
        RemoteOperation.ValidateRelativePath(candidate.ServerJarRelative, nameof(candidate.ServerJarRelative));
        RemoteOperation.ValidateAbsolutePath(candidate.WorkshopHostDirectory, nameof(candidate.WorkshopHostDirectory));
        RemoteOperation.ValidateAbsolutePath(candidate.CompanionHostDirectory, nameof(candidate.CompanionHostDirectory));
        if (candidate.CompanionHostDirectory != deploymentDirectory + "/.apollo-native" ||
            candidate.WorkshopHostDirectory != candidate.ServerHostDirectory + "/steamapps/workshop")
        {
            throw new InvalidDataException("Discovered mount path is outside its exact boundary.");
        }

        ValidateBootstrap(candidate.BootstrapEnv, "/usr/bin/env");
        ValidateBootstrap(candidate.BootstrapBash, "/bin/bash");
        if (!candidate.ImageDigest.StartsWith("sha256:", StringComparison.Ordinal) || candidate.ImageDigest.Length != 71)
        {
            throw new InvalidDataException("Image digest is malformed.");
        }

        NativeReleaseFile.ValidateSha256(candidate.ImageDigest["sha256:".Length..], nameof(candidate.ImageDigest));
        NativeReleaseFile.ValidateSha256(candidate.ComposeInvariantSha256, nameof(candidate.ComposeInvariantSha256));
        NativeReleaseFile.ValidateSha256(candidate.OtherServicesSha256, nameof(candidate.OtherServicesSha256));
        if (string.IsNullOrWhiteSpace(candidate.RestartPolicy))
        {
            throw new InvalidDataException("Restart policy is unavailable.");
        }
    }

    private static void ValidateBootstrap(RuntimeFileIdentity identity, string exactPath)
    {
        if (identity is null || identity.Path != exactPath || identity.Kind != "file" || identity.Mode != "0755")
        {
            throw new InvalidDataException("Bootstrap identity mismatch.");
        }

        NativeReleaseFile.ValidateSha256(identity.Sha256, nameof(identity));
    }

    private string RenderEnvironment(ServiceSnapshot current)
    {
        var environment = ContainerEnvironment(current, "off");
        var lines = new List<string>
        {
            $"APOLLO_NATIVE_HOST_DIR={current.CompanionHostDirectory}",
            $"APOLLO_SERVER_HOST_DIR={current.ServerHostDirectory}",
            $"APOLLO_WORKSHOP_HOST_DIR={current.WorkshopHostDirectory}",
        };
        lines.AddRange(environment.Select(entry => $"{entry.Key}={entry.Value}"));
        return string.Join('\n', lines) + "\n";
    }

    private Dictionary<string, string> ContainerEnvironment(ServiceSnapshot current, string phase)
    {
        var environment = new Dictionary<string, string>(StringComparer.Ordinal)
        {
            ["APOLLO_NATIVE_ASSIST"] = phase,
            ["APOLLO_RUNTIME_LOCK_MODE"] = current.RuntimeLockMode,
            ["APOLLO_SERVER_ROOT"] = current.ServerRoot,
            ["APOLLO_SERVER_JAR_RELATIVE"] = current.ServerJarRelative,
            ["APOLLO_FINGERPRINT_FILE_SHA256"] = bundle.Files["fingerprint.properties"].Sha256,
        };
        AddIdentity(environment, "APOLLO_BOOTSTRAP_ENV", current.BootstrapEnv);
        AddIdentity(environment, "APOLLO_BOOTSTRAP_BASH", current.BootstrapBash);
        environment["APOLLO_ORIGINAL_ENTRYPOINT_COUNT"] = current.Entrypoint.Count.ToString(CultureInfo.InvariantCulture);
        for (var index = 0; index < current.Entrypoint.Count; index++)
        {
            AddIdentity(environment, $"APOLLO_ORIGINAL_ENTRYPOINT_{index.ToString(CultureInfo.InvariantCulture)}", current.Entrypoint[index]);
        }

        return environment;
    }

    private static void AddIdentity(Dictionary<string, string> environment, string prefix, RuntimeFileIdentity identity)
    {
        environment[$"{prefix}_PATH"] = identity.Path;
        environment[$"{prefix}_KIND"] = identity.Kind;
        environment[$"{prefix}_MODE"] = identity.Mode;
        environment[$"{prefix}_SHA256"] = identity.Sha256;
    }

    private static bool DictionaryEquals(
        IReadOnlyDictionary<string, string> actual,
        IReadOnlyDictionary<string, string> expected)
        => actual.Count == expected.Count && expected.All(entry =>
            actual.TryGetValue(entry.Key, out var value) && value == entry.Value);

    private sealed class DiscoveryDocument
    {
        public int SchemaVersion { get; init; }
        public List<ServiceSnapshot>? Services { get; init; }
    }

    private sealed class ComposeValidationDocument
    {
        public int SchemaVersion { get; init; }
        public string ServiceName { get; init; } = string.Empty;
        public string ComposeInvariantSha256 { get; init; } = string.Empty;
        public string OtherServicesSha256 { get; init; } = string.Empty;
        public string ImageReference { get; init; } = string.Empty;
        public string ExpectedPhase { get; init; } = string.Empty;
        public Dictionary<string, string>? ApolloEnvironment { get; init; }
        public List<ComposeMountIdentity>? ApolloMounts { get; init; }
        public bool ApolloOverridePresent { get; init; }
    }

    private sealed record ComposeMountIdentity(string Source, string Target, bool ReadOnly);

    private sealed class ServiceSnapshot
    {
        public string Name { get; init; } = string.Empty;
        public string AppId { get; init; } = string.Empty;
        public string BuildId { get; init; } = string.Empty;
        public string GameVersion { get; init; } = string.Empty;
        public int JavaFeature { get; init; }
        public string Os { get; init; } = string.Empty;
        public string Arch { get; init; } = string.Empty;
        public string ImageDigest { get; init; } = string.Empty;
        public string ImageReference { get; init; } = string.Empty;
        public string ComposeInvariantSha256 { get; init; } = string.Empty;
        public string OtherServicesSha256 { get; init; } = string.Empty;
        public List<string>? Cmd { get; init; }
        public List<RuntimeFileIdentity> Entrypoint { get; init; } = [];
        public string RuntimeLockMode { get; init; } = string.Empty;
        public string ServerJarSha256 { get; init; } = string.Empty;
        public string NativeManifestSha256 { get; init; } = string.Empty;
        public bool NativeLibrariesVerified { get; init; }
        public string ServerHostDirectory { get; init; } = string.Empty;
        public string ServerRoot { get; init; } = string.Empty;
        public string ServerJarRelative { get; init; } = string.Empty;
        public bool ServerRootIsSymlink { get; init; }
        public bool ServerRootReadOnly { get; init; }
        public string WorkshopHostDirectory { get; init; } = string.Empty;
        public bool WorkshopWritable { get; init; }
        public string CompanionHostDirectory { get; init; } = string.Empty;
        public bool CompanionParentIsSymlink { get; init; }
        public RuntimeFileIdentity BootstrapEnv { get; init; } = null!;
        public RuntimeFileIdentity BootstrapBash { get; init; } = null!;
        public string RestartPolicy { get; init; } = string.Empty;
        public bool HealthcheckConfigured { get; init; }
    }
}
