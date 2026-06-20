# BNW Phase 2b — Tourism: real per-rival influence model + cultural victory (agent spec)

Design context: `docs/brave-new-world-adoption.md` §1 (Tier B), §3.1, §4 (Tier B rows **Tourism** + **Cultural Victory** + **Great Musician concert tour**), §5.3. This is the implementation contract for Phase 2b, split into independently-committable increments. **Run a single consolidated build+test and commit each increment.**

Baseline already shipped (Phase 2a): the ideological-pressure seam is live. `IdeologicalPressureSource` (interface + `CivCountPressureSource`) exists at `core/src/com/unciv/logic/civilization/PublicOpinion/IdeologicalPressureSource.kt`; `PublicOpinionManager.recompute(source)` consumes it; `GameInfo.getIdeologicalPressureSource()` is the one-line swap point and currently returns `CivCountPressureSource()`. The projector already scrubs rival `publicOpinion`. **Phase 2b's keystone deliverable is to swap that factory to a tourism-driven source with ZERO change to `PublicOpinionManager`.**

What exists for tourism today (all data, Tier B "fake"): a `Tourism` stockpiled resource (`Civ V - Brave New World/TileResources.json`); buildings/Great-Work-slots bank it via `Provides [n] [Tourism]`; `Accumulated Culture` is banked via `GlobalUniques.json` `Provides [1] [Accumulated Culture] <for every [[Culture] Per Turn]>`; the cultural victory is the two-global-numbers `Have more [Tourism] than each player's [Accumulated Culture]` milestone in `Civ V - Brave New World/VictoryTypes.json`. There is **no native `Stat.Tourism`** (confirmed: `Tourism` appears in `core/src` only inside the Phase 2a seam file). The per-turn Tourism *output* a civ generates is already aggregated by the engine and is readable as the per-turn **supply** of the `Tourism` stockpile resource via `Civilization.getCivResourceSupply().firstOrNull { it.resource.name == "Tourism" }?.amount` (the `Provides [n] [Tourism]` uniques on buildings flow through `city.getResourcesGeneratedByCity()` → `summarizedCivResourceSupply`). The mod keeps banking the `Tourism` stockpile each turn in `TurnManager.startTurn` (the `getCivResourceSupply().filter { isStockpiled } → gainStockpiledResource` loop); we leave that banking intact (harmless; it no longer drives victory) and read the *per-turn supply*, not the accumulated stockpile, as our raw output.

---

## Framing decisions

- **D1 — Tourism output is read from the existing engine supply, not re-derived.** A civ's raw per-turn tourism output = the per-turn supply of the `Tourism` stockpiled resource (`getCivResourceSupply()`), which already aggregates `Provides [n] [Tourism]` from buildings/wonders/Great-Work slots. We do NOT re-walk buildings. The new manager exposes `getBaseTourismOutput(): Float` reading this. **Rationale:** zero data churn, automatically picks up every mod source, and Phase 2c (Great Works objects) contributes tourism through the same supply path (or through the 2c seam below) without 2b knowing Great-Work internals.

- **D2 — Authority-only `GameInfo` state, recomputed each turn and projected (mirrors Phase 2a D2).** A new `TourismManager` on `Civilization` holds the accumulated per-rival influence. It is recomputed by the authority each turn and projected; clients never recompute it (they see rivals' culture/buildings scrubbed, so a client recompute would diverge). The projector scrubs a *rival's* influence-over-others while preserving the viewer's own influence and the publicly-observable culture-defense — exactly how `publicOpinion` is handled today.

- **D3 — Culture-as-defense is lifetime culture (`Civilization.totalCultureForContests`), not the `Accumulated Culture` stockpile.** Civ V compares your accumulated tourism toward a rival against that rival's *lifetime culture*. `totalCultureForContests` already tracks lifetime culture, is serialized + cloned, and is publicly projectable (score-relevant). We threshold the influence level against it. The mod's `Accumulated Culture` stockpile becomes vestigial (still banked, unused by the new victory); leave it for save-compat and the data fallback (Increment 4).

- **D4 — The 2c contract (Great Works seam).** `TourismManager.getBaseTourismOutput()` returns the summed per-turn output; `getTourismMultiplierAgainst(target)` returns the per-target relationship multiplier. Phase 2c will add Great-Work-derived output **and** theming multipliers. To avoid 2c editing 2b internals, `getBaseTourismOutput()` sums (a) the engine `Tourism` supply (D1) **plus** (b) an open extension point: an additive `@Transient` hook `tourismOutputContributors: MutableList<() -> Float>` (default empty, registered at setTransients time). **Contract stated explicitly:** 2c registers a contributor closure (its Great-Work tourism) into `tourismOutputContributors` and adds per-target theming factors via a separate, additive `tourismMultiplierContributors: MutableList<(Civilization) -> Float>`. 2b ships both lists empty; the math already multiplies/sums them, so 2c is purely additive with no 2b edits. If the closure-list approach clashes with serialization expectations, the fallback is a plain overridable method `getGreatWorkTourismOutput(): Float = 0f` that 2c overrides — but prefer the transient-list seam (no inheritance, testable in isolation).

- **D5 — Influence levels and thresholds (standard Civ V).** Influence over a rival is `accumulatedInfluence[rival]` compared as a percentage of that rival's lifetime culture (`totalCultureForContests`):
  - `Exotic` (< 10%), `Exposed` (≥ 10%), `Familiar` (≥ 30%), `Popular` (≥ 60%), `Influential` (≥ 100%), `Dominant` (≥ 200%).
  These match Civ V's standard breakpoints. Encode as an enum `TourismInfluenceLevel { Exotic, Exposed, Familiar, Popular, Influential, Dominant }` with a `fromRatio(ratio: Float)` companion. **Cultural victory = `Influential` (≥ 100%) or better over ALL living major rivals.**

- **D6 — Accumulation rule.** Each turn, for each living major rival `r`: `accumulatedInfluence[r] += round(getBaseTourismOutput() * getTourismMultiplierAgainst(r))`. This is a monotonic accumulator (Civ V tourism toward a civ does not decay; the rival's *culture grows*, which is the "defense" — so the ratio can fall even though the numerator only rises). No smoothing needed (unlike pressure meters): the accumulator IS the smoothing. Store as `HashMap<String, Int>` (rival civName → accumulated influence points). Clamp the per-turn delta at `≥ 0` (war or hostile multipliers can zero output, never make it negative).

- **D7 — No new `GameCommand`.** Tourism is fully passive: recomputed on the authority in `TurnManager.startTurn` and projected. The concert tour already routes through the `Perform Concert Tour` event with `presentation: None` (auto-resolved on the authority for everyone, including AI and human joiners), so it needs no command either — only its *effect* changes (Increment 5). **State explicitly: Phase 2b adds zero `GameCommand` subtypes.** This is documented so the `CommandCatalogueTest` is not touched.

---

## INCREMENT 1 (current target) — `TourismManager` + per-rival influence model

**Goal:** a new authority-only manager on `Civilization` that, each turn, accumulates per-rival tourism influence from this civ's tourism output × per-target multipliers, and exposes the influence *level* toward each living major rival. Pure state + math; no projection/victory/concert wiring yet (those are later increments), but the field/clone/setTransients/recompute call site land here so the state is live and serialized.

**New files & state:**

1. `core/src/com/unciv/logic/civilization/managers/TourismManager.kt` — `class TourismManager : IsPartOfGameInfoSerialization`:
   - `@Transient lateinit var civInfo: Civilization`
   - `var accumulatedInfluence = HashMap<String, Int>()` — rival civName → lifetime accumulated influence points (D6). Defaults empty so old saves deserialize to neutral.
   - `@Transient var tourismOutputContributors = ArrayList<() -> Float>()` — D4 seam for Phase 2c (not serialized; rebuilt empty each load).
   - `@Transient var tourismMultiplierContributors = ArrayList<(Civilization) -> Float>()` — D4 seam for Phase 2c per-target multipliers.
   - `fun clone(): TourismManager` — copies `accumulatedInfluence` into a fresh `HashMap` (transient contributor lists are NOT cloned — they re-register at setTransients).
   - `fun setTransients(civInfo: Civilization)` — sets `this.civInfo`; leaves contributor lists as their (empty) default.
   - `fun getBaseTourismOutput(): Float` — sum of the engine `Tourism` supply (`civInfo.getCivResourceSupply().firstOrNull { it.resource.name == TOURISM_RESOURCE }?.amount?.toFloat() ?: 0f`) plus `tourismOutputContributors.sumOf { it().toDouble() }.toFloat()`. Guard: if the ruleset has no `Tourism` resource, returns 0 (non-BNW rulesets).
   - `fun getTourismMultiplierAgainst(target: Civilization): Float` — Increment 1 returns a flat `1f` plus `tourismMultiplierContributors.sumOf { ... }` (Increment 3 fills in the real diplomacy multipliers; kept separate so this increment compiles + tests cleanly).
   - `fun recompute()` — authority-only per-turn step (D6): for each `other` in `civInfo.gameInfo.civilizations` that `isMajorCiv() && !isDefeated() && other != civInfo`, `accumulatedInfluence[other.civName] = (accumulatedInfluence[other.civName] ?: 0) + max(0, round(getBaseTourismOutput() * getTourismMultiplierAgainst(other)))`. Pruning entries for civs no longer major/alive is **not** done (keeps history; harmless). Skip entirely if the ruleset has no `Tourism` resource.
   - `fun getInfluenceLevelOver(target: Civilization): TourismInfluenceLevel` — `TourismInfluenceLevel.fromRatio(getInfluenceRatioOver(target))`.
   - `fun getInfluenceRatioOver(target: Civilization): Float` — `accumulated = accumulatedInfluence[target.civName] ?: 0`; `defense = target.totalCultureForContests`; return `if (defense <= 0) (if (accumulated > 0) 2.001f else 0f) else accumulated.toFloat() / defense.toFloat()` (mirrors the zero-division guard in `Victory.getMoreCountableThanOtherCivPercent`, expressed as a ratio not a percent).
   - `fun isInfluentialOverAllMajors(): Boolean` — the living-major rivals; `relevant.isNotEmpty() && relevant.all { getInfluenceLevelOver(it) >= TourismInfluenceLevel.Influential }`. Used by Increment 4.
   - `companion object { const val TOURISM_RESOURCE = "Tourism" }`.

2. `core/src/com/unciv/logic/civilization/Tourism/TourismInfluenceLevel.kt` — `enum class TourismInfluenceLevel { Exotic, Exposed, Familiar, Popular, Influential, Dominant }` with `companion object { fun fromRatio(ratio: Float): TourismInfluenceLevel }` using the D5 breakpoints. (Place under a `Tourism/` package mirroring the `PublicOpinion/` package convention used by the seam.)

**EXACT file ownership (Increment 1 edits):**
- `core/src/com/unciv/logic/civilization/Civilization.kt` — **all THREE** of: (a) field `var tourism = TourismManager()` next to `var publicOpinion = ...`; (b) clone line `toReturn.tourism = tourism.clone()` next to the `publicOpinion` clone; (c) `tourism.setTransients(this)` next to `publicOpinion.setTransients(this)`. Missing any one silently drops state across undo/projection (Phase 2a stresses this).
- `core/src/com/unciv/logic/civilization/managers/TurnManager.kt` — in `startTurn`, immediately AFTER the existing `civInfo.publicOpinion.recompute(...)` line and BEFORE `civInfo.updateStatsForNextTurn()`, add `civInfo.tourism.recompute()`.
- New files: `TourismManager.kt`, `TourismInfluenceLevel.kt` (above).

**Cross-increment contract:** Increment 3 fills `getTourismMultiplierAgainst`. Increment 2 (projection) scrubs `accumulatedInfluence` for rivals. Increment 4 (victory) calls `isInfluentialOverAllMajors`. Increment 5 (concert tour) mutates `accumulatedInfluence` directly. Phase 2c registers into the two `@Transient` contributor lists (D4).

**Projection:** none in this increment (Increment 2). Note that until Increment 2 lands, the projector does not scrub `tourism`, so a multi-player v3 game would leak rival influence — Increments 1+2 ship close together; flag in the commit that Increment 2 must land before any v3 playtest.

**AI:** none.

**Tests** (`tests/src/com/unciv/logic/civilization/managers/TourismManagerTest.kt`, modeled on `PublicOpinionManagerTest`):
- Drive output directly by registering a `tourismOutputContributors` closure returning a fixed value (simplest, recommended — exercises the math without ruleset surgery). Add one test that builds a synthetic `Tourism` resource + building to assert `getBaseTourismOutput()` reads the engine supply.
- `accumulates influence each turn`: contributor returns 5f; one rival with `totalCultureForContests = 0`; after N `recompute()` calls, `accumulatedInfluence[rival] == 5*N`.
- `influence level thresholds`: set `accumulatedInfluence[rival]` and `rival.totalCultureForContests` directly; assert `getInfluenceLevelOver` returns Exotic/Exposed/Familiar/Popular/Influential/Dominant at the D5 ratios. Include the zero-defense edge (defense 0, accumulated>0 → Dominant).
- `isInfluentialOverAllMajors`: 2 rivals, both at ratio ≥ 1.0 → true; drop one below → false; no living rivals → false.
- `clone round-trip preserves accumulatedInfluence`: mirror the Phase 2a clone test.

**Risks:** save-compat — `accumulatedInfluence` defaults empty (gdx Json omits default/empty collections; old saves load to neutral). The `@Transient` contributor lists must NOT be serialized (they hold closures); confirm `@Transient` is honored by the gdx Json path (it is, same mechanism as `PublicOpinionManager.civInfo`).

---

## INCREMENT 2 (next) — Projection / `scrubCivSecrets`

**Goal:** a rival's tourism influence over *other* civs is hidden on the wire; the viewer's own influence and the publicly-observable culture-defense survive. Mirror exactly how Phase 2a scrubs `publicOpinion`.

**EXACT file ownership:**
- `core/src/com/unciv/logic/multiplayer/v3/visibility/PlayerViewProjector.kt` — in `scrubCivSecrets(civ)`, in the block right after the Phase 2a `publicOpinion` scrub, add `civ.tourism.accumulatedInfluence.clear()`. `totalCultureForContests` is **left intact** (publicly observable, score-relevant — already projected today, do not change).

**Cross-increment contract:** depends on Increment 1's field. No effect on victory/concert/multiplier increments.

**Projection rationale:** `accumulatedInfluence` keyed by civName is structurally fine empty; the client's `setTransients()` rebuilds `TourismManager.civInfo` and leaves the empty map — a valid "no influence yet" state. The viewer's own `tourism` is on the viewer civ object, which `redactOtherCivSecrets` skips via the `civID == viewerId` guard.

**Tests** — extend `tests/src/com/unciv/logic/multiplayer/v3/PlayerViewProjectorTest.kt` (in or beside the `publicOpinion` scrub test):
- Set `civB.tourism.accumulatedInfluence["SomeCiv"] = 50` (rival) and `civA.tourism.accumulatedInfluence["SomeCiv"] = 40` (viewer). Project for A. Assert `bInView.tourism.accumulatedInfluence.isEmpty()` and `aInView.tourism.accumulatedInfluence["SomeCiv"] == 40`. Assert `bInView.totalCultureForContests` preserved.

**Risks:** none beyond the "scrub leaves a deserializable state" invariant, satisfied by clearing a map.

---

## INCREMENT 3 (then) — Per-target multipliers

**Goal:** make `getTourismMultiplierAgainst(target)` reflect the real Civ V relationship modifiers.

**EXACT file ownership:**
- `core/src/com/unciv/logic/civilization/managers/TourismManager.kt` — replace the Increment-1 stub body of `getTourismMultiplierAgainst` with the real computation, plus private helpers. **Concrete factors** (named `private val` constants for tuning):
  - **At war** with target → `0f` (short-circuit first).
  - Base `1.0`.
  - **Open Borders** (either direction): `+0.25`.
  - **Shared majority religion**: `+0.25` (reuse the `believesSameReligion`-style logic from `DiplomacyManager`).
  - **Ideology** (read `PolicyManager.getCurrentIdeology()` on both): same → `+0.25`; both have an ideology but different → `-0.25`. Neither / one-sided → no change.
  - **Declaration of Friendship**: `+0.25` (`DiplomacyFlags.DeclarationOfFriendship`).
  - **Research Agreement active**: `+0.25` (`DiplomacyFlags.ResearchAgreement`).
  - Add `tourismMultiplierContributors.sumOf { it(target) }` (D4 / Phase 2c theming seam).
  - Final: `max(0f, base + sumOfModifiers)`.
  - **Trade-Route factor:** there is no native city↔city ITR system yet (Tier C). Do **NOT** fabricate a trade-route signal. **Recommended: ship the five diplomacy-backed multipliers above now** (Open Borders, Religion, Ideology, DoF, RA) and leave a `// TODO(Phase 3 ITR): add trade-route tourism bonus once city-to-city routes exist`.

**Cross-increment contract:** consumes Increment 1's manager; feeds Increment 4 (victory) and Increment 6 (keystone). Phase 2c adds theming via `tourismMultiplierContributors`. ITR (Phase 3) wires the trade-route factor.

**Tests** — `TourismManagerTest` additions: war zeroes the multiplier and `recompute` adds 0; open borders raises it; shared religion / same ideology raise it; different ideology lowers it; DoF + RA flags raise it; factors stack and clamp at `≥ 0`. Guard every `getDiplomacyManager` with `?.` (unmet rivals → base 1.0).

**Risks:** reading diplomacy on unmet civs — treat unmet as base 1.0.

---

## INCREMENT 4 (then) — Cultural victory rewrite

**Goal:** replace the two-global-numbers cultural victory with the real condition: **Influential or better over ALL living major rivals** (D5/`isInfluentialOverAllMajors`).

**Design choice:** add an engine-checked `MilestoneType` rather than abusing the data `MoreCountableThanEachPlayer` (which can't express per-rival influence *levels*). Keep `MoreCountableThanEachPlayer` untouched (other rulesets use it).

**EXACT file ownership:**
- `core/src/com/unciv/models/ruleset/Victory.kt`:
  - Add `MilestoneType.InfluentialOverAllCivs("Become Influential over all living civilizations")` (exact text must match the JSON milestone string and have a placeholder shape distinct from `MoreCountableThanEachPlayer`).
  - In `Milestone.hasBeenCompletedBy`, add the branch returning `civInfo.tourism.isInfluentialOverAllMajors()`.
  - In `getVictoryScreenButtonHeaderText` / `getVictoryScreenButtons`, add branches (one button per living major rival, green when `getInfluenceLevelOver(rival) >= Influential`, labelled with rival name + level; respect `shouldHideCivCount()` / `Constants.unknownNationName` like `MoreCountableThanEachPlayer`).
  - In `getFocus`, map `InfluentialOverAllCivs -> Victory.Focus.Culture`.
- `android/assets/jsons/Civ V - Brave New World/VictoryTypes.json` — change the `Cultural` victory's milestone string to `"Become Influential over all living civilizations"`; update `victoryScreenHeader`. Do NOT keep the old data milestone as a fallback (it would let a civ win two ways).

**Victory-check hook location:** no new hook — `victoryManager.getVictoryTypeAchieved()` already iterates `milestoneObjects` calling `hasBeenCompletedBy`. `tourism.recompute()` runs in `startTurn` so influence is current.

**Cross-increment contract:** depends on Increment 1 (`isInfluentialOverAllMajors`, `getInfluenceLevelOver`) and Increment 3 (multipliers make influence accrue).

**Projection:** victory-screen buttons run client-side reading the viewer's OWN `tourism` (preserved) + rivals' `totalCultureForContests` (preserved). Confirm the screen never reads a *rival's* `accumulatedInfluence`.

**Tests** (`VictoryTest.kt` or new `CulturalVictoryTest.kt`): high accumulated influence over all rivals → milestone complete; drop one rival's ratio → not complete; `getVictoryTypeAchieved` returns Cultural; the JSON milestone string parses to `MilestoneType.InfluentialOverAllCivs`; BNW ruleset load/validation passes with the edited JSON.

**Risks:** `MilestoneType` parses by placeholder text — ensure the new text's placeholder shape is unambiguous. Cultural-victory milestones are ruleset data re-read from the bundled JSON on load (safe because the ruleset is bundled; host+joiner parity per §7).

---

## INCREMENT 5 (then) — Concert tour rewrite

**Goal:** the Great Musician's "Perform Concert Tour" boosts your accumulated influence over the rival whose territory the Musician is in, using the per-rival model, instead of the flat self-Tourism bump.

**Design (Option A — Kotlin trigger unique):** the event is `presentation: None` (auto-resolved on the authority, no command needed, D7).
- `core/src/com/unciv/logic/civilization/managers/TourismManager.kt` — add `fun addConcertTourInfluence(target, multiplier = CONCERT_TOUR_FACTOR)` and `const val CONCERT_TOUR_FACTOR = 10f`.
- `core/src/com/unciv/models/ruleset/unique/UniqueType.kt` — add `OneTimeGainTourismInfluenceOverNearbyCiv` (UniqueTarget.Unit / triggerable), near the other `OneTime*` triggerables.
- `core/src/com/unciv/models/ruleset/unique/UniqueTriggerActivation.kt` — add the handler: read the tile owner under the Musician (`unit.getTile().getOwner()`); if a living major rival, `unit.civ.tourism.addConcertTourInfluence(owner)` = `accumulatedInfluence[owner.civName] += round(getBaseTourismOutput() * CONCERT_TOUR_FACTOR)`; raise a notification; consume the unit (reuse the existing `<by consuming this unit>` modifier). Mirror an existing unit-consuming `OneTime*` handler.
- `android/assets/jsons/Civ V - Brave New World/Events.json` — change the `Perform Concert Tour` choice's uniques to use the new unique (+ consume-unit), keep `presentation: None`.

**Cross-increment contract:** consumes Increment 1's manager + Increment 3's `getBaseTourismOutput`.

**Projection:** resolves on the authority (event `None`), mutating canonical `accumulatedInfluence`; viewer sees its own updated influence after projection. No `ResolveEvent` needed.

**AI:** AI Great Musicians already auto-resolve the event; after this change they accrue influence toward the rival they stand in. Moving the Musician toward a good target is Increment 6.

**Tests** (`TourismManagerTest` or `ConcertTourTest.kt`): a unit of civ A inside civ B's territory triggers the unique → `A.tourism.accumulatedInfluence[B.civName]` increased by `round(output × 10)` and the unit consumed. Tour in own/neutral/city-state territory → no change.

**Risks:** resolve tile owner robustly (null on neutral; exclude city-states). Confirm the new UniqueType passes ruleset validation.

---

## INCREMENT 6 (then) — Keystone swap + AI

**Goal (the keystone):** swap `GameInfo.getIdeologicalPressureSource()` to a tourism-driven source so culturally-influential rivals push you toward THEIR ideology, with ZERO change to `PublicOpinionManager`. Plus light AI for cultural-victory pursuit.

**New file & state:**
- `core/src/com/unciv/logic/civilization/PublicOpinion/TourismPressureSource.kt` — `class TourismPressureSource : IdeologicalPressureSource`:
  - `override fun pressureOn(target): Map<PolicyBranch, Float>` — for each `other` living major rival `r` that is **Popular or better** over `target` (`r.tourism.getInfluenceLevelOver(target) >= Popular`) and has an adopted ideology, add weight to that ideology's branch, scaled: `Popular → 1f`, `Influential → 2f`, `Dominant → 3f` (Civ V: only Popular+ exerts ideological pressure). Returns the `PolicyBranch`-keyed map `PublicOpinionManager.recompute` already consumes.
  - **Direction is crucial:** pressure ON `target` comes from civs influential OVER `target` — read each *other* civ's influence over `target`. Computed on the authority where every civ's real `tourism` is present (the scrub only affects wire views).

**EXACT file ownership:**
- `core/src/com/unciv/logic/GameInfo.kt` — `getIdeologicalPressureSource()`: `return if (ruleset.tileResources.containsKey(TourismManager.TOURISM_RESOURCE)) TourismPressureSource() else CivCountPressureSource()` (keeps non-BNW ideology mods on civ-counts).
- `core/src/com/unciv/logic/automation/civilization/NextTurnAutomation.kt` — light, guarded AI hooks: no new building-valuation code (the data `[-50]% weight ... <when [Cultural] Victory is disabled>` markers already steer it); add a small nudge — when close to `isInfluentialOverAllMajors()` bias toward tourism construction and move Great Musicians toward the least-influenced rival's territory.

**Cross-increment contract:** consumes Increments 1+3. After this, Phase 2a's public-opinion is driven by real tourism. Verify by diff that `PublicOpinionManager` is untouched.

**Projection:** unchanged — pressure recomputed on authority; the *result* (`publicOpinion`) is already projected + scrubbed by Phase 2a. `TourismPressureSource` is stateless.

**Tests:** `TourismPressureSourceTest` (R Influential/Dominant over T with ideology X → pressureOn(T) returns X with level-scaled weight; merely-Familiar contributes nothing; no-ideology contributes nothing); an integration test wiring `TourismPressureSource` into `PublicOpinionManager.recompute` proving the pressure direction end-to-end with NO change to `PublicOpinionManager`; `getIdeologicalPressureSource` returns `TourismPressureSource` with Tourism, `CivCountPressureSource` otherwise.

**Risks:** the pressure *direction* is the easiest thing to get backwards — the integration test pins it. Performance O(civs²) same as `CivCountPressureSource`. Authority-only (reads canonical influence).

---

## Risks (all increments)

- **Save-compat (gdx Json omits default/empty fields).** `TourismManager.accumulatedInfluence` defaults empty; old saves deserialize to a neutral manager **only if** `Civilization` has the field + `clone()` + `setTransients()` all updated (Increment 1). The `@Transient` contributor lists must never serialize. Add the clone round-trip test.
- **Host vs joiner (D2).** Influence computed ONLY on the authority and projected; the scrub (Increment 2) prevents leaking a rival's influence-over-others. `TourismPressureSource` reads canonical influence on the authority.
- **No native trade-route system (Tier C).** The "Trade Route" multiplier (Increment 3) is deferred to the ITR work — do NOT fabricate a signal.
- **Victory data edit propagates to existing saves** (ruleset re-read on load) — safe because the ruleset is bundled.
- **Vestigial `Accumulated Culture` + `Tourism` stockpile** left banking (D3) for save-compat; document so a future reader doesn't "fix" the unused milestone.
- **`MilestoneType` text collision** — new `InfluentialOverAllCivs` placeholder shape must be distinct (no `[countable]`).
- **Zero `GameCommand` added (D7).** `CommandExecutor`/`CommandCatalogueTest` intentionally untouched — call this out in each commit.
