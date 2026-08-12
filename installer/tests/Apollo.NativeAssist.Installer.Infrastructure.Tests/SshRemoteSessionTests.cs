using System.Diagnostics;
using System.Text;
using Apollo.NativeAssist.Installer.Infrastructure.Ssh;
using Xunit;

namespace Apollo.NativeAssist.Installer.Infrastructure.Tests;

public sealed class SshRemoteSessionTests
{
    private const string HostKeyPin = "ohD8VZEXGWo6Ez8GSEJQ9WpafgLFsOfLOtGGQCQo6Og";
    private static readonly IReadOnlyList<string> ComposeFiles =
    [
        "/srv/pz/docker-compose.yml",
        "/srv/pz/compose.apollo-native.yaml",
    ];

    [Fact]
    public void Recreate_script_is_fixed_bounded_and_targets_only_the_selected_service()
    {
        var operation = RemoteOperation.RecreateService("/srv/pz", ComposeFiles, "project-zomboid", "on");

        var script = SshRemoteSession.RenderCommand(operation);

        Assert.Contains("set -eu", script, StringComparison.Ordinal);
        Assert.Contains("cd -- '/srv/pz'", script, StringComparison.Ordinal);
        Assert.Contains("-f '/srv/pz/docker-compose.yml'", script, StringComparison.Ordinal);
        Assert.Contains("-f '/srv/pz/compose.apollo-native.yaml'", script, StringComparison.Ordinal);
        Assert.Contains("up -d --no-deps --force-recreate --pull never -- 'project-zomboid'", script, StringComparison.Ordinal);
        Assert.Contains("--env-file '/srv/pz/.apollo-native.env'", script, StringComparison.Ordinal);
        Assert.DoesNotContain("down", script, StringComparison.Ordinal);
        Assert.DoesNotContain("rm ", script, StringComparison.Ordinal);
    }

    [Fact]
    public void Every_operation_renders_from_a_closed_template_without_credentials()
    {
        const string sensitiveValue =
            "top-secret-password";
        var operations = new[]
        {
            RemoteOperation.Discover("/srv/pz", ComposeFiles, "project-zomboid"),
            RemoteOperation.Backup("/srv/pz", ComposeFiles, "project-zomboid"),
            RemoteOperation.PrepareStaging("/srv/pz", ComposeFiles, "project-zomboid"),
            RemoteOperation.ValidateMergedConfiguration("/srv/pz", ComposeFiles, "project-zomboid", "off"),
            RemoteOperation.RecreateService("/srv/pz", ComposeFiles, "project-zomboid", "off"),
            RemoteOperation.VerifyVanilla("/srv/pz", ComposeFiles, "project-zomboid", requireHealthcheck: true),
            RemoteOperation.SetKillSwitch("/srv/pz", ComposeFiles, "project-zomboid", "on"),
            RemoteOperation.VerifyReady("/srv/pz", ComposeFiles, "project-zomboid", requireHealthcheck: true),
            RemoteOperation.RestoreBackup("/srv/pz", ComposeFiles, "project-zomboid", "/srv/pz/.apollo-backups/20260811T120000Z"),
        };

        foreach (var operation in operations)
        {
            var script = SshRemoteSession.RenderCommand(operation);
            Assert.False(string.IsNullOrWhiteSpace(script));
            Assert.DoesNotContain(sensitiveValue, script, StringComparison.Ordinal);
            Assert.DoesNotContain("sudo", script, StringComparison.Ordinal);
        }
    }

    [Theory]
    [InlineData("")]
    [InlineData("ON")]
    [InlineData("off; id")]
    public void Kill_switch_rejects_every_phase_outside_the_closed_on_off_set(string phase)
    {
        Assert.Throws<ArgumentException>(() => RemoteOperation.SetKillSwitch(
            "/srv/pz",
            ComposeFiles,
            "project-zomboid",
            phase));
    }

    [Theory]
    [InlineData("off")]
    [InlineData("on")]
    public void Kill_switch_rewrites_exactly_one_line_without_accepting_an_arbitrary_value(string phase)
    {
        var script = SshRemoteSession.RenderCommand(
            RemoteOperation.SetKillSwitch("/srv/pz", ComposeFiles, "project-zomboid", phase));

        Assert.Contains($"print \"APOLLO_NATIVE_ASSIST={phase}\"", script, StringComparison.Ordinal);
        Assert.Contains("/^APOLLO_NATIVE_ASSIST=/{count++;print", script, StringComparison.Ordinal);
        Assert.Contains("END{if(count!=1) exit 43}", script, StringComparison.Ordinal);
        Assert.Contains("next} {print}", script, StringComparison.Ordinal);
    }

    [Fact]
    public void Kill_switch_rejects_links_and_atomically_persists_a_mode_0600_file_inside_deployment()
    {
        var script = SshRemoteSession.RenderCommand(
            RemoteOperation.SetKillSwitch("/srv/pz", ComposeFiles, "project-zomboid", "off"));

        Assert.Contains("cd -- '/srv/pz'", script, StringComparison.Ordinal);
        Assert.Contains("root=$(realpath -e -- .)", script, StringComparison.Ordinal);
        Assert.Contains("test ! -L .apollo-native.env", script, StringComparison.Ordinal);
        Assert.Contains("test \"$(realpath -e -- .apollo-native.env)\" = \"$root/.apollo-native.env\"", script, StringComparison.Ordinal);
        Assert.Contains("tmp=$(mktemp ./.apollo-native.env.tmp.XXXXXX)", script, StringComparison.Ordinal);
        Assert.Contains("test ! -L \"$tmp\"", script, StringComparison.Ordinal);
        Assert.Contains("test \"$(dirname -- \"$(realpath -e -- \"$tmp\")\")\" = \"$root\"", script, StringComparison.Ordinal);

        var chmod = script.IndexOf("chmod 0600 \"$tmp\"", StringComparison.Ordinal);
        var rewrite = script.IndexOf("awk ", StringComparison.Ordinal);
        var fileSync = script.IndexOf("sync -f -- \"$tmp\"", StringComparison.Ordinal);
        var rename = script.IndexOf("mv -f -- \"$tmp\" .apollo-native.env", StringComparison.Ordinal);
        var directorySync = script.IndexOf("sync -f -- .", StringComparison.Ordinal);
        Assert.True(chmod >= 0);
        Assert.True(rewrite > chmod);
        Assert.True(fileSync > rewrite);
        Assert.True(rename > fileSync);
        Assert.True(directorySync > rename);
    }

    [Fact]
    public void Executed_kill_switch_preserves_blank_lines_crlf_and_a_present_final_newline()
    {
        using var fixture = new ExecutableBashFixture();
        fixture.WriteEnvironment("ALPHA=one\r\n\r\nAPOLLO_NATIVE_ASSIST=on\r\nOMEGA=two\r\n");
        var originalInode = fixture.Query("stat -c %i -- .apollo-native.env");

        var result = fixture.ExecuteKillSwitch("off");

        AssertSuccess(result, "execute kill-switch with a final newline");
        Assert.Equal(
            Encoding.UTF8.GetBytes("ALPHA=one\r\n\r\nAPOLLO_NATIVE_ASSIST=off\r\nOMEGA=two\r\n"),
            fixture.ReadEnvironment());
        Assert.NotEqual(originalInode, fixture.Query("stat -c %i -- .apollo-native.env"));
        Assert.Empty(fixture.TemporaryFiles());
    }

    [Fact]
    public void Executed_kill_switch_sets_the_replacement_file_mode_to_0600()
    {
        using var fixture = new ExecutableBashFixture();
        if (!fixture.CanObservePosixModes(out var diagnostic))
        {
            Assert.Skip(diagnostic);
        }

        fixture.WriteEnvironment("APOLLO_NATIVE_ASSIST=on\nKEEP=this\n");

        var result = fixture.ExecuteKillSwitch("off");

        AssertSuccess(result, "execute kill-switch for the mode check");
        Assert.Equal("600", fixture.Query("stat -c %a -- .apollo-native.env"));
    }

    [Fact]
    public void Executed_kill_switch_preserves_blank_lines_and_an_absent_final_newline()
    {
        using var fixture = new ExecutableBashFixture();
        fixture.WriteEnvironment("ALPHA=one\n\nAPOLLO_NATIVE_ASSIST=on\nOMEGA=two");

        var result = fixture.ExecuteKillSwitch("off");

        AssertSuccess(result, "execute kill-switch without a final newline");
        Assert.Equal(
            Encoding.UTF8.GetBytes("ALPHA=one\n\nAPOLLO_NATIVE_ASSIST=off\nOMEGA=two"),
            fixture.ReadEnvironment());
        Assert.Empty(fixture.TemporaryFiles());
    }

    [Theory]
    [InlineData("ALPHA=one\n\nOMEGA=two")]
    [InlineData("APOLLO_NATIVE_ASSIST=on\n\nAPOLLO_NATIVE_ASSIST=off\n")]
    public void Executed_kill_switch_requires_exactly_one_target_and_leaves_original_untouched(string original)
    {
        using var fixture = new ExecutableBashFixture();
        fixture.WriteEnvironment(original);

        var result = fixture.ExecuteKillSwitch("off");

        Assert.True(result.ExitCode == 43, result.Diagnostics("reject a missing or duplicate kill-switch line"));
        Assert.Equal(Encoding.UTF8.GetBytes(original), fixture.ReadEnvironment());
        Assert.Empty(fixture.TemporaryFiles());
    }

    [Fact]
    public void Executed_kill_switch_rejects_an_actual_target_symlink_without_changing_its_referent()
    {
        using var fixture = new ExecutableBashFixture();
        fixture.WriteFile(".apollo-native.env.target", "APOLLO_NATIVE_ASSIST=on\nKEEP=this\n");
        fixture.CreateFileSymlink(".apollo-native.env", ".apollo-native.env.target");

        var result = fixture.ExecuteKillSwitch("off");

        Assert.True(result.ExitCode != 0, result.Diagnostics("reject the target symlink"));
        Assert.Equal(
            Encoding.UTF8.GetBytes("APOLLO_NATIVE_ASSIST=on\nKEEP=this\n"),
            fixture.ReadFile(".apollo-native.env.target"));
        Assert.Equal("link", fixture.Query("if test -L .apollo-native.env; then printf link; else printf not-link; fi"));
        Assert.Empty(fixture.TemporaryFiles());
    }

    [Fact]
    public void Executed_kill_switch_rejects_and_cleans_an_actual_temporary_symlink()
    {
        using var fixture = new ExecutableBashFixture();
        fixture.WriteEnvironment("APOLLO_NATIVE_ASSIST=on\nKEEP=this\n");
        fixture.InstallSymlinkReturningMktemp();

        var result = fixture.ExecuteKillSwitch("off", prependFixtureTools: true);

        Assert.True(result.ExitCode == 1, result.Diagnostics("reject the temporary symlink"));
        Assert.Equal(
            Encoding.UTF8.GetBytes("APOLLO_NATIVE_ASSIST=on\nKEEP=this\n"),
            fixture.ReadEnvironment());
        Assert.Empty(fixture.TemporaryFiles());
    }

    [Fact]
    public void Backup_uses_a_fresh_noncolliding_leaf_before_any_upload()
    {
        var script = SshRemoteSession.RenderCommand(
            RemoteOperation.Backup("/srv/pz", ComposeFiles, "project-zomboid"));

        Assert.Contains("mkdir -m 0700 -- .apollo-backups", script, StringComparison.Ordinal);
        Assert.Contains("mkdir -- \"$backup\"", script, StringComparison.Ordinal);
        Assert.DoesNotContain("mkdir -p -- \"$backup\"", script, StringComparison.Ordinal);
        Assert.Contains("test ! -L .apollo-backups", script, StringComparison.Ordinal);
        Assert.Contains("realpath -e", script, StringComparison.Ordinal);
        Assert.Contains("state.manifest", script, StringComparison.Ordinal);
        Assert.Contains(".apollo-native.env", script, StringComparison.Ordinal);
    }

    [Fact]
    public void Restore_validates_realpath_and_manifest_before_deleting_current_state()
    {
        var script = SshRemoteSession.RenderCommand(RemoteOperation.RestoreBackup(
            "/srv/pz",
            ["/srv/pz/docker-compose.yml"],
            "project-zomboid",
            "/srv/pz/.apollo-backups/20260811T120000Z"));

        var validation = script.IndexOf("validated=1", StringComparison.Ordinal);
        var deletion = script.IndexOf("rm -rf -- .apollo-native", StringComparison.Ordinal);
        Assert.True(validation >= 0);
        Assert.True(deletion > validation);
        Assert.Contains("realpath -e", script, StringComparison.Ordinal);
        Assert.Contains("state.manifest", script, StringComparison.Ordinal);
        Assert.Contains("test ! -L", script, StringComparison.Ordinal);
        Assert.Contains("for item in .apollo-native .apollo-native.env compose.apollo-native.yaml", script, StringComparison.Ordinal);
        Assert.DoesNotContain("apollo_kind=", script, StringComparison.Ordinal);
        Assert.DoesNotContain("--env-file", script, StringComparison.Ordinal);
        Assert.DoesNotContain("-f './compose.apollo-native.yaml'", script, StringComparison.Ordinal);
        Assert.Contains("docker compose -f '/srv/pz/docker-compose.yml' up", script, StringComparison.Ordinal);
    }

    [Fact]
    public void Discovery_proves_compose_registry_process_and_current_runtime_facts_read_only()
    {
        var script = SshRemoteSession.RenderCommand(
            RemoteOperation.Discover("/srv/pz", ComposeFiles, "project-zomboid"));

        Assert.Contains("config', '--format', 'json'", script, StringComparison.Ordinal);
        Assert.Contains("compose_service", script, StringComparison.Ordinal);
        Assert.Contains("RepoDigests", script, StringComparison.Ordinal);
        Assert.Contains("PortBindings", script, StringComparison.Ordinal);
        Assert.Contains("NetworkSettings", script, StringComparison.Ordinal);
        Assert.Contains("MaximumRetryCount", script, StringComparison.Ordinal);
        Assert.Contains("Propagation", script, StringComparison.Ordinal);
        Assert.Contains("IPAMConfig", script, StringComparison.Ordinal);
        Assert.Contains("expected_ipv4 != (ipam.get('IPv4Address') or '')", script, StringComparison.Ordinal);
        Assert.Contains("expected_ipv6 != (ipam.get('IPv6Address') or '')", script, StringComparison.Ordinal);
        Assert.Contains("Aliases", script, StringComparison.Ordinal);
        Assert.Contains("MacAddress", script, StringComparison.Ordinal);
        Assert.Contains("running_mounts", script, StringComparison.Ordinal);
        Assert.Contains("StartedAt", script, StringComparison.Ordinal);
        Assert.Contains("/proc/[0-9]", script, StringComparison.Ordinal);
        Assert.DoesNotContain("'/usr/bin/test','-w'", script, StringComparison.Ordinal);
        Assert.Contains("test -r \"$2\" -a -w \"$2\" -a -x \"$2\"", script, StringComparison.Ordinal);
        Assert.Contains("/proc/$1", script, StringComparison.Ordinal);
        Assert.Contains("'-L'", script, StringComparison.Ordinal);
        Assert.DoesNotContain("mkdir", script, StringComparison.Ordinal);
        Assert.DoesNotContain("rm -", script, StringComparison.Ordinal);
    }

    [Theory]
    [InlineData(RemoteOperationKind.VerifyVanilla)]
    [InlineData(RemoteOperationKind.VerifyReady)]
    public void Runtime_verification_polls_same_container_until_bounded_healthy_milestones(RemoteOperationKind kind)
    {
        var operation = kind == RemoteOperationKind.VerifyVanilla
            ? RemoteOperation.VerifyVanilla("/srv/pz", ComposeFiles, "project-zomboid", requireHealthcheck: true)
            : RemoteOperation.VerifyReady("/srv/pz", ComposeFiles, "project-zomboid", requireHealthcheck: true);

        var script = SshRemoteSession.RenderCommand(operation);

        Assert.Contains("deadline=$((SECONDS + 170))", script, StringComparison.Ordinal);
        Assert.Contains("initial_cid", script, StringComparison.Ordinal);
        Assert.Contains("initial_started", script, StringComparison.Ordinal);
        Assert.Contains("42\\.20\\.2", script, StringComparison.Ordinal);
        Assert.Contains("Health.Status", script, StringComparison.Ordinal);
        Assert.Contains("/proc/[0-9]", script, StringComparison.Ordinal);
        Assert.Contains("final_started", script, StringComparison.Ordinal);
        Assert.Contains("sleep 2", script, StringComparison.Ordinal);
        if (kind == RemoteOperationKind.VerifyReady)
        {
            Assert.Contains("CIRCUIT_OPEN|INCOMPATIBLE|advice", script, StringComparison.Ordinal);
            Assert.Contains("control seq=[0-9]+ state=READY reason=hooks-armed advice_errors_total=0", script, StringComparison.Ordinal);
        }
    }

    [Fact]
    public void Posix_quoting_preserves_valid_apostrophes_without_enabling_shell_injection()
    {
        var operation = RemoteOperation.Backup(
            "/srv/pz'alpha",
            ["/srv/pz'alpha/docker-compose.yml"],
            "project-zomboid");

        var script = SshRemoteSession.RenderCommand(operation);

        Assert.Contains("'/srv/pz'\"'\"'alpha'", script, StringComparison.Ordinal);
        Assert.DoesNotContain("/srv/pz'alpha'", script, StringComparison.Ordinal);
    }

    [Fact]
    public void Output_limit_counts_utf8_bytes_and_never_returns_partial_secrets()
    {
        var safe = SshRemoteSession.ApplyOutputLimit("ready", "", 16);
        var oversized = SshRemoteSession.ApplyOutputLimit("пароль", "error", 8);

        Assert.False(safe.Truncated);
        Assert.Equal("ready", safe.StandardOutput);
        Assert.True(oversized.Truncated);
        Assert.Equal(string.Empty, oversized.StandardOutput);
        Assert.Equal(string.Empty, oversized.StandardError);
    }

    [Fact]
    public async Task Command_output_collector_never_buffers_past_the_shared_stdout_stderr_limit()
    {
        var output = new BoundedCommandOutput(8);

        await output.CopyAsync(new MemoryStream(Encoding.UTF8.GetBytes("ready")), standardError: false, TestContext.Current.CancellationToken);
        await Assert.ThrowsAsync<InvalidDataException>(() => output.CopyAsync(
            new MemoryStream(Encoding.UTF8.GetBytes("boom")),
            standardError: true,
            TestContext.Current.CancellationToken));

        Assert.True(output.Truncated);
        Assert.Equal(5, output.BufferedBytes);
    }

    [Fact]
    public void Host_key_pin_is_mandatory_and_compared_in_constant_time_shape()
    {
        Assert.True(SshRemoteSession.IsExpectedHostKey(HostKeyPin, HostKeyPin));
        Assert.False(SshRemoteSession.IsExpectedHostKey(HostKeyPin, "phD8VZEXGWo6Ez8GSEJQ9WpafgLFsOfLOtGGQCQo6Og"));
        Assert.Throws<ArgumentException>(() => Options(""));
        Assert.Throws<ArgumentException>(() => Options("SHA256:" + HostKeyPin));
        Assert.Equal(HostKeyPin, Options(HostKeyPin).HostKeySha256);
    }

    [Fact]
    public async Task Bounded_read_stops_a_nonseekable_source_at_the_first_excess_byte()
    {
        var source = new CountingNonSeekableStream(4096);
        await using var bounded = new BoundedReadStream(source, 1024);

        await Assert.ThrowsAsync<InvalidDataException>(() => bounded.CopyToAsync(Stream.Null, TestContext.Current.CancellationToken));

        Assert.Equal(1025, source.BytesRead);
    }

    [Fact]
    public async Task Bounded_write_never_writes_past_the_limit_when_remote_file_grows()
    {
        await using var destination = new MemoryStream();
        await using var bounded = new BoundedWriteStream(destination, 1024);

        await bounded.WriteAsync(new byte[1024], TestContext.Current.CancellationToken);
        await Assert.ThrowsAsync<InvalidDataException>(() => bounded.WriteAsync(new byte[1], TestContext.Current.CancellationToken).AsTask());

        Assert.Equal(1024, destination.Length);
    }

    [Theory]
    [InlineData(0)]
    [InlineData(1024 * 1024 + 1)]
    public void Connection_options_reject_unbounded_output_limits(int maxOutputBytes)
    {
        Assert.Throws<ArgumentOutOfRangeException>(() => new SshConnectionOptions(
            "server.example",
            22,
            "steam",
            new SshPasswordCredential("secret"),
            HostKeyPin,
            TimeSpan.FromSeconds(30),
            maxOutputBytes));
    }

    [Fact]
    public void Credential_models_do_not_render_secret_material()
    {
        var firstMethod =
            new SshPasswordCredential("password-value");
        var key = new SshPrivateKeyCredential(Encoding.UTF8.GetBytes("PRIVATE-KEY-BYTES"), "key-passphrase");

        Assert.DoesNotContain("password-value", firstMethod.ToString(), StringComparison.Ordinal);
        Assert.DoesNotContain("PRIVATE-KEY-BYTES", key.ToString(), StringComparison.Ordinal);
        Assert.DoesNotContain("key-passphrase", key.ToString(), StringComparison.Ordinal);
    }

    [Fact]
    public void Disposed_credentials_cannot_create_authentication_material()
    {
        var firstMethod =
            new SshPasswordCredential("password-value");
        var key = new SshPrivateKeyCredential(Encoding.UTF8.GetBytes("PRIVATE-KEY-BYTES"), "key-passphrase");
        firstMethod.Dispose();
        key.Dispose();

        Assert.Throws<ObjectDisposedException>(() => firstMethod.CreateAuthenticationMethod("steam", []));
        Assert.Throws<ObjectDisposedException>(() => key.CreateAuthenticationMethod("steam", []));
    }

    private static SshConnectionOptions Options(string hostKeyPin) => new(
        "server.example",
        22,
        "steam",
        new SshPasswordCredential("secret"),
        hostKeyPin,
        TimeSpan.FromSeconds(30),
        64 * 1024);

    private static void AssertSuccess(BashResult result, string operation)
        => Assert.True(result.ExitCode == 0, result.Diagnostics(operation));

    private sealed record BashResult(int ExitCode, string StandardOutput, string StandardError)
    {
        public string Diagnostics(string operation)
            => $"Executable Bash fixture could not {operation}. Exit={ExitCode}; stdout={StandardOutput}; stderr={StandardError}";
    }

    private sealed class ExecutableBashFixture : IDisposable
    {
        private const int ProcessTimeoutMilliseconds = 15_000;
        private const int CleanupTimeoutMilliseconds = 5_000;
        private static readonly string[] RequiredCommands =
        [
            "awk", "chmod", "dirname", "ln", "mktemp", "mv", "od", "realpath", "rm", "stat", "sync", "tail", "tr", "truncate",
        ];

        private readonly string bashPath;
        private readonly DirectoryInfo root;
        private readonly string deploymentPath;
        private readonly string fixtureBinPath;

        public ExecutableBashFixture()
        {
            bashPath = FindBash();
            root = Directory.CreateTempSubdirectory("apollo-kill-switch-");
            deploymentPath = ToBashPath(root.FullName);
            fixtureBinPath = deploymentPath + "/fixture-bin";
            try
            {
                Directory.CreateDirectory(Path.Combine(root.FullName, "fixture-bin"));
                var probe = Run("for command_name in " + string.Join(' ', RequiredCommands) +
                    "; do command -v \"$command_name\" >/dev/null || { printf 'missing command: %s\\n' \"$command_name\" >&2; exit 97; }; done");
                if (probe.ExitCode != 0)
                {
                    throw new InvalidOperationException(probe.Diagnostics(
                        $"initialize; Bash and GNU-compatible fixture commands are required (bash={bashPath})"));
                }
            }
            catch
            {
                Dispose();
                throw;
            }
        }

        public void WriteEnvironment(string content) => WriteFile(".apollo-native.env", content);

        public void WriteFile(string name, string content)
            => File.WriteAllBytes(Path.Combine(root.FullName, name), Encoding.UTF8.GetBytes(content));

        public byte[] ReadEnvironment() => ReadFile(".apollo-native.env");

        public byte[] ReadFile(string name) => File.ReadAllBytes(Path.Combine(root.FullName, name));

        public FileInfo[] TemporaryFiles() => root.GetFiles(".apollo-native.env.tmp.*");

        public bool CanObservePosixModes(out string diagnostic)
        {
            WriteFile(".mode-probe", "mode");
            var probe = Run("chmod 0600 -- .mode-probe && stat -c %a -- .mode-probe");
            var observed = probe.StandardOutput.TrimEnd('\r', '\n');
            if (probe.ExitCode == 0 && observed == "600")
            {
                diagnostic = string.Empty;
                return true;
            }

            diagnostic =
                $"Executable Bash fixture cannot observe POSIX mode 0600 on this filesystem " +
                $"(bash={bashPath}, observed={observed}, exit={probe.ExitCode}). The rendered chmod ordering remains covered.";
            return false;
        }

        public BashResult ExecuteKillSwitch(string phase, bool prependFixtureTools = false)
        {
            var operation = RemoteOperation.SetKillSwitch(
                deploymentPath,
                [deploymentPath + "/docker-compose.yml"],
                "project-zomboid",
                phase);
            var pathPrefix = prependFixtureTools ? $"PATH={Quote(fixtureBinPath)}:$PATH\nexport PATH\n" : string.Empty;
            return RunRaw(pathPrefix + SshRemoteSession.RenderCommand(operation));
        }

        public void InstallSymlinkReturningMktemp()
        {
            CreateFileSymlink(".apollo-native.env.tmp.attacker", ".apollo-native.env");
            var nativePath = Path.Combine(root.FullName, "fixture-bin", "mktemp");
            File.WriteAllText(
                nativePath,
                "#!/bin/sh\nset -eu\nprintf '%s\\n' ./.apollo-native.env.tmp.attacker\n",
                new UTF8Encoding(false));
            var chmod = Run($"chmod 0700 -- {Quote(fixtureBinPath + "/mktemp")}");
            if (chmod.ExitCode != 0)
            {
                throw new InvalidOperationException(chmod.Diagnostics("make the fixture mktemp executable"));
            }
        }

        public void CreateFileSymlink(string linkName, string targetName)
        {
            var linkPath = Path.Combine(root.FullName, linkName);
            var targetPath = Path.Combine(root.FullName, targetName);
            try
            {
                File.CreateSymbolicLink(linkPath, targetPath);
            }
            catch (Exception exception) when (exception is IOException or UnauthorizedAccessException or PlatformNotSupportedException)
            {
                throw new InvalidOperationException(
                    $"Executable Bash fixture requires file symbolic-link creation, but this platform rejected '{linkPath}'.",
                    exception);
            }

            var probe = Run($"test -L {Quote(deploymentPath + "/" + linkName)}");
            if (probe.ExitCode != 0)
            {
                throw new InvalidOperationException(probe.Diagnostics(
                    $"recognize the platform-created symbolic link '{linkName}'"));
            }
        }

        public string Query(string command)
        {
            var result = Run(command);
            if (result.ExitCode != 0)
            {
                throw new InvalidOperationException(result.Diagnostics("query fixture state"));
            }

            return result.StandardOutput.TrimEnd('\r', '\n');
        }

        public BashResult Run(string command)
            => RunRaw($"set -eu\ncd -- {Quote(deploymentPath)}\n{command}");

        public void Dispose()
        {
            if (root.Exists)
            {
                root.Delete(recursive: true);
            }
        }

        private BashResult RunRaw(string script)
        {
            var startInfo = new ProcessStartInfo
            {
                FileName = bashPath,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
            };
            startInfo.ArgumentList.Add("--noprofile");
            startInfo.ArgumentList.Add("--norc");
            startInfo.ArgumentList.Add("-c");
            startInfo.ArgumentList.Add(script);

            Process? process = null;
            try
            {
                process = Process.Start(startInfo) ??
                    throw new InvalidOperationException($"Executable Bash fixture could not start {bashPath}.");
                var standardOutput = process.StandardOutput.ReadToEndAsync();
                var standardError = process.StandardError.ReadToEndAsync();
                if (!process.WaitForExit(ProcessTimeoutMilliseconds))
                {
                    Terminate(process, "process timeout");
                    throw new InvalidOperationException(
                        $"Executable Bash fixture exceeded its {ProcessTimeoutMilliseconds} ms process timeout and was terminated.");
                }

                if (!Task.WaitAll([standardOutput, standardError], CleanupTimeoutMilliseconds))
                {
                    Terminate(process, "output-drain timeout");
                    throw new InvalidOperationException(
                        $"Executable Bash fixture exceeded its {CleanupTimeoutMilliseconds} ms output-drain timeout and was terminated.");
                }

                return new BashResult(process.ExitCode, standardOutput.Result, standardError.Result);
            }
            catch (System.ComponentModel.Win32Exception exception)
            {
                throw new InvalidOperationException(
                    $"Executable Bash fixture requires Bash, but '{bashPath}' could not be started.",
                    exception);
            }
            finally
            {
                if (process is not null)
                {
                    TryTerminate(process);
                    process.Dispose();
                }
            }
        }

        private static void Terminate(Process process, string reason)
        {
            if (!process.HasExited)
            {
                process.Kill(entireProcessTree: true);
                if (!process.WaitForExit(CleanupTimeoutMilliseconds))
                {
                    throw new InvalidOperationException(
                        $"Executable Bash fixture did not terminate within {CleanupTimeoutMilliseconds} ms after {reason}.");
                }
            }
        }

        private static void TryTerminate(Process process)
        {
            try
            {
                Terminate(process, "fixture cleanup");
            }
            catch (InvalidOperationException)
            {
                // The primary test failure retains the explicit timeout diagnostic.
            }
        }

        private static string FindBash()
        {
            string[] candidates = OperatingSystem.IsWindows()
                ? [@"C:\Program Files\Git\bin\bash.exe", @"C:\Program Files\Git\usr\bin\bash.exe"]
                : ["/bin/bash", "/usr/bin/bash"];
            return candidates.FirstOrDefault(File.Exists) ?? "bash";
        }

        private static string ToBashPath(string nativePath)
        {
            var fullPath = Path.GetFullPath(nativePath);
            if (!OperatingSystem.IsWindows())
            {
                return fullPath;
            }

            return "/" + char.ToLowerInvariant(fullPath[0]) + fullPath[2..].Replace('\\', '/');
        }

        private static string Quote(string value) => "'" + value.Replace("'", "'\"'\"'", StringComparison.Ordinal) + "'";
    }

    private sealed class CountingNonSeekableStream(long remaining) : Stream
    {
        private long remaining = remaining;
        public long BytesRead { get; private set; }
        public override bool CanRead => true;
        public override bool CanSeek => false;
        public override bool CanWrite => false;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }

        public override int Read(byte[] buffer, int offset, int count)
        {
            var actual = (int)Math.Min(count, remaining);
            Array.Clear(buffer, offset, actual);
            remaining -= actual;
            BytesRead += actual;
            return actual;
        }

        public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
        {
            var actual = (int)Math.Min(buffer.Length, remaining);
            buffer[..actual].Span.Clear();
            remaining -= actual;
            BytesRead += actual;
            return ValueTask.FromResult(actual);
        }

        public override void Flush() { }
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    }
}
