# BNW Phase 3 — World Congress (agent spec)

Design context: `docs/brave-new-world-adoption.md` §1 (Tier C), §4 (Tier C row World Congress), §5.3, §7 (the `ProposeTrade`/kotlinx-serialization deferral warning). Implementation contract, split into independently-committable increments.

> **Top note:** subagents do **NOT** run Gradle and do **NOT** commit. The orchestrator runs the single build+test (`:tests:test`) and commits feature.

Baseline today: the **only** diplomatic-vote concept is the legacy UN diplomatic victory in `VictoryManager` (`votesNeededForDiplomaticVictory`, `getUNBuildingAndOwnerNames`, `hasEnoughVotesForDiplomaticVictory`, `getDiplomaticVictoryVoteBreakdown`), driven by `GameInfo.diplomaticVictoryVotesCast: HashMap<String, String?>` + `GameInfo.processDiplomaticVictory()`, scheduled by per-civ flags in `TurnManager.handleDiplomaticVictoryFlags` (`CivFlags.TurnsTillNextDiplomaticVote / ShowDiplomaticVotingResults / ShouldResetDiplomaticVotes`), founded by `UniqueType.OneTimeTriggerVoting` (the UN wonder), AI votes in `NextTurnAutomation.tryVoteForDiplomaticVictory`, human votes via `NextTurnAction.WorldCongressVote` → `DiplomaticVotePickerScreen` → `Civilization.diplomaticVoteForCiv`. There is **no** native World Congress: no sessions, proposals, resolutions, host, delegates-from-CS, world projects, or embargoes.

## Framing decisions

- **D1 — World Congress is GLOBAL (`GameInfo`-level) authoritative state**, NOT per-civ. Mirror `BarbarianManager`: a `WorldCongressManager : IsPartOfGameInfoSerialization` field on `GameInfo` with `@Transient lateinit var gameInfo`, wired into `GameInfo.clone()` (a `congress = congress.clone()` line) and `GameInfo.setTransients()` (`congress.setTransients(this)` after `barbarians.setTransients(this)`). The field default-constructs to a valid **"no congress founded yet"** state (`isFounded = false`) and needs no migration. Do **NOT** bump `CompatibilityVersion.CURRENT_COMPATIBILITY_NUMBER`.

- **D2 — flat kotlinx DTO layer (the §7 warning).** `GameCommand` is kotlinx `@Serializable`; the canonical congress *state* uses libgdx-JSON (`IsPartOfGameInfoSerialization`). Two separate type families — never embed a state object in a command. Both new commands carry **only flat primitives**; the authority re-resolves them against canonical state. Proposals are server-canonical, referenced by **int id**.

- **D3 — authority computes, clients render projections.** All resolution effects, vote tallies, delegate counts, and phase transitions happen ONLY on the authority. The UI emits `GameCommand`s and reads the projected `WorldCongressManager`; it never mutates congress state. `PlayerViewProjector` scrubs other civs' not-yet-public vote intentions.

- **D4 — turn-loop hook mirrors the existing diplomatic-vote machinery.** Add `GameInfo.processWorldCongress()` (twin of `processDiplomaticVictory()`, idempotent via a serialized `lastProcessedTurn` guard) called from `TurnManager.startTurnFlags` (twin of `handleDiplomaticVictoryFlags`). Reaches both single-player and v3 `nextTurn` for free. Session cadence = "every N turns" via a `turnsUntilNextSession` countdown.

- **D5 — Diplomatic Victory = EXTEND, not replace (§7 / task #6).** The UN diplomatic victory stays as-is for the legacy `OneTimeTriggerVoting` path. World Congress models "elect a World Leader" as a resolution that, when it passes, **writes into the same `diplomaticVictoryVotesCast` map and calls `processDiplomaticVictory()`** — reusing `votesNeededForDiplomaticVictory`/`hasEnoughVotesForDiplomaticVictory` unchanged. Increment 4 reconciles them (guard on `congress.isFounded`).

- **D6 — generic founding detection (no hardcoded tech name).** A `UniqueType.OneTimeFoundWorldCongress` trigger (new, additive) OR — fallback needing no new unique — auto-found when any major civ first reaches a configurable era (`ModConstants`-driven, default a Renaissance-tier era number). Increment 1 ships the era-based auto-found (works on the existing bundled ruleset, zero data edits); the explicit trigger is an optional later refinement.

- **D7 — resolution catalog is data-driven where possible, bespoke Kotlin where not.** Each resolution = a `ResolutionType` enum entry + metadata + an `apply`/`revoke` strategy. Effects the engine already speaks (happiness, embargo, per-civ yields) use existing primitives (`Civilization.temporaryUniques` / civ-wide uniques / resource bans); effects with no primitive (World's Fair) are bespoke. Ship a **small subset first** (Increment 2), grow it (Increment 3).

---

## INCREMENT 1 — Founding + delegates + session schedule state

**Goal:** a `WorldCongressManager` on `GameInfo` that founds itself (D6), elects a host, computes per-civ delegate counts (base + capital + allied city-states + policy/wonder bonuses), and counts down to the next session — with full clone/setTransients/save-compat. **No commands, no resolutions, no UI yet.** Sessions just transition phase with an empty proposal list.

**New files & state:**
- `core/src/com/unciv/logic/civilization/managers/WorldCongressManager.kt` — `class WorldCongressManager : IsPartOfGameInfoSerialization`:
  - `@Transient lateinit var gameInfo: GameInfo`
  - `var isFounded = false`; `var hostCivId = ""`; `var foundingTurn = -1`; `var turnsUntilNextSession = 0`; `var currentPhase = CongressPhase.Idle` (enum `Idle, Proposing, Voting, Resolved`); `var sessionNumber = 0`; `var lastProcessedTurn = -1` (serialized idempotency guard, D4)
  - `var delegateCounts = HashMap<String, Int>()` (civId → count; recomputed each session, persisted for projection/UI)
  - `var activeProposals = ArrayList<CongressProposal>()` (empty this increment; type defined now)
  - `var enactedResolutions = ArrayList<EnactedResolution>()` (empty this increment)
  - `var nextProposalId = 1` (monotonic wire-key source, D2)
  - methods: `clone()` (deep-copy lists via element `.clone()`), `setTransients(gameInfo)` (sets `gameInfo`, calls `setTransients` on elements), `tryFoundCongress()` (D6), `electHost()` (most delegates, tie by civId), `recomputeDelegates()`, `getDelegateCount(civ)`, `getTurnsBetweenSessions()` = `(N * gameInfo.speed.modifier).toInt()` (N from `ModConstants` `worldCongressSessionTurns`, default ~15), `getFoundingEraNumber()` (ModConstants, default Renaissance-tier), `advanceTurn()` (tick: `tryFoundCongress()`; if founded decrement `turnsUntilNextSession`; at 0 begin a session — Inc 1 just flips phase, recomputes delegates, re-elects host, `sessionNumber++`, resolves to Idle, resets the countdown), `@Readonly getMemberCivs()` = alive major civs (city-states grant delegates to their ally, NOT members).
- `core/src/com/unciv/logic/civilization/CongressProposal.kt` — `class CongressProposal : IsPartOfGameInfoSerialization`: `var id = 0`, `var resolutionType = ""` (enum name as String, resolved via `ResolutionType.valueOf`), `var proposerCivId = ""`, `var targetCivId = ""`, `var choiceArg = ""`, `var votesFor = HashMap<String,Int>()`, `var votesAgainst = HashMap<String,Int>()`, `clone()`, `setTransients(gameInfo)` no-op, `@Readonly totalFor()/totalAgainst()`.
- `core/src/com/unciv/logic/civilization/EnactedResolution.kt` — `class EnactedResolution : IsPartOfGameInfoSerialization`: `resolutionType`, `targetCivId`, `choiceArg`, `enactedTurn`, `sessionNumber` + `clone()`.
- `core/src/com/unciv/logic/civilization/ResolutionType.kt` — `enum class ResolutionType` (may ship empty/placeholders in Inc 1; behavior lands Inc 2/3).

**Delegate formula** (`getDelegateCount`): base `1` per member major; `+1` if `hostCivId`; `+1` per alive city-state whose `allyCiv == civ` (`gameInfo.getAliveCityStates().count { it.allyCiv == civ }`); `+N` from a new `UniqueType.WorldCongressDelegates` (`civ.getMatchingUniques(...)`).

**EXACT file ownership (edit existing):**
- `core/src/com/unciv/logic/GameInfo.kt`: field `var congress = WorldCongressManager()` (near `barbarians`); `toReturn.congress = congress.clone()` in `clone()`; `congress.setTransients(this)` in `setTransients()` (after `barbarians.setTransients(this)`); `fun processWorldCongress()` (twin of `processDiplomaticVictory`) — `if (congress.lastProcessedTurn != turns) { congress.lastProcessedTurn = turns; congress.advanceTurn() }`.
- `core/src/com/unciv/logic/civilization/managers/TurnManager.kt`: in `startTurnFlags()`, after `handleDiplomaticVictoryFlags()`, add `civInfo.gameInfo.processWorldCongress()`.
- `core/src/com/unciv/models/ruleset/unique/UniqueType.kt`: add `WorldCongressDelegates` (Global, e.g. "Provides [amount] Delegate(s) in the World Congress") and (D6, optional) `OneTimeFoundWorldCongress`.
- `core/src/com/unciv/models/ruleset/ModConstants.kt` (verify it's where `cityStateElectionTurns` etc. live): add `worldCongressSessionTurns` (default 15) + founding era number.

**Cross-increment contract:** `activeProposals` keyed by `id`; `nextProposalId` is the id source; `delegateCounts` recomputed at session start; `ResolutionType` is the single source of resolution identity (name-as-String); `getMemberCivs()` is the canonical participant filter. Later increments build on these without changing shapes.

**Projection (this increment):** congress is GLOBAL state on the cloned `GameInfo` → survives `gameInfo.clone()` automatically. Nothing secret yet (founding/host/schedule/delegates are public). **No projector edit in Inc 1** — add a projector test asserting projected `congress.isFounded/hostCivId/delegateCounts` match canonical (regression anchor for Inc 2's vote scrub).

**AI:** none (founding + scheduling automatic).

**Tests** (`tests/src/com/unciv/logic/civilization/managers/WorldCongressManagerTest.kt`): founds when a major reaches the founding era (assert `isFounded`, `hostCivId` non-empty, countdown > 0); delegate count includes base + host + allied city-states; `WorldCongressDelegates` unique adds; session counts down and cycles phase (`sessionNumber` increments, schedule resets); clone round-trip preserves state (incl. empty lists); fresh `WorldCongressManager()` defaults to not-founded/Idle/empty (save-compat anchor).

**Risks:** the `lastProcessedTurn` guard MUST be serialized so a mid-session save/load doesn't double-process. Confirm `processWorldCongress` does real work exactly once per `nextTurn`. Verify `ModConstants` is the right home for the constants.

---

## INCREMENT 2 — Propose + vote commands + resolution-apply for a small subset

**Goal:** a founded congress runs a real session: at `Proposing`, members propose; at `Voting`, civs cast delegates; on resolve, enacted resolutions apply. Ship a **small subset** using existing primitives only: **Ban Luxury (embargo), Sciences Funding, Arts Funding** (StandingArmyTax/sanctions move to Inc 3). Two flat kotlinx commands. Minimal AI propose/vote.

**New flat kotlinx commands** (`network/.../command/GameCommand.kt`, additive `@SerialName`, new "World Congress" region):
```
@Serializable @SerialName("proposeResolution")
data class ProposeResolution(val resolutionType: String, val targetCivId: String = "", val choiceArg: String = "") : GameCommand

@Serializable @SerialName("castCongressVote")
data class CastCongressVote(val proposalId: Int, val delegates: Int, val voteFor: Boolean) : GameCommand
```
Pure primitives (heeding §7): the proposal is server-canonical, referenced by `proposalId`.

**Executor** (`CommandExecutor.kt` — two `when` branches + two methods, mirroring `executeAdoptPolicy`/`executeDemandResponse`):
- `executeProposeResolution`: require member, `isFounded`, `currentPhase == Proposing`, civ hasn't already proposed (cap ≤1 per member, ≤2 total per session), `ResolutionType.valueOf` exists + currently proposable. Create a `CongressProposal` (`id = nextProposalId++`), add to `activeProposals`. `CommandException` on any violation, state untouched.
- `executeCastCongressVote`: require `currentPhase == Voting`, proposal exists, member, civ hasn't already cast on this proposal, **full bloc** `delegates == getDelegateCount(actingCiv)` (matches Civ V, simplifies AI), all FOR or all AGAINST. Record into `votesFor`/`votesAgainst`. If all members have voted on all proposals → `congress.resolveSession()`.

**`WorldCongressManager` additions:**
- `advanceTurn()`: when countdown hits 0 + phase Idle → `beginSession()` (recompute delegates, re-elect host, `currentPhase = Proposing`, `sessionNumber++`, clear `activeProposals`, notify members). **One-turn Proposing window, one-turn Voting window** (AI proposes/votes inside the authority tick; humans act via command; un-acted blocs auto-resolve as abstentions at window end — null = abstain, mirroring `diplomaticVictoryVotesCast`). This keeps sessions deterministic and bounded.
- `resolveSession()`: per proposal `enacted = totalFor() > totalAgainst()` (ties fail). For each passing: build `EnactedResolution`, add, `applyResolution(...)`. Set phase Resolved→Idle, reset countdown, clear `activeProposals`, notify outcomes.
- `applyResolution(res)`: dispatch on `ResolutionType`. **This subset:**
  - **BanLuxury** (`choiceArg` = luxury name): store in a new `var bannedLuxuries = HashSet<String>()` on the manager; add ONE check in the luxury-happiness path (locate the happiness-from-luxuries call site in `CityStats`/`CivInfoStatsForNextTurn`) to skip banned luxuries for ALL civs, guarded on `congress.isFounded`.
  - **SciencesFunding / ArtsFunding**: grant every member a civ-wide `[+N]% Science`/`[+N]% Culture` via `Civilization.temporaryUniques` (the mechanism the ideology plan's anarchy uses — verify the API). No bespoke hook (percent-yield uniques already flow through stats).
- populate `ResolutionType` with these entries + metadata: `needsTarget`, `needsChoiceArg`, `choiceArgKind` (`Luxury`/`Civ`/`None`), `isProposable(congress, proposer)`.

**AI** (`NextTurnAutomation.kt`, new `automateWorldCongress(civInfo)` near `tryVoteForDiplomaticVictory`): if Proposing + not proposed → pick a self-benefiting resolution; if Voting → vote FOR if beneficial/neutral, AGAINST if harmful (cast full bloc), reusing `opinionOfOtherCiv`. Authority-only (AI civs).

**Projection** (`PlayerViewProjector` — new `scrubCongressSecrets(projected, viewerId)` after `redactOtherCivSecrets`): during `Voting`, remove `votesFor`/`votesAgainst` entries keyed by civIds *other than the viewer* (in-progress votes hidden until resolution); keep the viewer's own vote; active proposal metadata + delegate counts public; resolved results public.

**Cross-increment contract:** `applyResolution` dispatch + `ResolutionType` metadata are Inc 3's extension point (add enum entries + branches, no command/state-shape change). `resolveSession` pass rule (FOR > AGAINST) fixed. `bannedLuxuries`/effect-flags are the durable effect store re-read on load.

**Tests** (extend `WorldCongressManagerTest` + `CommandCatalogueTest` legal+illegal per command): propose adds proposal / rejected when not Proposing / by non-member / unknown type; vote records / rejected when not Voting / exceeding delegates / twice / unknown id; passing SciencesFunding grants the science unique to members + records `enactedResolutions`; BanLuxury suppresses that luxury's happiness; tied vote fails; projector during Voting scrubs rival votes, keeps own + delegate counts + proposal metadata; an AI member votes AGAINST a BanLuxury on a luxury it trades.

**Risks:** the one bespoke happiness hook (BanLuxury) must be located precisely + guarded on `congress.isFounded && luxury in bannedLuxuries` (non-congress games unaffected). Full-bloc voting must agree between executor + AI. The window-end abstention path is mandatory (a human who never votes must not freeze the congress).

---

## INCREMENT 3 — More resolutions + world projects

**Goal:** broaden the catalog and add **World Projects** (World's Fair / International Games) as a production-contribution competition with ranked rewards.

**Resolutions added** (each = enum entry + `applyResolution` branch + `isProposable`): **Trade Sanctions** (`targetCivId`; `var sanctionedCivs = HashSet<String>()` read by the trade-eval path); **StandingArmyTax** (global unit-gold-upkeep flag read by maintenance); **WorldReligion** (`choiceArg` = religion; happiness/pressure); **WorldIdeology** (`choiceArg` = ideology branch; ties into Phase 2a public opinion if present, else a flat happiness modifier); **NuclearNonProliferation** (a `cannotBuild` flag), **HistoricalLandmarks**, **CulturalHeritageSites**, **ScholarsInResidence** (civ-wide temporary-unique yields). Ship pure-unique ones first; bespoke-hook ones (sanctions, proliferation) guarded on `congress.isFounded`.

**World Projects:** new `var activeWorldProject: WorldProject? = null` (`class WorldProject : IsPartOfGameInfoSerialization` with `projectType`, `startTurn`, `endTurn`, `contributions: HashMap<String,Int>`). A passing World's Fair/International Games resolution starts a project; members contribute via a special city construction (ruleset-gated, buildable only while active) whose production banks into `contributions`; on `endTurn` rank civs and grant tiered rewards (free tenet / culture / gold) via triggered uniques. Resolves on a timer in `advanceTurn`.

**EXACT file ownership:** `WorldCongressManager.kt` (branches + project methods), `ResolutionType.kt` (entries + metadata), NEW `core/.../civilization/WorldProject.kt`, the World's Fair construction in bundled JSON (data), the trade-eval/maintenance/construction-ban hook sites (each guarded on `isFounded`).

**Projection:** world-project `contributions` + sanctioned/banned flags are public (kept). No new scrub.

**AI:** extend `automateWorldCongress` — contribute to projects when idle production exists; vote against sanctions on self; favor resolutions matching its victory focus.

**Tests:** per-resolution enact-effect tests; world-project ranks + rewards; sanctions block trade with target; AI votes against self-sanction; clone round-trip with an active project.

**Risks:** each bespoke hook is a separate insertion point — guard every one on `congress.isFounded`. World-project construction ruleset-gated to active projects only.

---

## INCREMENT 4 — Diplomatic Victory reconciliation (extend, not replace)

**Goal (D5 / task #6):** model "elect a World Leader" as a resolution that wins when passed — reusing the `VictoryManager` UN flow with minimal churn.

**Mechanic:** add `ResolutionType.WorldLeaderElection` (`choiceArg` = candidate civId). When it resolves, write the bloc tallies into `gameInfo.diplomaticVictoryVotesCast` (each member → backed candidate, weighted by delegates) and call `gameInfo.processDiplomaticVictory()` — reusing `hasEnoughVotesForDiplomaticVictory`/`votesNeededForDiplomaticVictory`/`getDiplomaticVictoryVoteBreakdown` **unchanged**; congress becomes a new front-end. **Reconcile:** `OneTimeTriggerVoting` (UN wonder) + the `CivFlags.TurnsTillNextDiplomaticVote` machinery stay for rulesets WITHOUT a founded congress; when `congress.isFounded`, route the world-leader vote through the congress and guard `TurnManager.handleDiplomaticVictoryFlags` to NOT double-schedule legacy votes. The UN-owner "+2 votes" maps onto the host's +1 delegate (don't double-count).

**EXACT file ownership:** `WorldCongressManager.kt` (`WorldLeaderElection` enact → writes `diplomaticVictoryVotesCast` + calls `processDiplomaticVictory`), `ResolutionType.kt`, a small guard in `TurnManager.handleDiplomaticVictoryFlags` (skip when `congress.isFounded`). `VictoryManager.kt` ideally **untouched**; if delegate-weighted tallies need a hook, add a thin `VictoryManager.recordCongressVotes(...)` rather than rewriting.

**Projection:** candidate tallies follow Inc-2's vote-scrub; the `diplomaticVictoryVotesCast` write happens at resolution (already public).

**AI:** reuse `tryVoteForDiplomaticVictory` for candidate choice; when congress founded, cast the delegate bloc via `CastCongressVote`.

**Tests:** world-leader election with enough delegates wins the diplomatic victory; legacy UN vote still works without a congress (regression); founded congress suppresses legacy vote scheduling.

**Risks:** the two front-ends must be mutually exclusive at runtime (guard on `isFounded`). Verify `calculateDiplomaticVotingResults` can express delegate weights; if not, add the thin `recordCongressVotes` hook rather than distorting the `HashMap<String,String?>` map.

---

## INCREMENT 5 — UI (`WorldCongressScreen`)

**Goal:** a minimal, functional full-screen UI to view proposals, propose, and cast votes — **emits `GameCommand`s only**, opened from the world screen.

**New file:** `core/src/com/unciv/ui/screens/...` `WorldCongressScreen.kt` (mirror `DiplomaticVotePickerScreen`). Reads `worldScreen.viewingCiv.gameInfo.congress` (the *projected* manager — already redacted in v3). Shows founding status, host, your delegate count, phase, active proposals (type/target/proposer + public tallies), enacted history, active world-project leaderboard. Proposing → "Propose" buttons per available `ResolutionType` (filtered by `isProposable`) with target/choiceArg pickers → `GameCommand.ProposeResolution`. Voting → FOR/AGAINST per proposal → `GameCommand.CastCongressVote(proposalId, delegates=yourDelegateCount, voteFor)`.
- **Command emission:** locate the existing single-player-vs-v3 dispatch convention used by `PolicyPickerScreen`/`DiplomaticVotePickerScreen` for `AdoptPolicy` etc. and **reuse it verbatim** (v3 client sends a `GameCommand`; single-player calls the engine/executor directly).
- **Opened from:** add `NextTurnAction.WorldCongressSession` (twin of `WorldCongressVote`) in `core/.../worldscreen/status/NextTurnAction.kt`: `isChoice = congress.isFounded && currentPhase in (Proposing, Voting) && viewingCiv is a member who hasn't acted`; `action = pushScreen(WorldCongressScreen(viewingCiv))`. Reuse the `beginSession` notification to prompt.

**EXACT file ownership:** NEW `WorldCongressScreen.kt`; EDIT `NextTurnAction.kt`. No state/command changes.

**Tests:** UI is hard to unit-test headlessly — keep logic in the (tested) model. At most a smoke test that the screen constructs without throwing given a founded congress, and a `NextTurnAction.WorldCongressSession.isChoice` logic test if extractable. Primary verification is manual.

**Risks:** the single-player-vs-v3 dispatch must match the existing screens exactly (read one and copy precisely) or it desyncs.

---

## Risks (all increments)

- **Serialization correctness (D1):** `GameInfo.congress` + every nested element MUST default-construct to a valid not-founded state and be in `clone()` + `setTransients()`. Do NOT bump `CURRENT_COMPATIBILITY_NUMBER` (no migration needed). Clone-round-trip + fresh-default test per increment.
- **Serialization split (D2 / §7):** `GameCommand` = kotlinx flat primitives; congress state = libgdx-JSON. Never embed state in a command — reference proposals by int `proposalId`.
- **Exhaustive `when`:** adding the two commands forces branches in `CommandExecutor.execute` (compile error until handled) + a `CommandCatalogueTest` pair.
- **Authority-only (D3):** all tallies/effects/phases on the authority; clients send commands + render the projected view. The Inc-2 vote-scrub is mandatory (anti-maphack parity with `scrubCivSecrets`).
- **Turn-loop idempotency (D4):** `processWorldCongress()` must do real work once per game-turn (serialized `lastProcessedTurn` guard).
- **Bespoke-effect blast radius:** BanLuxury/sanctions/proliferation each insert a hook into a hot path — every one guarded on `congress.isFounded` + membership so non-congress rulesets and the legacy diplomatic-vote game are bit-for-bit unaffected.
- **Diplomatic-victory dual front-end (D5):** congress election + legacy UN vote MUST be mutually exclusive at runtime; delegate weighting must be expressible through `diplomaticVictoryVotesCast` (else add a thin `recordCongressVotes` hook).
- **Session-window stalls:** Proposing/Voting windows auto-resolve un-acted members as abstentions at window end so a human who never opens the screen can't freeze the congress.
