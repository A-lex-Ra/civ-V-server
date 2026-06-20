// SPDX-License-Identifier: LicenseRef-Unciv-v3-ViewOnly
// Copyright (c) 2026 Alexander Rastorguev (A-lex-Ra) <rastorguev2047@gmail.com>
//
// Part of the Unciv multiplayer-v3 netcode — view-only, NOT under the Mozilla
// Public License that covers the rest of this repository. No right to use,
// copy, modify, run, or distribute is granted without written permission;
// permission is gladly given on request (email or GitHub issue).
// Full terms: /LICENSE.v3  ·  License map: /LICENSING.md

package com.unciv.network.command

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A single player-initiated intent (move this unit there, found a city here, end the turn, …).
 *
 * Commands carry **only the intent** — ids and targets — never the resulting state. The
 * authority validates and applies each command against the canonical `GameInfo` (see the
 * `CommandExecutor` in `:core`), which is the only place MP state is mutated.
 *
 * The hierarchy is **sealed and additive**: new subtypes get a new stable [SerialName] and
 * receivers reject unknown subtypes cleanly rather than corrupting state. This keeps the wire
 * format forward/backward compatible across protocol revisions.
 *
 * Phase 0: only a couple of representative stubs exist. The full catalogue
 * (`AttackUnit`, `FoundCity`, `SetCityProduction`, `BuyConstruction`, `ChooseTech`,
 * `AdoptPolicy`, `DiplomacyAction`, `AcceptTrade`, …) is filled in from Phase 2 onward.
 */
@Serializable
sealed interface GameCommand {

    /** Marks the issuing player's turn as finished. Triggers inter-turn processing on the host. */
    @Serializable
    @SerialName("endTurn")
    data object EndTurn : GameCommand

    /**
     * Move a unit from one tile to another.
     *
     * Phase 0 placeholder to demonstrate the sealed hierarchy and serialization; the real
     * routing through the command bus lands in Phase 2.
     */
    @Serializable
    @SerialName("moveUnit")
    data class MoveUnit(
        val unitId: Int,
        val fromX: Int,
        val fromY: Int,
        val toX: Int,
        val toY: Int
    ) : GameCommand

    /**
     * Found a city with the acting civ's settler-type unit standing on tile ([x], [y]).
     *
     * The unit is identified by acting-civ + tile (like [MoveUnit]); there is no unit id on the
     * wire. The authority delegates to the engine's Found-City unit-action path so the settler is
     * consumed and all founding side effects run.
     */
    @Serializable
    @SerialName("foundCity")
    data class FoundCity(
        val x: Int,
        val y: Int
    ) : GameCommand

    /**
     * Set the current construction of the acting civ's city centered on tile ([cityX], [cityY])
     * to [constructionName] (a building or unit name from the ruleset).
     */
    @Serializable
    @SerialName("setCityProduction")
    data class SetCityProduction(
        val cityX: Int,
        val cityY: Int,
        val constructionName: String
    ) : GameCommand

    /**
     * Set the acting civ's current research goal to [techName].
     *
     * Mirrors the TechPickerScreen: the engine plots the prerequisite path to the chosen tech and
     * stores it in the civ's `techsToResearch` queue.
     */
    @Serializable
    @SerialName("chooseTech")
    data class ChooseTech(
        val techName: String
    ) : GameCommand

    /**
     * Promote the acting civ's unit on tile ([x], [y]) with promotion [promotionName].
     *
     * The unit is identified by acting-civ + tile. The promotion must be currently available to
     * that unit (right unit type, prerequisites met, not already taken, enough XP).
     */
    @Serializable
    @SerialName("promoteUnit")
    data class PromoteUnit(
        val x: Int,
        val y: Int,
        val promotionName: String
    ) : GameCommand

    /**
     * Invoke a simple unit action (Fortify, Sleep, SleepUntilHealed, Explore, Disband, …) on the
     * acting civ's unit on tile ([x], [y]).
     *
     * [actionType] is the name of a [com.unciv.models.UnitActionType] enum constant. This single
     * command reuses the engine's whole `UnitActions` catalogue via `invokeUnitAction`; only
     * actions currently available to the unit succeed.
     */
    @Serializable
    @SerialName("genericUnitAction")
    data class GenericUnitAction(
        val x: Int,
        val y: Int,
        val actionType: String
    ) : GameCommand

    /**
     * The acting civ's unit on tile ([attackerX], [attackerY]) attacks an enemy on tile
     * ([targetX], [targetY]).
     *
     * Covers melee (move adjacent + strike) and ranged (strike within range) attacks; the
     * authority resolves the engine's own [com.unciv.logic.battle.AttackableTile] for the pair and
     * delegates to the combat entry point. The attacker is identified by acting-civ + tile.
     */
    @Serializable
    @SerialName("attackUnit")
    data class AttackUnit(
        val attackerX: Int,
        val attackerY: Int,
        val targetX: Int,
        val targetY: Int
    ) : GameCommand

    // region Diplomacy

    /**
     * The acting civ declares war on [targetCivName].
     *
     * Mirrors the DiplomacyScreen "Declare war" button; the authority validates the engine's own
     * `canDeclareWar()` gate before delegating to `DiplomacyManager.declareWar()`.
     */
    @Serializable
    @SerialName("declareWar")
    data class DeclareWar(
        val targetCivName: String
    ) : GameCommand

    /**
     * The acting civ makes peace with [targetCivName] (must currently be at war).
     *
     * Mirrors the DiplomacyScreen "Negotiate Peace" path, delegating to `DiplomacyManager.makePeace()`.
     */
    @Serializable
    @SerialName("makePeace")
    data class MakePeace(
        val targetCivName: String
    ) : GameCommand

    /**
     * The acting civ signs a Declaration of Friendship with [targetCivName].
     *
     * In single-player this is the *acceptance* step of a friendship offer (AlertPopup);
     * the authority validates `canSignDeclarationOfFriendshipWith()` and delegates to
     * `DiplomacyManager.signDeclarationOfFriendship()`.
     */
    @Serializable
    @SerialName("declareFriendship")
    data class DeclareFriendship(
        val targetCivName: String
    ) : GameCommand

    /**
     * The acting civ signs a Defensive Pact with [targetCivName].
     *
     * The pact duration is derived on the authority from the game speed (deal duration), matching the
     * way the trade system builds a defensive-pact offer. Validated with
     * `canSignDefensivePactWith()`, then `DiplomacyManager.signDefensivePact(duration)`.
     */
    @Serializable
    @SerialName("defensivePact")
    data class DefensivePact(
        val targetCivName: String
    ) : GameCommand

    /** The acting civ denounces [targetCivName]. Delegates to `DiplomacyManager.denounce()`. */
    @Serializable
    @SerialName("denounce")
    data class Denounce(
        val targetCivName: String
    ) : GameCommand

    /**
     * The acting civ gifts [gold] gold to the city-state [targetCivName].
     *
     * Mirrors the DiplomacyScreen city-state "Give a Gift" button; the authority delegates to
     * `CityStateFunctions.receiveGoldGift`, which moves the gold and grants influence.
     */
    @Serializable
    @SerialName("giftGold")
    data class GiftGold(
        val targetCivName: String,
        val gold: Int
    ) : GameCommand

    /**
     * The acting civ responds to a pending demand from [targetCivName].
     *
     * [demandName] is the name of a [com.unciv.logic.civilization.diplomacy.Demand] enum constant
     * (the demand the *other* civ raised, recorded as a `PopupAlert` on the acting civ). [agree]
     * picks `agreeToDemand`/`refuseDemand`.
     */
    @Serializable
    @SerialName("demandResponse")
    data class DemandResponse(
        val targetCivName: String,
        val demandName: String,
        val agree: Boolean
    ) : GameCommand

    /**
     * The acting (major) civ pledges or withdraws protection over the city-state [cityStateCivName].
     *
     * [pledge] true → `CityStateFunctions.addProtectorCiv`; false → `removeProtectorCiv`.
     */
    @Serializable
    @SerialName("cityStateProtection")
    data class CityStateProtection(
        val cityStateCivName: String,
        val pledge: Boolean
    ) : GameCommand

    /**
     * The acting (major) civ demands gold tribute from the city-state [cityStateCivName] —
     * "bullying" it for an instant gold payout at the cost of −15 influence.
     *
     * Mirrors the DiplomacyScreen city-state "Take [n] gold" button. The authority validates the
     * same gate the button enforces (the target is a met city-state, the acting civ is not at war
     * with it, and its gold tribute willingness is non-negative —
     * `CityStateFunctions.getTributeWillingness(actingCiv) >= 0`), then delegates to
     * `CityStateFunctions.tributeGold`, which pays the gold, applies the influence hit and sets the
     * recently-bullied flag. The gold amount is derived on the authority (`goldGainedByTribute`), so
     * it is not carried on the wire.
     */
    @Serializable
    @SerialName("tributeGold")
    data class TributeGold(
        val cityStateCivName: String
    ) : GameCommand

    /**
     * The acting (major) civ demands a worker from the city-state [cityStateCivName] — "bullying" it
     * into yielding a worker-type unit at the cost of −50 influence.
     *
     * Mirrors the DiplomacyScreen city-state "Take worker" button. The authority validates the same
     * gate the button enforces (met city-state, not at war, and worker tribute willingness
     * non-negative — `getTributeWillingness(actingCiv, demandingWorker = true) >= 0`, which already
     * folds in the "capital size ≥ 4" requirement), then delegates to
     * `CityStateFunctions.tributeWorker`. The yielded unit's type and placement are chosen
     * deterministically on the authority (`DiplomacyManager.state.stateBasedRandom`), so all clients
     * converge — nothing about the spawned worker is carried on the wire.
     */
    @Serializable
    @SerialName("tributeWorker")
    data class TributeWorker(
        val cityStateCivName: String
    ) : GameCommand

    // endregion

    // region Trade

    /**
     * The acting civ accepts ([accept] = true) or declines a pending trade request from
     * [fromCivName].
     *
     * The trade payload is NOT carried on the wire: the authority matches the pending entry in the
     * acting civ's `tradeRequests` by requesting-civ, then runs the engine's own accept/decline path
     * (`TradeLogic.acceptTrade` / `TradeRequest.decline`). See `ProposeTrade` (deferred) for why the
     * full bilateral trade is not embedded.
     */
    @Serializable
    @SerialName("respondToTrade")
    data class RespondToTrade(
        val fromCivName: String,
        val accept: Boolean
    ) : GameCommand

    // NOTE: ProposeTrade is intentionally DEFERRED. A proposal carries a full bilateral `Trade`
    // (`Trade`/`TradeOffer`/`TradeRequest` in core/.../logic/trade/), but those types use the game's
    // libgdx-JSON serialization (`IsPartOfGameInfoSerialization`) and are NOT `@Serializable`
    // (kotlinx) — which is the wire format for `GameCommand`. Embedding them would require a parallel
    // kotlinx DTO + conversion that risks drift from the canonical model, so it is left out here.
    // RespondToTrade (above) still lets a player accept/decline incoming requests.

    // endregion

    // region Policy

    /**
     * The acting civ adopts the policy (or policy branch) named [policyName].
     *
     * A branch is just the branch's own name (both individual policies and branches live in
     * `ruleset.policies`), so one command keyed by name handles both. Validated with the engine's
     * `isAdoptable` + `canAdoptPolicy` gate, then `PolicyManager.adopt(policy)`.
     */
    @Serializable
    @SerialName("adoptPolicy")
    data class AdoptPolicy(
        val policyName: String
    ) : GameCommand

    // endregion

    // region Ideology

    /**
     * BNW Phase 2a — Increment 2. The acting civ switches its ideology to the policy branch named
     * [toBranchName] (an ideology branch — [com.unciv.models.ruleset.PolicyBranch.isIdeology]).
     *
     * Switching costs anarchy (N turns of no production / research) and loses the old ideology's
     * tenets (partially refunded). Validated on the authority
     * (`CommandExecutor.executeSwitchIdeology`): the civ must already have a *different* ideology,
     * not be in anarchy, and the switch must be either voluntarily adoptable or forced by Civil
     * Resistance (`PublicOpinionManager.forcedSwitchPending`). Applied via the shared
     * `PolicyManager.switchIdeology(toBranch)` (the same path the AI uses). When the switch is the
     * forced one, the pending Civil-Resistance `PopupAlert` is consumed.
     */
    @Serializable
    @SerialName("switchIdeology")
    data class SwitchIdeology(
        val toBranchName: String
    ) : GameCommand

    // endregion

    // region City management

    /**
     * The acting civ purchases [constructionName] (a building or unit) in its city centered on
     * ([cityX], [cityY]), paying with the stat named [stat] (a [com.unciv.models.stats.Stat] constant
     * name — only Gold/Faith/etc. usable to buy; Production/Happiness are rejected by the engine gate).
     *
     * For a building carrying `CreatesOneImprovement`, [improvementTileX]/[improvementTileY] pick the
     * tile to place the improvement on (as the CityScreen tile-picker does); leave both null to let the
     * engine auto-choose. Validated with the engine's own
     * `CityConstructions.isConstructionPurchaseAllowed` (the one true buy test), then delegated to
     * `CityConstructions.purchaseConstruction(...)`.
     */
    @Serializable
    @SerialName("buyConstruction")
    data class BuyConstruction(
        val cityX: Int,
        val cityY: Int,
        val constructionName: String,
        val stat: String,
        val improvementTileX: Int? = null,
        val improvementTileY: Int? = null
    ) : GameCommand

    /**
     * The acting civ flags its city centered on ([cityX], [cityY]) for razing ([raze] = true) or
     * stops razing it ([raze] = false).
     *
     * Mirrors the CityScreen raze/stop-razing buttons: when starting to raze, the engine's
     * `City.canBeDestroyed()` gate (plus the "may not annex" rule) is validated before setting
     * `city.isBeingRazed`.
     */
    @Serializable
    @SerialName("razeCity")
    data class RazeCity(
        val cityX: Int,
        val cityY: Int,
        val raze: Boolean
    ) : GameCommand

    /**
     * The acting civ annexes its puppet city centered on ([cityX], [cityY]).
     *
     * Mirrors the CityScreen "Annex city" button (only shown for a puppet of a civ that may annex).
     * Delegates to `CityConquestFunctions.annexCity()` via `City.annexCity()`.
     */
    @Serializable
    @SerialName("annexCity")
    data class AnnexCity(
        val cityX: Int,
        val cityY: Int
    ) : GameCommand

    /**
     * The acting civ buys the tile ([tileX], [tileY]) for its city centered on ([cityX], [cityY]),
     * paying gold.
     *
     * Mirrors the CityScreen "Buy tile" path: validated with `CityExpansionManager.canBuyTile` and an
     * affordability check, then delegated to `CityExpansionManager.buyTile(tile)`.
     */
    @Serializable
    @SerialName("buyTile")
    data class BuyTile(
        val cityX: Int,
        val cityY: Int,
        val tileX: Int,
        val tileY: Int
    ) : GameCommand

    /**
     * The acting civ sets the citizen-allocation focus of its city centered on ([cityX], [cityY]) to
     * [focusName] (a [com.unciv.logic.city.CityFocus] constant name).
     *
     * Mirrors the CitizenManagementTable focus buttons: `City.setCityFocus(focus)` followed by
     * `City.reassignPopulation()`.
     */
    @Serializable
    @SerialName("setCityFocus")
    data class SetCityFocus(
        val cityX: Int,
        val cityY: Int,
        val focusName: String
    ) : GameCommand

    /**
     * The acting civ resets the citizen allocation of its city centered on ([cityX], [cityY]),
     * unlocking all tiles.
     *
     * Mirrors the CitizenManagementTable "Reset Citizens" button: `City.reassignPopulation(true)`.
     */
    @Serializable
    @SerialName("resetCitizens")
    data class ResetCitizens(
        val cityX: Int,
        val cityY: Int
    ) : GameCommand

    /**
     * The acting civ toggles the "Avoid Growth" setting of its city centered on ([cityX], [cityY]).
     *
     * Mirrors the CitizenManagementTable "Avoid Growth" button: flips `City.avoidGrowth` then
     * `City.reassignPopulation()`.
     */
    @Serializable
    @SerialName("toggleAvoidGrowth")
    data class ToggleAvoidGrowth(
        val cityX: Int,
        val cityY: Int
    ) : GameCommand

    /**
     * The acting civ toggles the locked state of the worked tile ([tileX], [tileY]) of its city
     * centered on ([cityX], [cityY]).
     *
     * Mirrors the CityScreenTileTable Lock/Unlock buttons: a locked tile keeps its assigned citizen
     * across reassignment. Only a tile currently worked by the city can be (un)locked.
     */
    @Serializable
    @SerialName("toggleLockedTile")
    data class ToggleLockedTile(
        val cityX: Int,
        val cityY: Int,
        val tileX: Int,
        val tileY: Int
    ) : GameCommand

    /**
     * The acting civ sells the building [buildingName] from its city centered on ([cityX], [cityY]).
     *
     * Mirrors the ConstructionInfoTable "Sell" button: validated for a sellable, built, non-free
     * building (and at most one sale per turn) before delegating to `City.sellBuilding(building)`.
     */
    @Serializable
    @SerialName("sellBuilding")
    data class SellBuilding(
        val cityX: Int,
        val cityY: Int,
        val buildingName: String
    ) : GameCommand

    // NOTE: ToggleWeLoveTheKing is intentionally DEFERRED. There is no player-initiated WLTK action
    // in the engine or UI: We-Love-The-King-Day is set exclusively by the engine in
    // `CityTurnManager.tryWeLoveTheKing()` when a city has its `demandedResource` available, and the
    // "CityStatsTable" WLTK label only opens a Civilopedia tutorial popup. There is no `City` method
    // to toggle the flag, so a command would have to hand-roll the `CityFlags.WeLoveTheKing` flag,
    // which the architecture forbids ("never hand-roll state"). See the executor for the same note.

    // endregion

    // region Espionage

    /**
     * The acting civ moves its spy named [spyName] to the city centered on ([targetCityX],
     * [targetCityY]), or recalls it to the hideout when both coordinates equal [HIDEOUT] (the
     * sentinel for "no city").
     *
     * Spies are keyed by their stable `Spy.name` (a `lateinit val`-style name set at construction and
     * unique within a civ's spy list). Validated with `Spy.canMoveTo(city)` before delegating to
     * `Spy.moveTo(city)` (or `moveTo(null)` for the hideout).
     */
    @Serializable
    @SerialName("moveSpy")
    data class MoveSpy(
        val spyName: String,
        val targetCityX: Int,
        val targetCityY: Int
    ) : GameCommand {
        companion object {
            /** Sentinel position meaning "move the spy to the hideout" (no target city). */
            const val HIDEOUT = Int.MIN_VALUE
        }
    }

    /**
     * The acting civ sets the action of its spy named [spyName] to [spyActionName] (a
     * [com.unciv.models.SpyAction] constant name).
     *
     * Delegates to `Spy.setAction(action, turns)`. Only the player-selectable actions are accepted
     * (e.g. starting/stopping a Coup); engine-internal lifecycle actions (Moving, Dead, …) are rejected.
     */
    @Serializable
    @SerialName("setSpyAction")
    data class SetSpyAction(
        val spyName: String,
        val spyActionName: String
    ) : GameCommand

    // endregion

    // region Unit actions (parameterized)

    /**
     * The acting civ upgrades its unit on tile ([unitX], [unitY]) into [toUnitName] (a base-unit name
     * from the ruleset).
     *
     * Mirrors the UnitUpgradeMenu / `UnitActionsUpgrade`: the target is resolved to the civ's
     * equivalent unit, the engine's own `UnitUpgradeManager.canUpgrade` gate plus the action's
     * enablement checks (enough gold, movement left, on owned land, not embarked) are validated, then
     * `UnitUpgradeManager.performUpgrade` is called. Only direct, paid upgrades are routed (free/special
     * ruins upgrades are engine-triggered, not player intents).
     */
    @Serializable
    @SerialName("upgradeUnit")
    data class UpgradeUnit(
        val unitX: Int,
        val unitY: Int,
        val toUnitName: String
    ) : GameCommand

    /**
     * The acting civ's worker-type unit on tile ([unitX], [unitY]) starts building improvement
     * [improvementName] on its current tile.
     *
     * Mirrors `UnitActionsFromUniques.getBuildingImprovementsActions` + the ImprovementPickerScreen
     * choice: the dedicated command carries the picked [improvementName] (the (x,y,actionType) tuple of
     * [GenericUnitAction] cannot carry it, and the picker opens a WorldScreen). Validated with
     * `MapUnit.canBuildImprovement` and `ImprovementPickerScreen.canReport(getImprovementBuildingProblems)`,
     * then applied via `Tile.startWorkingOnImprovement` (the same path the picker's `accept` uses).
     */
    @Serializable
    @SerialName("buildImprovement")
    data class BuildImprovement(
        val unitX: Int,
        val unitY: Int,
        val improvementName: String
    ) : GameCommand

    /**
     * The acting civ paradrops its unit on tile ([unitX], [unitY]) to the tile ([targetX], [targetY]).
     *
     * Mirrors the two-step UI paradrop (prepare via the Paradrop action, then click the destination):
     * the authority prepares the paradrop, validates the destination with the engine's own
     * `UnitMovement.canReachInCurrentTurn` (which routes through the paradrop range/visibility gate),
     * then delegates to `UnitMovement.moveToTile`, which performs the airborne drop (movement + an attack
     * charge). A dedicated command is required because the drop needs the destination tile that the
     * action-type tuple cannot carry.
     */
    @Serializable
    @SerialName("paradrop")
    data class Paradrop(
        val unitX: Int,
        val unitY: Int,
        val targetX: Int,
        val targetY: Int
    ) : GameCommand

    /**
     * The acting civ gifts its unit on tile ([unitX], [unitY]) to the civ that owns that tile's
     * territory.
     *
     * Mirrors `UnitActions.getGiftActions`: a unit can be gifted to the major civ / city-state whose
     * territory it stands in (city-states only take eligible military/special units; majors must be
     * friendly). The recipient is the tile owner, so no target id is carried. Validated against the same
     * gate, then delegated to `MapUnit.gift` (a Great Person given to a city-state is destroyed, as in
     * single-player).
     */
    @Serializable
    @SerialName("giftUnit")
    data class GiftUnit(
        val unitX: Int,
        val unitY: Int
    ) : GameCommand

    /**
     * The acting civ swaps its unit on tile ([unitX], [unitY]) with its own same-type unit on tile
     * ([otherX], [otherY]).
     *
     * Mirrors the "Swap units" action: the engine UI only toggles a swap *mode* and resolves the actual
     * swap when the player clicks the partner tile, so the partner tile is carried explicitly here.
     * Validated with `UnitMovement.canUnitSwapTo`, then applied via `UnitMovement.swapMoveToTile`.
     */
    @Serializable
    @SerialName("swapUnits")
    data class SwapUnits(
        val unitX: Int,
        val unitY: Int,
        val otherX: Int,
        val otherY: Int
    ) : GameCommand

    /**
     * The acting civ disbands its unit on tile ([unitX], [unitY]).
     *
     * The engine's Disband action lambda opens a confirmation popup via `GUI.getWorldScreen()` (not
     * headless-safe and not driveable by [GenericUnitAction]), so a dedicated command applies the
     * already-confirmed intent by calling `MapUnit.disband()` directly and refreshing upkeep, mirroring
     * the popup's confirm callback.
     */
    @Serializable
    @SerialName("disbandUnit")
    data class DisbandUnit(
        val unitX: Int,
        val unitY: Int
    ) : GameCommand

    /**
     * BNW Portuguese Nau "Sell Exotic Goods". The acting civ's exotic-goods unit on tile ([unitX], [unitY])
     * sells its wares for Gold (scaling with distance from the capital) and XP.
     *
     * A dedicated command (not [GenericUnitAction]) because its UI action lambda itself sends a command and
     * applies the reward optimistically, so the engine action is NOT safe to re-invoke on the authority. The
     * authority resolves the unit, re-checks the shared `SellExoticGoods` gate (uses left, has movement, in
     * foreign/neutral territory), then applies the identical deterministic effect via
     * `SellExoticGoods.sellExoticGoods`. Carries no choice beyond the unit's tile.
     */
    @Serializable
    @SerialName("sellExoticGoods")
    data class SellExoticGoods(
        val unitX: Int,
        val unitY: Int
    ) : GameCommand

    // NOTE: SetUp, AirSweep, Automate, Repair, and Pillage are intentionally NOT dedicated commands.
    // They carry no choice the (x,y,actionType) tuple of GenericUnitAction can't express, and their
    // engine action lambdas are headless-safe, so the executor drives them through the existing
    // GenericUnitAction path (extended to invoke mapped action getters without a WorldScreen). See
    // CommandExecutor.applySimpleUnitActionDirectly / the GenericUnitAction headless extension.

    // endregion

    // region Great person

    /**
     * The acting civ picks the free Great Person [unitName] (a base-unit name from the ruleset).
     *
     * Mirrors `GreatPersonPickerScreen.confirmAction`: validated for an available free pick (the civ has
     * `freeGreatPeople > 0`, the unit is in `getGreatPeople()`, and — under the Maya long-count
     * restriction — is in `longCountGPPool`), then the unit is added in the capital and the free-GP
     * counters are decremented exactly as the picker does.
     */
    @Serializable
    @SerialName("chooseGreatPerson")
    data class ChooseGreatPerson(
        val unitName: String
    ) : GameCommand

    // NOTE: The on-map Great Person actions (HurryResearch/HurryPolicy/HurryWonder/HurryBuilding/
    // ConductTradeMission and the great-person Create-Improvement) are NOT dedicated commands. They act
    // on the unit's own tile/current city, carry no extra choice, are registered in
    // UnitActions.actionTypeToFunctions, and their action lambdas are headless-safe — so they route
    // through GenericUnitAction (the executor invokes the mapped action getter directly, without a
    // WorldScreen). See the GenericUnitAction headless extension in CommandExecutor.

    // endregion

    // region Religion

    /**
     * The acting civ founds a pantheon with the belief [beliefName] (a Pantheon-type belief from the
     * ruleset).
     *
     * Mirrors `PantheonPickerScreen`: validated with `ReligionManager.canFoundOrExpandPantheon` and that
     * the belief is an available, choosable Pantheon belief, then applied via
     * `ReligionManager.chooseBeliefs([belief], useFreeBeliefs)` (which internally calls the private
     * `foundPantheon` and advances the religion state) — the exact path the picker's OK action takes.
     */
    @Serializable
    @SerialName("foundPantheon")
    data class FoundPantheon(
        val beliefName: String
    ) : GameCommand

    /**
     * The acting civ founds a religion with its great prophet on tile ([unitX], [unitY]), under the
     * religion icon [religionName] (a religion name from `ruleset.religions`), shown as [displayName]
     * (defaults to [religionName] when blank), choosing the beliefs [beliefNames].
     *
     * Mirrors `ReligiousBeliefsPickerScreen` for founding: the prophet's found-religion ability must be
     * usable here (`mayFoundReligionHere`), the icon must be free, and every belief must exist and be
     * currently choosable (right type, not already taken). The authority replays the picker's OK action:
     * `ReligionManager.foundReligion(displayName, religionName)` then `chooseBeliefs(beliefs, …)`. The
     * belief list serializes cleanly as `List<String>`.
     */
    @Serializable
    @SerialName("foundReligion")
    data class FoundReligion(
        val unitX: Int,
        val unitY: Int,
        val religionName: String,
        val displayName: String = "",
        val beliefNames: List<String> = emptyList()
    ) : GameCommand

    /**
     * The acting civ enhances its religion with its great prophet on tile ([unitX], [unitY]), choosing
     * the beliefs [beliefNames].
     *
     * Mirrors `ReligiousBeliefsPickerScreen` for enhancing: the prophet's enhance ability must be usable
     * here (`mayEnhanceReligionHere`), and every belief must exist and be currently choosable. The
     * authority replays the picker's OK action: `ReligionManager.useProphetForEnhancingReligion(prophet)`
     * then `chooseBeliefs(beliefs, …)`.
     */
    @Serializable
    @SerialName("enhanceReligion")
    data class EnhanceReligion(
        val unitX: Int,
        val unitY: Int,
        val beliefNames: List<String> = emptyList()
    ) : GameCommand

    /**
     * The acting civ's missionary/prophet on tile ([unitX], [unitY]) spreads its religion to the city
     * centered on ([targetCityX], [targetCityY]).
     *
     * Mirrors `UnitActionsReligion.getSpreadReligionActions`: the unit must currently be able to spread
     * (`maySpreadReligionNow`) onto a valid city tile. Applied via the engine's own spread action
     * (pressure + side effects), exactly as the unit action does.
     */
    @Serializable
    @SerialName("spreadReligion")
    data class SpreadReligion(
        val unitX: Int,
        val unitY: Int,
        val targetCityX: Int,
        val targetCityY: Int
    ) : GameCommand

    /**
     * The acting civ's inquisitor on tile ([unitX], [unitY]) removes heresy (foreign religious
     * pressure) from its own city centered on ([targetCityX], [targetCityY]).
     *
     * Mirrors `UnitActionsReligion.getRemoveHeresyActions`: the unit must currently have a usable
     * remove-heresy ability on that own city. Applied via the engine's own remove-heresy action.
     */
    @Serializable
    @SerialName("removeHeresy")
    data class RemoveHeresy(
        val unitX: Int,
        val unitY: Int,
        val targetCityX: Int,
        val targetCityY: Int
    ) : GameCommand

    // endregion

    // region Events

    /**
     * The acting civ resolves a pending ruleset [Event][com.unciv.models.ruleset.Event] by picking one
     * of its choices. This is the event analogue of [DemandResponse]: both answer a `PopupAlert` that
     * the authority recorded on the acting civ.
     *
     * An `Alert`-presentation event fires during inter-turn processing and enqueues a
     * `PopupAlert(AlertType.Event, "<eventName>[<split>unitId=<id>]")` on the acting civ — which a human
     * joiner sees in their filtered view but, until this command, could only resolve **locally** on
     * their throwaway snapshot (never on the canonical state). `None`-presentation events and AI civs
     * auto-resolve on the authority and never need this command.
     *
     * [eventName] keys the pending alert and `ruleset.events`. [choiceIndex] is the index into the
     * event's full `choices` list (stable across host/joiner since both share the ruleset, and matching
     * choices are filtered from that same list in order). [unitId] is the optional unit the event is
     * bound to (e.g. a Great Musician's concert tour), as encoded in the alert.
     *
     * Validated on the authority against the pending alert and the choice's current conditions, then
     * applied via `EventChoice.triggerChoice(civ, unit)`; the alert is consumed on success.
     */
    @Serializable
    @SerialName("resolveEvent")
    data class ResolveEvent(
        val eventName: String,
        val choiceIndex: Int,
        val unitId: Int? = null
    ) : GameCommand

    // endregion

    // region Trade Routes

    /**
     * BNW Phase 3 — International Trade Routes. The acting civ's trade unit on tile ([unitX], [unitY])
     * establishes an *International Trade Route* to the city centered on ([destCityX], [destCityY]).
     *
     * A dedicated command (not [GenericUnitAction]) because the destination city is a choice the
     * `(x,y,actionType)` tuple can't carry — the same reason [BuildImprovement]/[SpreadReligion] are
     * dedicated. The route ORIGIN is the unit's CURRENT city: the unit must be standing ON one of the acting
     * civ's own city centers, and that city is the origin (resolved on the authority, so it is not on the
     * wire). The authority resolves the unit + destination city, validates origin / capacity / route-type /
     * connectivity / max-length (all `CommandException`), then records the route via the shared
     * `TradeRouteManager.establish`.
     *
     * [internalYield] is the `TradeRouteYield` enum name (`"Food"` / `"Production"`) a DOMESTIC route should
     * carry; `""` (the default) means "unspecified" — the authority defaults a domestic route to Production
     * and ignores the field for an international route.
     */
    @Serializable
    @SerialName("establishTradeRoute")
    data class EstablishTradeRoute(
        val unitX: Int,
        val unitY: Int,
        val destCityX: Int,
        val destCityY: Int,
        val internalYield: String = ""
    ) : GameCommand

    /**
     * BNW Phase 3 — International Trade Routes. The acting civ instantly relocates its trade unit on tile
     * ([unitX], [unitY]) to its own city centered on ([destCityX], [destCityY]); the move consumes the unit's
     * whole turn.
     *
     * A dedicated command (not [GenericUnitAction]) because the destination city is a choice the
     * `(x,y,actionType)` tuple can't carry. The authority resolves the trade unit + destination city,
     * validates that the destination is the acting civ's own city center, holds no other trade unit, and
     * differs from the current tile (all `CommandException`), then relocates via
     * `MapUnit.removeFromTile()` + `putInTile()` and spends the unit's movement.
     */
    @Serializable
    @SerialName("moveTradeUnitToCity")
    data class MoveTradeUnitToCity(
        val unitX: Int,
        val unitY: Int,
        val destCityX: Int,
        val destCityY: Int
    ) : GameCommand

    // endregion

    // region Great Works

    /**
     * BNW Phase 2c — Increment 3. The acting civ moves one of its Great Works (identified by the stable
     * [workId]) into the slot at index [toSlotIndex] of building [toBuildingName] in its city centered on
     * ([toCityX], [toCityY]).
     *
     * The *source* slot is derived on the authority from where [workId] currently sits, so it isn't on
     * the wire. If the destination slot is already occupied by ANOTHER work owned by the same civ, the
     * two works are swapped (the displaced work takes the moved work's old slot). Validated on the
     * authority (`CommandExecutor.executeMoveGreatWork`): the acting civ must own the work, the
     * destination slot must exist and be owned by the acting civ, the work's type must fit the slot, and
     * the destination must not hold another civ's work. A dedicated command (rather than a generic
     * action) is used because [workId] is a stable registry string the (x,y) conventions can't carry.
     */
    @Serializable
    @SerialName("moveGreatWork")
    data class MoveGreatWork(
        val workId: String,
        val toCityX: Int,
        val toCityY: Int,
        val toBuildingName: String,
        val toSlotIndex: Int
    ) : GameCommand

    // endregion

    // region World Congress

    /**
     * BNW Phase 3 — World Congress (Increment 2). The acting civ proposes the resolution named
     * [resolutionType] (a [com.unciv.logic.civilization.ResolutionType] enum name) during the congress's
     * Proposing phase.
     *
     * Flat primitives only (§7 / D2): the proposal is server-canonical and referenced afterwards by its
     * int id. [targetCivId] is set for targeting resolutions (e.g. TradeSanctions / WorldLeaderElection
     * candidate) and [choiceArg] for choice resolutions (e.g. BanLuxury's luxury name). The authority
     * (`CommandExecutor.executeProposeResolution`) validates membership, phase, the per-member /
     * per-session cap, and that the type is currently proposable, then creates a
     * [com.unciv.logic.civilization.CongressProposal] (`id = nextProposalId++`). `CommandException` on any
     * violation, state untouched.
     */
    @Serializable
    @SerialName("proposeResolution")
    data class ProposeResolution(
        val resolutionType: String,
        val targetCivId: String = "",
        val choiceArg: String = ""
    ) : GameCommand

    /**
     * BNW Phase 3 — World Congress (Increment 2). The acting civ casts its full delegate bloc on the
     * proposal with [proposalId] during the Voting phase: [delegates] delegates, all FOR ([voteFor] true)
     * or all AGAINST.
     *
     * Full-bloc only (matching Civ V and simplifying the AI): the authority
     * (`CommandExecutor.executeCastCongressVote`) requires `delegates == getDelegateCount(actingCiv)`,
     * that the civ is a member who hasn't already voted on this proposal, and that the proposal exists in
     * the current Voting session. `CommandException` on any violation, state untouched.
     */
    @Serializable
    @SerialName("castCongressVote")
    data class CastCongressVote(
        val proposalId: Int,
        val delegates: Int,
        val voteFor: Boolean
    ) : GameCommand

    // endregion
}
