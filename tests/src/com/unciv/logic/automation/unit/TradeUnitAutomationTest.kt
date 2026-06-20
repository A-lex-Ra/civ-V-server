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
    fun `a trade unit on its own city center establishes a route toward a reachable foreign city`() {
        val aiCity: City = testGame.addCity(aiCiv, testGame.getTile(0, 0))   // route origin
        val foreignCity: City = testGame.addCity(foreignCiv, testGame.getTile(3, 0))
        aiCiv.gainStockpiledResource(tradeRouteResource, 1)
        // New ITR model: the unit must stand ON its own city center; the route originates THERE.
        val unit = addLandTradeUnit(aiCiv, aiCity.getCenterTile())

        TradeUnitAutomation.automateTradeUnit(unit)

        assertEquals("The AI must have established one route", 1, manager.connections.size)
        assertEquals("The route must originate at the unit's own city",
            aiCity.id, manager.connections.first().originCityId)
        assertEquals("The route must target the known foreign city",
            foreignCity.id, manager.connections.first().destinationCityId)
        assertEquals(aiCiv.civID, manager.connections.first().ownerCivId)
    }

    @Test
    fun `with capacity full the AI establishes no new route`() {
        val aiCity: City = testGame.addCity(aiCiv, testGame.getTile(0, 0))
        testGame.addCity(foreignCiv, testGame.getTile(3, 0))
        // No tokens granted -> capacity 0. The CivilianUnitAutomation guard would not even delegate, but
        // calling automateTradeUnit directly must also be a no-op because capacity gates the establish.
        val unit = addLandTradeUnit(aiCiv, aiCity.getCenterTile())

        TradeUnitAutomation.automateTradeUnit(unit)

        assertTrue("No route may be established with zero capacity", manager.connections.isEmpty())
    }
}
