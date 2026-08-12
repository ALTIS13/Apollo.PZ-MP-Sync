# Apollo MP Sync

Apollo MP Sync improves the presentation of supported multiplayer actions in Project Zomboid `42.20.2` while leaving movement, physics, zombies, anti-cheat, relay, and damage under the vanilla server's authority.

Players use the [Steam Workshop item](https://steamcommunity.com/sharedfiles/filedetails/?id=3780069702). Server administrators may optionally add Native Assist with the command-free Windows installer from the [latest public GitHub Release](https://github.com/ALTIS13/Apollo.PZ-MP-Sync/releases/latest).

## Current functionality

- The Workshop mod synchronizes supported action presentation such as reading, sitting, rest and sleep, climbing, floor transitions, vehicle seats, and timed-action progress.
- Native Assist adds bounded server-side validation for supported PvP and PvE hits and nearby zombie handoffs on the exact supported runtime.
- Native Assist is optional, server-only, and fail-closed. A runtime, fingerprint, hook, or readiness mismatch preserves the Workshop/Lua features and vanilla networking.
- The Windows x64 graphical installer supports Docker Compose over SSH. It previews every planned write and restart, installs or updates, safely disables the companion, and restores the exact pre-install state.
- Coolify installation is not available because its current API cannot safely make an atomic conditional Compose update. No Coolify changes are attempted.

## Install

- Players: [Client installation in English](public/docs/CLIENT_INSTALL_EN.md) or [Russian](public/docs/CLIENT_INSTALL_RU.md).
- Docker Compose server administrators: [Native Assist guide in English](public/docs/NATIVE_ASSIST_EN.md) or [Russian](public/docs/NATIVE_ASSIST_RU.md).
- Runtime boundary and known limits: [Compatibility](public/docs/COMPATIBILITY.md).

The Workshop item is `3780069702`; the Mod ID is `ApolloMPSyncB42`. Clients receive only the Lua mod and its assets. Clients never install Java or Native Assist.

## Source, support, and license

Source and issue tracking are at [ALTIS13/Apollo.PZ-MP-Sync](https://github.com/ALTIS13/Apollo.PZ-MP-Sync). Release builds use Node.js 24, Java 25, and .NET 8 and are verified from the checked-in workflows. Apollo MP Sync is an independent clean-room implementation released under the [MIT License](LICENSE).
