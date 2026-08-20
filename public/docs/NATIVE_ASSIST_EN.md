# Apollo Native Assist administrator guide

## 1. Scope

Native Assist is an optional server-only companion for Apollo MP Sync. Its command-free Windows x64 graphical installer supports **Docker Compose over SSH** only. Administrators do not manually edit Compose or enter shell, Docker, or Java commands. Clients never install Java or Native Assist.

Coolify installation is not available because the current API cannot safely make an atomic conditional Compose update. No Coolify changes are attempted, and the installer has no Coolify token, connector, or hidden mode.

Download the setup executable from the [latest public GitHub Release](https://github.com/ALTIS13/Apollo.PZ-MP-Sync/releases/latest). The client mod remains available from the [Steam Workshop item](https://steamcommunity.com/sharedfiles/filedetails/?id=3780069702).

## 2. Exact supported server

Native Assist can become active only when every value matches:

| Field | Required value |
| --- | --- |
| Steam dedicated-server app | `380870` |
| Dedicated-server BuildID | `24775771` |
| Project Zomboid version | `42.20.3` |
| Server Java feature | `25` |
| Server operating system | `linux` |
| Server architecture | `amd64` |
| Container image digest | `sha256:5e3479ea2ef66a4f14686fd3abc3286cf31a82c0e37f737b4b5976ff37da9951` |

The installer also requires the exact reviewed image, ENTRYPOINT, null CMD, service, container, mounts, networks, ports, restart policy, server JAR, native libraries, agent, and fingerprint. Unknown or changed evidence fails closed before mutation.

## 3. Connection fields

Open the setup executable and leave the server mode as **Docker Compose over SSH**. Enter these GUI fields:

- **SSH host** and **SSH port**;
- **SSH username**;
- authentication mode: **Private key** with a private-key file and optional passphrase, or **Password** only when deliberately selected;
- absolute **deployment directory**;
- one to eight unique relative **Compose files**, all inside that deployment directory;
- the **exact service name** for Project Zomboid.

Credentials remain in memory only, are cleared at terminal paths, and are never added to logs, backups, recovery metadata, or release files.

## 4. Verify the SSH host key

Select **Check server**. The first connection is a credential-free SSH host-key observation: no password, passphrase, or private key is read or sent. The installer displays the key algorithm and unpadded **SHA-256 fingerprint**.

Compare the fingerprint through a trusted independent channel. Explicitly confirm **Trust and continue** only when it matches. Rejection, missing evidence, duplicate or changed keys, cancellation, or timeout stops before authenticated SSH. Authentication is pinned to the confirmed fingerprint and fails closed if the key changes.

## 5. Preview and restart confirmation

After authentication, the installer performs read-only discovery and exact compatibility checks. A supported **preview** lists the backup, companion files, environment and override writes, disabled restart and vanilla verification, enabled restart and READY verification, and rollback availability. The preview contains display targets, not commands, credentials, environment values, or captured Compose bytes.

Review every planned write and restart. Installation stays disabled until you select the separate **restart confirmation** checkbox that approves the listed service restarts.

## 6. Install or update

Select **Install or update**. The installer rechecks the server, creates an exact backup before the first mutation, stages Native Assist with its kill switch off, validates the merged Compose model, recreates the selected service, and requires vanilla `SERVER STARTED` without Native Assist markers.

It then enables the kill switch, recreates the same service once more, and verifies both `SERVER STARTED` and the exact `READY/hooks-armed` Native Assist state with zero advice errors. Both success markers are required before the installer reports readiness.

If cancellation or failure occurs after backup, the installer makes exactly one rollback attempt with a non-cancelled recovery token. A failure before backup performs no rollback because no server state was changed.

## 7. Safe disable

Choose **Safe disable** to keep the installed files and recovery record while turning the closed kill switch off. The installer obtains a fresh server snapshot, changes only `APOLLO_NATIVE_ASSIST=off`, recreates the selected service, and verifies vanilla `SERVER STARTED` with no `READY/hooks-armed` or Java-agent marker.

After the off value is written, cancellation cannot interrupt the restart and vanilla verification. If either check fails, the installer reports a fixed failure state and does not silently re-enable Native Assist.

## 8. Full rollback and recovery

Choose **Full rollback** to restore the exact pre-install state recorded by the backup. This restores absent, partial, or complete prior Apollo files and the original Compose model, then recreates the selected service exactly once and verifies vanilla `SERVER STARTED`.

The non-secret recovery record is stored locally so the GUI can offer recovery after an application restart. Reauthentication and the same verified server identity are required. Restore uses exactly one rollback attempt; an invalid record or backup path fails closed without guessing or retrying another target.

Automatic post-backup recovery and explicit full rollback never expose the backup path, raw Compose, commands, credentials, or exception text in the visible log.

## 9. Fail-closed behavior and support

Only `READY/hooks-armed` activates Native Assist decisions. `ABSENT`, `DISABLED`, `INCOMPATIBLE`, and `CIRCUIT_OPEN` preserve vanilla Project Zomboid networking and the supported Apollo Lua behavior. A missing or mismatched runtime, fingerprint, library, hook, readiness marker, or container invariant never enables partial native behavior.

Native Assist cannot eliminate latency, make every physics simulation identical, or guarantee compatibility with another mod that replaces the same actions. Review the [compatibility boundary](COMPATIBILITY.md) and report issues in the [public repository](https://github.com/ALTIS13/Apollo.PZ-MP-Sync).
