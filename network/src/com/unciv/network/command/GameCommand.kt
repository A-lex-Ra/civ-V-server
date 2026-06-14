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
}
