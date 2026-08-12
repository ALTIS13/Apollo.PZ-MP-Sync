using System.Collections.ObjectModel;
using System.Collections.Specialized;
using System.ComponentModel;
using System.Globalization;
using System.Runtime.CompilerServices;
using System.Text;
using System.Windows.Input;
using Apollo.NativeAssist.Installer.Application;
using Apollo.NativeAssist.Installer.Core;
using Apollo.NativeAssist.Installer.Infrastructure.Ssh;

namespace Apollo.NativeAssist.Installer.ViewModels;

public enum WizardStep
{
    Connection,
    HostKeyTrust,
    Review,
    Progress,
    Result,
}

public enum RequestedOperation
{
    InstallOrUpdate,
    SafeDisable,
    FullRollback,
}

public interface IInstallerSessionWorkflow
{
    RecoveryLoadResult LoadRecovery();
    Task<SshHostKeyObservation> ObserveHostKeyAsync(ConnectionInput input, CancellationToken cancellationToken);
    Task<IInstallerSession> CreateConfirmedAsync(
        ConnectionInput input,
        SshHostKeyObservation observation,
        ICredentialSource credentialSource,
        CancellationToken cancellationToken);
}

public sealed class InstallerSessionWorkflow(
    InstallerSessionFactory factory,
    RecoveryRecordStore recoveryStore) : IInstallerSessionWorkflow
{
    public RecoveryLoadResult LoadRecovery() => recoveryStore.Load();

    public Task<SshHostKeyObservation> ObserveHostKeyAsync(
        ConnectionInput input,
        CancellationToken cancellationToken)
        => factory.ObserveHostKeyAsync(input, cancellationToken);

    public Task<IInstallerSession> CreateConfirmedAsync(
        ConnectionInput input,
        SshHostKeyObservation observation,
        ICredentialSource credentialSource,
        CancellationToken cancellationToken)
        => factory.CreateConfirmedAsync(input, observation, credentialSource, cancellationToken);
}

public sealed class MainWindowViewModel : INotifyPropertyChanged, IAsyncDisposable
{
    private static readonly HashSet<string> RenderedEventNames = new(StringComparer.Ordinal)
    {
        "installer.step", "installer.compatibility", "installer.result",
    };

    private static readonly HashSet<string> RenderedFieldNames = new(StringComparer.Ordinal)
    {
        "step", "state", "reasonCode", "supported",
    };

    private readonly IInstallerSessionWorkflow workflow;
    private readonly Action<string>? languageSwitcher;
    private readonly object lifecycleLock = new();
    private readonly HashSet<Task> activeTasks = [];
    private WizardStep step;
    private RequestedOperation requestedOperation;
    private AuthenticationMode authenticationMode;
    private string host = "pz.example.test";
    private string port = "22";
    private string username = "steam";
    private string deploymentDirectory = "/srv/pz";
    private string serviceName = "pz-server";
    private string password = "";
    private string privateKeyPath = "";
    private string privateKeyPassphrase = "";
    private string statusCode = "connection-required";
    private string language;
    private string visibleLog = "";
    private bool confirmPlannedRestarts;
    private bool previewSupported;
    private bool isBusy;
    private bool hasVerifiedContext;
    private SshHostKeyObservation? observation;
    private ConnectionInput? observedInput;
    private IInstallerSession? session;
    private RecoveryRecord? recoveryRecord;
    private CancellationTokenSource? operationCancellation;
    private IReadOnlyList<PlannedAction> plannedActions = [];
    private bool shutdownRequested;
    private Task? shutdownTask;

    public MainWindowViewModel(
        IInstallerSessionWorkflow workflow,
        string language = "en-US",
        Action<string>? languageSwitcher = null)
    {
        this.workflow = workflow ?? throw new ArgumentNullException(nameof(workflow));
        this.language = IsSupportedLanguage(language) ? language : "en-US";
        this.languageSwitcher = languageSwitcher;
        ComposeFiles = ["compose.yaml"];
        ComposeFiles.CollectionChanged += ComposeFilesChanged;

        CheckServerCommand = new AsyncCommand(_ => TrackAsync(CheckServerAsync), _ => Step == WizardStep.Connection && !IsBusy && !IsShuttingDown);
        TrustAndContinueCommand = new AsyncCommand(_ => TrackAsync(TrustAndContinueAsync), _ => Step == WizardStep.HostKeyTrust && !IsBusy && !IsShuttingDown);
        InstallCommand = new AsyncCommand(_ => TrackAsync(() => StartOperationOrReauthenticateAsync(RequestedOperation.InstallOrUpdate)),
            _ => Step == WizardStep.Review && PreviewSupported && ConfirmPlannedRestarts && !IsBusy && !IsShuttingDown && session is not null);
        SafeDisableCommand = new AsyncCommand(_ => TrackAsync(() => StartOperationOrReauthenticateAsync(RequestedOperation.SafeDisable)),
            _ => !IsBusy && !IsShuttingDown &&
                 (Step == WizardStep.Review && session is not null || Step == WizardStep.Result && hasVerifiedContext));
        FullRollbackCommand = new AsyncCommand(_ => TrackAsync(() => StartOperationOrReauthenticateAsync(RequestedOperation.FullRollback)),
            _ => !IsBusy && !IsShuttingDown && recoveryRecord is not null &&
                 (Step == WizardStep.Review && session is not null && RecoveryMatchesObservedInput() ||
                  Step == WizardStep.Result && hasVerifiedContext));
        CancelCommand = new RelayCommand(_ => Cancel(), _ => Step == WizardStep.Progress && operationCancellation is { IsCancellationRequested: false });
        RejectHostKeyCommand = new RelayCommand(_ => RejectHostKey(), _ => Step == WizardStep.HostKeyTrust && !IsBusy);
        AddComposeFileCommand = new RelayCommand(_ => ComposeFiles.Add($"compose-{ComposeFiles.Count + 1}.yaml"), _ => ComposeFiles.Count < 8 && IsConnectionEditable);
        RemoveComposeFileCommand = new RelayCommand(_ => ComposeFiles.RemoveAt(ComposeFiles.Count - 1), _ => ComposeFiles.Count > 1 && IsConnectionEditable);
        SwitchLanguageCommand = new RelayCommand(SwitchLanguage, value => value is string culture && IsSupportedLanguage(culture));

        LoadRecovery();
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    public ObservableCollection<string> ComposeFiles { get; }
    public AsyncCommand CheckServerCommand { get; }
    public AsyncCommand TrustAndContinueCommand { get; }
    public AsyncCommand InstallCommand { get; }
    public AsyncCommand SafeDisableCommand { get; }
    public AsyncCommand FullRollbackCommand { get; }
    public ICommand CancelCommand { get; }
    public ICommand RejectHostKeyCommand { get; }
    public ICommand AddComposeFileCommand { get; }
    public ICommand RemoveComposeFileCommand { get; }
    public ICommand SwitchLanguageCommand { get; }

    public WizardStep Step { get => step; private set { if (Set(ref step, value)) NotifyState(); } }
    public RequestedOperation RequestedOperation { get => requestedOperation; private set => Set(ref requestedOperation, value); }
    public AuthenticationMode AuthenticationMode
    {
        get => authenticationMode;
        set
        {
            if (Set(ref authenticationMode, value))
            {
                ClearCredentialFields();
                OnPropertyChanged(nameof(IsPasswordAuthentication));
                OnPropertyChanged(nameof(IsPrivateKeyAuthentication));
            }
        }
    }

    public string Host { get => host; set => Set(ref host, value ?? ""); }
    public string Port { get => port; set => Set(ref port, value ?? ""); }
    public string Username { get => username; set => Set(ref username, value ?? ""); }
    public string DeploymentDirectory { get => deploymentDirectory; set => Set(ref deploymentDirectory, value ?? ""); }
    public string ServiceName { get => serviceName; set => Set(ref serviceName, value ?? ""); }
    public string Password { get => password; set => Set(ref password, value ?? ""); }
    public string PrivateKeyPath { get => privateKeyPath; set => Set(ref privateKeyPath, value ?? ""); }
    public string PrivateKeyPassphrase { get => privateKeyPassphrase; set => Set(ref privateKeyPassphrase, value ?? ""); }
    public string StatusCode { get => statusCode; private set => Set(ref statusCode, value); }
    public string VisibleLog { get => visibleLog; private set => Set(ref visibleLog, value); }
    public string Language { get => language; private set => Set(ref language, value); }
    public bool ConfirmPlannedRestarts { get => confirmPlannedRestarts; set { if (Set(ref confirmPlannedRestarts, value)) RaiseCommands(); } }
    public bool PreviewSupported { get => previewSupported; private set { if (Set(ref previewSupported, value)) RaiseCommands(); } }
    public bool IsBusy
    {
        get => isBusy;
        private set
        {
            if (Set(ref isBusy, value))
            {
                OnPropertyChanged(nameof(IsConnectionEditable));
                RaiseCommands();
            }
        }
    }
    public bool IsShuttingDown => shutdownRequested;
    public bool IsConnectionEditable => Step == WizardStep.Connection && !IsBusy && !IsShuttingDown;
    public bool IsPasswordAuthentication => AuthenticationMode == AuthenticationMode.Password;
    public bool IsPrivateKeyAuthentication => AuthenticationMode == AuthenticationMode.PrivateKey;
    public bool IsConnectionStep => Step == WizardStep.Connection;
    public bool IsHostKeyTrustStep => Step == WizardStep.HostKeyTrust;
    public bool IsReviewStep => Step == WizardStep.Review;
    public bool IsProgressStep => Step == WizardStep.Progress;
    public bool IsResultStep => Step == WizardStep.Result;
    public bool ShowSafeDisable => hasVerifiedContext && Step is WizardStep.Review or WizardStep.Result;
    public bool ShowFullRollback => ShowSafeDisable && recoveryRecord is not null &&
                                    (Step == WizardStep.Result || RecoveryMatchesObservedInput());
    public string HostKeyAlgorithm => observation?.Algorithm ?? "";
    public string HostKeySha256 => observation?.Sha256 ?? "";
    public IReadOnlyList<PlannedAction> PlannedActions { get => plannedActions; private set => Set(ref plannedActions, value); }
    public int CancellationRequests { get; private set; }

    public async ValueTask DisposeAsync() => await ShutdownAsync();

    public Task ShutdownAsync()
    {
        Task[] active;
        Task completion;
        lock (lifecycleLock)
        {
            if (shutdownTask is not null)
            {
                return shutdownTask;
            }

            shutdownRequested = true;
            active = activeTasks.ToArray();
            shutdownTask = completion = ShutdownCoreAsync(active);
        }

        OnPropertyChanged(nameof(IsShuttingDown));
        OnPropertyChanged(nameof(IsConnectionEditable));
        RaiseCommands();
        Cancel();
        return completion;
    }

    private async Task ShutdownCoreAsync(IReadOnlyList<Task> active)
    {
        try
        {
            await Task.WhenAll(active);
        }
        catch (Exception error) when (!WizardExceptionPolicy.IsFatal(error))
        {
            // Command handlers translate failures to fixed result codes before completing.
        }
        finally
        {
            try
            {
                operationCancellation?.Dispose();
            }
            catch (Exception error) when (!WizardExceptionPolicy.IsFatal(error))
            {
                MarkSessionCleanupFailure();
            }
            finally
            {
                operationCancellation = null;
                try
                {
                    if (!await DisposeSessionAsync())
                    {
                        MarkSessionCleanupFailure();
                    }
                }
                finally
                {
                    ClearCredentialFields();
                }
            }
        }
    }

    private Task TrackAsync(Func<Task> action)
    {
        Task active;
        lock (lifecycleLock)
        {
            if (shutdownRequested)
            {
                return Task.CompletedTask;
            }

            try
            {
                active = action();
            }
            catch (Exception error)
            {
                active = Task.FromException(error);
            }

            activeTasks.Add(active);
        }

        return AwaitTrackedAsync(active);
    }

    private async Task AwaitTrackedAsync(Task active)
    {
        try
        {
            await active;
        }
        finally
        {
            lock (lifecycleLock)
            {
                activeTasks.Remove(active);
            }
        }
    }

    private async Task<bool> DisposeSessionAsync()
    {
        var owned = session;
        session = null;
        var succeeded = true;
        try
        {
            if (owned is not null)
            {
                await owned.DisposeAsync();
            }
        }
        catch (Exception error) when (!WizardExceptionPolicy.IsFatal(error))
        {
            succeeded = false;
        }
        finally
        {
            OnPropertyChanged(nameof(ShowSafeDisable));
            OnPropertyChanged(nameof(ShowFullRollback));
            RaiseCommands();
        }

        return succeeded;
    }

    private void LoadRecovery()
    {
        try
        {
            var loaded = workflow.LoadRecovery();
            if (!loaded.Success || loaded.Record is null)
            {
                return;
            }

            recoveryRecord = loaded.Record;
            Host = loaded.Record.Host;
            Port = loaded.Record.Port.ToString(CultureInfo.InvariantCulture);
            Username = loaded.Record.Username;
            DeploymentDirectory = loaded.Record.DeploymentDirectory;
            ServiceName = loaded.Record.ServiceName;
            ComposeFiles.Clear();
            foreach (var file in loaded.Record.ComposeFiles)
            {
                ComposeFiles.Add(file);
            }
        }
        catch
        {
            recoveryRecord = null;
        }
    }

    private async Task CheckServerAsync()
    {
        if (!TryBuildInput(out var input))
        {
            return;
        }

        IsBusy = true;
        try
        {
            var observed = await workflow.ObserveHostKeyAsync(input, CancellationToken.None);
            if (!TryBuildInput(out var currentInput) || !input.StructurallyEquals(currentInput))
            {
                Terminal("connection-changed");
                return;
            }

            if (RecoveryMatches(input) && !string.Equals(recoveryRecord!.HostKeySha256, observed.Sha256, StringComparison.Ordinal))
            {
                Terminal("host-key-mismatch");
                return;
            }

            observedInput = input;
            observation = observed;
            OnPropertyChanged(nameof(HostKeyAlgorithm));
            OnPropertyChanged(nameof(HostKeySha256));
            StatusCode = "host-key-observed";
            Step = WizardStep.HostKeyTrust;
        }
        catch
        {
            Terminal("host-key-observation-failed");
        }
        finally
        {
            IsBusy = false;
        }
    }

    private async Task TrustAndContinueAsync()
    {
        if (observedInput is null || observation is null)
        {
            Terminal("host-key-observation-missing");
            return;
        }

        if (!TryBuildInput(out var currentInput) || !observedInput.StructurallyEquals(currentInput))
        {
            Terminal("connection-changed");
            return;
        }

        IsBusy = true;
        var source = new OneShotCredentialSource(AuthenticationMode, Password, PrivateKeyPath, PrivateKeyPassphrase);
        try
        {
            session = await workflow.CreateConfirmedAsync(observedInput, observation, source, CancellationToken.None);
            hasVerifiedContext = true;
            OnPropertyChanged(nameof(ShowSafeDisable));
            OnPropertyChanged(nameof(ShowFullRollback));
            var preview = await session.PreviewAsync(CancellationToken.None);
            PreviewSupported = preview.Supported;
            PlannedActions = preview.Actions;
            StatusCode = preview.ReasonCode;
            RefreshVisibleLog();
            Step = WizardStep.Review;
        }
        catch
        {
            var disposed = await DisposeSessionAsync();
            Terminal(disposed
                ? source.WasRead ? "preview-failed" : "host-key-confirmation-failed"
                : "session-cleanup-failed");
        }
        finally
        {
            source.Clear();
            ClearCredentialFields();
            IsBusy = false;
        }
    }

    private async Task RunOperationAsync(RequestedOperation operation)
    {
        if (session is null)
        {
            Terminal("verified-session-required");
            return;
        }

        RequestedOperation = operation;
        Step = WizardStep.Progress;
        IsBusy = true;
        using var cancellation = new CancellationTokenSource();
        operationCancellation = cancellation;
        RaiseCommands();
        try
        {
            InstallationResult result = operation switch
            {
                RequestedOperation.InstallOrUpdate => await session.InstallAsync(cancellation.Token),
                RequestedOperation.SafeDisable => await session.DisableAsync(cancellation.Token),
                RequestedOperation.FullRollback when RecoveryMatchesObservedInput() =>
                    await session.RestoreAsync(recoveryRecord!.BackupPath, cancellation.Token),
                _ => new InstallationResult(InstallerState.Failed, "recovery-record-required", null),
            };
            StatusCode = result.ReasonCode;
            RefreshVisibleLog();
            Step = WizardStep.Result;
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested)
        {
            StatusCode = "cancelled";
            RefreshVisibleLog();
            Step = WizardStep.Result;
        }
        catch
        {
            StatusCode = operation switch
            {
                RequestedOperation.InstallOrUpdate => "install-failed",
                RequestedOperation.SafeDisable => "disable-failed",
                RequestedOperation.FullRollback => "restore-failed",
                _ => "operation-failed",
            };
            RefreshVisibleLog();
            Step = WizardStep.Result;
        }
        finally
        {
            operationCancellation = null;
            try
            {
                try
                {
                    ClearCredentialFields();
                    if (operation == RequestedOperation.InstallOrUpdate)
                    {
                        ReloadRecovery();
                    }
                }
                finally
                {
                    if (!await DisposeSessionAsync())
                    {
                        MarkSessionCleanupFailure();
                    }
                }
            }
            finally
            {
                IsBusy = false;
            }
        }
    }

    private void MarkSessionCleanupFailure()
    {
        StatusCode = "session-cleanup-failed";
        Step = WizardStep.Result;
    }

    private void ReloadRecovery()
    {
        recoveryRecord = null;
        try
        {
            var loaded = workflow.LoadRecovery();
            if (loaded.Success && loaded.Record is not null)
            {
                recoveryRecord = loaded.Record;
            }
        }
        catch
        {
            recoveryRecord = null;
        }

        OnPropertyChanged(nameof(ShowFullRollback));
        RaiseCommands();
    }

    private Task StartOperationOrReauthenticateAsync(RequestedOperation operation)
    {
        if (session is not null)
        {
            return RunOperationAsync(operation);
        }

        if (Step == WizardStep.Result && hasVerifiedContext &&
            (operation != RequestedOperation.FullRollback || recoveryRecord is not null))
        {
            RequestedOperation = operation;
            observation = null;
            observedInput = null;
            PreviewSupported = false;
            ConfirmPlannedRestarts = false;
            PlannedActions = [];
            StatusCode = "reauthentication-required";
            Step = WizardStep.Connection;
        }

        return Task.CompletedTask;
    }

    private void Cancel()
    {
        var cancellation = operationCancellation;
        if (cancellation is null || cancellation.IsCancellationRequested)
        {
            return;
        }

        CancellationRequests++;
        OnPropertyChanged(nameof(CancellationRequests));
        cancellation.Cancel();
        RaiseCommands();
    }

    private void RejectHostKey()
    {
        observation = null;
        observedInput = null;
        Terminal("host-key-rejected");
    }

    private bool TryBuildInput(out ConnectionInput input)
    {
        input = null!;
        try
        {
            if (!int.TryParse(Port, NumberStyles.None, CultureInfo.InvariantCulture, out var parsedPort))
            {
                throw new ArgumentException();
            }

            if (AuthenticationMode == AuthenticationMode.Password)
            {
                if (string.IsNullOrWhiteSpace(Password) || PrivateKeyPath.Length != 0 || PrivateKeyPassphrase.Length != 0)
                {
                    throw new ArgumentException();
                }
            }
            else if (AuthenticationMode == AuthenticationMode.PrivateKey)
            {
                if (string.IsNullOrWhiteSpace(PrivateKeyPath) || Password.Length != 0)
                {
                    throw new ArgumentException();
                }
            }
            else
            {
                throw new ArgumentException();
            }

            input = new ConnectionInput(
                Host, parsedPort, Username, AuthenticationMode, DeploymentDirectory,
                ComposeFiles.ToArray(), ServiceName);
            StatusCode = "connection-valid";
            return true;
        }
        catch
        {
            StatusCode = "connection-invalid";
            return false;
        }
    }

    private void RefreshVisibleLog()
    {
        if (session is null)
        {
            VisibleLog = "";
            return;
        }

        var lines = new List<string>();
        foreach (var entry in session.Events.OrderBy(value => value.Sequence))
        {
            if (!RenderedEventNames.Contains(entry.Name))
            {
                continue;
            }

            var line = new StringBuilder()
                .Append(entry.Sequence.ToString(CultureInfo.InvariantCulture))
                .Append(' ')
                .Append(entry.Name);
            foreach (var field in entry.Fields.Where(value => RenderedFieldNames.Contains(value.Key)).OrderBy(value => value.Key, StringComparer.Ordinal))
            {
                line.Append(' ').Append(field.Key).Append('=').Append(field.Value);
            }

            lines.Add(line.ToString());
        }

        VisibleLog = string.Join("\r\n", lines);
    }

    private void SwitchLanguage(object? value)
    {
        if (value is not string culture || !IsSupportedLanguage(culture) || string.Equals(Language, culture, StringComparison.Ordinal))
        {
            return;
        }

        try
        {
            languageSwitcher?.Invoke(culture);
            Language = culture;
        }
        catch
        {
            StatusCode = "language-switch-failed";
        }
    }

    private void Terminal(string reasonCode)
    {
        StatusCode = reasonCode;
        ClearCredentialFields();
        Step = WizardStep.Result;
    }

    private void ClearCredentialFields()
    {
        Password = "";
        PrivateKeyPath = "";
        PrivateKeyPassphrase = "";
    }

    private bool RecoveryMatchesObservedInput() => observedInput is not null && RecoveryMatches(observedInput);

    private bool RecoveryMatches(ConnectionInput input)
        => recoveryRecord is not null &&
           string.Equals(recoveryRecord.Host, input.Host, StringComparison.Ordinal) &&
           recoveryRecord.Port == input.Port &&
           string.Equals(recoveryRecord.Username, input.Username, StringComparison.Ordinal) &&
           string.Equals(recoveryRecord.DeploymentDirectory, input.DeploymentDirectory, StringComparison.Ordinal) &&
           recoveryRecord.ComposeFiles.SequenceEqual(input.ComposeFiles, StringComparer.Ordinal) &&
           string.Equals(recoveryRecord.ServiceName, input.ServiceName, StringComparison.Ordinal);

    private void ComposeFilesChanged(object? sender, NotifyCollectionChangedEventArgs args)
    {
        OnPropertyChanged(nameof(ComposeFiles));
        RaiseCommands();
    }

    private void NotifyState()
    {
        OnPropertyChanged(nameof(IsConnectionStep));
        OnPropertyChanged(nameof(IsHostKeyTrustStep));
        OnPropertyChanged(nameof(IsReviewStep));
        OnPropertyChanged(nameof(IsProgressStep));
        OnPropertyChanged(nameof(IsResultStep));
        OnPropertyChanged(nameof(IsConnectionEditable));
        OnPropertyChanged(nameof(ShowSafeDisable));
        OnPropertyChanged(nameof(ShowFullRollback));
        RaiseCommands();
    }

    private void RaiseCommands()
    {
        CheckServerCommand?.RaiseCanExecuteChanged();
        TrustAndContinueCommand?.RaiseCanExecuteChanged();
        InstallCommand?.RaiseCanExecuteChanged();
        SafeDisableCommand?.RaiseCanExecuteChanged();
        FullRollbackCommand?.RaiseCanExecuteChanged();
        (CancelCommand as RelayCommand)?.RaiseCanExecuteChanged();
        (RejectHostKeyCommand as RelayCommand)?.RaiseCanExecuteChanged();
        (AddComposeFileCommand as RelayCommand)?.RaiseCanExecuteChanged();
        (RemoveComposeFileCommand as RelayCommand)?.RaiseCanExecuteChanged();
    }

    private bool Set<T>(ref T field, T value, [CallerMemberName] string? propertyName = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value))
        {
            return false;
        }

        field = value;
        OnPropertyChanged(propertyName);
        return true;
    }

    private void OnPropertyChanged([CallerMemberName] string? propertyName = null)
        => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));

    private static bool IsSupportedLanguage(string culture) => culture is "en-US" or "ru-RU";

    private sealed class OneShotCredentialSource(
        AuthenticationMode mode,
        string password,
        string privateKeyPath,
        string privateKeyPassphrase) : ICredentialSource
    {
        private int read;
        private string? passwordValue = password;
        private string? pathValue = privateKeyPath;
        private string? passphraseValue = privateKeyPassphrase;

        public bool WasRead => Volatile.Read(ref read) != 0;

        public CredentialInput Read()
        {
            if (Interlocked.Exchange(ref read, 1) != 0)
            {
                throw new InvalidOperationException("Credentials are one-shot.");
            }

            var input = mode == AuthenticationMode.Password
                ? CredentialInput.ForPassword(passwordValue ?? "")
                : CredentialInput.ForPrivateKey(pathValue ?? "", string.IsNullOrEmpty(passphraseValue) ? null : passphraseValue);
            Clear();
            return input;
        }

        public void Clear()
        {
            passwordValue = null;
            pathValue = null;
            passphraseValue = null;
        }
    }

    private sealed class RelayCommand(Action<object?> execute, Func<object?, bool>? canExecute = null) : ICommand
    {
        public event EventHandler? CanExecuteChanged;
        public bool CanExecute(object? parameter) => canExecute?.Invoke(parameter) ?? true;
        public void Execute(object? parameter) => execute(parameter);
        public void RaiseCanExecuteChanged() => CanExecuteChanged?.Invoke(this, EventArgs.Empty);
    }
}

internal static class WizardExceptionPolicy
{
    public static bool IsFatal(Exception error)
    {
        if (error is OutOfMemoryException or StackOverflowException or AccessViolationException or AppDomainUnloadedException)
        {
            return true;
        }

        if (error is AggregateException aggregate)
        {
            return aggregate.InnerExceptions.Any(IsFatal);
        }

        return error.InnerException is not null && IsFatal(error.InnerException);
    }
}
