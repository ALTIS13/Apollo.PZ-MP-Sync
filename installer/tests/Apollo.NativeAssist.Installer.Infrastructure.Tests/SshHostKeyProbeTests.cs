using System.Reflection;
using Apollo.NativeAssist.Installer.Infrastructure.Ssh;
using Renci.SshNet;
using Xunit;

namespace Apollo.NativeAssist.Installer.Infrastructure.Tests;

public sealed class SshHostKeyProbeTests
{
    private static readonly byte[] HostKey = Convert.FromHexString(new string('a', 64));

    [Fact]
    public async Task Observation_never_receives_a_credential_and_hashes_the_raw_key_itself()
    {
        var transport = new FakeHostKeyObservationTransport
        {
            Evidence = [("ssh-ed25519", HostKey)],
        };

        var result = await new SshHostKeyProbe(transport).ObserveAsync(
            "pz.example.net",
            22,
            "steam",
            TimeSpan.FromSeconds(10),
            TestContext.Current.CancellationToken);

        Assert.Equal("ssh-ed25519", result.Algorithm);
        Assert.Equal("4Od6UHQSsSD27eYfYilbGnsv8Z09zI9yU+UWY0cMiI4", result.Sha256);
        Assert.Equal(43, result.Sha256.Length);
        Assert.False(result.Sha256.EndsWith('='));
        Assert.Equal(0, transport.CredentialObjectsObserved);
        Assert.Equal([true], transport.TrustDecisions);
    }

    [Fact]
    public void Public_observation_api_cannot_receive_credentials_passphrases_or_private_key_bytes()
    {
        var method = Assert.Single(typeof(IHostKeyProbe).GetMethods());
        Assert.Equal(
            ["host", "port", "username", "timeout", "cancellationToken"],
            method.GetParameters().Select(parameter => parameter.Name));
        Assert.Equal(
            [typeof(string), typeof(int), typeof(string), typeof(TimeSpan), typeof(CancellationToken)],
            method.GetParameters().Select(parameter => parameter.ParameterType));

        var constructor = Assert.Single(typeof(SshHostKeyProbe).GetConstructors(BindingFlags.Instance | BindingFlags.Public));
        Assert.Empty(constructor.GetParameters());

        var observationApiTypes = new[] { typeof(IHostKeyProbe), typeof(SshHostKeyProbe), typeof(SshHostKeyObservation) };
        var publicParameters = observationApiTypes
            .SelectMany(type => type.GetMethods(BindingFlags.Instance | BindingFlags.Static | BindingFlags.Public))
            .SelectMany(callable => callable.GetParameters())
            .Concat(observationApiTypes
                .SelectMany(type => type.GetConstructors(BindingFlags.Instance | BindingFlags.Public))
                .SelectMany(callable => callable.GetParameters()));
        Assert.DoesNotContain(publicParameters, parameter =>
            parameter.ParameterType == typeof(byte[]) ||
            typeof(SshCredential).IsAssignableFrom(parameter.ParameterType) ||
            parameter.Name?.Contains("password", StringComparison.OrdinalIgnoreCase) is true ||
            parameter.Name?.Contains("passphrase", StringComparison.OrdinalIgnoreCase) is true ||
            parameter.Name?.Contains("privateKey", StringComparison.OrdinalIgnoreCase) is true);
    }

    [Fact]
    public void Production_observation_connection_uses_only_none_authentication()
    {
        var connection = SshNetHostKeyObservationTransport.CreateConnectionInfo(
            "pz.example.net",
            22,
            "steam",
            TimeSpan.FromSeconds(10));
        try
        {
            Assert.IsType<NoneAuthenticationMethod>(Assert.Single(connection.AuthenticationMethods));
        }
        finally
        {
            foreach (var authenticationMethod in connection.AuthenticationMethods)
            {
                authenticationMethod.Dispose();
            }
        }
    }

    [Fact]
    public async Task Missing_host_key_evidence_fails_closed()
    {
        var exception = await Assert.ThrowsAsync<InvalidDataException>(() => Probe([]));

        Assert.Contains("exactly one", exception.Message, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task Duplicate_host_key_evidence_is_rejected_and_the_cloned_key_is_zeroed()
    {
        var transport = new FakeHostKeyObservationTransport
        {
            Evidence = [("ssh-ed25519", HostKey), ("ssh-ed25519", HostKey)],
        };

        await Assert.ThrowsAsync<InvalidDataException>(() => Observe(transport));

        Assert.Equal([true, false], transport.TrustDecisions);
        AssertCapturedCloneWasZeroed(transport);
    }

    [Fact]
    public async Task Changed_host_key_evidence_fails_closed_and_zeroes_the_cloned_key()
    {
        var changed = HostKey.ToArray();
        changed[0] ^= 0xff;
        var transport = new FakeHostKeyObservationTransport
        {
            Evidence = [("ssh-ed25519", HostKey), ("ssh-ed25519", changed)],
        };

        await Assert.ThrowsAsync<InvalidDataException>(() => Observe(transport));

        Assert.Equal([true, false], transport.TrustDecisions);
        AssertCapturedCloneWasZeroed(transport);
    }

    [Theory]
    [InlineData(0)]
    [InlineData(16385)]
    public async Task Empty_or_oversized_raw_host_keys_fail_closed(int keyBytes)
    {
        var transport = new FakeHostKeyObservationTransport
        {
            Evidence = [("ssh-ed25519", new byte[keyBytes])],
        };

        await Assert.ThrowsAsync<InvalidDataException>(() => Observe(transport));

        Assert.Equal([false], transport.TrustDecisions);
    }

    [Fact]
    public async Task Oversized_authentication_banner_fails_closed_and_zeroes_the_cloned_key()
    {
        var transport = new FakeHostKeyObservationTransport
        {
            Evidence = [("ssh-ed25519", HostKey)],
            AuthenticationBannerBytes = 8193,
        };

        await Assert.ThrowsAsync<InvalidDataException>(() => Observe(transport));

        AssertCapturedCloneWasZeroed(transport);
    }

    [Theory]
    [InlineData("")]
    [InlineData(" ssh-ed25519")]
    [InlineData("ssh-ed25519\n")]
    [InlineData("ssh,ed25519")]
    [InlineData("ssh-ed25519/credential")]
    public async Task Invalid_algorithm_name_fails_closed(string algorithm)
    {
        var transport = new FakeHostKeyObservationTransport
        {
            Evidence = [(algorithm, HostKey)],
        };

        await Assert.ThrowsAsync<InvalidDataException>(() => Observe(transport));

        Assert.Equal([false], transport.TrustDecisions);
    }

    [Fact]
    public async Task Caller_cancellation_is_preserved_and_zeroes_the_cloned_key()
    {
        var transport = new FakeHostKeyObservationTransport
        {
            Evidence = [("ssh-ed25519", HostKey)],
            WaitForCancellation = true,
        };
        using var cancellation = new CancellationTokenSource();
        cancellation.CancelAfter(TimeSpan.FromMilliseconds(25));

        var exception = await Assert.ThrowsAnyAsync<OperationCanceledException>(() => new SshHostKeyProbe(transport).ObserveAsync(
            "pz.example.net",
            22,
            "steam",
            TimeSpan.FromSeconds(10),
            cancellation.Token));

        Assert.Equal(cancellation.Token, exception.CancellationToken);
        AssertCapturedCloneWasZeroed(transport);
    }

    [Fact]
    public async Task Timeout_aborts_the_transport_and_zeroes_the_cloned_key()
    {
        var transport = new FakeHostKeyObservationTransport
        {
            Evidence = [("ssh-ed25519", HostKey)],
            WaitForCancellation = true,
        };

        await Assert.ThrowsAsync<TimeoutException>(() => new SshHostKeyProbe(transport).ObserveAsync(
            "pz.example.net",
            22,
            "steam",
            TimeSpan.FromMilliseconds(25),
            TestContext.Current.CancellationToken));

        AssertCapturedCloneWasZeroed(transport);
    }

    [Fact]
    public async Task Fatal_transport_exception_propagates_and_zeroes_the_cloned_key()
    {
        var fatal = new IOException("socket reset");
        var transport = new FakeHostKeyObservationTransport
        {
            Evidence = [("ssh-ed25519", HostKey)],
            FatalException = fatal,
        };

        var actual = await Assert.ThrowsAsync<IOException>(() => Observe(transport));

        Assert.Same(fatal, actual);
        AssertCapturedCloneWasZeroed(transport);
    }

    [Fact]
    public async Task Unexpected_none_authentication_success_fails_closed_and_zeroes_the_cloned_key()
    {
        var transport = new FakeHostKeyObservationTransport
        {
            Evidence = [("ssh-ed25519", HostKey)],
            Outcome = HostKeyObservationConnectionOutcome.Connected,
        };

        var exception = await Assert.ThrowsAsync<InvalidDataException>(() => Observe(transport));

        Assert.Contains("unexpectedly succeeded", exception.Message, StringComparison.OrdinalIgnoreCase);
        AssertCapturedCloneWasZeroed(transport);
    }

    [Theory]
    [InlineData("", 22, "steam")]
    [InlineData("host name", 22, "steam")]
    [InlineData("pz.example.net", 0, "steam")]
    [InlineData("pz.example.net", 65536, "steam")]
    [InlineData("pz.example.net", 22, "root user")]
    public async Task Malformed_connection_input_is_rejected_before_transport(
        string host,
        int port,
        string username)
    {
        var transport = new FakeHostKeyObservationTransport();

        await Assert.ThrowsAnyAsync<ArgumentException>(() => new SshHostKeyProbe(transport).ObserveAsync(
            host,
            port,
            username,
            TimeSpan.FromSeconds(10),
            TestContext.Current.CancellationToken));

        Assert.Equal(0, transport.Calls);
    }

    [Theory]
    [InlineData(0)]
    [InlineData(-1)]
    [InlineData(301)]
    public async Task Unbounded_timeout_is_rejected_before_transport(int timeoutSeconds)
    {
        var transport = new FakeHostKeyObservationTransport();

        await Assert.ThrowsAsync<ArgumentOutOfRangeException>(() => new SshHostKeyProbe(transport).ObserveAsync(
            "pz.example.net",
            22,
            "steam",
            TimeSpan.FromSeconds(timeoutSeconds),
            TestContext.Current.CancellationToken));

        Assert.Equal(0, transport.Calls);
    }

    [Fact]
    public async Task Already_cancelled_call_never_starts_the_transport()
    {
        var transport = new FakeHostKeyObservationTransport();
        using var cancellation = new CancellationTokenSource();
        cancellation.Cancel();

        var exception = await Assert.ThrowsAnyAsync<OperationCanceledException>(() => new SshHostKeyProbe(transport).ObserveAsync(
            "pz.example.net",
            22,
            "steam",
            TimeSpan.FromSeconds(10),
            cancellation.Token));

        Assert.Equal(cancellation.Token, exception.CancellationToken);
        Assert.Equal(0, transport.Calls);
    }

    private static Task<SshHostKeyObservation> Probe((string Algorithm, byte[] Key)[] evidence)
        => Observe(new FakeHostKeyObservationTransport { Evidence = evidence });

    private static Task<SshHostKeyObservation> Observe(FakeHostKeyObservationTransport transport)
        => new SshHostKeyProbe(transport).ObserveAsync(
            "pz.example.net",
            22,
            "steam",
            TimeSpan.FromSeconds(10),
            TestContext.Current.CancellationToken);

    private static void AssertCapturedCloneWasZeroed(FakeHostKeyObservationTransport transport)
    {
        var captured = Assert.IsType<byte[]>(transport.CapturedClonedRawKey);
        Assert.NotSame(HostKey, captured);
        Assert.NotEmpty(captured);
        Assert.All(captured, value => Assert.Equal((byte)0, value));
    }

    private sealed class FakeHostKeyObservationTransport : IHostKeyObservationTransport
    {
        public (string Algorithm, byte[] Key)[] Evidence { get; init; } = [];
        public int? AuthenticationBannerBytes { get; init; }
        public HostKeyObservationConnectionOutcome Outcome { get; init; } = HostKeyObservationConnectionOutcome.AuthenticationRejected;
        public Exception? FatalException { get; init; }
        public bool WaitForCancellation { get; init; }
        public List<bool> TrustDecisions { get; } = [];
        public byte[]? CapturedClonedRawKey { get; private set; }
        public int Calls { get; private set; }
        public int CredentialObjectsObserved { get; private set; }

        public async Task<HostKeyObservationConnectionOutcome> ConnectAsync(
            string host,
            int port,
            string username,
            TimeSpan timeout,
            Func<string, ReadOnlyMemory<byte>, bool> hostKeyReceived,
            Action<int> authenticationBannerReceived,
            CancellationToken cancellationToken)
        {
            Calls++;
            CredentialObjectsObserved += new object[] { host, port, username, timeout, cancellationToken }
                .Count(value => value is SshCredential or byte[]);
            foreach (var (algorithm, key) in Evidence)
            {
                var canTrust = hostKeyReceived(algorithm, key);
                TrustDecisions.Add(canTrust);
                if (canTrust && CapturedClonedRawKey is null)
                {
                    var target = hostKeyReceived.Target;
                    Assert.NotNull(target);
                    var clonedBuffers = target.GetType()
                        .GetFields(BindingFlags.Instance | BindingFlags.NonPublic)
                        .Where(field => field.FieldType == typeof(byte[]))
                        .Select(field => field.GetValue(target))
                        .OfType<byte[]>()
                        .ToArray();
                    CapturedClonedRawKey = Assert.Single(clonedBuffers);
                }
            }

            if (AuthenticationBannerBytes is { } bannerBytes)
            {
                authenticationBannerReceived(bannerBytes);
            }

            if (FatalException is not null)
            {
                throw FatalException;
            }

            if (WaitForCancellation)
            {
                await Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken);
            }

            return Outcome;
        }
    }
}
