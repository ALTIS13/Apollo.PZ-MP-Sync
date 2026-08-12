using System.Collections.ObjectModel;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;
using Apollo.NativeAssist.Installer.Application;
using Apollo.NativeAssist.Installer.Core;
using Apollo.NativeAssist.Installer.Infrastructure.Ssh;
using Apollo.NativeAssist.Installer.ViewModels;
using Xunit;

namespace Apollo.NativeAssist.Installer.Ui.Tests;

public sealed class MainWindowViewModelTests
{
    private static readonly SshHostKeyObservation Observation = new(
        "ssh-ed25519", Convert.ToBase64String(new byte[32]).TrimEnd('='));

    [Theory]
    [MemberData(nameof(InvalidConnections))]
    public async Task Invalid_connection_fields_never_start_host_key_observation(Action<MainWindowViewModel> invalidate)
    {
        var workflow = new FakeWorkflow();
        var vm = Create(workflow);
        invalidate(vm);

        await vm.CheckServerCommand.ExecuteAsync(null);

        Assert.Equal(WizardStep.Connection, vm.Step);
        Assert.Equal(0, workflow.ObserveCalls);
        Assert.False(string.IsNullOrWhiteSpace(vm.StatusCode));
    }

    public static TheoryData<Action<MainWindowViewModel>> InvalidConnections => new()
    {
        vm => vm.Host = "",
        vm => vm.Port = "0",
        vm => vm.Port = "65536",
        vm => vm.Port = "not-a-port",
        vm => vm.Username = "bad user",
        vm => vm.DeploymentDirectory = "relative/path",
        vm => vm.ServiceName = "server; reboot",
        vm => vm.ComposeFiles.Clear(),
        vm => { vm.ComposeFiles.Clear(); for (var i = 0; i < 9; i++) vm.ComposeFiles.Add($"compose-{i}.yaml"); },
        vm => vm.ComposeFiles[0] = "",
        vm => { vm.ComposeFiles.Add("compose.yaml"); },
        vm => vm.ComposeFiles[0] = "../compose.yaml",
        vm => vm.Password = "",
        vm => { vm.AuthenticationMode = AuthenticationMode.PrivateKey; vm.PrivateKeyPath = ""; },
    };

    [Fact]
    public void Compose_rows_are_bounded_from_one_through_eight()
    {
        var vm = Create();
        for (var i = 1; i < 8; i++)
        {
            Assert.True(vm.AddComposeFileCommand.CanExecute(null));
            vm.AddComposeFileCommand.Execute(null);
        }

        Assert.Equal(8, vm.ComposeFiles.Count);
        Assert.False(vm.AddComposeFileCommand.CanExecute(null));
        for (var i = 8; i > 1; i--)
        {
            Assert.True(vm.RemoveComposeFileCommand.CanExecute(null));
            vm.RemoveComposeFileCommand.Execute(null);
        }

        Assert.Single(vm.ComposeFiles);
        Assert.False(vm.RemoveComposeFileCommand.CanExecute(null));
    }

    [Fact]
    public async Task Credentials_are_read_only_after_explicit_host_key_confirmation_and_are_then_cleared()
    {
        var workflow = new FakeWorkflow();
        var vm = Create(workflow);
        vm.Password = "sentinel-secret";

        await vm.CheckServerCommand.ExecuteAsync(null);

        Assert.Equal(WizardStep.HostKeyTrust, vm.Step);
        Assert.Equal(0, workflow.CredentialReads);
        await vm.TrustAndContinueCommand.ExecuteAsync(null);
        Assert.Equal(WizardStep.Review, vm.Step);
        Assert.Equal(1, workflow.CredentialReads);
        Assert.Equal(string.Empty, vm.Password);
        Assert.Equal(string.Empty, vm.PrivateKeyPath);
        Assert.Equal(string.Empty, vm.PrivateKeyPassphrase);
        Assert.DoesNotContain("sentinel-secret", vm.VisibleLog, StringComparison.Ordinal);
    }

    [Fact]
    public async Task Private_key_mode_supplies_only_key_path_and_optional_passphrase_after_trust()
    {
        var workflow = new FakeWorkflow();
        var vm = Create(workflow);
        vm.AuthenticationMode = AuthenticationMode.PrivateKey;
        vm.PrivateKeyPath = "C:\\keys\\pz.pem";
        vm.PrivateKeyPassphrase = "sentinel-passphrase";

        await vm.CheckServerCommand.ExecuteAsync(null);
        Assert.Equal(0, workflow.CredentialReads);
        await vm.TrustAndContinueCommand.ExecuteAsync(null);

        Assert.Equal(AuthenticationMode.PrivateKey, workflow.CredentialMode);
        Assert.Equal("C:\\keys\\pz.pem", workflow.PrivateKeyPathRead);
        Assert.Equal("sentinel-passphrase", workflow.PassphraseRead);
        Assert.Null(workflow.PasswordRead);
        Assert.Equal(string.Empty, vm.PrivateKeyPath);
        Assert.Equal(string.Empty, vm.PrivateKeyPassphrase);
    }

    [Fact]
    public async Task Rejecting_host_key_is_terminal_and_never_reads_credentials()
    {
        var workflow = new FakeWorkflow();
        var vm = Create(workflow);
        vm.Password = "sentinel-secret";
        await vm.CheckServerCommand.ExecuteAsync(null);

        vm.RejectHostKeyCommand.Execute(null);

        Assert.Equal(WizardStep.Result, vm.Step);
        Assert.Equal("host-key-rejected", vm.StatusCode);
        Assert.Equal(0, workflow.CredentialReads);
        AssertCredentialsCleared(vm);
    }

    [Fact]
    public async Task Saved_host_key_mismatch_stops_before_confirmation_or_credentials()
    {
        var record = Recovery() with { HostKeySha256 = Convert.ToBase64String(Enumerable.Repeat((byte)1, 32).ToArray()).TrimEnd('=') };
        var workflow = new FakeWorkflow { RecoveryResult = new RecoveryLoadResult(record, "recovery-record-loaded") };
        var vm = Create(workflow);
        vm.Password = "sentinel-secret";

        await vm.CheckServerCommand.ExecuteAsync(null);

        Assert.Equal(WizardStep.Result, vm.Step);
        Assert.Equal("host-key-mismatch", vm.StatusCode);
        Assert.Equal(0, workflow.CreateCalls);
        Assert.Equal(0, workflow.CredentialReads);
        AssertCredentialsCleared(vm);
    }

    [Fact]
    public async Task Authenticated_host_key_mismatch_is_terminal_and_clears_credentials()
    {
        var workflow = new FakeWorkflow { CreateError = new InvalidOperationException("changed host key") };
        var vm = Create(workflow);
        vm.Password = "sentinel-secret";
        await vm.CheckServerCommand.ExecuteAsync(null);

        await vm.TrustAndContinueCommand.ExecuteAsync(null);

        Assert.Equal(WizardStep.Result, vm.Step);
        Assert.Equal("host-key-confirmation-failed", vm.StatusCode);
        AssertCredentialsCleared(vm);
        Assert.DoesNotContain("changed host key", vm.VisibleLog, StringComparison.Ordinal);
    }

    [Fact]
    public async Task Connection_is_frozen_during_host_key_probe_and_edit_drift_fails_before_credentials()
    {
        var observed = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var workflow = new FakeWorkflow
        {
            Observe = async (_, _) =>
            {
                observed.TrySetResult();
                await release.Task;
                return Observation;
            },
        };
        var vm = Create(workflow);

        var checking = vm.CheckServerCommand.ExecuteAsync(null);
        await observed.Task.WaitAsync(TimeSpan.FromSeconds(2), TestContext.Current.CancellationToken);
        Assert.False(vm.IsConnectionEditable);
        Assert.False(vm.CheckServerCommand.CanExecute(null));
        Assert.False(vm.AddComposeFileCommand.CanExecute(null));
        Assert.False(vm.RemoveComposeFileCommand.CanExecute(null));
        vm.Host = "edited-during-probe.example.test";
        release.TrySetResult();
        await checking;

        Assert.Equal(WizardStep.Result, vm.Step);
        Assert.Equal("connection-changed", vm.StatusCode);
        Assert.Equal(0, workflow.CreateCalls);
        Assert.Equal(0, workflow.CredentialReads);
    }

    [Fact]
    public async Task Programmatic_auth_or_connection_drift_after_observation_fails_before_credential_read()
    {
        var workflow = new FakeWorkflow();
        var vm = Create(workflow);
        await vm.CheckServerCommand.ExecuteAsync(null);
        vm.AuthenticationMode = AuthenticationMode.PrivateKey;
        vm.PrivateKeyPath = "C:\\keys\\changed.pem";
        vm.DeploymentDirectory = "/srv/changed";

        await vm.TrustAndContinueCommand.ExecuteAsync(null);

        Assert.Equal(WizardStep.Result, vm.Step);
        Assert.Equal("connection-changed", vm.StatusCode);
        Assert.Equal(0, workflow.CreateCalls);
        Assert.Equal(0, workflow.CredentialReads);
        AssertCredentialsCleared(vm);
    }

    [Fact]
    public async Task Install_requires_supported_preview_and_explicit_confirmation()
    {
        var vm = await CreateReviewAsync(new FakeWorkflow());

        Assert.True(vm.PreviewSupported);
        Assert.False(vm.InstallCommand.CanExecute(null));
        vm.ConfirmPlannedRestarts = true;
        Assert.True(vm.InstallCommand.CanExecute(null));
    }

    [Fact]
    public async Task Unsupported_preview_never_enables_install()
    {
        var workflow = new FakeWorkflow();
        workflow.Session.Preview = new InstallationPreview(false, "runtime-build-id-mismatch", Runtime(), []);
        var vm = await CreateReviewAsync(workflow);

        vm.ConfirmPlannedRestarts = true;

        Assert.Equal(WizardStep.Review, vm.Step);
        Assert.False(vm.PreviewSupported);
        Assert.Equal("runtime-build-id-mismatch", vm.StatusCode);
        Assert.False(vm.InstallCommand.CanExecute(null));
        Assert.Equal(0, workflow.Session.InstallCalls);
    }

    [Fact]
    public async Task Cancel_before_backup_is_requested_once_and_finishes_cancelled_without_restore()
    {
        var workflow = new FakeWorkflow();
        workflow.Session.Install = async token =>
        {
            await Task.Delay(Timeout.InfiniteTimeSpan, token);
            return Result(InstallerState.Ready, "ready", "/unexpected");
        };
        var vm = await CreateReviewAsync(workflow);
        vm.ConfirmPlannedRestarts = true;

        var running = vm.InstallCommand.ExecuteAsync(null);
        await workflow.Session.InstallStarted.Task.WaitAsync(TimeSpan.FromSeconds(2), TestContext.Current.CancellationToken);
        vm.CancelCommand.Execute(null);
        vm.CancelCommand.Execute(null);
        await running;

        Assert.Equal(1, vm.CancellationRequests);
        Assert.Equal(WizardStep.Result, vm.Step);
        Assert.Equal("cancelled", vm.StatusCode);
        Assert.Equal(0, workflow.Session.RestoreCalls);
    }

    [Fact]
    public async Task Cancel_after_backup_relies_on_session_for_exactly_one_rollback()
    {
        var workflow = new FakeWorkflow();
        workflow.Session.Install = async token =>
        {
            workflow.Session.BackupReached.TrySetResult();
            try { await Task.Delay(Timeout.InfiniteTimeSpan, token); }
            catch (OperationCanceledException) { workflow.Session.RollbackAttempts++; }
            return Result(InstallerState.Cancelled, "cancelled", "/srv/pz/.apollo-backups/one");
        };
        var vm = await CreateReviewAsync(workflow);
        vm.ConfirmPlannedRestarts = true;

        var running = vm.InstallCommand.ExecuteAsync(null);
        await workflow.Session.BackupReached.Task.WaitAsync(TimeSpan.FromSeconds(2), TestContext.Current.CancellationToken);
        vm.CancelCommand.Execute(null);
        vm.CancelCommand.Execute(null);
        await running;

        Assert.Equal(1, vm.CancellationRequests);
        Assert.Equal(1, workflow.Session.RollbackAttempts);
        Assert.Equal(0, workflow.Session.RestoreCalls);
        Assert.Equal("cancelled", vm.StatusCode);
    }

    [Fact]
    public async Task Closing_during_post_backup_progress_waits_for_rollback_before_session_disposal_and_actual_close()
    {
        await RunOnStaAsync(async () =>
        {
            var application = System.Windows.Application.Current ?? new System.Windows.Application();
            application.Resources.MergedDictionaries.Clear();
            application.Resources.MergedDictionaries.Add(LoadDictionary("en-US"));
            var workflow = new FakeWorkflow();
            var order = new List<string>();
            var cancellationObserved = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            var rollbackRelease = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            workflow.Session.Install = async token =>
            {
                order.Add("backup");
                workflow.Session.BackupReached.TrySetResult();
                try { await Task.Delay(Timeout.InfiniteTimeSpan, token); }
                catch (OperationCanceledException)
                {
                    order.Add("rollback-start");
                    cancellationObserved.TrySetResult();
                    await rollbackRelease.Task;
                    order.Add("rollback-complete");
                }

                return Result(InstallerState.Cancelled, "cancelled", "/srv/pz/.apollo-backups/one");
            };
            workflow.Session.Disposing = () => order.Add("dispose");
            var vm = await CreateReviewAsync(workflow);
            vm.ConfirmPlannedRestarts = true;
            var window = new MainWindow(vm) { ShowActivated = false, ShowInTaskbar = false, Opacity = 0 };
            var closed = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            window.Closed += (_, _) => { order.Add("closed"); closed.TrySetResult(); };
            window.Show();

            _ = vm.InstallCommand.ExecuteAsync(null);
            await workflow.Session.BackupReached.Task;
            window.Close();
            await cancellationObserved.Task;

            Assert.False(closed.Task.IsCompleted);
            Assert.Equal(0, workflow.Session.DisposeCalls);
            Assert.Equal(1, vm.CancellationRequests);
            rollbackRelease.TrySetResult();
            await closed.Task;
            Assert.Equal(new[] { "backup", "rollback-start", "rollback-complete", "dispose", "closed" }, order);
            Assert.Equal(1, workflow.Session.DisposeCalls);
        });
    }

    [Theory]
    [InlineData("success")]
    [InlineData("cancel")]
    [InlineData("failure")]
    public async Task Throwing_session_disposal_is_bounded_on_every_install_terminal_path(string outcome)
    {
        const string disposalDetail = "dispose leaked-sentinel C:\\keys\\id_ed25519 /srv/private";
        var recovery = Recovery() with { BackupPath = "/srv/pz/.apollo-backups/current" };
        var workflow = new FakeWorkflow();
        workflow.Session.DisposeError = new InvalidOperationException(disposalDetail);
        workflow.Session.Install = outcome switch
        {
            "success" => _ =>
            {
                workflow.RecoveryResult = new RecoveryLoadResult(recovery, "recovery-record-loaded");
                return Task.FromResult(Result(InstallerState.Ready, "ready", recovery.BackupPath));
            },
            "cancel" => async token =>
            {
                try
                {
                    await Task.Delay(Timeout.InfiniteTimeSpan, token);
                }
                catch (OperationCanceledException)
                {
                    workflow.RecoveryResult = new RecoveryLoadResult(recovery, "recovery-record-loaded");
                    throw;
                }

                throw new InvalidOperationException("unreachable");
            },
            "failure" => _ =>
            {
                workflow.RecoveryResult = new RecoveryLoadResult(recovery, "recovery-record-loaded");
                throw new InvalidOperationException("remote leaked-sentinel /srv/private");
            },
            _ => throw new ArgumentOutOfRangeException(nameof(outcome)),
        };
        var vm = await CreateReviewAsync(workflow);
        vm.Password = "late-password";
        vm.PrivateKeyPath = "C:\\keys\\late";
        vm.PrivateKeyPassphrase = "late-passphrase";
        vm.ConfirmPlannedRestarts = true;

        var running = vm.InstallCommand.ExecuteAsync(null);
        if (outcome == "cancel")
        {
            await workflow.Session.InstallStarted.Task.WaitAsync(TimeSpan.FromSeconds(2), TestContext.Current.CancellationToken);
            vm.CancelCommand.Execute(null);
        }

        var commandError = await Record.ExceptionAsync(() => running);

        Assert.Null(commandError);
        Assert.Equal(WizardStep.Result, vm.Step);
        Assert.Equal("session-cleanup-failed", vm.StatusCode);
        Assert.False(vm.IsBusy);
        Assert.False(vm.InstallCommand.CanExecute(null));
        Assert.False(vm.CancelCommand.CanExecute(null));
        Assert.True(vm.SafeDisableCommand.CanExecute(null));
        Assert.True(vm.ShowFullRollback);
        Assert.Equal(1, workflow.Session.DisposeCalls);
        AssertCredentialsCleared(vm);
        Assert.DoesNotContain(disposalDetail, vm.StatusCode, StringComparison.Ordinal);
        Assert.DoesNotContain("leaked-sentinel", vm.VisibleLog, StringComparison.Ordinal);
        Assert.DoesNotContain("id_ed25519", vm.VisibleLog, StringComparison.Ordinal);
        Assert.DoesNotContain("/srv/private", vm.VisibleLog, StringComparison.Ordinal);
        await vm.ShutdownAsync();
        await vm.ShutdownAsync();
        Assert.Equal(1, workflow.Session.DisposeCalls);
    }

    [Fact]
    public async Task Shutdown_during_active_install_waits_then_contains_one_disposal_failure()
    {
        const string disposalDetail = "dispose leaked-sentinel C:\\keys\\id_ed25519 /srv/private";
        var rollbackStarted = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var rollbackRelease = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var workflow = new FakeWorkflow();
        workflow.Session.DisposeError = new InvalidOperationException(disposalDetail);
        workflow.Session.Install = async token =>
        {
            workflow.Session.BackupReached.TrySetResult();
            try
            {
                await Task.Delay(Timeout.InfiniteTimeSpan, token);
            }
            catch (OperationCanceledException)
            {
                rollbackStarted.TrySetResult();
                await rollbackRelease.Task;
            }

            return Result(InstallerState.Cancelled, "cancelled", Recovery().BackupPath);
        };
        var vm = await CreateReviewAsync(workflow);
        vm.Password = "late-password";
        vm.ConfirmPlannedRestarts = true;
        var running = vm.InstallCommand.ExecuteAsync(null);
        await workflow.Session.BackupReached.Task.WaitAsync(TimeSpan.FromSeconds(2), TestContext.Current.CancellationToken);

        var firstShutdown = vm.ShutdownAsync();
        var secondShutdown = vm.ShutdownAsync();
        await rollbackStarted.Task.WaitAsync(TimeSpan.FromSeconds(2), TestContext.Current.CancellationToken);

        Assert.Same(firstShutdown, secondShutdown);
        Assert.False(firstShutdown.IsCompleted);
        Assert.Equal(0, workflow.Session.DisposeCalls);
        Assert.Equal(1, vm.CancellationRequests);
        rollbackRelease.TrySetResult();
        var commandError = await Record.ExceptionAsync(() => running);
        var shutdownError = await Record.ExceptionAsync(() => firstShutdown);

        Assert.Null(commandError);
        Assert.Null(shutdownError);
        Assert.Equal(1, workflow.Session.DisposeCalls);
        Assert.Equal("session-cleanup-failed", vm.StatusCode);
        Assert.False(vm.IsBusy);
        AssertCredentialsCleared(vm);
        Assert.DoesNotContain("leaked-sentinel", vm.VisibleLog, StringComparison.Ordinal);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task Idle_review_or_result_window_close_contains_disposal_failure_and_never_latches_close(bool startAtResult)
    {
        await RunOnStaAsync(async () =>
        {
            const string disposalDetail = "dispose leaked-sentinel C:\\keys\\id_ed25519 /srv/private";
            var application = System.Windows.Application.Current ?? new System.Windows.Application();
            application.Resources.MergedDictionaries.Clear();
            application.Resources.MergedDictionaries.Add(LoadDictionary("en-US"));
            var workflow = new FakeWorkflow();
            workflow.Session.DisposeError = new InvalidOperationException(disposalDetail);
            var vm = await CreateReviewAsync(workflow);
            if (startAtResult)
            {
                vm.ConfirmPlannedRestarts = true;
                var operationError = await Record.ExceptionAsync(() => vm.InstallCommand.ExecuteAsync(null));
                Assert.Null(operationError);
                Assert.Equal(WizardStep.Result, vm.Step);
            }
            else
            {
                vm.Password = "late-password";
            }

            var window = new MainWindow(vm) { ShowActivated = false, ShowInTaskbar = false, Opacity = 0 };
            var closed = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            var fatalReports = 0;
            window.Closed += (_, _) => closed.TrySetResult();
            DispatcherUnhandledExceptionEventHandler handler = (_, args) =>
            {
                fatalReports++;
                args.Handled = true;
            };
            Dispatcher.CurrentDispatcher.UnhandledException += handler;
            try
            {
                window.Show();
                window.Close();
                window.Close();
                await closed.Task.WaitAsync(TimeSpan.FromSeconds(2), TestContext.Current.CancellationToken);
                await Dispatcher.Yield(DispatcherPriority.ApplicationIdle);
                var shutdownError = await Record.ExceptionAsync(() => vm.ShutdownAsync());

                Assert.Null(shutdownError);
                Assert.Equal(0, fatalReports);
                Assert.Equal(1, workflow.Session.DisposeCalls);
                Assert.Equal("session-cleanup-failed", vm.StatusCode);
                AssertCredentialsCleared(vm);
                Assert.DoesNotContain("leaked-sentinel", vm.VisibleLog, StringComparison.Ordinal);
            }
            finally
            {
                Dispatcher.CurrentDispatcher.UnhandledException -= handler;
            }
        });
    }

    [Fact]
    public async Task Direct_and_nested_fatal_shutdown_failures_reach_the_wpf_dispatcher_once_after_close_cleanup()
    {
        await RunOnStaAsync(async () =>
        {
            var application = System.Windows.Application.Current ?? new System.Windows.Application();
            application.Resources.MergedDictionaries.Clear();
            application.Resources.MergedDictionaries.Add(LoadDictionary("en-US"));
            var sentinel = new Window { ShowActivated = false, ShowInTaskbar = false, Opacity = 0 };
            sentinel.Show();
            Exception[] fatalCases =
            [
                new OutOfMemoryException("direct-fatal"),
                new AggregateException(
                    "outer-fatal",
                    new InvalidOperationException("ordinary-inner"),
                    new InvalidOperationException("nested-fatal", new AccessViolationException("fatal-leaf"))),
            ];
            try
            {
                foreach (var fatal in fatalCases)
                {
                    var workflow = new FakeWorkflow();
                    workflow.Session.DisposeError = fatal;
                    var vm = await CreateReviewAsync(workflow);
                    vm.Password = "late-password";
                    var window = new MainWindow(vm) { ShowActivated = false, ShowInTaskbar = false, Opacity = 0 };
                    var closed = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
                    var reported = new TaskCompletionSource<Exception>(TaskCreationOptions.RunContinuationsAsynchronously);
                    var reportCount = 0;
                    var wasClosedWhenReported = false;
                    window.Closed += (_, _) => closed.TrySetResult();
                    DispatcherUnhandledExceptionEventHandler handler = (_, args) =>
                    {
                        reportCount++;
                        wasClosedWhenReported = closed.Task.IsCompleted;
                        reported.TrySetResult(args.Exception);
                        args.Handled = true;
                    };
                    Dispatcher.CurrentDispatcher.UnhandledException += handler;
                    try
                    {
                        window.Show();
                        window.Close();
                        window.Close();

                        await closed.Task.WaitAsync(TimeSpan.FromSeconds(2), TestContext.Current.CancellationToken);
                        var surfaced = await reported.Task.WaitAsync(TimeSpan.FromSeconds(2), TestContext.Current.CancellationToken);
                        await Dispatcher.Yield(DispatcherPriority.ApplicationIdle);

                        Assert.Same(fatal, surfaced);
                        Assert.Equal(1, reportCount);
                        Assert.True(wasClosedWhenReported);
                        Assert.Equal(1, workflow.Session.DisposeCalls);
                        Assert.NotEqual("session-cleanup-failed", vm.StatusCode);
                        AssertCredentialsCleared(vm);
                        var repeatedShutdown = await Record.ExceptionAsync(() => vm.ShutdownAsync());
                        Assert.Same(fatal, repeatedShutdown);
                        Assert.Equal(1, workflow.Session.DisposeCalls);
                    }
                    finally
                    {
                        Dispatcher.CurrentDispatcher.UnhandledException -= handler;
                    }
                }
            }
            finally
            {
                sentinel.Close();
            }
        });
    }

    [Theory]
    [InlineData(InstallerState.Ready, "ready")]
    [InlineData(InstallerState.Cancelled, "cancelled")]
    [InlineData(InstallerState.Unsupported, "runtime-changed")]
    [InlineData(InstallerState.Failed, "ready-verification-failed.rollback-failed")]
    [InlineData(InstallerState.RolledBack, "stage-disabled-failed")]
    public async Task Install_terminal_results_are_shown_without_leaking_new_credentials(InstallerState state, string reason)
    {
        var workflow = new FakeWorkflow();
        workflow.Session.Install = _ => Task.FromResult(Result(state, reason, "/srv/pz/.apollo-backups/one"));
        var vm = await CreateReviewAsync(workflow);
        vm.Password = "late-sentinel";
        vm.ConfirmPlannedRestarts = true;

        await vm.InstallCommand.ExecuteAsync(null);

        Assert.Equal(WizardStep.Result, vm.Step);
        Assert.Equal(reason, vm.StatusCode);
        AssertCredentialsCleared(vm);
        Assert.DoesNotContain("late-sentinel", vm.VisibleLog, StringComparison.Ordinal);
        Assert.True(vm.ShowSafeDisable);
    }

    [Theory]
    [InlineData(InstallerState.Ready, "ready")]
    [InlineData(InstallerState.Cancelled, "cancelled")]
    [InlineData(InstallerState.Unsupported, "runtime-changed")]
    [InlineData(InstallerState.Failed, "ready-verification-failed.rollback-failed")]
    [InlineData(InstallerState.RolledBack, "stage-disabled-failed")]
    public async Task Every_install_terminal_path_disposes_session_and_requires_fresh_trust_before_next_operation(
        InstallerState state,
        string reason)
    {
        var workflow = new FakeWorkflow();
        workflow.Session.Install = _ => Task.FromResult(Result(state, reason, "/srv/pz/.apollo-backups/one"));
        var vm = await CreateReviewAsync(workflow);
        vm.ConfirmPlannedRestarts = true;

        await vm.InstallCommand.ExecuteAsync(null);

        Assert.Equal(1, workflow.Session.DisposeCalls);
        Assert.True(vm.ShowSafeDisable);
        Assert.True(vm.SafeDisableCommand.CanExecute(null));
        await vm.SafeDisableCommand.ExecuteAsync(null);
        Assert.Equal(WizardStep.Connection, vm.Step);
        Assert.Equal("reauthentication-required", vm.StatusCode);
        Assert.Equal(0, workflow.Session.DisableCalls);
        await vm.ShutdownAsync();
        await vm.ShutdownAsync();
        Assert.Equal(1, workflow.Session.DisposeCalls);
    }

    [Fact]
    public async Task Safe_disable_runs_on_verified_session_and_remains_visible_on_result()
    {
        var workflow = new FakeWorkflow();
        var vm = await CreateReviewAsync(workflow);

        await vm.SafeDisableCommand.ExecuteAsync(null);

        Assert.Equal(1, workflow.Session.DisableCalls);
        Assert.Equal(WizardStep.Result, vm.Step);
        Assert.Equal("disabled", vm.StatusCode);
        Assert.True(vm.ShowSafeDisable);
        Assert.Equal(1, workflow.Session.DisposeCalls);
    }

    [Fact]
    public async Task Safe_disable_failure_is_terminal_and_clears_mutable_credentials()
    {
        var workflow = new FakeWorkflow();
        workflow.Session.DisableResult = Result(InstallerState.Failed, "vanilla-verification-failed", null);
        var vm = await CreateReviewAsync(workflow);
        vm.Password = "late-disable-secret";

        await vm.SafeDisableCommand.ExecuteAsync(null);

        Assert.Equal(WizardStep.Result, vm.Step);
        Assert.Equal("vanilla-verification-failed", vm.StatusCode);
        AssertCredentialsCleared(vm);
        Assert.Equal(1, workflow.Session.DisposeCalls);
    }

    [Fact]
    public async Task Saved_recovery_populates_connection_and_full_restore_uses_exact_backup()
    {
        var workflow = new FakeWorkflow { RecoveryResult = new RecoveryLoadResult(Recovery(), "recovery-record-loaded") };
        var vm = Create(workflow);
        vm.Password = "sentinel-secret";

        Assert.Equal("pz.example.test", vm.Host);
        Assert.Equal("/srv/pz", vm.DeploymentDirectory);
        Assert.Equal(new[] { "compose.yaml" }, vm.ComposeFiles);
        Assert.False(vm.ShowFullRollback);
        await vm.CheckServerCommand.ExecuteAsync(null);
        await vm.TrustAndContinueCommand.ExecuteAsync(null);
        Assert.True(vm.ShowFullRollback);

        await vm.FullRollbackCommand.ExecuteAsync(null);

        Assert.Equal("/srv/pz/.apollo-backups/20260812T120000Z", workflow.Session.RestorePath);
        Assert.Equal(WizardStep.Result, vm.Step);
        Assert.Equal("restored", vm.StatusCode);
        Assert.True(vm.ShowFullRollback);
        Assert.Equal(1, workflow.Session.DisposeCalls);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task Install_completion_reloads_current_backup_and_never_uses_missing_or_stale_startup_recovery(bool staleAtStartup)
    {
        var stale = Recovery() with { BackupPath = "/srv/pz/.apollo-backups/stale" };
        var current = Recovery() with { BackupPath = "/srv/pz/.apollo-backups/current" };
        var workflow = new FakeWorkflow
        {
            RecoveryResult = staleAtStartup
                ? new RecoveryLoadResult(stale, "recovery-record-loaded")
                : new RecoveryLoadResult(null, "recovery-record-not-found"),
            ReauthenticatedSession = new FakeSession(),
        };
        workflow.Session.Install = _ =>
        {
            workflow.RecoveryResult = new RecoveryLoadResult(current, "recovery-record-loaded");
            return Task.FromResult(Result(InstallerState.Ready, "ready", current.BackupPath));
        };
        var vm = await CreateReviewAsync(workflow);
        vm.ConfirmPlannedRestarts = true;

        await vm.InstallCommand.ExecuteAsync(null);

        Assert.True(vm.ShowFullRollback);
        Assert.True(workflow.LoadRecoveryCalls >= 2);
        await vm.FullRollbackCommand.ExecuteAsync(null);
        Assert.Equal(WizardStep.Connection, vm.Step);
        vm.Password = "fresh-secret";
        await vm.CheckServerCommand.ExecuteAsync(null);
        await vm.TrustAndContinueCommand.ExecuteAsync(null);
        await vm.FullRollbackCommand.ExecuteAsync(null);
        Assert.Equal(current.BackupPath, workflow.ReauthenticatedSession.RestorePath);
        Assert.DoesNotContain("stale", workflow.ReauthenticatedSession.RestorePath, StringComparison.Ordinal);
    }

    [Fact]
    public async Task Invalid_recovery_reload_after_install_fails_closed_and_hides_full_rollback()
    {
        var workflow = new FakeWorkflow { RecoveryResult = new RecoveryLoadResult(Recovery(), "recovery-record-loaded") };
        workflow.Session.Install = _ =>
        {
            workflow.RecoveryResult = new RecoveryLoadResult(null, "recovery-record-invalid");
            return Task.FromResult(Result(InstallerState.RolledBack, "stage-disabled-failed", Recovery().BackupPath));
        };
        var vm = await CreateReviewAsync(workflow);
        vm.ConfirmPlannedRestarts = true;

        await vm.InstallCommand.ExecuteAsync(null);

        Assert.False(vm.ShowFullRollback);
        Assert.False(vm.FullRollbackCommand.CanExecute(null));
    }

    [Fact]
    public async Task Restore_failure_is_terminal_and_preserves_recovery_action()
    {
        var workflow = new FakeWorkflow { RecoveryResult = new RecoveryLoadResult(Recovery(), "recovery-record-loaded") };
        workflow.Session.RestoreResult = Result(InstallerState.Failed, "restore-failed", Recovery().BackupPath);
        var vm = Create(workflow);
        await vm.CheckServerCommand.ExecuteAsync(null);
        await vm.TrustAndContinueCommand.ExecuteAsync(null);
        vm.PrivateKeyPath = "late-key-path";

        await vm.FullRollbackCommand.ExecuteAsync(null);

        Assert.Equal(WizardStep.Result, vm.Step);
        Assert.Equal("restore-failed", vm.StatusCode);
        AssertCredentialsCleared(vm);
        Assert.True(vm.ShowFullRollback);
        Assert.Equal(1, workflow.Session.DisposeCalls);
    }

    [Fact]
    public async Task Visible_log_is_rendered_only_from_redacted_events()
    {
        var workflow = new FakeWorkflow();
        workflow.Session.MutableEvents.Add(new RedactedEvent(2, "installer.result", new Dictionary<string, string>
        {
            ["reasonCode"] = "ready",
            ["state"] = "ready",
        }));
        workflow.Session.MutableEvents.Add(new RedactedEvent(1, "installer.step", new Dictionary<string, string>
        {
            ["step"] = "probe",
        }));
        var vm = await CreateReviewAsync(workflow);

        Assert.Equal("1 installer.step step=probe\r\n2 installer.result reasonCode=ready state=ready", vm.VisibleLog);
        Assert.DoesNotContain(vm.Host, vm.VisibleLog, StringComparison.Ordinal);
        Assert.DoesNotContain("sentinel-secret", vm.VisibleLog, StringComparison.Ordinal);
    }

    [Fact]
    public void Language_switch_preserves_all_field_values_and_uses_only_supported_cultures()
    {
        var switched = new List<string>();
        var vm = Create(languageSwitcher: switched.Add);
        vm.Host = "kept.example.test";
        vm.Password = "kept-secret";
        vm.ComposeFiles.Add("override.yaml");

        vm.SwitchLanguageCommand.Execute("ru-RU");

        Assert.Equal("ru-RU", vm.Language);
        Assert.Equal(new[] { "ru-RU" }, switched);
        Assert.Equal("kept.example.test", vm.Host);
        Assert.Equal("kept-secret", vm.Password);
        Assert.Equal(new[] { "compose.yaml", "override.yaml" }, vm.ComposeFiles);
        vm.SwitchLanguageCommand.Execute("de-DE");
        Assert.Equal("ru-RU", vm.Language);
        Assert.Equal(new[] { "ru-RU" }, switched);
    }

    [Fact]
    public async Task Async_commands_suppress_double_clicks()
    {
        var workflow = new FakeWorkflow();
        var gate = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        workflow.Session.Install = async token =>
        {
            await gate.Task.WaitAsync(token);
            return Result(InstallerState.Ready, "ready", "/srv/pz/.apollo-backups/one");
        };
        var vm = await CreateReviewAsync(workflow);
        vm.ConfirmPlannedRestarts = true;

        var first = vm.InstallCommand.ExecuteAsync(null);
        await workflow.Session.InstallStarted.Task.WaitAsync(TimeSpan.FromSeconds(2), TestContext.Current.CancellationToken);
        var second = vm.InstallCommand.ExecuteAsync(null);
        gate.SetResult();
        await Task.WhenAll(first, second);

        Assert.Equal(1, workflow.Session.InstallCalls);
    }

    [Fact]
    public async Task Every_observation_or_preview_terminal_path_clears_mutable_credentials()
    {
        var observationFailure = new FakeWorkflow { ObserveError = new IOException("sentinel exception") };
        var observed = Create(observationFailure);
        observed.Password = "observation-secret";
        await observed.CheckServerCommand.ExecuteAsync(null);
        Assert.Equal(WizardStep.Result, observed.Step);
        AssertCredentialsCleared(observed);

        var previewFailure = new FakeWorkflow();
        previewFailure.Session.PreviewError = new IOException("sentinel preview") ;
        var previewed = Create(previewFailure);
        previewed.Password = "preview-secret";
        await previewed.CheckServerCommand.ExecuteAsync(null);
        await previewed.TrustAndContinueCommand.ExecuteAsync(null);
        Assert.Equal(WizardStep.Result, previewed.Step);
        AssertCredentialsCleared(previewed);
        Assert.DoesNotContain("sentinel", previewed.VisibleLog, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void English_and_Russian_resource_dictionaries_have_equivalent_nonempty_keys()
    {
        RunOnSta(() =>
        {
            var english = LoadDictionary("en-US");
            var russian = LoadDictionary("ru-RU");
            var englishKeys = english.Keys.Cast<object>().Select(value => value.ToString()).Order(StringComparer.Ordinal).ToArray();
            var russianKeys = russian.Keys.Cast<object>().Select(value => value.ToString()).Order(StringComparer.Ordinal).ToArray();

            Assert.Equal(englishKeys, russianKeys);
            Assert.All(english.Values.Cast<object>(), value => Assert.False(string.IsNullOrWhiteSpace(value.ToString())));
            Assert.All(russian.Values.Cast<object>(), value => Assert.False(string.IsNullOrWhiteSpace(value.ToString())));
            Assert.Equal("Code", english["ActionCodeHeader"]);
            Assert.Equal("Target", english["ActionTargetHeader"]);
            Assert.Equal("Код", russian["ActionCodeHeader"]);
            Assert.Equal("Цель", russian["ActionTargetHeader"]);
        });
    }

    [Fact]
    public void Planned_action_headers_switch_between_localized_resource_values()
    {
        RunOnSta(() =>
        {
            var application = System.Windows.Application.Current ?? new System.Windows.Application();
            application.Resources.MergedDictionaries.Clear();
            application.Resources.MergedDictionaries.Add(LoadDictionary("en-US"));
            var window = new MainWindow(Create());
            try
            {
                var list = Assert.IsType<ListView>(window.FindName("PlannedActionsList"));
                var grid = Assert.IsType<GridView>(list.View);
                Assert.Equal(new[] { "Code", "Target" }, grid.Columns.Select(column => column.Header?.ToString()));
                application.Resources.MergedDictionaries.Clear();
                application.Resources.MergedDictionaries.Add(LoadDictionary("ru-RU"));
                window.UpdateLayout();
                Assert.Equal(new[] { "Код", "Цель" }, grid.Columns.Select(column => column.Header?.ToString()));
            }
            finally
            {
                window.Close();
            }
        });
    }

    [Fact]
    public void Native_window_labels_inputs_never_default_enter_and_log_is_read_only()
    {
        RunOnSta(() =>
        {
            var application = System.Windows.Application.Current ?? new System.Windows.Application();
            application.Resources.MergedDictionaries.Clear();
            application.Resources.MergedDictionaries.Add(LoadDictionary("en-US"));
            var window = new MainWindow(Create());
            try
            {
                window.Measure(new Size(1100, 900));
                window.Arrange(new Rect(0, 0, 1100, 900));
                window.UpdateLayout();
                var controls = LogicalDescendants<Control>(window).ToArray();
                var inputs = controls.Where(control => control is TextBox or PasswordBox or ComboBox or RadioButton or CheckBox).ToArray();

                Assert.NotEmpty(inputs);
                Assert.All(inputs, control => Assert.False(string.IsNullOrWhiteSpace(AutomationProperties.GetName(control))));
                Assert.All(controls.OfType<Button>(), button => Assert.False(button.IsDefault));
                var labels = controls.OfType<Label>().ToArray();
                Assert.True(labels.Length >= 8);
                Assert.All(labels, label => Assert.False(string.IsNullOrWhiteSpace(label.Content?.ToString())));
                var log = Assert.IsType<TextBox>(window.FindName("EventLogTextBox"));
                Assert.True(log.IsReadOnly);
                Assert.True(log.AcceptsReturn);
                Assert.Equal("Docker Compose over SSH", Assert.IsType<TextBlock>(window.FindName("ServerModeText")).Text);
            }
            finally
            {
                window.Close();
            }
        });
    }

    [Fact]
    public async Task Compose_rows_are_live_accessible_tab_stops_before_row_controls_service_and_check()
    {
        await RunOnStaAsync(async () =>
        {
            var application = System.Windows.Application.Current ?? new System.Windows.Application();
            application.Resources.MergedDictionaries.Clear();
            application.Resources.MergedDictionaries.Add(LoadDictionary("en-US"));
            var vm = Create();
            vm.ComposeFiles.Add("override.yaml");
            var window = new MainWindow(vm) { ShowActivated = false, ShowInTaskbar = false, Opacity = 0 };
            var closed = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            window.Closed += (_, _) => closed.TrySetResult();
            window.Show();
            window.UpdateLayout();

            var items = Assert.IsType<ItemsControl>(window.FindName("ComposeFilesItems"));
            var rows = Descendants<TextBox>(items).ToArray();
            Assert.Equal(2, rows.Length);
            Assert.All(rows, row => Assert.Equal(9, KeyboardNavigation.GetTabIndex(row)));
            Assert.All(rows, row => Assert.False(string.IsNullOrWhiteSpace(AutomationProperties.GetName(row))));
            var add = Assert.IsType<Button>(window.FindName("AddComposeFileButton"));
            var remove = Assert.IsType<Button>(window.FindName("RemoveComposeFileButton"));
            var service = Assert.IsType<TextBox>(window.FindName("ServiceNameTextBox"));
            var check = Assert.IsType<Button>(window.FindName("CheckServerButton"));
            Assert.True(KeyboardNavigation.GetTabIndex(rows[^1]) < KeyboardNavigation.GetTabIndex(add));
            Assert.True(KeyboardNavigation.GetTabIndex(add) < KeyboardNavigation.GetTabIndex(remove));
            Assert.True(KeyboardNavigation.GetTabIndex(remove) < KeyboardNavigation.GetTabIndex(service));
            Assert.True(KeyboardNavigation.GetTabIndex(service) < KeyboardNavigation.GetTabIndex(check));

            window.Close();
            await closed.Task;
        });
    }

    private static ResourceDictionary LoadDictionary(string culture)
        => (ResourceDictionary)System.Windows.Application.LoadComponent(
            new Uri($"/Apollo.NativeAssist.Installer;component/Localization/Strings.{culture}.xaml", UriKind.Relative));

    private static IEnumerable<T> Descendants<T>(DependencyObject parent) where T : DependencyObject
    {
        for (var index = 0; index < VisualTreeHelper.GetChildrenCount(parent); index++)
        {
            var child = VisualTreeHelper.GetChild(parent, index);
            if (child is T typed) yield return typed;
            foreach (var descendant in Descendants<T>(child)) yield return descendant;
        }
    }

    private static IEnumerable<T> LogicalDescendants<T>(DependencyObject parent) where T : DependencyObject
    {
        foreach (var child in LogicalTreeHelper.GetChildren(parent).OfType<DependencyObject>())
        {
            if (child is T typed) yield return typed;
            foreach (var descendant in LogicalDescendants<T>(child)) yield return descendant;
        }
    }

    private static void RunOnSta(Action action)
    {
        Exception? error = null;
        var thread = new Thread(() =>
        {
            try { action(); }
            catch (Exception exception) { error = exception; }
        });
        thread.SetApartmentState(ApartmentState.STA);
        thread.Start();
        thread.Join();
        if (error is not null) throw new AggregateException(error);
    }

    private static Task RunOnStaAsync(Func<Task> action)
    {
        var completion = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var thread = new Thread(() =>
        {
            var dispatcher = Dispatcher.CurrentDispatcher;
            SynchronizationContext.SetSynchronizationContext(new DispatcherSynchronizationContext(dispatcher));
            dispatcher.BeginInvoke(async () =>
            {
                try
                {
                    await action();
                    completion.TrySetResult();
                }
                catch (Exception exception)
                {
                    completion.TrySetException(exception);
                }
                finally
                {
                    dispatcher.BeginInvokeShutdown(DispatcherPriority.Background);
                }
            });
            Dispatcher.Run();
        });
        thread.SetApartmentState(ApartmentState.STA);
        thread.Start();
        return completion.Task;
    }

    private static MainWindowViewModel Create(FakeWorkflow? workflow = null, Action<string>? languageSwitcher = null)
    {
        var viewModel = new MainWindowViewModel(workflow ?? new FakeWorkflow(), "en-US", languageSwitcher)
        {
            AuthenticationMode = AuthenticationMode.Password,
        };
        viewModel.Password = "sentinel-" + "secret";
        return viewModel;
    }

    private static async Task<MainWindowViewModel> CreateReviewAsync(FakeWorkflow workflow)
    {
        var vm = Create(workflow);
        await vm.CheckServerCommand.ExecuteAsync(null);
        await vm.TrustAndContinueCommand.ExecuteAsync(null);
        Assert.Equal(WizardStep.Review, vm.Step);
        return vm;
    }

    private static void AssertCredentialsCleared(MainWindowViewModel vm)
    {
        Assert.Equal(string.Empty, vm.Password);
        Assert.Equal(string.Empty, vm.PrivateKeyPath);
        Assert.Equal(string.Empty, vm.PrivateKeyPassphrase);
    }

    private static RuntimeProbe Runtime() => new(
        "380870", "24574884", "42.20.2", 25, "linux", "amd64", "sha256:" + new string('a', 64));

    private static InstallationResult Result(InstallerState state, string reason, string? backup)
        => new(state, reason, backup);

    private static RecoveryRecord Recovery() => new(
        "pz.example.test", 22, "steam", Observation.Sha256,
        "/srv/pz", ["compose.yaml"], "pz-server", "/srv/pz/.apollo-backups/20260812T120000Z");

    private sealed class FakeWorkflow : IInstallerSessionWorkflow
    {
        public int ObserveCalls { get; private set; }
        public int CreateCalls { get; private set; }
        public int CredentialReads { get; private set; }
        public AuthenticationMode? CredentialMode { get; private set; }
        public string? PasswordRead { get; private set; }
        public string? PrivateKeyPathRead { get; private set; }
        public string? PassphraseRead { get; private set; }
        public Exception? ObserveError { get; init; }
        public Func<ConnectionInput, CancellationToken, Task<SshHostKeyObservation>>? Observe { get; init; }
        public Exception? CreateError { get; init; }
        public FakeSession Session { get; } = new();
        public FakeSession? ReauthenticatedSession { get; init; }
        public RecoveryLoadResult RecoveryResult { get; set; } = new(null, "recovery-record-not-found");
        public int LoadRecoveryCalls { get; private set; }

        public RecoveryLoadResult LoadRecovery()
        {
            LoadRecoveryCalls++;
            return RecoveryResult;
        }

        public Task<SshHostKeyObservation> ObserveHostKeyAsync(ConnectionInput input, CancellationToken cancellationToken)
        {
            ObserveCalls++;
            if (ObserveError is not null) return Task.FromException<SshHostKeyObservation>(ObserveError);
            if (Observe is not null) return Observe(input, cancellationToken);
            return Task.FromResult(Observation);
        }

        public Task<IInstallerSession> CreateConfirmedAsync(
            ConnectionInput input, SshHostKeyObservation observation, ICredentialSource credentialSource,
            CancellationToken cancellationToken)
        {
            CreateCalls++;
            if (CreateError is not null) return Task.FromException<IInstallerSession>(CreateError);
            using var credential = credentialSource.Read();
            CredentialReads++;
            this.CredentialMode = input.AuthenticationMode;
            if (input.AuthenticationMode == AuthenticationMode.Password)
            {
                this.PasswordRead = ReadCredentialText(credential, "CopyPassword");
            }
            else
            {
                PrivateKeyPathRead = ReadCredentialText(credential, "CopyPrivateKeyPath");
                PassphraseRead = ReadCredentialText(credential, "CopyPrivateKeyPassphrase");
            }

            return Task.FromResult<IInstallerSession>(CreateCalls > 1 && ReauthenticatedSession is not null
                ? ReauthenticatedSession
                : Session);
        }

        private static string? ReadCredentialText(CredentialInput input, string method)
        {
            var value = typeof(CredentialInput).GetMethod(method, System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.NonPublic)!.Invoke(input, null);
            return value is char[] characters ? new string(characters) : null;
        }
    }

    private sealed class FakeSession : IInstallerSession
    {
        public ObservableCollection<RedactedEvent> MutableEvents { get; } = [];
        public IReadOnlyList<RedactedEvent> Events => MutableEvents;
        public InstallationPreview Preview { get; set; } = new(true, "supported", Runtime(),
            [new PlannedAction("restart-disabled", "pz-server", true, true)]);
        public Exception? PreviewError { get; set; }
        public Func<CancellationToken, Task<InstallationResult>> Install { get; set; }
            = _ => Task.FromResult(Result(InstallerState.Ready, "ready", "/srv/pz/.apollo-backups/one"));
        public int InstallCalls { get; private set; }
        public int DisableCalls { get; private set; }
        public int RestoreCalls { get; private set; }
        public int RollbackAttempts { get; set; }
        public string? RestorePath { get; private set; }
        public InstallationResult DisableResult { get; set; } = Result(InstallerState.Disabled, "disabled", null);
        public InstallationResult RestoreResult { get; set; } = Result(InstallerState.RolledBack, "restored", "/srv/pz/.apollo-backups/one");
        public TaskCompletionSource InstallStarted { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public TaskCompletionSource BackupReached { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public int DisposeCalls { get; private set; }
        public Action? Disposing { get; set; }
        public Exception? DisposeError { get; set; }

        public Task<InstallationPreview> PreviewAsync(CancellationToken cancellationToken)
            => PreviewError is null ? Task.FromResult(Preview) : Task.FromException<InstallationPreview>(PreviewError);

        public Task<InstallationResult> InstallAsync(CancellationToken cancellationToken)
        {
            InstallCalls++;
            InstallStarted.TrySetResult();
            return Install(cancellationToken);
        }

        public Task<InstallationResult> DisableAsync(CancellationToken cancellationToken)
        {
            DisableCalls++;
            return Task.FromResult(DisableResult);
        }

        public Task<InstallationResult> RestoreAsync(string backupPath, CancellationToken cancellationToken)
        {
            RestoreCalls++;
            RestorePath = backupPath;
            return Task.FromResult(RestoreResult with { BackupPath = backupPath });
        }

        public ValueTask DisposeAsync()
        {
            DisposeCalls++;
            Disposing?.Invoke();
            if (DisposeError is not null)
            {
                return ValueTask.FromException(DisposeError);
            }

            return ValueTask.CompletedTask;
        }
    }
}
