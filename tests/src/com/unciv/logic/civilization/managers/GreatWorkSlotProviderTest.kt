package com.unciv.logic.civilization.managers

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.GreatWorkType
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BNW Phase 2c — Increment 2: [GreatWorkSlotProvider] (derive slots from building data) and
 * [GreatWorkManager.evictOrphanedPlacements] (re-bank works whose slot vanished).
 *
 * The G&K test ruleset has no Great-Work slot buildings, so we synthesize them: a visible building
 * carrying the new `Provides [n] [Type] Great Work slots` unique (the durable path), and a renamed
 * building matching the bundled hidden-building name pattern (the D3 fallback path).
 */
@RunWith(GdxTestRunner::class)
class GreatWorkSlotProviderTest {

    private lateinit var testGame: TestGame
    private var nextTileX = 0

    @Before
    fun setUp() {
        testGame = TestGame()
        testGame.makeHexagonalMap(6)
        testGame.gameInfo.greatWorkManager.setTransients(testGame.gameInfo)
        registerGreatWorkResource(GreatWorkType.Art)
    }

    /** Register the legacy stockpiled "Great Work of X" resource so re-banking can resolve it. */
    private fun registerGreatWorkResource(type: GreatWorkType) {
        if (testGame.ruleset.tileResources.containsKey(type.resourceName)) return
        val resource = testGame.createResource("Stockpiled")
        testGame.ruleset.tileResources.remove(resource.name)
        resource.name = type.resourceName
        testGame.ruleset.tileResources[type.resourceName] = resource
    }

    private fun addCivWithCity(): Pair<Civilization, com.unciv.logic.city.City> {
        val civ = testGame.addCiv()
        val city = testGame.addCity(civ, freshTile())
        return civ to city
    }

    // Cities are spaced 3 tiles apart so their center tiles never overlap.
    private fun freshTile(): Tile {
        val tile = testGame.getTile(nextTileX, 0)
        nextTileX += 3
        return tile
    }

    /** Build a building carrying [uniques], named [name], add it to the ruleset and to [city]. */
    private fun addBuildingNamed(city: com.unciv.logic.city.City, name: String, vararg uniques: String): Building {
        val building = testGame.createBuilding(*uniques)
        testGame.ruleset.buildings.remove(building.name)
        building.name = name
        testGame.ruleset.buildings[name] = building
        city.cityConstructions.addBuilding(building)
        return building
    }

    @Test
    fun `data unique yields the declared slots`() {
        val (civ, city) = addCivWithCity()
        addBuildingNamed(city, "TestMuseum", "Provides [2] [Art] Great Work slots")

        val slots = GreatWorkSlotProvider.getSlotsForCiv(civ).filter { it.buildingName == "TestMuseum" }
        assertEquals(2, slots.size)
        assertEquals(setOf(0, 1), slots.map { it.slotIndex }.toSet())
        assertTrue(slots.all { it.slotType == GreatWorkType.Art })
        assertTrue(slots.all { it.cityLocation == city.location })
        assertTrue(slots.all { it.civId == civ.civName })
    }

    @Test
    fun `hidden building name pattern derives slots attributed to the host`() {
        val (civ, city) = addCivWithCity()
        // Single (no number) → index 0; numbered → n-1; attributed to the host "The Louvre".
        addBuildingNamed(city, "[Royal Library] [Great Work of Writing]")
        addBuildingNamed(city, "[The Louvre] [Great Work of Art] 1")
        addBuildingNamed(city, "[The Louvre] [Great Work of Art] 2")

        val slots = GreatWorkSlotProvider.getSlotsForCiv(civ)
        val writing = slots.filter { it.buildingName == "Royal Library" }
        assertEquals(1, writing.size)
        assertEquals(0, writing[0].slotIndex)
        assertEquals(GreatWorkType.Writing, writing[0].slotType)

        val louvre = slots.filter { it.buildingName == "The Louvre" }.sortedBy { it.slotIndex }
        assertEquals(2, louvre.size)
        assertEquals(listOf(0, 1), louvre.map { it.slotIndex })
        assertTrue(louvre.all { it.slotType == GreatWorkType.Art })
    }

    @Test
    fun `getFreeSlotsForCiv excludes occupied slots and orders capital first`() {
        val civ = testGame.addCiv()
        val capital = testGame.addCity(civ, freshTile())
        val secondCity = testGame.addCity(civ, freshTile())
        addBuildingNamed(capital, "CapMuseum", "Provides [1] [Art] Great Work slots")
        addBuildingNamed(secondCity, "CityMuseum", "Provides [1] [Art] Great Work slots")

        val manager = testGame.gameInfo.greatWorkManager

        // All free initially, capital-first ordering.
        val free = GreatWorkSlotProvider.getFreeSlotsForCiv(civ, GreatWorkType.Art)
        assertEquals(2, free.size)
        assertEquals(capital.location, free[0].cityLocation)

        // Occupy the capital slot → it drops out of the free list.
        val work = GreatWork().apply { id = manager.newId(); type = GreatWorkType.Art }
        manager.registerWork(work)
        manager.placeWork(work, free[0])

        val freeAfter = GreatWorkSlotProvider.getFreeSlotsForCiv(civ, GreatWorkType.Art)
        assertEquals(1, freeAfter.size)
        assertEquals(secondCity.location, freeAfter[0].cityLocation)
    }

    @Test
    fun `getFreeSlotsForCiv respects type fit - Artifact fits Art, Writing does not`() {
        val (civ, city) = addCivWithCity()
        addBuildingNamed(city, "ArtMuseum", "Provides [1] [Art] Great Work slots")

        assertEquals(1, GreatWorkSlotProvider.getFreeSlotsForCiv(civ, GreatWorkType.Art).size)
        assertEquals(1, GreatWorkSlotProvider.getFreeSlotsForCiv(civ, GreatWorkType.Artifact).size)
        assertTrue(GreatWorkSlotProvider.getFreeSlotsForCiv(civ, GreatWorkType.Writing).isEmpty())
    }

    @Test
    fun `evictOrphanedPlacements evicts and re-banks a work whose building was removed`() {
        val (civ, city) = addCivWithCity()
        val building = addBuildingNamed(city, "TempMuseum", "Provides [1] [Art] Great Work slots")
        val manager = testGame.gameInfo.greatWorkManager

        val slot = GreatWorkSlotProvider.getFreeSlotsForCiv(civ, GreatWorkType.Art).first()
        val work = GreatWork().apply {
            id = manager.newId(); type = GreatWorkType.Art; creatingCivName = civ.civName
        }
        manager.registerWork(work)
        manager.placeWork(work, slot)

        val stockpileBefore = civ.getResourceAmount(GreatWorkType.Art.resourceName)

        // Remove the building → its slot no longer exists.
        city.cityConstructions.removeBuilding(building)
        manager.evictOrphanedPlacements()

        // Placement gone, and the work re-banked to the owner's stockpile (+1).
        assertFalse(manager.slotPlacements.containsKey(slot.key()))
        assertEquals(stockpileBefore + 1, civ.getResourceAmount(GreatWorkType.Art.resourceName))
    }

    @Test
    fun `evictOrphanedPlacements keeps placements whose slot still exists`() {
        val (civ, city) = addCivWithCity()
        addBuildingNamed(city, "KeepMuseum", "Provides [1] [Art] Great Work slots")
        val manager = testGame.gameInfo.greatWorkManager

        val slot = GreatWorkSlotProvider.getFreeSlotsForCiv(civ, GreatWorkType.Art).first()
        val work = GreatWork().apply { id = manager.newId(); type = GreatWorkType.Art; creatingCivName = civ.civName }
        manager.registerWork(work)
        manager.placeWork(work, slot)

        manager.evictOrphanedPlacements()
        assertTrue(manager.slotPlacements.containsKey(slot.key()))
    }
}
