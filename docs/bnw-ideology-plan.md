# BNW Phase 2a — Ideologies: data-approximation → Civ-V-faithful (agent spec)

Design context: `docs/brave-new-world-adoption.md` §3.3, §4 (Tier B Ideologies), §5.3. This file is the
implementation contract, split into independently-committable increments. The orchestrator builds+tests+commits
each increment; **subagents do NOT run Gradle and do NOT commit.**

Baseline already shipped: tenet *adoption* works via `GameCommand.AdoptPolicy`; ideology *selection* + free-tenet
events work for v3 joiners via `GameCommand.ResolveEvent`. Ideologies today are pure data (Order/Freedom/Autocracy
policy branches + stockpile tier tokens + selection events). No native ideology/public-opinion/anarchy concept exists.

## Framing decisions
- **D1 — generic ideology detection.** Don't hardcode "Order/Freedom/Autocracy". Add `PolicyBranch.isIdeology`
  (prefer detecting via existing data — the mutual-exclusion `Unavailable <after adopting [otherBranch]>` and/or
  the `Remove [Ideology]` unique — to avoid editing bundled data; a data marker is the fallback) + a helper
  `PolicyManager.getCurrentIdeology(): PolicyBranch?`. All increments read ideology membership only through these.
- **D2 — public opinion is AUTHORITY-ONLY `GameInfo` state**, recomputed by the authority each turn and *projected*;
  clients never recompute it (they see scrubbed rival ideologies, so a client recompute would diverge from the host).
- **D3 — tourism is a SEPARATE feature (Phase 2b).** Define the consumed seam now, stub it:
  `interface IdeologicalPressureSource { fun pressureOn(target: Civilization): Map<PolicyBranch, Float> }`.
  Increment 1 ships a `CivCountPressureSource` (counts of civs per ideology, optional proximity weighting). Phase 2b
  swaps in a `TourismPressureSource` with no change to the public-opinion math.

---

## INCREMENT 1 (current target) — Public-opinion pressure
Goal: a civ with an ideology feels happiness pressure toward the most prevalent surrounding ideology; mismatch →
measured unhappiness ("Dissidents"). Driven by civ-counts for now (D3 stub).

**New state — `PublicOpinionManager : IsPartOfGameInfoSerialization`** (`core/.../managers/PublicOpinionManager.kt`),
held on `Civilization` (field + `clone()` line + `setTransients(civInfo)` line — update ALL THREE in
`Civilization.kt` or state silently drops across undo/projection):
- `var ideologyPressureByBranch: HashMap<String, Float>` (branch name → pressure meter, smoothed across turns)
- `var dissidentUnhappiness: Int` (derived-but-persisted; the happiness hook + projection read it)
- `@Transient lateinit var civInfo: Civilization`
- methods: `clone()`, `setTransients(civInfo)`, `recompute(source: IdeologicalPressureSource)`,
  `getPreferredIdeology(): PolicyBranch?`, `getHappinessFromPublicOpinion(): Int` (≤ 0)

**Pressure seam (D3):** `core/.../civilization/PublicOpinion/IdeologicalPressureSource.kt` (interface) +
`CivCountPressureSource`. `GameInfo.getIdeologicalPressureSource()` factory returns the current impl (one-line swap later).

**Happiness hook:** in `CivInfoStatsForNextTurn.getHappinessBreakdown()` add a civ-wide entry
`"Ideological Pressure" -> civInfo.publicOpinion.getHappinessFromPublicOpinion().toFloat()` (only when the civ has an
ideology and value ≠ 0). Flows into `updateStatsForNextTurn` → `getHappiness()` automatically (no other call sites).

**Per-turn recompute:** in `TurnManager.startTurn`, immediately BEFORE `civInfo.updateStatsForNextTurn()`, call
`civInfo.publicOpinion.recompute(gameInfo.getIdeologicalPressureSource())`.

**Projection:** in `PlayerViewProjector.scrubCivSecrets` (the rivals-only block) clear rival
`publicOpinion.ideologyPressureByBranch` + zero `dissidentUnhappiness` (consistent with adopted-policies already being
scrubbed there; empty/zero is a valid deserializable state). The viewer's OWN opinion stays intact.

**AI:** none needed — AI reads `getHappiness()`, the new term flows in automatically.

**Tests** (model on existing `tests/src/com/unciv/...`):
- `PublicOpinionManagerTest`: ≥3 civs, assign ideologies via `policies.adopt(branchStart)`; assert `recompute` pushes
  pressure toward the majority ideology, a minority-ideology civ gets `dissidentUnhappiness < 0`, majority ~0; assert
  `getHappinessBreakdown()` has the key with the right sign and `getHappiness()` drops; clone round-trip keeps state.
- Extend the v3 projector test: project for civ A → civ B's `publicOpinion` cleared, A's intact.

**Verify-by-outcome (orchestrator does this, not the agent):** full `:tests:test` green + the new tests pass.

---

## INCREMENT 2 (next) — Switching ideology
Add `anarchyTurnsRemaining`/`forcedSwitchPending` to `PublicOpinionManager`. New
`GameCommand.SwitchIdeology(toBranchName)` + `CommandExecutor.executeSwitchIdeology` (mirror `executeAdoptPolicy`,
validate legal switch + not already in anarchy). Anarchy = reuse `Civilization.temporaryUniques` with `[-100]%`
production/science for N turns (verify those uniques exist; add to GlobalUniques if not — do NOT hand-roll a flag).
Tenet loss/refund = reuse `PolicyManager.getCultureRefundMap` + `removePolicy(forRemoval=true)`. Extract
`PolicyManager.switchIdeology(toBranch)` shared by AI + executor. Forced switch surfaces via an `AlertType.Event`
(reuse `ResolveEvent`). AI switch in `NextTurnAutomation` policy region, guarded (big pressure delta, not in anarchy).
Update the v3 command tests (`CommandExecutorTest`, and the per-command catalogue test).

## INCREMENT 3 (then) — Free tenets from wonders / level-3 buildings
Mostly DATA: add `Triggers a [[Ideology]: Free [<branch>] Tenet] event` to qualifying wonders/buildings in the bundled
`Buildings.json` (three `<after adopting [X]>`-gated variants, or a Kotlin `OneTimeFreeTenet` UniqueType only if the
data approach is too verbose). Reuses `freePolicies` + the existing free-tenet events + `ResolveEvent`/`AdoptPolicy`.
No new command, no projector change, no AI change.

---

## Risks (all increments)
- Save-compat: new manager fields default safely on old saves ONLY if `clone()` + `setTransients()` are updated with
  the field. Add a clone/round-trip test.
- Host vs joiner: public opinion computed ONLY on the authority + projected (D2); the projector scrub enforces it.
- Adding a `GameCommand` is additive (stable `@SerialName`); the `CommandExecutor.execute` `when` is exhaustive so a
  new subtype forces a branch (compile-time safety). Check whether a per-command test enumerates the hierarchy.
