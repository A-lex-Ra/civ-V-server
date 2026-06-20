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
 * Verifies the documented formulas: domestic routes carry era-scaled Food OR Production to the destination
 * city (no gold/science); international routes pay double owner gold, destination-owner gold, and catch-up
 * science; and a per-turn application banks the monetary yields and delivers internal Food/Production.
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

    private fun connection(o: City, d: City, yield: TradeRouteYield = TradeRouteYield.None) =
        TradeRouteConnection().apply {
            originCityId = o.id
            destinationCityId = d.id
            ownerCivId = owner.civID
            type = TradeRouteType.Land
            length = 5
            establishedTurn = 1
            internalYield = yield
        }

    @Test
    fun `a domestic route carries food or production to the destination and no gold or science`() {
        val capital = testGame.addCity(owner, testGame.getTile(0, 0), initialPopulation = 5)
        val ownCity = testGame.addCity(owner, testGame.getTile(3, 0), initialPopulation = 5)

        val foodRoute = TradeRouteYields.computeYields(connection(capital, ownCity, TradeRouteYield.Food), testGame.gameInfo)
        assertTrue("A domestic Food route must deliver food to the destination", foodRoute.destFood > 0)
        assertEquals("A domestic Food route delivers no production", 0, foodRoute.destProduction)
        assertEquals("Domestic route grants no owner gold", 0, foodRoute.ownerGold)
        assertEquals("Domestic route grants no science", 0, foodRoute.ownerScience)
        assertEquals("Domestic route grants no destination-owner gold", 0, foodRoute.destOwnerGold)

        val prodRoute = TradeRouteYields.computeYields(connection(capital, ownCity, TradeRouteYield.Production), testGame.gameInfo)
        assertTrue("A domestic Production route must deliver production", prodRoute.destProduction > 0)
        assertEquals("A domestic Production route delivers no food", 0, prodRoute.destFood)
    }

    @Test
    fun `a sea domestic route carries double a land one`() {
        val capital = testGame.addCity(owner, testGame.getTile(0, 0), initialPopulation = 5)
        val ownCity = testGame.addCity(owner, testGame.getTile(3, 0), initialPopulation = 5)
        val land = TradeRouteYields.computeYields(
            connection(capital, ownCity, TradeRouteYield.Production), testGame.gameInfo)
        val sea = TradeRouteYields.computeYields(
            connection(capital, ownCity, TradeRouteYield.Production).apply { type = TradeRouteType.Sea }, testGame.gameInfo)
        assertEquals("Sea internal routes carry double the land amount",
            land.destProduction * 2, sea.destProduction)
    }

    @Test
    fun `an international route pays owner and destination-owner gold while a domestic one pays none`() {
        val capital = testGame.addCity(owner, testGame.getTile(0, 0), initialPopulation = 5)
        val ownCity = testGame.addCity(owner, testGame.getTile(3, 0), initialPopulation = 5)
        val foreignCity = testGame.addCity(foreign, testGame.getTile(-3, 0), initialPopulation = 5)

        val domestic = TradeRouteYields.computeYields(connection(capital, ownCity), testGame.gameInfo)
        val international = TradeRouteYields.computeYields(connection(capital, foreignCity), testGame.gameInfo)

        assertEquals("A domestic route pays no owner gold", 0, domestic.ownerGold)
        assertTrue("International owner gold must be positive (was ${international.ownerGold})",
            international.ownerGold > 0)
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
    fun `applyYieldsForOwner delivers internal food and production to the destination city`() {
        val capital = testGame.addCity(owner, testGame.getTile(0, 0), initialPopulation = 5)
        val foodCity = testGame.addCity(owner, testGame.getTile(3, 0), initialPopulation = 3)
        val prodCity = testGame.addCity(owner, testGame.getTile(-3, 0), initialPopulation = 3)
        manager.connections.add(connection(capital, foodCity, TradeRouteYield.Food))
        manager.connections.add(connection(capital, prodCity, TradeRouteYield.Production))

        val foodBefore = foodCity.population.foodStored
        val prodOverflowBefore = prodCity.cityConstructions.productionOverflow

        manager.applyYieldsForOwner(owner)

        assertTrue("An internal Food route must raise the destination's stored food " +
            "(before=$foodBefore, after=${foodCity.population.foodStored})",
            foodCity.population.foodStored > foodBefore)
        assertTrue("An internal Production route must add production to the destination " +
            "(before=$prodOverflowBefore, after=${prodCity.cityConstructions.productionOverflow})",
            prodCity.cityConstructions.productionOverflow > prodOverflowBefore)
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
