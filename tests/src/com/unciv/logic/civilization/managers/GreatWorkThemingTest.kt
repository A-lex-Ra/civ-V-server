package com.unciv.logic.civilization.managers

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Tourism.GreatWorksTourismSource
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
 * BNW Phase 2c — Increment 5: [GreatWorkTheming] (matching rules), [GreatWorkManager.getTourismContribution]
 * (per-work + theming tourism), and the [GreatWorksTourismSource] registration into the Phase 2b seam.
 */
@RunWith(GdxTestRunner::class)
class GreatWorkThemingTest {

    private lateinit var testGame: TestGame
    private var nextTileX = 0

    @Before
    fun setUp() {
        testGame = TestGame()
        testGame.makeHexagonalMap(6)
        testGame.gameInfo.greatWorkManager.setTransients(testGame.gameInfo)
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

    /** Register and place an Art work with the given [era]/[artist] into the given slot index of [building]. */
    private fun placeWork(
        civ: Civilization, city: City, building: String, index: Int,
        era: String, artist: String
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
    fun `same-era rule is themed when all works share an era`() {
        val (civ, city) = addCivWithCity()
        addBuildingNamed(
            city, "EraMuseum",
            "Provides [2] [Art] Great Work slots",
            "Provides a Theming bonus of [+3 Culture] when its Great Works are [of the same era]"
        )
        placeWork(civ, city, "EraMuseum", 0, era = "Ancient era", artist = "A")
        placeWork(civ, city, "EraMuseum", 1, era = "Ancient era", artist = "B")

        assertTrue("Two same-era works must theme the building",
            GreatWorkTheming.isThemed(civ, "EraMuseum", city.location))
    }

    @Test
    fun `same-era rule is not themed when eras differ`() {
        val (civ, city) = addCivWithCity()
        addBuildingNamed(
            city, "EraMuseum",
            "Provides [2] [Art] Great Work slots",
            "Provides a Theming bonus of [+3 Culture] when its Great Works are [of the same era]"
        )
        placeWork(civ, city, "EraMuseum", 0, era = "Ancient era", artist = "A")
        placeWork(civ, city, "EraMuseum", 1, era = "Classical era", artist = "B")

        assertFalse("Differing eras must NOT theme the building",
            GreatWorkTheming.isThemed(civ, "EraMuseum", city.location))
    }

    @Test
    fun `not themed when a slot is unfilled`() {
        val (civ, city) = addCivWithCity()
        addBuildingNamed(
            city, "EraMuseum",
            "Provides [2] [Art] Great Work slots",
            "Provides a Theming bonus of [+3 Culture] when its Great Works are [of the same era]"
        )
        placeWork(civ, city, "EraMuseum", 0, era = "Ancient era", artist = "A") // only one of two slots filled

        assertFalse("An unfilled slot must prevent theming",
            GreatWorkTheming.isThemed(civ, "EraMuseum", city.location))
    }

    @Test
    fun `distinct-artists rule themed and not`() {
        val (civ, city) = addCivWithCity()
        addBuildingNamed(
            city, "ArtistMuseum",
            "Provides [2] [Art] Great Work slots",
            "Provides a Theming bonus of [+2 Culture] when its Great Works are [by distinct artists]"
        )
        placeWork(civ, city, "ArtistMuseum", 0, era = "Ancient era", artist = "Alice")
        placeWork(civ, city, "ArtistMuseum", 1, era = "Classical era", artist = "Bob")
        assertTrue("Distinct artists must theme",
            GreatWorkTheming.isThemed(civ, "ArtistMuseum", city.location))

        // Make both artists identical -> no longer themed.
        testGame.gameInfo.greatWorkManager.works.values.forEach { it.artistName = "Same" }
        assertFalse("Duplicate artists must NOT theme",
            GreatWorkTheming.isThemed(civ, "ArtistMuseum", city.location))
    }

    @Test
    fun `getTourismContribution is two per filled slot plus theming`() {
        val (civ, city) = addCivWithCity()
        // A 2-slot themed building (same era) + a third lone filled slot in another building.
        addBuildingNamed(
            city, "ThemedMuseum",
            "Provides [2] [Art] Great Work slots",
            "Provides a Theming bonus of [+3 Culture] when its Great Works are [of the same era]"
        )
        addBuildingNamed(city, "LoneGallery", "Provides [1] [Art] Great Work slots")
        placeWork(civ, city, "ThemedMuseum", 0, era = "Ancient era", artist = "A")
        placeWork(civ, city, "ThemedMuseum", 1, era = "Ancient era", artist = "B")
        placeWork(civ, city, "LoneGallery", 0, era = "Classical era", artist = "C")

        // 3 filled slots -> 3*2 = 6, plus one themed building -> + THEMED_TOURISM_BONUS.
        val expected = 3 * 2f + GreatWorkTheming.THEMED_TOURISM_BONUS
        assertEquals(expected, testGame.gameInfo.greatWorkManager.getTourismContribution(civ), 0.0001f)
    }

    @Test
    fun `registered source yields the same number as getTourismContribution`() {
        val (civ, city) = addCivWithCity()
        addBuildingNamed(city, "Gallery", "Provides [2] [Art] Great Work slots")
        placeWork(civ, city, "Gallery", 0, era = "Ancient era", artist = "A")
        placeWork(civ, city, "Gallery", 1, era = "Ancient era", artist = "B")

        // Register the source fresh, then evaluate the (single Great-Works) contributor it added.
        civ.tourism.tourismOutputContributors.clear()
        GreatWorksTourismSource.register(civ)
        val fromSource = civ.tourism.tourismOutputContributors.sumOf { it().toDouble() }.toFloat()

        assertEquals(
            "The registered source must yield exactly getTourismContribution",
            testGame.gameInfo.greatWorkManager.getTourismContribution(civ), fromSource, 0.0001f
        )
    }

    @Test
    fun `register is idempotent - no duplicate contributor`() {
        val (civ, _) = addCivWithCity()
        civ.tourism.tourismOutputContributors.clear()
        GreatWorksTourismSource.register(civ)
        GreatWorksTourismSource.register(civ)
        GreatWorksTourismSource.register(civ)
        assertEquals(
            "Repeated registration must not accumulate duplicate Great-Works contributors",
            1, civ.tourism.tourismOutputContributors.size
        )
    }
}
