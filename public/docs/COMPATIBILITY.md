# Apollo MP Sync compatibility

## Supported scope

Apollo MP Sync Workshop `0.2.1` supports Project Zomboid `42.20.x`. The optional server-side Native Assist release is `0.2.2`. The Workshop component is [item `3780069702`](https://steamcommunity.com/sharedfiles/filedetails/?id=3780069702) with Mod ID `ApolloMPSyncB42`; clients do not install Native Assist.

Native Assist is optional and supports exactly this server tuple:

| Field | Required value |
| --- | --- |
| Steam dedicated-server app | `380870` |
| Dedicated-server BuildID | `24775771` |
| Game version | `42.20.3` |
| Java feature | `25` |
| Operating system | `linux` |
| Architecture | `amd64` |
| Container image digest | `sha256:5e3479ea2ef66a4f14686fd3abc3286cf31a82c0e37f737b4b5976ff37da9951` |

The installer supports Docker Compose over SSH. Coolify and local Windows dedicated servers are not supported installation targets in this release.

## Unsupported versions and environments

Build 41 and Build 42.21+ are not supported by the Workshop release. A different dedicated-server BuildID, Java feature, operating system, architecture, image digest, server JAR, native-library set, launcher, or fingerprint is incompatible with Native Assist even when the Lua/Workshop component still accepts the 42.20.x client family.

Clients use the Workshop mod on supported servers and do not need the server runtime tuple or Java.

## Authority and fail-closed fallback

Project Zomboid remains authoritative for movement, vehicle and trailer physics, zombies, anti-cheat, relay, and damage. Apollo Lua synchronizes supported presentation semantics and does not directly set authoritative coordinates or apply damage.

Only `READY` with reason `hooks-armed` enables Native Assist decisions. `ABSENT`, `DISABLED`, `INCOMPATIBLE`, and `CIRCUIT_OPEN` preserve vanilla networking and the supported Lua behavior. A failed check does not select a nearby runtime or partially enable hooks.

## Known limits

- Apollo cannot eliminate latency, packet loss, vanilla interpolation, or client-side physics differences.
- Native rewind is bounded and never extrapolates or directly corrects coordinates.
- Cross-floor, blocked-line-of-sight, unloaded-area, stale, high-RTT, and high-jitter cases fall back to strict-current or vanilla handling.
- Compatibility is not guaranteed with mods that replace the same actions, hit processing, vehicle behavior, or zombie ownership logic.
- Native Assist supports `linux/amd64` Docker Compose servers only; it is not a general Java mod loader or a client component.

## Links

Use the [latest public GitHub Release](https://github.com/ALTIS13/Apollo.PZ-MP-Sync/releases/latest) for the Windows installer and immutable checksums. Source and issue tracking are in the [public repository](https://github.com/ALTIS13/Apollo.PZ-MP-Sync).
