using System.Security.Cryptography;
using System.Text;
using Renci.SshNet;
using Renci.SshNet.Common;

namespace Apollo.NativeAssist.Installer.Infrastructure.Ssh;

internal enum HostKeyObservationConnectionOutcome
{
    AuthenticationRejected,
    Connected,
}

internal interface IHostKeyObservationTransport
{
    Task<HostKeyObservationConnectionOutcome> ConnectAsync(
        string host,
        int port,
        string username,
        TimeSpan timeout,
        Func<string, ReadOnlyMemory<byte>, bool> hostKeyReceived,
        Action<int> authenticationBannerReceived,
        CancellationToken cancellationToken);
}

internal sealed class SshNetHostKeyObservationTransport : IHostKeyObservationTransport
{
    public async Task<HostKeyObservationConnectionOutcome> ConnectAsync(
        string host,
        int port,
        string username,
        TimeSpan timeout,
        Func<string, ReadOnlyMemory<byte>, bool> hostKeyReceived,
        Action<int> authenticationBannerReceived,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(hostKeyReceived);
        ArgumentNullException.ThrowIfNull(authenticationBannerReceived);
        var connection = CreateConnectionInfo(host, port, username, timeout);
        SshClient? client = null;
        EventHandler<HostKeyEventArgs>? hostKeyHandler = null;
        EventHandler<AuthenticationBannerEventArgs>? bannerHandler = null;
        try
        {
            client = new SshClient(connection);
            hostKeyHandler = (_, eventArgs) =>
                eventArgs.CanTrust = hostKeyReceived(eventArgs.HostKeyName, eventArgs.HostKey);
            bannerHandler = (_, eventArgs) =>
                authenticationBannerReceived(Encoding.UTF8.GetByteCount(eventArgs.BannerMessage));
            client.HostKeyReceived += hostKeyHandler;
            connection.AuthenticationBanner += bannerHandler;
            try
            {
                await client.ConnectAsync(cancellationToken).ConfigureAwait(false);
                return HostKeyObservationConnectionOutcome.Connected;
            }
            catch (SshAuthenticationException)
            {
                return HostKeyObservationConnectionOutcome.AuthenticationRejected;
            }
        }
        finally
        {
            if (client is not null && hostKeyHandler is not null)
            {
                client.HostKeyReceived -= hostKeyHandler;
            }

            if (bannerHandler is not null)
            {
                connection.AuthenticationBanner -= bannerHandler;
            }

            try
            {
                client?.Dispose();
            }
            finally
            {
                foreach (var authenticationMethod in connection.AuthenticationMethods)
                {
                    authenticationMethod.Dispose();
                }
            }
        }
    }

    internal static ConnectionInfo CreateConnectionInfo(string host, int port, string username, TimeSpan timeout)
    {
        var authentication = new NoneAuthenticationMethod(username);
        try
        {
            return new ConnectionInfo(host, port, username, authentication)
            {
                Timeout = timeout,
            };
        }
        catch
        {
            authentication.Dispose();
            throw;
        }
    }
}

public sealed class SshHostKeyProbe : IHostKeyProbe
{
    private const int MaxAuthenticationBannerBytes = 8 * 1024;
    private const int MaxRawHostKeyBytes = 16 * 1024;
    private static readonly TimeSpan MaximumTimeout = TimeSpan.FromMinutes(5);
    private readonly IHostKeyObservationTransport transport;

    public SshHostKeyProbe()
        : this(new SshNetHostKeyObservationTransport())
    {
    }

    internal SshHostKeyProbe(IHostKeyObservationTransport transport)
    {
        this.transport = transport ?? throw new ArgumentNullException(nameof(transport));
    }

    public async Task<SshHostKeyObservation> ObserveAsync(
        string host,
        int port,
        string username,
        TimeSpan timeout,
        CancellationToken cancellationToken)
    {
        ValidateConnectionInput(host, port, username, timeout);
        cancellationToken.ThrowIfCancellationRequested();
        using var evidence = new HostKeyEvidence();
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(timeout);

        HostKeyObservationConnectionOutcome outcome;
        try
        {
            outcome = await transport.ConnectAsync(
                host,
                port,
                username,
                timeout,
                evidence.TryCapture,
                evidence.CaptureAuthenticationBanner,
                deadline.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw new OperationCanceledException("SSH host-key observation was cancelled.", cancellationToken);
        }
        catch (OperationCanceledException exception) when (deadline.IsCancellationRequested)
        {
            throw new TimeoutException("SSH host-key observation exceeded its bounded timeout.", exception);
        }

        if (cancellationToken.IsCancellationRequested)
        {
            throw new OperationCanceledException("SSH host-key observation was cancelled.", cancellationToken);
        }

        if (deadline.IsCancellationRequested)
        {
            throw new TimeoutException("SSH host-key observation exceeded its bounded timeout.");
        }

        if (outcome != HostKeyObservationConnectionOutcome.AuthenticationRejected)
        {
            throw new InvalidDataException("Credential-free SSH authentication unexpectedly succeeded.");
        }

        return evidence.CreateObservation();
    }

    private static void ValidateConnectionInput(string host, int port, string username, TimeSpan timeout)
    {
        if (!IsValidHost(host))
        {
            throw new ArgumentException("SSH host is invalid.", nameof(host));
        }

        if (port is < 1 or > 65535)
        {
            throw new ArgumentOutOfRangeException(nameof(port));
        }

        if (!IsValidUsername(username))
        {
            throw new ArgumentException("SSH username is invalid.", nameof(username));
        }

        if (timeout <= TimeSpan.Zero || timeout > MaximumTimeout)
        {
            throw new ArgumentOutOfRangeException(nameof(timeout));
        }
    }

    private static bool IsValidHost(string? value)
        => value is { Length: >= 1 and <= 253 } &&
           IsAsciiLetterOrDigit(value[0]) &&
           IsAsciiLetterOrDigit(value[^1]) &&
           value.All(character => IsAsciiLetterOrDigit(character) || character is '.' or ':' or '-');

    private static bool IsValidUsername(string? value)
        => value is { Length: >= 1 and <= 64 } &&
           (IsAsciiLetterOrDigit(value[0]) || value[0] == '_') &&
           value.All(character => IsAsciiLetterOrDigit(character) || character is '_' or '.' or '-');

    private static bool IsValidAlgorithm(string? value)
        => value is { Length: >= 1 and <= 128 } &&
           IsAsciiLetterOrDigit(value[0]) &&
           value.All(character => IsAsciiLetterOrDigit(character) || character is '@' or '.' or '_' or '+' or '-');

    private static bool IsAsciiLetterOrDigit(char value)
        => value is >= 'A' and <= 'Z' or >= 'a' and <= 'z' or >= '0' and <= '9';

    private sealed class HostKeyEvidence : IDisposable
    {
        private int observationCount;
        private bool invalid;
        private string? algorithm;
        private byte[]? rawKey;

        public bool TryCapture(string observedAlgorithm, ReadOnlyMemory<byte> observedRawKey)
        {
            observationCount++;
            if (observationCount != 1 ||
                !IsValidAlgorithm(observedAlgorithm) ||
                observedRawKey.Length is < 1 or > MaxRawHostKeyBytes)
            {
                invalid = true;
                return false;
            }

            algorithm = observedAlgorithm;
            rawKey = observedRawKey.ToArray();
            return true;
        }

        public void CaptureAuthenticationBanner(int byteCount)
        {
            if (byteCount is < 0 or > MaxAuthenticationBannerBytes)
            {
                invalid = true;
            }
        }

        public SshHostKeyObservation CreateObservation()
        {
            if (invalid || observationCount != 1 || algorithm is null || rawKey is null)
            {
                throw new InvalidDataException("SSH host-key observation requires exactly one valid, bounded key.");
            }

            var fingerprint = Convert.ToBase64String(SHA256.HashData(rawKey)).TrimEnd('=');
            return new SshHostKeyObservation(algorithm, fingerprint);
        }

        public void Dispose()
        {
            if (rawKey is not null)
            {
                CryptographicOperations.ZeroMemory(rawKey);
                rawKey = null;
            }
        }
    }
}
