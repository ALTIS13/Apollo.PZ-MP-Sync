using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Apollo.NativeAssist.Installer.Core;
using Apollo.NativeAssist.Installer.Infrastructure.Docker;
using Apollo.NativeAssist.Installer.Infrastructure.Ssh;
using Xunit;

namespace Apollo.NativeAssist.Installer.Infrastructure.Tests;

public sealed class DockerComposeConnectorTests
{
    private const string ImageDigest = "sha256:5e3479ea2ef66a4f14686fd3abc3286cf31a82c0e37f737b4b5976ff37da9951";
    private const string ImageReference = "ghcr.io/renegade-master/zomboid-dedicated-server@" + ImageDigest;
    private const string ServerJarSha = "bda809fb49004a07dbfc560d059c0ee58d0643ab0f33b53351b13bd62f1d8227";
    private const string NativeManifestSha = "86dcfd62671e7a8618c9bbba8433a82425b9c2e896a635c4f21aa70de17108ba";

    private static readonly RuntimeProbe ExpectedRuntime = new(
        "380870", "24775771", "42.20.3", 25, "linux", "amd64", ImageDigest);

    private static readonly VerifiedReleaseManifest Manifest = new(ExpectedRuntime);

    public static TheoryData<string> UnsafeAbsolutePaths => new()
    {
        "relative/path",
        "/srv/pz/../other",
        "/srv/pz\nother",
        "/srv/pz\r",
        "/srv//pz",
    };

    [Theory]
    [MemberData(nameof(UnsafeAbsolutePaths))]
    public void Remote_operations_reject_noncanonical_paths(string path)
    {
        Assert.Throws<ArgumentException>(() => RemoteOperation.Discover(
            path,
            ["/srv/pz/docker-compose.yml"],
            "project-zomboid"));
    }

    [Theory]
    [InlineData("")]
    [InlineData("project zomboid")]
    [InlineData("project-zomboid\nother")]
    [InlineData("--project-zomboid")]
    public void Remote_operations_reject_unsafe_service_names(string serviceName)
    {
        Assert.Throws<ArgumentException>(() => RemoteOperation.Discover(
            "/srv/pz",
            ["/srv/pz/docker-compose.yml"],
            serviceName));
    }

    [Fact]
    public async Task Exact_read_only_probe_returns_runtime_without_uploads()
    {
        var session = new FakeRemoteSession(GoodDiscoveryJson());
        await using var connector = CreateConnector(session);

        var actual = await connector.ProbeAsync(CancellationToken.None);

        Assert.Equal(ExpectedRuntime, actual);
        Assert.Single(session.Operations);
        Assert.Equal(RemoteOperationKind.Discover, session.Operations[0].Kind);
        Assert.Empty(session.Uploads);
        Assert.Empty(session.Mutations);
    }

    [Fact]
    public async Task Plan_is_available_only_after_exact_probe_and_names_every_write_and_restart()
    {
        var session = new FakeRemoteSession(GoodDiscoveryJson());
        await using var connector = CreateConnector(session);

        Assert.Throws<InvalidOperationException>(() => connector.DescribePlan());
        await connector.ProbeAsync(CancellationToken.None);

        Assert.Equal(
            [
                "/srv/pz/.apollo-backups",
                "/srv/pz/.apollo-native",
                "/srv/pz/.apollo-native.env",
                "/srv/pz/compose.apollo-native.yaml",
                "project-zomboid:restart-disabled",
                "project-zomboid:restart-enabled",
                "/srv/pz/.apollo-backups",
            ],
            connector.DescribePlan().Select(action => action.Target));
    }

    [Fact]
    public async Task Plan_is_a_fresh_read_only_snapshot_with_only_reviewed_display_fields()
    {
        var session = new FakeRemoteSession(GoodDiscoveryJson());
        await using var connector = CreateConnector(session);
        await connector.ProbeAsync(CancellationToken.None);

        var first = connector.DescribePlan();
        var second = connector.DescribePlan();

        Assert.NotSame(first, second);
        Assert.Equal(
            ["backup", "write-companion", "write-environment", "write-override", "restart-disabled", "restart-enabled", "rollback-available"],
            first.Select(action => action.Code));
        Assert.Equal([false, false, false, false, true, true, false], first.Select(action => action.RestartsService));
        Assert.Equal([true, true, true, true, true, true, false], first.Select(action => action.Mutates));
        var exposed = Assert.IsAssignableFrom<IList<PlannedAction>>(first);
        Assert.Throws<NotSupportedException>(() => exposed[0] = new PlannedAction("altered", "/leak", true, false));
        Assert.DoesNotContain(first, action => action.Target.Contains("APOLLO_", StringComparison.Ordinal));
        Assert.DoesNotContain(first, action => action.Target.Contains("docker compose", StringComparison.Ordinal));
        Assert.DoesNotContain(first, action => action.Target.Contains(ImageReference, StringComparison.Ordinal));
    }

    [Fact]
    public async Task Wrong_image_is_unsupported_before_backup_or_upload()
    {
        var session = new FakeRemoteSession(MutateDiscovery(root =>
            root["services"]![0]!["imageDigest"] = "sha256:" + new string('0', 64)));
        await using var connector = CreateConnector(session);

        var result = await new InstallationOrchestrator(connector, Manifest).InstallAsync(CancellationToken.None);

        Assert.Equal(InstallerState.Unsupported, result.State);
        Assert.Equal("runtime-image-digest-mismatch", result.ReasonCode);
        Assert.Empty(session.Uploads);
        Assert.Empty(session.Mutations);
    }

    public static TheoryData<string> HostileDiscoveries => new()
    {
        MutateDiscovery(root => root["services"]!.AsArray().Add(root["services"]![0]!.DeepClone())),
        MutateDiscovery(root => root["services"]![0]!["entrypoint"]![0]!["path"] = "/bin/sh"),
        MutateDiscovery(root => root["services"]![0]!["cmd"] = new JsonArray("unexpected")),
        MutateDiscovery(root => root["services"]![0]!["serverRootIsSymlink"] = true),
        MutateDiscovery(root => root["services"]![0]!["workshopWritable"] = false),
        MutateDiscovery(root => root["services"]![0]!["serverRootReadOnly"] = false),
        MutateDiscovery(root => root["services"]![0]!["serverJarSha256"] = new string('0', 64)),
        MutateDiscovery(root => root["services"]![0]!["nativeManifestSha256"] = new string('1', 64)),
        MutateDiscovery(root => root["services"]![0]!["nativeLibrariesVerified"] = false),
        MutateDiscovery(root => root["services"]![0]!["imageReference"] = "ghcr.io/attacker/image@" + ImageDigest),
    };

    [Theory]
    [MemberData(nameof(HostileDiscoveries))]
    public async Task Ambiguous_or_untrusted_discovery_fails_before_any_write(string discoveryJson)
    {
        var session = new FakeRemoteSession(discoveryJson);
        await using var connector = CreateConnector(session);

        await Assert.ThrowsAsync<InvalidDataException>(() => connector.ProbeAsync(CancellationToken.None));

        Assert.Empty(session.Uploads);
        Assert.Empty(session.Mutations);
    }

    [Fact]
    public async Task Discovery_command_failure_fails_before_any_write()
    {
        var session = new FakeRemoteSession(GoodDiscoveryJson())
        {
            DiscoverResult = new RemoteResult(17, string.Empty, "inspect failed", false),
        };
        await using var connector = CreateConnector(session);

        await Assert.ThrowsAsync<InvalidDataException>(() => connector.ProbeAsync(CancellationToken.None));

        Assert.Empty(session.Uploads);
        Assert.Empty(session.Mutations);
    }

    [Fact]
    public async Task Disabled_stage_uploads_only_the_closed_companion_env_and_override_set()
    {
        var session = new FakeRemoteSession(GoodDiscoveryJson());
        await using var connector = CreateConnector(session);
        await connector.ProbeAsync(CancellationToken.None);

        var backup = await connector.BackupAsync(CancellationToken.None);
        await connector.StageDisabledAsync(CancellationToken.None);

        Assert.Equal("/srv/pz/.apollo-backups/20260811T120000Z", backup);
        Assert.Equal(
        new[]
        {
            "/srv/pz/.apollo-native/apollo-native-agent.jar",
            "/srv/pz/.apollo-native/apollo-native-entrypoint.sh",
            "/srv/pz/.apollo-native/fingerprint.properties",
            "/srv/pz/.apollo-native/native-libraries.sha256",
            "/srv/pz/.apollo-native.env",
            "/srv/pz/compose.apollo-native.yaml",
        }.Order(StringComparer.Ordinal),
        session.Uploads.Select(upload => upload.Destination).Order(StringComparer.Ordinal));
        Assert.DoesNotContain(session.Uploads, upload => upload.Destination == "/srv/pz/docker-compose.yml");

        var environment = Encoding.UTF8.GetString(session.Uploads.Single(upload => upload.Destination.EndsWith(".env", StringComparison.Ordinal)).Content);
        Assert.Contains("APOLLO_NATIVE_ASSIST=off\n", environment, StringComparison.Ordinal);
        Assert.DoesNotContain("__RESOLVE_", environment, StringComparison.Ordinal);
        Assert.DoesNotContain("__CAPTURE_", environment, StringComparison.Ordinal);
        Assert.Equal(
            [RemoteOperationKind.Discover, RemoteOperationKind.Backup, RemoteOperationKind.PrepareStaging],
            session.Operations.Select(operation => operation.Kind));
    }

    [Fact]
    public async Task Rollout_operations_are_bounded_to_the_selected_service_and_phase()
    {
        var session = new FakeRemoteSession(GoodDiscoveryJson());
        await using var connector = CreateConnector(session);
        await connector.ProbeAsync(CancellationToken.None);
        await connector.BackupAsync(CancellationToken.None);
        await connector.StageDisabledAsync(CancellationToken.None);

        await connector.ValidateMergedConfigurationAsync(CancellationToken.None);
        await connector.RestartAsync(CancellationToken.None);
        await connector.VerifyVanillaAsync(CancellationToken.None);
        await connector.EnableAsync(CancellationToken.None);
        await connector.RestartAsync(CancellationToken.None);
        await connector.VerifyReadyAsync(CancellationToken.None);

        Assert.Collection(
            session.Operations.Skip(3),
            operation => Assert.Equal(RemoteOperationKind.ValidateMergedConfiguration, operation.Kind),
            operation => Assert.Equal((RemoteOperationKind.RecreateService, "off"), (operation.Kind, operation.Phase)),
            operation => Assert.Equal(RemoteOperationKind.VerifyVanilla, operation.Kind),
            operation => Assert.Equal((RemoteOperationKind.SetKillSwitch, "on"), (operation.Kind, operation.Phase)),
            operation => Assert.Equal((RemoteOperationKind.RecreateService, "on"), (operation.Kind, operation.Phase)),
            operation => Assert.Equal(RemoteOperationKind.VerifyReady, operation.Kind));
        Assert.All(session.Operations, operation => Assert.Equal("project-zomboid", operation.ServiceName));
        Assert.All(
            session.Operations.Where(operation => operation.Kind is RemoteOperationKind.ValidateMergedConfiguration or
                RemoteOperationKind.RecreateService or RemoteOperationKind.VerifyVanilla or RemoteOperationKind.SetKillSwitch or
                RemoteOperationKind.VerifyReady),
            operation => Assert.Contains("/srv/pz/compose.apollo-native.yaml", operation.ComposeFiles));
        Assert.DoesNotContain(
            "/srv/pz/compose.apollo-native.yaml",
            session.Operations.Single(operation => operation.Kind == RemoteOperationKind.Discover).ComposeFiles);
    }

    [Fact]
    public async Task Disable_sets_only_off_and_recreate_uses_off_phase()
    {
        var session = new FakeRemoteSession(GoodDiscoveryJson());
        await using var connector = CreateConnector(session);
        await connector.ProbeAsync(CancellationToken.None);

        await connector.EnableAsync(CancellationToken.None);
        await connector.RestartAsync(CancellationToken.None);
        await connector.DisableAsync(CancellationToken.None);
        await connector.RestartAsync(CancellationToken.None);

        Assert.Equal(
            [
                (RemoteOperationKind.SetKillSwitch, "on"),
                (RemoteOperationKind.RecreateService, "on"),
                (RemoteOperationKind.SetKillSwitch, "off"),
                (RemoteOperationKind.RecreateService, "off"),
            ],
            session.Operations.Skip(1).Select(operation => (operation.Kind, operation.Phase)));
    }

    [Fact]
    public async Task Merged_compose_drift_fails_before_restart()
    {
        var session = new FakeRemoteSession(GoodDiscoveryJson())
        {
            MergedValidationResult = new RemoteResult(0, GoodMergedValidationJson(invariantSha256: new string('9', 64)), string.Empty, false),
        };
        await using var connector = CreateConnector(session);
        await connector.ProbeAsync(CancellationToken.None);
        await connector.BackupAsync(CancellationToken.None);
        await connector.StageDisabledAsync(CancellationToken.None);

        await Assert.ThrowsAsync<InvalidDataException>(() => connector.ValidateMergedConfigurationAsync(CancellationToken.None));

        Assert.DoesNotContain(session.Operations, operation => operation.Kind == RemoteOperationKind.RecreateService);
    }

    [Fact]
    public async Task Disabled_validation_rejects_enabled_phase_before_first_restart()
    {
        var session = new FakeRemoteSession(GoodDiscoveryJson())
        {
            MergedValidationResult = new RemoteResult(0, GoodMergedValidationJson(phase: "on"), string.Empty, false),
        };
        await using var connector = CreateConnector(session);
        await connector.ProbeAsync(CancellationToken.None);
        await connector.BackupAsync(CancellationToken.None);
        await connector.StageDisabledAsync(CancellationToken.None);

        await Assert.ThrowsAsync<InvalidDataException>(() => connector.ValidateMergedConfigurationAsync(CancellationToken.None));

        Assert.DoesNotContain(session.Operations, operation => operation.Kind == RemoteOperationKind.RecreateService);
    }

    [Fact]
    public void Release_bundle_snapshots_mutable_inputs()
    {
        var files = ReleaseFiles().ToList();
        var entrypoint = new List<RuntimeFileIdentity>
        {
            new("/bin/bash", "file", "0755", "7e8d290708f90eec5e87c6715df90140b7b02fb7a4deb3be08b71d201703ae58"),
            new("/home/steam/run_server.sh", "file", "0755", "7a173dfaa49f7270f542ae3ab8e12266a6ee63e07cbabec71d88f8f1e3169be8"),
        };
        var bundle = new NativeReleaseBundle(
            files,
            Encoding.UTF8.GetBytes("services:\n  project-zomboid: {}\n"),
            new NativeRuntimeContract(ServerJarSha, NativeManifestSha, entrypoint, true, "none-captured"));

        files.Clear();
        entrypoint.Clear();
        Assert.Equal(4, bundle.Files.Count);
        Assert.Equal(2, bundle.Runtime.Entrypoint.Count);
        Assert.False(bundle.Files is Dictionary<string, NativeReleaseFile>);
    }

    [Fact]
    public async Task Malformed_discovery_hash_is_normalized_to_invalid_data_before_writes()
    {
        var session = new FakeRemoteSession(MutateDiscovery(root => root["services"]![0]!["bootstrapEnv"]!["sha256"] = "ABC"));
        await using var connector = CreateConnector(session);

        await Assert.ThrowsAsync<InvalidDataException>(() => connector.ProbeAsync(CancellationToken.None));

        Assert.Empty(session.Uploads);
        Assert.Empty(session.Mutations);
    }

    private static DockerComposeConnector CreateConnector(FakeRemoteSession session)
        => new(
            session,
            Manifest,
            ReleaseBundle(),
            "/srv/pz",
            ["/srv/pz/docker-compose.yml"],
            "project-zomboid");

    private static NativeReleaseBundle ReleaseBundle()
    {
        return new NativeReleaseBundle(
        ReleaseFiles(),
        Encoding.UTF8.GetBytes("services:\n  project-zomboid: {}\n"),
        new NativeRuntimeContract(
            ServerJarSha,
            NativeManifestSha,
            [
                new RuntimeFileIdentity("/bin/bash", "file", "0755", "7e8d290708f90eec5e87c6715df90140b7b02fb7a4deb3be08b71d201703ae58"),
                new RuntimeFileIdentity("/home/steam/run_server.sh", "file", "0755", "7a173dfaa49f7270f542ae3ab8e12266a6ee63e07cbabec71d88f8f1e3169be8"),
            ],
            true,
            "none-captured"));
    }

    private static IReadOnlyList<NativeReleaseFile> ReleaseFiles()
    {
        static NativeReleaseFile File(string name, string content, UnixFileMode mode)
        {
            var bytes = Encoding.UTF8.GetBytes(content);
            return new NativeReleaseFile(name, bytes, mode, Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant());
        }

        return
        [
            File("apollo-native-agent.jar", "agent", UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.GroupRead | UnixFileMode.OtherRead),
            File("apollo-native-entrypoint.sh", "#!/bin/bash\n", UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute | UnixFileMode.GroupRead | UnixFileMode.GroupExecute | UnixFileMode.OtherRead | UnixFileMode.OtherExecute),
            File("fingerprint.properties", $"appId=380870\nimageReference={ImageReference}\n", UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.GroupRead | UnixFileMode.OtherRead),
            File("native-libraries.sha256", "native\n", UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.GroupRead | UnixFileMode.OtherRead),
        ];
    }

    private static string GoodDiscoveryJson() => JsonSerializer.Serialize(new
    {
        schemaVersion = 1,
        services = new[]
        {
            new
            {
                name = "project-zomboid",
                appId = "380870",
                buildId = "24775771",
                gameVersion = "42.20.3",
                javaFeature = 25,
                os = "linux",
                arch = "amd64",
                imageDigest = ImageDigest,
                imageReference = ImageReference,
                composeInvariantSha256 = new string('3', 64),
                otherServicesSha256 = new string('4', 64),
                cmd = (string[]?)null,
                entrypoint = new[]
                {
                    new { path = "/bin/bash", kind = "file", mode = "0755", sha256 = "7e8d290708f90eec5e87c6715df90140b7b02fb7a4deb3be08b71d201703ae58" },
                    new { path = "/home/steam/run_server.sh", kind = "file", mode = "0755", sha256 = "7a173dfaa49f7270f542ae3ab8e12266a6ee63e07cbabec71d88f8f1e3169be8" },
                },
                runtimeLockMode = "none-captured",
                serverJarSha256 = ServerJarSha,
                nativeManifestSha256 = NativeManifestSha,
                nativeLibrariesVerified = true,
                serverHostDirectory = "/srv/pz/server-files",
                serverRoot = "/home/steam/Zomboid/server-files",
                serverJarRelative = "projectzomboid.jar",
                serverRootIsSymlink = false,
                serverRootReadOnly = true,
                workshopHostDirectory = "/srv/pz/server-files/steamapps/workshop",
                workshopWritable = true,
                companionHostDirectory = "/srv/pz/.apollo-native",
                companionParentIsSymlink = false,
                bootstrapEnv = new { path = "/usr/bin/env", kind = "file", mode = "0755", sha256 = new string('2', 64) },
                bootstrapBash = new { path = "/bin/bash", kind = "file", mode = "0755", sha256 = "7e8d290708f90eec5e87c6715df90140b7b02fb7a4deb3be08b71d201703ae58" },
                restartPolicy = "unless-stopped",
                healthcheckConfigured = true,
            },
        },
    });

    private static string GoodMergedValidationJson(string? invariantSha256 = null, string phase = "off") => JsonSerializer.Serialize(new
    {
        schemaVersion = 1,
        serviceName = "project-zomboid",
        composeInvariantSha256 = invariantSha256 ?? new string('3', 64),
        otherServicesSha256 = new string('4', 64),
        imageReference = ImageReference,
        expectedPhase = phase,
        apolloEnvironment = GoodApolloEnvironment(phase),
        apolloMounts = new[]
        {
            new { source = "/srv/pz/.apollo-native", target = "/opt/apollo-native", readOnly = true },
            new { source = "/srv/pz/server-files", target = "/home/steam/Zomboid/server-files", readOnly = true },
            new { source = "/srv/pz/server-files/steamapps/workshop", target = "/home/steam/Zomboid/server-files/steamapps/workshop", readOnly = false },
        },
        apolloOverridePresent = true,
    });

    private static Dictionary<string, string> GoodApolloEnvironment(string phase)
    {
        var fingerprint = ReleaseFiles().Single(file => file.Name == "fingerprint.properties");
        return new Dictionary<string, string>(StringComparer.Ordinal)
        {
            ["APOLLO_NATIVE_ASSIST"] = phase,
            ["APOLLO_RUNTIME_LOCK_MODE"] = "none-captured",
            ["APOLLO_SERVER_ROOT"] = "/home/steam/Zomboid/server-files",
            ["APOLLO_SERVER_JAR_RELATIVE"] = "projectzomboid.jar",
            ["APOLLO_FINGERPRINT_FILE_SHA256"] = fingerprint.Sha256,
            ["APOLLO_BOOTSTRAP_ENV_PATH"] = "/usr/bin/env",
            ["APOLLO_BOOTSTRAP_ENV_KIND"] = "file",
            ["APOLLO_BOOTSTRAP_ENV_MODE"] = "0755",
            ["APOLLO_BOOTSTRAP_ENV_SHA256"] = new string('2', 64),
            ["APOLLO_BOOTSTRAP_BASH_PATH"] = "/bin/bash",
            ["APOLLO_BOOTSTRAP_BASH_KIND"] = "file",
            ["APOLLO_BOOTSTRAP_BASH_MODE"] = "0755",
            ["APOLLO_BOOTSTRAP_BASH_SHA256"] = "7e8d290708f90eec5e87c6715df90140b7b02fb7a4deb3be08b71d201703ae58",
            ["APOLLO_ORIGINAL_ENTRYPOINT_COUNT"] = "2",
            ["APOLLO_ORIGINAL_ENTRYPOINT_0_PATH"] = "/bin/bash",
            ["APOLLO_ORIGINAL_ENTRYPOINT_0_KIND"] = "file",
            ["APOLLO_ORIGINAL_ENTRYPOINT_0_MODE"] = "0755",
            ["APOLLO_ORIGINAL_ENTRYPOINT_0_SHA256"] = "7e8d290708f90eec5e87c6715df90140b7b02fb7a4deb3be08b71d201703ae58",
            ["APOLLO_ORIGINAL_ENTRYPOINT_1_PATH"] = "/home/steam/run_server.sh",
            ["APOLLO_ORIGINAL_ENTRYPOINT_1_KIND"] = "file",
            ["APOLLO_ORIGINAL_ENTRYPOINT_1_MODE"] = "0755",
            ["APOLLO_ORIGINAL_ENTRYPOINT_1_SHA256"] = "7a173dfaa49f7270f542ae3ab8e12266a6ee63e07cbabec71d88f8f1e3169be8",
        };
    }

    private static string MutateDiscovery(Action<JsonObject> mutation)
    {
        var root = JsonNode.Parse(GoodDiscoveryJson())!.AsObject();
        mutation(root);
        return root.ToJsonString();
    }

    private sealed class FakeRemoteSession(string discoveryJson) : IRemoteSession
    {
        public RemoteResult DiscoverResult { get; set; } = new(0, discoveryJson, string.Empty, false);
        public RemoteResult MergedValidationResult { get; set; } = new(0, GoodMergedValidationJson(), string.Empty, false);
        public List<RemoteOperation> Operations { get; } = [];
        public List<RemoteOperation> Mutations { get; } = [];
        public List<UploadRecord> Uploads { get; } = [];

        public Task<RemoteResult> RunAsync(RemoteOperation operation, CancellationToken cancellationToken)
        {
            Operations.Add(operation);
            if (operation.Kind is not RemoteOperationKind.Discover and
                not RemoteOperationKind.ValidateMergedConfiguration and
                not RemoteOperationKind.VerifyVanilla and
                not RemoteOperationKind.VerifyReady)
            {
                Mutations.Add(operation);
            }

            return Task.FromResult(operation.Kind switch
            {
                RemoteOperationKind.Discover => DiscoverResult,
                RemoteOperationKind.ValidateMergedConfiguration => MergedValidationResult,
                RemoteOperationKind.Backup => new RemoteResult(0, "/srv/pz/.apollo-backups/20260811T120000Z\n", string.Empty, false),
                _ => new RemoteResult(0, "ok\n", string.Empty, false),
            });
        }

        public async Task UploadAsync(Stream source, string absoluteDestination, UnixFileMode mode, CancellationToken cancellationToken)
        {
            using var memory = new MemoryStream();
            await source.CopyToAsync(memory, cancellationToken);
            Uploads.Add(new UploadRecord(absoluteDestination, memory.ToArray(), mode));
        }

        public Task DownloadAsync(string absoluteSource, Stream destination, CancellationToken cancellationToken)
            => throw new NotSupportedException();

        public ValueTask DisposeAsync() => ValueTask.CompletedTask;
    }

    private sealed record UploadRecord(string Destination, byte[] Content, UnixFileMode Mode);
}
