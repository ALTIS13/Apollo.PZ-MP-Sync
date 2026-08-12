using System.Text.RegularExpressions;
using Apollo.NativeAssist.Installer.Infrastructure.Ssh;

namespace Apollo.NativeAssist.Installer.Application;

public enum AuthenticationMode
{
    PrivateKey,
    Password,
}

public sealed partial record ConnectionInput
{
    private string host = null!;
    private int port;
    private string username = null!;
    private AuthenticationMode authenticationMode;
    private string deploymentDirectory = null!;
    private IReadOnlyList<string> composeFiles = null!;
    private string serviceName = null!;

    public ConnectionInput(
        string host,
        int port,
        string username,
        AuthenticationMode authenticationMode,
        string deploymentDirectory,
        IReadOnlyList<string> composeFiles,
        string serviceName)
    {
        Host = host;
        Port = port;
        Username = username;
        AuthenticationMode = authenticationMode;
        DeploymentDirectory = deploymentDirectory;
        ComposeFiles = composeFiles;
        ServiceName = serviceName;
    }

    public string Host
    {
        get => host;
        init => host = value is not null && HostPattern().IsMatch(value)
            ? value
            : throw new ArgumentException("SSH host is invalid.", nameof(Host));
    }

    public int Port
    {
        get => port;
        init => port = value is >= 1 and <= 65535
            ? value
            : throw new ArgumentOutOfRangeException(nameof(Port));
    }

    public string Username
    {
        get => username;
        init => username = value is not null && UsernamePattern().IsMatch(value)
            ? value
            : throw new ArgumentException("SSH username is invalid.", nameof(Username));
    }

    public AuthenticationMode AuthenticationMode
    {
        get => authenticationMode;
        init => authenticationMode = Enum.IsDefined(value)
            ? value
            : throw new ArgumentOutOfRangeException(nameof(AuthenticationMode));
    }

    public string DeploymentDirectory
    {
        get => deploymentDirectory;
        init => deploymentDirectory = RemoteOperation.ValidateAbsolutePath(value, nameof(DeploymentDirectory));
    }

    public IReadOnlyList<string> ComposeFiles
    {
        get => composeFiles;
        init
        {
            ArgumentNullException.ThrowIfNull(value);
            if (value.Count is < 1 or > 8)
            {
                throw new ArgumentException("One to eight Compose files are required.", nameof(ComposeFiles));
            }

            var validated = value.Select(path => RemoteOperation.ValidateRelativePath(path, nameof(ComposeFiles))).ToArray();
            if (validated.Distinct(StringComparer.Ordinal).Count() != validated.Length)
            {
                throw new ArgumentException("Compose files must be unique.", nameof(ComposeFiles));
            }

            composeFiles = Array.AsReadOnly(validated);
        }
    }

    public string ServiceName
    {
        get => serviceName;
        init
        {
            _ = RemoteOperation.Discover("/validation", ["/validation/compose.yaml"], value);
            serviceName = value;
        }
    }

    internal IReadOnlyList<string> AbsoluteComposeFiles()
    {
        var prefix = DeploymentDirectory == "/" ? "/" : DeploymentDirectory + "/";
        return Array.AsReadOnly(ComposeFiles.Select(path => prefix + path).ToArray());
    }

    internal bool StructurallyEquals(ConnectionInput other)
        => string.Equals(Host, other.Host, StringComparison.Ordinal) &&
           Port == other.Port &&
           string.Equals(Username, other.Username, StringComparison.Ordinal) &&
           AuthenticationMode == other.AuthenticationMode &&
           string.Equals(DeploymentDirectory, other.DeploymentDirectory, StringComparison.Ordinal) &&
           ComposeFiles.SequenceEqual(other.ComposeFiles, StringComparer.Ordinal) &&
           string.Equals(ServiceName, other.ServiceName, StringComparison.Ordinal);

    [GeneratedRegex("^(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9.:-]*[A-Za-z0-9])?$", RegexOptions.CultureInvariant)]
    private static partial Regex HostPattern();

    [GeneratedRegex("^[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}$", RegexOptions.CultureInvariant)]
    private static partial Regex UsernamePattern();
}

public interface ICredentialSource
{
    CredentialInput Read();
}

public sealed class CredentialInput : IDisposable
{
    private char[]? password;
    private char[]? privateKeyPath;
    private char[]? privateKeyPassphrase;
    private bool disposed;

    public CredentialInput(char[]? password, char[]? privateKeyPath, char[]? privateKeyPassphrase)
    {
        this.password = password;
        this.privateKeyPath = privateKeyPath;
        this.privateKeyPassphrase = privateKeyPassphrase;
    }

    public bool IsCleared => disposed;

    public static CredentialInput ForPassword(string password)
    {
        ArgumentNullException.ThrowIfNull(password);
        return new CredentialInput(password.ToCharArray(), null, null);
    }

    public static CredentialInput ForPrivateKey(string path, string? passphrase = null)
    {
        ArgumentNullException.ThrowIfNull(path);
        return new CredentialInput(null, path.ToCharArray(), passphrase?.ToCharArray());
    }

    internal bool HasPassword => password is not null;
    internal bool HasPrivateKeyPath => privateKeyPath is not null;
    internal bool HasPrivateKeyPassphrase => privateKeyPassphrase is not null;

    internal char[] CopyPassword() => Copy(password, "password");
    internal char[] CopyPrivateKeyPath() => Copy(privateKeyPath, "private key path");
    internal char[]? CopyPrivateKeyPassphrase()
    {
        ThrowIfDisposed();
        return privateKeyPassphrase?.ToArray();
    }

    public void Dispose()
    {
        if (disposed)
        {
            return;
        }

        disposed = true;
        Clear(ref password);
        Clear(ref privateKeyPath);
        Clear(ref privateKeyPassphrase);
    }

    private char[] Copy(char[]? value, string label)
    {
        ThrowIfDisposed();
        return value?.ToArray() ?? throw new ArgumentException($"A {label} was not supplied.");
    }

    private void ThrowIfDisposed() => ObjectDisposedException.ThrowIf(disposed, this);

    private static void Clear(ref char[]? value)
    {
        if (value is not null)
        {
            Array.Clear(value);
            value = null;
        }
    }
}
