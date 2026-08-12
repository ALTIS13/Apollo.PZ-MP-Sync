using System.IO;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text.Json;
using System.Text.Json.Serialization;
using Apollo.NativeAssist.Installer.Infrastructure.Ssh;

namespace Apollo.NativeAssist.Installer.Application;

public sealed record RecoveryRecord(
    string Host,
    int Port,
    string Username,
    string HostKeySha256,
    string DeploymentDirectory,
    IReadOnlyList<string> ComposeFiles,
    string ServiceName,
    string BackupPath);

public sealed record RecoveryLoadResult(RecoveryRecord? Record, string ReasonCode)
{
    public bool Success => Record is not null;
}

public sealed class RecoveryRecordStore
{
    private const int MaximumRecordBytes = 64 * 1024;
    private static readonly HashSet<string> ExactPropertyNames = new(StringComparer.Ordinal)
    {
        "host", "port", "username", "hostKeySha256", "deploymentDirectory", "composeFiles", "serviceName", "backupPath",
    };
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNameCaseInsensitive = false,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow,
        AllowTrailingCommas = false,
        ReadCommentHandling = JsonCommentHandling.Disallow,
    };

    public RecoveryRecordStore()
        : this(System.IO.Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "Apollo.PZ MP Sync", "NativeAssist", "recovery.json"))
    {
    }

    public RecoveryRecordStore(string path)
    {
        if (string.IsNullOrWhiteSpace(path))
        {
            throw new ArgumentException("A recovery-record path is required.", nameof(path));
        }

        Path = System.IO.Path.GetFullPath(path);
    }

    public string Path { get; }

    public void Save(RecoveryRecord record)
    {
        var validated = Validate(record);
        var bytes = JsonSerializer.SerializeToUtf8Bytes(validated, JsonOptions);
        if (bytes.Length > MaximumRecordBytes)
        {
            throw new InvalidDataException("Recovery record exceeds its bounded size.");
        }

        var directory = System.IO.Path.GetDirectoryName(Path)
            ?? throw new InvalidOperationException("Recovery record requires a parent directory.");
        Directory.CreateDirectory(directory);
        var temporaryPath = System.IO.Path.Combine(
            directory,
            System.IO.Path.GetFileName(Path) + "." + Guid.NewGuid().ToString("N") + ".tmp");
        try
        {
            using (var stream = new FileStream(
                       temporaryPath, FileMode.CreateNew, FileAccess.Write, FileShare.None,
                       16 * 1024, FileOptions.WriteThrough))
            {
                stream.Write(bytes);
                stream.Flush(flushToDisk: true);
            }

            RestrictToCurrentUserBestEffort(temporaryPath);
            if (File.Exists(Path))
            {
                try
                {
                    File.Replace(temporaryPath, Path, null, ignoreMetadataErrors: true);
                }
                catch (PlatformNotSupportedException)
                {
                    File.Move(temporaryPath, Path, overwrite: true);
                }
            }
            else
            {
                File.Move(temporaryPath, Path);
            }

            RestrictToCurrentUserBestEffort(Path);
        }
        finally
        {
            Array.Clear(bytes);
            try
            {
                File.Delete(temporaryPath);
            }
            catch
            {
                // The destination record is authoritative; abandoned temporary files contain metadata only.
            }
        }
    }

    public RecoveryLoadResult Load()
    {
        if (!File.Exists(Path))
        {
            return new RecoveryLoadResult(null, "recovery-record-not-found");
        }

        try
        {
            var information = new FileInfo(Path);
            information.Refresh();
            if (!information.Exists || information.Length is < 2 or > MaximumRecordBytes ||
                information.Attributes.HasFlag(FileAttributes.Directory) ||
                information.Attributes.HasFlag(FileAttributes.ReparsePoint) || information.LinkTarget is not null)
            {
                return Invalid();
            }

            var bytes = File.ReadAllBytes(Path);
            try
            {
                using var document = JsonDocument.Parse(bytes, new JsonDocumentOptions
                {
                    AllowTrailingCommas = false,
                    CommentHandling = JsonCommentHandling.Disallow,
                    MaxDepth = 16,
                });
                if (document.RootElement.ValueKind != JsonValueKind.Object)
                {
                    return Invalid();
                }

                var names = new HashSet<string>(StringComparer.Ordinal);
                foreach (var property in document.RootElement.EnumerateObject())
                {
                    if (!names.Add(property.Name))
                    {
                        return Invalid();
                    }
                }

                if (!names.SetEquals(ExactPropertyNames))
                {
                    return Invalid();
                }

                var record = JsonSerializer.Deserialize<RecoveryRecord>(bytes, JsonOptions);
                return new RecoveryLoadResult(Validate(record), "recovery-record-loaded");
            }
            finally
            {
                Array.Clear(bytes);
            }
        }
        catch
        {
            return Invalid();
        }
    }

    public bool TryLoad(out RecoveryRecord? record, out string reasonCode)
    {
        var result = Load();
        record = result.Record;
        reasonCode = result.ReasonCode;
        return result.Success;
    }

    internal static RecoveryRecord Validate(RecoveryRecord? record)
    {
        ArgumentNullException.ThrowIfNull(record);
        if (!IsValidHostKey(record.HostKeySha256))
        {
            throw new ArgumentException("Recovery host-key fingerprint is invalid.", nameof(record));
        }

        var input = new ConnectionInput(
            record.Host, record.Port, record.Username, AuthenticationMode.Password,
            record.DeploymentDirectory, record.ComposeFiles, record.ServiceName);
        var absoluteComposeFiles = input.AbsoluteComposeFiles();
        _ = RemoteOperation.RestoreBackup(
            input.DeploymentDirectory, absoluteComposeFiles, input.ServiceName, record.BackupPath);
        var backupPrefix = input.DeploymentDirectory == "/"
            ? "/.apollo-backups/"
            : input.DeploymentDirectory + "/.apollo-backups/";
        if (!record.BackupPath.StartsWith(backupPrefix, StringComparison.Ordinal))
        {
            throw new ArgumentException("Recovery backup path escaped its deployment directory.", nameof(record));
        }

        return record with { ComposeFiles = Array.AsReadOnly(input.ComposeFiles.ToArray()) };
    }

    private static RecoveryLoadResult Invalid() => new(null, "recovery-record-invalid");

    internal static bool IsValidHostKey(string? value)
    {
        if (value is null || value.Length != 43 || value.Any(character =>
                character is not (>= 'A' and <= 'Z') and not (>= 'a' and <= 'z') and
                not (>= '0' and <= '9') and not '+' and not '/'))
        {
            return false;
        }

        try
        {
            return Convert.FromBase64String(value + "=").Length == 32;
        }
        catch (FormatException)
        {
            return false;
        }
    }

    private static void RestrictToCurrentUserBestEffort(string path)
    {
        try
        {
            if (OperatingSystem.IsWindows())
            {
                using var identity = WindowsIdentity.GetCurrent();
                var user = identity.User;
                if (user is null)
                {
                    return;
                }

                var security = new FileSecurity();
                security.SetOwner(user);
                security.SetAccessRuleProtection(isProtected: true, preserveInheritance: false);
                security.AddAccessRule(new FileSystemAccessRule(
                    user, FileSystemRights.FullControl, AccessControlType.Allow));
                new FileInfo(path).SetAccessControl(security);
            }
            else
            {
                File.SetUnixFileMode(path, UnixFileMode.UserRead | UnixFileMode.UserWrite);
            }
        }
        catch
        {
            // Permission hardening is best effort because some filesystems do not expose ACL or mode support.
        }
    }
}
