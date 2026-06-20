package com.unciv.logic.trade

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.files.UncivFiles
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BNW Phase 3 — Increment 3: [TradeRouteYields.computeYields] + [TradeRouteManager.applyYieldsForOwner].
 *
 * Verifies the documented formulas: domestic routes yield owner gold only; international routes pay double
 * owner gold, science (when the destination is more advanced), and destination-owner gold; and a per-turn
 * application banks both the owner's and the destination owner's gold.
 */
@RunWith(GdxTestRunner::class)
class TradeRouteYieldsTest {

    private lateinit var testGame: TestGame
    private lateinit var owner: Civilization
    private lateinit var foreign: Civilization
    private lateinit var tradeRouteResource: TileResource

    private val manager get() = testGame.gameInfo.tradeRouteManager

    @Before
    fun setUp() {
        UncivGame.Current.files = UncivFiles(Gdx.files)
        testGame = TestGame()
        testGame.makeHexagonalMap(5)
        testGame.gameInfo.turns = 1
        owner = testGame.addCiv(isPlayer = true)
        foreign = testGame.addCiv(isPlayer = true)
        tradeRouteResource = testGame.createResource("Stockpiled")
        testGame.ruleset.tileResources.remove(tradeRouteResource.name)
        tradeRouteResource.name = TradeRouteManager.TRADE_ROUTE_RESOURCE
        testGame.ruleset.tileResources[TradeRouteManager.TRADE_ROUTE_RESOURCE] = tradeRouteResource
    }

    private fun connection(o: City, d: City) = TradeRouteConnection().apply {
        originCityId = o.id
        destinationCityId = d.id
        ownerCivId = owner.civID
        type = TradeRouteType.Land
        length = 5
        establishedTurn = 1
    }

    @Test
    fun `a domestic route yields owner gold but no science or destination-owner gold`() {
        val capital = testGame.addCity(owner, testGame.getTile(0, 0), initialPopulation = 5)
        val ownCity = testGame.addCity(owner, testGame.getTile(3, 0), initialPopulation = 5)
        val result = TradeRouteYields.computeYields(connection(capital, ownCity), testGame.gameInfo)

        assertTrue("Domestic owner gold must be positive", result.ownerGold > 0)
        assertEquals("Domestic route grants no science", 0, result.ownerScience)
        assertEquals("Domestic route grants no destination-owner gold", 0, result.destOwnerGold)
    }

    @Test
    fun `an international route pays more owner gold than the same-population domestic route`() {
        val capital = testGame.addCity(owner, testGame.getTile(0, 0), initialPopulation = 5)
        val ownCity = testGame.addCity(owner, testGame.getTile(3, 0), initialPopulation = 5)
        val foreignCity = testGame.addCity(foreign, testGame.getTile(-3, 0), initialPopulation = 5)

        val domestic = TradeRouteYields.computeYields(connection(capital, ownCity), testGame.gameInfo)
        val international = TradeRouteYields.computeYields(connection(capital, foreignCity), testGame.gameInfo)

        assertTrue("International owner gold must exceed the equivalent domestic route's gold " +
            "(domestic=${domestic.ownerGold}, international=${international.ownerGold})",
            international.ownerGold > domestic.ownerGold)
        assertTrue("International route must grant destination-owner gold", international.destOwnerGold > 0)
    }

    @Test
    fun `an international route to a more advanced civ grants science`() {
        val capital = testGame.addCity(owner, testGame.getTile(0, 0), initialPopulation = 5)
        val foreignCity = testGame.addCity(foreign, testGame.getTile(-3, 0), initialPopulation = 5)
        // Make the foreign civ more advanced so techDiff > 0.
        repeat(6) {
            val tech = testGame.ruleset.technologies.values
                .firstOrNull { foreign.tech.canBeResearched(it.name) && !foreign.tech.isResearched(it.name) }
                ?: return@repeat
            foreign.tech.addTechnology(tech.name)
        }
        assertTrue("Precondition: foreign must be more advanced",
            foreign.tech.getNumberOfTechsResearched() > owner.tech.getNumberOfTechsResearched())

        val result = TradeRouteYields.computeYields(connection(capital, foreignCity), testGame.gameInfo)
        assertTrue("Science must be positive toward a more advanced destination (was ${result.ownerScience})",
            result.ownerScience > 0)
    }

    @Test
    fun `applyYieldsForOwner banks gold for both the owner and the destination owner`() {
        val capital = testGame.addCity(owner, testGame.getTile(0, 0), initialPopulation = 5)
        val foreignCity = testGame.addCity(foreign, testGame.getTile(-3, 0), initialPopulation = 5)
        manager.connections.add(connection(capital, foreignCity))

        val ownerGoldBefore = owner.gold
        val foreignGoldBefore = foreign.gold

        manager.applyYieldsForOwner(owner)

        assertTrue("Owner gold must rise after applying yields", owner.gold > ownerGoldBefore)
        assertTrue("Destination-owner gold must rise on an international route", foreign.gold > foreignGoldBefore)
    }

    @Test
    fun `religion pressure rises in the destination after applying yields`() {
        val capital = testGame.addCity(owner, testGame.getTile(0, 0), initialPopulation = 8)
        val foreignCity = testGame.addCity(foreign, testGame.getTile(-3, 0), initialPopulation = 5)

        // Give the origin a majority religion so pressure spreads to the destination.
        val religion = testGame.addReligion(owner)
        capital.religion.addPressure(religion.name, 1000)
        assertEquals("Precondition: origin majority religion", religion.name, capital.religion.getMajorityReligionName())

        val pressureBefore = foreignCity.religion.getPressures()[religion.name]
        manager.connections.add(connection(capital, foreignCity))
        manager.applyYieldsForOwner(owner)
        val pressureAfter = foreignCity.religion.getPressures()[religion.name]

        assertTrue("Origin religion pressure must rise in the destination city (before=$pressureBefore, after=$pressureAfter)",
            pressureAfter > pressureBefore)
    }
}
