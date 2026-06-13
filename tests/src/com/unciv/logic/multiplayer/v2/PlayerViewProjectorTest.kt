package com.unciv.logic.multiplayer.v2

import com.unciv.logic.GameInfo
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Notification
import com.unciv.logic.civilization.NotificationAction
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.RoadStatus
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
 * send to a given player —
 *  - enemy units the viewer cannot see are absent, the viewer's own units survive;
 *  - other civs' interior secrets (gold, stockpiles, tech, policies, espionage, notifications,
 *    diplomacy internals) are scrubbed while the viewer's own civ is left intact;
 *  - a seen enemy city stays present but its building/construction/citizen/stockpile detail is gone;
 *  - an unexplored tile's resource/improvement/road/feature is hidden;
 *  - and the canonical game is left untouched, the projection being a distinct deep copy.
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

    /** The projected copy of [civ] (by id) in [view]. */
    private fun projectedCiv(view: GameInfo, civId: String): Civilization =
        view.getCivilizationOrNull(civId)!!

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

        // Give B some interior secrets so we can also prove the canonical civ is untouched.
        civB.addGold(500)
        civB.tech.techsResearched.add("Pottery")
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
        // Canonical secrets must NOT be scrubbed by the projection.
        assertEquals("Projection must not touch canonical B's gold", 500, civB.gold)
        assertTrue(
            "Projection must not touch canonical B's researched techs",
            civB.tech.techsResearched.contains("Pottery")
        )
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
        assertTrue(
            "Projection must deep-copy each civ (not share the canonical instance)",
            projectedCiv(viewForA, civB.civID) !== civB
        )
    }

    // region Priority 1 — other civs' interior secrets

    @Test
    fun otherCivInteriorSecretsAreScrubbedFromView() {
        testGame.addUnit("Warrior", civA, centerTile)

        // Seed B with a spread of interior secrets a maphacking A must not be able to read.
        civB.addGold(1234)
        civB.resourceStockpiles.add("Iron", 7)
        civB.tech.techsResearched.add("Pottery")
        civB.tech.techsInProgress["Writing"] = 30
        civB.tech.freeTechs = 2
        civB.policies.storedCulture = 99
        civB.policies.freePolicies = 1
        civB.policies.getAdoptedPolicies().add("Tradition")
        civB.notifications.add(
            Notification("secret plans", emptyArray(), emptyList<NotificationAction>(), NotificationCategory.General)
        )

        val viewForA = PlayerViewProjector.projectFor(testGame.gameInfo, civA.civID)
        val bInView = projectedCiv(viewForA, civB.civID)

        assertEquals("B's gold must be scrubbed", 0, bInView.gold)
        assertTrue("B's stockpiled resources must be scrubbed", bInView.resourceStockpiles.isEmpty())
        assertTrue("B's researched techs must be scrubbed", bInView.tech.techsResearched.isEmpty())
        assertTrue("B's tech-in-progress must be scrubbed", bInView.tech.techsInProgress.isEmpty())
        assertEquals("B's free techs must be scrubbed", 0, bInView.tech.freeTechs)
        assertEquals("B's stored culture must be scrubbed", 0, bInView.policies.storedCulture)
        assertEquals("B's free policies must be scrubbed", 0, bInView.policies.freePolicies)
        assertTrue("B's adopted policies must be scrubbed", bInView.policies.getAdoptedPolicies().isEmpty())
        assertTrue("B's notifications must be scrubbed", bInView.notifications.isEmpty())
    }

    @Test
    fun viewersOwnSecretsAreKeptInView() {
        testGame.addUnit("Warrior", civA, centerTile)

        // A is the viewer: A's own interior must be fully preserved.
        civA.addGold(777)
        civA.tech.techsResearched.add("Pottery")
        civA.policies.getAdoptedPolicies().add("Tradition")

        val viewForA = PlayerViewProjector.projectFor(testGame.gameInfo, civA.civID)
        val aInView = projectedCiv(viewForA, civA.civID)

        assertEquals("Viewer A's own gold must be preserved", 777, aInView.gold)
        assertTrue(
            "Viewer A's own researched techs must be preserved",
            aInView.tech.techsResearched.contains("Pottery")
        )
        assertTrue(
            "Viewer A's own adopted policies must be preserved",
            aInView.policies.getAdoptedPolicies().contains("Tradition")
        )
    }

    // endregion

    // region Priority 2 — seen enemy city interior

    @Test
    fun seenEnemyCityStaysButInteriorIsStripped() {
        testGame.addUnit("Warrior", civA, centerTile)

        // B founds a city near the center; A explores its center tile so the city itself stays.
        val cityTile = centerTile.neighbors.first()
        val cityB: City = testGame.addCity(civB, cityTile)
        cityTile.setExplored(civA, true)

        // Give the city interior detail that A must not be able to read.
        cityB.cityConstructions.constructionQueue.clear()
        cityB.cityConstructions.constructionQueue.add("Monument")
        cityB.cityConstructions.inProgressConstructions["Monument"] = 5
        cityB.population.foodStored = 42
        cityB.workedTiles.add(cityTile.position)
        cityB.resourceStockpiles.add("Iron", 3)
        // A city always has at least its Palace among builtBuildings after founding.
        assertTrue(
            "Precondition: founded city should have at least one built building",
            cityB.cityConstructions.builtBuildings.isNotEmpty()
        )

        val viewForA = PlayerViewProjector.projectFor(testGame.gameInfo, civA.civID)
        val bInView = projectedCiv(viewForA, civB.civID)

        assertEquals("Seen enemy city must remain present in A's view", 1, bInView.cities.size)
        val cityInView = bInView.cities.first()
        // Existence / identity kept.
        assertEquals("City position must be kept", cityB.location, cityInView.location)
        assertEquals("City name must be kept", cityB.name, cityInView.name)
        // Interior stripped.
        assertTrue(
            "Built-buildings list must be stripped",
            cityInView.cityConstructions.builtBuildings.isEmpty()
        )
        assertTrue(
            "Construction queue must be stripped",
            cityInView.cityConstructions.constructionQueue.isEmpty()
        )
        assertTrue(
            "In-progress constructions must be stripped",
            cityInView.cityConstructions.inProgressConstructions.isEmpty()
        )
        assertEquals("Food stockpile must be stripped", 0, cityInView.population.foodStored)
        assertTrue("Worked tiles must be stripped", cityInView.workedTiles.isEmpty())
        assertTrue("City resource stockpiles must be stripped", cityInView.resourceStockpiles.isEmpty())
    }

    // endregion

    // region Priority 3 — unexplored tile contents

    @Test
    fun unexploredTileContentsAreHidden() {
        testGame.addUnit("Warrior", civA, centerTile)

        // A far, never-explored tile carrying contents A must not be able to read.
        val far = farTile()
        far.setTileResource("Iron", updateCache = false)
        far.resourceAmount = 4
        far.improvement = "Farm"
        far.roadStatus = RoadStatus.Road

        testGame.gameInfo.civilizations.forEach { it.cache.updateOurTiles() }
        assertFalse("Far tile must be unexplored by A", far.isExplored(civA))

        val viewForA = PlayerViewProjector.projectFor(testGame.gameInfo, civA.civID)
        val farInView = viewForA.tileMap[far.position.x, far.position.y]

        assertNull("Resource on an unexplored tile must be hidden", farInView.resource)
        assertEquals("Resource amount on an unexplored tile must be hidden", 0, farInView.resourceAmount)
        assertNull("Improvement on an unexplored tile must be hidden", farInView.improvement)
        assertEquals("Road on an unexplored tile must be hidden", RoadStatus.None, farInView.roadStatus)
        // The tile itself must still exist so the cloned map stays structurally valid.
        assertNotNull("The unexplored tile itself must still be present in the view", farInView)
    }

    @Test
    fun visibleTileContentsAreKept() {
        testGame.addUnit("Warrior", civA, centerTile)

        // Contents on the center tile, which A can currently see, must NOT be redacted.
        centerTile.setTileResource("Iron", updateCache = false)
        centerTile.improvement = "Farm"

        testGame.gameInfo.civilizations.forEach { it.cache.updateOurTiles() }
        assertTrue("Center tile must be visible to A", centerTile.isVisible(civA))

        val viewForA = PlayerViewProjector.projectFor(testGame.gameInfo, civA.civID)
        val centerInView = viewForA.tileMap[centerTile.position.x, centerTile.position.y]

        assertEquals("Visible tile's resource must be kept", "Iron", centerInView.resource)
        assertEquals("Visible tile's improvement must be kept", "Farm", centerInView.improvement)
    }

    // endregion
}
