package com.unciv.logic.multiplayer.v2

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.multiplayer.v2.visibility.PlayerViewProjector
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 3a deliverable check (see docs/multiplayer-v2.md §2 goal #3, §10 Phase 3): the
 * [PlayerViewProjector] produces a redacted deep copy of the canonical [GameInfo] that is safe to
 * send to a given player — enemy units the viewer cannot see are absent, the viewer's own units
 * survive, and the canonical game is left untouched.
 */
@RunWith(GdxTestRunner::class)
class PlayerViewProjectorTest {

    private val testGame = TestGame()
    private lateinit var civA: Civilization
    private lateinit var civB: Civilization

    /** Center of the hex map — tile (0,0) always exists on a TestGame hexagonal map. */
    private val centerTile: Tile get() = testGame.tileMap[0, 0]

    @Before
    fun setUp() {
        // A reasonably large map so we have tiles that are far out of any unit's sight radius.
        testGame.makeHexagonalMap(6)
        civA = testGame.addCiv()
        civB = testGame.addCiv()
    }

    /** A tile as far as possible from [centerTile] — guaranteed outside any normal unit's sight. */
    private fun farTile(): Tile =
        testGame.tileMap.values.maxByOrNull { it.aerialDistanceTo(centerTile) }!!

    /** All units in [gameInfo] owned by [civId], located by scanning every tile. */
    private fun unitsOf(gameInfo: GameInfo, civId: String): List<MapUnit> =
        gameInfo.tileMap.values.flatMap { it.getUnits().toList() }.filter { it.owner == civId }

    @Test
    fun enemyUnitOnFoggedTileIsRemovedFromView() {
        // A's unit sits at the center; B's unit sits at the far edge, well outside A's sight.
        val far = farTile()
        testGame.addUnit("Warrior", civA, centerTile)
        testGame.addUnit("Warrior", civB, far)

        // Sanity: in the canonical game the far tile is genuinely not visible to A.
        testGame.gameInfo.civilizations.forEach { it.cache.updateOurTiles() }
        assertFalse("Far tile must be fogged for A in this setup", far.isVisible(civA))

        val viewForA = PlayerViewProjector.projectFor(testGame.gameInfo, civA.civID)

        val bUnitsInAView = unitsOf(viewForA, civB.civID)
        assertTrue(
            "B's unit on a tile A cannot see must be ABSENT from A's view (found ${bUnitsInAView.size})",
            bUnitsInAView.isEmpty()
        )
        // And the tile itself, in A's view, must hold no military unit.
        val farInView = viewForA.tileMap[far.position.x, far.position.y]
        assertNull("Fogged enemy unit must be gone from the tile in A's view", farInView.militaryUnit)
    }

    @Test
    fun enemyUnitOnVisibleTileIsKeptInView() {
        // Put A and B units adjacent so the B unit sits on a tile A can currently see.
        val adjacentTile = centerTile.neighbors.first()
        testGame.addUnit("Warrior", civA, centerTile)
        testGame.addUnit("Warrior", civB, adjacentTile)

        testGame.gameInfo.civilizations.forEach { it.cache.updateOurTiles() }
        assertTrue("Adjacent tile must be visible to A in this setup", adjacentTile.isVisible(civA))

        val viewForA = PlayerViewProjector.projectFor(testGame.gameInfo, civA.civID)

        val bUnitsInAView = unitsOf(viewForA, civB.civID)
        assertEquals(
            "B's unit on a tile A CAN see must be present in A's view",
            1, bUnitsInAView.size
        )
        val adjacentInView = viewForA.tileMap[adjacentTile.position.x, adjacentTile.position.y]
        assertNotNull("Visible enemy unit must remain on the tile in A's view", adjacentInView.militaryUnit)
    }

    @Test
    fun viewersOwnUnitsSurviveProjection() {
        val far = farTile() // far away, but it's A's own unit so must survive regardless
        testGame.addUnit("Warrior", civA, centerTile)
        testGame.addUnit("Scout", civA, far)

        val viewForA = PlayerViewProjector.projectFor(testGame.gameInfo, civA.civID)

        val aUnitsInAView = unitsOf(viewForA, civA.civID)
        assertEquals(
            "A's own units must all survive projection, even on tiles A isn't standing on",
            2, aUnitsInAView.size
        )
    }

    @Test
    fun projectionDoesNotMutateCanonicalGameInfo() {
        val far = farTile()
        testGame.addUnit("Warrior", civA, centerTile)
        val bUnit: MapUnit = testGame.addUnit("Warrior", civB, far)

        val canonicalBUnitsBefore = unitsOf(testGame.gameInfo, civB.civID).size
        assertEquals("Precondition: B has exactly one unit canonically", 1, canonicalBUnitsBefore)

        PlayerViewProjector.projectFor(testGame.gameInfo, civA.civID)

        // The canonical game must be completely unchanged by the projection.
        val canonicalBUnitsAfter = unitsOf(testGame.gameInfo, civB.civID).size
        assertEquals(
            "Projection must NOT remove B's unit from the canonical GameInfo",
            canonicalBUnitsBefore, canonicalBUnitsAfter
        )
        assertEquals(
            "B's unit must still be on its original canonical tile",
            far, bUnit.currentTile
        )
        assertEquals("Canonical far tile must still hold B's unit", bUnit, far.militaryUnit)
    }

    @Test
    fun projectionIsADistinctDeepCopy() {
        testGame.addUnit("Warrior", civA, centerTile)

        val viewForA = PlayerViewProjector.projectFor(testGame.gameInfo, civA.civID)

        assertTrue("Projection must return a different GameInfo instance", viewForA !== testGame.gameInfo)
        assertTrue(
            "Projection must deep-copy the tileMap (not share it)",
            viewForA.tileMap !== testGame.gameInfo.tileMap
        )
    }
}
