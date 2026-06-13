# Multiplayer v2 — Implementation Context for Subagents

Read this once before starting your assigned stage. The **full design** is in
[`docs/multiplayer-v2.md`](multiplayer-v2.md) — read the sections relevant to your stage
(especially §2 goals, §5 protocol, §6 turn models, §10 phases, §11 open questions). Don't
duplicate that doc; this file is just the working context.

## What you are building

A **fast authoritative multiplayer**: clients send small **commands** (player intents); the
single **authority** validates + applies them to the canonical `GameInfo` and streams back
**per-player, visibility-filtered deltas**. Two co-equal authority modes (same `GameSession`):
**dedicated server** and **anyone-can-host via the relay**.

Two principles that constrain every stage:
- **No determinism requirement.** Only the authority simulates; clients never replay the world,
  so there is nothing to keep deterministic for convergence. Don't add checksum/replay machinery.
- **Hidden information by construction.** A client must only ever *receive* state it may legally
  see. Never put fogged/enemy/unseen state on the wire to a client.

## Module / package map

| Where | Path | What |
|---|---|---|
| `:network` | `network/src/com/unciv/network/` | Pure wire protocol, **NO engine deps**. `Protocol.kt` (version + id typealiases), `relay/RelayMessages.kt` (envelopes), `game/GameFrame.kt` (authority↔client frames), `command/GameCommand.kt` (intents), `serialization/RelayJson.kt` (shared `relayJson`). |
| `:core` v2 | `core/src/com/unciv/logic/multiplayer/v2/` | Engine side. `command/CommandExecutor.kt`, `session/GameSession.kt` (the authority loop), `visibility/PlayerViewProjector.kt` (anti-maphack projection), `client/GameInfoCodec.kt` + `client/ClientGameView.kt` (snapshot decode + view holder), `transport/RelayTransport.kt` + `WebSocketRelayTransport.kt`. |
| `:server` | `server/src/com/unciv/app/server/` | `RelayServer.kt` + the `/relay` route wired in `UncivServer.kt`. |
| `:tests` | `tests/src/com/unciv/logic/multiplayer/v2/` | `RelayIntegrationTest.kt`. Test scaffolding for building a `GameInfo` lives in `tests/src/.../testing/` (e.g. `TestGame`) — use it. |

## Conventions (match the existing code, don't invent)

- Kotlin; sources under `src/`; JVM 1.8 target; 4-space indent.
- Wire types: `@Serializable sealed interface` + a stable `@SerialName("...")` per subtype,
  serialized via the shared `relayJson`. **Additive only** — never rename/repurpose a SerialName.
- `:network` stays **engine-independent** — it may not import anything from `com.unciv` outside
  `com.unciv.network`.
- `:core` exposes `:network` via `api`. The authority mutates `GameInfo` **only** through
  `CommandExecutor` — it is the single choke-point.
- Ordered collections (`LinkedHashMap`) are good hygiene but not a correctness requirement.
- Do **not** touch the APIv0/APIv1 PBEM paths — they remain the default; v2 is additive and
  feature-flagged.

## Build & test (Windows, gradle wrapper, JDK present; daemon is warm)

```
./gradlew :network:build :server:build :core:compileKotlin
./gradlew :tests:test --tests 'com.unciv.logic.multiplayer.v2.*'
```

- The `:core` compile is heavy (minutes) — expect it. Don't run `desktop:dist` unless asked.
- **Leave the build green and the v2 tests passing.** That is the bar for "done".

## Current status

- **Phase 0 ✓** — protocol skeleton + `core` v2 stubs.
- **Phase 1 ✓** — relay (`RelayServer`) + client transport (`WebSocketRelayTransport`);
  `RelayIntegrationTest` passes. Host migration / `HostChanged` **removed** (deferred to Phase 7) —
  do not reintroduce it.
- **Phase 2 ✓** — `CommandExecutor` applies `MoveUnit` to the canonical `GameInfo`; illegal commands
  throw `CommandException`. It is the sole mutation choke-point (`EndTurn` is handled by the session,
  not the executor).
- **Phase 3 ✓** — command-in / filtered-snapshot-out, sequential play, both authority modes:
  - `PlayerViewProjector.projectFor(gameInfo, civId)` returns a redacted deep copy (fogged enemy
    units, undiscovered cities, unseen camps removed; enemy secrets / seen-city interiors / unexplored
    tile contents scrubbed). It rebuilds **only** `tileMap` transients on the clone (no full
    `GameInfo.setTransients()`), so anything it removes must leave **no dangling reference** — the
    client runs full `setTransients()` on decode.
  - `GameSession(gameInfo, roster, outbound)`: `onFrame` applies `PlayerCommand`s via the executor
    (rejection → directed `CommandRejected`); `EndTurn` → `nextTurn()` + a per-player `PlayerView`
    pushed through the injected `(PlayerId, GameFrame) -> Unit` sink. Transport-agnostic.
  - `GameInfoCodec` (JSON+gzip ↔ `GameInfo` via the engine's own `gameInfoToString`/`gameInfoFromString`)
    and `ClientGameView` (decodes a `PlayerView`, holds the latest filtered `GameInfo`).
  - **Delta granularity (open, §11):** Phase 3 ships *full per-player filtered snapshots*, which
    satisfy delta-out functionally. True semantic `StateDelta` patches are a later bandwidth
    optimization and need a format decision — do **not** invent one inside another stage.
- **Frames now in `GameFrame.kt`:** added `PlayerView`, `CommandRejected`; `RelayTo` added to
  `ClientToRelay`. Still-provisional leftovers from the abandoned lockstep framing: `ResolvedTurn`,
  `StateCheckpoint`, `ChecksumReport`, `ResyncGrant`, and `TurnSubmission`/`ResyncRequest`. Phase 5
  uses `TurnSubmission`; Phase 6 uses `ResyncRequest` + `PlayerView`. Don't add checksum/replay frames.

## Test-harness gotchas (TestGame + GdxTestRunner) — save yourself the debugging

These bit Phase 3 tests; the runner gives a **non-null headless `Gdx.app`** but leaves several
`UncivGame.Current` lateinits uninitialized, so production guards like `if (Gdx.app != null)` don't
protect the test:
- **`nextTurn()` music crash.** `GameInfo.nextTurn()` calls `UncivGame.Current.musicController` when
  `turns % 10 == 0`. A fresh `TestGame` starts at `turns = 0`. Set `gameInfo.turns = 1` before driving
  a turn (a real running game is past turn 0 anyway).
- **`MissingNationException` on decode.** Full `setTransients()` (run by `gameInfoFromString` on the
  client) re-resolves each civ's nation **by name from `RulesetCache`**. `TestGame.addCiv()` synthesises
  ad-hoc `Nation-0` that lives only in TestGame's cloned ruleset → decode throws. For round-trip tests
  use real major civs: `testGame.ruleset.nations.values.filter { it.isMajorCiv }.take(n)` passed to the
  `addCiv(nation, …)` overload.
- **`files`/tutorial-save crash.** Founding a city (or anything that makes civs *meet*) completes a
  tutorial task → `settings.save()` → needs `UncivGame.Current.files`. Init it once in `@Before`:
  `UncivGame.Current.files = UncivFiles(Gdx.files)` (as `GameSerializationTests` does).

## Your rules as a stage implementer

1. Implement **only your assigned stage** (see the §10 phase table). No scope creep into later
   phases.
2. Add/extend a **test** that proves your stage's "Verifiable by" cell.
3. **Verify** it compiles and the relevant v2 test passes before you report. Never report success
   without having run the build/test.
4. Keep `:network` engine-free; keep hidden-information and no-determinism principles.
5. If you hit an **open design question** (§11) — e.g. how a command identifies a unit, delta
   granularity — make the *smallest reasonable* decision, implement it, and **flag it explicitly**
   in your report so the orchestrator can confirm. Don't silently guess on a fork that affects
   later stages.
6. Report back concisely: files added/changed, how you verified (command + result), and any
   assumption/decision made.
