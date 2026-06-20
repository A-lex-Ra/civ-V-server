package com.unciv.logic.trade

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BNW Phase 3 (International Trade Routes) — Increments 1, 3, 4: the authoritative [TradeRouteManager].
 *
 * The default test ruleset (Civ V - Gods & Kings) lacks the `Trade Route` stockpile token, so we
 * synthesize it (the `Stockpiled` data marker) and a land/sea trade unit type via [TestGame], exactly the
 * way other manager tests synthesize missing ruleset objects. Capacity = token count, Venice doubling is
 * "for free" (it just grants extra tokens, modeled here by granting N tokens).
 */
@RunWith(GdxTestRunner::class)
class TradeRouteManagerTest {

    private lateinit var testGame: TestGame
    private lateinit var civ: Civilization
    private lateinit var tradeRouteResource: TileResource

    private val manager get() = testGame.gameInfo.tradeRouteManager

    @Before
    fun setUp() {
        // TestGame()'s ctor sets UncivGame.Current, so it MUST come first — otherwise this class fails in
        // isolation (running before any other TestGame-constructing class has initialized Current).
        testGame = TestGame()
        // Founding a city (in the tests below) makes civs meet -> a tutorial task -> settings.save() ->
        // needs UncivGame.files; set after Current exists but before any addCity call.
        UncivGame.Current.files = UncivFiles(Gdx.files)
        testGame.makeHexagonalMap(5)
        testGame.gameInfo.turns = 1
        civ = testGame.addCiv(isPlayer = true)
        // Synthesize the "Trade Route" stockpile token the BNW ruleset would supply.
        tradeRouteResource = testGame.createResource("Stockpiled")
        testGame.ruleset.tileResources.remove(tradeRouteResource.name)
        tradeRouteResource.name = TradeRouteManager.TRADE_ROUTE_RESOURCE
        testGame.ruleset.tileResources[TradeRouteManager.TRADE_ROUTE_RESOURCE] = tradeRouteResource
    }

    /** Grant [n] trade-route tokens to [target] (Venice doubling = just granting more). */
    private fun grantTokens(target: Civilization, n: Int) =
        target.gainStockpiledResource(tradeRouteResource, n)

    /** A land trade unit (Caravan-equivalent) standing on [city]'s center (its route origin). */
    private fun addLandTradeUnit(owner: Civilization, city: City): MapUnit {
        val baseUnit = testGame.createBaseUnit(
            "Civilian", "Costs [1] [Trade Route]", "Can establish trade routes between cities"
        )
        baseUnit.movement = 2
        return testGame.addUnit(baseUnit.name, owner, city.getCenterTile())
    }

    // region capacity

    @Test
    fun `getMaxCapacity equals the granted token count`() {
        assertEquals("No tokens -> no capacity", 0, manager.getMaxCapacity(civ))
        grantTokens(civ, 2)
        assertEquals("Two tokens -> capacity 2 (Venice doubling flows in for free)", 2, manager.getMaxCapacity(civ))
    }

    @Test
    fun `usedCapacity counts only the owning civ's routes`() {
        val other = testGame.addCiv()
        manager.connections.add(connection(civ, "a", "b"))
        manager.connections.add(connection(civ, "c", "d"))
        manager.connections.add(connection(other, "e", "f"))

        assertEquals(2, manager.usedCapacity(civ.civID))
        assertEquals(1, manager.usedCapacity(other.civID))
    }

    private fun connection(owner: Civilization, originId: String, destId: String) =
        TradeRouteConnection().apply {
            ownerCivId = owner.civID
            originCityId = originId
            destinationCityId = destId
        }

    // endregion
    // region computeRoute (BFS land/sea)

    @Test
    fun `computeRoute returns a positive length for two land-connected same-civ cities`() {
        val capital = testGame.addCity(civ, testGame.getTile(0, 0))
        val other = testGame.addCity(civ, testGame.getTile(3, 0))

        val length = manager.computeRoute(capital, other, TradeRouteType.Land)
        assertNotNull("A land route must connect two land-adjacent cities of the same civ", length)
        assertTrue("Route length must be positive", length!! > 0)
    }

    @Test
    fun `computeRoute returns null when no path of that type exists`() {
        val capital = testGame.addCity(civ, testGame.getTile(0, 0))
        val other = testGame.addCity(civ, testGame.getTile(3, 0))
        // All tiles are land -> no SEA route between inland cities.
        val seaLength = manager.computeRoute(capital, other, TradeRouteType.Sea)
        assertNull("A sea route must NOT exist across all-land terrain", seaLength)
    }

    @Test
    fun `computeRoute crosses a foreign civ's territory WITHOUT open borders (Civ V faithful)`() {
        // origin is ours; the destination city (and the ring of tiles the route must step through to reach
        // it) belongs to another MAJOR civ we are at PEACE with but have NO open borders with. In Civ V a
        // land trade route still crosses it — open borders are NOT required, only war blocks a route.
        val origin = testGame.addCity(civ, testGame.getTile(0, 0))
        val foreign = testGame.addCiv()
        val dest = testGame.addCity(foreign, testGame.getTile(3, 0))
        if (!civ.knows(foreign)) civ.diplomacyFunctions.makeCivilizationsMeet(foreign)
        civ.getDiplomacyManager(foreign)!!.makePeace()
        civ.getDiplomacyManager(foreign)!!.hasOpenBorders = false

        val length = manager.computeRoute(origin, dest, TradeRouteType.Land)
        assertNotNull("A land route must cross foreign territory WITHOUT open borders (Civ V)", length)
    }

    @Test
    fun `computeRoute is blocked through enemy territory when at war`() {
        val origin = testGame.addCity(civ, testGame.getTile(0, 0))
        val foreign = testGame.addCiv()
        val dest = testGame.addCity(foreign, testGame.getTile(3, 0))
        if (!civ.knows(foreign)) civ.diplomacyFunctions.makeCivilizationsMeet(foreign)
        civ.getDiplomacyManager(foreign)!!.declareWar()

        val length = manager.computeRoute(origin, dest, TradeRouteType.Land)
        assertNull("War blocks the trade route's path through enemy territory", length)
    }

    // endregion
    // region establish

    @Test
    fun `establish records a route, parks the unit, and does not spend a token`() {
        val origin = testGame.addCity(civ, testGame.getTile(0, 0))
        val dest = testGame.addCity(civ, testGame.getTile(3, 0))
        grantTokens(civ, 1)
        // New ITR model: the trade unit stands ON its own ORIGIN city center.
        val unit = addLandTradeUnit(civ, origin)

        val tokensBefore = manager.getMaxCapacity(civ)
        manager.establish(origin, dest, unit)

        assertEquals("Exactly one route recorded", 1, manager.connections.size)
        val route = manager.connections.first()
        assertEquals(origin.id, route.originCityId)
        assertEquals(dest.id, route.destinationCityId)
        assertEquals(civ.civID, route.ownerCivId)
        assertEquals(TradeRouteType.Land, route.type)
        assertEquals(unit.id, route.unitId)
        assertEquals("Establishing must not spend a Trade Route token", tokensBefore, manager.getMaxCapacity(civ))
        assertEquals("The unit parks (no movement left)", 0f, unit.currentMovement, 0f)
    }

    // endregion
    // region clone & serialize round-trip

    @Test
    fun `clone produces a distinct-but-equal connection list`() {
        manager.connections.add(connection(civ, "origin", "dest").apply { length = 7; establishedTurn = 3; unitId = 9 })

        val cloned = manager.clone()
        assertEquals("Cloned list size matches", 1, cloned.connections.size)
        assertNotSame("Clone must be a distinct list instance", manager.connections, cloned.connections)
        assertNotSame("Each connection must be a distinct instance", manager.connections.first(), cloned.connections.first())

        val original = manager.connections.first()
        val copy = cloned.connections.first()
        assertEquals(original.originCityId, copy.originCityId)
        assertEquals(original.destinationCityId, copy.destinationCityId)
        assertEquals(original.ownerCivId, copy.ownerCivId)
        assertEquals(original.length, copy.length)
        assertEquals(original.establishedTurn, copy.establishedTurn)
        assertEquals(original.unitId, copy.unitId)
    }

    @Test
    fun `serialize round-trip preserves connections and re-attaches gameInfo`() {
        // The ad-hoc setUp civ carries a synthesized "Nation-0" that exists only in TestGame's cloned
        // ruleset; gameInfoFromString -> setTransients re-resolves nations from RulesetCache and would
        // throw MissingNationException on it. Drop it so only RulesetCache-resolvable nations remain.
        testGame.gameInfo.civilizations.remove(civ)
        // Use a REAL major-civ nation so gameInfoFromString -> setTransients re-resolves it from RulesetCache.
        val realNations = testGame.ruleset.nations.values.filter { it.isMajorCiv }.take(1)
        val realCiv = testGame.addCiv(realNations.first(), isPlayer = true)
        testGame.addCity(realCiv, testGame.getTile(0, 0))
        testGame.gameInfo.currentPlayer = realCiv.civID
        testGame.gameInfo.currentPlayerCiv = realCiv

        manager.connections.add(connection(realCiv, "origin-id", "dest-id").apply {
            type = TradeRouteType.Sea; length = 11; establishedTurn = 4; unitId = 2
        })

        val serialized = UncivFiles.gameInfoToString(testGame.gameInfo, forceZip = true, updateChecksum = true)
        val reloaded = UncivFiles.gameInfoFromString(serialized)

        assertEquals("connections must survive the round-trip", 1, reloaded.tradeRouteManager.connections.size)
        val route = reloaded.tradeRouteManager.connections.first()
        assertEquals("origin-id", route.originCityId)
        assertEquals("dest-id", route.destinationCityId)
        assertEquals(TradeRouteType.Sea, route.type)
        assertEquals(11, route.length)
        assertEquals(2, route.unitId)
        // setTransients must have re-attached the gameInfo back-reference (no lateinit crash).
        assertNotNull("gameInfo must be re-attached after load", reloaded.tradeRouteManager.gameInfo)
        assertTrue(reloaded.tradeRouteManager.gameInfo === reloaded)
    }

    // endregion
    // region expiry / renewal / plunder / city-loss

    @Test
    fun `a route past its duration with a live unit and valid path renews`() {
        val capital = testGame.addCity(civ, testGame.getTile(0, 0))
        val dest = testGame.addCity(civ, testGame.getTile(3, 0))
        grantTokens(civ, 1)
        val unit = addLandTradeUnit(civ, capital)
        val route = manager.establish(capital, dest, unit)
        // Push the clock well past the (speed-scaled) duration.
        route.establishedTurn = -1000
        testGame.gameInfo.turns = 1000

        manager.processExpiryAndRenewal(civ)

        assertEquals("A still-valid route must be renewed, not dropped", 1, manager.connections.size)
        assertEquals("Renewal resets establishedTurn to the current turn", 1000, manager.connections.first().establishedTurn)
    }

    @Test
    fun `a route past its duration whose unit died is dropped`() {
        val capital = testGame.addCity(civ, testGame.getTile(0, 0))
        val dest = testGame.addCity(civ, testGame.getTile(3, 0))
        grantTokens(civ, 1)
        val unit = addLandTradeUnit(civ, capital)
        val route = manager.establish(capital, dest, unit)
        route.establishedTurn = -1000
        testGame.gameInfo.turns = 1000
        // Kill the bound unit but keep its id off the route's plunder path by removing AFTER (so the
        // route still references a now-dead unit id when expiry runs).
        unit.removeFromTile()
        civ.units.removeUnit(unit)

        manager.processExpiryAndRenewal(civ)

        assertTrue("A route whose unit is gone must be dropped at expiry", manager.connections.isEmpty())
    }

    @Test
    fun `destroying the parked trade unit plunders its route and notifies the owner`() {
        val capital = testGame.addCity(civ, testGame.getTile(0, 0))
        val dest = testGame.addCity(civ, testGame.getTile(3, 0))
        grantTokens(civ, 1)
        val unit = addLandTradeUnit(civ, capital)
        manager.establish(capital, dest, unit)
        assertEquals(1, manager.connections.size)

        unit.destroy()

        assertTrue("Destroying the bound unit must drop its route", manager.connections.isEmpty())
    }

    @Test
    fun `removeRoutesTouchingCity drops routes touching the lost city`() {
        val capital = testGame.addCity(civ, testGame.getTile(0, 0))
        val dest = testGame.addCity(civ, testGame.getTile(3, 0))
        grantTokens(civ, 1)
        val unit = addLandTradeUnit(civ, capital)
        manager.establish(capital, dest, unit)
        assertEquals(1, manager.connections.size)

        manager.removeRoutesTouchingCity(dest.id)
        assertTrue("Losing the destination city must drop the route", manager.connections.isEmpty())
    }

    @Test
    fun `removeRoutesForUnit is benign for a non-trade unit id`() {
        manager.connections.add(connection(civ, "a", "b").apply { unitId = 5 })
        val dropped = manager.removeRoutesForUnit(999)
        assertTrue("An unrelated unit id drops nothing", dropped.isEmpty())
        assertEquals("The route remains", 1, manager.connections.size)
        assertFalse(manager.connections.isEmpty())
    }

    // endregion
}
