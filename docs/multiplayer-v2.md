# Multiplayer v2 — Authoritative Server, Command-In / Delta-Out

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
no real authority — and because every client downloads the **full** `GameInfo`, there is **no
hidden information**: any client can read the entire map, every civ, every unit and every
barbarian camp (a trivial maphack).

## 2. Goals

The heart of v2 is a **fast, authoritative server that takes commands in and sends
visibility-filtered deltas out**. Everything else hangs off that.

1. **Authoritative simulation** — exactly one party owns the canonical `GameInfo` and is the
   single source of truth. Clients send *intents*; they never mutate shared state directly.
2. **Command-in / delta-out protocol** — clients send small `GameCommand`s (player actions);
   the authority validates, applies, and streams back **deltas** (state patches), not
   whole-state blobs.
3. **Hidden information by construction** — a client only ever *receives* state it is allowed
   to see (its own civ + currently/previously visible tiles, units, cities). Other civs'
   interiors, fogged units and unseen barbarians are **never on the wire to that client**.
   This makes maphacks impossible even with a modified client.
4. **Two co-equal authority modes** — the *same* `GameSession` runs either:
   - **Dedicated server** — persistent, always-on, fully trusted; clients connect to it; or
   - **Anyone can host** — a player's client *is* the authority, reached by other players
     through the public **relay** (which solves NAT/connectivity, nothing more).

   These are equal-priority first-class targets, not one-then-the-other.
5. **Simultaneous turns** — all human players act within the same turn; the authority
   orders/resolves and pushes the resulting deltas.
6. **Future-proof** — versioned protocol, additive command set, one `GameSession` for both
   modes.
7. **Host migration** — *far future, deliberately deprioritized* (see §3 and §7 for why partial
   replication makes this hard, and what it would take).

## 3. No determinism requirement

A **truly authoritative** design does **not** require deterministic simulation. Only the
authority simulates; it is the sole source of truth and ships the *results* (deltas) to
clients. Clients never re-run the world, so there is nothing for them to diverge *from*.

This is the key difference from **lockstep / command-replay** netcode (which the earlier draft
of this doc assumed): lockstep ships only commands and relies on every client reproducing
identical state by replaying them — which forces (a) bit-for-bit determinism everywhere and
(b) **full state on every client**, the very thing goal #3 forbids. Dropping the replay model
drops both constraints at once.

Consequences:

- We do **not** need a determinism audit, a state checksum for convergence, or a CI determinism
  harness as a *correctness* gate. Drift between clients is impossible because clients don't
  simulate.
- Unciv's existing **state-derived RNG** (`GameContext.stateBasedRandom`) and ordered
  collections remain good engineering — they help save-compat, reproducible debugging and any
  future dedicated-server replay tooling — but they are **not** a v2 correctness dependency.
- Wall-clock fields, `HashMap` iteration order, floating-point quirks, etc. simply don't matter
  for client convergence anymore; they only matter inside the single authority, like any normal
  server.

The cost we accept in exchange: deltas are larger than bare commands, and the authority must
compute a per-player view. For a 4X with hidden information and anti-cheat as goals, that is the
right trade.

## 4. Architecture overview

```
   ┌──────────── DEDICATED SERVER ────────────┐        ┌──────── PUBLIC RELAY (NAT punch-through) ───────┐
   │  GameSession = AUTHORITY                  │        │  Rooms by gameId · membership/presence          │
   │  canonical GameInfo                       │        │  directed + broadcast routing of OPAQUE frames  │
   │  validates commands · emits per-player    │        │  NO game logic, never inspects payloads         │
   │  visibility-filtered deltas               │        └───▲─────────────▲──────────────────▲────────────┘
   └───▲───────────────▲───────────────────────┘            │             │                  │
       │ cmd        delta│ (filtered)                 cmd ↑ delta↓   cmd ↑ delta↓       cmd ↑ delta↓
       │               │                              ┌────┴─────┐  ┌────┴─────┐      ┌────┴─────────────┐
   ┌───┴────┐    ┌─────┴────┐                         │ HOST     │  │ Player   │ ...  │ Player client    │
   │ Player │    │ Player   │  (clients hold ONLY     │ client   │  │ client   │      │ (filtered view)  │
   │ client │    │ client   │   their filtered view)  │ =AUTHOR. │  │(filtered │      │                  │
   └────────┘    └──────────┘                         │ full GI  │  │  view)   │      │                  │
                                                       └──────────┘  └──────────┘      └──────────────────┘
        DIRECT (dedicated mode)                              ANYONE-CAN-HOST mode (via relay)
```

Both modes run the identical `GameSession`. In dedicated mode clients connect straight to the
server; in anyone-can-host mode they connect *out* to the relay, which forwards opaque frames
between the host-authority and the other players. The relay never holds or understands game
state — it only moves bytes and tracks who's in the room.

### Layers

| Layer | Module | Responsibility |
|------|--------|----------------|
| **Protocol** | `:network` (pure Kotlin + kotlinx.serialization) | Wire types shared by client, relay and server: relay envelopes, `GameCommand` (in), state-delta / snapshot frames (out), protocol version. **No engine deps.** |
| **Command** | `core` → `logic/multiplayer/v2/command` | `GameCommand` sealed hierarchy + `CommandExecutor`: the single choke-point that validates and applies an intent to the canonical `GameInfo`. |
| **Authority/Session** | `core` → `logic/multiplayer/v2/session` | `GameSession`: owns canonical `GameInfo`, applies commands, runs inter-turn processing, and computes the **per-player visibility-filtered delta** for each connected player. Runs in dedicated server **or** client-host. |
| **Visibility/Delta** | `core` → `logic/multiplayer/v2` | Turns "what changed" into "what *this* player may see" using the engine's existing per-civ visibility (`Civilization.viewableTiles`, explored tiles, known civs). |
| **View model** | `core` (client) | The thin, partial state a client holds — its own civ in full plus the filtered view of everything else — built up by applying deltas. |
| **Transport** | `core` → `logic/multiplayer/v2/transport` (client) and `server` (relay/server) | WebSocket client; relay rooms/routing on the server; dedicated-server endpoint. |

## 5. Protocol sketch (`:network`)

All messages are `@Serializable` sealed hierarchies with a `"type"` discriminator, using the
shared `relayJson` config so both ends round-trip identically. `Protocol.VERSION` is negotiated
on connect; unknown subtypes are rejected gracefully.

```
// Relay envelopes (relay only inspects routing/membership, never game content)
ClientToRelay: Hello(protocolVersion, userId, auth) | CreateRoom(gameId) | JoinRoom(roomId)
             | LeaveRoom
             | Relay(payload)                 // broadcast to room (opaque)
             | RelayTo(targetUserId, payload)  // DIRECTED to one peer (opaque) — needed so the
                                               //   authority can send each player a *different*
                                               //   visibility-filtered delta
RelayToClient: Welcome(roomId, role, peers) | PeerJoined/PeerLeft | HostChanged(newHostId)
             | Relayed(fromId, payload) | Error(code, msg)

// Game frames, carried opaquely inside Relay/RelayTo/Relayed (or sent directly in dedicated mode)
GameFrame:
  // client -> authority
  - PlayerCommand(seq, playerId, command: GameCommand)        // one action intent
  - TurnSubmission(turn, playerId, commands, done)            // simultaneous turns
  - Ack(lastAppliedDeltaSeq)                                  // flow control / reconnect cursor
  - ResyncRequest                                             // "send me a fresh snapshot"
  // authority -> a SPECIFIC client (visibility-filtered; not a verbatim broadcast)
  - StateDelta(deltaSeq, turn, patches)                       // only what THIS player may see
  - PlayerView(turn, compatVersion, gzippedFilteredGameInfo)  // full filtered snapshot (join/reconnect)
  - CommandRejected(seq, reason)                              // illegal/declined action
```

> **Protocol status.** The committed Phase 0/1 `:network` skeleton carries provisional,
> replay-oriented frames (`ResolvedTurn`, `StateCheckpoint`, `ChecksumReport`,
> `ResyncRequest/Grant`). Those are placeholders from the earlier lockstep framing; Phase 3
> replaces them with the delta-out frames above (`StateDelta` / `PlayerView` / `CommandRejected`)
> and adds `RelayTo` for directed delivery.

### `GameCommand` (sealed, additive)

One subtype per player-initiated mutation, e.g.:
`MoveUnit`, `AttackUnit`, `FoundCity`, `SetCityProduction`, `BuyConstruction`, `BuyTile`,
`PromoteUnit`, `GenericUnitAction`, `ChooseTech`, `AdoptPolicy`, `DiplomacyAction`,
`AcceptTrade`, `EndTurn`, … Each carries only the *intent* (ids + target), not resulting state.

## 6. Turn models

### Sequential (Phase 3) — replaces the file-store path, behind a flag
The active player's client streams `PlayerCommand`s to the authority. The authority validates
each against the canonical `GameInfo` (legal mover, ownership, resources, range), applies it,
then computes and sends **each connected player their own visibility-filtered `StateDelta`** —
so a player sees an enemy unit appear only if it entered their vision. `EndTurn` triggers
`GameInfo.nextTurn()` on the authority (inter-turn processing stays authority-side); the
resulting changes go out as the next round of per-player deltas.

### Simultaneous (Phase 5) — the real prize
1. All human players act **at once**; each client optimistically applies its *own* commands
   locally (client-side prediction, limited to its own visible domain) and streams them as
   `TurnSubmission`.
2. When every player marks `done` (or a per-turn timer fires), the authority **resolves**: a
   defined ordering (e.g. `(submissionIndex, playerId, seq)`) and conflict rules (two units onto
   one tile, simultaneous combat, contested city capture), then applies the canonical sequence
   and runs inter-turn processing.
3. The authority pushes per-player `StateDelta`s; each client **reconciles** its prediction
   against the authoritative result for its own actions. (No global checksum needed — the
   authority is the only simulator; see §3.)

## 7. Authority modes & hosting

- **Dedicated server (first-class).** Run `GameSession` in the server process. Clients connect
  directly (or via the relay like any other host). Fully trusted, persistent, always-on — the
  recommended mode for serious/long games and the only mode that is naturally cheat-resistant.
- **Anyone can host (first-class).** A player's client runs `GameSession` and *is* the
  authority. Other players reach it through the relay, which solves NAT/connectivity by having
  everyone connect *out* to the public server. Same code path as dedicated mode.
- **Relay = pure connectivity.** It assigns the room creator `role = HOST`, tracks membership /
  presence, and routes **opaque** frames — both broadcast (`Relay`) and **directed**
  (`RelayTo`, required for per-player deltas). It never inspects or stores game state.
- **Host migration — far future (Phase 7).** Under this model clients hold only their *filtered*
  view, so no surviving client can transparently become the authority from its own memory (the
  thing that made migration cheap under full-replication lockstep is exactly what goal #3
  removes). Realistic options, deferred:
  - **Dedicated server** for any game that needs resilience/persistence — migration is a
    non-issue there.
  - **Cold-standby checkpoint:** the client-host periodically ships an opaque full-state blob to
    the relay (or a designated standby peer); on host loss the game pauses and resumes from that
    checkpoint. Heavier, lower priority.
  - Until then, if a client-host drops, the game pauses/ends — acceptable for casual sessions.

## 8. Security / anti-cheat

- **Hidden information is enforced at the wire, not the UI.** Because the authority sends each
  client only its visibility-filtered view, a modified/hostile client literally never receives
  state it shouldn't see — maphacks are impossible by construction (goal #3). This is a concrete
  improvement over today's full-`GameInfo` download.
- **Command validation.** The authority checks every command against canonical state (legal
  mover, ownership, resources, range); clients cannot apply illegal mutations.
- **Trust model.** Dedicated-server mode is fully trusted. In anyone-can-host mode the host is
  trusted (as in most P2P RTS/4X) — they *do* hold full state — so competitive/ranked play
  should prefer dedicated mode. The command + filtered-delta design makes spectator/observer and
  stricter server-side validation natural extensions.
- **Relay** enforces room membership + basic auth (reuse existing userId/password).

## 9. Versioning / future-proofing

- `Protocol.VERSION` handshake; refuse or downgrade on mismatch.
- `GameCommand` and frame types are **sealed + `@SerialName`** → new subtypes are additive;
  receivers reject unknown subtypes cleanly rather than corrupting state.
- `PlayerView` snapshots carry a `compatVersion` (reuse `GameInfo` save-compat machinery).
- Everything is feature-flagged; APIv0/APIv1 remain the default until v2 is at parity.

## 10. Incremental, shippable phases

| Phase | Deliverable | Verifiable by |
|------|-------------|---------------|
| **0** ✓ | This doc + `:network` protocol module skeleton + `core` `v2` package stubs. No behaviour change. | Project compiles; unit tests unchanged. |
| **1** ✓ | Relay server (rooms/routing/presence) + client relay transport — **pure connectivity** for anyone-can-host. | Two clients exchange frames through the relay. |
| **2** | `CommandExecutor` + authority applies commands to the canonical `GameInfo` in one process (first: `MoveUnit`). | Unit test: a command mutates `GameInfo` through the bus; an illegal command is rejected. |
| **3** | **Command-in / visibility-filtered delta-out** protocol + thin client view model; sequential play; runs identically in **dedicated-server and anyone-can-host** modes. | Full game playable sequentially, no file-store; a client holds only its filtered view (cannot see fogged enemy state). |
| **4** | Client-side prediction + reconciliation for the player's *own* actions. | Own actions feel instant and reconcile against authority deltas. |
| **5** | **Simultaneous turns** + conflict resolution. | Multi-human simultaneous game resolves correctly. |
| **6** | Reconnection / desync recovery: a dropped *client* rejoins and gets a fresh filtered `PlayerView`. | Drop + rejoin re-syncs from the authority. |
| **7** *(far future)* | Host promotion/migration via cold-standby checkpoint. | Kill a client-host → resume from standby. |

## 11. Open questions

- **Delta granularity & format.** Semantic patches ("unit X → tile T", "tile T revealed with
  contents …") vs. structural diffs of the filtered `GameInfo`. Trade-off: authoring cost vs.
  size/robustness.
- **Visibility recompute cost.** Computing N per-player filtered deltas each resolution step —
  how to do it cheaply, reusing `Civilization.viewableTiles` / explored state.
- **Directed routing on the relay.** `RelayTo(targetUserId)` for per-player deltas — confirm the
  relay stays game-blind (routes by membership-level userId only) and whether broadcast is still
  needed at all.
- **How much client prediction** in simultaneous mode (own units only? none, for simplicity
  first?).
- Per-turn timer policy for simultaneous mode (fixed clock vs. "all done" vs. hybrid).
- Conflict-resolution rules catalogue (movement contention, combat ordering, city-capture races)
  — needs a dedicated spec once Phase 5 starts.
- Keep chat on the existing `/chat` WebSocket or fold it into relay frames?

## 12. Build / verification note

Compilation needs a real JVM + enough memory (the Kotlin compiler on `core` wants well over
the 256 MiB available in some dev sandboxes). Build & test on a developer machine or CI:

```
./gradlew :network:build :server:build :core:compileKotlin
./gradlew :tests:test --tests 'com.unciv.logic.multiplayer.v2.*'
```
