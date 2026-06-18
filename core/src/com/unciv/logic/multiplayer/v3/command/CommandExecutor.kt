package com.unciv.logic.multiplayer.v3.command

import com.unciv.GUI
import com.unciv.logic.GameInfo
import com.unciv.logic.automation.unit.UnitAutomation
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.city.City
import com.unciv.logic.city.CityFocus
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.Demand
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.trade.TradeLogic
import com.unciv.models.Spy
import com.unciv.models.SpyAction
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.Belief
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.stats.Stat
import com.unciv.models.ruleset.unique.UniqueTarget
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.network.command.GameCommand
import com.unciv.ui.screens.pickerscreens.ImprovementPickerScreen
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionModifiers
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsFromUniques

/**
 * Thrown when a [GameCommand] cannot be applied to the canonical [GameInfo] — the acting player
 * does not own the unit, the unit is not where the command says it is, the destination is not
 * reachable/enterable this turn, etc.
 *
 * The authority rejects the command cleanly (state is left untouched) rather than corrupting the
 * canonical state. In later phases this maps to a `GameFrame.CommandRejected` sent back to the
 * issuing client (see docs/multiplayer-v3.md §5).
 */
class CommandException(message: String) : Exception(message)

/**
 * The single choke-point that mutates [GameInfo] in multiplayer v3.
 *
 * The authority validates each [GameCommand] against the canonical state (legal mover,
 * ownership, reachability, …) and then applies it through the engine's own APIs — never by
 * hand-rolling state changes. Clients only ever send *intents*; this is the one place those
 * intents become canonical mutations.
 *
 * Each command identifies its subject by **acting civ + tile** (matching the existing
 * [GameCommand.MoveUnit] convention); there is no reliable stable unit id on the wire. The
 * executor delegates to the same engine entry points the UI/automation use, so all side effects
 * (consuming a settler, era progression, combat capture, …) happen exactly as in single-player.
 *
 * `EndTurn` is intentionally **not** applied here — inter-turn processing (`GameInfo.nextTurn`) is
 * driven by the session/authority loop (see docs/multiplayer-v3.md Phase 3), not by this executor.
 */
class CommandExecutor {

    /**
     * Validate then apply [command] to [gameInfo] on behalf of [playerCivId] (the civ id of the
     * player that issued it — carried by the enclosing `GameFrame.PlayerCommand`, not by the
     * command itself).
     *
     * @throws CommandException if the command is illegal. The [gameInfo] is left unmodified.
     * @throws NotImplementedError for command types not yet handled in this phase.
     */
    fun execute(gameInfo: GameInfo, playerCivId: String, command: GameCommand) {
        when (command) {
            is GameCommand.MoveUnit -> executeMoveUnit(gameInfo, playerCivId, command)
            is GameCommand.FoundCity -> executeFoundCity(gameInfo, playerCivId, command)
            is GameCommand.SetCityProduction -> executeSetCityProduction(gameInfo, playerCivId, command)
            is GameCommand.ChooseTech -> executeChooseTech(gameInfo, playerCivId, command)
            is GameCommand.PromoteUnit -> executePromoteUnit(gameInfo, playerCivId, command)
            is GameCommand.GenericUnitAction -> executeGenericUnitAction(gameInfo, playerCivId, command)
            is GameCommand.AttackUnit -> executeAttackUnit(gameInfo, playerCivId, command)
            is GameCommand.DeclareWar -> executeDeclareWar(gameInfo, playerCivId, command)
            is GameCommand.MakePeace -> executeMakePeace(gameInfo, playerCivId, command)
            is GameCommand.DeclareFriendship -> executeDeclareFriendship(gameInfo, playerCivId, command)
            is GameCommand.DefensivePact -> executeDefensivePact(gameInfo, playerCivId, command)
            is GameCommand.Denounce -> executeDenounce(gameInfo, playerCivId, command)
            is GameCommand.GiftGold -> executeGiftGold(gameInfo, playerCivId, command)
            is GameCommand.DemandResponse -> executeDemandResponse(gameInfo, playerCivId, command)
            is GameCommand.CityStateProtection -> executeCityStateProtection(gameInfo, playerCivId, command)
            is GameCommand.RespondToTrade -> executeRespondToTrade(gameInfo, playerCivId, command)
            is GameCommand.AdoptPolicy -> executeAdoptPolicy(gameInfo, playerCivId, command)
            is GameCommand.BuyConstruction -> executeBuyConstruction(gameInfo, playerCivId, command)
            is GameCommand.RazeCity -> executeRazeCity(gameInfo, playerCivId, command)
            is GameCommand.AnnexCity -> executeAnnexCity(gameInfo, playerCivId, command)
            is GameCommand.BuyTile -> executeBuyTile(gameInfo, playerCivId, command)
            is GameCommand.SetCityFocus -> executeSetCityFocus(gameInfo, playerCivId, command)
            is GameCommand.ResetCitizens -> executeResetCitizens(gameInfo, playerCivId, command)
            is GameCommand.ToggleAvoidGrowth -> executeToggleAvoidGrowth(gameInfo, playerCivId, command)
            is GameCommand.ToggleLockedTile -> executeToggleLockedTile(gameInfo, playerCivId, command)
            is GameCommand.SellBuilding -> executeSellBuilding(gameInfo, playerCivId, command)
            is GameCommand.MoveSpy -> executeMoveSpy(gameInfo, playerCivId, command)
            is GameCommand.SetSpyAction -> executeSetSpyAction(gameInfo, playerCivId, command)
            is GameCommand.UpgradeUnit -> executeUpgradeUnit(gameInfo, playerCivId, command)
            is GameCommand.BuildImprovement -> executeBuildImprovement(gameInfo, playerCivId, command)
            is GameCommand.Paradrop -> executeParadrop(gameInfo, playerCivId, command)
            is GameCommand.GiftUnit -> executeGiftUnit(gameInfo, playerCivId, command)
            is GameCommand.SwapUnits -> executeSwapUnits(gameInfo, playerCivId, command)
            is GameCommand.DisbandUnit -> executeDisbandUnit(gameInfo, playerCivId, command)
            is GameCommand.ChooseGreatPerson -> executeChooseGreatPerson(gameInfo, playerCivId, command)
            is GameCommand.FoundPantheon -> executeFoundPantheon(gameInfo, playerCivId, command)
            is GameCommand.FoundReligion -> executeFoundReligion(gameInfo, playerCivId, command)
            is GameCommand.EnhanceReligion -> executeEnhanceReligion(gameInfo, playerCivId, command)
            is GameCommand.SpreadReligion -> executeSpreadReligion(gameInfo, playerCivId, command)
            is GameCommand.RemoveHeresy -> executeRemoveHeresy(gameInfo, playerCivId, command)
            is GameCommand.EndTurn ->
                // Inter-turn processing (GameInfo.nextTurn) is owned by the session/authority loop,
                // not the executor (see docs/multiplayer-v3.md Phase 3).
                throw NotImplementedError("CommandExecutor does not handle EndTurn; the session drives nextTurn (see docs/multiplayer-v3.md Phase 3)")
        }
    }

    private fun executeMoveUnit(gameInfo: GameInfo, playerCivId: String, command: GameCommand.MoveUnit) {
        val actingCiv = requireCiv(gameInfo, playerCivId)

        // Source and destination tiles must exist on the map.
        val fromTile = requireTile(gameInfo, command.fromX, command.fromY, "Source")
        val toTile = requireTile(gameInfo, command.toX, command.toY, "Destination")

        // Identify the unit by acting civ + source tile (see header / unit-identity note in the PR):
        // MapUnit has no reliably-populated stable id in a fresh game, so the command's source tile
        // plus ownership is the canonical key. There must be exactly one such unit owned by the
        // acting civ on that tile.
        val unit = findMovableUnit(fromTile, actingCiv.civID)
            ?: throw CommandException(
                "No unit owned by '$playerCivId' at source tile (${command.fromX}, ${command.fromY})"
            )

        // Reject a no-op move so callers get a clear signal rather than a silent success.
        if (toTile == fromTile)
            throw CommandException("MoveUnit source and destination are the same tile")

        // The move must be legal for the engine: reachable this turn AND enterable.
        if (!unit.movement.canReachInCurrentTurn(toTile) || !unit.movement.canMoveTo(toTile))
            throw CommandException(
                "Unit at (${command.fromX}, ${command.fromY}) cannot move to (${command.toX}, ${command.toY}) this turn"
            )

        // Apply via the engine's own movement API — do NOT hand-roll movement.
        unit.movement.moveToTile(toTile)

        // moveToTile is best-effort along the path; for a single legal step it should land exactly
        // on the target. Guard against a partial move so we never report a success that didn't happen.
        if (unit.currentTile != toTile)
            throw CommandException(
                "Move did not reach (${command.toX}, ${command.toY}); unit ended at " +
                    "(${unit.currentTile.position.x}, ${unit.currentTile.position.y})"
            )
    }

    private fun executeFoundCity(gameInfo: GameInfo, playerCivId: String, command: GameCommand.FoundCity) {
        val actingCiv = requireCiv(gameInfo, playerCivId)
        val tile = requireTile(gameInfo, command.x, command.y, "Target")

        // The settler-type unit must be one owned by the acting civ standing on the tile, and it
        // must be able to found a city *here and now*. We let the engine decide that via the same
        // factory the UI uses: getFoundCityAction returns null when the unit can never found, or a
        // UnitAction with a null `action` when it cannot found on this tile right now (no movement,
        // too close to another city, water, …).
        val foundingUnit = tile.getUnits().firstOrNull {
            it.owner == actingCiv.civID && UnitActionsFromUniques.getFoundCityAction(it, tile)?.action != null
        } ?: throw CommandException(
            "No unit owned by '$playerCivId' on tile (${command.x}, ${command.y}) can found a city there"
        )

        // The action unique that grants founding (carries side-effect modifiers such as
        // "<by consuming this unit>").
        val foundCityUnique = UnitActionModifiers.getUsableUnitActionUniques(foundingUnit, UniqueType.FoundCity).firstOrNull()
            ?: UnitActionModifiers.getUsableUnitActionUniques(foundingUnit, UniqueType.FoundPuppetCity).firstOrNull()
            ?: throw CommandException("Unit on tile (${command.x}, ${command.y}) cannot found a city")

        // Delegate to the engine: Civilization.addCity -> CityFounder().foundCity + chooseNextConstruction.
        // This runs every founding side effect (capital indicator, starting buildings, proximity,
        // triggered uniques, …) exactly as single-player does.
        actingCiv.addCity(tile.position, foundingUnit)

        // Consume the settler the way the engine's found-city action does. The FoundCity unique on
        // a normal Settler carries action modifiers (UnitActionConsumeUnit), so activateSideEffects
        // handles consumption/movement cost; if it has none, the action would simply destroy the unit.
        val hasActionModifiers = foundCityUnique.modifiers.any {
            it.type?.targetTypes?.contains(UniqueTarget.UnitActionModifier) == true
        }
        if (hasActionModifiers) UnitActionModifiers.activateSideEffects(foundingUnit, foundCityUnique)
        else foundingUnit.destroy()
    }

    private fun executeSetCityProduction(gameInfo: GameInfo, playerCivId: String, command: GameCommand.SetCityProduction) {
        val actingCiv = requireCiv(gameInfo, playerCivId)
        val city = requireOwnedCity(gameInfo, actingCiv, command.cityX, command.cityY)

        // Resolve the construction by name through the city's own ruleset lookup. Unknown names
        // throw inside the engine; convert to a clean CommandException.
        val construction = try {
            city.cityConstructions.getConstruction(command.constructionName)
        } catch (e: Exception) {
            throw CommandException("'${command.constructionName}' is not a known building or unit in this ruleset")
        }

        // Validate buildability using the exact same gate the CityScreen uses to enable the button.
        if (!city.cityConstructions.canAddToQueue(construction))
            throw CommandException(
                "'${command.constructionName}' cannot currently be built in city '${city.name}'"
            )

        // Set as the current (top-of-queue) construction — the same path CityScreen uses.
        city.cityConstructions.setCurrentConstruction(command.constructionName)
        city.cityConstructions.currentConstructionIsUserSet = true
    }

    private fun executeChooseTech(gameInfo: GameInfo, playerCivId: String, command: GameCommand.ChooseTech) {
        val actingCiv = requireCiv(gameInfo, playerCivId)

        val tech = gameInfo.ruleset.technologies[command.techName]
            ?: throw CommandException("'${command.techName}' is not a known technology in this ruleset")

        val techManager = actingCiv.tech
        if (techManager.isResearched(tech.name) && !tech.isContinuallyResearchable())
            throw CommandException("'${command.techName}' is already researched")

        // Mirror TechPickerScreen: plot the prerequisite path to the chosen tech. An empty path
        // means the tech is unreachable (unresearchable / missing prerequisites that themselves are
        // unreachable).
        val path = techManager.getRequiredTechsToDestination(tech)
        if (path.isEmpty())
            throw CommandException("'${command.techName}' is not researchable for '$playerCivId'")

        // Same assignment the picker makes on confirm: replace the research queue with the path and
        // bank any overflow science toward it.
        techManager.techsToResearch = ArrayList(path.map { it.name })
        techManager.updateResearchProgress()
    }

    private fun executePromoteUnit(gameInfo: GameInfo, playerCivId: String, command: GameCommand.PromoteUnit) {
        val actingCiv = requireCiv(gameInfo, playerCivId)
        val tile = requireTile(gameInfo, command.x, command.y, "Target")

        val unit = tile.getUnits().firstOrNull { it.owner == actingCiv.civID }
            ?: throw CommandException(
                "No unit owned by '$playerCivId' at tile (${command.x}, ${command.y})"
            )

        val promotion = gameInfo.ruleset.unitPromotions[command.promotionName]
            ?: throw CommandException("'${command.promotionName}' is not a known promotion in this ruleset")

        // The promotion must be currently available to this unit (right unit type, prerequisites,
        // not already taken, not blocked by uniques) — the same gate the promotion picker enforces.
        if (unit.promotions.getAvailablePromotions().none { it.name == promotion.name })
            throw CommandException(
                "Promotion '${command.promotionName}' is not available to the unit at (${command.x}, ${command.y})"
            )

        // The picker only offers promotions when the unit can be promoted (enough XP for a paid one,
        // or a free promotion is available).
        if (!unit.promotions.canBePromoted())
            throw CommandException(
                "Unit at (${command.x}, ${command.y}) cannot be promoted right now (not enough XP)"
            )

        // Apply via the engine's own promotion API — the path the promotion picker uses.
        unit.promotions.addPromotion(command.promotionName)
    }

    private fun executeGenericUnitAction(gameInfo: GameInfo, playerCivId: String, command: GameCommand.GenericUnitAction) {
        val actingCiv = requireCiv(gameInfo, playerCivId)
        val tile = requireTile(gameInfo, command.x, command.y, "Target")

        val unit = tile.getUnits().firstOrNull { it.owner == actingCiv.civID }
            ?: throw CommandException(
                "No unit owned by '$playerCivId' at tile (${command.x}, ${command.y})"
            )

        val actionType = try {
            UnitActionType.valueOf(command.actionType)
        } catch (e: IllegalArgumentException) {
            throw CommandException("Unknown unit action type '${command.actionType}'")
        }

        // FLAGGED design fork (see report): the engine's general dispatcher,
        // UnitActions.invokeUnitAction, enumerates UnitActions.getUnitActions for the un-mapped
        // simple actions (Fortify/Sleep/Explore/Disband/…), and that enumeration EAGERLY calls
        // GUI.getWorldScreen() (in addEscortAction/addSwapAction) — a hard NPE when no WorldScreen
        // exists (dedicated server, or any headless authority/test). So invokeUnitAction is only
        // safe when a WorldScreen is present (a UI-bearing client-host).
        //
        // To keep this command working in BOTH authority modes we delegate to the engine's own
        // MapUnit methods for the common no-GUI actions (the same calls those UnitAction lambdas
        // make, with the engine's own availability guards), and only fall back to the full
        // invokeUnitAction catalogue when a WorldScreen is actually available.
        if (applySimpleUnitActionDirectly(unit, actionType)) return

        // Headless-safe path for the action types that ARE mapped in UnitActions.actionTypeToFunctions
        // (SetUp, AirSweep, Paradrop-prep, Repair, ConstructImprovement, CreateImprovement, the
        // great-person Hurry*/ConductTradeMission, SpreadReligion, RemoveHeresy, GiftUnit, …). The
        // type-filtered overload `getUnitActions(unit, type)` invokes ONLY that one mapped getter and
        // never enumerates the unmapped, GUI-eager actions (Disband/Swap/Escort/Skip), so it is safe
        // without a WorldScreen. We run the first enabled action — the same one the UI would. Getters
        // whose lambdas DO push a screen (ConstructImprovement opens the picker) are still rejected
        // below when no WorldScreen exists, because they have dedicated commands instead.
        if (actionType in MAPPED_HEADLESS_SAFE_ACTIONS) {
            val invoked = UnitActions.getUnitActions(unit, actionType)
                .firstOrNull { it.action != null }
                ?.action
            if (invoked == null)
                throw CommandException(
                    "Unit action '${command.actionType}' is not available to the unit at (${command.x}, ${command.y})"
                )
            invoked.invoke()
            return
        }

        if (!GUI.isWorldLoaded())
            throw CommandException(
                "Unit action '${command.actionType}' cannot be applied without a WorldScreen on this authority " +
                    "(only the simple ongoing actions and the mapped headless-safe actions are wired headless)"
            )

        // A WorldScreen exists: delegate to the engine's full dispatcher. invokeUnitAction looks up
        // the currently-available UnitAction of the given type and runs it; returns false if none.
        val invoked = UnitActions.invokeUnitAction(unit, actionType)
        if (!invoked)
            throw CommandException(
                "Unit action '${command.actionType}' is not available to the unit at (${command.x}, ${command.y})"
            )
    }

    /**
     * Headless-safe delegation for the common simple unit actions, mirroring the exact engine calls
     * the corresponding [com.unciv.models.UnitAction] lambdas make in
     * [com.unciv.ui.screens.worldscreen.unit.actions.UnitActions], including their availability
     * guards. These set the unit's ongoing `action` / fortification and need no WorldScreen.
     *
     * @return `true` if [actionType] was a handled simple action AND was applied; `false` if it is
     *   not one of these simple actions (caller should try the full dispatcher).
     * @throws CommandException if it is a handled simple action but is not currently available.
     */
    private fun applySimpleUnitActionDirectly(unit: MapUnit, actionType: UnitActionType): Boolean {
        when (actionType) {
            UnitActionType.Fortify -> {
                if (!unit.canFortify() || !unit.hasMovement())
                    throw CommandException("Unit cannot fortify right now")
                unit.fortify()
            }
            UnitActionType.FortifyUntilHealed -> {
                if (!unit.canFortify() || !unit.hasMovement() || !unit.canHealInCurrentTile())
                    throw CommandException("Unit cannot fortify-until-healed right now")
                unit.fortifyUntilHealed()
            }
            UnitActionType.Sleep -> {
                if (unit.isFortified() || unit.canFortify() || unit.isGuarding() || !unit.hasMovement())
                    throw CommandException("Unit cannot sleep right now")
                unit.action = UnitActionType.Sleep.value
            }
            UnitActionType.SleepUntilHealed -> {
                if (unit.isFortified() || unit.canFortify() || unit.isGuarding() || !unit.hasMovement()
                    || !unit.canHealInCurrentTile())
                    throw CommandException("Unit cannot sleep-until-healed right now")
                unit.action = UnitActionType.SleepUntilHealed.value
            }
            UnitActionType.Explore -> {
                if (unit.baseUnit.movesLikeAirUnits || unit.isExploring())
                    throw CommandException("Unit cannot explore right now")
                unit.action = UnitActionType.Explore.value
                if (unit.hasMovement()) UnitAutomation.automatedExplore(unit)
            }
            UnitActionType.StopExploration -> {
                if (!unit.isExploring()) throw CommandException("Unit is not exploring")
                unit.action = null
            }
            UnitActionType.StopMovement -> {
                if (!unit.isMoving()) throw CommandException("Unit is not moving")
                unit.action = null
            }
            UnitActionType.StopAutomation -> {
                if (!unit.isAutomated()) throw CommandException("Unit is not automated")
                unit.action = null
                unit.automated = false
            }
            UnitActionType.Automate -> {
                // Mirror UnitActions.addAutomateActions: this getter is unmapped (so not reachable via
                // the mapped-action path) and the engine UI lambda is headless-safe.
                if (unit.isAutomated()) throw CommandException("Unit is already automated")
                if (!unit.hasMovement()) throw CommandException("Unit cannot be automated right now")
                unit.automated = true
                UnitAutomation.automateUnitMoves(unit)
            }
            UnitActionType.Pillage -> {
                // The mapped-looking Pillage action wraps the real work in a GUI ConfirmPopup
                // (UnitActionsPillage.getPillageActions). The single-action factory getPillageAction
                // returns the raw, headless-safe action lambda with the engine's own availability guard.
                val pillageAction = com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsPillage
                    .getPillageAction(unit, unit.getTile())?.action
                    ?: throw CommandException("Unit cannot pillage right now")
                pillageAction.invoke()
            }
            else -> return false // not a simple action — let the caller try the full dispatcher
        }
        return true
    }

    private fun executeAttackUnit(gameInfo: GameInfo, playerCivId: String, command: GameCommand.AttackUnit) {
        val actingCiv = requireCiv(gameInfo, playerCivId)
        val attackerTile = requireTile(gameInfo, command.attackerX, command.attackerY, "Attacker")
        val targetTile = requireTile(gameInfo, command.targetX, command.targetY, "Target")

        // The attacker is the acting civ's *military* unit on the attacker tile (civilians can't
        // attack); use the same ordering as the rest of the engine.
        val attacker = attackerTile.militaryUnit?.takeIf { it.owner == actingCiv.civID }
            ?: throw CommandException(
                "No military unit owned by '$playerCivId' at (${command.attackerX}, ${command.attackerY})"
            )

        if (!attacker.canAttack())
            throw CommandException(
                "Unit at (${command.attackerX}, ${command.attackerY}) cannot attack right now (no attacks left / no movement)"
            )

        // Resolve the engine's own AttackableTile for this attacker/target pair. getAttackableEnemies
        // produces every legal (tileToAttackFrom -> tileToAttack) combination considering melee
        // adjacency, ranged range, line of sight, movement, war state and unit uniques. We pick the
        // one that strikes the requested target tile, preferring the cheapest move (most movement
        // left), which matches "attack without moving if already in range".
        val attackableTile = TargetHelper
            .getAttackableEnemies(attacker, attacker.movement.getDistanceToTiles())
            .filter { it.tileToAttack == targetTile }
            .maxByOrNull { it.movementLeftAfterMovingToAttackTile }
            ?: throw CommandException(
                "Unit at (${command.attackerX}, ${command.attackerY}) cannot attack the target at " +
                    "(${command.targetX}, ${command.targetY})"
            )

        // Delegate to the engine's combat entry point. moveAndAttack moves the attacker to
        // tileToAttackFrom (a no-op for an in-range ranged attacker), handles siege set-up, then
        // strikes via attack()/Nuke. This covers melee and ranged uniformly; nuclear weapons route
        // through Nuke inside attackOrNuke.
        //
        // NOTE / FLAGGED (see report): air units and nuke targeting have extra UI-side prerequisites
        // (interception choices, air-sweep mode, nuke blast-radius confirmation) that are NOT modelled
        // as distinct commands here. They will still run through this path if a valid AttackableTile
        // resolves, but a dedicated air/nuke command + validation is deferred. Capture of civilians is
        // handled inside Battle.attack() itself, so a melee strike onto a lone enemy civilian captures
        // it as in single-player.
        Battle.moveAndAttack(MapUnitCombatant(attacker), attackableTile)
    }

    // region diplomacy

    private fun executeDeclareWar(gameInfo: GameInfo, playerCivId: String, command: GameCommand.DeclareWar) {
        val actingCiv = requireCiv(gameInfo, playerCivId)
        val targetCiv = requireOtherCiv(gameInfo, actingCiv, command.targetCivName)

        val diplomacyManager = actingCiv.getDiplomacyManager(targetCiv)
            ?: throw CommandException("'$playerCivId' has not met '${command.targetCivName}'")

        // The same gate the DiplomacyScreen war button uses (not defeated, no active peace treaty,
        // not already at war).
        if (!diplomacyManager.canDeclareWar())
            throw CommandException("'$playerCivId' cannot declare war on '${command.targetCivName}' right now")

        // Delegate to the engine's own declaration path (runs all side effects: defensive pacts,
        // betrayal modifiers, notifications, …).
        diplomacyManager.declareWar()
    }

    private fun executeMakePeace(gameInfo: GameInfo, playerCivId: String, command: GameCommand.MakePeace) {
        val actingCiv = requireCiv(gameInfo, playerCivId)
        val targetCiv = requireOtherCiv(gameInfo, actingCiv, command.targetCivName)

        val diplomacyManager = actingCiv.getDiplomacyManager(targetCiv)
            ?: throw CommandException("'$playerCivId' has not met '${command.targetCivName}'")

        // Peace is only meaningful when currently at war (mirrors the UI only offering it then).
        if (!actingCiv.isAtWarWith(targetCiv))
            throw CommandException("'$playerCivId' is not at war with '${command.targetCivName}'")

        diplomacyManager.makePeace()
    }

    private fun executeDeclareFriendship(gameInfo: GameInfo, playerCivId: String, command: GameCommand.DeclareFriendship) {
        val actingCiv = requireCiv(gameInfo, playerCivId)
        val targetCiv = requireOtherCiv(gameInfo, actingCiv, command.targetCivName)

        actingCiv.getDiplomacyManager(targetCiv)
            ?: throw CommandException("'$playerCivId' has not met '${command.targetCivName}'")

        // Same gate the engine uses (major civ, not at war, no active denouncement/friendship).
        if (!actingCiv.diplomacyFunctions.canSignDeclarationOfFriendshipWith(targetCiv))
            throw CommandException(
                "'$playerCivId' cannot sign a Declaration of Friendship with '${command.targetCivName}' right now"
            )

        actingCiv.getDiplomacyManager(targetCiv)!!.signDeclarationOfFriendship()
    }

    private fun executeDefensivePact(gameInfo: GameInfo, playerCivId: String, command: GameCommand.DefensivePact) {
        val actingCiv = requireCiv(gameInfo, playerCivId)
        val targetCiv = requireOtherCiv(gameInfo, actingCiv, command.targetCivName)

        val diplomacyManager = actingCiv.getDiplomacyManager(targetCiv)
            ?: throw CommandException("'$playerCivId' has not met '${command.targetCivName}'")

        // Same gate the trade system uses to offer a defensive pact (requires the enabling unique,
        // an active friendship, no existing pact, …).
        if (!actingCiv.diplomacyFunctions.canSignDefensivePactWith(targetCiv))
            throw CommandException(
                "'$playerCivId' cannot sign a Defensive Pact with '${command.targetCivName}' right now"
            )

        // Pact duration comes from the game speed's deal duration, matching the way TradeOffer builds
        // a (non-immediate, non-peace) treaty offer.
        diplomacyManager.signDefensivePact(gameInfo.speed.dealDuration)
    }

    private fun executeDenounce(gameInfo: GameInfo, playerCivId: String, command: GameCommand.Denounce) {
        val actingCiv = requireCiv(gameInfo, playerCivId)
        val targetCiv = requireOtherCiv(gameInfo, actingCiv, command.targetCivName)

        val diplomacyManager = actingCiv.getDiplomacyManager(targetCiv)
            ?: throw CommandException("'$playerCivId' has not met '${command.targetCivName}'")

        // The UI only offers Denounce between non-warring civs that aren't already denounced/friends.
        if (actingCiv.isAtWarWith(targetCiv))
            throw CommandException("'$playerCivId' is at war with '${command.targetCivName}' and cannot denounce them")
        if (diplomacyManager.hasFlag(DiplomacyFlags.Denunciation))
            throw CommandException("'$playerCivId' has already denounced '${command.targetCivName}'")
        if (diplomacyManager.hasFlag(DiplomacyFlags.DeclarationOfFriendship))
            throw CommandException("'$playerCivId' cannot denounce a Declaration-of-Friendship partner")

        diplomacyManager.denounce()
    }

    private fun executeGiftGold(gameInfo: GameInfo, playerCivId: String, command: GameCommand.GiftGold) {
        val actingCiv = requireCiv(gameInfo, playerCivId)
        val targetCiv = requireOtherCiv(gameInfo, actingCiv, command.targetCivName)

        // The player "Give a Gift" action targets a city-state and runs through
        // CityStateFunctions.receiveGoldGift (moves the gold + grants influence). (DiplomacyManager
        // .giftGold is a trade-internal modifier-only helper and is NOT the player gift path.)
        if (!targetCiv.isCityState)
            throw CommandException("'${command.targetCivName}' is not a city-state; gold can only be gifted to city-states")
        if (actingCiv.getDiplomacyManager(targetCiv) == null)
            throw CommandException("'$playerCivId' has not met '${command.targetCivName}'")
        if (command.gold <= 0)
            throw CommandException("Gift amount must be positive")
        if (actingCiv.isAtWarWith(targetCiv))
            throw CommandException("'$playerCivId' is at war with '${command.targetCivName}' and cannot gift gold")
        if (actingCiv.gold < command.gold)
            throw CommandException("'$playerCivId' cannot afford to gift ${command.gold} gold")

        targetCiv.cityStateFunctions.receiveGoldGift(actingCiv, command.gold)
    }

    private fun executeDemandResponse(gameInfo: GameInfo, playerCivId: String, command: GameCommand.DemandResponse) {
        val actingCiv = requireCiv(gameInfo, playerCivId)
        val demandingCiv = requireOtherCiv(gameInfo, actingCiv, command.targetCivName)

        val diplomacyManager = actingCiv.getDiplomacyManager(demandingCiv)
            ?: throw CommandException("'$playerCivId' has not met '${command.targetCivName}'")

        val demand = try {
            Demand.valueOf(command.demandName)
        } catch (e: IllegalArgumentException) {
            throw CommandException("Unknown demand '${command.demandName}'")
        }

        // The demand was raised by the other civ and recorded as a PopupAlert on the acting civ
        // (value = the demanding civ's id). It must still be pending to respond to it.
        val pendingAlert = actingCiv.popupAlerts.firstOrNull {
            it.type == demand.demandAlert && it.value == demandingCiv.civID
        } ?: throw CommandException(
            "No pending '${command.demandName}' demand from '${command.targetCivName}' for '$playerCivId'"
        )

        // Delegate to the engine's own response path (mirrors AlertPopup.addDemand).
        if (command.agree) diplomacyManager.agreeToDemand(demand)
        else diplomacyManager.refuseDemand(demand) // (handles the DoNotAttackUs -> declareWar case internally)

        // Consume the resolved alert so it isn't presented/answered again.
        actingCiv.popupAlerts.remove(pendingAlert)
    }

    private fun executeCityStateProtection(gameInfo: GameInfo, playerCivId: String, command: GameCommand.CityStateProtection) {
        val actingCiv = requireCiv(gameInfo, playerCivId)
        val cityState = requireOtherCiv(gameInfo, actingCiv, command.cityStateCivName)

        if (!cityState.isCityState)
            throw CommandException("'${command.cityStateCivName}' is not a city-state")
        if (actingCiv.getDiplomacyManager(cityState) == null)
            throw CommandException("'$playerCivId' has not met '${command.cityStateCivName}'")

        // addProtectorCiv/removeProtectorCiv silently no-op when not allowed, so we validate first and
        // delegate to the engine's own (city-state-side) methods, with the acting civ as the protector.
        if (command.pledge) {
            if (!cityState.cityStateFunctions.otherCivCanPledgeProtection(actingCiv))
                throw CommandException("'$playerCivId' cannot pledge protection over '${command.cityStateCivName}' right now")
            cityState.cityStateFunctions.addProtectorCiv(actingCiv)
        } else {
            if (!cityState.cityStateFunctions.otherCivCanWithdrawProtection(actingCiv))
                throw CommandException("'$playerCivId' cannot withdraw protection over '${command.cityStateCivName}' right now")
            cityState.cityStateFunctions.removeProtectorCiv(actingCiv)
        }
    }

    // endregion

    // region trade

    private fun executeRespondToTrade(gameInfo: GameInfo, playerCivId: String, command: GameCommand.RespondToTrade) {
        val actingCiv = requireCiv(gameInfo, playerCivId)
        val requestingCiv = requireOtherCiv(gameInfo, actingCiv, command.fromCivName)

        // Match the pending request on the authority by requesting civ (the wire never carries the
        // bilateral Trade — see ProposeTrade deferral note in GameCommand).
        val tradeRequest = actingCiv.tradeRequests.firstOrNull { it.requestingCiv == requestingCiv.civID }
            ?: throw CommandException(
                "No pending trade request from '${command.fromCivName}' for '$playerCivId'"
            )

        if (command.accept) {
            // Mirror TradePopup "Sounds good!": rebuild the TradeLogic and run the engine's own accept.
            val tradeLogic = TradeLogic(actingCiv, requestingCiv)
            tradeLogic.currentTrade.set(tradeRequest.trade)
            tradeLogic.acceptTrade()
        } else {
            // Mirror TradePopup "Not this time.": engine sets the decline cooldown flags.
            tradeRequest.decline(actingCiv)
        }

        // Both paths consume the request (TradePopup removes it on close in either case).
        actingCiv.tradeRequests.remove(tradeRequest)
    }

    // endregion

    // region policy

    private fun executeAdoptPolicy(gameInfo: GameInfo, playerCivId: String, command: GameCommand.AdoptPolicy) {
        val actingCiv = requireCiv(gameInfo, playerCivId)

        // Both individual policies and policy branches live in ruleset.policies keyed by name, so a
        // single name resolves either.
        val policy = gameInfo.ruleset.policies[command.policyName]
            ?: throw CommandException("'${command.policyName}' is not a known policy in this ruleset")

        val policyManager = actingCiv.policies
        // Same gate the PolicyPickerScreen enforces (excluding UI-only isCurrentPlayer): not adopted,
        // not an automatic branch-completion policy, rule-adoptable, and the civ can afford a policy.
        if (policyManager.isAdopted(policy.name))
            throw CommandException("'${command.policyName}' is already adopted by '$playerCivId'")
        if (!policyManager.isAdoptable(policy))
            throw CommandException("'${command.policyName}' is not adoptable by '$playerCivId' right now")
        if (!policyManager.canAdoptPolicy())
            throw CommandException("'$playerCivId' cannot adopt a policy right now (not enough culture / no free policy)")

        // Delegate to the engine (handles culture cost, branch auto-completion, triggered uniques, …).
        policyManager.adopt(policy)
    }

    // endregion

    // region city management

    private fun executeBuyConstruction(gameInfo: GameInfo, playerCivId: String, command: GameCommand.BuyConstruction) {
        val actingCiv = requireCiv(gameInfo, playerCivId)
        val city = requireOwnedCity(gameInfo, actingCiv, command.cityX, command.cityY)

        // Resolve the stat to pay with by enum constant name; the engine's own gate rejects
        // Production/Happiness (which are not in statsUsableToBuy), so we don't special-case here.
        val stat = Stat.safeValueOf(command.stat)
            ?: throw CommandException("Unknown stat '${command.stat}'")

        // Resolve the construction by name through the city's ruleset; only a non-perpetual
        // construction (building or unit) can be purchased.
        val construction = try {
            city.cityConstructions.getConstruction(command.constructionName)
        } catch (e: Exception) {
            throw CommandException("'${command.constructionName}' is not a known building or unit in this ruleset")
        }
        if (construction !is INonPerpetualConstruction)
            throw CommandException("'${command.constructionName}' cannot be purchased")

        // The optional improvement tile is only meaningful for CreatesOneImprovement buildings (the
        // CityScreen tile-picker passes it down); resolve it if supplied, else let the engine choose.
        val improvementTileX = command.improvementTileX
        val improvementTileY = command.improvementTileY
        val improvementTile: Tile? =
            if (improvementTileX != null && improvementTileY != null)
                requireTile(gameInfo, improvementTileX, improvementTileY, "Improvement")
            else null

        // The one true buy test (puppet/resistance/purchasable/placement/stat/affordability) — the same
        // gate the BuyButtonFactory uses to enable the button.
        val buyCost = construction.getStatBuyCost(city, stat)
            ?: throw CommandException("'${command.constructionName}' cannot be purchased with ${stat.name}")
        if (!city.cityConstructions.isConstructionPurchaseAllowed(construction, stat, buyCost))
            throw CommandException(
                "'${command.constructionName}' cannot currently be purchased with ${stat.name} in city '${city.name}'"
            )

        // Delegate to the engine's own purchase path (queuePosition -1 = not from queue, like the UI's
        // ad-hoc buy). Returns false when e.g. a unit can't be placed; surface that as a clean failure.
        val bought = city.cityConstructions.purchaseConstruction(
            construction, -1, automatic = false, stat = stat, tile = improvementTile
        )
        if (!bought)
            throw CommandException("Could not place '${command.constructionName}' near city '${city.name}'")

        // Re-apply worked-tiles optimization exactly as the BuyButtonFactory does after a purchase.
        city.reassignPopulation()
    }

    private fun executeRazeCity(gameInfo: GameInfo, playerCivId: String, command: GameCommand.RazeCity) {
        val actingCiv = requireCiv(gameInfo, playerCivId)
        val city = requireOwnedCity(gameInfo, actingCiv, command.cityX, command.cityY)

        if (command.raze) {
            // Mirror the CityScreen raze button gate: city can be destroyed AND the civ may annex
            // (a "may not annex" civ keeps puppets and cannot start razing from this screen).
            if (actingCiv.hasUnique(UniqueType.MayNotAnnexCities))
                throw CommandException("'$playerCivId' may not annex cities and cannot raze '${city.name}'")
            if (!city.canBeDestroyed())
                throw CommandException("City '${city.name}' cannot be razed")
            city.isBeingRazed = true
        } else {
            if (!city.isBeingRazed)
                throw CommandException("City '${city.name}' is not being razed")
            city.isBeingRazed = false
        }
    }

    private fun executeAnnexCity(gameInfo: GameInfo, playerCivId: String, command: GameCommand.AnnexCity) {
        val actingCiv = requireCiv(gameInfo, playerCivId)
        val city = requireOwnedCity(gameInfo, actingCiv, command.cityX, command.cityY)

        // The CityScreen only shows the Annex button for a puppet of a civ that may annex.
        if (!city.isPuppet)
            throw CommandException("City '${city.name}' is not a puppet and cannot be annexed")
        if (actingCiv.hasUnique(UniqueType.MayNotAnnexCities))
            throw CommandException("'$playerCivId' may not annex cities")

        // Delegate to the engine (resets focus/avoid-growth, schedules reassignment, updates stats).
        city.annexCity()
    }

    private fun executeBuyTile(gameInfo: GameInfo, playerCivId: String, command: GameCommand.BuyTile) {
        val actingCiv = requireCiv(gameInfo, playerCivId)
        val city = requireOwnedCity(gameInfo, actingCiv, command.cityX, command.cityY)
        val tile = requireTile(gameInfo, command.tileX, command.tileY, "Target")

        // Same gate as askToBuyTile: the tile must be buyable for this city AND the civ must afford it.
        if (!city.expansion.canBuyTile(tile))
            throw CommandException(
                "Tile (${command.tileX}, ${command.tileY}) cannot be bought by city '${city.name}'"
            )
        val goldCost = city.expansion.getGoldCostOfTile(tile)
        if (!city.civ.hasStatToBuy(Stat.Gold, goldCost))
            throw CommandException("'$playerCivId' cannot afford to buy the tile (cost $goldCost gold)")

        // Delegate to the engine (deducts gold, takes ownership, defers reassignment).
        city.expansion.buyTile(tile)
    }

    private fun executeSetCityFocus(gameInfo: GameInfo, playerCivId: String, command: GameCommand.SetCityFocus) {
        val actingCiv = requireCiv(gameInfo, playerCivId)
        val city = requireOwnedCity(gameInfo, actingCiv, command.cityX, command.cityY)

        // The CitizenManagementTable is interactive only for non-puppet cities.
        if (city.isPuppet)
            throw CommandException("City '${city.name}' is a puppet and its focus cannot be set")

        val focus = try {
            CityFocus.valueOf(command.focusName)
        } catch (e: IllegalArgumentException) {
            throw CommandException("Unknown city focus '${command.focusName}'")
        }

        // Same path as the focus buttons: set the focus then re-optimize worked tiles.
        city.setCityFocus(focus)
        city.reassignPopulation()
    }

    private fun executeResetCitizens(gameInfo: GameInfo, playerCivId: String, command: GameCommand.ResetCitizens) {
        val actingCiv = requireCiv(gameInfo, playerCivId)
        val city = requireOwnedCity(gameInfo, actingCiv, command.cityX, command.cityY)

        if (city.isPuppet)
            throw CommandException("City '${city.name}' is a puppet and its citizens cannot be reset")

        // Mirror the "Reset Citizens" button: reassign and unlock all tiles.
        city.reassignPopulation(resetLocked = true)
    }

    private fun executeToggleAvoidGrowth(gameInfo: GameInfo, playerCivId: String, command: GameCommand.ToggleAvoidGrowth) {
        val actingCiv = requireCiv(gameInfo, playerCivId)
        val city = requireOwnedCity(gameInfo, actingCiv, command.cityX, command.cityY)

        if (city.isPuppet)
            throw CommandException("City '${city.name}' is a puppet and avoid-growth cannot be toggled")

        // Mirror the "Avoid Growth" button: flip the flag then reassign population.
        city.avoidGrowth = !city.avoidGrowth
        city.reassignPopulation()
    }

    private fun executeToggleLockedTile(gameInfo: GameInfo, playerCivId: String, command: GameCommand.ToggleLockedTile) {
        val actingCiv = requireCiv(gameInfo, playerCivId)
        val city = requireOwnedCity(gameInfo, actingCiv, command.cityX, command.cityY)
        val tile = requireTile(gameInfo, command.tileX, command.tileY, "Target")

        if (city.isPuppet)
            throw CommandException("City '${city.name}' is a puppet and its tiles cannot be locked")

        // The Lock/Unlock buttons only appear for a tile currently worked by this city.
        if (!city.isWorked(tile))
            throw CommandException(
                "Tile (${command.tileX}, ${command.tileY}) is not worked by city '${city.name}'"
            )

        // Mirror the buttons: lock toggles presence in the city's lockedTiles set.
        if (city.lockedTiles.contains(tile.position)) city.lockedTiles.remove(tile.position)
        else city.lockedTiles.add(tile.position)
        city.cityStats.update()
    }

    private fun executeSellBuilding(gameInfo: GameInfo, playerCivId: String, command: GameCommand.SellBuilding) {
        val actingCiv = requireCiv(gameInfo, playerCivId)
        val city = requireOwnedCity(gameInfo, actingCiv, command.cityX, command.cityY)

        val building = city.getRuleset().buildings[command.buildingName]
            ?: throw CommandException("'${command.buildingName}' is not a known building in this ruleset")

        // The ConstructionInfoTable only enables Sell for a sellable building that is actually built,
        // not free, in a non-puppet city, and at most once per turn (unless godMode).
        if (!building.isSellable())
            throw CommandException("'${command.buildingName}' cannot be sold")
        if (!city.cityConstructions.isBuilt(building.name))
            throw CommandException("'${command.buildingName}' is not built in city '${city.name}'")
        if (city.isPuppet)
            throw CommandException("City '${city.name}' is a puppet and buildings cannot be sold there")
        if (actingCiv.civConstructions.hasFreeBuilding(city, building))
            throw CommandException("'${command.buildingName}' is free in '${city.name}' and cannot be sold")
        if (city.hasSoldBuildingThisTurn && !gameInfo.gameParameters.godMode)
            throw CommandException("'${city.name}' has already sold a building this turn")

        // Delegate to the engine (removes the building, refunds gold, sets the sold-this-turn flag).
        city.sellBuilding(building)
    }

    // endregion

    // region espionage

    private fun executeMoveSpy(gameInfo: GameInfo, playerCivId: String, command: GameCommand.MoveSpy) {
        val actingCiv = requireCiv(gameInfo, playerCivId)
        val spy = requireSpy(actingCiv, command.spyName)

        // A sentinel position recalls the spy to the hideout (moveTo(null)).
        if (command.targetCityX == GameCommand.MoveSpy.HIDEOUT && command.targetCityY == GameCommand.MoveSpy.HIDEOUT) {
            spy.moveTo(null)
            return
        }

        // Resolve the destination city by center tile (any civ's city is a valid spy destination).
        val tile = requireTile(gameInfo, command.targetCityX, command.targetCityY, "Target")
        val city = tile.getCity()
        if (city == null || !tile.isCityCenter() || city.location != tile.position)
            throw CommandException("Tile (${command.targetCityX}, ${command.targetCityY}) is not a city center")

        // Same gate the espionage screen uses to offer a move target.
        if (!spy.canMoveTo(city))
            throw CommandException("Spy '${command.spyName}' cannot move to '${city.name}'")

        spy.moveTo(city)
    }

    private fun executeSetSpyAction(gameInfo: GameInfo, playerCivId: String, command: GameCommand.SetSpyAction) {
        val actingCiv = requireCiv(gameInfo, playerCivId)
        val spy = requireSpy(actingCiv, command.spyName)

        val action = try {
            SpyAction.valueOf(command.spyActionName)
        } catch (e: IllegalArgumentException) {
            throw CommandException("Unknown spy action '${command.spyActionName}'")
        }

        // Only the actions the player can actually choose in the espionage screen are accepted; the
        // engine sets all other actions (Moving/EstablishNetwork/StealingTech/Dead/…) itself as part
        // of the spy lifecycle. Delegate to Spy.setAction with the same turn counts the UI uses.
        when (action) {
            SpyAction.Coup -> {
                // The Coup button is only shown when the spy can stage a coup (set up in a non-allied
                // city-state).
                if (!spy.canDoCoup())
                    throw CommandException("Spy '${command.spyName}' cannot stage a coup right now")
                spy.setAction(SpyAction.Coup, 1)
            }
            SpyAction.CounterIntelligence ->
                // Cancelling a coup (or assigning counter-intelligence) uses 10 turns, matching the UI.
                spy.setAction(SpyAction.CounterIntelligence, 10)
            else ->
                throw CommandException("Spy action '${command.spyActionName}' is not player-settable")
        }
    }

    // endregion

    // region unit actions (parameterized)

    private fun executeUpgradeUnit(gameInfo: GameInfo, playerCivId: String, command: GameCommand.UpgradeUnit) {
        val actingCiv = requireCiv(gameInfo, playerCivId)
        val tile = requireTile(gameInfo, command.unitX, command.unitY, "Unit")
        val unit = requireUnitOnTile(tile, actingCiv, command.unitX, command.unitY)

        // Resolve the target to the civ's equivalent unit (the same lookup UnitActionsUpgrade uses).
        if (gameInfo.ruleset.units[command.toUnitName] == null)
            throw CommandException("'${command.toUnitName}' is not a known unit in this ruleset")
        val upgradedUnit = actingCiv.getEquivalentUnit(command.toUnitName)

        // Same gate the (paid) Upgrade action enables on: the engine's canUpgrade with resources,
        // plus enough gold, movement left, standing on owned land, not embarked.
        if (!unit.upgrade.canUpgrade(unitToUpgradeTo = upgradedUnit))
            throw CommandException("Unit at (${command.unitX}, ${command.unitY}) cannot upgrade to '${command.toUnitName}'")
        if (unit.isEmbarked())
            throw CommandException("Unit at (${command.unitX}, ${command.unitY}) cannot upgrade while embarked")
        if (!unit.hasMovement())
            throw CommandException("Unit at (${command.unitX}, ${command.unitY}) has no movement left to upgrade")
        if (unit.getTile().getOwner() != actingCiv)
            throw CommandException("Unit at (${command.unitX}, ${command.unitY}) must be on owned territory to upgrade")
        val goldCost = unit.upgrade.getCostOfUpgrade(upgradedUnit)
        if (actingCiv.gold < goldCost)
            throw CommandException("'$playerCivId' cannot afford to upgrade to '${command.toUnitName}' ($goldCost gold)")

        // Delegate to the engine's own upgrade (deducts gold, replaces the unit instance).
        unit.upgrade.performUpgrade(upgradedUnit, isFree = false, goldCostOfUpgrade = goldCost)
    }

    private fun executeBuildImprovement(gameInfo: GameInfo, playerCivId: String, command: GameCommand.BuildImprovement) {
        val actingCiv = requireCiv(gameInfo, playerCivId)
        val tile = requireTile(gameInfo, command.unitX, command.unitY, "Unit")
        val unit = requireUnitOnTile(tile, actingCiv, command.unitX, command.unitY)

        val improvement = gameInfo.ruleset.tileImprovements[command.improvementName]
            ?: throw CommandException("'${command.improvementName}' is not a known improvement in this ruleset")

        // Same gate the ImprovementPickerScreen uses to enable the improvement button:
        // the unit can build it AND the only remaining build problems (if any) are reportable.
        if (!unit.hasMovement())
            throw CommandException("Unit at (${command.unitX}, ${command.unitY}) has no movement left to build")
        if (tile.isCityCenter())
            throw CommandException("Cannot build an improvement on a city center")
        if (!unit.canBuildImprovement(improvement, tile))
            throw CommandException("Unit at (${command.unitX}, ${command.unitY}) cannot build '${command.improvementName}' here")
        val problems = tile.improvementFunctions.getImprovementBuildingProblems(improvement, unit.cache.state).toSet()
        if (!ImprovementPickerScreen.canReport(problems))
            throw CommandException("'${command.improvementName}' cannot be built here right now")

        // Apply via the same engine path the picker's accept() takes.
        tile.startWorkingOnImprovement(improvement, actingCiv, unit)
        unit.action = null // "wake up" the worker, as the picker does
    }

    private fun executeParadrop(gameInfo: GameInfo, playerCivId: String, command: GameCommand.Paradrop) {
        val actingCiv = requireCiv(gameInfo, playerCivId)
        val tile = requireTile(gameInfo, command.unitX, command.unitY, "Unit")
        val unit = requireUnitOnTile(tile, actingCiv, command.unitX, command.unitY)
        val targetTile = requireTile(gameInfo, command.targetX, command.targetY, "Target")

        // The unit must actually offer a paradrop action right now (right uniques, hasn't moved). This
        // mirrors getParadropActions and also fills unit.cache.paradropDestinationTileFilters used by
        // the reachability gate below.
        val paradropAction = UnitActions.getUnitActions(unit, UnitActionType.Paradrop)
            .firstOrNull { it.action != null }
            ?: throw CommandException("Unit at (${command.unitX}, ${command.unitY}) cannot paradrop right now")

        // Prepare the paradrop (the same toggle the action performs) so the engine's movement gate
        // routes through canParadropOn for the destination.
        if (!unit.isPreparingParadrop()) paradropAction.action!!.invoke()
        if (!unit.isPreparingParadrop())
            throw CommandException("Unit at (${command.unitX}, ${command.unitY}) could not prepare a paradrop")

        // Validate the destination with the engine's own per-turn reachability (paradrop range +
        // visibility + passability), then perform the drop. On any failure, cancel the prep so we
        // don't leave the unit half-prepared.
        if (targetTile == tile || !unit.movement.canReachInCurrentTurn(targetTile)) {
            unit.action = null
            throw CommandException(
                "Unit at (${command.unitX}, ${command.unitY}) cannot paradrop to (${command.targetX}, ${command.targetY})"
            )
        }
        unit.movement.moveToTile(targetTile)
    }

    private fun executeGiftUnit(gameInfo: GameInfo, playerCivId: String, command: GameCommand.GiftUnit) {
        val actingCiv = requireCiv(gameInfo, playerCivId)
        val tile = requireTile(gameInfo, command.unitX, command.unitY, "Unit")
        val unit = requireUnitOnTile(tile, actingCiv, command.unitX, command.unitY)

        // The recipient is the owner of the territory the unit stands in (mirrors getGiftActions).
        val recipient = tile.getOwner()
            ?: throw CommandException("Unit at (${command.unitX}, ${command.unitY}) is not in any civ's territory; cannot gift")
        if (recipient.civID == actingCiv.civID)
            throw CommandException("Cannot gift a unit to its own civ")

        // Same eligibility gate the action uses.
        if (unit.isTransported)
            throw CommandException("Transported units cannot be gifted")
        if (recipient.isCityState) {
            if (recipient.isAtWarWith(actingCiv))
                throw CommandException("Cannot gift a unit to a city-state you are at war with")
            val eligible = unit.isMilitary() || unit.getMatchingUniques(
                UniqueType.GainInfluenceWithUnitGiftToCityState, checkCivInfoUniques = true
            ).any { unit.matchesFilter(it.params[1]) }
            if (!eligible)
                throw CommandException("'${recipient.civName}' (city-state) will not accept this unit as a gift")
        } else if (!tile.isFriendlyTerritory(actingCiv)) {
            throw CommandException("Can only gift a unit inside friendly major-civ territory")
        }

        // Apply the same engine effects the action lambda does (influence/diplomatic modifiers + the
        // actual gift). A Great Person given to a city-state is destroyed, as in single-player.
        if (recipient.isCityState) {
            for (unique in unit.getMatchingUniques(
                UniqueType.GainInfluenceWithUnitGiftToCityState, checkCivInfoUniques = true
            )) {
                if (unit.matchesFilter(unique.params[1])) {
                    recipient.getDiplomacyManager(actingCiv)!!.addInfluence(unique.params[0].toFloat() - 5f)
                    break
                }
            }
            recipient.getDiplomacyManager(actingCiv)!!.addInfluence(5f)
        } else {
            recipient.getDiplomacyManager(actingCiv)!!
                .addModifier(com.unciv.logic.civilization.diplomacy.DiplomaticModifiers.GaveUsUnits, 5f)
        }

        if (recipient.isCityState && unit.isGreatPerson()) unit.destroy()
        else unit.gift(recipient)
    }

    private fun executeSwapUnits(gameInfo: GameInfo, playerCivId: String, command: GameCommand.SwapUnits) {
        val actingCiv = requireCiv(gameInfo, playerCivId)
        val tile = requireTile(gameInfo, command.unitX, command.unitY, "Unit")
        val unit = requireUnitOnTile(tile, actingCiv, command.unitX, command.unitY)
        val otherTile = requireTile(gameInfo, command.otherX, command.otherY, "Swap target")

        // canUnitSwapTo encapsulates the whole swap legality (reachable this turn, same-type owned
        // partner that can reach our tile, both can enter, escort handling) — the same gate the UI's
        // swap mode uses to highlight valid partner tiles.
        if (!unit.movement.canUnitSwapTo(otherTile))
            throw CommandException(
                "Unit at (${command.unitX}, ${command.unitY}) cannot swap with (${command.otherX}, ${command.otherY})"
            )

        unit.movement.swapMoveToTile(otherTile)
    }

    private fun executeDisbandUnit(gameInfo: GameInfo, playerCivId: String, command: GameCommand.DisbandUnit) {
        val actingCiv = requireCiv(gameInfo, playerCivId)
        val tile = requireTile(gameInfo, command.unitX, command.unitY, "Unit")
        val unit = requireUnitOnTile(tile, actingCiv, command.unitX, command.unitY)

        // The UI only offers Disband when the unit still has movement (mirrors addDisbandAction).
        if (!unit.hasMovement())
            throw CommandException("Unit at (${command.unitX}, ${command.unitY}) cannot be disbanded right now")

        // Apply the confirmed intent (the popup's confirm callback): disband + refresh upkeep.
        unit.disband()
        actingCiv.updateStatsForNextTurn()
    }

    // endregion

    // region great person

    private fun executeChooseGreatPerson(gameInfo: GameInfo, playerCivId: String, command: GameCommand.ChooseGreatPerson) {
        val actingCiv = requireCiv(gameInfo, playerCivId)
        val greatPeople = actingCiv.greatPeople

        // Same gate as GreatPersonPickerScreen: a free pick must be available and the unit must be a
        // valid great person for this civ (and within the Maya long-count pool when restricted).
        if (greatPeople.freeGreatPeople <= 0)
            throw CommandException("'$playerCivId' has no free Great Person to choose")
        val chosen = greatPeople.getGreatPeople().firstOrNull { it.name == command.unitName }
            ?: throw CommandException("'${command.unitName}' is not an available Great Person for '$playerCivId'")
        val useMayaLongCount = greatPeople.mayaLimitedFreeGP > 0
        if (useMayaLongCount && command.unitName !in greatPeople.longCountGPPool)
            throw CommandException("'${command.unitName}' is not selectable under the Maya long-count restriction")

        // Mirror confirmAction: add the unit in the capital and decrement the free-GP counters.
        actingCiv.units.addUnit(chosen, actingCiv.getCapital())
        greatPeople.freeGreatPeople--
        if (useMayaLongCount) {
            greatPeople.mayaLimitedFreeGP--
            greatPeople.longCountGPPool.remove(command.unitName)
        }
    }

    // endregion

    // region religion

    private fun executeFoundPantheon(gameInfo: GameInfo, playerCivId: String, command: GameCommand.FoundPantheon) {
        val actingCiv = requireCiv(gameInfo, playerCivId)
        val religionManager = actingCiv.religionManager

        // Same gate the PantheonPickerScreen uses.
        if (!religionManager.canFoundOrExpandPantheon())
            throw CommandException("'$playerCivId' cannot found or expand a pantheon right now")

        val belief = gameInfo.ruleset.beliefs[command.beliefName]
            ?: throw CommandException("'${command.beliefName}' is not a known belief in this ruleset")
        if (belief.type != BeliefType.Pantheon)
            throw CommandException("'${command.beliefName}' is not a Pantheon belief")
        // The belief must be free (not already taken by anyone) — same check the picker enables on.
        if (religionManager.getReligionWithBelief(belief) != null)
            throw CommandException("Belief '${command.beliefName}' is already taken")

        // Apply via the engine's own path (chooseBeliefs founds the pantheon when state == None).
        religionManager.chooseBeliefs(listOf(belief), useFreeBeliefs = religionManager.usingFreeBeliefs())
    }

    private fun executeFoundReligion(gameInfo: GameInfo, playerCivId: String, command: GameCommand.FoundReligion) {
        val actingCiv = requireCiv(gameInfo, playerCivId)
        val tile = requireTile(gameInfo, command.unitX, command.unitY, "Unit")
        val prophet = requireUnitOnTile(tile, actingCiv, command.unitX, command.unitY)
        val religionManager = actingCiv.religionManager

        // The prophet must be able to found a religion here AND offer the action right now (uniques,
        // movement, side-effect availability) — mirrors getFoundReligionActions.
        if (!religionManager.mayFoundReligionHere(tile))
            throw CommandException("'$playerCivId' cannot found a religion at (${command.unitX}, ${command.unitY})")
        if (UnitActions.getUnitActions(prophet, UnitActionType.FoundReligion).none { it.action != null })
            throw CommandException("Unit at (${command.unitX}, ${command.unitY}) cannot found a religion right now")

        // Validate the religion icon name (must be a ruleset religion not yet taken in this game).
        if (command.religionName !in gameInfo.ruleset.religions)
            throw CommandException("'${command.religionName}' is not a religion in this ruleset")
        if (gameInfo.religions.values.any { it.name == command.religionName })
            throw CommandException("Religion '${command.religionName}' has already been founded")

        // Resolve + validate the chosen beliefs against the engine's own "what to choose" plan.
        val beliefs = resolveChoosableBeliefs(
            gameInfo, religionManager, command.beliefNames, religionManager.getBeliefsToChooseAtFounding()
        )

        // Replay the picker's OK action: assign the religion name/holy city, then add the beliefs.
        val displayName = command.displayName.ifBlank { command.religionName }
        religionManager.foundReligion(prophet) // sets state to FoundingReligion + records holy city
        religionManager.foundReligion(displayName, command.religionName)
        religionManager.chooseBeliefs(beliefs, religionManager.usingFreeBeliefs())
    }

    private fun executeEnhanceReligion(gameInfo: GameInfo, playerCivId: String, command: GameCommand.EnhanceReligion) {
        val actingCiv = requireCiv(gameInfo, playerCivId)
        val tile = requireTile(gameInfo, command.unitX, command.unitY, "Unit")
        val prophet = requireUnitOnTile(tile, actingCiv, command.unitX, command.unitY)
        val religionManager = actingCiv.religionManager

        if (!religionManager.mayEnhanceReligionHere(tile))
            throw CommandException("'$playerCivId' cannot enhance a religion at (${command.unitX}, ${command.unitY})")
        if (UnitActions.getUnitActions(prophet, UnitActionType.EnhanceReligion).none { it.action != null })
            throw CommandException("Unit at (${command.unitX}, ${command.unitY}) cannot enhance a religion right now")

        val beliefs = resolveChoosableBeliefs(
            gameInfo, religionManager, command.beliefNames, religionManager.getBeliefsToChooseAtEnhancing()
        )

        // Replay the picker's OK action: mark the prophet used, then add the beliefs.
        religionManager.useProphetForEnhancingReligion(prophet)
        religionManager.chooseBeliefs(beliefs, religionManager.usingFreeBeliefs())
    }

    private fun executeSpreadReligion(gameInfo: GameInfo, playerCivId: String, command: GameCommand.SpreadReligion) {
        val actingCiv = requireCiv(gameInfo, playerCivId)
        val unitTile = requireTile(gameInfo, command.unitX, command.unitY, "Unit")
        val unit = requireUnitOnTile(unitTile, actingCiv, command.unitX, command.unitY)
        val targetCityTile = requireCityCenterTile(gameInfo, command.targetCityX, command.targetCityY)

        // The unit must be standing on the target city's tile (the spread action acts on the unit's
        // own current tile) and currently be able to spread there.
        if (unitTile != targetCityTile)
            throw CommandException("The missionary must be on the target city's center tile to spread religion")
        if (!actingCiv.religionManager.maySpreadReligionNow(unit))
            throw CommandException("Unit at (${command.unitX}, ${command.unitY}) cannot spread religion here right now")

        invokeUnitTileAction(unit, UnitActionType.SpreadReligion, "spread religion")
    }

    private fun executeRemoveHeresy(gameInfo: GameInfo, playerCivId: String, command: GameCommand.RemoveHeresy) {
        val actingCiv = requireCiv(gameInfo, playerCivId)
        val unitTile = requireTile(gameInfo, command.unitX, command.unitY, "Unit")
        val unit = requireUnitOnTile(unitTile, actingCiv, command.unitX, command.unitY)
        val targetCityTile = requireCityCenterTile(gameInfo, command.targetCityX, command.targetCityY)

        if (unitTile != targetCityTile)
            throw CommandException("The inquisitor must be on the target city's center tile to remove heresy")
        val city = targetCityTile.getCity()
        if (city == null || city.civ.civID != actingCiv.civID)
            throw CommandException("Remove Heresy can only be used in your own city")

        invokeUnitTileAction(unit, UnitActionType.RemoveHeresy, "remove heresy")
    }

    /** Run the (headless-safe, tile-acting) unit action [type] for [unit], or fail cleanly. */
    private fun invokeUnitTileAction(unit: MapUnit, type: UnitActionType, label: String) {
        val action = UnitActions.getUnitActions(unit, type)
            .firstOrNull { it.action != null }
            ?.action
            ?: throw CommandException("Unit cannot $label right now")
        action.invoke()
    }

    /**
     * Resolve [beliefNames] to [Belief] objects and validate they form a legal selection: every name
     * exists, none is already taken in this game, and the multiset of belief *types* exactly matches
     * the engine's own plan of [allowedByType] beliefs to choose for this prophet use. This mirrors the
     * ReligiousBeliefsPickerScreen, which only lets the player confirm once each required slot is filled
     * with an available belief of the right type.
     */
    private fun resolveChoosableBeliefs(
        gameInfo: GameInfo,
        religionManager: com.unciv.logic.civilization.managers.ReligionManager,
        beliefNames: List<String>,
        allowedByType: com.unciv.models.Counter<BeliefType>
    ): List<Belief> {
        val beliefs = beliefNames.map { name ->
            gameInfo.ruleset.beliefs[name]
                ?: throw CommandException("'$name' is not a known belief in this ruleset")
        }

        // None of the chosen beliefs may already be held by another religion.
        for (belief in beliefs) {
            val owner = religionManager.getReligionWithBelief(belief)
            if (owner != null && owner != religionManager.religion)
                throw CommandException("Belief '${belief.name}' is already taken")
        }

        // The chosen belief types must exactly match what the engine expects to be chosen, where an
        // "Any" slot accepts a belief of any type. Validate counts so we neither over- nor under-pick.
        val required = com.unciv.models.Counter<BeliefType>()
        for ((type, count) in allowedByType) required.add(type, count)
        val totalRequired = required.sumValues()
        if (beliefs.size != totalRequired)
            throw CommandException("Expected $totalRequired belief(s) to choose, got ${beliefs.size}")

        // Greedily assign each chosen belief to a matching required slot (its own type, else an Any slot).
        val remaining = required.clone()
        for (belief in beliefs) {
            when {
                remaining[belief.type] > 0 -> remaining.add(belief.type, -1)
                remaining[BeliefType.Any] > 0 -> remaining.add(BeliefType.Any, -1)
                else -> throw CommandException("Belief '${belief.name}' (${belief.type}) is not a valid choice here")
            }
        }
        return beliefs
    }

    // endregion

    // region helpers

    /** A target civ named [name], distinct from [actingCiv], that exists in this game. */
    private fun requireOtherCiv(gameInfo: GameInfo, actingCiv: Civilization, name: String): Civilization {
        val targetCiv = gameInfo.getCivilizationOrNull(name)
            ?: throw CommandException("Unknown target civ '$name'")
        if (targetCiv.civID == actingCiv.civID)
            throw CommandException("Target civ '$name' is the acting civ")
        return targetCiv
    }


    /** The issuing player must be a real civ in this game. */
    private fun requireCiv(gameInfo: GameInfo, playerCivId: String): Civilization =
        gameInfo.getCivilizationOrNull(playerCivId)
            ?: throw CommandException("Unknown acting civ '$playerCivId'")

    /** The tile at ([x], [y]) must exist on the map. [role] is used only for the error message. */
    private fun requireTile(gameInfo: GameInfo, x: Int, y: Int, role: String): Tile =
        gameInfo.tileMap.getOrNull(x, y)
            ?: throw CommandException("$role tile ($x, $y) is not on the map")

    /** The city centered on ([x], [y]) must exist and be owned by [actingCiv]. */
    private fun requireOwnedCity(gameInfo: GameInfo, actingCiv: Civilization, x: Int, y: Int): City {
        val tile = requireTile(gameInfo, x, y, "City")
        val city = tile.getCity()
            ?: throw CommandException("No city at tile ($x, $y)")
        if (!tile.isCityCenter() || city.location != tile.position)
            throw CommandException("Tile ($x, $y) is not a city center")
        if (city.civ.civID != actingCiv.civID)
            throw CommandException("City '${city.name}' at ($x, $y) is not owned by '${actingCiv.civID}'")
        return city
    }

    /**
     * The single unit on [tile] owned by [civId] that we can act on, or `null` if there is none.
     * Prefers the military unit, then the civilian unit, then any air unit — matching the engine's
     * own [Tile.getFirstUnit] ordering. Foreign-owned units on the tile are ignored.
     */
    private fun findMovableUnit(tile: Tile, civId: String): MapUnit? =
        tile.getUnits().firstOrNull { it.owner == civId }

    /** The (first) unit on [tile] owned by [actingCiv]; throws cleanly if none. */
    private fun requireUnitOnTile(tile: Tile, actingCiv: Civilization, x: Int, y: Int): MapUnit =
        tile.getUnits().firstOrNull { it.owner == actingCiv.civID }
            ?: throw CommandException("No unit owned by '${actingCiv.civID}' at tile ($x, $y)")

    /** The tile at ([x], [y]) which must be a city center (of any civ). */
    private fun requireCityCenterTile(gameInfo: GameInfo, x: Int, y: Int): Tile {
        val tile = requireTile(gameInfo, x, y, "City")
        val city = tile.getCity()
        if (city == null || !tile.isCityCenter() || city.location != tile.position)
            throw CommandException("Tile ($x, $y) is not a city center")
        return tile
    }

    /**
     * The spy named [spyName] belonging to [actingCiv], by its stable `Spy.name`.
     *
     * Spies are keyed by name: each spy receives a unique name from its nation's pool at recruitment
     * (`EspionageManager.getSpyName`), the name is stable across the game (only a *dead* spy is renamed
     * on revival), and the espionage screen lists/selects spies the same way. There is no separate
     * stable id, so the name is the canonical wire locator.
     */
    private fun requireSpy(actingCiv: Civilization, spyName: String): Spy =
        actingCiv.espionageManager.spyList.firstOrNull { it.name == spyName }
            ?: throw CommandException("No spy named '$spyName' for '${actingCiv.civID}'")

    // endregion

    private companion object {
        /**
         * Unit-action types that are registered in [UnitActions] `actionTypeToFunctions` AND whose
         * getter + action lambda are headless-safe (touch no `GUI`/WorldScreen). For these the executor
         * can drive [GameCommand.GenericUnitAction] without a WorldScreen by invoking the type-filtered
         * getter directly (which never enumerates the GUI-eager unmapped actions). This routes the
         * on-map Great Person actions and the no-target religion actions through GenericUnitAction
         * instead of dedicated commands. (ConstructImprovement is deliberately excluded — its lambda
         * opens the improvement picker — it has the dedicated [GameCommand.BuildImprovement] instead.)
         */
        val MAPPED_HEADLESS_SAFE_ACTIONS: Set<UnitActionType> = setOf(
            UnitActionType.SetUp,
            UnitActionType.AirSweep,
            UnitActionType.Paradrop,
            UnitActionType.Repair,
            UnitActionType.CreateImprovement,
            UnitActionType.HurryResearch,
            UnitActionType.HurryPolicy,
            UnitActionType.HurryWonder,
            UnitActionType.HurryBuilding,
            UnitActionType.ConductTradeMission,
            UnitActionType.SpreadReligion,
            UnitActionType.RemoveHeresy,
            UnitActionType.GiftUnit,
            UnitActionType.Transform,
            UnitActionType.AddInCapital,
            UnitActionType.Guard
        )
    }
}
