# CLAUDE.md — civ-V-server (Unciv fork)

## What this project is
A fork of **Unciv** (open-source Civ-V-like 4X strategy game, Kotlin + libgdx) that adds an
authoritative **multiplayer-v3** netcode: *command-in / filtered-view-out* over a WebSocket relay.
One process is the **authority** and owns the canonical `GameInfo`; clients send `GameCommand`
intents and receive per-player, visibility-filtered `PlayerView` snapshots (gzipped GameInfo).
`PlayerId == UserId`. The host client is also a client of its own authority (in-process loopback,
"option A") and renders its own filtered view, not the canonical state.

Modules:
- `:core` — game engine + UI + v3 **client** logic (`core/src/com/unciv/logic/multiplayer/v3/`)
- `:network` — engine-free v3 wire protocol (relay envelopes, `GameFrame`, `GameCommand`)
- `:server` — `UncivServer` (legacy v1 file-store + chat) and the Kotlin `RelayServer` (`/relay`)
- `:desktop` / `:android` — platform launchers · `:tests` — test suite
- `server/relay-node/` — lightweight, JVM-free Node reimplementation of the relay

Design docs: `docs/multiplayer-v3*.md`.

## Relay — already running on the VPS (do NOT start one locally)
The relay is deployed and live at **`http://45.13.238.70:8080`** (the client rewrites this to
`ws://45.13.238.70:8080/relay`). It is the lightweight Node relay from `server/relay-node/`, running
under systemd unit **`unciv-relay`** on `root@45.13.238.70`. The client default server URL
(`Constants.uncivXyzServer`) already points there, so **no local relay is needed** for testing.
- Manage: `ssh root@45.13.238.70 'systemctl status unciv-relay'` (or `restart`); logs:
  `journalctl -u unciv-relay -f`. Health: `curl http://45.13.238.70:8080/healthz`.

## Build the desktop jar
```
./gradlew.bat :desktop:dist        # -> desktop/build/libs/Unciv.jar (self-contained fat jar)
```
A running client **locks** `Unciv.jar` on Windows — close any clients before rebuilding.
Requires Java 21+ to run (this machine has JDK 22 at `C:\Program Files\Java\jdk-22`).

## Run two parallel clients (v3 multiplayer test on one machine)
Each instance MUST use its own data dir via `--data-dir=`: separate settings → distinct multiplayer
**User IDs**. This is required because v3 has `PlayerId == UserId` — two clients sharing a data dir
share one identity and cannot be two different players. Both fresh dirs default to the VPS relay.

```powershell
$jar  = "D:\civ-V-server\desktop\build\libs\Unciv.jar"
$java = "C:\Program Files\Java\jdk-22\bin\java.exe"   # any JRE/JDK 21+
$a = Join-Path $env:TEMP "unciv-v3-A"; $b = Join-Path $env:TEMP "unciv-v3-B"
New-Item -ItemType Directory -Force $a, $b | Out-Null
Start-Process $java -ArgumentList @("-jar", $jar, "--data-dir=$a") -WorkingDirectory $a
Start-Process $java -ArgumentList @("-jar", $jar, "--data-dir=$b") -WorkingDirectory $b
```

Playing a v3 game across the two clients:
1. Client **B**: Options → Multiplayer → copy its **User ID**.
2. Client **A**: New Game → enable "Authoritative Multiplayer (experimental)" → add a second human
   player and set its ID to B's User ID → Start. The in-game (hamburger) menu shows **Room ID: …**.
3. Client **B**: Multiplayer → "Join experimental game (v2)" → paste the Room ID → Join.
4. Resume: saving on the host then Load-ing that save **re-hosts** it as the authority (new Room ID).
