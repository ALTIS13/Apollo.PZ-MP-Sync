using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using Renci.SshNet;

namespace Apollo.NativeAssist.Installer.Infrastructure.Ssh;

public abstract class SshCredential : IDisposable
{
    private protected SshCredential()
    {
    }

    internal abstract AuthenticationMethod CreateAuthenticationMethod(string username, List<IDisposable> ownedResources);

    public abstract void Dispose();

    public sealed override string ToString() => $"{GetType().Name}([REDACTED])";
}

public sealed class SshPasswordCredential : SshCredential
{
    private string? password;

    public SshPasswordCredential(string password)
    {
        if (string.IsNullOrEmpty(password) || password.Length > 4096 || password.Any(character => character == '\0'))
        {
            throw new ArgumentException("A bounded password is required.", nameof(password));
        }

        this.password = password;
    }

    internal override AuthenticationMethod CreateAuthenticationMethod(string username, List<IDisposable> ownedResources)
        => new PasswordAuthenticationMethod(username, password ?? throw new ObjectDisposedException(nameof(SshPasswordCredential)));

    public override void Dispose() => password = null;
}

public sealed class SshPrivateKeyCredential : SshCredential
{
    private byte[]? privateKeyBytes;
    private string? passphrase;

    public SshPrivateKeyCredential(ReadOnlySpan<byte> privateKeyBytes, string? passphrase = null)
    {
        if (privateKeyBytes.IsEmpty || privateKeyBytes.Length > 1024 * 1024)
        {
            throw new ArgumentException("A bounded private key is required.", nameof(privateKeyBytes));
        }

        if (passphrase is { Length: > 4096 } || passphrase?.Contains('\0') is true)
        {
            throw new ArgumentException("Private-key passphrase is invalid.", nameof(passphrase));
        }

        this.privateKeyBytes = privateKeyBytes.ToArray();
        this.passphrase = passphrase;
    }

    internal override AuthenticationMethod CreateAuthenticationMethod(string username, List<IDisposable> ownedResources)
    {
        var stream = new MemoryStream(privateKeyBytes ?? throw new ObjectDisposedException(nameof(SshPrivateKeyCredential)), writable: false);
        try
        {
            var key = passphrase is null ? new PrivateKeyFile(stream) : new PrivateKeyFile(stream, passphrase);
            ownedResources.Add(key);
            ownedResources.Add(stream);
            return new PrivateKeyAuthenticationMethod(username, key);
        }
        catch
        {
            stream.Dispose();
            throw;
        }
    }

    public override void Dispose()
    {
        if (privateKeyBytes is not null)
        {
            CryptographicOperations.ZeroMemory(privateKeyBytes);
            privateKeyBytes = null;
        }

        passphrase = null;
    }
}

public sealed partial class SshConnectionOptions
{
    public SshConnectionOptions(
        string host,
        int port,
        string username,
        SshCredential credential,
        string hostKeySha256,
        TimeSpan operationTimeout,
        int maxOutputBytes)
    {
        if (host is null || !HostPattern().IsMatch(host))
        {
            throw new ArgumentException("SSH host is invalid.", nameof(host));
        }

        if (port is < 1 or > 65535)
        {
            throw new ArgumentOutOfRangeException(nameof(port));
        }

        if (username is null || !UsernamePattern().IsMatch(username))
        {
            throw new ArgumentException("SSH username is invalid.", nameof(username));
        }

        ArgumentNullException.ThrowIfNull(credential);
        if (!IsValidHostKeyPin(hostKeySha256))
        {
            throw new ArgumentException("A non-padded SHA-256 SSH host-key fingerprint is required.", nameof(hostKeySha256));
        }

        if (operationTimeout < TimeSpan.FromSeconds(1) || operationTimeout > TimeSpan.FromMinutes(5))
        {
            throw new ArgumentOutOfRangeException(nameof(operationTimeout));
        }

        if (maxOutputBytes is < 1024 or > 1024 * 1024)
        {
            throw new ArgumentOutOfRangeException(nameof(maxOutputBytes));
        }

        Host = host;
        Port = port;
        Username = username;
        this.Credential = credential;
        HostKeySha256 = hostKeySha256;
        OperationTimeout = operationTimeout;
        MaxOutputBytes = maxOutputBytes;
    }

    public string Host { get; }
    public int Port { get; }
    public string Username { get; }
    public SshCredential Credential { get; }
    public string HostKeySha256 { get; }
    public TimeSpan OperationTimeout { get; }
    public int MaxOutputBytes { get; }

    public override string ToString() => $"{Username}@{Host}:{Port} (credential=[REDACTED])";

    [GeneratedRegex("^(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9.:-]*[A-Za-z0-9])?$", RegexOptions.CultureInvariant)]
    private static partial Regex HostPattern();

    [GeneratedRegex("^[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}$", RegexOptions.CultureInvariant)]
    private static partial Regex UsernamePattern();

    private static bool IsValidHostKeyPin(string value)
    {
        if (value is null || value.Length != 43 || value.Any(character =>
                character is not (>= 'A' and <= 'Z') and not (>= 'a' and <= 'z') and not (>= '0' and <= '9') and not '+' and not '/'))
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
}

internal sealed class BoundedReadStream(Stream inner, long limit) : Stream
{
    private long consumed;

    public override bool CanRead => inner.CanRead;
    public override bool CanSeek => false;
    public override bool CanWrite => false;
    public override long Length => throw new NotSupportedException();
    public override long Position { get => consumed; set => throw new NotSupportedException(); }

    public override int Read(byte[] buffer, int offset, int count)
    {
        var actual = inner.Read(buffer, offset, BoundedCount(count));
        Account(actual);
        return actual;
    }

    public override int Read(Span<byte> buffer)
    {
        var actual = inner.Read(buffer[..BoundedCount(buffer.Length)]);
        Account(actual);
        return actual;
    }

    public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
    {
        var actual = await inner.ReadAsync(buffer[..BoundedCount(buffer.Length)], cancellationToken).ConfigureAwait(false);
        Account(actual);
        return actual;
    }

    private int BoundedCount(int requested)
    {
        if (consumed > limit)
        {
            throw new InvalidDataException("Transfer exceeds the bounded size.");
        }

        return checked((int)Math.Min(requested, (limit - consumed) + 1));
    }

    private void Account(int count)
    {
        consumed = checked(consumed + count);
        if (consumed > limit)
        {
            throw new InvalidDataException("Transfer exceeds the bounded size.");
        }
    }

    public override void Flush() => throw new NotSupportedException();
    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();
    public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
}

internal sealed class BoundedWriteStream(Stream inner, long limit) : Stream
{
    private long written;

    public override bool CanRead => false;
    public override bool CanSeek => false;
    public override bool CanWrite => inner.CanWrite;
    public override long Length => written;
    public override long Position { get => written; set => throw new NotSupportedException(); }

    public override void Write(byte[] buffer, int offset, int count)
    {
        EnsureCapacity(count);
        inner.Write(buffer, offset, count);
        written += count;
    }

    public override void Write(ReadOnlySpan<byte> buffer)
    {
        EnsureCapacity(buffer.Length);
        inner.Write(buffer);
        written += buffer.Length;
    }

    public override async ValueTask WriteAsync(ReadOnlyMemory<byte> buffer, CancellationToken cancellationToken = default)
    {
        EnsureCapacity(buffer.Length);
        await inner.WriteAsync(buffer, cancellationToken).ConfigureAwait(false);
        written += buffer.Length;
    }

    public override void Flush() => inner.Flush();
    public override Task FlushAsync(CancellationToken cancellationToken) => inner.FlushAsync(cancellationToken);

    private void EnsureCapacity(int count)
    {
        if (count < 0 || written > limit - count)
        {
            throw new InvalidDataException("Transfer exceeds the bounded size.");
        }
    }

    public override int Read(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();
}

internal sealed class BoundedCommandOutput(int limit)
{
    private static readonly UTF8Encoding StrictUtf8 = new(false, true);
    private readonly object sync = new();
    private readonly MemoryStream standardOutput = new();
    private readonly MemoryStream standardError = new();
    private int bufferedBytes;

    public bool Truncated { get; private set; }
    public int BufferedBytes { get { lock (sync) { return bufferedBytes; } } }

    public async Task CopyAsync(Stream source, bool standardError, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(source);
        var buffer = new byte[4096];
        while (true)
        {
            var count = await source.ReadAsync(buffer.AsMemory(0, NextReadCapacity(buffer.Length)), cancellationToken).ConfigureAwait(false);
            if (count == 0)
            {
                return;
            }

            lock (sync)
            {
                if (bufferedBytes > limit - count)
                {
                    Truncated = true;
                    throw new InvalidDataException("Remote command output exceeds the bounded size.");
                }

                (standardError ? this.standardError : this.standardOutput).Write(buffer, 0, count);
                bufferedBytes += count;
            }
        }
    }

    private int NextReadCapacity(int maximum)
    {
        lock (sync)
        {
            return Math.Min(maximum, Math.Max(1, (limit - bufferedBytes) + 1));
        }
    }

    public RemoteResult ToResult(int exitCode)
    {
        lock (sync)
        {
            if (Truncated)
            {
                return new RemoteResult(-1, string.Empty, string.Empty, true);
            }

            return new RemoteResult(
                exitCode,
                StrictUtf8.GetString(standardOutput.GetBuffer(), 0, checked((int)standardOutput.Length)),
                StrictUtf8.GetString(standardError.GetBuffer(), 0, checked((int)standardError.Length)),
                false);
        }
    }
}

public sealed class SshRemoteSession : IRemoteSession
{
    private const long MaxTransferBytes = 128L * 1024 * 1024;
    private readonly SshConnectionOptions options;
    private readonly List<IDisposable> ownedResources = [];
    private readonly SshClient sshClient;
    private readonly SftpClient sftpClient;
    private readonly SemaphoreSlim gate = new(1, 1);
    private bool disposed;

    public SshRemoteSession(SshConnectionOptions options)
    {
        this.options = options ?? throw new ArgumentNullException(nameof(options));
        var sshConnection = CreateConnectionInfo(options, ownedResources);
        var sftpResources = new List<IDisposable>();
        try
        {
            var sftpConnection = CreateConnectionInfo(options, sftpResources);
            ownedResources.AddRange(sftpResources);
            sshClient = new SshClient(sshConnection)
            {
                KeepAliveInterval = TimeSpan.FromSeconds(15),
            };
            sftpClient = new SftpClient(sftpConnection)
            {
                KeepAliveInterval = TimeSpan.FromSeconds(15),
                OperationTimeout = options.OperationTimeout,
            };
            sshClient.HostKeyReceived += VerifyHostKey;
            sftpClient.HostKeyReceived += VerifyHostKey;
        }
        catch
        {
            foreach (var resource in sftpResources)
            {
                resource.Dispose();
            }

            foreach (var resource in ownedResources)
            {
                resource.Dispose();
            }

            throw;
        }
    }

    public async Task<RemoteResult> RunAsync(RemoteOperation operation, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(operation);
        ThrowIfDisposed();
        await gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            await EnsureSshConnectedAsync(cancellationToken).ConfigureAwait(false);
            using var command = sshClient.CreateCommand(RenderCommand(operation), Encoding.UTF8);
            command.CommandTimeout = options.OperationTimeout;
            var output = new BoundedCommandOutput(options.MaxOutputBytes);
            var execution = command.ExecuteAsync(cancellationToken);
            var standardOutput = output.CopyAsync(command.OutputStream, standardError: false, cancellationToken);
            var standardError = output.CopyAsync(command.ExtendedOutputStream, standardError: true, cancellationToken);
            try
            {
                await Task.WhenAll(execution, standardOutput, standardError).ConfigureAwait(false);
            }
            catch (InvalidDataException) when (output.Truncated)
            {
                try
                {
                    command.CancelAsync();
                }
                catch
                {
                    // Disposing the command below is the final transport abort fallback.
                }
                try
                {
                    await execution.ConfigureAwait(false);
                }
                catch
                {
                    // The output limit is the authoritative fail-closed result.
                }

                return output.ToResult(-1);
            }

            return output.ToResult(command.ExitStatus ?? -1);
        }
        finally
        {
            gate.Release();
        }
    }

    public async Task UploadAsync(Stream source, string absoluteDestination, UnixFileMode mode, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(source);
        RemoteOperation.ValidateAbsolutePath(absoluteDestination, nameof(absoluteDestination));
        ValidateFileMode(mode);
        ThrowIfDisposed();
        if (source.CanSeek && source.Length - source.Position > MaxTransferBytes)
        {
            throw new InvalidDataException("Upload exceeds the bounded transfer size.");
        }

        await gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        var temporary = absoluteDestination + ".apollo-upload-" + RandomNumberGenerator.GetHexString(16).ToLowerInvariant();
        try
        {
            await EnsureSftpConnectedAsync(cancellationToken).ConfigureAwait(false);
            await using var boundedSource = new BoundedReadStream(source, MaxTransferBytes);
            await sftpClient.UploadFileAsync(boundedSource, temporary, cancellationToken).ConfigureAwait(false);
            cancellationToken.ThrowIfCancellationRequested();
            var attributes = await sftpClient.GetAttributesAsync(temporary, cancellationToken).ConfigureAwait(false);
            if (attributes.Size > MaxTransferBytes)
            {
                throw new InvalidDataException("Uploaded file exceeds the bounded transfer size.");
            }

            sftpClient.ChangePermissions(temporary, checked((short)((int)mode & 0x1ff)));
            sftpClient.RenameFile(temporary, absoluteDestination, true);
        }
        catch
        {
            await TryDeleteAsync(temporary).ConfigureAwait(false);
            throw;
        }
        finally
        {
            gate.Release();
        }
    }

    public async Task DownloadAsync(string absoluteSource, Stream destination, CancellationToken cancellationToken)
    {
        RemoteOperation.ValidateAbsolutePath(absoluteSource, nameof(absoluteSource));
        ArgumentNullException.ThrowIfNull(destination);
        ThrowIfDisposed();
        await gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            await EnsureSftpConnectedAsync(cancellationToken).ConfigureAwait(false);
            var attributes = await sftpClient.GetAttributesAsync(absoluteSource, cancellationToken).ConfigureAwait(false);
            if (!attributes.IsRegularFile || attributes.Size > MaxTransferBytes)
            {
                throw new InvalidDataException("Remote download is not a bounded regular file.");
            }

            await using var boundedDestination = new BoundedWriteStream(destination, MaxTransferBytes);
            await sftpClient.DownloadFileAsync(absoluteSource, boundedDestination, cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            gate.Release();
        }
    }

    public ValueTask DisposeAsync()
    {
        if (disposed)
        {
            return ValueTask.CompletedTask;
        }

        disposed = true;
        sshClient.Dispose();
        sftpClient.Dispose();
        foreach (var resource in ownedResources)
        {
            resource.Dispose();
        }

        gate.Dispose();
        return ValueTask.CompletedTask;
    }

    internal static string RenderCommand(RemoteOperation operation)
    {
        ArgumentNullException.ThrowIfNull(operation);
        var deployment = Quote(operation.DeploymentDirectory);
        var service = Quote(operation.ServiceName);
        var compose = string.Join(' ', operation.ComposeFiles.Select(path => "-f " + Quote(path)));
        var composeCommand = $"docker compose {compose}";
        var rolloutComposeCommand = $"docker compose --env-file {Quote(operation.DeploymentDirectory + "/.apollo-native.env")} {compose}";
        return operation.Kind switch
        {
            RemoteOperationKind.Discover => RenderDiscovery(operation),
            RemoteOperationKind.Backup => Lines(
                "set -eu",
                $"cd -- {deployment}",
                "stamp=$(date -u +%Y%m%dT%H%M%SZ)",
                $"backup={deployment}/.apollo-backups/$stamp",
                "umask 077",
                "root=$(realpath -e -- .)",
                "test ! -L .apollo-backups",
                "if [ -e .apollo-backups ]; then",
                "  test -d .apollo-backups -a -O .apollo-backups",
                "  test \"$(stat -Lc %a -- .apollo-backups)\" = 700",
                "else",
                "  mkdir -m 0700 -- .apollo-backups",
                "fi",
                "backup_parent=$(realpath -e -- .apollo-backups)",
                "test \"$backup_parent\" = \"$root/.apollo-backups\"",
                "mkdir -- \"$backup\"",
                "test ! -L \"$backup\"",
                "test \"$(realpath -e -- \"$backup\")\" = \"$backup_parent/$stamp\"",
                "for item in .apollo-native .apollo-native.env compose.apollo-native.yaml; do",
                "  name=$item",
                "  if [ ! -e \"$item\" ] && [ ! -L \"$item\" ]; then printf '%s absent\\n' \"$name\" >>\"$backup/state.manifest\"; continue; fi",
                "  test ! -L \"$item\"",
                "  if [ \"$item\" = .apollo-native ]; then test -d \"$item\"; kind=directory; else test -f \"$item\"; kind=file; fi",
                "  cp -a -- \"$item\" \"$backup/\"",
                "  printf '%s %s\\n' \"$name\" \"$kind\" >>\"$backup/state.manifest\"",
                "done",
                "chmod 0600 \"$backup/state.manifest\"",
                "printf '%s\\n' \"$backup\""),
            RemoteOperationKind.PrepareStaging => Lines(
                "set -eu",
                $"cd -- {deployment}",
                "if [ -L .apollo-native ]; then exit 41; fi",
                "install -d -m 0750 -- .apollo-native"),
            RemoteOperationKind.ValidateMergedConfiguration => RenderComposeValidation(operation),
            RemoteOperationKind.RecreateService => Lines(
                "set -eu",
                $"cd -- {deployment}",
                $"test {Quote(operation.Phase!)} = off -o {Quote(operation.Phase!)} = on",
                $"{rolloutComposeCommand} up -d --no-deps --force-recreate --pull never -- {service}"),
            RemoteOperationKind.VerifyVanilla => RenderRuntimeVerification(operation, requireReady: false),
            RemoteOperationKind.SetKillSwitch => Lines(
                "set -eu",
                $"cd -- {deployment}",
                "root=$(realpath -e -- .)",
                "test ! -L .apollo-native.env",
                "test -f .apollo-native.env",
                "test \"$(realpath -e -- .apollo-native.env)\" = \"$root/.apollo-native.env\"",
                "umask 077",
                "tmp=$(mktemp ./.apollo-native.env.tmp.XXXXXX)",
                "trap 'rm -f -- \"$tmp\"' EXIT HUP INT TERM",
                "test ! -L \"$tmp\"",
                "test -f \"$tmp\"",
                "test \"$(dirname -- \"$(realpath -e -- \"$tmp\")\")\" = \"$root\"",
                "chmod 0600 \"$tmp\"",
                "last_byte=$(tail -c 1 -- .apollo-native.env | od -An -t u1 | tr -d ' ')",
                $"awk -v BINMODE=3 'BEGIN{{count=0}} /^APOLLO_NATIVE_ASSIST=/{{count++;print \"APOLLO_NATIVE_ASSIST={operation.Phase}\" (substr($0,length($0),1)==\"\\r\" ? \"\\r\" : \"\");next}} {{print}} END{{if(count!=1) exit 43}}' .apollo-native.env >\"$tmp\"",
                "if [ \"$last_byte\" != 10 ]; then truncate -s -1 -- \"$tmp\"; fi",
                "sync -f -- \"$tmp\"",
                "mv -f -- \"$tmp\" .apollo-native.env",
                "sync -f -- .",
                "trap - EXIT HUP INT TERM"),
            RemoteOperationKind.VerifyReady => RenderRuntimeVerification(operation, requireReady: true),
            RemoteOperationKind.RestoreBackup => Lines(
                "set -eu",
                $"cd -- {deployment}",
                $"backup={Quote(operation.BackupPath!)}",
                "root=$(realpath -e -- .)",
                "test ! -L .apollo-backups",
                "test -d .apollo-backups -a -O .apollo-backups",
                "test \"$(stat -Lc %a -- .apollo-backups)\" = 700",
                "backup_parent=$(realpath -e -- .apollo-backups)",
                "test \"$backup_parent\" = \"$root/.apollo-backups\"",
                "test -d \"$backup\"",
                "test ! -L \"$backup\"",
                "test -O \"$backup\"",
                "test \"$(stat -Lc %a -- \"$backup\")\" = 700",
                "backup_real=$(realpath -e -- \"$backup\")",
                "test \"$(dirname -- \"$backup_real\")\" = \"$backup_parent\"",
                "test -f \"$backup/state.manifest\"",
                "test ! -L \"$backup/state.manifest\"",
                "test \"$(wc -l <\"$backup/state.manifest\")\" = 3",
                "grep -Eq '^\\.apollo-native (absent|directory)$' \"$backup/state.manifest\"",
                "grep -Eq '^\\.apollo-native.env (absent|file)$' \"$backup/state.manifest\"",
                "grep -Eq '^compose.apollo-native.yaml (absent|file)$' \"$backup/state.manifest\"",
                "for item in .apollo-native .apollo-native.env compose.apollo-native.yaml; do",
                "  kind=$(awk -v item=\"$item\" '$1 == item {print $2}' \"$backup/state.manifest\")",
                "  case \"$kind\" in",
                "    absent) test ! -e \"$backup/$item\" && test ! -L \"$backup/$item\" ;;",
                "    directory) test -d \"$backup/$item\" && test ! -L \"$backup/$item\" ;;",
                "    file) test -f \"$backup/$item\" && test ! -L \"$backup/$item\" ;;",
                "    *) exit 44 ;;",
                "  esac",
                "done",
                "validated=1",
                "rm -rf -- .apollo-native",
                "rm -f -- .apollo-native.env compose.apollo-native.yaml",
                "for item in .apollo-native .apollo-native.env compose.apollo-native.yaml; do",
                "  if [ -e \"$backup/$item\" ]; then cp -a -- \"$backup/$item\" .; fi",
                "done",
                $"{composeCommand} up -d --no-deps --force-recreate --pull never -- {service}"),
            _ => throw new ArgumentOutOfRangeException(nameof(operation)),
        };
    }

    internal static RemoteResult ApplyOutputLimit(string standardOutput, string standardError, int maxOutputBytes)
    {
        ArgumentNullException.ThrowIfNull(standardOutput);
        ArgumentNullException.ThrowIfNull(standardError);
        if (maxOutputBytes < 1)
        {
            throw new ArgumentOutOfRangeException(nameof(maxOutputBytes));
        }

        var total = checked(Encoding.UTF8.GetByteCount(standardOutput) + Encoding.UTF8.GetByteCount(standardError));
        return total > maxOutputBytes
            ? new RemoteResult(-1, string.Empty, string.Empty, true)
            : new RemoteResult(0, standardOutput, standardError, false);
    }

    internal static bool IsExpectedHostKey(string expected, string actual)
    {
        try
        {
            var expectedBytes = Convert.FromBase64String(expected + "=");
            var actualBytes = Convert.FromBase64String(actual + "=");
            return expectedBytes.Length == 32 && actualBytes.Length == 32 &&
                   CryptographicOperations.FixedTimeEquals(expectedBytes, actualBytes);
        }
        catch (FormatException)
        {
            return false;
        }
    }

    private static string RenderDiscovery(RemoteOperation operation)
    {
        var variables = new StringBuilder()
            .Append("APOLLO_DEPLOYMENT=").Append(Quote(operation.DeploymentDirectory)).Append(' ')
            .Append("APOLLO_SERVICE=").Append(Quote(operation.ServiceName)).Append(' ')
            .Append("APOLLO_COMPOSE_COUNT=").Append(Quote(operation.ComposeFiles.Count.ToString(System.Globalization.CultureInfo.InvariantCulture))).Append(' ');
        for (var index = 0; index < operation.ComposeFiles.Count; index++)
        {
            variables.Append("APOLLO_COMPOSE_").Append(index).Append('=').Append(Quote(operation.ComposeFiles[index])).Append(' ');
        }

        return "set -eu\ncd -- " + Quote(operation.DeploymentDirectory) + "\n" + variables + "/usr/bin/env python3 - <<'PY'\n" + DiscoveryProgram + "\nPY\n";
    }

    private static string RenderComposeValidation(RemoteOperation operation)
    {
        var compose = string.Join(' ', operation.ComposeFiles.Select(path => "-f " + Quote(path)));
        return Lines(
            "set -eu",
            $"cd -- {Quote(operation.DeploymentDirectory)}",
            $"docker compose --env-file {Quote(operation.DeploymentDirectory + "/.apollo-native.env")} {compose} config --format json | APOLLO_SERVICE={Quote(operation.ServiceName)} APOLLO_EXPECTED_PHASE={Quote(operation.Phase!)} APOLLO_DEPLOYMENT={Quote(operation.DeploymentDirectory)} /usr/bin/env python3 -c {Quote(ComposeValidationProgram)}");
    }

    private static string RenderRuntimeVerification(RemoteOperation operation, bool requireReady)
    {
        var compose = string.Join(' ', operation.ComposeFiles.Select(path => "-f " + Quote(path)));
        var composeCommand = $"docker compose --env-file {Quote(operation.DeploymentDirectory + "/.apollo-native.env")} {compose}";
        var service = Quote(operation.ServiceName);
        var lines = new List<string>
        {
            "set -eu",
            $"cd -- {Quote(operation.DeploymentDirectory)}",
            $"initial_cid=$({composeCommand} ps -q -- {service})",
            "test -n \"$initial_cid\"",
            "test \"$(printf '%s\\n' \"$initial_cid\" | wc -l)\" = 1",
            "initial_started=$(docker inspect --format '{{.State.StartedAt}}' \"$initial_cid\")",
            "probe_agent_state() {",
            "  docker exec \"$1\" /bin/bash --noprofile --norc -c '",
            "set -eu",
            "count=0; agent=0",
            "for p in /proc/[0-9]*; do",
            "  test -r \"$p/cmdline\" -a -r \"$p/environ\" || continue",
            "  cmd=$(tr \"\\0\" \" \" <\"$p/cmdline\")",
            "  case \"$cmd\" in *zombie.network.GameServer*|*projectzomboid.jar*) ;; *) continue ;; esac",
            "  exe=$(readlink -f \"$p/exe\" 2>/dev/null || true)",
            "  case \"${exe##*/}\" in java|java.bin) ;; *) continue ;; esac",
            "  count=$((count + 1))",
            "  tr \"\\0\" \"\\n\" <\"$p/environ\" | grep -Fq \"JAVA_TOOL_OPTIONS=-javaagent:/opt/apollo-native/apollo-native-agent.jar=\" && agent=1 || true",
            "done",
            "test \"$count\" -eq 1",
            "printf \"%s\\n\" \"$agent\"",
            "'",
            "}",
            "control_first=''",
            "deadline=$((SECONDS + 170))",
            "while [ \"$SECONDS\" -lt \"$deadline\" ]; do",
            $"  current_cid=$({composeCommand} ps -q -- {service})",
            "  test \"$current_cid\" = \"$initial_cid\"",
            "  test \"$(docker inspect --format '{{.State.StartedAt}}' \"$current_cid\")\" = \"$initial_started\"",
            "  test \"$(docker inspect --format '{{.State.Running}}' \"$current_cid\")\" = true",
            "  health=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' \"$current_cid\")",
            "  test \"$health\" != unhealthy",
            "  version_ok=0; started_ok=0; ready_ok=0; forbidden_ok=1; agent_ok=0; control_ok=0",
            "  docker logs --since \"$initial_started\" --tail 4000 \"$current_cid\" 2>&1 | grep -Eq 'version[= :]+42\\.20\\.2([^0-9.]|$)' && version_ok=1 || true",
            "  docker logs --since \"$initial_started\" --tail 4000 \"$current_cid\" 2>&1 | grep -Fq 'SERVER STARTED' && started_ok=1 || true",
        };
        if (requireReady)
        {
            lines.Add("  docker logs --since \"$initial_started\" --tail 4000 \"$current_cid\" 2>&1 | grep -Eq 'native-assist state=READY.*reason=hooks-armed|native-assist state=READY reason=hooks-armed' && ready_ok=1 || true");
            lines.Add("  docker logs --since \"$initial_started\" --tail 4000 \"$current_cid\" 2>&1 | grep -E 'CIRCUIT_OPEN|INCOMPATIBLE|advice[-_ ]?(error|failure)|bridge[-_ ].*error' >/dev/null && forbidden_ok=0 || true");
            lines.Add("  test \"$(probe_agent_state \"$current_cid\")\" = 1 && agent_ok=1 || true");
            lines.Add("  control_line=$(docker logs --since \"$initial_started\" --tail 4000 \"$current_cid\" 2>&1 | grep -E '^\\[ApolloNativeAssist\\] control seq=[0-9]+ ' | tail -n 1 || true)");
            lines.Add("  if printf '%s\\n' \"$control_line\" | grep -Eq '^\\[ApolloNativeAssist\\] control seq=[0-9]+ state=READY reason=hooks-armed advice_errors_total=0$'; then");
            lines.Add("    if [ -z \"$control_first\" ]; then control_first=$control_line; elif [ \"$control_line\" != \"$control_first\" ]; then control_ok=1; fi");
            lines.Add("  else control_first=''; fi");
        }
        else
        {
            lines.Add("  ready_ok=1");
            lines.Add("  docker logs --since \"$initial_started\" --tail 4000 \"$current_cid\" 2>&1 | grep -E 'native-assist state=READY|hooks-armed|^-javaagent:' >/dev/null && forbidden_ok=0 || true");
            lines.Add("  test \"$(probe_agent_state \"$current_cid\")\" = 0 && agent_ok=1 || true");
            lines.Add("  control_ok=1");
        }

        var healthCondition = operation.RequireHealthcheck
            ? "[ \"$health\" = healthy ]"
            : "{ [ \"$health\" = none ] || [ \"$health\" = healthy ]; }";
        var finalHealthCondition = operation.RequireHealthcheck
            ? "test \"$final_health\" = healthy"
            : "test \"$final_health\" = none -o \"$final_health\" = healthy";
        var finalLogCondition = requireReady
            ? "! docker logs --since \"$initial_started\" --tail 4000 \"$initial_cid\" 2>&1 | grep -E 'CIRCUIT_OPEN|INCOMPATIBLE|advice[-_ ]?(error|failure)|bridge[-_ ].*error' >/dev/null"
            : "! docker logs --since \"$initial_started\" --tail 4000 \"$initial_cid\" 2>&1 | grep -E 'native-assist state=READY|hooks-armed|^-javaagent:' >/dev/null";
        var finalControlCondition = requireReady
            ? "final_control=$(docker logs --since \"$initial_started\" --tail 4000 \"$initial_cid\" 2>&1 | grep -E '^\\[ApolloNativeAssist\\] control seq=[0-9]+ ' | tail -n 1); printf '%s\\n' \"$final_control\" | grep -Eq '^\\[ApolloNativeAssist\\] control seq=[0-9]+ state=READY reason=hooks-armed advice_errors_total=0$'"
            : ":";
        lines.AddRange(
        [
            $"  if [ \"$version_ok\" = 1 ] && [ \"$started_ok\" = 1 ] && [ \"$ready_ok\" = 1 ] && [ \"$forbidden_ok\" = 1 ] && [ \"$agent_ok\" = 1 ] && [ \"$control_ok\" = 1 ] && {healthCondition}; then",
            "    sleep 3",
            $"    test \"$({composeCommand} ps -q -- {service})\" = \"$initial_cid\"",
            "    final_started=$(docker inspect --format '{{.State.StartedAt}}' \"$initial_cid\")",
            "    test \"$final_started\" = \"$initial_started\"",
            "    test \"$(docker inspect --format '{{.State.Running}}' \"$initial_cid\")\" = true",
            $"    test \"$(probe_agent_state \"$initial_cid\")\" = {(requireReady ? "1" : "0")}",
            "    final_health=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' \"$initial_cid\")",
            $"    {finalHealthCondition}",
            $"    {finalLogCondition}",
            $"    {finalControlCondition}",
            "    printf 'verified\\n'",
            "    exit 0",
            "  fi",
            "  sleep 2",
            "done",
            "exit 45",
        ]);
        return Lines([.. lines]);
    }

    private const string ComposeValidationProgram = """
import hashlib, json, os, sys

MAX = 1024 * 1024
raw = sys.stdin.buffer.read(MAX + 1)
if len(raw) > MAX:
    raise RuntimeError('compose-output-too-large')
config = json.loads(raw.decode('utf-8', 'strict'))
services = config.get('services')
service = os.environ['APOLLO_SERVICE']
expected_phase = os.environ['APOLLO_EXPECTED_PHASE']
deployment = os.environ['APOLLO_DEPLOYMENT'].rstrip('/')
if not isinstance(services, dict) or service not in services:
    raise RuntimeError('service-missing')
selected = services[service]
if not isinstance(selected, dict):
    raise RuntimeError('service-invalid')

def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode('ascii')

environment = selected.get('environment')
if not isinstance(environment, dict):
    raise RuntimeError('apollo-environment-missing')
server_root = environment.get('APOLLO_SERVER_ROOT')
if not isinstance(server_root, str) or not server_root.startswith('/'):
    raise RuntimeError('apollo-server-root-missing')

def invariant_service(value, root):
    invariant = {key: item for key, item in value.items() if key not in {'entrypoint', 'environment', 'volumes', 'security_opt'}}
    invariant['environment'] = {key: item for key, item in (value.get('environment') or {}).items() if not key.startswith('APOLLO_')}
    normalized_volumes = []
    for volume in value.get('volumes') or []:
        if not isinstance(volume, dict):
            normalized_volumes.append(volume)
            continue
        target = volume.get('target')
        if target == '/opt/apollo-native':
            continue
        normalized = dict(volume)
        if target in (root, root + '/steamapps/workshop'):
            normalized['read_only'] = 'apollo-managed'
            bind = dict(normalized.get('bind') or {})
            bind['create_host_path'] = 'apollo-managed'
            normalized['bind'] = bind
        normalized_volumes.append(normalized)
    invariant['volumes'] = normalized_volumes
    invariant['security_opt'] = [item for item in (value.get('security_opt') or []) if item != 'no-new-privileges:true']
    return invariant

invariant = invariant_service(selected, server_root)
others = {key: value for key, value in services.items() if key != service}
entrypoint = selected.get('entrypoint')
volumes = selected.get('volumes')
security = selected.get('security_opt')
expected_apollo_keys = {
    'APOLLO_NATIVE_ASSIST','APOLLO_RUNTIME_LOCK_MODE','APOLLO_SERVER_ROOT','APOLLO_SERVER_JAR_RELATIVE',
    'APOLLO_FINGERPRINT_FILE_SHA256','APOLLO_BOOTSTRAP_ENV_PATH','APOLLO_BOOTSTRAP_ENV_KIND',
    'APOLLO_BOOTSTRAP_ENV_MODE','APOLLO_BOOTSTRAP_ENV_SHA256','APOLLO_BOOTSTRAP_BASH_PATH',
    'APOLLO_BOOTSTRAP_BASH_KIND','APOLLO_BOOTSTRAP_BASH_MODE','APOLLO_BOOTSTRAP_BASH_SHA256',
    'APOLLO_ORIGINAL_ENTRYPOINT_COUNT','APOLLO_ORIGINAL_ENTRYPOINT_0_PATH','APOLLO_ORIGINAL_ENTRYPOINT_0_KIND',
    'APOLLO_ORIGINAL_ENTRYPOINT_0_MODE','APOLLO_ORIGINAL_ENTRYPOINT_0_SHA256','APOLLO_ORIGINAL_ENTRYPOINT_1_PATH',
    'APOLLO_ORIGINAL_ENTRYPOINT_1_KIND','APOLLO_ORIGINAL_ENTRYPOINT_1_MODE','APOLLO_ORIGINAL_ENTRYPOINT_1_SHA256',
}
if {key for key in environment if key.startswith('APOLLO_')} != expected_apollo_keys:
    raise RuntimeError('apollo-environment-set-invalid')
expected_entrypoint = [
    '/usr/bin/env','-u','BASH_ENV','-u','ENV','-u','BASHOPTS','-u','SHELLOPTS','-u','BASH_FUNC_builtin%%',
    '/bin/bash','--noprofile','--norc','/opt/apollo-native/apollo-native-entrypoint.sh',
    environment['APOLLO_ORIGINAL_ENTRYPOINT_COUNT'],environment['APOLLO_ORIGINAL_ENTRYPOINT_0_PATH'],
    environment['APOLLO_ORIGINAL_ENTRYPOINT_1_PATH'],
]
if entrypoint != expected_entrypoint:
    raise RuntimeError('apollo-entrypoint-missing')
if expected_phase not in ('off', 'on') or environment.get('APOLLO_NATIVE_ASSIST') != expected_phase:
    raise RuntimeError('apollo-environment-missing')
if not isinstance(volumes, list) or not isinstance(security, list) or 'no-new-privileges:true' not in security:
    raise RuntimeError('apollo-boundary-missing')
targets = {}
apollo_mounts = []
for volume in volumes:
    if isinstance(volume, dict) and isinstance(volume.get('target'), str):
        target = volume['target']
        source = volume.get('source')
        if target in ('/opt/apollo-native', server_root, server_root + '/steamapps/workshop'):
            if volume.get('type') != 'bind' or not isinstance(source, str) or not source.startswith('/') or target in targets:
                raise RuntimeError('apollo-mount-invalid')
            targets[target] = bool(volume.get('read_only', False))
            apollo_mounts.append({'source': source, 'target': target, 'readOnly': targets[target]})
if targets.get('/opt/apollo-native') is not True or targets.get(server_root) is not True or targets.get(server_root + '/steamapps/workshop') is not False:
    raise RuntimeError('apollo-mounts-missing')
if targets.keys() != {'/opt/apollo-native', server_root, server_root + '/steamapps/workshop'}:
    raise RuntimeError('apollo-mounts-invalid')
if next(item['source'] for item in apollo_mounts if item['target'] == '/opt/apollo-native') != deployment + '/.apollo-native':
    raise RuntimeError('apollo-companion-source-invalid')
result = {
    'schemaVersion': 1,
    'serviceName': service,
    'composeInvariantSha256': hashlib.sha256(canonical(invariant)).hexdigest(),
    'otherServicesSha256': hashlib.sha256(canonical(others)).hexdigest(),
    'imageReference': selected.get('image') or '',
    'expectedPhase': expected_phase,
    'apolloEnvironment': {key: environment[key] for key in sorted(expected_apollo_keys)},
    'apolloMounts': sorted(apollo_mounts, key=lambda item: item['target']),
    'apolloOverridePresent': True,
}
print(json.dumps(result, sort_keys=True, separators=(',', ':')))
""";

    private const string DiscoveryProgram = """
import hashlib, json, os, pathlib, re, subprocess, sys

MAX = 1024 * 1024
deployment = os.environ['APOLLO_DEPLOYMENT']
service = os.environ['APOLLO_SERVICE']
files = [os.environ[f'APOLLO_COMPOSE_{i}'] for i in range(int(os.environ['APOLLO_COMPOSE_COUNT']))]
compose = ['docker', 'compose'] + [part for path in files for part in ('-f', path)]

def run(argv, *, allow_failure=False):
    completed = subprocess.run(argv, cwd=deployment, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, timeout=45, check=False)
    if len(completed.stdout) + len(completed.stderr) > MAX:
        raise RuntimeError('bounded-output-exceeded')
    if completed.returncode != 0 and not allow_failure:
        raise RuntimeError('remote-probe-failed')
    return completed.stdout.decode('utf-8', 'strict').strip()

config = json.loads(run(compose + ['config', '--format', 'json']))
services = config.get('services')
if not isinstance(services, dict) or service not in services:
    raise RuntimeError('service-missing')
compose_service = services[service]
if not isinstance(compose_service, dict):
    raise RuntimeError('service-invalid')
def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode('ascii')
def invariant_service(value, root):
    invariant = {key: item for key, item in value.items() if key not in {'entrypoint', 'environment', 'volumes', 'security_opt'}}
    invariant['environment'] = {key: item for key, item in (value.get('environment') or {}).items() if not key.startswith('APOLLO_')}
    normalized_volumes = []
    for volume in value.get('volumes') or []:
        if not isinstance(volume, dict):
            normalized_volumes.append(volume)
            continue
        target = volume.get('target')
        if target == '/opt/apollo-native':
            continue
        normalized = dict(volume)
        if target in (root, root + '/steamapps/workshop'):
            normalized['read_only'] = 'apollo-managed'
            bind = dict(normalized.get('bind') or {})
            bind['create_host_path'] = 'apollo-managed'
            normalized['bind'] = bind
        normalized_volumes.append(normalized)
    invariant['volumes'] = normalized_volumes
    invariant['security_opt'] = [item for item in (value.get('security_opt') or []) if item != 'no-new-privileges:true']
    return invariant
other_services = {key: value for key, value in services.items() if key != service}
ids = [line for line in run(compose + ['ps', '-q', '--', service]).splitlines() if line]
if len(ids) != 1:
    raise RuntimeError('service-ambiguous')
cid = ids[0]
inspected = json.loads(run(['docker', 'inspect', cid]))
if not isinstance(inspected, list) or len(inspected) != 1:
    raise RuntimeError('inspect-ambiguous')
container = inspected[0]
cfg = container.get('Config') or {}
mounts = container.get('Mounts') or []
compose_image = compose_service.get('image')
if not isinstance(compose_image, str) or '@sha256:' not in compose_image or cfg.get('Image') != compose_image:
    raise RuntimeError('compose-image-drift')
image_inspect = json.loads(run(['docker', 'image', 'inspect', compose_image]))
if not isinstance(image_inspect, list) or len(image_inspect) != 1 or compose_image not in (image_inspect[0].get('RepoDigests') or []):
    raise RuntimeError('registry-digest-unverified')
if 'entrypoint' in compose_service and compose_service.get('entrypoint') != cfg.get('Entrypoint'):
    raise RuntimeError('compose-entrypoint-drift')
if 'command' in compose_service and compose_service.get('command') != cfg.get('Cmd'):
    raise RuntimeError('compose-command-drift')
compose_healthcheck = compose_service.get('healthcheck') is not None
if compose_healthcheck != (cfg.get('Healthcheck') is not None):
    raise RuntimeError('compose-healthcheck-drift')
compose_restart = compose_service.get('restart') or 'no'
restart_match = re.fullmatch(r'(no|always|unless-stopped|on-failure)(?::([0-9]+))?', compose_restart)
if restart_match is None or restart_match.group(1) != 'on-failure' and restart_match.group(2) is not None:
    raise RuntimeError('compose-restart-invalid')
expected_restart_name = restart_match.group(1)
expected_restart_retries = int(restart_match.group(2) or '0')
running_restart_model = ((container.get('HostConfig') or {}).get('RestartPolicy') or {})
running_restart = running_restart_model.get('Name') or 'no'
running_restart_retries = int(running_restart_model.get('MaximumRetryCount') or 0)
if expected_restart_name != running_restart or expected_restart_retries != running_restart_retries:
    raise RuntimeError('compose-restart-drift')

def dexec(*argv, allow_failure=False):
    return run(['docker', 'exec', cid, *argv], allow_failure=allow_failure)

def regular_non_symlink(path):
    regular = subprocess.run(['docker', 'exec', cid, '/usr/bin/test', '-f', path], stdin=subprocess.DEVNULL,
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15).returncode == 0
    linked = subprocess.run(['docker', 'exec', cid, '/usr/bin/test', '-L', path], stdin=subprocess.DEVNULL,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15).returncode == 0
    return regular and not linked

def identity(path):
    if not regular_non_symlink(path):
        raise RuntimeError('file-identity-invalid')
    raw = dexec('/usr/bin/stat', '-c', '%F|%a', '--', path)
    kind, mode = raw.rsplit('|', 1)
    if kind != 'regular file' or not re.fullmatch(r'[0-7]{3,4}', mode):
        raise RuntimeError('file-identity-invalid')
    sha = dexec('/usr/bin/sha256sum', '--', path).split()[0]
    return {'path': path, 'kind': 'file', 'mode': mode.zfill(4), 'sha256': sha}

def declared_mounts():
    declared = compose_service.get('volumes') or []
    result = set()
    for volume in declared:
        if not isinstance(volume, dict) or volume.get('type') != 'bind':
            raise RuntimeError('compose-volume-invalid')
        source, target = volume.get('source'), volume.get('target')
        if not isinstance(source, str) or not isinstance(target, str):
            raise RuntimeError('compose-volume-invalid')
        bind = volume.get('bind') or {}
        if not isinstance(bind, dict) or set(bind) - {'create_host_path', 'propagation'}:
            raise RuntimeError('compose-bind-options-unsupported')
        if volume.get('consistency') not in (None, ''):
            raise RuntimeError('compose-bind-consistency-unsupported')
        propagation = bind.get('propagation') or 'rprivate'
        result.add(('bind', source, target, not bool(volume.get('read_only', False)), propagation))
    if len(result) != len(declared):
        raise RuntimeError('compose-volume-duplicate')
    return result

running_mounts = {
    (str(mount.get('Type') or '').lower(), mount.get('Source'), mount.get('Destination'), bool(mount.get('RW')), mount.get('Propagation') or 'rprivate')
    for mount in mounts
}
if declared_mounts() != running_mounts:
    raise RuntimeError('compose-mount-drift')

def normalize_ip(value):
    return '' if value in (None, '', '0.0.0.0') else str(value)

declared_ports = set()
for port in compose_service.get('ports') or []:
    if not isinstance(port, dict) or port.get('published') is None:
        raise RuntimeError('compose-port-invalid')
    declared_ports.add((str(port.get('target')), str(port.get('published')), str(port.get('protocol') or 'tcp'), normalize_ip(port.get('host_ip'))))
running_ports = set()
for key, bindings in ((container.get('HostConfig') or {}).get('PortBindings') or {}).items():
    target, protocol = key.rsplit('/', 1)
    for binding in bindings or []:
        running_ports.add((str(target), str(binding.get('HostPort') or ''), protocol, normalize_ip(binding.get('HostIp'))))
if declared_ports != running_ports:
    raise RuntimeError('compose-port-drift')

compose_network_mode = compose_service.get('network_mode')
running_network_mode = (container.get('HostConfig') or {}).get('NetworkMode')
if compose_network_mode:
    if compose_network_mode != running_network_mode:
        raise RuntimeError('compose-network-mode-drift')
else:
    declared_networks = compose_service.get('networks')
    network_specs = declared_networks if isinstance(declared_networks, dict) else {alias: None for alias in (declared_networks or ['default'])}
    aliases = list(network_specs)
    network_models = config.get('networks') or {}
    expected_networks = {((network_models.get(alias) or {}).get('name') or alias): network_specs[alias] or {} for alias in aliases}
    running_networks = (((container.get('NetworkSettings') or {}).get('Networks')) or {})
    if set(expected_networks) != set(running_networks):
        raise RuntimeError('compose-network-drift')
    automatic_aliases = {service, str(container.get('Name') or '').lstrip('/'), cid, cid[:12]}
    for network_name, spec in expected_networks.items():
        if not isinstance(spec, dict) or set(spec) - {'aliases','ipv4_address','ipv6_address','link_local_ips','mac_address','driver_opts','gw_priority','priority'}:
            raise RuntimeError('compose-network-options-unsupported')
        if int(spec.get('priority') or 0) != 0:
            raise RuntimeError('compose-network-priority-unsupported')
        running = running_networks[network_name] or {}
        expected_aliases = set(spec.get('aliases') or [])
        actual_aliases = set(running.get('Aliases') or []) - automatic_aliases
        if expected_aliases != actual_aliases:
            raise RuntimeError('compose-network-alias-drift')
        expected_ipv4 = spec.get('ipv4_address') or ''
        expected_ipv6 = spec.get('ipv6_address') or ''
        ipam = running.get('IPAMConfig') or {}
        if expected_ipv4 != (ipam.get('IPv4Address') or ''):
            raise RuntimeError('compose-network-ipv4-drift')
        if expected_ipv6 != (ipam.get('IPv6Address') or ''):
            raise RuntimeError('compose-network-ipv6-drift')
        if expected_ipv4 and (running.get('IPAddress') or '') != expected_ipv4.split('/', 1)[0]:
            raise RuntimeError('running-network-ipv4-drift')
        if expected_ipv6 and (running.get('GlobalIPv6Address') or '') != expected_ipv6.split('/', 1)[0]:
            raise RuntimeError('running-network-ipv6-drift')
        if set(spec.get('link_local_ips') or []) != set(ipam.get('LinkLocalIPs') or []):
            raise RuntimeError('compose-network-link-local-drift')
        if spec.get('mac_address') and spec.get('mac_address') != (running.get('MacAddress') or ''):
            raise RuntimeError('compose-network-mac-drift')
        if dict(spec.get('driver_opts') or {}) != dict(running.get('DriverOpts') or {}):
            raise RuntimeError('compose-network-driver-options-drift')
        if int(spec.get('gw_priority') or 0) != int(running.get('GwPriority') or 0):
            raise RuntimeError('compose-network-gateway-priority-drift')

candidates = []
for mount in mounts:
    destination = mount.get('Destination')
    if not isinstance(destination, str):
        continue
    for relative in ('java/projectzomboid.jar', 'projectzomboid.jar'):
        candidate = destination.rstrip('/') + '/' + relative
        if regular_non_symlink(candidate):
            candidates.append((mount, destination, relative, candidate))
if len(candidates) != 1:
    raise RuntimeError('server-root-ambiguous')
server_mount, server_root, server_jar_relative, server_jar = candidates[0]
compose_invariant = invariant_service(compose_service, server_root)

workshop_target = server_root.rstrip('/') + '/steamapps/workshop'
workshop = [mount for mount in mounts if mount.get('Destination') == workshop_target]
if len(workshop) != 1:
    raise RuntimeError('workshop-mount-missing')

native_lines = [
('09614762c651fc8b52c63ac14cb0ef01eb1d23c055bd2536945ec124f0232fa0','libpzexe_jni64.so'),
('0f2c41c20644503c17e13498203986493332fc8296dbd78493bc1fed352ec0cc','libsteam_api.so'),
('ffc6217cb3548d9682540af5707c2792f9d35bc9d74884b36edae4b6c28aeec2','libsteamwebrtc.so'),
('b5d6178a789280c3bc4787ddf24f1bd44e4037d2d097bf6b4e5b4174c9b67354','linux64/libPZBullet64.so'),
('256304a998a33fa9ba356182cad3ebaad0db14ac36762b806a950d0f08e95d6f','linux64/libPZBulletNoOpenGL64.so'),
('04fb6e7995e6ece95093e0f47f6417c151473567f6b8a3b0ac2dd68ea10a2cad','linux64/libPZClipper64.so'),
('0777dda6db77ddd3059f27f94e0d56fae827b21436b5feb4d719e96878fd21c4','linux64/libPZPathFind64.so'),
('baa9213172885e82310a40886359fd831c766dc726469f60dd75831a51a2bbe0','linux64/libPZPopMan64.so'),
('e556dd4141e4d7d37036bde460e18646ec8922f2df20d8cb524efb8332161f05','linux64/libPZXInitThreads64.so'),
('d818c1dc7b6ac3f62bc5b213e939018bcdff670769fe5ef3b57972d88a8612f7','linux64/libRakNet64.so'),
('1f5287032e128c0adaacb672a6a954f9ab840c0bb0519034dd70340dc3c3246e','linux64/libZNetJNI64.so'),
('29865ab29d7cf7c3533a8d8dda693d71774c1c3adf7d9722e04ff7f27921564e','linux64/libZNetNoSteam64.so'),
('5d145bb4e49f5aad0f07023a4e5d0c1fc19d8a7d5b817e884e618054e20ed57d','linux64/libjassimp64.so'),
('09614762c651fc8b52c63ac14cb0ef01eb1d23c055bd2536945ec124f0232fa0','linux64/libpzexe_jni64.so'),
('0f2c41c20644503c17e13498203986493332fc8296dbd78493bc1fed352ec0cc','linux64/libsteam_api.so'),
('d719d7e93116e53b9e318823e36dd50a4186ea3193485c7a54d8a759495bb759','linux64/libsteamwebrtc.so'),
('d8fbc2925af26522c3316f8bad2ac307b4726391a6174c220ca760fc3a421591','linux64/steamclient.so'),
('c5f9d9551cce91ea4b4f6ffe1e256ea169128ad522c033afdf5fc1372bc73821','steamclient.so'),
]
native_ok = True
for expected, relative in native_lines:
    native_path = server_root.rstrip('/') + '/' + relative
    actual = dexec('/usr/bin/sha256sum', '--', native_path, allow_failure=True) if regular_non_symlink(native_path) else ''
    native_ok = native_ok and bool(actual) and actual.split()[0] == expected
manifest_bytes = ''.join(f'{digest}  {relative}\n' for digest, relative in native_lines).encode('ascii')

app_manifest = ''
for candidate in (server_root + '/steamapps/appmanifest_380870.acf', server_root + '/appmanifest_380870.acf'):
    if regular_non_symlink(candidate):
        app_manifest = dexec('/usr/bin/cat', '--', candidate)
        break
app_match = re.search(r'"appid"\s+"([0-9]+)"', app_manifest, re.I)
build_match = re.search(r'"buildid"\s+"([0-9]+)"', app_manifest, re.I)
if app_match is None or app_match.group(1) != '380870' or build_match is None:
    raise RuntimeError('app-manifest-invalid')
started_at = ((container.get('State') or {}).get('StartedAt'))
if not isinstance(started_at, str) or not started_at:
    raise RuntimeError('container-start-unavailable')
logs = run(['docker', 'logs', '--since', started_at, '--tail', '10000', cid], allow_failure=True)
versions = set(re.findall(r'(?i)version\s*[=: ]\s*(\d+\.\d+\.\d+)', logs))
if len(versions) != 1:
    raise RuntimeError('current-version-ambiguous')
processes = dexec('/bin/bash', '--noprofile', '--norc', '-c', 'for p in /proc/[0-9]*; do test -r "$p/cmdline" || continue; exe=$(readlink -f "$p/exe" 2>/dev/null || true); test -n "$exe" || continue; printf "%s|%s|" "${p##*/}" "$exe"; tr "\\0" " " <"$p/cmdline"; printf "\\n"; done')
java_processes = []
for line in processes.splitlines():
    parts = line.split('|', 2)
    if len(parts) == 3 and ('zombie.network.GameServer' in parts[2] or 'projectzomboid.jar' in parts[2]) and pathlib.PurePosixPath(parts[1]).name.startswith('java'):
        java_processes.append(parts)
if len(java_processes) != 1:
    raise RuntimeError('server-java-process-ambiguous')
java_completed = subprocess.run(['docker','exec',cid,java_processes[0][1],'-version'], cwd=deployment,
                                stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                timeout=15, check=False)
if java_completed.returncode != 0 or len(java_completed.stdout) + len(java_completed.stderr) > MAX:
    raise RuntimeError('server-java-version-command-failed')
java_text = (java_completed.stdout + java_completed.stderr).decode('utf-8', 'strict')
java_match = re.search(r'version\s+"(\d+)', java_text)
if java_match is None:
    raise RuntimeError('server-java-version-invalid')

entrypoint = cfg.get('Entrypoint')
if not isinstance(entrypoint, list) or not entrypoint:
    raise RuntimeError('entrypoint-missing')
server_host = server_mount.get('Source')
workshop_host = workshop[0].get('Source')
if not isinstance(server_host, str) or not isinstance(workshop_host, str):
    raise RuntimeError('host-mount-missing')
companion = deployment.rstrip('/') + '/.apollo-native'
workshop_access_script = 'test "$(id -u)" = "$(stat -c %u -- /proc/$1)" && test -r "$2" -a -w "$2" -a -x "$2"'
workshop_accessible = subprocess.run(
    ['docker','exec',cid,'/bin/bash','--noprofile','--norc','-c',workshop_access_script,'apollo-workshop',java_processes[0][0],workshop_target],
    stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15).returncode == 0

service_json = {
    'name': service,
    'appId': app_match.group(1),
    'buildId': build_match.group(1),
    'gameVersion': next(iter(versions)),
    'javaFeature': int(java_match.group(1)),
    'os': 'linux' if dexec('/usr/bin/uname', '-s').lower() == 'linux' else '',
    'arch': {'x86_64':'amd64','aarch64':'arm64'}.get(dexec('/usr/bin/uname', '-m'), ''),
    'imageDigest': compose_image.rsplit('@', 1)[1],
    'imageReference': compose_image,
    'composeInvariantSha256': hashlib.sha256(canonical(compose_invariant)).hexdigest(),
    'otherServicesSha256': hashlib.sha256(canonical(other_services)).hexdigest(),
    'cmd': cfg.get('Cmd'),
    'entrypoint': [identity(path) for path in entrypoint],
    'runtimeLockMode': 'none-captured',
    'serverJarSha256': dexec('/usr/bin/sha256sum', '--', server_jar).split()[0],
    'nativeManifestSha256': hashlib.sha256(manifest_bytes).hexdigest(),
    'nativeLibrariesVerified': native_ok,
    'serverHostDirectory': server_host,
    'serverRoot': server_root,
    'serverJarRelative': server_jar_relative,
    'serverRootIsSymlink': subprocess.run(['docker','exec',cid,'/usr/bin/test','-L',server_root]).returncode == 0,
    'serverRootReadOnly': not bool(server_mount.get('RW')),
    'workshopHostDirectory': workshop_host,
    'workshopWritable': bool(workshop[0].get('RW')) and workshop_accessible,
    'companionHostDirectory': companion,
    'companionParentIsSymlink': pathlib.Path(deployment).is_symlink(),
    'bootstrapEnv': identity('/usr/bin/env'),
    'bootstrapBash': identity('/bin/bash'),
    'restartPolicy': running_restart,
    'healthcheckConfigured': compose_healthcheck,
}
print(json.dumps({'schemaVersion':1,'services':[service_json]}, separators=(',',':'), sort_keys=True))
""";

    private static string Quote(string value) => "'" + value.Replace("'", "'\"'\"'", StringComparison.Ordinal) + "'";

    private static string Lines(params string[] lines) => string.Join('\n', lines) + "\n";

    private static ConnectionInfo CreateConnectionInfo(SshConnectionOptions options, List<IDisposable> resources)
        => new(options.Host, options.Port, options.Username, options.Credential.CreateAuthenticationMethod(options.Username, resources))
        {
            Timeout = options.OperationTimeout,
        };

    private static void ValidateFileMode(UnixFileMode mode)
    {
        const UnixFileMode allPermissions = UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute |
                                                UnixFileMode.GroupRead | UnixFileMode.GroupWrite | UnixFileMode.GroupExecute |
                                                UnixFileMode.OtherRead | UnixFileMode.OtherWrite | UnixFileMode.OtherExecute;
        if (mode == 0 || (mode & ~allPermissions) != 0)
        {
            throw new ArgumentOutOfRangeException(nameof(mode));
        }
    }

    private async Task EnsureSshConnectedAsync(CancellationToken cancellationToken)
    {
        if (!sshClient.IsConnected)
        {
            await sshClient.ConnectAsync(cancellationToken).ConfigureAwait(false);
        }
    }

    private async Task EnsureSftpConnectedAsync(CancellationToken cancellationToken)
    {
        if (!sftpClient.IsConnected)
        {
            await sftpClient.ConnectAsync(cancellationToken).ConfigureAwait(false);
        }
    }

    private async Task TryDeleteAsync(string path)
    {
        try
        {
            if (sftpClient.IsConnected && await sftpClient.ExistsAsync(path, CancellationToken.None).ConfigureAwait(false))
            {
                await sftpClient.DeleteFileAsync(path, CancellationToken.None).ConfigureAwait(false);
            }
        }
        catch
        {
            // Preserve the original upload failure; the random temporary name is never executable or referenced.
        }
    }

    private void VerifyHostKey(object? sender, Renci.SshNet.Common.HostKeyEventArgs eventArgs)
        => eventArgs.CanTrust = IsExpectedHostKey(options.HostKeySha256, eventArgs.FingerPrintSHA256);

    private void ThrowIfDisposed() => ObjectDisposedException.ThrowIf(disposed, this);
}
