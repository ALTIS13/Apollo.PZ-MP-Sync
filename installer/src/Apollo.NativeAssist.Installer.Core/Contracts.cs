namespace Apollo.NativeAssist.Installer.Core;

public enum InstallerState
{
    Ready,
    Unsupported,
    Disabled,
    RolledBack,
    Failed,
    Cancelled,
}

public sealed record RuntimeProbe(
    string AppId,
    string BuildId,
    string GameVersion,
    int JavaFeature,
    string Os,
    string Arch,
    string ImageDigest);

public sealed record VerifiedReleaseManifest(RuntimeProbe Runtime);

public sealed record CompatibilityResult(bool Supported, string ReasonCode);

public sealed record InstallationResult(InstallerState State, string ReasonCode, string? BackupPath);

public sealed record PlannedAction(string Code, string Target, bool Mutates, bool RestartsService);

public sealed record InstallationPreview(
    bool Supported,
    string ReasonCode,
    RuntimeProbe? Runtime,
    IReadOnlyList<PlannedAction> Actions);

public interface IServerConnector : IAsyncDisposable
{
    Task<RuntimeProbe> ProbeAsync(CancellationToken cancellationToken);
    IReadOnlyList<PlannedAction> DescribePlan() => throw new NotSupportedException("Connector plan description is not implemented.");
    Task<string> BackupAsync(CancellationToken cancellationToken);
    Task StageDisabledAsync(CancellationToken cancellationToken);
    Task ValidateMergedConfigurationAsync(CancellationToken cancellationToken);
    Task RestartAsync(CancellationToken cancellationToken);
    Task VerifyVanillaAsync(CancellationToken cancellationToken);
    Task EnableAsync(CancellationToken cancellationToken);
    Task DisableAsync(CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        return Task.FromException(new NotSupportedException("Connector disable operation is not implemented."));
    }
    Task VerifyReadyAsync(CancellationToken cancellationToken);
    Task RollbackAsync(string backupPath, CancellationToken cancellationToken);
}
