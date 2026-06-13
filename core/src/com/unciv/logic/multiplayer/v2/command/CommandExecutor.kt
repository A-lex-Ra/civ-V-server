package com.unciv.logic.multiplayer.v2.command

import com.unciv.logic.GameInfo
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.network.command.GameCommand

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
 * Phase 2: [GameCommand.MoveUnit] is wired through here. The rest of the catalogue
 * (`AttackUnit`, `FoundCity`, …) and `EndTurn` (inter-turn processing) land in later phases.
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
            is GameCommand.EndTurn ->
                // Inter-turn processing (GameInfo.nextTurn) is wired through here in a later phase.
                throw NotImplementedError("CommandExecutor does not handle EndTurn yet (see docs/multiplayer-v2.md Phase 3)")
        }
    }

    private fun executeMoveUnit(gameInfo: GameInfo, playerCivId: String, command: GameCommand.MoveUnit) {
        // The issuing player must be a real civ in this game.
        val actingCiv = gameInfo.getCivilizationOrNull(playerCivId)
            ?: throw CommandException("Unknown acting civ '$playerCivId'")

        // Source and destination tiles must exist on the map.
        val fromTile = gameInfo.tileMap.getOrNull(command.fromX, command.fromY)
            ?: throw CommandException("Source tile (${command.fromX}, ${command.fromY}) is not on the map")
        val toTile = gameInfo.tileMap.getOrNull(command.toX, command.toY)
            ?: throw CommandException("Destination tile (${command.toX}, ${command.toY}) is not on the map")

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

    /**
     * The single unit on [tile] owned by [civId] that we can act on, or `null` if there is none.
     * Prefers the military unit, then the civilian unit, then any air unit — matching the engine's
     * own [Tile.getFirstUnit] ordering. Foreign-owned units on the tile are ignored.
     */
    private fun findMovableUnit(tile: Tile, civId: String): MapUnit? =
        tile.getUnits().firstOrNull { it.owner == civId }
}
