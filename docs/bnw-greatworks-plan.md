# BNW Phase 2c — Real Great Works objects + theming (agent spec)

Design context: `docs/brave-new-world-adoption.md` §3.2 (slot/theming/stockpile fakery), §3.5 (Great People create works via triggers), §4 Tier B row **Great Works**, §5.3 (v3 treatment). Implementation contract, split into independently-committable increments, structured to match `docs/bnw-ideology-plan.md`.

> **Top note:** subagents do NOT run Gradle and do NOT commit. The orchestrator runs the single build + `:tests:test` and commits each increment. Each increment is self-contained and compiles/tests green on its own.

Baseline today: Great Works are **stockpiled resources** (`Great Work of Art/Writing/Music`, `Artifact` in the bundled `TileResources.json`); banked by `Instantly provides [1] [Great Work of …] <by consuming this unit>` on Great Artist/Writer/Musician units (`Civ V - Brave New World/Units.json`) and `Instantly provides [1] [Artifact]` from archaeology Dig events. "Slots" are ~60 hidden auto-built sub-buildings per work-type that `Instantly consumes [1] [Great Work of …]` and `Provides [2] [Tourism]`; "Theming Bonus" is another hidden building gated `Only available <in cities with a [<wonder> Slot N]>`. The engine has **no** native notion of a building slot or a per-city object store (`Building.kt` has only `specialistSlots: Counter<String>`; `City`/`CityConstructions` store only `builtBuildings`/queues; `OneTimeProvideResources` in `UniqueTriggerActivation.kt` only adds to `resourceStockpiles`).

## Framing decisions

- **D1 — Authoritative storage is a GameInfo-level `GreatWorkManager`.** Real Civ V lets you see *rivals'* works (Culture Overview), and works move between cities/civs. Holding the canonical registry once at `GameInfo` avoids cross-civ ownership bugs and a per-city sub-store the engine doesn't have. `GreatWorkManager` owns (a) every `GreatWork` object by stable id, and (b) the slot→workId placement map. A work's *owner*/*location* are derived from which slot holds it. New field on `GameInfo` (field + `clone()` line + `setTransients()` line — update ALL THREE).

- **D2 — Slots are derived from building data, not stored.** A slot is identified by `GreatWorkSlot(civId, cityLocation, buildingName, slotIndex, slotType)`. Slot *existence* is recomputed on demand from each built building's slot markers (D3); only the *contents* (slotKey→workId) are serialized. There is no slot state to store: a sold/destroyed building drops its slots and the manager evicts orphaned placements.

- **D3 — Slot counts come from a new data-driven unique, with the bundled hidden buildings as the fallback signal.** Add `UniqueType.ProvidesGreatWorkSlots` = `"Provides [amount] [param] Great Work slots"` (target `Building`; `param` ∈ Writing/Art/Music). Real visible culture buildings/wonders carry this (added in Increment 6). For not-yet-edited data, `GreatWorkSlotProvider` ALSO detects the mod's existing hidden slot sub-buildings by name pattern. Slot **type** enum is `GreatWorkType { Writing, Art, Music, Artifact }`; Art slots accept both `Art` and `Artifact` works; Writing→Writing, Music→Music.

- **D4 — Tourism is owned by Phase 2b — CROSS-FEATURE CONTRACT.** 2c does NOT compute tourism influence. Each Great Work yields **`2 + theming` tourism**. 2c exposes `GreatWorkManager.getTourismContribution(civ): Float` and plugs it into the 2b tourism-output aggregation seam (`TourismManager.tourismOutputContributors`, a `MutableList<() -> Float>` per the 2b plan). Until 2b lands, Increment 5 ships `getTourismContribution` + a thin self-contained adapter and a TODO at the registration point; it must not depend on 2b types compiling.

- **D5 — Works are public (visibility).** A civ's Great Works (names, types, theming, which building/city) are visible to all (Culture Overview). `PlayerViewProjector` does NOT scrub `gameInfo.greatWorkManager`. The one scrub: drop manager *placements* whose `cityLocation` is not in the viewer's explored set (so an unseen rival city's existence doesn't leak); keep the `GreatWork` objects in the registry.

- **D6 — Creation finds a free slot; no free slot = bank as stockpile (back-compat fallback).** On a Great Artist/Writer/Musician action or a dig, construct a named `GreatWork`, then search the civ's slots (capital-first, then city founded-order) for a free matching slot. Found → place. None free → fall back to `gainStockpiledResource` so the work isn't lost; notify.

- **D7 — Serialization discipline.** `GreatWork` and `GreatWorkManager` implement `IsPartOfGameInfoSerialization`; serialized via `clone()`+`setTransients()`. Every field is copied in `clone()`, and a default-constructed `GreatWorkManager` is a valid empty state.

---

## INCREMENT 1 (current target) — The `GreatWork` object + `GreatWorkManager` registry

**Goal:** a serializable named Great Work object and a GameInfo-level registry that stores works and their slot placements, with clone/round-trip safety. No behavior change yet (the stockpile path still runs).

**New files & state:**
- `core/src/com/unciv/models/ruleset/GreatWorkType.kt` — `enum class GreatWorkType { Writing, Art, Music, Artifact }` with `val resourceName: String` (legacy stockpiled-resource name), `fun fitsSlot(slotType): Boolean` (Artifact and Art both fit an Art slot; else exact match), `companion object { fun fromResourceName(name): GreatWorkType? }`.
- `core/src/com/unciv/logic/civilization/managers/GreatWork.kt` — `class GreatWork : IsPartOfGameInfoSerialization` with `var id: String`, `var type: GreatWorkType = GreatWorkType.Art`, `var name = ""`, `var creatingCivName = ""`, `var artistName = ""`, `var fromEra = ""`, `var turnCreated = 0`, `fun clone()`. No-arg constructor for gdx Json; prefer non-`lateinit` defaults so a partially-written object survives.
- `core/src/com/unciv/logic/civilization/managers/GreatWorkManager.kt` — `class GreatWorkManager : IsPartOfGameInfoSerialization`:
  - `@Transient lateinit var gameInfo: GameInfo`
  - `var works = HashMap<String, GreatWork>()`
  - `var slotPlacements = HashMap<String, String>()` (serialized slotKey → workId; slotKey is a flat `String` from `GreatWorkSlot.key()` so gdx Json needs no custom key handler)
  - `var nextId = 0` (`fun newId(): String = "gw${nextId++}"`)
  - `fun clone()` (deep-copies `works` via `GreatWork.clone()`, copies `slotPlacements`, `nextId`), `fun setTransients(gameInfo)`, `registerWork`/`getWork`/`removeWork`, `placeWork`/`clearSlot`/`getWorkInSlot`, `getWorksOf(civ): List<GreatWork>`.
- `core/src/com/unciv/logic/civilization/managers/GreatWorkSlot.kt` — `data class GreatWorkSlot(civId, cityLocation: HexCoord, buildingName, slotIndex, slotType: GreatWorkType)` with `fun key(): String = "$civId|${cityLocation.x},${cityLocation.y}|$buildingName|$slotIndex"`.

**EXACT file ownership (Increment 1 may edit only these):**
- NEW: the four files above.
- EDIT `core/src/com/unciv/logic/GameInfo.kt`: add `var greatWorkManager = GreatWorkManager()` (near other managers); `toReturn.greatWorkManager = greatWorkManager.clone()` in `clone()`; `greatWorkManager.setTransients(this)` in `setTransients()`.

**Cross-increment contract:** Increments 2-8 use `GreatWorkManager` only through its methods; never re-key `slotPlacements` directly. `GreatWorkSlot.key()` is the single source of slot identity.

**Projection / AI / UI:** none yet.

**Tests** (`tests/src/com/unciv/logic/civilization/managers/GreatWorkManagerTest.kt`, model on `PublicOpinionManagerTest`): register/get/remove works; place/getWorkInSlot/clearSlot; clone round-trip (deep copy, distinct map instances, `getWork` resolves); serialization correctness (a default-constructed manager is a valid empty state — usable after `setTransients`, no NPE).

**Risks:** prefer non-`lateinit` defaults for serialized fields so gdx Json populates them; confirm the enum round-trips.

---

## INCREMENT 2 (next) — Deriving slots from buildings

**Goal:** given a `Civilization`, enumerate its real Great Work slots from built-building data; no contents logic yet.

**New data unique:** EDIT `UniqueType.kt`: add `ProvidesGreatWorkSlots("Provides [amount] [param] Great Work slots", UniqueTarget.Building)` (forward-looking marker; no engine effect beyond being read by the enumerator).

**New code:** `core/src/com/unciv/logic/civilization/managers/GreatWorkSlotProvider.kt` (object):
- `fun getSlotsForCiv(civ): List<GreatWorkSlot>` — per city, per built building, emit slots from (1) `ProvidesGreatWorkSlots` uniques (preferred), and (2) **fallback (D3):** the bundled hidden sub-building name pattern `[<host>] [Great Work of <Type>]` / `… <n>` (single → index 0; numbered → n-1), attributed to the host building name. Detect by parsing the name.
- `fun getFreeSlotsForCiv(civ, type): List<GreatWorkSlot>` — slots absent from `slotPlacements` whose `slotType` accepts `type`, ordered capital-first then city founded order.
- `GreatWorkManager.evictOrphanedPlacements(gameInfo)` — drop placements whose slot no longer exists; **re-bank** the removed work to the (former) owner's stockpile so it isn't silently destroyed.

**EXACT file ownership:** NEW `GreatWorkSlotProvider.kt`; EDIT `UniqueType.kt` (one entry), `GreatWorkManager.kt` (add `evictOrphanedPlacements`).

**Cross-increment contract:** Increment 4 (creation) calls `getFreeSlotsForCiv`; Increments 3/5 call `getSlotsForCiv`. Slot derivation is the single authority; no other code parses building names.

**Tests** (`GreatWorkSlotProviderTest`): a building with `Provides [2] [Art] Great Work slots` → two Art slots, indices 0/1; the bundled-pattern fallback derives a slot; `getFreeSlotsForCiv` excludes occupied and orders capital-first; `evictOrphanedPlacements` evicts + re-banks.

**Risks:** name-pattern parsing brittle — confined to `GreatWorkSlotProvider`; the unique is the durable path (Increment 6 adds it to real buildings).

---

## INCREMENT 3 (then) — Moving works: `GameCommand.MoveGreatWork` + executor + projection

**Goal:** a player moves one of their works between slots (free slot, or swap with another owned work), validated on the authority; rivals' placements in unexplored cities scrubbed.

**New command:** EDIT `network/src/com/unciv/network/command/GameCommand.kt` (new `// region Great Works`, stable `@SerialName("moveGreatWork")`):
```
@Serializable @SerialName("moveGreatWork")
data class MoveGreatWork(val workId: String, val toCityX: Int, val toCityY: Int,
                         val toBuildingName: String, val toSlotIndex: Int) : GameCommand
```
(Source slot derived from where `workId` currently sits. Swap if the destination is occupied by another owned work.)

**New executor branch:** EDIT `core/src/com/unciv/logic/multiplayer/v3/command/CommandExecutor.kt`: add `is GameCommand.MoveGreatWork -> executeMoveGreatWork(...)` (forces the exhaustive branch) + `executeMoveGreatWork` mirroring `executeAdoptPolicy`/`executeSwitchIdeology`: `requireCiv`; resolve work or `CommandException`; validate the acting civ owns it; resolve destination `GreatWorkSlot` against `getSlotsForCiv(actingCiv)` (reject if nonexistent/not owned); validate `work.type.fitsSlot(destSlot.slotType)`; apply via `greatWorkManager.moveWork(work, destSlot)` (clears old, swaps if occupied by an owned work, places).

**Projection (D5):** EDIT `core/src/com/unciv/logic/multiplayer/v3/visibility/PlayerViewProjector.kt`: add `redactGreatWorkPlacements(projected, viewingCivId, visibility)` — for each `slotPlacements` entry whose slot `civId != viewingCivId` AND whose `cityLocation` is not in the viewer's explored set, remove the placement (leave the `GreatWork` object in `works`). Do NOT touch `greatWorkManager` in `scrubCivSecrets` (it's GameInfo-level).

**Cross-increment contract:** uses `getSlotsForCiv` (Increment 2). Projector scrub uses the visibility snapshot.

**Tests:** extend `CommandCatalogueTest` (legal move changes `getWorkInSlot`; illegal — not owned / type mismatch / unknown work → `CommandException`, state untouched); extend `PlayerViewProjectorTest` (placement in an unexplored rival city scrubbed for the viewer, registry kept, owner's own projection keeps the placement).

**Risks:** swap only two **owned** works; reject if destination holds another civ's work. `workId` is a stable string — the reason a dedicated command (not `GenericUnitAction`) is used.

---

## INCREMENT 4 — Creating works as named objects (Great People + archaeology)

**Goal:** Great Artist/Writer/Musician actions and dig payoffs create a **named `GreatWork`** placed into a free slot (D6) instead of (only) banking a stockpiled resource.

**Where it hooks:** the bundled data fires `OneTimeProvideResources` whose handler is in `UniqueTriggerActivation.kt`. Intercept there:
- EDIT `UniqueTriggerActivation.kt` `OneTimeProvideResources` branch: when `resourceName` maps to a `GreatWorkType` (`GreatWorkType.fromResourceName != null`) AND the ruleset has Great-Work slots (probe: any building has `ProvidesGreatWorkSlots` OR legacy hidden slot buildings exist), route through `GreatWorkCreation.createAndPlace(...)` instead of `gainStockpiledResource`. If the ruleset has no slot concept, keep the legacy path (non-BNW untouched).
- NEW `core/src/com/unciv/logic/civilization/managers/GreatWorkCreation.kt` (object): `createAndPlace(civ, type, unit?, notificationText?): GreatWork` — build a `GreatWork` (id from `newId()`, generated `name`, `artistName` from the unit, `fromEra = civ.getEra().name`, `creatingCivName`, `turnCreated`), register, then `getFreeSlotsForCiv(civ, type).firstOrNull()` → place + notify, else `gainStockpiledResource` fallback + notify. Name generation: a hardcoded Kotlin list keyed by type (no new ruleset file).

**EXACT file ownership:** NEW `GreatWorkCreation.kt`; EDIT `UniqueTriggerActivation.kt` (the one `OneTimeProvideResources` branch, keep the stockpile fallback).

**Cross-increment contract:** uses `getFreeSlotsForCiv` (Inc 2) + `placeWork`/`gainStockpiledResource`. The Great Musician's `Triggers a [Perform Concert Tour] event` is untouched (Phase 2b territory); only its provide-resource part is intercepted here.

**Projection:** runs on the authority; placement projected by Increment 3's rules.

**Tests** (`GreatWorkCreationTest`): trigger `OneTimeProvideResources` for `Great Work of Art` with a free Art slot → work placed, type/era/civ set; no free slot → registered AND stockpile incremented (fallback); non-BNW ruleset → legacy stockpile path unchanged.

**Risks:** no double-counting — a placed work isn't also banked (the hidden consume building never fires because the stockpile stays 0). Increment 6 removes the hidden buildings entirely.

---

## INCREMENT 5 — Theming bonuses + tourism contribution (2b contract)

**Goal:** replace the crude all-slots-filled flag with real matching rules producing a Culture + Tourism theming bonus, and expose per-work + theming tourism into the 2b aggregation seam (D4).

**New data / model:**
- Add `UniqueType.GreatWorkThemingBonus("Provides a Theming bonus of [stats] when its Great Works are [param]", UniqueTarget.Building)`, `param` ∈ a small fixed vocabulary: `"of the same era"`, `"by distinct artists"`, `"from the same civilization"`, `"all filled"` (combinations = multiple AND-ed uniques). Reasonable Civ V fidelity per building.
- `core/src/com/unciv/logic/civilization/managers/GreatWorkTheming.kt` (object): `isThemed(civ, building, cityLocation): Boolean` (gather the works in that building's slots, require all filled, then evaluate each rule on `fromEra`/`artistName`/`creatingCivName`); `getThemingStats(...)` (declared Culture stats if themed); `getThemingTourism(...)` (a `Float`, since Tourism is the mod's pseudo-stat resource, not a `Stat`).

**Tourism seam (D4) — CONTRACT with Phase 2b:**
- `GreatWorkManager.getTourismContribution(civ): Float` = `2f * (filled slots owned by civ)` + `Σ getThemingTourism(themed buildings)`.
- NEW `core/src/com/unciv/logic/civilization/Tourism/GreatWorksTourismSource.kt` — registers `{ getTourismContribution(civ) }` into `TourismManager.tourismOutputContributors` (the 2b seam). Until 2b lands, ship `getTourismContribution` + a self-contained adapter + a one-line TODO at the registration point; do not import 2b types. If 2b is merged when this runs, wire it.

**EXACT file ownership:** NEW `GreatWorkTheming.kt`, `GreatWorksTourismSource.kt`; EDIT `UniqueType.kt` (add `GreatWorkThemingBonus`), `GreatWorkManager.kt` (add `getTourismContribution`, theming helpers). Do NOT edit `TourismManager` unless it already exists (then add the one-line source registration in `setTransients`).

**Cross-increment contract:** Increment 6 removes the dead hidden buildings; Increment 8 (AI) calls `isThemed`/`getTourismContribution`.

**Projection:** theming/tourism are derived from public placements; nothing new to scrub.

**Tests** (`GreatWorkThemingTest`): same-era rule themed/not; distinct-artists themed/not; `getTourismContribution` exact value (`3*2 + theming`); the source returns the same number as `getTourismContribution`.

**Risks:** theming Tourism is a `Float` fed to 2b; theming **Culture** is real `Stats.culture` — decide where Culture is injected (keep it a documented TODO if it risks touching `CityStats` broadly; ship tourism contract first).

---

## INCREMENT 6 — Bundled data migration (remove hidden slot/theming buildings, add real markers)

**Goal:** make the bundled BNW ruleset use the new model — visible buildings/wonders carry `Provides [n] [Type] Great Work slots` and `GreatWorkThemingBonus`; delete the ~60 hidden `[X] [Great Work of …]` and `[X] [Theming Bonus]` sub-buildings.

**DATA-only:** EDIT `android/assets/jsons/Civ V - Brave New World/Buildings.json` — add the new uniques to each host building/wonder (Amphitheater, Royal Library, Cathedral, Palace, Museum, Hermitage, Opera House, Globe Theatre, Sydney Opera House, Broadway, Louvre, Uffizi, …); delete the hidden sub-buildings + their `Automatically built`/`Instantly consumes`/`Unavailable <without …>` uniques. Keep the `Great Work of *`/`Artifact` stockpiled resources (the no-free-slot fallback still uses them). Clean dangling `civilopediaText` links to removed buildings.

**EXACT file ownership:** `Buildings.json` (+ translation `.properties` only if a building name was referenced).

**Cross-increment contract:** depends on the `ProvidesGreatWorkSlots` (Inc 2) + `GreatWorkThemingBonus` (Inc 5) uniques. The name-pattern fallback becomes dead for this ruleset but stays for safety.

**Tests:** a ruleset-load/validation test — BNW loads with no unknown-unique errors; a Museum/Louvre yields the expected slot count via `GreatWorkSlotProvider`.

**Risks:** the mod's per-city Tourism conditionals on the hidden buildings are lost; fold them into the 2b model or re-add as host-level `Provides [n] [Tourism]`. Flag for 2b coordination.

---

## INCREMENT 8 — AI: fill slots & theming swaps + minimal UI

**Goal:** light AI that places/swaps works to maximize theming, and a minimal Culture-overview panel to view/move works.

**AI:** EDIT `core/src/com/unciv/logic/automation/civilization/NextTurnAutomation.kt`: add a guarded `optimizeGreatWorks(civ)` in the policy/economy region — (1) place any banked/unplaced works into free matching slots (`greatWorkManager.autoFillFreeSlots(civ)`); (2) greedy bounded swaps that turn an un-themed building themed, evaluated via `GreatWorkTheming.isThemed`/`getTourismContribution` delta; apply via `greatWorkManager.moveWork` directly (AI on authority). O(works²)-bounded, behind `isMajorCiv()`.

**UI (minimal):** NEW `core/src/com/unciv/ui/screens/overviewscreen/GreatWorksOverviewTab.kt` in `EmpireOverviewScreen` — list the viewing civ's works by city/building/slot, show theming status, and (own works) a move/swap affordance issuing `GameCommand.MoveGreatWork` (v3) or `greatWorkManager.moveWork` (single-player). Read-only view of rivals' works (D5). Register the tab in `EmpireOverviewCategories` (locate it).

**EXACT file ownership:** EDIT `NextTurnAutomation.kt` (one method + call site), `GreatWorkManager.kt` (`autoFillFreeSlots`); NEW `GreatWorksOverviewTab.kt`; EDIT the overview tab registry.

**Tests** (`GreatWorkAutomationTest`): 2 banked same-era works + a 2-slot same-era-themed building → both placed + themed; a swap case increases `getTourismContribution`. No automated UI test required.

**Risks:** AI swap loops must terminate (cap iterations, only strictly-improving swaps). v3 UI must route through the command, not mutate canonical state.

---

## Risks (all increments)

- **Serialization correctness:** `greatWorkManager` is serialized via `clone()`+`setTransients()`; every field copied in `clone()` and survives a no-arg deserialize (prefer non-`lateinit` defaults) so a default-constructed manager is a valid empty state.
- **Double-counting tourism** between Inc 4 (Kotlin creation) and Inc 6 (data removal): Inc 4 only banks when no slot is free (placed works never feed the hidden consume buildings); Inc 6 removes them. Land 4→5→6 in order.
- **Host vs joiner:** `GreatWorkManager` is canonical GameInfo state; clients issue `MoveGreatWork` intents only. Works public; the projector scrubs *placements* in unexplored cities.
- **Adding a `GameCommand` is additive** (`@SerialName("moveGreatWork")`); the executor `when` is exhaustive (compile-forced); add the legal+illegal pair to `CommandCatalogueTest`.
- **2b coordination:** the tourism seam (`TourismManager.tourismOutputContributors`) is owned by 2b; Increment 5 ships `getTourismContribution` independently and only registers the source when 2b's seam exists.
