using Apollo.NativeAssist.Installer.Core;

namespace Apollo.NativeAssist.Installer.Application;

public interface IInstallerSession : IAsyncDisposable
{
    IReadOnlyList<RedactedEvent> Events { get; }
    Task<InstallationPreview> PreviewAsync(CancellationToken cancellationToken);
    Task<InstallationResult> InstallAsync(CancellationToken cancellationToken);
    Task<InstallationResult> DisableAsync(CancellationToken cancellationToken);
    Task<InstallationResult> RestoreAsync(string backupPath, CancellationToken cancellationToken);
}
