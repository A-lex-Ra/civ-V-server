package com.unciv.logic.multiplayer.v2.command

import com.unciv.GUI
import com.unciv.logic.GameInfo
import com.unciv.logic.automation.unit.UnitAutomation
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.unique.UniqueTarget
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.network.command.GameCommand
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
 * issuing client (see docs/multiplayer-v2.md §5).
 */
class CommandException(message: String) : Exception(message)

/**
 * The single choke-point that mutates [GameInfo] in multiplayer v2.
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
 * driven by the session/authority loop (see docs/multiplayer-v2.md Phase 3), not by this executor.
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
            is GameCommand.EndTurn ->
                // Inter-turn processing (GameInfo.nextTurn) is owned by the session/authority loop,
                // not the executor (see docs/multiplayer-v2.md Phase 3).
                throw NotImplementedError("CommandExecutor does not handle EndTurn; the session drives nextTurn (see docs/multiplayer-v2.md Phase 3)")
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

        if (!GUI.isWorldLoaded())
            throw CommandException(
                "Unit action '${command.actionType}' cannot be applied without a WorldScreen on this authority " +
                    "(only Fortify/FortifyUntilHealed/Sleep/SleepUntilHealed/Explore/StopMovement/StopExploration/StopAutomation are wired headless)"
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

    // region helpers

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

    // endregion
}
