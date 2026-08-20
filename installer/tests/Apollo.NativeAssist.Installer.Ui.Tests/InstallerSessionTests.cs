using System.Text;
using Apollo.NativeAssist.Installer.Application;
using Apollo.NativeAssist.Installer.Core;
using Apollo.NativeAssist.Installer.Infrastructure.Docker;
using Apollo.NativeAssist.Installer.Infrastructure.Ssh;
using Apollo.NativeAssist.Installer.Release;
using Xunit;

namespace Apollo.NativeAssist.Installer.Ui.Tests;

public sealed class InstallerSessionTests : IDisposable
{
    private static readonly RuntimeProbe Runtime = new(
        "380870", "24775771", "42.20.3", 25, "linux", "amd64", "sha256:" + new string('a', 64));
    private static readonly SshHostKeyObservation Observation = new("ssh-ed25519", Convert.ToBase64String(new byte[32]).TrimEnd('='));
    private readonly string temporaryDirectory = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "apollo-session-tests-" + Guid.NewGuid().ToString("N"));

    public InstallerSessionTests() => Directory.CreateDirectory(temporaryDirectory);

    [Fact]
    public async Task Factory_never_requests_credentials_before_host_key_confirmation()
    {
        var source = new CountingCredentialSource(CredentialInput.ForPassword("sentinel-" + "password"));
        var factory = CreateFactory(source: source);

        var observed = await factory.ObserveHostKeyAsync(ValidInput(), CancellationToken.None);

        Assert.Equal(Observation, observed);
        Assert.Equal(0, source.ReadCount);
        await using var session = await factory.CreateConfirmedAsync(ValidInput(), Observation, source, CancellationToken.None);
        Assert.Equal(1, source.ReadCount);
    }

    [Fact]
    public async Task Confirmation_requires_the_exact_latest_observation_and_matching_input()
    {
        var source = new CountingCredentialSource(CredentialInput.ForPassword("sentinel-" + "password"));
        var factory = CreateFactory(source: source);
        await factory.ObserveHostKeyAsync(ValidInput(), CancellationToken.None);

        await Assert.ThrowsAsync<InvalidOperationException>(() => factory.CreateConfirmedAsync(
            ValidInput(), Observation with { Algorithm = "ssh-rsa" }, source, CancellationToken.None));
        await Assert.ThrowsAsync<InvalidOperationException>(() => factory.CreateConfirmedAsync(
            ValidInput() with { Host = "other.example.test" }, Observation, source, CancellationToken.None));

        Assert.Equal(0, source.ReadCount);
    }

    [Fact]
    public async Task Failed_confirmation_consumes_observation_before_a_correct_retry()
    {
        var source = new CountingCredentialSource(CredentialInput.ForPassword("sentinel-" + "password"));
        var factory = CreateFactory(source: source);
        await factory.ObserveHostKeyAsync(ValidInput(), CancellationToken.None);

        await Assert.ThrowsAsync<InvalidOperationException>(() => factory.CreateConfirmedAsync(
            ValidInput(), Observation with { Algorithm = "ssh-rsa" }, source, CancellationToken.None));
        await Assert.ThrowsAsync<InvalidOperationException>(() => factory.CreateConfirmedAsync(
            ValidInput(), Observation, source, CancellationToken.None));

        Assert.Equal(0, source.ReadCount);
        await factory.ObserveHostKeyAsync(ValidInput(), CancellationToken.None);
        await using var session = await factory.CreateConfirmedAsync(
            ValidInput(), Observation, source, CancellationToken.None);
        Assert.Equal(1, source.ReadCount);
    }

    [Fact]
    public async Task Cancelled_confirmation_consumes_observation_without_reading_credentials()
    {
        var source = new CountingCredentialSource(CredentialInput.ForPassword("sentinel-" + "password"));
        var factory = CreateFactory(source: source);
        await factory.ObserveHostKeyAsync(ValidInput(), CancellationToken.None);
        using var cancellation = new CancellationTokenSource();
        cancellation.Cancel();

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => factory.CreateConfirmedAsync(
            ValidInput(), Observation, source, cancellation.Token));
        await Assert.ThrowsAsync<InvalidOperationException>(() => factory.CreateConfirmedAsync(
            ValidInput(), Observation, source, CancellationToken.None));

        Assert.Equal(0, source.ReadCount);
    }

    [Theory]
    [InlineData(0)]
    [InlineData(9)]
    public void Connection_rejects_compose_counts_outside_one_to_eight(int count)
    {
        var files = Enumerable.Range(0, count).Select(index => $"compose-{index}.yaml").ToArray();
        Assert.Throws<ArgumentException>(() => ValidInput() with { ComposeFiles = files });
    }

    [Theory]
    [InlineData("../compose.yaml")]
    [InlineData("/srv/pz/compose.yaml")]
    [InlineData("nested\\compose.yaml")]
    [InlineData("compose.yaml/../escape.yaml")]
    public void Connection_rejects_noncanonical_relative_compose_paths(string path)
    {
        Assert.Throws<ArgumentException>(() => ValidInput() with { ComposeFiles = new[] { path } });
    }

    [Fact]
    public void Connection_requires_unique_compose_paths_and_a_safe_service_name()
    {
        Assert.Throws<ArgumentException>(() => ValidInput() with { ComposeFiles = new[] { "compose.yaml", "compose.yaml" } });
        Assert.Throws<ArgumentException>(() => ValidInput() with { ServiceName = "server; reboot" });
    }

    [Fact]
    public async Task Connector_receives_canonical_absolute_compose_paths_inside_deployment_directory()
    {
        RemoteOperation? operation = null;
        var remote = new FakeRemoteSession
        {
            Run = value =>
            {
                operation = value;
                throw new InvalidDataException("stop after observing request");
            },
        };
        var source = new CountingCredentialSource(CredentialInput.ForPassword("sentinel-" + "password"));
        var factory = CreateFactory(source, remote: remote);
        var input = ValidInput() with { ComposeFiles = new[] { "compose.yaml", "deploy/override.yaml" } };
        await factory.ObserveHostKeyAsync(input, CancellationToken.None);
        await using var session = await factory.CreateConfirmedAsync(input, Observation, source, CancellationToken.None);

        var preview = await session.PreviewAsync(CancellationToken.None);

        Assert.False(preview.Supported);
        Assert.NotNull(operation);
        Assert.Equal(new[] { "/srv/pz/compose.yaml", "/srv/pz/deploy/override.yaml" }, operation!.ComposeFiles);
    }

    [Fact]
    public async Task Password_and_private_key_inputs_are_mutually_exclusive()
    {
        var source =
            new CountingCredentialSource(new CredentialInput(
            ("sentinel-" + "password").ToCharArray(), "key.pem".ToCharArray(), null));
        var factory = CreateFactory(source: source);
        await factory.ObserveHostKeyAsync(ValidInput(), CancellationToken.None);

        await Assert.ThrowsAsync<ArgumentException>(() => factory.CreateConfirmedAsync(
            ValidInput(), Observation, source, CancellationToken.None));

        Assert.True(source.LastCredential!.IsCleared);
    }

    [Fact]
    public async Task Credential_input_takes_ownership_and_zeroes_caller_buffers()
    {
        var sampleCharacters = ("sentinel-" + "password").ToCharArray();
        var source = new CountingCredentialSource(new CredentialInput(sampleCharacters, null, null));
        var factory = CreateFactory(source: source);
        await factory.ObserveHostKeyAsync(ValidInput(), CancellationToken.None);

        await using var session = await factory.CreateConfirmedAsync(
            ValidInput(), Observation, source, CancellationToken.None);

        Assert.All(sampleCharacters, character => Assert.Equal('\0', character));
    }

    [Fact]
    public async Task Private_key_file_is_bounded_to_one_mebibyte()
    {
        var keyPath = System.IO.Path.Combine(temporaryDirectory, "oversized.pem");
        await File.WriteAllBytesAsync(keyPath, new byte[(1024 * 1024) + 1], TestContext.Current.CancellationToken);
        var source = new CountingCredentialSource(CredentialInput.ForPrivateKey(keyPath));
        var factory = CreateFactory(source: source);
        var input = ValidInput() with { AuthenticationMode = AuthenticationMode.PrivateKey };
        await factory.ObserveHostKeyAsync(input, CancellationToken.None);

        var exception = await Assert.ThrowsAsync<InvalidDataException>(() => factory.CreateConfirmedAsync(
            input, Observation, source, CancellationToken.None));

        AssertSanitizedKeyFailure(exception, "private-key-file-size-invalid", keyPath);
        Assert.True(source.LastCredential!.IsCleared);
    }

    [Fact]
    public async Task Regular_private_key_reaches_the_bounded_handle_read()
    {
        var keyPath = System.IO.Path.Combine(temporaryDirectory, "regular-key-sentinel.pem");
        await File.WriteAllBytesAsync(keyPath, Encoding.UTF8.GetBytes("PRIVATE KEY"), TestContext.Current.CancellationToken);
        var source = new CountingCredentialSource(CredentialInput.ForPrivateKey(keyPath));
        var factory = CreateFactory(source: source);
        var input = ValidInput() with { AuthenticationMode = AuthenticationMode.PrivateKey };
        await factory.ObserveHostKeyAsync(input, CancellationToken.None);

        await using var session = await factory.CreateConfirmedAsync(
            input, Observation, source, CancellationToken.None);

        Assert.Equal(1, source.ReadCount);
        Assert.True(source.LastCredential!.IsCleared);
    }

    [Fact]
    public async Task Missing_private_key_path_never_escapes_in_exception_rendering()
    {
        var keyPath = System.IO.Path.Combine(temporaryDirectory, "missing-key-sentinel.pem");
        var source = new CountingCredentialSource(CredentialInput.ForPrivateKey(keyPath));
        var factory = CreateFactory(source: source);
        var input = ValidInput() with { AuthenticationMode = AuthenticationMode.PrivateKey };
        await factory.ObserveHostKeyAsync(input, CancellationToken.None);

        var exception = await Assert.ThrowsAsync<InvalidDataException>(() => factory.CreateConfirmedAsync(
            input, Observation, source, CancellationToken.None));

        AssertSanitizedKeyFailure(exception, "private-key-file-invalid", keyPath);
        Assert.True(source.LastCredential!.IsCleared);
    }

    [Fact]
    public async Task Directory_private_key_path_never_escapes_in_exception_rendering()
    {
        var keyPath = System.IO.Path.Combine(temporaryDirectory, "directory-key-sentinel");
        Directory.CreateDirectory(keyPath);
        var source = new CountingCredentialSource(CredentialInput.ForPrivateKey(keyPath));
        var factory = CreateFactory(source: source);
        var input = ValidInput() with { AuthenticationMode = AuthenticationMode.PrivateKey };
        await factory.ObserveHostKeyAsync(input, CancellationToken.None);

        var exception = await Assert.ThrowsAsync<InvalidDataException>(() => factory.CreateConfirmedAsync(
            input, Observation, source, CancellationToken.None));

        AssertSanitizedKeyFailure(exception, "private-key-file-invalid", keyPath);
        Assert.True(source.LastCredential!.IsCleared);
    }

    [Fact]
    public async Task Locked_private_key_path_never_escapes_in_exception_rendering()
    {
        if (!OperatingSystem.IsWindows())
        {
            return;
        }

        var keyPath = System.IO.Path.Combine(temporaryDirectory, "locked-key-sentinel.pem");
        await File.WriteAllBytesAsync(keyPath, Encoding.UTF8.GetBytes("PRIVATE KEY"), TestContext.Current.CancellationToken);
        using var locked = new FileStream(keyPath, FileMode.Open, FileAccess.ReadWrite, FileShare.None);
        var source = new CountingCredentialSource(CredentialInput.ForPrivateKey(keyPath));
        var factory = CreateFactory(source: source);
        var input = ValidInput() with { AuthenticationMode = AuthenticationMode.PrivateKey };
        await factory.ObserveHostKeyAsync(input, CancellationToken.None);

        var exception = await Assert.ThrowsAsync<InvalidDataException>(() => factory.CreateConfirmedAsync(
            input, Observation, source, CancellationToken.None));

        AssertSanitizedKeyFailure(exception, "private-key-file-invalid", keyPath);
        Assert.True(source.LastCredential!.IsCleared);
    }

    [Fact]
    public async Task Private_key_read_failure_never_escapes_path_or_raw_exception()
    {
        var keyPath = System.IO.Path.Combine(temporaryDirectory, "read-failure-key-sentinel.pem");
        var keyPathBuffer = keyPath.ToCharArray();
        var passphraseBuffer = "passphrase-sentinel".ToCharArray();
        var source = new CountingCredentialSource(new CredentialInput(null, keyPathBuffer, passphraseBuffer));
        var factory = CreateFactory(
            source,
            privateKeyReader: path => throw new IOException("raw read failure for " + path));
        var input = ValidInput() with { AuthenticationMode = AuthenticationMode.PrivateKey };
        await factory.ObserveHostKeyAsync(input, CancellationToken.None);

        var exception = await Assert.ThrowsAsync<InvalidDataException>(() => factory.CreateConfirmedAsync(
            input, Observation, source, CancellationToken.None));

        AssertSanitizedKeyFailure(exception, "private-key-file-invalid", keyPath);
        Assert.Equal(1, source.ReadCount);
        Assert.All(keyPathBuffer, character => Assert.Equal('\0', character));
        Assert.All(passphraseBuffer, character => Assert.Equal('\0', character));
    }

    [Fact]
    public async Task Cancellation_during_private_key_read_disposes_buffers_before_session_creation()
    {
        var keyPathBuffer = "cancelled-key-path-sentinel.pem".ToCharArray();
        var passphraseBuffer = "cancelled-passphrase-sentinel".ToCharArray();
        var source = new CountingCredentialSource(new CredentialInput(null, keyPathBuffer, passphraseBuffer));
        using var cancellation = new CancellationTokenSource();
        var factory = CreateFactory(
            source,
            privateKeyReader: _ =>
            {
                cancellation.Cancel();
                return Encoding.UTF8.GetBytes("PRIVATE KEY");
            });
        var input = ValidInput() with { AuthenticationMode = AuthenticationMode.PrivateKey };
        await factory.ObserveHostKeyAsync(input, CancellationToken.None);

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => factory.CreateConfirmedAsync(
            input, Observation, source, cancellation.Token));

        Assert.Equal(1, source.ReadCount);
        Assert.All(keyPathBuffer, character => Assert.Equal('\0', character));
        Assert.All(passphraseBuffer, character => Assert.Equal('\0', character));
    }

    [Fact]
    public async Task Private_key_symlink_or_reparse_point_is_rejected()
    {
        var target = System.IO.Path.Combine(temporaryDirectory, "target.pem");
        var link = System.IO.Path.Combine(temporaryDirectory, "link.pem");
        await File.WriteAllTextAsync(target, "PRIVATE KEY", TestContext.Current.CancellationToken);
        File.CreateSymbolicLink(link, target);
        Assert.True(File.GetAttributes(link).HasFlag(FileAttributes.ReparsePoint));

        var source = new CountingCredentialSource(CredentialInput.ForPrivateKey(link));
        var factory = CreateFactory(source: source);
        var input = ValidInput() with { AuthenticationMode = AuthenticationMode.PrivateKey };
        await factory.ObserveHostKeyAsync(input, CancellationToken.None);

        var exception = await Assert.ThrowsAsync<InvalidDataException>(() => factory.CreateConfirmedAsync(
            input, Observation, source, CancellationToken.None));
        AssertSanitizedKeyFailure(exception, "private-key-file-invalid", link);
        Assert.True(source.LastCredential!.IsCleared);
    }

    [Fact]
    public async Task Session_saves_recovery_metadata_immediately_after_backup_and_disposes_connector()
    {
        var connector = new FakeConnector(Runtime);
        var source = new CountingCredentialSource(CredentialInput.ForPassword("sentinel-" + "password"));
        var store = new RecoveryRecordStore(System.IO.Path.Combine(temporaryDirectory, "recovery.json"));
        var factory = CreateFactory(source, store: store, connector: connector);
        await factory.ObserveHostKeyAsync(ValidInput(), CancellationToken.None);

        await using (var session = await factory.CreateConfirmedAsync(ValidInput(), Observation, source, CancellationToken.None))
        {
            var result = await session.InstallAsync(CancellationToken.None);
            Assert.Equal(InstallerState.Ready, result.State);
            var loaded = store.Load();
            Assert.Equal("recovery-record-loaded", loaded.ReasonCode);
            Assert.Equal(connector.BackupPath, loaded.Record!.BackupPath);
        }

        Assert.True(connector.Disposed);
    }

    [Fact]
    public async Task Session_disposal_releases_transport_even_when_injected_connector_does_not_own_it()
    {
        var connector = new FakeConnector(Runtime);
        var remote = new FakeRemoteSession();
        var source = new CountingCredentialSource(CredentialInput.ForPassword("sentinel-" + "password"));
        var factory = CreateFactory(source, remote: remote, connector: connector);
        await factory.ObserveHostKeyAsync(ValidInput(), CancellationToken.None);

        var session = await factory.CreateConfirmedAsync(ValidInput(), Observation, source, CancellationToken.None);
        await session.DisposeAsync();

        Assert.True(connector.Disposed);
        Assert.True(remote.Disposed);
    }

    [Fact]
    public void Recovery_record_contains_only_the_exact_nonsecret_schema()
    {
        var store = new RecoveryRecordStore(System.IO.Path.Combine(temporaryDirectory, "recovery.json"));
        store.Save(ValidRecord());

        var json = File.ReadAllText(store.Path);

        Assert.DoesNotContain("sentinel-" + "password", json, StringComparison.Ordinal);
        Assert.DoesNotContain("PRIVATE KEY", json, StringComparison.Ordinal);
        Assert.DoesNotContain("docker compose", json, StringComparison.Ordinal);
        Assert.Equal(
            new[] { "backupPath", "composeFiles", "deploymentDirectory", "host", "hostKeySha256", "port", "serviceName", "username" },
            System.Text.Json.JsonDocument.Parse(json).RootElement.EnumerateObject().Select(property => property.Name).Order(StringComparer.Ordinal));
        Assert.Empty(Directory.EnumerateFiles(temporaryDirectory, "*.tmp", SearchOption.TopDirectoryOnly));
    }

    [Fact]
    public void Recovery_record_is_owner_only_when_unix_modes_are_available()
    {
        var store = new RecoveryRecordStore(System.IO.Path.Combine(temporaryDirectory, "recovery.json"));
        store.Save(ValidRecord());

        if (!OperatingSystem.IsWindows())
        {
            Assert.Equal(UnixFileMode.UserRead | UnixFileMode.UserWrite, File.GetUnixFileMode(store.Path));
        }
    }

    [Theory]
    [InlineData("{not-json")]
    [InlineData("{\"host\":\"pz.example.test\",\"host\":\"other.example.test\"}")]
    [InlineData("{\"host\":\"pz.example.test\",\"unknown\":1}")]
    public void Corrupt_duplicate_or_unknown_recovery_json_fails_closed_with_one_reason(string json)
    {
        var store = new RecoveryRecordStore(System.IO.Path.Combine(temporaryDirectory, "recovery.json"));
        File.WriteAllText(store.Path, json);

        var result = store.Load();

        Assert.Null(result.Record);
        Assert.Equal("recovery-record-invalid", result.ReasonCode);
    }

    [Fact]
    public void Recovery_load_revalidates_endpoint_paths_service_and_backup_scope()
    {
        var store = new RecoveryRecordStore(System.IO.Path.Combine(temporaryDirectory, "recovery.json"));
        var invalid = ValidRecord() with { BackupPath = "/srv/other/backup" };
        File.WriteAllText(store.Path, System.Text.Json.JsonSerializer.Serialize(invalid, new System.Text.Json.JsonSerializerOptions
        {
            PropertyNamingPolicy = System.Text.Json.JsonNamingPolicy.CamelCase,
        }));

        var result = store.Load();

        Assert.Null(result.Record);
        Assert.Equal("recovery-record-invalid", result.ReasonCode);
    }

    public void Dispose()
    {
        if (Directory.Exists(temporaryDirectory))
        {
            Directory.Delete(temporaryDirectory, recursive: true);
        }
    }

    private InstallerSessionFactory CreateFactory(
        CountingCredentialSource? source = null,
        RecoveryRecordStore? store = null,
        FakeRemoteSession? remote = null,
        FakeConnector? connector = null,
        Func<string, byte[]>? privateKeyReader = null)
    {
        _ = source;
        remote ??= new FakeRemoteSession();
        return privateKeyReader is null
            ? new InstallerSessionFactory(
                new FakeHostKeyProbe(Observation),
                CreateRelease(),
                store ?? new RecoveryRecordStore(System.IO.Path.Combine(temporaryDirectory, "recovery.json")),
                _ => remote,
                connector is null ? null : (_, _, _, _) => connector,
                TimeSpan.FromSeconds(5))
            : new InstallerSessionFactory(
                new FakeHostKeyProbe(Observation),
                CreateRelease(),
                store ?? new RecoveryRecordStore(System.IO.Path.Combine(temporaryDirectory, "recovery.json")),
                _ => remote,
                connector is null ? null : (_, _, _, _) => connector,
                TimeSpan.FromSeconds(5),
                privateKeyReader);
    }

    private static ConnectionInput ValidInput() => new(
        "pz.example.test", 22, "steam", AuthenticationMode.Password,
        "/srv/pz", new[] { "compose.yaml" }, "pz-server");

    private static RecoveryRecord ValidRecord() => new(
        "pz.example.test", 22, "steam", Observation.Sha256,
        "/srv/pz", new[] { "compose.yaml" }, "pz-server", "/srv/pz/.apollo-backups/20260812T120000Z");

    private static void AssertSanitizedKeyFailure(Exception exception, string reasonCode, string sentinelPath)
    {
        Assert.Equal(reasonCode, exception.Message);
        Assert.Null(exception.InnerException);
        Assert.DoesNotContain(sentinelPath, exception.ToString(), StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain(System.IO.Path.GetFileName(sentinelPath), exception.ToString(), StringComparison.OrdinalIgnoreCase);
    }

    private static EmbeddedInstallerRelease CreateRelease()
    {
        static NativeReleaseFile File(string name, string contents)
        {
            var bytes = Encoding.UTF8.GetBytes(contents);
            var sha = Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(bytes)).ToLowerInvariant();
            return new NativeReleaseFile(name, bytes, UnixFileMode.UserRead | UnixFileMode.UserWrite, sha);
        }

        var fingerprint = "imageReference=example.invalid/pz@" + Runtime.ImageDigest + "\n";
        var bundle = new NativeReleaseBundle(
            [
                File("apollo-native-agent.jar", "jar"),
                File("apollo-native-entrypoint.sh", "script"),
                File("fingerprint.properties", fingerprint),
                File("native-libraries.sha256", "manifest"),
            ],
            Encoding.UTF8.GetBytes("services: {}\n"),
            new NativeRuntimeContract(
                new string('b', 64), new string('c', 64),
                [new RuntimeFileIdentity("/bin/bash", "file", "0755", new string('d', 64))], true, "none-captured"));
        return new EmbeddedInstallerRelease(new VerifiedReleaseManifest(Runtime), bundle, "test");
    }

    private sealed class FakeHostKeyProbe(SshHostKeyObservation observation) : IHostKeyProbe
    {
        public Task<SshHostKeyObservation> ObserveAsync(
            string host, int port, string username, TimeSpan timeout, CancellationToken cancellationToken)
            => Task.FromResult(observation);
    }

    private sealed class CountingCredentialSource(CredentialInput credential) : ICredentialSource
    {
        public int ReadCount { get; private set; }
        public CredentialInput? LastCredential { get; private set; }

        public CredentialInput Read()
        {
            ReadCount++;
            this.LastCredential = credential;
            return credential;
        }
    }

    private sealed class FakeRemoteSession : IRemoteSession
    {
        public Func<RemoteOperation, RemoteResult> Run { get; init; } = _ => throw new InvalidDataException("not configured");
        public bool Disposed { get; private set; }
        public Task<RemoteResult> RunAsync(RemoteOperation operation, CancellationToken cancellationToken) => Task.FromResult(Run(operation));
        public Task UploadAsync(Stream source, string absoluteDestination, UnixFileMode mode, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task DownloadAsync(string absoluteSource, Stream destination, CancellationToken cancellationToken) => Task.CompletedTask;
        public ValueTask DisposeAsync() { Disposed = true; return ValueTask.CompletedTask; }
    }

    private sealed class FakeConnector(RuntimeProbe runtime) : IServerConnector
    {
        public string BackupPath { get; } = "/srv/pz/.apollo-backups/20260812T120000Z";
        public bool Disposed { get; private set; }
        public Task<RuntimeProbe> ProbeAsync(CancellationToken cancellationToken) => Task.FromResult(runtime);
        public IReadOnlyList<PlannedAction> DescribePlan() => [];
        public Task<string> BackupAsync(CancellationToken cancellationToken) => Task.FromResult(BackupPath);
        public Task StageDisabledAsync(CancellationToken cancellationToken) => Task.CompletedTask;
        public Task ValidateMergedConfigurationAsync(CancellationToken cancellationToken) => Task.CompletedTask;
        public Task RestartAsync(CancellationToken cancellationToken) => Task.CompletedTask;
        public Task VerifyVanillaAsync(CancellationToken cancellationToken) => Task.CompletedTask;
        public Task EnableAsync(CancellationToken cancellationToken) => Task.CompletedTask;
        public Task DisableAsync(CancellationToken cancellationToken) => Task.CompletedTask;
        public Task VerifyReadyAsync(CancellationToken cancellationToken) => Task.CompletedTask;
        public Task RollbackAsync(string backupPath, CancellationToken cancellationToken) => Task.CompletedTask;
        public ValueTask DisposeAsync() { Disposed = true; return ValueTask.CompletedTask; }
    }
}
