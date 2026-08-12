using Apollo.NativeAssist.Installer.Core;
using Xunit;

namespace Apollo.NativeAssist.Installer.Core.Tests;

public sealed class InstallationOrchestratorTests
{
    private static readonly RuntimeProbe ExpectedRuntime = new(
        "380870",
        "24574884",
        "42.20.2",
        25,
        "linux",
        "amd64",
        "ghcr.io/renegade-master/zomboid-dedicated-server@sha256:5e3479ea2ef66a4f14686fd3abc3286cf31a82c0e37f737b4b5976ff37da9951");

    private static readonly VerifiedReleaseManifest Manifest = new(ExpectedRuntime);

    public static TheoryData<RuntimeProbe, string> RuntimeMismatches => new()
    {
        { ExpectedRuntime with { AppId = "108600" }, "runtime-app-id-mismatch" },
        { ExpectedRuntime with { BuildId = "24574885" }, "runtime-build-id-mismatch" },
        { ExpectedRuntime with { GameVersion = "42.20.3" }, "runtime-game-version-mismatch" },
        { ExpectedRuntime with { JavaFeature = 24 }, "runtime-java-feature-mismatch" },
        { ExpectedRuntime with { Os = "windows" }, "runtime-os-mismatch" },
        { ExpectedRuntime with { Arch = "arm64" }, "runtime-arch-mismatch" },
        { ExpectedRuntime with { ImageDigest = "sha256:wrong" }, "runtime-image-digest-mismatch" },
    };

    [Fact]
    public async Task Preview_is_read_only_and_returns_exact_connector_plan()
    {
        var connector = new FakeConnector(ExpectedRuntime);

        var preview = await new InstallationOrchestrator(connector, Manifest)
            .PreviewAsync(CancellationToken.None);

        Assert.True(preview.Supported);
        Assert.Equal("supported", preview.ReasonCode);
        Assert.Equal(
            ["backup", "write-companion", "restart-disabled", "restart-enabled"],
            preview.Actions.Select(action => action.Code));
        Assert.Empty(connector.Events);
    }

    [Fact]
    public async Task Preview_snapshots_a_mutable_connector_plan_into_a_read_only_collection()
    {
        var connector = new FakeConnector(ExpectedRuntime);
        var preview = await new InstallationOrchestrator(connector, Manifest)
            .PreviewAsync(CancellationToken.None);

        connector.Plan[0] = new PlannedAction("altered", "/srv/pz/altered", true, false);
        connector.Plan.Add(new PlannedAction("extra", "/srv/pz/extra", true, false));

        Assert.Equal("backup", preview.Actions[0].Code);
        Assert.Equal(4, preview.Actions.Count);
        var exposedPlan = Assert.IsAssignableFrom<IList<PlannedAction>>(preview.Actions);
        Assert.Throws<NotSupportedException>(() => exposedPlan[0] = new PlannedAction("altered", "/srv/pz/altered", true, false));
    }

    [Fact]
    public async Task Unsupported_preview_returns_no_actions_and_performs_zero_writes()
    {
        var connector = new FakeConnector(ExpectedRuntime with { BuildId = "wrong" });

        var preview = await new InstallationOrchestrator(connector, Manifest)
            .PreviewAsync(CancellationToken.None);

        Assert.False(preview.Supported);
        Assert.Equal("runtime-build-id-mismatch", preview.ReasonCode);
        Assert.Empty(preview.Actions);
        Assert.Empty(connector.Events);
    }

    [Fact]
    public async Task Preview_cancellation_after_probe_is_propagated_without_writes()
    {
        using var cancellation = new CancellationTokenSource();
        var connector = new FakeConnector(ExpectedRuntime).CancelAfter("probe", cancellation);

        await Assert.ThrowsAsync<OperationCanceledException>(() => new InstallationOrchestrator(connector, Manifest)
            .PreviewAsync(cancellation.Token));

        Assert.Empty(connector.Events);
    }

    [Fact]
    public async Task Safe_disable_writes_off_then_restarts_and_verifies_vanilla()
    {
        var connector = new FakeConnector(ExpectedRuntime);

        var result = await new InstallationOrchestrator(connector, Manifest)
            .DisableAsync(CancellationToken.None);

        Assert.Equal(InstallerState.Disabled, result.State);
        Assert.Equal("disabled", result.ReasonCode);
        Assert.Equal(["disable", "restart-off", "verify-vanilla"], connector.Events);
        Assert.False(connector.RestartTokenCanBeCanceled);
        Assert.False(connector.VanillaVerificationTokenCanBeCanceled);
    }

    [Fact]
    public async Task Disable_failure_does_not_restart_or_reenable()
    {
        var connector = new FakeConnector(ExpectedRuntime).FailAt("disable");

        var result = await new InstallationOrchestrator(connector, Manifest)
            .DisableAsync(CancellationToken.None);

        Assert.Equal(new InstallationResult(InstallerState.Failed, "disable-failed", null), result);
        Assert.Equal(["disable"], connector.Events);
        Assert.Equal(0, connector.RestartCalls);
        Assert.Equal(0, connector.OperationCalls("enable"));
    }

    [Fact]
    public async Task Cancellation_before_disable_returns_cancelled_without_mutation()
    {
        using var cancellation = new CancellationTokenSource();
        cancellation.Cancel();
        var connector = new FakeConnector(ExpectedRuntime);

        var result = await new InstallationOrchestrator(connector, Manifest)
            .DisableAsync(cancellation.Token);

        Assert.Equal(new InstallationResult(InstallerState.Cancelled, "cancelled", null), result);
        Assert.Equal(0, connector.OperationCalls("probe"));
        Assert.Empty(connector.Events);
    }

    [Fact]
    public async Task Cancellation_after_disable_keeps_recovery_sequence_non_cancelled()
    {
        using var cancellation = new CancellationTokenSource();
        var connector = new FakeConnector(ExpectedRuntime).CancelAfter("disable", cancellation);

        var result = await new InstallationOrchestrator(connector, Manifest)
            .DisableAsync(cancellation.Token);

        Assert.Equal(new InstallationResult(InstallerState.Disabled, "disabled", null), result);
        Assert.Equal(["disable", "restart-off", "verify-vanilla"], connector.Events);
        Assert.False(connector.RestartTokenCanBeCanceled);
        Assert.False(connector.VanillaVerificationTokenCanBeCanceled);
    }

    [Fact]
    public async Task Safe_disable_uses_a_mismatched_runtime_only_as_a_fresh_snapshot()
    {
        var connector = new FakeConnector(ExpectedRuntime with { BuildId = "different-build" });

        var result = await new InstallationOrchestrator(connector, Manifest)
            .DisableAsync(CancellationToken.None);

        Assert.Equal(new InstallationResult(InstallerState.Disabled, "disabled", null), result);
        Assert.Equal(1, connector.OperationCalls("probe"));
        Assert.Equal(["disable", "restart-off", "verify-vanilla"], connector.Events);
    }

    [Fact]
    public async Task Explicit_restore_uses_one_non_cancelled_attempt()
    {
        var connector = new FakeConnector(ExpectedRuntime);

        var result = await new InstallationOrchestrator(connector, Manifest)
            .RestoreAsync("/srv/pz/.apollo-backups/20260811T120000Z", CancellationToken.None);

        Assert.Equal(InstallerState.RolledBack, result.State);
        Assert.Equal("restored", result.ReasonCode);
        Assert.Equal(1, connector.RollbackCalls);
        Assert.Equal("/srv/pz/.apollo-backups/20260811T120000Z", connector.RollbackBackupPath);
        Assert.False(connector.RollbackTokenCanBeCanceled);
    }

    [Fact]
    public async Task Explicit_restore_after_successful_probe_uses_one_non_cancelled_attempt()
    {
        using var cancellation = new CancellationTokenSource();
        var connector = new FakeConnector(ExpectedRuntime).CancelAfter("probe", cancellation);

        var result = await new InstallationOrchestrator(connector, Manifest)
            .RestoreAsync("/srv/pz/.apollo-backups/20260811T120000Z", cancellation.Token);

        Assert.Equal(new InstallationResult(InstallerState.RolledBack, "restored", null), result);
        Assert.Equal(1, connector.RollbackCalls);
        Assert.False(connector.RollbackTokenCanBeCanceled);
    }

    [Theory]
    [InlineData("")]
    [InlineData(" ")]
    public async Task Explicit_restore_rejects_blank_backup_path_without_probing(string backupPath)
    {
        var connector = new FakeConnector(ExpectedRuntime);

        var result = await new InstallationOrchestrator(connector, Manifest)
            .RestoreAsync(backupPath, CancellationToken.None);

        Assert.Equal(new InstallationResult(InstallerState.Failed, "restore-path-invalid", null), result);
        Assert.Equal(0, connector.OperationCalls("probe"));
        Assert.Equal(0, connector.RollbackCalls);
    }

    [Fact]
    public async Task Restore_failure_is_reported_without_retry()
    {
        var connector = new FakeConnector(ExpectedRuntime).FailAt("rollback");

        var result = await new InstallationOrchestrator(connector, Manifest)
            .RestoreAsync("/srv/pz/.apollo-backups/20260811T120000Z", CancellationToken.None);

        Assert.Equal(new InstallationResult(InstallerState.Failed, "restore-failed", null), result);
        Assert.Equal(1, connector.RollbackCalls);
    }

    [Fact]
    public async Task Explicit_restore_uses_a_mismatched_runtime_only_as_a_fresh_snapshot()
    {
        var connector = new FakeConnector(ExpectedRuntime with { BuildId = "different-build" });

        var result = await new InstallationOrchestrator(connector, Manifest)
            .RestoreAsync("/srv/pz/.apollo-backups/20260811T120000Z", CancellationToken.None);

        Assert.Equal(new InstallationResult(InstallerState.RolledBack, "restored", null), result);
        Assert.Equal(1, connector.OperationCalls("probe"));
        Assert.Equal(1, connector.RollbackCalls);
    }

    [Fact]
    public async Task Unupgraded_connectors_fail_closed_for_plan_and_disable()
    {
        IServerConnector connector = new UnupgradedConnector(ExpectedRuntime);

        Assert.Throws<NotSupportedException>(() => connector.DescribePlan());
        await Assert.ThrowsAsync<NotSupportedException>(() => connector.DisableAsync(CancellationToken.None));
    }

    [Theory]
    [MemberData(nameof(RuntimeMismatches))]
    public async Task Every_runtime_tuple_mismatch_performs_zero_writes(RuntimeProbe actual, string reasonCode)
    {
        var connector = new FakeConnector(actual);

        var result = await new InstallationOrchestrator(connector, Manifest).InstallAsync(CancellationToken.None);

        Assert.Equal(InstallerState.Unsupported, result.State);
        Assert.Equal(reasonCode, result.ReasonCode);
        Assert.Null(result.BackupPath);
        Assert.Empty(connector.Events);
    }

    [Fact]
    public async Task Missing_runtime_probe_fails_closed_and_performs_zero_writes()
    {
        var connector = new FakeConnector(null!);

        var result = await new InstallationOrchestrator(connector, Manifest).InstallAsync(CancellationToken.None);

        Assert.Equal(new InstallationResult(InstallerState.Failed, "runtime-probe-invalid", null), result);
        Assert.Empty(connector.Events);
    }

    [Fact]
    public async Task Enable_happens_only_after_backup_disabled_validation_and_vanilla_health()
    {
        var connector = new FakeConnector(ExpectedRuntime);

        var result = await new InstallationOrchestrator(connector, Manifest).InstallAsync(CancellationToken.None);

        Assert.Equal(new InstallationResult(InstallerState.Ready, "ready", "/backup/20260811"), result);
        Assert.Equal(
            ["backup", "stage-off", "validate-merged", "restart-off", "verify-vanilla", "enable", "restart-on", "verify-ready"],
            connector.Events);
        Assert.Equal(2, connector.RestartCalls);
    }

    [Fact]
    public async Task Successful_install_emits_an_append_only_fixed_schema_event_stream()
    {
        var connector = new FakeConnector(ExpectedRuntime);
        var orchestrator = new InstallationOrchestrator(connector, Manifest);

        await orchestrator.InstallAsync(CancellationToken.None);

        Assert.Equal(
        [
            "installer.step:probe",
            "installer.compatibility:supported",
            "installer.step:backup",
            "installer.step:stage-disabled",
            "installer.step:merged-configuration-valid",
            "installer.step:restart-disabled",
            "installer.step:vanilla-verified",
            "installer.step:enabled",
            "installer.step:restart-enabled",
            "installer.step:ready-verified",
            "installer.result:ready",
        ],
        orchestrator.Events.Select(EventSummary));
        Assert.Equal(Enumerable.Range(1, 11).Select(value => (long)value), orchestrator.Events.Select(entry => entry.Sequence));
    }

    [Fact]
    public async Task Failure_events_never_include_exception_text_or_connector_arguments()
    {
        const string sensitiveText = "sk-proj-abcdefghijklmnopqrstuvwxyz docker compose up";
        var connector = new FakeConnector(ExpectedRuntime).FailAt("enable", sensitiveText);
        var orchestrator = new InstallationOrchestrator(connector, Manifest);

        await orchestrator.InstallAsync(CancellationToken.None);

        var serialized = string.Join(
            "\n",
            orchestrator.Events.SelectMany(entry => entry.Fields.Select(field => $"{entry.Name}:{field.Key}={field.Value}")));
        Assert.DoesNotContain(sensitiveText, serialized, StringComparison.Ordinal);
        Assert.DoesNotContain("docker compose", serialized, StringComparison.Ordinal);
        Assert.Equal("installer.step:rollback", EventSummary(orchestrator.Events[^2]));
        Assert.Equal("installer.result:rolled-back", EventSummary(orchestrator.Events[^1]));
    }

    [Fact]
    public async Task Backup_failure_performs_no_mutation_and_no_rollback()
    {
        var connector = new FakeConnector(ExpectedRuntime).FailAt("backup");

        var result = await new InstallationOrchestrator(connector, Manifest).InstallAsync(CancellationToken.None);

        Assert.Equal(new InstallationResult(InstallerState.Failed, "backup-failed", null), result);
        Assert.Equal(["backup"], connector.Events);
        Assert.Equal(0, connector.RollbackCalls);
        Assert.Equal(0, connector.ServiceMutationCalls);
    }

    [Fact]
    public async Task Blank_backup_path_performs_no_mutation_and_no_rollback()
    {
        var connector = new FakeConnector(ExpectedRuntime) { BackupPath = " " };

        var result = await new InstallationOrchestrator(connector, Manifest).InstallAsync(CancellationToken.None);

        Assert.Equal(new InstallationResult(InstallerState.Failed, "backup-path-invalid", null), result);
        Assert.Equal(["backup"], connector.Events);
        Assert.Equal(0, connector.RollbackCalls);
        Assert.Equal(0, connector.ServiceMutationCalls);
    }

    [Theory]
    [InlineData("stage-off", "stage-disabled-failed")]
    [InlineData("validate-merged", "merged-configuration-invalid")]
    [InlineData("restart-off", "disabled-restart-failed")]
    [InlineData("verify-vanilla", "vanilla-verification-failed")]
    [InlineData("enable", "enable-failed")]
    [InlineData("restart-on", "enabled-restart-failed")]
    [InlineData("verify-ready", "ready-verification-failed")]
    public async Task Every_post_backup_failure_rolls_back_exactly_once(string operation, string reasonCode)
    {
        var connector = new FakeConnector(ExpectedRuntime).FailAt(operation);

        var result = await new InstallationOrchestrator(connector, Manifest).InstallAsync(CancellationToken.None);

        Assert.Equal(new InstallationResult(InstallerState.RolledBack, reasonCode, "/backup/20260811"), result);
        Assert.Equal(1, connector.RollbackCalls);
        Assert.Equal("rollback", connector.Events[^1]);
        Assert.Equal(1, connector.OperationCalls(operation));
    }

    [Fact]
    public async Task Cancellation_after_backup_rolls_back_once_without_reusing_cancelled_token()
    {
        using var cancellation = new CancellationTokenSource();
        var connector = new FakeConnector(ExpectedRuntime).CancelAfter("stage-off", cancellation);

        var result = await new InstallationOrchestrator(connector, Manifest).InstallAsync(cancellation.Token);

        Assert.Equal(new InstallationResult(InstallerState.Cancelled, "cancelled", "/backup/20260811"), result);
        Assert.Equal(["backup", "stage-off", "rollback"], connector.Events);
        Assert.Equal(1, connector.RollbackCalls);
        Assert.False(connector.RollbackTokenCanBeCanceled);
    }

    [Fact]
    public async Task Cancellation_between_probe_and_backup_returns_cancelled_without_writes()
    {
        using var cancellation = new CancellationTokenSource();
        var connector = new FakeConnector(ExpectedRuntime).CancelAfter("probe", cancellation);

        var result = await new InstallationOrchestrator(connector, Manifest).InstallAsync(cancellation.Token);

        Assert.Equal(new InstallationResult(InstallerState.Cancelled, "cancelled", null), result);
        Assert.Empty(connector.Events);
        Assert.Equal(0, connector.RollbackCalls);
    }

    [Fact]
    public async Task Already_cancelled_install_never_calls_the_connector()
    {
        using var cancellation = new CancellationTokenSource();
        cancellation.Cancel();
        var connector = new FakeConnector(ExpectedRuntime);

        var result = await new InstallationOrchestrator(connector, Manifest).InstallAsync(cancellation.Token);

        Assert.Equal(new InstallationResult(InstallerState.Cancelled, "cancelled", null), result);
        Assert.Equal(0, connector.OperationCalls("probe"));
        Assert.Empty(connector.Events);
    }

    [Fact]
    public async Task Rollback_failure_is_reported_without_a_second_attempt()
    {
        var connector = new FakeConnector(ExpectedRuntime)
            .FailAt("verify-ready")
            .FailAt("rollback");

        var result = await new InstallationOrchestrator(connector, Manifest).InstallAsync(CancellationToken.None);

        Assert.Equal(new InstallationResult(InstallerState.Failed, "ready-verification-failed.rollback-failed", "/backup/20260811"), result);
        Assert.Equal(1, connector.RollbackCalls);
    }

    [Fact]
    public async Task Failed_mutation_is_never_retried_automatically()
    {
        var connector = new FakeConnector(ExpectedRuntime).FailAt("enable");

        await new InstallationOrchestrator(connector, Manifest).InstallAsync(CancellationToken.None);

        Assert.Equal(1, connector.OperationCalls("enable"));
        Assert.Equal(1, connector.RollbackCalls);
    }

    private static string EventSummary(RedactedEvent entry)
    {
        if (entry.Name == "installer.step")
        {
            return $"{entry.Name}:{entry.Fields["step"]}";
        }

        if (entry.Name == "installer.compatibility")
        {
            return $"{entry.Name}:{entry.Fields["reasonCode"]}";
        }

        return $"{entry.Name}:{entry.Fields["state"]}";
    }

    private sealed class FakeConnector(RuntimeProbe runtime) : IServerConnector
    {
        private readonly Dictionary<string, Exception> failures = new(StringComparer.Ordinal);
        private readonly Dictionary<string, CancellationTokenSource> cancellations = new(StringComparer.Ordinal);
        private readonly Dictionary<string, int> calls = new(StringComparer.Ordinal);
        private string restartName = "restart-off";

        public List<string> Events { get; } = [];
        public string BackupPath { get; set; } = "/backup/20260811";
        public int RollbackCalls { get; private set; }
        public string? RollbackBackupPath { get; private set; }
        public int RestartCalls { get; private set; }
        public int ServiceMutationCalls { get; private set; }
        public bool RollbackTokenCanBeCanceled { get; private set; }
        public bool RestartTokenCanBeCanceled { get; private set; }
        public bool VanillaVerificationTokenCanBeCanceled { get; private set; }

        public FakeConnector FailAt(string operation, string? message = null)
        {
            failures[operation] = new InvalidOperationException(message ?? operation);
            return this;
        }

        public FakeConnector CancelAfter(string operation, CancellationTokenSource source)
        {
            cancellations[operation] = source;
            return this;
        }

        public int OperationCalls(string operation) => calls.GetValueOrDefault(operation);

        public Task<RuntimeProbe> ProbeAsync(CancellationToken cancellationToken)
            => Complete("probe", runtime, recordEvent: false, mutation: false);

        public List<PlannedAction> Plan { get; } =
        [
            new PlannedAction("backup", "/srv/pz/.apollo-backups", true, false),
            new PlannedAction("write-companion", "/srv/pz/.apollo-native.env", true, false),
            new PlannedAction("restart-disabled", "project-zomboid.service", true, true),
            new PlannedAction("restart-enabled", "project-zomboid.service", true, true),
        ];

        public IReadOnlyList<PlannedAction> DescribePlan() => Plan;

        public Task<string> BackupAsync(CancellationToken cancellationToken)
            => Complete("backup", BackupPath, recordEvent: true, mutation: false);

        public Task StageDisabledAsync(CancellationToken cancellationToken)
            => Complete("stage-off", recordEvent: true, mutation: true);

        public Task ValidateMergedConfigurationAsync(CancellationToken cancellationToken)
            => Complete("validate-merged", recordEvent: true, mutation: false);

        public Task RestartAsync(CancellationToken cancellationToken)
        {
            RestartCalls++;
            this.RestartTokenCanBeCanceled = cancellationToken.CanBeCanceled;
            var name = restartName;
            restartName = "restart-on";
            return Complete(name, recordEvent: true, mutation: true);
        }

        public Task VerifyVanillaAsync(CancellationToken cancellationToken)
        {
            this.VanillaVerificationTokenCanBeCanceled = cancellationToken.CanBeCanceled;
            return Complete("verify-vanilla", recordEvent: true, mutation: false);
        }

        public Task EnableAsync(CancellationToken cancellationToken)
            => Complete("enable", recordEvent: true, mutation: true);

        public Task DisableAsync(CancellationToken cancellationToken)
            => Complete("disable", recordEvent: true, mutation: true);

        public Task VerifyReadyAsync(CancellationToken cancellationToken)
            => Complete("verify-ready", recordEvent: true, mutation: false);

        public Task RollbackAsync(string backupPath, CancellationToken cancellationToken)
        {
            RollbackCalls++;
            RollbackBackupPath = backupPath;
            this.RollbackTokenCanBeCanceled = cancellationToken.CanBeCanceled;
            return Complete("rollback", recordEvent: true, mutation: true);
        }

        public ValueTask DisposeAsync() => ValueTask.CompletedTask;

        private Task Complete(string operation, bool recordEvent, bool mutation)
            => Complete<object?>(operation, null, recordEvent, mutation);

        private Task<T> Complete<T>(string operation, T value, bool recordEvent, bool mutation)
        {
            calls[operation] = calls.GetValueOrDefault(operation) + 1;
            if (recordEvent)
            {
                Events.Add(operation);
            }

            if (mutation)
            {
                ServiceMutationCalls++;
            }

            if (failures.TryGetValue(operation, out var failure))
            {
                return Task.FromException<T>(failure);
            }

            if (cancellations.TryGetValue(operation, out var cancellation))
            {
                cancellation.Cancel();
            }

            return Task.FromResult(value);
        }
    }

    private sealed class UnupgradedConnector(RuntimeProbe runtime) : IServerConnector
    {
        public Task<RuntimeProbe> ProbeAsync(CancellationToken cancellationToken) => Task.FromResult(runtime);
        public Task<string> BackupAsync(CancellationToken cancellationToken) => Task.FromResult("/backup/20260811");
        public Task StageDisabledAsync(CancellationToken cancellationToken) => Task.CompletedTask;
        public Task ValidateMergedConfigurationAsync(CancellationToken cancellationToken) => Task.CompletedTask;
        public Task RestartAsync(CancellationToken cancellationToken) => Task.CompletedTask;
        public Task VerifyVanillaAsync(CancellationToken cancellationToken) => Task.CompletedTask;
        public Task EnableAsync(CancellationToken cancellationToken) => Task.CompletedTask;
        public Task VerifyReadyAsync(CancellationToken cancellationToken) => Task.CompletedTask;
        public Task RollbackAsync(string backupPath, CancellationToken cancellationToken) => Task.CompletedTask;
        public ValueTask DisposeAsync() => ValueTask.CompletedTask;
    }
}

public sealed class RedactedLogTests
{
    [Fact]
    public void Structured_allowlisted_scalars_are_appended_with_invariant_values()
    {
        var log = new RedactedLog(
        [
            new LogFieldPolicy("state", LogScalarKind.StructuredToken),
            new LogFieldPolicy("attempt", LogScalarKind.Number),
            new LogFieldPolicy("healthy", LogScalarKind.Boolean),
        ], []);

        log.Append("installer.ready", new Dictionary<string, object?>
        {
            ["state"] = "ready",
            ["attempt"] = 1.5,
            ["healthy"] = true,
        });

        var entry = Assert.Single(log.Events);
        Assert.Equal(1, entry.Sequence);
        Assert.Equal("installer.ready", entry.Name);
        Assert.Equal("ready", entry.Fields["state"]);
        Assert.Equal("1.5", entry.Fields["attempt"]);
        Assert.Equal("true", entry.Fields["healthy"]);
    }

    [Theory]
    [InlineData("password")]
    [InlineData("apiToken")]
    [InlineData("client_secret")]
    [InlineData("sshKeyPath")]
    [InlineData("credentialType")]
    [InlineData("rconPort")]
    [InlineData("rawCommand")]
    public void Sensitive_or_command_field_names_are_rejected(string fieldName)
    {
        Assert.Throws<ArgumentException>(() => new RedactedLog(
            [new LogFieldPolicy(fieldName, LogScalarKind.StructuredToken)], []));
    }

    [Fact]
    public void Unknown_fields_and_non_scalar_values_are_rejected_without_partial_append()
    {
        var log = new RedactedLog(
            [new LogFieldPolicy("state", LogScalarKind.StructuredToken)], []);

        Assert.Throws<ArgumentException>(() => log.Append("installer.ready", new Dictionary<string, object?> { ["reason"] = "ok" }));
        Assert.Throws<ArgumentException>(() => log.Append("installer.ready", new Dictionary<string, object?> { ["state"] = new[] { "ready" } }));
        Assert.Empty(log.Events);
    }

    [Theory]
    [InlineData("")]
    [InlineData("ready now")]
    [InlineData("docker compose up")]
    public void Unstructured_event_names_are_rejected(string eventName)
    {
        var log = new RedactedLog(
            [new LogFieldPolicy("state", LogScalarKind.StructuredToken)], []);

        Assert.Throws<ArgumentException>(() => log.Append(eventName, new Dictionary<string, object?> { ["state"] = "ready" }));
        Assert.Empty(log.Events);
    }

    [Fact]
    public void Registered_secret_under_an_innocuous_key_is_redacted_before_storage()
    {
        var log = new RedactedLog(
            [new LogFieldPolicy("detail", LogScalarKind.StructuredToken)],
            ["correct-horse-battery-staple"]);

        log.Append("installer.detail", new Dictionary<string, object?>
        {
            ["detail"] = "correct-horse-battery-staple",
        });

        Assert.Equal("[REDACTED]", Assert.Single(log.Events).Fields["detail"]);
    }

    [Theory]
    [InlineData("sk-proj-abcdefghijklmnopqrstuvwxyz")]
    [InlineData("ghp_" + "abcdefghijklmnopqrstuvwxyz123456")]
    [InlineData("AKIAABCDEFGHIJKLMNOP")]
    [InlineData("eyJhbGciOiJIUzI1NiJ9" + "." + "eyJzdWIiOiIxIn0" + "." + "signature")]
    public void Recognized_credential_values_are_redacted_under_safe_keys(string credential)
    {
        var log = new RedactedLog(
            [new LogFieldPolicy("detail", LogScalarKind.StructuredToken)], []);

        log.Append("installer.detail", new Dictionary<string, object?> { ["detail"] = credential });

        Assert.Equal("[REDACTED]", Assert.Single(log.Events).Fields["detail"]);
    }

    [Theory]
    [InlineData("docker compose up")]
    [InlineData("bash -c whoami")]
    [InlineData("/bin/sh;id")]
    public void Raw_command_text_is_rejected_without_partial_append(string command)
    {
        var log = new RedactedLog(
            [new LogFieldPolicy("detail", LogScalarKind.StructuredToken)], []);

        Assert.Throws<ArgumentException>(() => log.Append(
            "installer.detail",
            new Dictionary<string, object?> { ["detail"] = command }));
        Assert.Empty(log.Events);
    }
}
