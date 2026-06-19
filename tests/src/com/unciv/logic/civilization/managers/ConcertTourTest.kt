package com.unciv.logic.civilization.managers

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueTriggerActivation
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionModifiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/**
 * BNW Phase 2b — Increment 5: the rewritten "Perform Concert Tour" (the
 * [UniqueType.OneTimeGainTourismInfluenceOverNearbyCiv] handler). Triggering it from a unit standing in a
 * major rival's territory adds `round(output × 10)` influence over that rival; in own / neutral /
 * city-state land it does nothing. The unit is consumed by the Great Musician's outer
 * `<by consuming this unit>` action modifier (verified separately, exactly as the engine runs it).
 */
@RunWith(GdxTestRunner::class)
class ConcertTourTest {

    private lateinit var testGame: TestGame
    private var nextTileX = 0
    private val output = 4f

    private val concertUnique = UniqueType.OneTimeGainTourismInfluenceOverNearbyCiv.text

    @Before
    fun setUp() {
        testGame = TestGame()
        testGame.makeHexagonalMap(8)
    }

    private fun addMajorCiv(): Civilization {
        val civ = testGame.addCiv()
        // Found a city far enough apart that center tiles (and thus tile ownership) don't overlap.
        testGame.addCity(civ, testGame.getTile(nextTileX, 0))
        nextTileX += 4
        return civ
    }

    private fun addCityState(): Civilization {
        val civ = testGame.addCiv(cityStateType = "Cultured")
        testGame.addCity(civ, testGame.getTile(nextTileX, 0))
        nextTileX += 4
        return civ
    }

    /** A non-city-center tile guaranteed to be owned by [civ]'s first city. A unit can stand on a
     *  foreign *non-center* tile (with open borders), but the engine forbids standing on a foreign
     *  city CENTER (CannotEnterCityCenter), so the territory tests must use an owned border tile. */
    private fun ownedTileOf(civ: Civilization): Tile {
        val city = civ.cities.first()
        val tile = city.getCenterTile().neighbors.first { !it.isCityCenter() }
        city.expansion.takeOwnership(tile)
        return tile
    }

    /** A unit of [civ] standing on [tile], producing a fixed tourism output via the D4 contributor seam. */
    private fun musicianOf(civ: Civilization, tile: Tile): MapUnit {
        // Placing a unit on a foreign-owned tile is rejected by the engine (CannotEnterForeignLand)
        // unless we have entry permission, so grant open borders from the tile's owner first.
        val owner = tile.getOwner()
        if (owner != null && owner != civ)
            civ.getDiplomacyManagerOrMeet(owner).hasOpenBorders = true
        val unit = testGame.addUnit("Warrior", civ, tile)
        civ.tourism.tourismOutputContributors.clear()
        civ.tourism.tourismOutputContributors.add { output }
        return unit
    }

    @Test
    fun `concert tour in a major rival's territory raises influence by output times ten`() {
        val viewer = addMajorCiv()
        val rival = addMajorCiv()
        val rivalTile = ownedTileOf(rival)
        val unit = musicianOf(viewer, rivalTile)
        assertEquals("Precondition: unit must stand in the rival's territory", rival, rivalTile.getOwner())

        val triggered = UniqueTriggerActivation.triggerUnique(Unique(concertUnique), unit)

        assertTrue("The concert-tour unique must report success in a rival's territory", triggered)
        assertEquals(
            "Influence must rise by round(output * CONCERT_TOUR_FACTOR)",
            (output * TourismManager.CONCERT_TOUR_FACTOR).roundToInt(),
            viewer.tourism.accumulatedInfluence[rival.civName]
        )
    }

    @Test
    fun `concert tour in own territory does nothing`() {
        val viewer = addMajorCiv()
        addMajorCiv() // a rival must exist, but the unit is in our OWN land
        val ownTile = viewer.cities.first().getCenterTile()
        val unit = musicianOf(viewer, ownTile)

        val triggered = UniqueTriggerActivation.triggerUnique(Unique(concertUnique), unit)

        assertTrue("No-op in own territory must not report success", !triggered)
        assertTrue(
            "No influence may accrue from a concert tour in our own land",
            viewer.tourism.accumulatedInfluence.isEmpty()
        )
    }

    @Test
    fun `concert tour in neutral territory does nothing`() {
        val viewer = addMajorCiv()
        addMajorCiv()
        // Any neutral (unowned) tile on the map.
        val neutralTile = testGame.tileMap.values.first { it.getOwner() == null }
        val unit = musicianOf(viewer, neutralTile)

        val triggered = UniqueTriggerActivation.triggerUnique(Unique(concertUnique), unit)

        assertTrue("No-op in neutral territory must not report success", !triggered)
        assertTrue(
            "No influence may accrue from a concert tour in neutral land",
            viewer.tourism.accumulatedInfluence.isEmpty()
        )
    }

    @Test
    fun `concert tour in city-state territory does nothing`() {
        val viewer = addMajorCiv()
        val cityState = addCityState()
        val csTile = ownedTileOf(cityState)
        val unit = musicianOf(viewer, csTile)

        val triggered = UniqueTriggerActivation.triggerUnique(Unique(concertUnique), unit)

        assertTrue("No-op in city-state territory must not report success", !triggered)
        assertTrue(
            "No influence may accrue from a concert tour in city-state land",
            viewer.tourism.accumulatedInfluence.isEmpty()
        )
    }

    @Test
    fun `the great musician's consuming action modifier destroys the unit`() {
        val viewer = addMajorCiv()
        // A neutral (unowned) tile — the assertion is only about unit consumption, so location is irrelevant,
        // and a civilian Great Person cannot be placed on a foreign city-center tile.
        val neutralTile = testGame.tileMap.values.first { it.getOwner() == null }
        // Build a unit carrying the Great Musician's real concert-tour action unique with the
        // <by consuming this unit> modifier; activating its side effects must consume the unit.
        val baseUnit = testGame.createBaseUnit(
            uniques = arrayOf(
                "Great Person - [Culture]",
                "Triggers a [Perform Concert Tour] event <by consuming this unit>"
            )
        )
        baseUnit.movement = 2
        val unit = testGame.addUnit(baseUnit.name, viewer, neutralTile)
        val outerUnique: Unique = unit.getMatchingUniques(UniqueType.TriggerEvent).first()

        UnitActionModifiers.activateSideEffects(unit, outerUnique)

        assertTrue("The <by consuming this unit> modifier must destroy the Great Musician", unit.isDestroyed)
    }
}
