package com.unciv.logic.civilization.managers

import com.unciv.logic.automation.civilization.NextTurnAutomation
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.GreatWorkType
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BNW Phase 2c — Increment 8: AI Great-Work optimization
 * ([GreatWorkManager.autoFillFreeSlots] and [NextTurnAutomation.optimizeGreatWorks]).
 *
 * The G&K test ruleset has no Great-Work slot concept, so we synthesize one (buildings carrying
 * `Provides [n] [Type] Great Work slots` + a `GreatWorkThemingBonus`) plus the legacy stockpiled
 * `Great Work of *` resource, mirroring [GreatWorkCreationTest]/[GreatWorkThemingTest].
 */
@RunWith(GdxTestRunner::class)
class GreatWorkAutomationTest {

    private lateinit var testGame: TestGame
    private var nextTileX = 0

    @Before
    fun setUp() {
        testGame = TestGame()
        testGame.makeHexagonalMap(6)
        testGame.gameInfo.greatWorkManager.setTransients(testGame.gameInfo)
        registerGreatWorkResource(GreatWorkType.Art)
    }

    private fun registerGreatWorkResource(type: GreatWorkType) {
        if (testGame.ruleset.tileResources.containsKey(type.resourceName)) return
        val resource = testGame.createResource("Stockpiled")
        testGame.ruleset.tileResources.remove(resource.name)
        resource.name = type.resourceName
        testGame.ruleset.tileResources[type.resourceName] = resource
    }

    private fun freshTile(): Tile {
        val tile = testGame.getTile(nextTileX, 0)
        nextTileX += 3
        return tile
    }

    private fun addCivWithCity(): Pair<Civilization, City> {
        val civ = testGame.addCiv()
        val city = testGame.addCity(civ, freshTile())
        return civ to city
    }

    private fun addBuildingNamed(city: City, name: String, vararg uniques: String): Building {
        val building = testGame.createBuilding(*uniques)
        testGame.ruleset.buildings.remove(building.name)
        building.name = name
        testGame.ruleset.buildings[name] = building
        city.cityConstructions.addBuilding(building)
        return building
    }

    /** Register an unplaced Art work created by [civ] (not placed in any slot). */
    private fun registerUnplacedArtWork(civ: Civilization, era: String, artist: String): GreatWork {
        val manager = testGame.gameInfo.greatWorkManager
        val work = GreatWork().apply {
            id = manager.newId()
            type = GreatWorkType.Art
            creatingCivName = civ.civName
            fromEra = era
            artistName = artist
            name = "Work-$id"
        }
        manager.registerWork(work)
        return work
    }

    /** Register and place an Art work with the given [era]/[artist] into [building]'s slot [index]. */
    private fun placeArtWork(
        civ: Civilization, city: City, building: String, index: Int, era: String, artist: String
    ): GreatWork {
        val manager = testGame.gameInfo.greatWorkManager
        val slot = GreatWorkSlotProvider.getSlotsForCiv(civ)
            .first { it.buildingName == building && it.cityLocation == city.location && it.slotIndex == index }
        val work = GreatWork().apply {
            id = manager.newId()
            type = GreatWorkType.Art
            creatingCivName = civ.civName
            fromEra = era
            artistName = artist
            name = "Work-$id"
        }
        manager.registerWork(work)
        manager.placeWork(work, slot)
        return work
    }

    @Test
    fun `autoFillFreeSlots places two unplaced same-era works and themes the building`() {
        val (civ, city) = addCivWithCity()
        addBuildingNamed(
            city, "EraMuseum",
            "Provides [2] [Art] Great Work slots",
            "Provides a Theming bonus of [+3 Culture] when its Great Works are [of the same era]"
        )
        val manager = testGame.gameInfo.greatWorkManager
        registerUnplacedArtWork(civ, era = "Ancient era", artist = "A")
        registerUnplacedArtWork(civ, era = "Ancient era", artist = "B")

        val placed = manager.autoFillFreeSlots(civ)

        assertEquals("Both unplaced works must be placed", 2, placed)
        assertEquals("Both slots must now be occupied", 2, manager.slotPlacements.size)
        assertTrue("The building must be themed once both same-era works are placed",
            GreatWorkTheming.isThemed(civ, "EraMuseum", city.location))
    }

    @Test
    fun `autoFillFreeSlots materializes banked stockpiled works into free slots`() {
        val (civ, city) = addCivWithCity()
        addBuildingNamed(city, "Gallery", "Provides [2] [Art] Great Work slots")
        val manager = testGame.gameInfo.greatWorkManager

        // Two banked Art works (no GreatWork objects yet) — the no-free-slot fallback from creation.
        val resource = testGame.ruleset.tileResources[GreatWorkType.Art.resourceName]!!
        civ.gainStockpiledResource(resource, 2)

        val placed = manager.autoFillFreeSlots(civ)

        assertEquals("Both banked works must be materialized and placed", 2, placed)
        assertEquals("The stockpile must be fully drained into the slots",
            0, civ.getResourceAmount(GreatWorkType.Art.resourceName))
        assertEquals("Two works must now be registered", 2, manager.works.size)
        assertEquals("Both slots must be occupied", 2, manager.slotPlacements.size)
    }

    @Test
    fun `optimizeGreatWorks fills slots and themes a building`() {
        val (civ, city) = addCivWithCity()
        addBuildingNamed(
            city, "EraMuseum",
            "Provides [2] [Art] Great Work slots",
            "Provides a Theming bonus of [+3 Culture] when its Great Works are [of the same era]"
        )
        registerUnplacedArtWork(civ, era = "Ancient era", artist = "A")
        registerUnplacedArtWork(civ, era = "Ancient era", artist = "B")

        NextTurnAutomation.optimizeGreatWorks(civ)

        assertTrue("optimizeGreatWorks must place + theme the building",
            GreatWorkTheming.isThemed(civ, "EraMuseum", city.location))
    }

    @Test
    fun `optimizeGreatWorks performs a swap that strictly increases tourism`() {
        val (civ, city) = addCivWithCity()
        // A same-era-themed 2-slot museum, currently NOT themed (one slot is a different era), plus a
        // lone untheme-able gallery holding a work that would complete the theme.
        addBuildingNamed(
            city, "ThemedMuseum",
            "Provides [2] [Art] Great Work slots",
            "Provides a Theming bonus of [+3 Culture] when its Great Works are [of the same era]"
        )
        addBuildingNamed(city, "LoneGallery", "Provides [1] [Art] Great Work slots")
        val manager = testGame.gameInfo.greatWorkManager

        placeArtWork(civ, city, "ThemedMuseum", 0, era = "Ancient era", artist = "A")
        placeArtWork(civ, city, "ThemedMuseum", 1, era = "Classical era", artist = "B") // breaks the theme
        placeArtWork(civ, city, "LoneGallery", 0, era = "Ancient era", artist = "C")    // would complete it

        assertTrue("Precondition: ThemedMuseum starts un-themed",
            !GreatWorkTheming.isThemed(civ, "ThemedMuseum", city.location))
        val before = manager.getTourismContribution(civ)

        NextTurnAutomation.optimizeGreatWorks(civ)

        val after = manager.getTourismContribution(civ)
        assertTrue("ThemedMuseum must be themed after the swap",
            GreatWorkTheming.isThemed(civ, "ThemedMuseum", city.location))
        assertTrue("The swap must strictly increase tourism ($before -> $after)", after > before)
    }
}
