using System.Text.RegularExpressions;

namespace Apollo.NativeAssist.Installer.Infrastructure.Ssh;

public sealed record RemoteResult(int ExitCode, string StandardOutput, string StandardError, bool Truncated);

public enum RemoteOperationKind
{
    Discover,
    Backup,
    PrepareStaging,
    ValidateMergedConfiguration,
    RecreateService,
    VerifyVanilla,
    SetKillSwitch,
    VerifyReady,
    RestoreBackup,
}

public sealed partial class RemoteOperation
{
    private RemoteOperation(
        RemoteOperationKind kind,
        string deploymentDirectory,
        IReadOnlyList<string> composeFiles,
        string serviceName,
        string? backupPath,
        string? phase,
        bool requireHealthcheck)
    {
        Kind = kind;
        DeploymentDirectory = ValidateAbsolutePath(deploymentDirectory, nameof(deploymentDirectory));
        ServiceName = ValidateServiceName(serviceName);
        ComposeFiles = ValidateComposeFiles(DeploymentDirectory, composeFiles);
        BackupPath = backupPath is null ? null : ValidateAbsolutePath(backupPath, nameof(backupPath));
        Phase = phase;
        RequireHealthcheck = requireHealthcheck;
    }

    public RemoteOperationKind Kind { get; }
    public string DeploymentDirectory { get; }
    public IReadOnlyList<string> ComposeFiles { get; }
    public string ServiceName { get; }
    public string? BackupPath { get; }
    public string? Phase { get; }
    public bool RequireHealthcheck { get; }

    public static RemoteOperation Discover(string deploymentDirectory, IReadOnlyList<string> composeFiles, string serviceName)
        => Create(RemoteOperationKind.Discover, deploymentDirectory, composeFiles, serviceName);

    public static RemoteOperation Backup(string deploymentDirectory, IReadOnlyList<string> composeFiles, string serviceName)
        => Create(RemoteOperationKind.Backup, deploymentDirectory, composeFiles, serviceName);

    public static RemoteOperation PrepareStaging(string deploymentDirectory, IReadOnlyList<string> composeFiles, string serviceName)
        => Create(RemoteOperationKind.PrepareStaging, deploymentDirectory, composeFiles, serviceName);

    public static RemoteOperation ValidateMergedConfiguration(string deploymentDirectory, IReadOnlyList<string> composeFiles, string serviceName, string phase)
        => Create(RemoteOperationKind.ValidateMergedConfiguration, deploymentDirectory, composeFiles, serviceName, phase: ValidatePhase(phase));

    public static RemoteOperation RecreateService(string deploymentDirectory, IReadOnlyList<string> composeFiles, string serviceName, string phase)
        => Create(RemoteOperationKind.RecreateService, deploymentDirectory, composeFiles, serviceName, phase: ValidatePhase(phase));

    public static RemoteOperation VerifyVanilla(string deploymentDirectory, IReadOnlyList<string> composeFiles, string serviceName, bool requireHealthcheck)
        => Create(RemoteOperationKind.VerifyVanilla, deploymentDirectory, composeFiles, serviceName, requireHealthcheck: requireHealthcheck);

    public static RemoteOperation SetKillSwitch(
        string deploymentDirectory,
        IReadOnlyList<string> composeFiles,
        string serviceName,
        string phase)
        => Create(
            RemoteOperationKind.SetKillSwitch,
            deploymentDirectory,
            composeFiles,
            serviceName,
            phase: ValidatePhase(phase));

    public static RemoteOperation VerifyReady(string deploymentDirectory, IReadOnlyList<string> composeFiles, string serviceName, bool requireHealthcheck)
        => Create(RemoteOperationKind.VerifyReady, deploymentDirectory, composeFiles, serviceName, requireHealthcheck: requireHealthcheck);

    public static RemoteOperation RestoreBackup(
        string deploymentDirectory,
        IReadOnlyList<string> composeFiles,
        string serviceName,
        string backupPath)
        => Create(
            RemoteOperationKind.RestoreBackup,
            deploymentDirectory,
            composeFiles,
            serviceName,
            ValidateAbsolutePath(backupPath, nameof(backupPath)));

    public static string ValidateAbsolutePath(string path, string parameterName)
    {
        if (string.IsNullOrEmpty(path) || path.Length > 1024 || path[0] != '/' || path.Contains('\\') ||
            path.Contains("//", StringComparison.Ordinal) || path.Any(char.IsControl))
        {
            throw new ArgumentException("A canonical absolute POSIX path is required.", parameterName);
        }

        var segments = path.Split('/', StringSplitOptions.None);
        if (segments.Skip(1).Any(segment => segment is "" or "." or ".."))
        {
            throw new ArgumentException("Path traversal and empty segments are forbidden.", parameterName);
        }

        return path;
    }

    public static string ValidateRelativePath(string path, string parameterName)
    {
        if (string.IsNullOrEmpty(path) || path.Length > 512 || path[0] == '/' || path.Contains('\\') ||
            path.Contains("//", StringComparison.Ordinal) || path.Any(char.IsControl))
        {
            throw new ArgumentException("A canonical relative POSIX path is required.", parameterName);
        }

        if (path.Split('/').Any(segment => segment is "" or "." or ".."))
        {
            throw new ArgumentException("Path traversal and empty segments are forbidden.", parameterName);
        }

        return path;
    }

    private static RemoteOperation Create(
        RemoteOperationKind kind,
        string deploymentDirectory,
        IReadOnlyList<string> composeFiles,
        string serviceName,
        string? backupPath = null,
        string? phase = null,
        bool requireHealthcheck = false)
        => new(kind, deploymentDirectory, composeFiles, serviceName, backupPath, phase, requireHealthcheck);

    private static IReadOnlyList<string> ValidateComposeFiles(string deploymentDirectory, IReadOnlyList<string> composeFiles)
    {
        ArgumentNullException.ThrowIfNull(composeFiles);
        if (composeFiles.Count is < 1 or > 8)
        {
            throw new ArgumentException("One to eight Compose files are required.", nameof(composeFiles));
        }

        var prefix = deploymentDirectory == "/" ? "/" : deploymentDirectory + "/";
        var validated = composeFiles.Select(path => ValidateAbsolutePath(path, nameof(composeFiles))).ToArray();
        if (validated.Any(path => !path.StartsWith(prefix, StringComparison.Ordinal)) ||
            validated.Distinct(StringComparer.Ordinal).Count() != validated.Length)
        {
            throw new ArgumentException("Compose files must be unique files inside the deployment directory.", nameof(composeFiles));
        }

        return Array.AsReadOnly(validated);
    }

    private static string ValidateServiceName(string serviceName)
    {
        if (serviceName is null || !ServiceNamePattern().IsMatch(serviceName))
        {
            throw new ArgumentException("Service name is unsafe.", nameof(serviceName));
        }

        return serviceName;
    }

    private static string ValidatePhase(string phase)
        => phase is "off" or "on" ? phase : throw new ArgumentException("Unknown rollout phase.", nameof(phase));

    [GeneratedRegex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$", RegexOptions.CultureInvariant)]
    private static partial Regex ServiceNamePattern();
}

public interface IRemoteSession : IAsyncDisposable
{
    Task<RemoteResult> RunAsync(RemoteOperation operation, CancellationToken cancellationToken);
    Task UploadAsync(Stream source, string absoluteDestination, UnixFileMode mode, CancellationToken cancellationToken);
    Task DownloadAsync(string absoluteSource, Stream destination, CancellationToken cancellationToken);
}
