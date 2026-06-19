package com.unciv.logic.civilization.managers

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.GreatWorkType
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueTriggerActivation
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BNW Phase 2c — Increment 4: [GreatWorkCreation] and the `OneTimeProvideResources` interception in
 * [UniqueTriggerActivation].
 *
 * The G&K test ruleset has no Great-Work slot concept, so we synthesize one (a building carrying
 * `Provides [n] [Type] Great Work slots`) plus the legacy `Great Work of *` stockpiled resource. A
 * separate test proves a ruleset with NO slot concept keeps the legacy stockpile path unchanged.
 */
@RunWith(GdxTestRunner::class)
class GreatWorkCreationTest {

    private lateinit var testGame: TestGame
    private var nextTileX = 0

    private val provideArtUnique = "Instantly provides [1] [${GreatWorkType.Art.resourceName}]"

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

    @Test
    fun `provide-resource with a free Art slot creates and places a named work`() {
        val (civ, city) = addCivWithCity()
        addBuildingNamed(city, "TestMuseum", "Provides [1] [Art] Great Work slots")
        val manager = testGame.gameInfo.greatWorkManager

        val triggered = UniqueTriggerActivation.triggerUnique(Unique(provideArtUnique), civ)

        assertTrue("The Great-Work creation path must report success", triggered)
        assertEquals("One work must have been registered", 1, manager.works.size)
        val work = manager.works.values.first()
        assertEquals(GreatWorkType.Art, work.type)
        assertEquals(civ.civName, work.creatingCivName)
        assertEquals("Era must be recorded from the civ", civ.getEra().name, work.fromEra)
        assertTrue("The work must have been placed in a slot", manager.slotPlacements.containsValue(work.id))
        // No stockpile credit when the work was placed.
        assertEquals("Nothing should be banked when a slot was free",
            0, civ.getResourceAmount(GreatWorkType.Art.resourceName))
    }

    @Test
    fun `provide-resource with no free slot registers the work and banks the stockpile`() {
        // A civ whose ruleset HAS the slot concept (another civ has a slot building), but THIS civ owns
        // no free slot of the right type, so creation falls back to banking.
        val (slotCiv, slotCity) = addCivWithCity()
        addBuildingNamed(slotCity, "OtherMuseum", "Provides [1] [Art] Great Work slots")

        val (civ, _) = addCivWithCity() // no slot building -> no free Art slot for this civ
        val manager = testGame.gameInfo.greatWorkManager
        val before = civ.getResourceAmount(GreatWorkType.Art.resourceName)

        val triggered = UniqueTriggerActivation.triggerUnique(Unique(provideArtUnique), civ)

        assertTrue(triggered)
        assertEquals("The work must still be registered (not lost)", 1, manager.works.size)
        assertFalse("The work must NOT be placed (this civ has no free slot)",
            manager.slotPlacements.values.contains(manager.works.values.first().id))
        assertEquals("The stockpile must be credited as the fallback",
            before + 1, civ.getResourceAmount(GreatWorkType.Art.resourceName))
        // Keep slotCiv referenced to make the intent explicit.
        assertTrue(GreatWorkSlotProvider.getSlotsForCiv(slotCiv).isNotEmpty())
    }

    @Test
    fun `a ruleset with no slot concept keeps the legacy stockpile path`() {
        // No slot building anywhere -> rulesetHasGreatWorkSlots == false -> legacy stockpile credit.
        val (civ, _) = addCivWithCity()
        val manager = testGame.gameInfo.greatWorkManager
        val before = civ.getResourceAmount(GreatWorkType.Art.resourceName)

        val triggered = UniqueTriggerActivation.triggerUnique(Unique(provideArtUnique), civ)

        assertTrue(triggered)
        assertTrue("No GreatWork object may be created in a non-slot ruleset", manager.works.isEmpty())
        assertEquals("The legacy stockpile must be incremented unchanged",
            before + 1, civ.getResourceAmount(GreatWorkType.Art.resourceName))
    }
}
