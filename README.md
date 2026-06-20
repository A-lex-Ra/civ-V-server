# Unciv — Authoritative Multiplayer (v3) + Brave New World fork

A fork of [Unciv](https://github.com/yairm210/Unciv) (the open-source, Civ-V-like
4X strategy game — Kotlin + libGDX). On top of the base game this fork adds two
things:

1. **Authoritative multiplayer-v3 netcode** — a *command-in / filtered-view-out*
   model over a WebSocket relay. One process is the **authority** and owns the
   canonical `GameInfo`; clients send `GameCommand` intents and receive
   per-player, **visibility-filtered** `PlayerView` snapshots (gzipped
   `GameInfo`). `PlayerId == UserId`. The host is also a client of its own
   authority (in-process loopback) and renders its own filtered view, not the
   canonical state.
2. **Brave New World content & mechanics** — Ideologies, Tourism, Great Works,
   International Trade Routes, World Congress and more — adopted partly as
   *ruleset data* from the [RobLoach `Civ-V-Brave-New-World`](https://github.com/RobLoach/Civ-V-Brave-New-World)
   mod and partly **reimplemented in Kotlin** for fidelity under the v3
   authority/command model.

> This is a personal fork. It is **not** affiliated with the upstream Unciv
> project or with Firaxis. The original upstream project README (install links,
> roadmap, FAQ, legal note) is preserved verbatim at
> [README.upstream.md](README.upstream.md).

## Multiplayer-v3 at a glance

| Module | Role |
|---|---|
| `:core` | game engine + UI + v3 **client** logic (`core/src/com/unciv/logic/multiplayer/v3/`) |
| `:network` | engine-free v3 **wire protocol** (relay envelopes, `GameFrame`, `GameCommand`) |
| `:server` | `UncivServer` (legacy v1 file-store + chat) and the Kotlin `RelayServer` (`/relay`) |
| `:desktop` / `:android` | platform launchers |
| `:tests` | test suite |
| `server/relay-node/` | lightweight, JVM-free Node reimplementation of the relay |

Design docs live in [`docs/multiplayer-v3*.md`](docs/) and
[`docs/brave-new-world-adoption.md`](docs/brave-new-world-adoption.md).

## Building the desktop jar

```
./gradlew.bat :desktop:dist        # -> desktop/build/libs/Unciv.jar (self-contained fat jar)
```

Requires Java 21+ to run. A running client **locks** `Unciv.jar` on Windows —
close any clients before rebuilding.

## Running a v3 game across two clients (one machine)

Each instance MUST use its own data dir via `--data-dir=` — separate settings
mean distinct multiplayer **User IDs**, which v3 requires (`PlayerId == UserId`).

```powershell
$jar  = "desktop\build\libs\Unciv.jar"
$java = "C:\Program Files\Java\jdk-22\bin\java.exe"   # any JRE/JDK 21+
$a = Join-Path $env:TEMP "unciv-v3-A"; $b = Join-Path $env:TEMP "unciv-v3-B"
New-Item -ItemType Directory -Force $a, $b | Out-Null
Start-Process $java -ArgumentList @("-jar", $jar, "--data-dir=$a") -WorkingDirectory $a
Start-Process $java -ArgumentList @("-jar", $jar, "--data-dir=$b") -WorkingDirectory $b
```

1. Client **B**: Options → Multiplayer → copy its **User ID**.
2. Client **A**: New Game → enable *Authoritative Multiplayer (experimental)* →
   add a second human player, set its ID to B's User ID → Start. The in-game
   (hamburger) menu shows **Room ID: …**.
3. Client **B**: Multiplayer → *Join experimental game (v2)* → paste the Room ID
   → Join.
4. Resume: saving on the host then Load-ing that save **re-hosts** it as the
   authority (new Room ID).

### Relay

The wire protocol is served by a small WebSocket relay (`/relay`). A reference
deployment runs the JVM-free Node relay from
[`server/relay-node/`](server/relay-node/); the client's default server URL
points at it, so no local relay is needed for normal testing.

## Brave New World

Most BNW content (civs, units, buildings, wonders, beliefs, techs, archaeology)
is reused **as data** from the RobLoach mod, which serializes and projects
through multiplayer-v3 for free. Systems that the mod could only *fake* in data —
Tourism with influence levels, real Great Works, Ideologies with public
opinion, World Congress, international trade-route yields — are reimplemented in
Kotlin. See [`docs/brave-new-world-adoption.md`](docs/brave-new-world-adoption.md)
for the tier-by-tier plan and current status.

## Licensing

This repository is **dual-licensed by file** — see [LICENSING.md](LICENSING.md)
for the exact map:

- **Mozilla Public License 2.0** ([LICENSE](LICENSE)) — all upstream Unciv code
  and this fork's modifications to it, plus the bundled Brave New World ruleset
  data (adopted from the RobLoach mod, itself MPL-2.0).
- **View-Only License** ([LICENSE.v3](LICENSE.v3)) — this fork's **original
  multiplayer-v3 netcode**: `core/src/com/unciv/logic/multiplayer/v3/`, the
  `:network` module, `server/.../RelayServer.kt`, and `server/relay-node/`.
  These files carry the SPDX tag `LicenseRef-Unciv-v3-ViewOnly`. They are
  published for reference; **permission to use, modify, or distribute them is
  granted on request** — email rastorguev2047@gmail.com or open a GitHub issue.

## Credits

- Built on [Unciv](https://github.com/yairm210/Unciv) by **yairm210** and the
  Unciv contributors (MPL-2.0).
- Brave New World content adopted from the
  [RobLoach `Civ-V-Brave-New-World`](https://github.com/RobLoach/Civ-V-Brave-New-World)
  mod (MPL-2.0), which builds on the original BNW mod by
  [**ravignir**](https://github.com/ravignir) with contributions from
  [**chris03-dev**](https://codeberg.org/chris03-dev) and
  [**RobLoach**](https://github.com/RobLoach).
- Multiplayer-v3 netcode and the BNW Kotlin reimplementation by **Alexander
  Rastorguev (A-lex-Ra)**.

Full art/icon/music/audio attribution is in [docs/Credits.md](docs/Credits.md).
