# BNW Phase 3 — International Trade Routes (ITR): city-to-city trade-route yields (agent spec)

Design context: `docs/brave-new-world-adoption.md` §1 (Tier C), §4 (Tier C: *International Trade Routes (yields)* + *Venice double-trade-routes*), §5.3 (new GameInfo state + `GameCommand.EstablishTradeRoute` + projection + AI), and the appendix (`StatsFromTradeRoute`/`ConnectTradeRoutes` uniques, the `Trade Route` stockpile token, Caravan/Cargo Ship units). Implementation contract, split into independently-committable increments, modeled on `docs/bnw-ideology-plan.md`.

**Top note:** subagents do **NOT** run Gradle and do **NOT** commit. The orchestrator runs the single build+test and commits each increment.

Baseline already shipped (verified):
- Caravan (land) and Cargo Ship (sea) units exist in `android/assets/jsons/Civ V - Brave New World/Units.json`, gated `Costs [1] [Trade Route]` / `Only available <with [Trade Route]>`, with a `// TODO: Establish Trade Route` marker. They currently only do the City-State *trade mission* (`ConductTradeMission`).
- `Trade Route` is a **stockpiled resource token**. Techs/buildings grant `Instantly provides [1] [Trade Route]`; Venice (`Double the normal number of Trade Routes available` nation tag) grants a **second** token per source. So **capacity = token count = `civ.getResourceAmount("Trade Route")`** and Venice already doubles it for free.
- Unciv's **existing** "trade routes" are city→capital road/harbor *connections* for gold (`CapitalConnectionsFinder`, `CityStats.getStatsFromTradeRoute`, `city.isConnectedToCapital()`, `UniqueType.StatsFromTradeRoute` = `"[stats] from each Trade Route"`, `StatPercentFromTradeRoutes`). **ITR is a DIFFERENT mechanic.** To avoid collision, the new state/command/yields use the term **"Trade Route Connection" / `tradeRouteConnections`** in code, and ITR yields are applied as a SEPARATE banked amount, NOT folded into `CityStats.getStatsFromTradeRoute`.

## Framing decisions

- **D1 — authoritative state lives on `GameInfo`, not per-Civ.** A trade-route connection is bilateral (touches two cities, possibly two civs), so a single GameInfo-level `TradeRouteManager` (field on `GameInfo`, `IsPartOfGameInfoSerialization`) holding `ArrayList<TradeRouteConnection>` is the single source of truth (mirrors `GameInfo.religions`).
- **D2 — cities/civs are referenced by STABLE STRING IDs in stored state** (`City.id`, `Civilization.civID`), never object reference or `HexCoord`. The command DTO identifies cities by **center-tile coordinates** (consistent with every other city-targeting command); the executor resolves coords → `City` → `City.id` once, on the authority.
- **D3 — the command is a flat kotlinx-serializable DTO** carrying only ints/strings (mirrors the `ProposeTrade` deferral note). The authority resolves the unit, validates, and builds the `TradeRouteConnection`.
- **D4 — per-turn yields are applied on the authority in `TurnManager`, banked directly** (`civ.addGold`, `civ.tech.addScience`, `city.religion.addPressure`), exactly as `endTurn` banks `nextTurnStats`. NOT routed through `CityStats`/`getStatsForNextTurn`.
- **D5 — establishment is a unit action** (`UnitActionType.EstablishTradeRoute`) on Caravan/Cargo Ship, driven headlessly in v3 by a **dedicated** `GameCommand.EstablishTradeRoute` (not `GenericUnitAction`, because the destination city is a choice the `(x,y,actionType)` tuple can't carry — same reason `BuildImprovement`/`SpreadReligion` are dedicated).
- **D6 — route capability detection is data-driven, not hardcoded "Caravan/Cargo Ship".** A trade unit is any unit whose `BaseUnit` carries the `Costs [1] [Trade Route]` cost (`UniqueType.CostsResources` with param `Trade Route`) OR a new explicit `EstablishTradeRoute` unit unique. Route **type** (Land/Sea) is derived from `unit.baseUnit.isLandUnit()`.

---

## INCREMENT 1 (current target) — Authoritative `TradeRouteManager` state + the `TradeRouteConnection` model

**Goal:** the serializable state holding established city↔city routes, wired into `GameInfo` clone/setTransients, with capacity/length/connectivity helpers — but **no yields, no command, no UI yet**. Inert (nothing creates a route), so existing-save behavior is unchanged.

**New files & state:**
- `core/src/com/unciv/logic/trade/TradeRouteConnection.kt`:
  ```
  enum class TradeRouteType { Land, Sea }
  class TradeRouteConnection : IsPartOfGameInfoSerialization {
      var originCityId = ""; var destinationCityId = ""; var ownerCivId = ""
      var type = TradeRouteType.Land; var length = 0; var establishedTurn = 0; var unitId = -1
      fun clone(): TradeRouteConnection
  }
  ```
  All fields default to a valid empty state. No `@Transient civInfo` — resolution helpers take `gameInfo` as a parameter.
- `core/src/com/unciv/logic/trade/TradeRouteManager.kt` — `class TradeRouteManager : IsPartOfGameInfoSerialization`:
  - `@Transient lateinit var gameInfo: GameInfo`; `var connections = ArrayList<TradeRouteConnection>()`
  - `fun clone()` (deep-copies each connection), `fun setTransients(gameInfo)`
  - `@Readonly` lookups (null-safe): `getRoutesEstablishedBy(civId)`, `getRoutesTouchingCity(cityId)` (origin OR dest), `getOriginCity(c)`/`getDestinationCity(c)`/`getOwnerCiv(c)` (scan `gameInfo.getCities()`/`getCivilizationOrNull`, tolerate removed city → null), `usedCapacity(civId) = getRoutesEstablishedBy(civId).size`
  - `fun getMaxCapacity(civ): Int = civ.getResourceAmount("Trade Route")` (Venice's doubled tokens flow in automatically)
  - `fun computeRoute(originCity, destCity, type): Int?` — path length in tiles if a route exists, else null. Use `com.unciv.logic.map.BFS` from `originCity.getCenterTile()` with a passability predicate: Land = "tile is land AND enterable by owner-diplomacy" (reuse the `CapitalConnectionsFinder.canEnterBordersOf` shape); Sea = "tile.isWater OR isCityCenter". Reached when `hasReachedTile(destCity.getCenterTile())`; length = `getPathTo(...).count()`. Do NOT hand-roll traversal.
  - `fun removeRoute(c)`, `fun removeRoutesTouchingCity(cityId)`, `fun removeRoutesForUnit(unitId)` (used by Increment 4 + city loss)

**EXACT file ownership (this increment edits only these):**
- NEW: `TradeRouteConnection.kt`, `TradeRouteManager.kt`
- EDIT `core/src/com/unciv/logic/GameInfo.kt` — THREE places (mirror `religions`): field `var tradeRouteManager = TradeRouteManager()`; `toReturn.tradeRouteManager = tradeRouteManager.clone()` in `clone()`; `tradeRouteManager.setTransients(this)` in `setTransients()` (BEFORE the per-civ `setTransients` loop).

**Cross-increment contract:** Increment 2 calls `computeRoute`/`getMaxCapacity`/`usedCapacity` + adds to `connections`. Increment 3 reads `getRoutesEstablishedBy`/`getRoutesTouchingCity` + `getOriginCity`/`getDestinationCity`. Increment 4 calls `removeRoute*`. These method names are the frozen seam.

**Serialization:** only primitive/enum/String/collection fields → libgdx-JSON auto-serializes; the field is serialized via `clone()` + `setTransients()` (both updated), and a default `TradeRouteManager()` (empty list) is a valid empty state.

**Projection / AI:** none yet.

**Tests** — NEW `tests/src/com/unciv/logic/trade/TradeRouteManagerTest.kt` (model on `PublicOpinionManagerTest` + `TestGame`): `computeRoute` positive length for connected same-civ land cities, null when no path of the type exists; `getMaxCapacity` equals the token count after `gainStockpiledResource(ruleset.tileResources["Trade Route"]!!, 2)`; `usedCapacity` counts only matching `ownerCivId`; clone round-trip (distinct-but-equal list); serialize round-trip (`UncivFiles.gameInfoToString`→`gameInfoFromString` preserves `connections`, `setTransients` re-attaches `gameInfo`).

**Risks:** guard helpers against `City.id == NO_ID`. BFS cost bounded (one per establishment).

---

## INCREMENT 2 (next) — Establishment: unit action + `GameCommand.EstablishTradeRoute` + executor

**Goal:** a Caravan/Cargo Ship on a target city center establishes a route from its home city to that city. Validates capacity, route type vs unit type, connectivity, max-length. Emits the command; the executor applies it.

**New state & files:**
- EDIT `core/src/com/unciv/models/UnitAction.kt` — add `UnitActionType.EstablishTradeRoute("Establish Trade Route", { ImageGetter.getUnitActionPortrait("ConductTradeMission") }, UncivSound.Chimes)`.
- NEW `core/src/com/unciv/ui/screens/worldscreen/unit/actions/UnitActionsTrade.kt` (sibling of `UnitActionsReligion`): `fun getEstablishTradeRouteActions(unit, tile): Sequence<UnitAction>` — offered when the unit is a trade unit (D6), stands on a city-center tile that is a valid destination (different from the unit's home, `usedCapacity < getMaxCapacity`). The `action` lambda is **headless-safe** (no `GUI`) and calls a shared `TradeRouteManager.establish(originCity, destCity, unit)`. **Origin = the owner civ's capital** (`unit.civ.getCapital()`) — the change-home-city scaffolding is explicitly deferred; document this.
- EDIT `core/src/com/unciv/ui/screens/worldscreen/unit/actions/UnitActions.kt` — register `UnitActionType.EstablishTradeRoute to UnitActionsTrade::getEstablishTradeRouteActions` in `actionTypeToFunctions` (so the executor's type-filtered headless path reaches it).
- EDIT `core/src/com/unciv/models/ruleset/unique/UniqueType.kt` — add `EstablishTradeRoute("Can establish trade routes between cities", UniqueTarget.Unit)` (optional explicit D6 marker; the `Costs [1] [Trade Route]` detection also works).
- NEW command (`network/src/com/unciv/network/command/GameCommand.kt`, new `// region Trade Routes`, stable `@SerialName`):
  ```
  @Serializable @SerialName("establishTradeRoute")
  data class EstablishTradeRoute(val unitX: Int, val unitY: Int,
                                 val destCityX: Int, val destCityY: Int) : GameCommand
  ```

**`TradeRouteManager.establish(originCity, destCity, unit): TradeRouteConnection`** (shared by UI lambda + executor after validation): derive `type` from `unit.baseUnit.isLandUnit()`; `length = computeRoute(...)` (caller verified non-null); build the connection (ids, type, length, `establishedTurn = gameInfo.turns`, `unitId = unit.id`); `connections.add(it)`; `unit.currentMovement = 0f`, `unit.action = null` (the unit parks; NOT consumed; Increment 4 handles expiry/plunder). Do NOT consume a `Trade Route` token — capacity is enforced by `usedCapacity < getMaxCapacity`.

**`CommandExecutor.executeEstablishTradeRoute`** (new `when` branch — exhaustive `when` forces it — + private fn, mirroring `executeBuildImprovement`/`executeSpreadReligion`): `requireCiv`; `requireUnitOnTile`; resolve dest city-center tile (existing helper) + `destCity`; `originCity = actingCiv.getCapital()` or `CommandException`. Gates (all `CommandException`): unit is a trade unit (D6); `destCity.id != originCity.id`; `usedCapacity < getMaxCapacity`; `length = computeRoute(...) ?: throw`; `length <= maxRouteLength(unit)`; the engine action is actually available (`getUnitActions(unit, EstablishTradeRoute).any { it.action != null }`). Then `establish(...)`.
- **`maxRouteLength(unit)`:** base tile budget by type (Land ~12, Sea ~20 — `companion object` constants), extended by the unit's bonus-range uniques in the data (read by matching uniques, not nation names).

**EXACT file ownership:** NEW `UnitActionsTrade.kt`; EDIT `UnitAction.kt`, `UnitActions.kt`, `UniqueType.kt`, `GameCommand.kt`, `CommandExecutor.kt`, `TradeRouteManager.kt`; DATA (optional clean): `Units.json` — replace the `// TODO` comments on Caravan & Cargo Ship with the new unique.

**Cross-increment contract:** Increment 3 reads `connections`; Increment 4 reads `establishedTurn`/`length`/`unitId`.

**Projection / AI:** none yet.

**Tests** — extend `CommandCatalogueTest` + `CommandExecutorTest`: legal (two own land-connected cities + Caravan on dest + a token → route added, right ids/type, `currentMovement==0`); illegal — capacity 0 / no connectivity / non-owner / no unit / dest==origin / unknown dest → `CommandException`, state untouched; command DTO kotlinx round-trip; add the command to the catalogue enumeration.

**Risks:** the UI getter + lambda must touch no `GUI` (headless authority + tests). Origin=capital is a documented fidelity gap.

---

## INCREMENT 3 (then) — Per-turn yields: Gold, Science, Religion pressure

**Goal:** each route yields every turn to its owner and (partially) to the destination-city owner, applied on the authority in `TurnManager`.

**New file:** `core/src/com/unciv/logic/trade/TradeRouteYields.kt` (object, all `@Readonly`): `fun computeYields(c, gameInfo): TradeRouteYieldResult(ownerGold, ownerScience, destOwnerGold, religionPressure, originReligionName?)`.

**Concrete formulas (document each):** resolve `o`/`d` via the manager (null → yield 0); `international = (o.civ.civID != d.civ.civID)`.
- **Gold (owner):** `base = round(d.population.population * 0.15f + o.population.population * 1.1f)` (mirror the capital-connection formula shape), `*2.0f` international else `*1.0f`; add the owner's `StatsFromTradeRoute` gold + `StatPercentFromTradeRoutes` percent (so Petra/Bazaar/policy data lights up). Min 1.
- **Gold (destination owner, only international):** `round(o.population.population * 0.15f)` + the dest city's per-route gold bonuses (Harbor/East India Company). Domestic → 0.
- **Science (owner, international):** `techDiff = max(0, d.civ.tech.getNumberOfTechsResearched() - o.civ.tech.getNumberOfTechsResearched())`; `round(techDiff * 0.5f)`. Domestic → 0.
- **Religion pressure (toward destination):** if `o` has a majority religion `r`, `d.religion.addPressure(r.name, ~30)` — spreads the home city's religion outward to `d` regardless of who owns it.

**Application — `TradeRouteManager.applyYieldsForOwner(civ)`** called per-turn: iterate `getRoutesEstablishedBy(civ.civID)`: `civ.addGold(ownerGold)`; if `civ.cities.isNotEmpty()` `civ.tech.addScience(ownerScience)`; resolve `getDestinationCity(c)?.civ` → `addGold(destOwnerGold)` (skip same/null); `getDestinationCity(c)?.religion?.addPressure(originReligionName, pressure)` (guarded by religion enabled). **Where:** in `TurnManager.endTurn`, immediately AFTER `civInfo.addGold(nextTurnStats.gold.toInt())` + `civInfo.tech.endTurn(...)`, add `civInfo.gameInfo.tradeRouteManager.applyYieldsForOwner(civInfo)` (owner-iteration only → no double-counting the dest-owner gold).

**EXACT file ownership:** NEW `TradeRouteYields.kt`; EDIT `TradeRouteManager.kt` (`applyYieldsForOwner`), `TurnManager.kt` (one call in `endTurn`).

**Cross-increment contract:** Increment 6 (AI) reads `computeYields` to rank targets.

**Projection:** unchanged (yields bank into gold/science already scrubbed per-civ).

**Tests** — NEW `TradeRouteYieldsTest` + extend `TradeRouteManagerTest`: domestic (owner gold>0, science==0, destOwnerGold==0); international owner gold > same-pop domestic; science>0 when dest has more techs; religion pressure rises after `applyYieldsForOwner`; per-turn integration (owner + dest-owner gold both rise).

**Risks:** guard each route processed once (owner-iteration); null guards for removed cities; `addScience` only when the civ has cities.

---

## INCREMENT 4 — Route duration, expiry, danger / plundering, city-loss cleanup

**Goal:** routes expire after a duration (auto-renew if still valid, else drop); an enemy killing the parked trade unit plunders/cancels the route; losing/razing either city cancels it.

**New state & logic:**
- `TradeRouteConnection` `companion object` const `ROUTE_DURATION_TURNS = 30` (scaled by game speed via the same accessor other durations use).
- `TradeRouteManager.processExpiryAndRenewal(gameInfo)` per-turn: for each connection where `gameInfo.turns - establishedTurn >= duration`: **renew** (`establishedTurn = gameInfo.turns`) if the bound unit still lives + `computeRoute != null` + capacity ok + not at war with dest-owner; else **drop** (`removeRoute` + notify). Call site: `TurnManager.startTurn`, iterating `getRoutesEstablishedBy(civInfo.civID)` (so it runs per-owner per-turn).
- **Plundering:** the parked trade unit is a defenseless civilian killable by the existing combat path. EDIT `core/src/com/unciv/logic/map/mapunit/MapUnit.kt` `destroy()` (or `UnitManager.removeUnit`): after removal, `civ.gameInfo.tradeRouteManager.removeRoutesForUnit(this.id)` (returns dropped routes so the caller notifies the owner: "Your trade route to [city] was plundered!"). The killer-gold *reward* is polish — if wiring the killer cleanly is heavy, ship "kill cancels the route + notifies" and note the gold bonus as a follow-up.
- **City loss/raze:** EDIT the city-destruction path (`City.destroyCity` / wherever a city is removed) → `tradeRouteManager.removeRoutesTouchingCity(city.id)`.

**EXACT file ownership:** EDIT `TradeRouteManager.kt` (`processExpiryAndRenewal`, finalize `removeRoutesForUnit` returning dropped routes, `removeRoutesTouchingCity`); `TurnManager.kt` (one call in `startTurn`); `MapUnit.kt`/`UnitManager` (one line + notification); the city-destruction site (one line).

**Cross-increment contract:** Increment 5 projection tolerates dropped unit/city (null-guarded). Increment 6 AI re-establishes dropped routes.

**Tests** — extend `TradeRouteManagerTest`: expiry past duration renews (unit alive + valid) / drops (unit killed); plunder (`unit.destroy()` drops the route + notifies); `removeRoutesTouchingCity(destCity.id)` drops it.

**Risks:** the `MapUnit.destroy()` hook only drops routes whose `unitId` matches (benign for non-trade units). Duration speed-scaling uses the standard accessor.

---

## INCREMENT 5 — v3 projection (`PlayerViewProjector`)

**Goal:** each player sees only routes they're entitled to: **your own routes fully; routes touching one of your cities fully; purely-rival routes scrubbed.**

**Logic** — EDIT `core/src/com/unciv/logic/multiplayer/v3/visibility/PlayerViewProjector.kt`: add `redactTradeRoutes(projected, viewingCivId)` after `redactOtherCivSecrets` (GameInfo-level state needs its own redactor, like `redactBarbarianEncampments`): `myCityIds = projected.civilizations.first{it.civID==viewerId}.cities.map{it.id}.toSet()`; keep a connection iff `c.ownerCivId == viewerId || c.originCityId in myCityIds || c.destinationCityId in myCityIds`; `projected.tradeRouteManager.connections.removeAll { !keep(it) }`. Read only serialized fields (the projector skips full `setTransients`, so `tradeRouteManager.gameInfo` is not set — do NOT call gameInfo-dependent helpers here).

**EXACT file ownership:** EDIT `PlayerViewProjector.kt` (one private fn + one call).

**Tests** — extend `PlayerViewProjectorTest`: A→B route, B→C route; project for A (A's present, B-C absent); for B (both present); for C (B-C present, A-B absent); canonical `connections` untouched.

**Risks:** read only serialized fields per the no-setTransients contract.

---

## INCREMENT 6 — AI: establish trade routes with idle trade units

**Goal:** the authority's AI uses idle Caravans/Cargo Ships to establish high-value routes; light but specified.

**Logic** — EDIT `core/src/com/unciv/logic/automation/unit/CivilianUnitAutomation.kt` `automateCivilianUnit`: early branch — if `unit` is a trade unit (D6), not already bound (`getRoutesEstablishedBy(civ).none{it.unitId==unit.id}`), and `usedCapacity < getMaxCapacity`, delegate to NEW `core/src/com/unciv/logic/automation/unit/TradeUnitAutomation.kt` `automateTradeUnit(unit)`: origin = capital; candidates = known/own cities reachable for the type (`computeRoute != null`), not already a destination of this civ's routes, within `maxRouteLength`; score via `TradeRouteYields.computeYields` (ownerGold + ownerScience*weight, prefer international/high-tech); `unit.movement.headTowards(destCenterTile)`; when on/adjacent and the action is available, invoke `getEstablishTradeRouteActions(...).firstOrNull{it.action!=null}?.action?.invoke()`. Runs on the authority for AI civs → no new command.

**EXACT file ownership:** NEW `TradeUnitAutomation.kt`; EDIT `CivilianUnitAutomation.kt` (one branch).

**Tests** — NEW `TradeUnitAutomationTest`: AI civ with a Caravan + capital + a known foreign connected city + a token → a route gets established toward the highest-value reachable target; capacity full → no new route.

**Risks:** don't thrash — keep the unit's movement destination; pre-filter by aerial distance before BFS.

---

## Risks (all increments)

- **Serialization correctness:** the new `GameInfo.tradeRouteManager` is serialized correctly ONLY because `clone()` + `setTransients()` are both updated (Increment 1); a default-constructed manager is a valid empty state. Round-trip + clone tests enforce it.
- **Don't collide with the existing capital-connection "trade routes."** `CityStats.getStatsFromTradeRoute`, `CapitalConnectionsFinder`, `StatsFromTradeRoute`/`StatPercentFromTradeRoutes`, `city.isConnectedToCapital()` are the OLD mechanic and stay untouched. ITR uses distinct names and banks yields separately (D4); it only *reads* the `[stats] from each Trade Route` bonuses to light up data.
- **Capacity = token count, Venice for free.** Add an explicit test that N tokens supports N routes.
- **Authority vs joiner:** yields/expiry/plunder/AI run ONLY on the authority; clients receive the projected (scrubbed) view (Increment 5).
- **Command additivity:** `EstablishTradeRoute` gets a stable `@SerialName`; the exhaustive `when` forces a branch; add it to `CommandCatalogueTest`.
- **Headless safety:** the establish unit-action getter + lambda touch no `GUI`/`WorldScreen`.
- **Deferred fidelity gaps (documented):** change-home-city (origin = capital), the killer-gold plunder reward (route-cancel-on-death shipped), and the bundled City-State-trade production bonuses are out of scope.
