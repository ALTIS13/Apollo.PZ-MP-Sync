namespace Apollo.NativeAssist.Installer.Infrastructure.Ssh;

public sealed record SshHostKeyObservation(string Algorithm, string Sha256);

public interface IHostKeyProbe
{
    Task<SshHostKeyObservation> ObserveAsync(
        string host,
        int port,
        string username,
        TimeSpan timeout,
        CancellationToken cancellationToken);
}
