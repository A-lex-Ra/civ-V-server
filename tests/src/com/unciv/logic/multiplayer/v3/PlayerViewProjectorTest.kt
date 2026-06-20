package com.unciv.logic.multiplayer.v3

import com.unciv.logic.GameInfo
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Notification
import com.unciv.logic.civilization.NotificationAction
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.multiplayer.v3.visibility.PlayerViewProjector
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
 * Phase 3a deliverable check (see docs/multiplayer-v3.md §2 goal #3, §10 Phase 3): the
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

    @Test
    fun rivalPublicOpinionIsScrubbedButViewersIsKept() {
        // BNW Phase 2a (D2): public opinion is authority-only state — a rival civ's ideological
        // pressure meter / dissident unhappiness must be scrubbed from the wire view (it would
        // otherwise leak the rival's hidden ideology), while the viewer's own opinion stays intact.
        testGame.addUnit("Warrior", civA, centerTile)

        // B (a rival of the viewer A) has public-opinion state, including Increment-2 switch state.
        civB.publicOpinion.ideologyPressureByBranch["Order"] = 3.5f
        civB.publicOpinion.dissidentUnhappiness = -4
        civB.publicOpinion.anarchyTurnsRemaining = 4
        civB.publicOpinion.forcedSwitchPending = true
        // A (the viewer) also has public-opinion state, which must survive.
        civA.publicOpinion.ideologyPressureByBranch["Freedom"] = 2f
        civA.publicOpinion.dissidentUnhappiness = -2
        civA.publicOpinion.anarchyTurnsRemaining = 3
        civA.publicOpinion.forcedSwitchPending = true

        val viewForA = PlayerViewProjector.projectFor(testGame.gameInfo, civA.civID)

        val bInView = projectedCiv(viewForA, civB.civID)
        assertTrue(
            "Rival B's ideological-pressure meter must be scrubbed",
            bInView.publicOpinion.ideologyPressureByBranch.isEmpty()
        )
        assertEquals(
            "Rival B's dissident unhappiness must be zeroed",
            0, bInView.publicOpinion.dissidentUnhappiness
        )
        assertEquals(
            "Rival B's anarchy countdown must be zeroed",
            0, bInView.publicOpinion.anarchyTurnsRemaining
        )
        assertFalse(
            "Rival B's forced-switch flag must be cleared",
            bInView.publicOpinion.forcedSwitchPending
        )

        val aInView = projectedCiv(viewForA, civA.civID)
        assertEquals(
            "Viewer A's own pressure meter must be preserved",
            2f, aInView.publicOpinion.ideologyPressureByBranch["Freedom"]!!, 0f
        )
        assertEquals(
            "Viewer A's own dissident unhappiness must be preserved",
            -2, aInView.publicOpinion.dissidentUnhappiness
        )
        assertEquals(
            "Viewer A's own anarchy countdown must be preserved",
            3, aInView.publicOpinion.anarchyTurnsRemaining
        )
        assertTrue(
            "Viewer A's own forced-switch flag must be preserved",
            aInView.publicOpinion.forcedSwitchPending
        )
    }

    @Test
    fun rivalTourismInfluenceIsScrubbedButViewersIsKept() {
        // BNW Phase 2b (D2): tourism influence is authority-only state — a rival civ's accumulated
        // influence over OTHER civs must be scrubbed from the wire view (it's computed from
        // culture/buildings the client can't see), while the viewer's own influence stays intact and
        // the publicly-observable culture-defense (totalCultureForContests) is left alone.
        testGame.addUnit("Warrior", civA, centerTile)

        // B (a rival of the viewer A) has tourism influence over some third civ, plus a culture score.
        civB.tourism.accumulatedInfluence["SomeCiv"] = 50
        civB.totalCultureForContests = 321
        // A (the viewer) also has tourism influence, which must survive.
        civA.tourism.accumulatedInfluence["SomeCiv"] = 40

        val viewForA = PlayerViewProjector.projectFor(testGame.gameInfo, civA.civID)

        val bInView = projectedCiv(viewForA, civB.civID)
        assertTrue(
            "Rival B's accumulated tourism influence must be scrubbed",
            bInView.tourism.accumulatedInfluence.isEmpty()
        )
        assertEquals(
            "Rival B's publicly-observable culture-defense must be preserved",
            321, bInView.totalCultureForContests
        )

        val aInView = projectedCiv(viewForA, civA.civID)
        assertEquals(
            "Viewer A's own accumulated tourism influence must be preserved",
            40, aInView.tourism.accumulatedInfluence["SomeCiv"]
        )
    }

    @Test
    fun cityStateInfluenceTowardViewerIsKeptButTowardThirdPartiesIsScrubbed() {
        // A city-state's influence with the VIEWER is the viewer's own diplomatic standing (it drives
        // the Ally/Friend relationship + the city-state's bonuses) — it must survive projection. Only
        // the city-state's influence with THIRD parties is a secret. Regression for the bug where every
        // snapshot reset the player's city-state influence to 0, so a gift / tribute "reverted" each round.
        testGame.addUnit("Warrior", civA, centerTile)
        val cityState = testGame.addCiv(cityStateType = "Cultured")
        // The city-state has met both majors, so it holds a DiplomacyManager toward each.
        civA.diplomacyFunctions.makeCivilizationsMeet(cityState)
        civB.diplomacyFunctions.makeCivilizationsMeet(cityState)
        cityState.getDiplomacyManager(civA)!!.setInfluenceWithoutSideEffects(60f)
        cityState.getDiplomacyManager(civB)!!.setInfluenceWithoutSideEffects(80f)

        val viewForA = PlayerViewProjector.projectFor(testGame.gameInfo, civA.civID)
        val csInView = projectedCiv(viewForA, cityState.civID)
        // Read the raw influence field by reflection: projectFor deliberately skips the full
        // GameInfo.setTransients(), so the public getInfluence() (which walks isAtWarWith -> nation
        // transients) would NPE here, and the field is internal to :core.
        fun influenceTowardId(towardId: String): Float {
            val mgr = csInView.getDiplomacyManager(towardId)!!
            return mgr.javaClass.getDeclaredField("influence").apply { isAccessible = true }.getFloat(mgr)
        }

        assertEquals(
            "The city-state's influence toward the VIEWER must be preserved (player's own standing)",
            60f, influenceTowardId(civA.civID), 0f
        )
        assertEquals(
            "The city-state's influence toward a THIRD party must be scrubbed",
            0f, influenceTowardId(civB.civID), 0f
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

    @Test
    fun unseenRivalCityRevealsOnlyTileOwnerOnExploredBorderTiles() {
        // B founds a city on a tile A never explores -> the CITY itself must stay hidden (removed from
        // A's view). But A HAS explored one of that city's border tiles, so A is entitled to know whose
        // tile it is (Civ V faithful: "you may not have seen the city, but you know the civ name and
        // colour when you see their tile"). The projector stamps Tile.viewOnlyOwnerCivName on that tile
        // (and its explored fringe) so the client can still draw B's cultural border — while deep,
        // never-approached interior tiles stay unowned so the hidden city's full shape does not leak.
        testGame.addUnit("Warrior", civA, centerTile)
        val cityB: City = testGame.addCity(civB, testGame.tileMap[5, 0])

        testGame.gameInfo.civilizations.forEach { it.cache.updateOurTiles() }
        val ownedNonCenter = cityB.getTiles().filter { it != cityB.getCenterTile() }.toList()
        val exploredBorder = ownedNonCenter.first()
        exploredBorder.setExplored(civA, true)
        // An owned tile A has neither explored nor borders an explored tile of -> must NOT leak an owner.
        val deepTile = ownedNonCenter
            .filter { it != exploredBorder && exploredBorder !in it.neighbors }
            .maxByOrNull { it.aerialDistanceTo(exploredBorder) }!!

        testGame.gameInfo.civilizations.forEach { it.cache.updateOurTiles() }
        assertFalse("Precondition: B's city center must be unexplored by A", cityB.getCenterTile().isExplored(civA))
        assertTrue("Precondition: A must have explored the chosen border tile", exploredBorder.isExplored(civA))
        assertFalse("Precondition: the deep interior tile must be unexplored by A", deepTile.isExplored(civA))

        val viewForA = PlayerViewProjector.projectFor(testGame.gameInfo, civA.civID)

        assertTrue(
            "An unseen rival city must be removed from A's view (the city itself stays hidden)",
            projectedCiv(viewForA, civB.civID).cities.isEmpty()
        )
        assertEquals(
            "An explored border tile of a hidden city must reveal ONLY its owner id for border rendering",
            civB.civID,
            viewForA.tileMap[exploredBorder.position.x, exploredBorder.position.y].viewOnlyOwnerCivName
        )
        assertEquals(
            "A never-approached interior tile of a hidden city must stay unowned (no territory-shape leak)",
            "",
            viewForA.tileMap[deepTile.position.x, deepTile.position.y].viewOnlyOwnerCivName
        )
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

    // region Great Work placements (BNW Phase 2c — Increment 3, D5)

    /** Register an Art Great Work owned by [owner] placed in a slot at [cityLocation]; returns the slot. */
    private fun placeWorkInCity(
        owner: Civilization, cityLocation: com.unciv.logic.map.HexCoord
    ): com.unciv.logic.civilization.managers.GreatWorkSlot {
        val manager = testGame.gameInfo.greatWorkManager
        val work = com.unciv.logic.civilization.managers.GreatWork().apply {
            id = manager.newId()
            type = com.unciv.models.ruleset.GreatWorkType.Art
            creatingCivName = owner.civName
            name = "RivalMasterpiece"
        }
        manager.registerWork(work)
        val slot = com.unciv.logic.civilization.managers.GreatWorkSlot(
            owner.civName, cityLocation, "Museum", 0, com.unciv.models.ruleset.GreatWorkType.Art
        )
        manager.placeWork(work, slot)
        return slot
    }

    @Test
    fun rivalGreatWorkPlacementInUnexploredCityIsScrubbedButRegistryKept() {
        testGame.addUnit("Warrior", civA, centerTile)
        // B's city sits on a far tile A has never explored.
        val far = farTile()
        val cityB: City = testGame.addCity(civB, far)
        val slot = placeWorkInCity(civB, cityB.location)
        val workId = testGame.gameInfo.greatWorkManager.slotPlacements[slot.key()]!!

        testGame.gameInfo.civilizations.forEach { it.cache.updateOurTiles() }
        assertFalse("B's city tile must be unexplored by A", far.isExplored(civA))

        val viewForA = PlayerViewProjector.projectFor(testGame.gameInfo, civA.civID)

        assertFalse(
            "B's placement in a city A never explored must be scrubbed from A's view",
            viewForA.greatWorkManager.slotPlacements.containsKey(slot.key())
        )
        assertNotNull(
            "The GreatWork object itself must remain in the registry (works are public)",
            viewForA.greatWorkManager.getWork(workId)
        )
    }

    @Test
    fun ownersGreatWorkPlacementIsKeptInOwnProjection() {
        testGame.addUnit("Warrior", civB, centerTile)
        val far = farTile()
        val cityB: City = testGame.addCity(civB, far)
        val slot = placeWorkInCity(civB, cityB.location)

        // B is the viewer: its own placement must survive (the civId-guard keeps it regardless of fog).
        val viewForB = PlayerViewProjector.projectFor(testGame.gameInfo, civB.civID)

        assertTrue(
            "B's own placement must be kept in B's own view",
            viewForB.greatWorkManager.slotPlacements.containsKey(slot.key())
        )
    }

    // endregion

    // region Trade Routes (BNW Phase 3 — Increment 5)

    /**
     * Sets up an A→B trade route (owned by A, origin A's city, dest B's city) and a B→C trade route
     * (owned by B, origin B's city, dest C's city), returning (civC, aToB, bToC).
     */
    private fun setUpTwoTradeRoutes(): Triple<Civilization, com.unciv.logic.trade.TradeRouteConnection, com.unciv.logic.trade.TradeRouteConnection> {
        val civC = testGame.addCiv()
        val cityA: City = testGame.addCity(civA, testGame.tileMap[0, 0])
        val cityB: City = testGame.addCity(civB, testGame.tileMap[3, 0])
        val cityC: City = testGame.addCity(civC, testGame.tileMap[-3, 0])

        val aToB = com.unciv.logic.trade.TradeRouteConnection().apply {
            ownerCivId = civA.civID; originCityId = cityA.id; destinationCityId = cityB.id
        }
        val bToC = com.unciv.logic.trade.TradeRouteConnection().apply {
            ownerCivId = civB.civID; originCityId = cityB.id; destinationCityId = cityC.id
        }
        testGame.gameInfo.tradeRouteManager.connections.add(aToB)
        testGame.gameInfo.tradeRouteManager.connections.add(bToC)
        return Triple(civC, aToB, bToC)
    }

    private fun connectionsFor(view: GameInfo) = view.tradeRouteManager.connections

    @Test
    fun tradeRouteViewerSeesOwnAndCityTouchingButNotPurelyRivalRoutes() {
        val (civC, _, _) = setUpTwoTradeRoutes()

        // A: sees its own A→B route; the purely-rival B→C route is scrubbed.
        val viewForA = PlayerViewProjector.projectFor(testGame.gameInfo, civA.civID)
        assertEquals("A sees exactly one route (its own A→B)", 1, connectionsFor(viewForA).size)
        assertEquals(civA.civID, connectionsFor(viewForA).first().ownerCivId)

        // B: A→B touches B's city, B→C is B's own — both present.
        val viewForB = PlayerViewProjector.projectFor(testGame.gameInfo, civB.civID)
        assertEquals("B sees both routes (one touches its city, one it owns)", 2, connectionsFor(viewForB).size)

        // C: only B→C touches C's city; A→B is purely rival -> absent.
        val viewForC = PlayerViewProjector.projectFor(testGame.gameInfo, civC.civID)
        assertEquals("C sees exactly one route (the B→C route touching its city)", 1, connectionsFor(viewForC).size)
        assertEquals(civB.civID, connectionsFor(viewForC).first().ownerCivId)
    }

    @Test
    fun tradeRouteProjectionLeavesTheCanonicalRegistryUntouched() {
        setUpTwoTradeRoutes()
        val canonicalBefore = testGame.gameInfo.tradeRouteManager.connections.size
        assertEquals("Precondition: two routes canonically", 2, canonicalBefore)

        PlayerViewProjector.projectFor(testGame.gameInfo, civA.civID)

        assertEquals("Projection must NOT drop routes from the canonical registry",
            canonicalBefore, testGame.gameInfo.tradeRouteManager.connections.size)
    }

    // endregion

    // region World Congress (BNW Phase 3)

    @Test
    fun congressPublicStateSurvivesProjection() {
        // Increment-1 regression anchor: founding / host / delegate counts are PUBLIC, so they must come
        // through projection unchanged (this is the baseline Increment 2's vote-scrub builds on).
        testGame.addUnit("Warrior", civA, centerTile)
        testGame.ruleset.modOptions.constants.worldCongressFoundingEra = 0
        val congress = testGame.gameInfo.congress
        congress.tryFoundCongress()
        congress.recomputeDelegates()

        val viewForA = PlayerViewProjector.projectFor(testGame.gameInfo, civA.civID)

        assertTrue("Founding status must be public", viewForA.congress.isFounded)
        assertEquals("Host must be public", congress.hostCivId, viewForA.congress.hostCivId)
        assertEquals("Delegate counts must be public",
            congress.delegateCounts, viewForA.congress.delegateCounts)
    }

    @Test
    fun rivalVotesAreScrubbedDuringVotingButOwnAndMetadataKept() {
        // Increment-2 anti-maphack: during Voting, rivals' in-progress votes are hidden; the viewer's own
        // vote, the proposal metadata, and delegate counts stay.
        testGame.addUnit("Warrior", civA, centerTile)
        testGame.ruleset.modOptions.constants.worldCongressFoundingEra = 0
        val congress = testGame.gameInfo.congress
        congress.tryFoundCongress()
        congress.recomputeDelegates()
        congress.currentPhase = com.unciv.logic.civilization.managers.CongressPhase.Voting

        val proposal = com.unciv.logic.civilization.CongressProposal().apply {
            id = 1
            resolutionType = com.unciv.logic.civilization.ResolutionType.SciencesFunding.name
            proposerCivId = civA.civID
            votesFor[civA.civID] = 2   // the viewer's own vote
            votesAgainst[civB.civID] = 3 // a rival's in-progress vote
        }
        congress.activeProposals.add(proposal)

        val viewForA = PlayerViewProjector.projectFor(testGame.gameInfo, civA.civID)
        val projectedProposal = viewForA.congress.activeProposals.first()

        assertTrue("The viewer's own vote must be kept", projectedProposal.votesFor.containsKey(civA.civID))
        assertFalse("A rival's in-progress vote must be scrubbed",
            projectedProposal.votesAgainst.containsKey(civB.civID))
        // Proposal metadata + delegate counts stay public.
        assertEquals(com.unciv.logic.civilization.ResolutionType.SciencesFunding.name,
            projectedProposal.resolutionType)
        assertEquals("Delegate counts remain public during Voting",
            congress.delegateCounts, viewForA.congress.delegateCounts)
        // Canonical state must be untouched.
        assertTrue("Canonical rival vote must be intact",
            congress.activeProposals.first().votesAgainst.containsKey(civB.civID))
    }

    // endregion
}
