# Multiplayer v2 — Authoritative, Command-Based, Relay-Hosted

> Status: **Design / RFC**. Nothing here changes existing behaviour yet.
> The existing PBEM multiplayer (APIv0 Dropbox, APIv1 UncivServer file-store) stays
> fully functional and is the fallback until v2 reaches feature parity.

## 1. Why

Today's multiplayer is **play-by-email (PBEM)**:

- The server (`server/src/com/unciv/app/server/UncivServer.kt`) is a *dumb file store*:
  `PUT /files/{name}` / `GET /files/{name}` plus optional basic-auth and a chat WebSocket.
  It contains **zero** game logic.
- After a turn, the client serialises the **entire** `GameInfo` (zipped) and uploads it
  (`MultiplayerServer.uploadGame`), plus a small `_Preview` blob.
- Other clients **poll** the preview (throttled loop in `Multiplayer.kt`, ~every 500 ms)
  and download the full game when it's their turn.
- Play is **strictly sequential**: only `GameInfo.currentPlayer` may act; everyone else waits.

Consequences: high latency, large per-turn uploads in the late game, no simultaneous play,
no real authority, and no way for "any player to host" without running a server.

## 2. Goals

1. **Authoritative simulation** — one party owns the canonical `GameInfo`; clients cannot
   forge illegal state.
2. **Command/delta protocol (Phase B)** — transmit *player actions*, not whole-state blobs.
3. **Simultaneous turns (Phase C)** — all human players act within the same turn; the
   authority orders/resolves and broadcasts the result.
4. **Anyone can host, via relay** — a player's client can *be* the authority; our public
   server only **relays** traffic between participants (no NAT setup, no game logic on the
   relay → cheap and scalable). The same authority code can also run as a dedicated server.
5. **Host migration** — if the host drops, another participant seamlessly takes over.
6. **Future-proof** — versioned protocol, additive command set, runs the *same* `GameSession`
   in client-host and dedicated-server modes.

## 3. Why this is feasible here (determinism audit)

Command-replay netcode requires the simulation to be **deterministic** given identical
starting state + identical ordered inputs. Unciv is already close:

- RNG is **state-derived**, not a global mutable PRNG:
  `GameContext.stateBasedRandom(caller, seed) = Random(hashOf(caller.hashCode(), seed, this.hashCode()))`
  (`core/.../ruleset/unique/GameContext.kt`). Reproducible from state alone. Used pervasively
  in combat (`Battle.takeDamage`, `Nuke`, `AirInterception`), quests, city-states, diplomacy.
- The remaining ad-hoc spots are still pure functions of state:
  - `BattleDamage`/`BattleUnitCapture`: `Random(turns * tile.position.hashCode())`
  - `ReligionManager`: `Random(gameInfo.turns)`
  - `RuinsManager`: `Random(tile.position.hashCode())`
  These are deterministic, just less robust; we may later route them through
  `stateBasedRandom` for consistency.
- `SecureRandom` is only used for `randomGameId()` — *not* part of turn simulation.

**Known determinism risks to control:**

- `HashMap`/`HashSet` **iteration order**. Safe only if construction order is identical on
  every peer. Replay reconstructs identical order; for newly authored code prefer ordered
  collections (`LinkedHashMap`) on any path that feeds RNG or command ordering.
- **Floating point.** JVM IEEE-754 ops are consistent across desktop/Android for the basic
  arithmetic used here; avoid `StrictMath`-sensitive surprises by keeping new logic simple.
- **Wall-clock.** `GameInfo.currentTurnStartTime = System.currentTimeMillis()` and similar
  must be **excluded from the state checksum** and never feed RNG.

A determinism test harness (Phase 2) makes these guarantees enforceable in CI.

## 4. Architecture overview

```
            ┌─────────────────────────── PUBLIC RELAY (our server) ───────────────────────────┐
            │  Room registry (by gameId)         Pure message routing — NO game logic          │
            │  /relay  (WebSocket)               host election / migration / presence / auth    │
            └───────▲───────────────▲───────────────────────────────▲──────────────────────────┘
                    │               │                               │
            (client→host cmds) (host→all results)            (client→host cmds)
                    │               │                               │
        ┌───────────┴─────┐   ┌─────┴───────────┐           ┌───────┴─────────┐
        │  HOST client    │   │  Player client  │   ...     │  Player client  │
        │  GameSession    │   │  (replica)      │           │  (replica)      │
        │  = AUTHORITY    │   │                 │           │                 │
        │  canonical      │   │  predicts +     │           │  predicts +     │
        │  GameInfo       │   │  reconciles     │           │  reconciles     │
        └─────────────────┘   └─────────────────┘           └─────────────────┘

   Dedicated-server mode: the same GameSession runs in the server process instead of a client.
```

### Layers

| Layer | Module | Responsibility |
|------|--------|----------------|
| **Protocol** | `:network` (new, pure Kotlin + kotlinx.serialization) | Wire types shared by client & relay: envelopes, room messages, `PlayerCommand`, `ResolvedTurn`, `StateCheckpoint`, checksums, protocol version. **No engine deps.** |
| **Command** | `core` → `logic/multiplayer/v2/command` | `GameCommand` sealed hierarchy + `CommandExecutor`: the single choke-point that mutates `GameInfo` in MP. |
| **Authority/Session** | `core` → `logic/multiplayer/v2/session` | `GameSession`: collects submissions, resolves a turn deterministically, emits results + checksum, handles resync. Runs in client-host **or** dedicated server. |
| **Transport** | `core` → `logic/multiplayer/v2/transport` (client) and `server` (relay) | WebSocket client to the relay; relay rooms/routing/host-election on the server. |
| **Relay** | `server` | Room-based WebSocket relay added alongside existing `/files` + `/chat` (kept for back-compat). |

## 5. Protocol sketch (`:network`)

All messages are `@Serializable` sealed hierarchies with a `type` discriminator. A
`PROTOCOL_VERSION` is negotiated on connect; unknown command subtypes are rejected gracefully.

```
// Relay envelopes (relay only inspects routing/membership, never game content)
ClientToRelay: Hello(protocolVersion, userId, auth) | CreateRoom(gameId) | JoinRoom(roomId)
             | LeaveRoom | Relay(payload: GameFrame)  // opaque to relay
RelayToClient: Welcome(roomId, role, peers) | PeerJoined/PeerLeft | HostChanged(newHostId)
             | Relayed(fromId, payload) | Error(code, msg)

// Game frames (authority <-> clients), carried opaquely inside Relay/Relayed
GameFrame:
  - PlayerCommand(seq, playerId, command: GameCommand)      // client -> host
  - TurnSubmission(turn, playerId, commands: List<GameCommand>, done: Bool)  // simultaneous turns
  - ResolvedTurn(turn, orderedCommands, postChecksum)       // host -> all
  - StateCheckpoint(turn, compatVersion, gzippedGameInfo)   // host -> joining/desynced peer
  - ChecksumReport(turn, checksum)                          // client -> host (drift detection)
  - ResyncRequest / ResyncGrant
```

### `GameCommand` (sealed, additive)

One subtype per player-initiated mutation, e.g.:
`MoveUnit`, `AttackUnit`, `FoundCity`, `SetCityProduction`, `BuyConstruction`, `BuyTile`,
`PromoteUnit`, `GenericUnitAction`, `ChooseTech`, `AdoptPolicy`, `DiplomacyAction`,
`AcceptTrade`, `EndTurn`, … Each carries only the *intent* (ids + target), not resulting state.

## 6. Turn models

### Sequential (Phase 3) — replaces the file-store path, behind a flag
Commands stream to the host as the active player acts; host validates + applies to canonical
`GameInfo`, broadcasts `ResolvedTurn`. Replicas apply the same ordered commands → converge.
`EndTurn` triggers `GameInfo.nextTurn()` on the host (inter-turn processing stays host-side),
then a fresh checksum + (periodically) a `StateCheckpoint`.

### Simultaneous (Phase 5) — the real prize
1. All human players act **at once**; each client executes its own commands locally
   (client-side prediction) for responsiveness and streams them as `TurnSubmission`.
2. When every player marks `done` (or a per-turn timer fires), the host **resolves**:
   deterministic ordering (e.g., by `(submissionIndex, playerId, seq)`), conflict rules
   (two units onto one tile, simultaneous combat ordering, contested city capture), then
   applies the canonical sequence and runs inter-turn processing.
3. Host broadcasts `ResolvedTurn`; clients **roll back** prediction and re-apply canonical
   order. `ChecksumReport` confirms convergence; mismatch → `StateCheckpoint` resync.

## 7. Hosting & relay ("anyone can host")

- The relay assigns the **room creator** as initial host (`role = HOST`). Any client can be a
  host because hosting = running `GameSession` locally; the relay solves NAT/connectivity by
  having everyone connect *out* to the public server.
- The relay never sees game semantics — it routes opaque `GameFrame` payloads and manages
  membership, presence, and host role. This keeps it stateless-ish and cheap.
- **Host migration:** every replica holds an up-to-date authoritative `GameInfo` (resolved
  turns + checkpoints). On host disconnect, the relay elects the peer with the highest
  `(appliedTurn, appliedSeq)` as new host; it resumes `GameSession` and emits a fresh
  checkpoint so stragglers re-sync.
- **Dedicated server mode (future):** run `GameSession` in the server process; the relay
  routes to it like any other host. Same code path → persistent, always-on games.

## 8. Security / anti-cheat

- Authority validates every command against the canonical state (legal mover, ownership,
  resources, range). Clients cannot apply illegal mutations to the shared truth.
- Relay enforces room membership + basic auth (reuse existing userId/password).
- Trust model in client-host mode: the host is trusted (as in most P2P RTS/4X). Dedicated
  server mode removes that trust. The command design makes a future spectator/observer and
  server-side validation natural.

## 9. Versioning / future-proofing

- `PROTOCOL_VERSION` handshake; refuse or downgrade on mismatch.
- `GameCommand` and frame types are **sealed + `@SerialName`** → new subtypes are additive;
  receivers reject unknown subtypes cleanly rather than corrupting state.
- `StateCheckpoint` carries `CompatibilityVersion` (reuse `GameInfo` save-compat machinery).
- Everything is feature-flagged; APIv0/APIv1 remain the default until v2 is at parity.

## 10. Incremental, shippable phases

| Phase | Deliverable | Verifiable by |
|------|-------------|---------------|
| **0** | This doc + `:network` protocol module skeleton + `core` `v2` package stubs. No behaviour change. | Project compiles; unit tests unchanged. |
| **1** | Relay server (rooms/routing/host role) + client relay transport. Echo/chat over relay. | Two clients exchange messages through the relay. |
| **2** | `CommandExecutor` + first command (`MoveUnit`) routed through the bus. Determinism harness. | New test: apply command log on two fresh `GameInfo`, checksums match. |
| **3** | `GameSession` authority + **sequential** play over relay (flagged). | Full game playable sequentially via relay, no file-store. |
| **4** | Migrate remaining actions to `GameCommand`; client-side prediction. | All actions issue commands; prediction reconciles. |
| **5** | **Simultaneous turns** + conflict resolution. | Multi-human simultaneous game resolves without desync. |
| **6** | Host migration, reconnection, desync recovery hardening. | Kill host mid-game → seamless takeover; drop+rejoin re-syncs. |

## 11. Open questions

- Per-turn timer policy for simultaneous mode (fixed clock vs. "all done" vs. hybrid)?
- Conflict-resolution rules catalogue (movement contention, combat ordering, city capture
  races) — needs a dedicated spec once Phase 5 starts.
- Relay scaling/persistence (in-memory rooms vs. backing store for dedicated/long games).
- Do we keep chat on the existing `/chat` WebSocket or fold it into the relay frames?

## 12. Build / verification note

Compilation needs a real JVM + enough memory (the Kotlin compiler on `core` wants well over
the 256 MiB available in the current dev sandbox). Build & test on a developer machine or CI:

```
JAVA_HOME=<jdk21> ./gradlew :network:build :server:build :core:compileKotlin
JAVA_HOME=<jdk21> ./gradlew :tests:test --tests '*Multiplayer*'
```
