namespace Apollo.NativeAssist.Installer.Core;

public sealed class InstallationOrchestrator
{
    private readonly IServerConnector connector;
    private readonly VerifiedReleaseManifest manifest;
    private readonly RedactedLog eventLog = new(
    [
        new LogFieldPolicy("step", LogScalarKind.StructuredToken),
        new LogFieldPolicy("state", LogScalarKind.StructuredToken),
        new LogFieldPolicy("reasonCode", LogScalarKind.StructuredToken),
        new LogFieldPolicy("supported", LogScalarKind.Boolean),
    ], []);

    public InstallationOrchestrator(IServerConnector connector, VerifiedReleaseManifest manifest)
    {
        this.connector = connector ?? throw new ArgumentNullException(nameof(connector));
        this.manifest = manifest ?? throw new ArgumentNullException(nameof(manifest));
        ArgumentNullException.ThrowIfNull(manifest.Runtime);
    }

    public IReadOnlyList<RedactedEvent> Events => eventLog.Events;

    public async Task<InstallationPreview> PreviewAsync(CancellationToken cancellationToken)
    {
        RuntimeProbe actualRuntime;
        try
        {
            actualRuntime = await connector.ProbeAsync(cancellationToken).ConfigureAwait(false);
            cancellationToken.ThrowIfCancellationRequested();
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw;
        }
        catch
        {
            return new InstallationPreview(false, "runtime-probe-failed", null, []);
        }

        if (actualRuntime is null)
        {
            return new InstallationPreview(false, "runtime-probe-invalid", null, []);
        }

        var compatibility = CompareRuntime(manifest.Runtime, actualRuntime);
        if (!compatibility.Supported)
        {
            return new InstallationPreview(false, compatibility.ReasonCode, actualRuntime, []);
        }

        return new InstallationPreview(
            true,
            compatibility.ReasonCode,
            actualRuntime,
            Array.AsReadOnly(connector.DescribePlan().ToArray()));
    }

    public async Task<InstallationResult> DisableAsync(CancellationToken cancellationToken)
    {
        if (cancellationToken.IsCancellationRequested)
        {
            return Result(InstallerState.Cancelled, "cancelled", null);
        }

        try
        {
            var runtime = await connector.ProbeAsync(cancellationToken).ConfigureAwait(false);
            cancellationToken.ThrowIfCancellationRequested();
            if (runtime is null)
            {
                return Result(InstallerState.Failed, "runtime-probe-invalid", null);
            }
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            return Result(InstallerState.Cancelled, "cancelled", null);
        }
        catch
        {
            return Result(InstallerState.Failed, "runtime-probe-failed", null);
        }

        try
        {
            await connector.DisableAsync(cancellationToken).ConfigureAwait(false);
            RecordStep("disabled");
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            return Result(InstallerState.Cancelled, "cancelled", null);
        }
        catch
        {
            return Result(InstallerState.Failed, "disable-failed", null);
        }

        try
        {
            await connector.RestartAsync(CancellationToken.None).ConfigureAwait(false);
            RecordStep("restart-disabled");
        }
        catch
        {
            return Result(InstallerState.Failed, "disabled-restart-failed", null);
        }

        try
        {
            await connector.VerifyVanillaAsync(CancellationToken.None).ConfigureAwait(false);
            RecordStep("vanilla-verified");
            return Result(InstallerState.Disabled, "disabled", null);
        }
        catch
        {
            return Result(InstallerState.Failed, "vanilla-verification-failed", null);
        }
    }

    public async Task<InstallationResult> RestoreAsync(string backupPath, CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(backupPath))
        {
            return Result(InstallerState.Failed, "restore-path-invalid", null);
        }

        try
        {
            var runtime = await connector.ProbeAsync(cancellationToken).ConfigureAwait(false);
            if (runtime is null)
            {
                return Result(InstallerState.Failed, "runtime-probe-invalid", null);
            }
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            return Result(InstallerState.Cancelled, "cancelled", null);
        }
        catch
        {
            return Result(InstallerState.Failed, "runtime-probe-failed", null);
        }

        try
        {
            await connector.RollbackAsync(backupPath, CancellationToken.None).ConfigureAwait(false);
            RecordStep("rollback");
            return Result(InstallerState.RolledBack, "restored", null);
        }
        catch
        {
            return Result(InstallerState.Failed, "restore-failed", null);
        }
    }

    public async Task<InstallationResult> InstallAsync(CancellationToken cancellationToken)
    {
        if (cancellationToken.IsCancellationRequested)
        {
            return Result(InstallerState.Cancelled, "cancelled", null);
        }

        RuntimeProbe actualRuntime;
        try
        {
            actualRuntime = await connector.ProbeAsync(cancellationToken).ConfigureAwait(false);
            cancellationToken.ThrowIfCancellationRequested();
            RecordStep("probe");
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            return Result(InstallerState.Cancelled, "cancelled", null);
        }
        catch
        {
            return Result(InstallerState.Failed, "runtime-probe-failed", null);
        }

        if (actualRuntime is null)
        {
            return Result(InstallerState.Failed, "runtime-probe-invalid", null);
        }

        var compatibility = CompareRuntime(manifest.Runtime, actualRuntime);
        eventLog.Append("installer.compatibility", new Dictionary<string, object?>
        {
            ["supported"] = compatibility.Supported,
            ["reasonCode"] = compatibility.ReasonCode,
        });
        if (!compatibility.Supported)
        {
            return Result(InstallerState.Unsupported, compatibility.ReasonCode, null);
        }

        string backupPath;
        try
        {
            cancellationToken.ThrowIfCancellationRequested();
            backupPath = await connector.BackupAsync(cancellationToken).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            return Result(InstallerState.Cancelled, "cancelled", null);
        }
        catch
        {
            return Result(InstallerState.Failed, "backup-failed", null);
        }

        if (string.IsNullOrWhiteSpace(backupPath))
        {
            return Result(InstallerState.Failed, "backup-path-invalid", null);
        }

        RecordStep("backup");

        var failureReason = "stage-disabled-failed";
        try
        {
            cancellationToken.ThrowIfCancellationRequested();
            await connector.StageDisabledAsync(cancellationToken).ConfigureAwait(false);
            cancellationToken.ThrowIfCancellationRequested();
            RecordStep("stage-disabled");

            failureReason = "merged-configuration-invalid";
            await connector.ValidateMergedConfigurationAsync(cancellationToken).ConfigureAwait(false);
            cancellationToken.ThrowIfCancellationRequested();
            RecordStep("merged-configuration-valid");

            failureReason = "disabled-restart-failed";
            await connector.RestartAsync(cancellationToken).ConfigureAwait(false);
            cancellationToken.ThrowIfCancellationRequested();
            RecordStep("restart-disabled");

            failureReason = "vanilla-verification-failed";
            await connector.VerifyVanillaAsync(cancellationToken).ConfigureAwait(false);
            cancellationToken.ThrowIfCancellationRequested();
            RecordStep("vanilla-verified");

            failureReason = "enable-failed";
            await connector.EnableAsync(cancellationToken).ConfigureAwait(false);
            cancellationToken.ThrowIfCancellationRequested();
            RecordStep("enabled");

            failureReason = "enabled-restart-failed";
            await connector.RestartAsync(cancellationToken).ConfigureAwait(false);
            cancellationToken.ThrowIfCancellationRequested();
            RecordStep("restart-enabled");

            failureReason = "ready-verification-failed";
            await connector.VerifyReadyAsync(cancellationToken).ConfigureAwait(false);
            cancellationToken.ThrowIfCancellationRequested();
            RecordStep("ready-verified");

            return Result(InstallerState.Ready, "ready", backupPath);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            return await RollbackAsync(backupPath, InstallerState.Cancelled, "cancelled").ConfigureAwait(false);
        }
        catch
        {
            return await RollbackAsync(backupPath, InstallerState.RolledBack, failureReason).ConfigureAwait(false);
        }
    }

    public static CompatibilityResult CompareRuntime(RuntimeProbe expected, RuntimeProbe actual)
    {
        ArgumentNullException.ThrowIfNull(expected);
        ArgumentNullException.ThrowIfNull(actual);

        if (!string.Equals(expected.AppId, actual.AppId, StringComparison.Ordinal))
        {
            return new CompatibilityResult(false, "runtime-app-id-mismatch");
        }

        if (!string.Equals(expected.BuildId, actual.BuildId, StringComparison.Ordinal))
        {
            return new CompatibilityResult(false, "runtime-build-id-mismatch");
        }

        if (!string.Equals(expected.GameVersion, actual.GameVersion, StringComparison.Ordinal))
        {
            return new CompatibilityResult(false, "runtime-game-version-mismatch");
        }

        if (expected.JavaFeature != actual.JavaFeature)
        {
            return new CompatibilityResult(false, "runtime-java-feature-mismatch");
        }

        if (!string.Equals(expected.Os, actual.Os, StringComparison.Ordinal))
        {
            return new CompatibilityResult(false, "runtime-os-mismatch");
        }

        if (!string.Equals(expected.Arch, actual.Arch, StringComparison.Ordinal))
        {
            return new CompatibilityResult(false, "runtime-arch-mismatch");
        }

        if (!string.Equals(expected.ImageDigest, actual.ImageDigest, StringComparison.Ordinal))
        {
            return new CompatibilityResult(false, "runtime-image-digest-mismatch");
        }

        return new CompatibilityResult(true, "supported");
    }

    private async Task<InstallationResult> RollbackAsync(
        string backupPath,
        InstallerState successState,
        string reasonCode)
    {
        try
        {
            await connector.RollbackAsync(backupPath, CancellationToken.None).ConfigureAwait(false);
            RecordStep("rollback");
            return Result(successState, reasonCode, backupPath);
        }
        catch
        {
            return Result(InstallerState.Failed, $"{reasonCode}.rollback-failed", backupPath);
        }
    }

    private void RecordStep(string step)
    {
        eventLog.Append("installer.step", new Dictionary<string, object?>
        {
            ["step"] = step,
        });
    }

    private InstallationResult Result(InstallerState state, string reasonCode, string? backupPath)
    {
        eventLog.Append("installer.result", new Dictionary<string, object?>
        {
            ["state"] = state switch
            {
                InstallerState.Ready => "ready",
                InstallerState.Unsupported => "unsupported",
                InstallerState.Disabled => "disabled",
                InstallerState.RolledBack => "rolled-back",
                InstallerState.Failed => "failed",
                InstallerState.Cancelled => "cancelled",
                _ => throw new InvalidOperationException("Unknown installer state."),
            },
            ["reasonCode"] = reasonCode,
        });
        return new InstallationResult(state, reasonCode, backupPath);
    }
}
