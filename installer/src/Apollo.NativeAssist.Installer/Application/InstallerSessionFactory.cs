using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using Microsoft.Win32.SafeHandles;
using Apollo.NativeAssist.Installer.Core;
using Apollo.NativeAssist.Installer.Infrastructure.Docker;
using Apollo.NativeAssist.Installer.Infrastructure.Ssh;
using Apollo.NativeAssist.Installer.Release;

namespace Apollo.NativeAssist.Installer.Application;

public sealed class InstallerSessionFactory
{
    private const int MaximumPrivateKeyBytes = 1024 * 1024;
    private readonly object observationLock = new();
    private readonly IHostKeyProbe hostKeyProbe;
    private readonly EmbeddedInstallerRelease release;
    private readonly RecoveryRecordStore recoveryStore;
    private readonly Func<SshConnectionOptions, IRemoteSession> remoteSessionFactory;
    private readonly Func<IRemoteSession, EmbeddedInstallerRelease, ConnectionInput, IReadOnlyList<string>, IServerConnector> connectorFactory;
    private readonly Func<string, byte[]> privateKeyReader;
    private readonly TimeSpan hostKeyTimeout;
    private ObservedHostKey? latestObservation;

    public InstallerSessionFactory()
        : this(
            new SshHostKeyProbe(),
            EmbeddedReleaseLoader.Load(new AssemblyReleaseResourceSource(Assembly.GetExecutingAssembly())),
            new RecoveryRecordStore(),
            options => new SshRemoteSession(options),
            null,
            TimeSpan.FromSeconds(30))
    {
    }

    public InstallerSessionFactory(
        IHostKeyProbe hostKeyProbe,
        EmbeddedInstallerRelease release,
        RecoveryRecordStore recoveryStore,
        Func<SshConnectionOptions, IRemoteSession> remoteSessionFactory,
        Func<IRemoteSession, EmbeddedInstallerRelease, ConnectionInput, IReadOnlyList<string>, IServerConnector>? connectorFactory = null,
        TimeSpan? hostKeyTimeout = null)
        : this(
            hostKeyProbe,
            release,
            recoveryStore,
            remoteSessionFactory,
            connectorFactory,
            hostKeyTimeout,
            ReadPrivateKey)
    {
    }

    internal InstallerSessionFactory(
        IHostKeyProbe hostKeyProbe,
        EmbeddedInstallerRelease release,
        RecoveryRecordStore recoveryStore,
        Func<SshConnectionOptions, IRemoteSession> remoteSessionFactory,
        Func<IRemoteSession, EmbeddedInstallerRelease, ConnectionInput, IReadOnlyList<string>, IServerConnector>? connectorFactory,
        TimeSpan? hostKeyTimeout,
        Func<string, byte[]> privateKeyReader)
    {
        this.hostKeyProbe = hostKeyProbe ?? throw new ArgumentNullException(nameof(hostKeyProbe));
        this.release = release ?? throw new ArgumentNullException(nameof(release));
        this.recoveryStore = recoveryStore ?? throw new ArgumentNullException(nameof(recoveryStore));
        this.remoteSessionFactory = remoteSessionFactory ?? throw new ArgumentNullException(nameof(remoteSessionFactory));
        this.connectorFactory = connectorFactory ?? (static (remote, embeddedRelease, input, absoluteComposeFiles) =>
            new DockerComposeConnector(
                remote, embeddedRelease.Manifest, embeddedRelease.Bundle,
                input.DeploymentDirectory, absoluteComposeFiles, input.ServiceName));
        this.privateKeyReader = privateKeyReader ?? throw new ArgumentNullException(nameof(privateKeyReader));
        this.hostKeyTimeout = hostKeyTimeout ?? TimeSpan.FromSeconds(30);
        if (this.hostKeyTimeout <= TimeSpan.Zero || this.hostKeyTimeout > TimeSpan.FromMinutes(5))
        {
            throw new ArgumentOutOfRangeException(nameof(hostKeyTimeout));
        }
    }

    public async Task<SshHostKeyObservation> ObserveHostKeyAsync(
        ConnectionInput input,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(input);
        cancellationToken.ThrowIfCancellationRequested();
        var observation = await hostKeyProbe.ObserveAsync(
            input.Host, input.Port, input.Username, hostKeyTimeout, cancellationToken).ConfigureAwait(false);
        ArgumentNullException.ThrowIfNull(observation);
        ValidateObservation(observation);
        lock (observationLock)
        {
            latestObservation = new ObservedHostKey(input, observation);
        }

        return observation;
    }

    public async Task<IInstallerSession> CreateConfirmedAsync(
        ConnectionInput input,
        SshHostKeyObservation confirmedObservation,
        ICredentialSource credentialSource,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(input);
        ArgumentNullException.ThrowIfNull(confirmedObservation);
        ArgumentNullException.ThrowIfNull(credentialSource);

        lock (observationLock)
        {
            var pendingObservation = latestObservation;
            latestObservation = null;
            if (pendingObservation is null ||
                !pendingObservation.Input.StructurallyEquals(input) ||
                pendingObservation.Observation != confirmedObservation)
            {
                throw new InvalidOperationException("The exact latest SSH host-key observation must be confirmed.");
            }
        }

        cancellationToken.ThrowIfCancellationRequested();

        CredentialInput supplied = credentialSource.Read()
            ?? throw new InvalidOperationException("Credential source returned no credential.");
        using (supplied)
        {
            cancellationToken.ThrowIfCancellationRequested();
            SshCredential? authentication = null;
            IRemoteSession? remote = null;
            IServerConnector? connector = null;
            try
            {
                authentication = CreateCredential(input.AuthenticationMode, supplied);
                cancellationToken.ThrowIfCancellationRequested();
                var options = new SshConnectionOptions(
                    input.Host, input.Port, input.Username, authentication, confirmedObservation.Sha256,
                    TimeSpan.FromMinutes(2), 256 * 1024);
                var createdRemote = remoteSessionFactory(options)
                    ?? throw new InvalidOperationException("Remote-session factory returned no session.");
                remote = new CredentialOwningRemoteSession(createdRemote, authentication);
                authentication = null!;
                var absoluteComposeFiles = input.AbsoluteComposeFiles();
                connector = connectorFactory(remote, release, input, absoluteComposeFiles)
                    ?? throw new InvalidOperationException("Connector factory returned no connector.");
                var ownedConnector = new RemoteOwningConnector(connector, remote);
                connector = ownedConnector;
                remote = null;
                var recoveryRecord = new RecoveryRecord(
                    input.Host, input.Port, input.Username, confirmedObservation.Sha256,
                    input.DeploymentDirectory, input.ComposeFiles, input.ServiceName, string.Empty);
                var recordingConnector = new RecoveryRecordingConnector(ownedConnector, recoveryStore, recoveryRecord);
                IInstallerSession session = new InstallerSession(
                    recordingConnector,
                    new InstallationOrchestrator(recordingConnector, release.Manifest));
                connector = null;
                return session;
            }
            catch
            {
                if (connector is not null)
                {
                    await connector.DisposeAsync().ConfigureAwait(false);
                }
                else if (remote is not null)
                {
                    await remote.DisposeAsync().ConfigureAwait(false);
                }
                else
                {
                    authentication?.Dispose();
                }

                throw;
            }
        }
    }

    private SshCredential CreateCredential(AuthenticationMode mode, CredentialInput supplied)
    {
        if (mode == AuthenticationMode.Password)
        {
            if (!supplied.HasPassword || supplied.HasPrivateKeyPath || supplied.HasPrivateKeyPassphrase)
            {
                throw new ArgumentException("Password authentication accepts only a password.", nameof(supplied));
            }

            var authenticationCharacters = supplied.CopyPassword();
            try
            {
                return new SshPasswordCredential(new string(authenticationCharacters));
            }
            finally
            {
                Array.Clear(authenticationCharacters);
            }
        }

        if (mode != AuthenticationMode.PrivateKey || supplied.HasPassword || !supplied.HasPrivateKeyPath)
        {
            throw new ArgumentException("Private-key authentication accepts only a key file and optional passphrase.", nameof(supplied));
        }

        var pathCharacters = supplied.CopyPrivateKeyPath();
        var passphraseCharacters = supplied.CopyPrivateKeyPassphrase();
        byte[]? privateKeyBytes = null;
        try
        {
            var path = new string(pathCharacters);
            try
            {
                privateKeyBytes = privateKeyReader(path);
                if (privateKeyBytes is null)
                {
                    throw new IOException();
                }
            }
            catch (InvalidDataException exception) when (
                exception.InnerException is null &&
                exception.Message is "private-key-file-invalid" or "private-key-file-size-invalid")
            {
                throw;
            }
            catch
            {
                throw new InvalidDataException("private-key-file-invalid");
            }

            var passphrase = passphraseCharacters is null ? null : new string(passphraseCharacters);
            return new SshPrivateKeyCredential(privateKeyBytes, passphrase);
        }
        finally
        {
            Array.Clear(pathCharacters);
            if (passphraseCharacters is not null)
            {
                Array.Clear(passphraseCharacters);
            }

            if (privateKeyBytes is not null)
            {
                CryptographicOperations.ZeroMemory(privateKeyBytes);
            }
        }
    }

    private static byte[] ReadPrivateKey(string path)
    {
        byte[]? bytes = null;
        try
        {
            if (string.IsNullOrWhiteSpace(path))
            {
                throw new IOException();
            }

            using var handle = OpenRegularFileWithoutFollowingLinks(path);
            var length = RandomAccess.GetLength(handle);
            if (length is < 1 or > MaximumPrivateKeyBytes)
            {
                throw new PrivateKeySizeException();
            }

            bytes = new byte[checked((int)length)];
            var offset = 0;
            while (offset < bytes.Length)
            {
                var read = RandomAccess.Read(handle, bytes.AsSpan(offset), offset);
                if (read == 0)
                {
                    throw new EndOfStreamException();
                }

                offset += read;
            }

            if (RandomAccess.GetLength(handle) != length)
            {
                throw new IOException();
            }

            return bytes;
        }
        catch (PrivateKeySizeException)
        {
            if (bytes is not null)
            {
                CryptographicOperations.ZeroMemory(bytes);
            }

            throw new InvalidDataException("private-key-file-size-invalid");
        }
        catch
        {
            if (bytes is not null)
            {
                CryptographicOperations.ZeroMemory(bytes);
            }

            throw new InvalidDataException("private-key-file-invalid");
        }
    }

    private static SafeFileHandle OpenRegularFileWithoutFollowingLinks(string path)
    {
        if (OperatingSystem.IsWindows())
        {
            const uint genericRead = 0x80000000;
            const uint fileShareRead = 0x00000001;
            const uint openExisting = 3;
            const uint fileAttributeNormal = 0x00000080;
            const uint fileFlagOpenReparsePoint = 0x00200000;
            const uint fileAttributeDirectory = 0x00000010;
            const uint fileAttributeReparsePoint = 0x00000400;
            const uint fileTypeDisk = 0x0001;

            var handle = NativeMethods.CreateFile(
                path,
                genericRead,
                fileShareRead,
                IntPtr.Zero,
                openExisting,
                fileAttributeNormal | fileFlagOpenReparsePoint,
                IntPtr.Zero);
            if (handle.IsInvalid)
            {
                handle.Dispose();
                throw new IOException();
            }

            try
            {
                if (NativeMethods.GetFileType(handle) != fileTypeDisk ||
                    !NativeMethods.GetFileInformationByHandleEx(
                        handle,
                        FileInfoByHandleClass.FileAttributeTagInfo,
                        out var information,
                        checked((uint)Marshal.SizeOf<FileAttributeTagInformation>())) ||
                    (information.FileAttributes & (fileAttributeDirectory | fileAttributeReparsePoint)) != 0)
                {
                    throw new IOException();
                }

                return handle;
            }
            catch
            {
                handle.Dispose();
                throw;
            }
        }

        if (!OperatingSystem.IsLinux())
        {
            throw new PlatformNotSupportedException();
        }

        const int openCloseOnExec = 0x00080000;
        const int openNoFollow = 0x00020000;
        const int atEmptyPath = 0x00001000;
        const uint statxType = 0x00000001;
        const ushort fileTypeMask = 0xf000;
        const ushort regularFileType = 0x8000;
        var flags = openCloseOnExec | openNoFollow;
        var descriptor = NativeMethods.Open(path, flags);
        if (descriptor < 0)
        {
            throw new IOException();
        }

        var unixHandle = new SafeFileHandle(new IntPtr(descriptor), ownsHandle: true);
        try
        {
            if (NativeMethods.Statx(descriptor, string.Empty, atEmptyPath, statxType, out var information) != 0 ||
                (information.Mode & fileTypeMask) != regularFileType)
            {
                throw new IOException();
            }

            return unixHandle;
        }
        catch
        {
            unixHandle.Dispose();
            throw;
        }
    }

    private sealed class PrivateKeySizeException : Exception;

    private enum FileInfoByHandleClass
    {
        FileAttributeTagInfo = 9,
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct FileAttributeTagInformation
    {
        public uint FileAttributes;
        public uint ReparseTag;
    }

    [StructLayout(LayoutKind.Explicit, Size = 256)]
    private struct StatxInformation
    {
        [FieldOffset(28)]
        public ushort Mode;
    }

    private static class NativeMethods
    {
        [DllImport("kernel32.dll", EntryPoint = "CreateFileW", SetLastError = true, CharSet = CharSet.Unicode)]
        internal static extern SafeFileHandle CreateFile(
            string fileName,
            uint desiredAccess,
            uint shareMode,
            IntPtr securityAttributes,
            uint creationDisposition,
            uint flagsAndAttributes,
            IntPtr templateFile);

        [DllImport("kernel32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool GetFileInformationByHandleEx(
            SafeFileHandle file,
            FileInfoByHandleClass fileInformationClass,
            out FileAttributeTagInformation fileInformation,
            uint bufferSize);

        [DllImport("kernel32.dll", SetLastError = true)]
        internal static extern uint GetFileType(SafeFileHandle file);

        [DllImport("libc", EntryPoint = "open", SetLastError = true, CharSet = CharSet.Ansi)]
        internal static extern int Open(string path, int flags);

        [DllImport("libc", EntryPoint = "statx", SetLastError = true, CharSet = CharSet.Ansi)]
        internal static extern int Statx(
            int directoryFileDescriptor,
            string path,
            int flags,
            uint mask,
            out StatxInformation information);
    }

    private static void ValidateObservation(SshHostKeyObservation observation)
    {
        if (observation.Algorithm is not { Length: >= 1 and <= 128 } ||
            !IsAsciiLetterOrDigit(observation.Algorithm[0]) ||
            observation.Algorithm.Any(character =>
                !IsAsciiLetterOrDigit(character) && character is not '@' and not '.' and not '_' and not '+' and not '-'))
        {
            throw new InvalidDataException("SSH host-key observation is invalid.");
        }

        if (!RecoveryRecordStore.IsValidHostKey(observation.Sha256))
        {
            throw new InvalidDataException("SSH host-key observation is invalid.");
        }
    }

    private static bool IsAsciiLetterOrDigit(char value)
        => value is >= 'A' and <= 'Z' or >= 'a' and <= 'z' or >= '0' and <= '9';

    private sealed record ObservedHostKey(ConnectionInput Input, SshHostKeyObservation Observation);

    private sealed class InstallerSession(
        IServerConnector connector,
        InstallationOrchestrator orchestrator) : IInstallerSession
    {
        private bool disposed;

        public IReadOnlyList<RedactedEvent> Events => orchestrator.Events;

        public Task<InstallationPreview> PreviewAsync(CancellationToken cancellationToken)
        {
            ThrowIfDisposed();
            return orchestrator.PreviewAsync(cancellationToken);
        }

        public Task<InstallationResult> InstallAsync(CancellationToken cancellationToken)
        {
            ThrowIfDisposed();
            return orchestrator.InstallAsync(cancellationToken);
        }

        public Task<InstallationResult> DisableAsync(CancellationToken cancellationToken)
        {
            ThrowIfDisposed();
            return orchestrator.DisableAsync(cancellationToken);
        }

        public Task<InstallationResult> RestoreAsync(string backupPath, CancellationToken cancellationToken)
        {
            ThrowIfDisposed();
            return orchestrator.RestoreAsync(backupPath, cancellationToken);
        }

        public async ValueTask DisposeAsync()
        {
            if (disposed)
            {
                return;
            }

            disposed = true;
            await connector.DisposeAsync().ConfigureAwait(false);
        }

        private void ThrowIfDisposed() => ObjectDisposedException.ThrowIf(disposed, this);
    }

    private sealed class RecoveryRecordingConnector(
        IServerConnector inner,
        RecoveryRecordStore store,
        RecoveryRecord record) : IServerConnector
    {
        public Task<RuntimeProbe> ProbeAsync(CancellationToken cancellationToken) => inner.ProbeAsync(cancellationToken);
        public IReadOnlyList<PlannedAction> DescribePlan() => inner.DescribePlan();

        public async Task<string> BackupAsync(CancellationToken cancellationToken)
        {
            var backupPath = await inner.BackupAsync(cancellationToken).ConfigureAwait(false);
            store.Save(record with { BackupPath = backupPath });
            return backupPath;
        }

        public Task StageDisabledAsync(CancellationToken cancellationToken) => inner.StageDisabledAsync(cancellationToken);
        public Task ValidateMergedConfigurationAsync(CancellationToken cancellationToken) => inner.ValidateMergedConfigurationAsync(cancellationToken);
        public Task RestartAsync(CancellationToken cancellationToken) => inner.RestartAsync(cancellationToken);
        public Task VerifyVanillaAsync(CancellationToken cancellationToken) => inner.VerifyVanillaAsync(cancellationToken);
        public Task EnableAsync(CancellationToken cancellationToken) => inner.EnableAsync(cancellationToken);
        public Task DisableAsync(CancellationToken cancellationToken) => inner.DisableAsync(cancellationToken);
        public Task VerifyReadyAsync(CancellationToken cancellationToken) => inner.VerifyReadyAsync(cancellationToken);
        public Task RollbackAsync(string backupPath, CancellationToken cancellationToken) => inner.RollbackAsync(backupPath, cancellationToken);
        public ValueTask DisposeAsync() => inner.DisposeAsync();
    }

    private sealed class CredentialOwningRemoteSession(IRemoteSession inner, SshCredential credential) : IRemoteSession
    {
        private bool disposed;

        public Task<RemoteResult> RunAsync(RemoteOperation operation, CancellationToken cancellationToken)
            => inner.RunAsync(operation, cancellationToken);

        public Task UploadAsync(Stream source, string absoluteDestination, UnixFileMode mode, CancellationToken cancellationToken)
            => inner.UploadAsync(source, absoluteDestination, mode, cancellationToken);

        public Task DownloadAsync(string absoluteSource, Stream destination, CancellationToken cancellationToken)
            => inner.DownloadAsync(absoluteSource, destination, cancellationToken);

        public async ValueTask DisposeAsync()
        {
            if (disposed)
            {
                return;
            }

            disposed = true;
            try
            {
                await inner.DisposeAsync().ConfigureAwait(false);
            }
            finally
            {
                credential.Dispose();
            }
        }
    }

    private sealed class RemoteOwningConnector(IServerConnector inner, IRemoteSession remote) : IServerConnector
    {
        private bool disposed;

        public Task<RuntimeProbe> ProbeAsync(CancellationToken cancellationToken) => inner.ProbeAsync(cancellationToken);
        public IReadOnlyList<PlannedAction> DescribePlan() => inner.DescribePlan();
        public Task<string> BackupAsync(CancellationToken cancellationToken) => inner.BackupAsync(cancellationToken);
        public Task StageDisabledAsync(CancellationToken cancellationToken) => inner.StageDisabledAsync(cancellationToken);
        public Task ValidateMergedConfigurationAsync(CancellationToken cancellationToken) => inner.ValidateMergedConfigurationAsync(cancellationToken);
        public Task RestartAsync(CancellationToken cancellationToken) => inner.RestartAsync(cancellationToken);
        public Task VerifyVanillaAsync(CancellationToken cancellationToken) => inner.VerifyVanillaAsync(cancellationToken);
        public Task EnableAsync(CancellationToken cancellationToken) => inner.EnableAsync(cancellationToken);
        public Task DisableAsync(CancellationToken cancellationToken) => inner.DisableAsync(cancellationToken);
        public Task VerifyReadyAsync(CancellationToken cancellationToken) => inner.VerifyReadyAsync(cancellationToken);
        public Task RollbackAsync(string backupPath, CancellationToken cancellationToken) => inner.RollbackAsync(backupPath, cancellationToken);

        public async ValueTask DisposeAsync()
        {
            if (disposed)
            {
                return;
            }

            disposed = true;
            try
            {
                await inner.DisposeAsync().ConfigureAwait(false);
            }
            finally
            {
                await remote.DisposeAsync().ConfigureAwait(false);
            }
        }
    }
}
