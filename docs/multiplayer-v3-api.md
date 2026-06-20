# Multiplayer v3 — API Reference

> Companion to the design doc [`docs/multiplayer-v3.md`](multiplayer-v3.md) (the *why*) and the
> implementer notes [`docs/multiplayer-v3-agents.md`](multiplayer-v3-agents.md). **This** file is
> the *what*: the wire protocol, the relay, the implemented command catalogue, and the in-process
> entry points.
>
> Naming note: this is the **authoritative, command-in / filtered-view-out** netcode in
> `com.unciv.logic.multiplayer.v3` and `:network`. It is unrelated to the legacy, now-`@Deprecated`
> `com.unciv.logic.multiplayer.apiv2` package (an abandoned runciv-style REST client) and to the
> `ApiVersion` enum (APIv0 Dropbox / APIv1 UncivServer file-store / APIv2 runciv REST). "v3" is our
> name precisely so the `apiv2` collision is gone.

## 1. How it works, in principle

One party — the **authority** — owns the canonical `GameInfo` and is the single source of truth.
Everyone else runs a thin **client** that holds only a *visibility-filtered* copy of the world.

```
          PlayerCommand / ResyncRequest   (intent, tiny)
   client ───────────────────────────────────────────────▶ authority (owns canonical GameInfo)
   (filtered view) ◀───────────────────────────────────── 
          PlayerView (per-player, redacted snapshot) / CommandRejected
```

Three invariants drive everything:

1. **Command-in / view-out.** Clients never mutate shared state. They send small `GameCommand`
   *intents*; the authority validates each against the canonical `GameInfo`, applies it through the
   single `CommandExecutor` choke-point, and ships back state. A rejected command leaves canonical
   state untouched and returns a directed `CommandRejected`.
2. **Hidden information by construction.** The authority projects the canonical state down to *what
   this player may legally see* (`PlayerViewProjector`) before sending it. Fogged enemy units,
   undiscovered cities, unseen barbarian camps and enemy interiors are **never on the wire** to a
   client — so a modified client cannot maphack. Per-player payloads are therefore delivered
   **directed** (never broadcast).
3. **No determinism requirement.** Only the authority simulates; clients never replay the world, so
   there is nothing to keep bit-for-bit deterministic and no convergence checksum. (See
   design doc §3.)

**AI players** live entirely inside the authority's `GameInfo` and are run by the engine during
`GameInfo.nextTurn()`. They are **not** in the roster and never receive a `PlayerView`; clients
never send commands on their behalf. The turn barrier waits only on connected, alive **human**
players (see §6).

### Two authority modes (same code)

The same `GameSession` runs either way:

- **Dedicated server** — `GameSession` runs in the server process; clients connect to it.
- **Anyone-can-host** — a player's client *is* the authority, reached by the other players through
  the public **relay** (which only solves NAT/connectivity). In this "option A" mode the host is
  also a local client of its own authority: its own frames are delivered in-process (no socket
  round-trip), everyone else's go over the relay.

## 2. Modules

| Module | Path | Contents |
|---|---|---|
| `:network` | `network/src/com/unciv/network/` | Engine-free wire types: `Protocol.kt` (version + id typealiases), `relay/RelayMessages.kt` (envelopes), `game/GameFrame.kt` (authority↔client frames), `command/GameCommand.kt` (intents), `serialization/RelayJson.kt` (shared `relayJson`). |
| `:core` v3 | `core/src/com/unciv/logic/multiplayer/v3/` | `command/CommandExecutor.kt` (the sole mutation choke-point), `session/GameSession.kt` (authority loop + turn barrier), `visibility/PlayerViewProjector.kt` (anti-maphack projection), `client/` (snapshot decode + view holder + prediction), `net/V3GameHost.kt` + `net/V3GameClient.kt`, `transport/`, and `V3GameManager.kt` (the UI-facing entry point). |
| `:server` | `server/src/com/unciv/app/server/` | `RelayServer.kt` + the `/relay` route in `UncivServer.kt`. |

All wire types are `@Serializable sealed interface`s with a stable `@SerialName` per subtype,
serialized through the shared `relayJson` with a `"type"` discriminator. The set is **additive**:
never rename or repurpose a `SerialName`; receivers reject unknown subtypes cleanly.

## 3. The relay protocol (`com.unciv.network.relay`)

The relay is **pure connectivity**: it tracks room membership/presence and routes frames. It never
inspects, stores or understands the game `payload`. One room hosts one game; the room creator
becomes the `HOST`.

`PeerRole` — `HOST` | `PLAYER` | `SPECTATOR` (spectator reserved for a future observer mode).

### Client → relay (`ClientToRelay`)

| Type | `SerialName` | Fields | Purpose |
|---|---|---|---|
| `Hello` | `hello` | `protocolVersion`, `userId`, `auth?` | Handshake: announce `Protocol.VERSION`, authenticate the user. |
| `CreateRoom` | `createRoom` | `gameId` | Create a room for `gameId`; creator becomes `HOST`. |
| `JoinRoom` | `joinRoom` | `roomId` | Join an existing room. |
| `LeaveRoom` | `leaveRoom` | — | Leave the current room. |
| `Relay` | `relay` | `payload: GameFrame` | **Broadcast** an opaque frame to the whole room. |
| `RelayTo` | `relayTo` | `targetUserId`, `payload: GameFrame` | **Directed** send to one peer. This is how the authority delivers each player their own redacted `PlayerView` — a per-player payload must not be broadcast or it would leak. |

### Relay → client (`RelayToClient`)

| Type | `SerialName` | Fields | Purpose |
|---|---|---|---|
| `Welcome` | `welcome` | `roomId`, `role: PeerRole`, `peers: List<UserId>` | Confirms join/create; gives the assigned role and current members. |
| `PeerJoined` | `peerJoined` | `userId` | A peer joined. |
| `PeerLeft` | `peerLeft` | `userId` | A peer left (the authority uses this to re-check the turn barrier — see §6). |
| `Relayed` | `relayed` | `fromId`, `payload: GameFrame` | An opaque frame forwarded from `fromId`. |
| `Error` | `error` | `code`, `message` | Bad handshake / unknown room / auth failure / … |

> Host migration / `HostChanged` is deliberately **deferred** (design doc §7) and not in the
> protocol.

### Running the relay server

The relay is **not** a separate service — it's the `/relay` WebSocket route built into `UncivServer`
and enabled by default (toggle with `-no-relay`). Minimum to run a relay: a JRE 21 + the
`UncivServer.jar`.

```bash
./gradlew :server:dist            # -> server/build/libs/UncivServer.jar
java -jar UncivServer.jar         # listens on :8080, relay at ws://<host>:8080/relay
# relay-only (no auth / no chat):
java -jar UncivServer.jar -no-auth -no-chat
```

`GET /isalive` returns `relayVersion` = `Protocol.VERSION` when the relay is enabled. Clients derive
the WebSocket URL from the configured multiplayer server URL via `V3GameManager.relayUrl`:
`https://host` → `wss://host/relay`, `http://host` → `ws://host/relay`, a bare host assumes `wss://`.

## 4. Game frames (`com.unciv.network.game.GameFrame`)

Frames are carried **opaquely** inside the relay envelopes (`Relay`/`RelayTo` → `Relayed`), or sent
directly in dedicated mode. The relay never reads them.

**Client → authority**

| Frame | `SerialName` | Fields | Meaning |
|---|---|---|---|
| `PlayerCommand` | `playerCommand` | `seq`, `playerId`, `command: GameCommand` | A single sequenced intent — the **only** command frame, for both turn models. Sequential and streaming-simultaneous play both stream these; `EndTurn` is sent as a `PlayerCommand` (it marks the player done / drives the barrier). |
| `ResyncRequest` | `resyncRequest` | `playerId` | "Send me a fresh filtered snapshot now" (join / reconnect / mid-turn). |

**Authority → a specific client**

| Frame | `SerialName` | Fields | Meaning |
|---|---|---|---|
| `PlayerView` | `playerView` | `turn`, `compatVersion`, `gzippedFilteredGameInfo` | The player's full **visibility-filtered** snapshot (JSON+gzip of a redacted `GameInfo`, decoded via the normal save path). Delivered **directed**. This is the "delta-out" — currently full filtered snapshots; semantic patches are a future optimization (design §11). |
| `CommandRejected` | `commandRejected` | `seq`, `reason` | The command with `seq` was illegal/declined; canonical state untouched. |

**Provisional / not in active use** (leftovers from the abandoned lockstep framing; do not build on
them, do not add checksum/replay frames): `ResolvedTurn`, `StateCheckpoint`, `ChecksumReport`,
`ResyncGrant`.

## 5. Command catalogue (`com.unciv.network.command.GameCommand`)

Every command carries **only intent** (ids + targets), never resulting state. The authority
re-derives and validates everything against the canonical `GameInfo` through the engine's own gates,
then delegates to the engine's own action path — it never hand-rolls state. Units/cities are
addressed by **acting-civ + tile coordinates** (no raw ids on the wire), except `MoveUnit`
(historical `unitId`) and spies (stable `Spy.name`).

**41 commands are implemented.** Listed by `SerialName`:

### Turn & core
| `SerialName` | Type | Key fields |
|---|---|---|
| `endTurn` | `EndTurn` | — (marks this player done; drives inter-turn processing / the barrier) |

### Units — movement & combat
| `SerialName` | Type | Key fields |
|---|---|---|
| `moveUnit` | `MoveUnit` | `unitId`, `fromX/Y`, `toX/Y` |
| `attackUnit` | `AttackUnit` | `attackerX/Y`, `targetX/Y` (melee + ranged) |
| `promoteUnit` | `PromoteUnit` | `x`, `y`, `promotionName` |
| `genericUnitAction` | `GenericUnitAction` | `x`, `y`, `actionType` (a `UnitActionType` name) — the catch-all that reuses the whole engine `UnitActions` catalogue (Fortify, Sleep, Explore, Disband, SetUp, AirSweep, Automate, Repair, Pillage, and the on-map Great-Person hurry actions) |
| `upgradeUnit` | `UpgradeUnit` | `unitX/Y`, `toUnitName` |
| `buildImprovement` | `BuildImprovement` | `unitX/Y`, `improvementName` (worker improvement pick) |
| `paradrop` | `Paradrop` | `unitX/Y`, `targetX/Y` |
| `giftUnit` | `GiftUnit` | `unitX/Y` (recipient = tile owner) |
| `swapUnits` | `SwapUnits` | `unitX/Y`, `otherX/Y` |
| `disbandUnit` | `DisbandUnit` | `unitX/Y` |

### Cities
| `SerialName` | Type | Key fields |
|---|---|---|
| `foundCity` | `FoundCity` | `x`, `y` (settler on tile) |
| `setCityProduction` | `SetCityProduction` | `cityX/Y`, `constructionName` |
| `buyConstruction` | `BuyConstruction` | `cityX/Y`, `constructionName`, `stat`, `improvementTileX/Y?` |
| `buyTile` | `BuyTile` | `cityX/Y`, `tileX/Y` |
| `sellBuilding` | `SellBuilding` | `cityX/Y`, `buildingName` |
| `razeCity` | `RazeCity` | `cityX/Y`, `raze` |
| `annexCity` | `AnnexCity` | `cityX/Y` (puppet → annex) |
| `setCityFocus` | `SetCityFocus` | `cityX/Y`, `focusName` (`CityFocus` name) |
| `resetCitizens` | `ResetCitizens` | `cityX/Y` |
| `toggleAvoidGrowth` | `ToggleAvoidGrowth` | `cityX/Y` |
| `toggleLockedTile` | `ToggleLockedTile` | `cityX/Y`, `tileX/Y` |

### Tech & policy
| `SerialName` | Type | Key fields |
|---|---|---|
| `chooseTech` | `ChooseTech` | `techName` (engine plots the prerequisite path) |
| `adoptPolicy` | `AdoptPolicy` | `policyName` (individual policy **or** branch) |

### Diplomacy
| `SerialName` | Type | Key fields |
|---|---|---|
| `declareWar` | `DeclareWar` | `targetCivName` |
| `makePeace` | `MakePeace` | `targetCivName` |
| `declareFriendship` | `DeclareFriendship` | `targetCivName` |
| `defensivePact` | `DefensivePact` | `targetCivName` (duration from game speed) |
| `denounce` | `Denounce` | `targetCivName` |
| `giftGold` | `GiftGold` | `targetCivName`, `gold` (city-state gift) |
| `demandResponse` | `DemandResponse` | `targetCivName`, `demandName`, `agree` |
| `cityStateProtection` | `CityStateProtection` | `cityStateCivName`, `pledge` |
| `tributeGold` | `TributeGold` | `cityStateCivName` (bully a city-state for gold; amount derived on authority) |
| `tributeWorker` | `TributeWorker` | `cityStateCivName` (bully a city-state for a worker; unit chosen deterministically) |
| `respondToTrade` | `RespondToTrade` | `fromCivName`, `accept` (accept/decline a pending request) |

### Espionage
| `SerialName` | Type | Key fields |
|---|---|---|
| `moveSpy` | `MoveSpy` | `spyName`, `targetCityX/Y` (`HIDEOUT` sentinel = recall) |
| `setSpyAction` | `SetSpyAction` | `spyName`, `spyActionName` (`SpyAction` name; player-selectable only) |

### Great person & religion
| `SerialName` | Type | Key fields |
|---|---|---|
| `chooseGreatPerson` | `ChooseGreatPerson` | `unitName` (free GP pick) |
| `foundPantheon` | `FoundPantheon` | `beliefName` |
| `foundReligion` | `FoundReligion` | `unitX/Y`, `religionName`, `displayName`, `beliefNames` |
| `enhanceReligion` | `EnhanceReligion` | `unitX/Y`, `beliefNames` |
| `spreadReligion` | `SpreadReligion` | `unitX/Y`, `targetCityX/Y` |
| `removeHeresy` | `RemoveHeresy` | `unitX/Y`, `targetCityX/Y` (inquisitor) |

### Intentionally NOT separate commands
- **`ProposeTrade`** — deferred: a full bilateral `Trade` uses the engine's libgdx-JSON
  serialization, not kotlinx (`GameCommand`'s wire format). `RespondToTrade` still lets a player
  accept/decline incoming requests.
- **`ToggleWeLoveTheKing`** — there is no player-initiated WLTK action in the engine; it is set only
  by the engine.
- **`SetUp`, `AirSweep`, `Automate`, `Repair`, `Pillage`**, and the on-map Great-Person hurry /
  trade-mission actions — they carry no choice the `(x, y, actionType)` tuple can't express and are
  headless-safe, so they route through **`genericUnitAction`**.

## 6. The authority / session (`GameSession`)

`GameSession` owns the canonical `GameInfo`, applies commands through `CommandExecutor`, runs
inter-turn processing, and emits each connected human their own filtered `PlayerView`.

- **Roster** — `Map<PlayerId, civId>` for human players only (`PlayerId == UserId`). Build it with
  `V3GameManager.rosterFrom(gameInfo)`.
- **Inbound** `onFrame(frame)`:
  - `PlayerCommand` → resolve civ from roster → `EndTurn` records "this human is done" (drives the
    barrier); any other command goes through `CommandExecutor.execute` (rejection → directed
    `CommandRejected`). This is the only command path — both sequential and streaming-simultaneous
    play stream `PlayerCommand`s, applied immediately in arrival order. There is no buffered
    whole-turn frame.
  - `ResyncRequest` → directed fresh `PlayerView` from current state.
- **Turn barrier (simultaneous, the live path).** A round resolves only when **every rostered human
  that is alive AND currently connected** has ended (`expectedHumanPlayers()`):
  - *Defeated* humans are excluded — an eliminated player never ends again.
  - *Disconnected / never-joined* humans are excluded via `isConnected` (fed from relay presence by
    `V3GameHost`) — an unfilled lobby slot or a dropped player must not hang the round on "Waiting
    for other players…". A `PeerLeft` re-checks the barrier immediately.
  - The round runs one `nextTurn()` per **alive human civ** (`aliveHumanTurnCount()`), which can be
    larger than the barrier set, so the engine still cycles an absent-but-alive human's turn and
    `turns` advances. AI runs inside those `nextTurn()` calls.
- **Security (open).** The authority currently trusts `playerId` on inbound frames. Binding it to the
  connection (the relay's `Relayed.fromId`) is a pending hardening step (design §10).

## 7. Entry points (`V3GameManager`)

`V3GameManager` is the single UI-facing object (held by `UncivGame.v3GameManager`). One instance
drives one game in **either** host or client mode.

| Member | Mode | Purpose |
|---|---|---|
| `hostGame(gameInfo, serverUrl, hostUserId, roster): RoomId` | host | Connect, create the room, wrap `gameInfo` in a `V3GameHost` (owns a `GameSession`), start it. Returns the room id to share. |
| `joinGame(roomId, serverUrl, myUserId): V3GameClient` | client | Connect, join the room, wrap a `V3GameClient` around a `ClientGameView`. |
| `sendCommand(command)` | both | Route the local player's intent to the authority (host: in-process `submitLocal`; client: over the relay). |
| `sendEndTurn()` | both | Send `EndTurn`; latches `localEndedTurn` so the UI disables input until the round resolves. |
| `requestInitialView()` / `awaitFirstView()` | client | Ask for and await the first filtered snapshot (a joiner needs this because the host only pushes a `PlayerView` on round resolution). |
| `onView` | both | Callback fired after each inbound `PlayerView` is applied (the WorldScreen swaps in the decoded filtered `GameInfo`). |
| `close()` | both | Tear down the transport. |

A v3 game is gated behind the experimental, off-by-default `GameParameters.isMultiplayerV3` flag
("Authoritative Multiplayer (experimental)"), mutually exclusive with the classic
`isOnlineMultiplayer` mode.

## 8. Versioning

- `Protocol.VERSION` is exchanged in the `Hello` handshake; peers refuse/downgrade on a major
  mismatch.
- Command and frame hierarchies are sealed + `@SerialName` → new subtypes are additive; unknown
  subtypes are rejected cleanly rather than corrupting state.
- `PlayerView` carries a `compatVersion` reusing the `GameInfo` save-compatibility machinery.
