package com.unciv.logic.multiplayer.v3

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.multiplayer.v3.command.CommandException
import com.unciv.logic.multiplayer.v3.command.CommandExecutor
import com.unciv.network.command.GameCommand
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 2 deliverable check (see docs/multiplayer-v3.md §10): the [CommandExecutor] applies a
 * [GameCommand] to the canonical [com.unciv.logic.GameInfo] through the engine's own movement API,
 * and rejects an illegal command without mutating state.
 */
@RunWith(GdxTestRunner::class)
class CommandExecutorTest {

    private val testGame = TestGame()
    private lateinit var civInfo: Civilization
    private lateinit var enemyCiv: Civilization
    private val executor = CommandExecutor()

    @Before
    fun setUp() {
        testGame.makeHexagonalMap(2)
        civInfo = testGame.addCiv()
        enemyCiv = testGame.addCiv()
    }

    private fun moveCommand(from: Tile, to: Tile) = GameCommand.MoveUnit(
        unitId = 0, // unit identity is by acting civ + source tile; unitId is unused this phase
        fromX = from.position.x, fromY = from.position.y,
        toX = to.position.x, toY = to.position.y
    )

    @Test
    fun legalMoveMutatesGameInfo() {
        val from = testGame.tileMap[0, 0]
        val to = testGame.tileMap[1, 0]
        val unit: MapUnit = testGame.addUnit("Warrior", civInfo, from)
        assertEquals(from, unit.currentTile)

        executor.execute(testGame.gameInfo, civInfo.civID, moveCommand(from, to))

        assertEquals("Unit should end on the target tile", to, unit.currentTile)
        assertEquals("Tile should now hold the moved unit", unit, to.militaryUnit)
    }

    @Test
    fun moveByNonOwnerIsRejectedAndStateUnchanged() {
        val from = testGame.tileMap[0, 0]
        val to = testGame.tileMap[1, 0]
        val unit = testGame.addUnit("Warrior", civInfo, from)

        // enemyCiv does not own the unit on the source tile -> illegal.
        assertThrows(CommandException::class.java) {
            executor.execute(testGame.gameInfo, enemyCiv.civID, moveCommand(from, to))
        }

        assertEquals("Unit must not have moved on a rejected command", from, unit.currentTile)
        assertEquals(unit, from.militaryUnit)
    }

    @Test
    fun moveBeyondReachIsRejectedAndStateUnchanged() {
        val from = testGame.tileMap[0, 0]
        val to = testGame.tileMap[1, 0]
        val unit = testGame.addUnit("Warrior", civInfo, from)
        unit.currentMovement = 0f // cannot reach anything this turn

        assertThrows(CommandException::class.java) {
            executor.execute(testGame.gameInfo, civInfo.civID, moveCommand(from, to))
        }

        assertEquals("Unit must not have moved when it has no movement left", from, unit.currentTile)
    }
}
