package com.unciv.logic.automation.unit

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.trade.TradeRouteManager
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BNW Phase 3 — Increment 6: [TradeUnitAutomation]. The authority's AI uses an idle trade unit to
 * establish a high-value route; with capacity full it establishes nothing.
 */
@RunWith(GdxTestRunner::class)
class TradeUnitAutomationTest {

    private lateinit var testGame: TestGame
    private lateinit var aiCiv: Civilization
    private lateinit var foreignCiv: Civilization
    private lateinit var tradeRouteResource: TileResource

    private val manager get() = testGame.gameInfo.tradeRouteManager

    @Before
    fun setUp() {
        testGame = TestGame()
        UncivGame.Current.files = UncivFiles(Gdx.files)
        testGame.makeHexagonalMap(5)
        testGame.gameInfo.turns = 1
        // isPlayer = false -> an AI civ (the automation target).
        aiCiv = testGame.addCiv()
        foreignCiv = testGame.addCiv()
        aiCiv.diplomacyFunctions.makeCivilizationsMeet(foreignCiv)
        // Open borders so the land route may cross the foreign civ's territory to reach its city.
        aiCiv.getDiplomacyManager(foreignCiv)!!.hasOpenBorders = true

        tradeRouteResource = testGame.createResource("Stockpiled")
        testGame.ruleset.tileResources.remove(tradeRouteResource.name)
        tradeRouteResource.name = TradeRouteManager.TRADE_ROUTE_RESOURCE
        testGame.ruleset.tileResources[TradeRouteManager.TRADE_ROUTE_RESOURCE] = tradeRouteResource
    }

    private fun addLandTradeUnit(owner: Civilization, tile: com.unciv.logic.map.tile.Tile): MapUnit {
        val baseUnit = testGame.createBaseUnit(
            "Civilian", "Costs [1] [Trade Route]", "Can establish trade routes between cities"
        )
        baseUnit.movement = 2
        return testGame.addUnit(baseUnit.name, owner, tile)
    }

    @Test
    fun `an idle trade unit establishes a route toward a reachable known foreign city`() {
        testGame.addCity(aiCiv, testGame.getTile(0, 0))           // AI capital (origin)
        val foreignCity: City = testGame.addCity(foreignCiv, testGame.getTile(3, 0))
        aiCiv.gainStockpiledResource(tradeRouteResource, 1)
        // The engine forbids entering a FOREIGN city center, so park the unit on a land tile ADJACENT to
        // it (open borders are set in setUp) — distance 1 → the automation establishes immediately.
        val dockTile = foreignCity.getCenterTile().neighbors.first { it.isLand && !it.isCityCenter() }
        val unit = addLandTradeUnit(aiCiv, dockTile)

        TradeUnitAutomation.automateTradeUnit(unit)

        assertEquals("The AI must have established one route", 1, manager.connections.size)
        assertEquals("The route must target the known foreign city",
            foreignCity.id, manager.connections.first().destinationCityId)
        assertEquals(aiCiv.civID, manager.connections.first().ownerCivId)
    }

    @Test
    fun `with capacity full the AI establishes no new route`() {
        testGame.addCity(aiCiv, testGame.getTile(0, 0))
        val foreignCity: City = testGame.addCity(foreignCiv, testGame.getTile(3, 0))
        // No tokens granted -> capacity 0. The CivilianUnitAutomation guard would not even delegate, but
        // calling automateTradeUnit directly must also be a no-op because capacity gates the establish.
        // Park adjacent to the foreign city center (its center is unenterable for a foreign unit).
        val dockTile = foreignCity.getCenterTile().neighbors.first { it.isLand && !it.isCityCenter() }
        val unit = addLandTradeUnit(aiCiv, dockTile)

        TradeUnitAutomation.automateTradeUnit(unit)

        assertTrue("No route may be established with zero capacity", manager.connections.isEmpty())
    }
}
