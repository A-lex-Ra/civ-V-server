package com.unciv.logic.map

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BNW Phase 3 — International Trade Routes: the third (trade) unit slot on a [Tile]. A trade unit may share a
 * tile with any non-trade units (civilian / military / air), but two trade units may not share a tile.
 */
@RunWith(GdxTestRunner::class)
class TradeUnitStackingTest {

    private lateinit var testGame: TestGame
    private lateinit var civ: Civilization

    @Before
    fun setUp() {
        UncivGame.Current.files = UncivFiles(Gdx.files)
        testGame = TestGame()
        testGame.makeHexagonalMap(5)
        testGame.gameInfo.turns = 1
        civ = testGame.addCiv(isPlayer = true)
    }

    /** A land trade unit (Caravan-equivalent) defined by the `Trade Route` cost data marker. */
    private fun addTradeUnit(tile: Tile): MapUnit {
        val baseUnit = testGame.createBaseUnit(
            "Civilian", "Costs [1] [Trade Route]", "Can establish trade routes between cities"
        )
        baseUnit.movement = 2
        return testGame.addUnit(baseUnit.name, civ, tile)
    }

    @Test
    fun `a trade unit coexists with a civilian and a military unit on one tile`() {
        val city = testGame.addCity(civ, testGame.getTile(0, 0))
        val centerTile = city.getCenterTile()

        val warrior = testGame.addUnit("Warrior", civ, centerTile)   // military slot
        val worker = testGame.addUnit("Worker", civ, centerTile)     // civilian slot
        val caravan = addTradeUnit(centerTile)                       // trade slot

        assertSame("Military slot holds the Warrior", warrior, centerTile.militaryUnit)
        assertSame("Civilian slot holds the Worker", worker, centerTile.civilianUnit)
        assertSame("Trade slot holds the trade unit", caravan, centerTile.tradeUnit)

        // All three are reported by getUnits() (so setTransients wires them all).
        val unitsOnTile = centerTile.getUnits().toList()
        assertEquals("All three units coexist on the tile", 3, unitsOnTile.size)
        assertNotNull(centerTile.militaryUnit)
        assertNotNull(centerTile.civilianUnit)
        assertNotNull(centerTile.tradeUnit)
    }

    @Test
    fun `a second trade unit cannot be placed on a tile already holding one`() {
        val city = testGame.addCity(civ, testGame.getTile(0, 0))
        val centerTile = city.getCenterTile()
        addTradeUnit(centerTile) // occupies the trade slot

        // A second trade unit, parked elsewhere, must NOT be able to move onto the occupied trade slot.
        val otherTile = testGame.getTile(2, 0)
        val secondCaravan = addTradeUnit(otherTile)

        assertFalse("A second trade unit may not enter a tile whose trade slot is taken",
            secondCaravan.movement.canMoveTo(centerTile))
    }
}
