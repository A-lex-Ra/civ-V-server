package com.unciv.logic.civilization.managers

import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.MilestoneType
import com.unciv.models.ruleset.Victory
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BNW Phase 2b — Increment 4: the rewritten cultural victory, driven by the per-rival tourism model
 * ([TourismManager.isInfluentialOverAllMajors]) via the new [MilestoneType.InfluentialOverAllCivs].
 *
 * The G&K test ruleset has no tourism cultural victory, so we register a synthetic "Cultural" victory
 * whose only milestone is the exact bundled-JSON string and enable it. Influence is set directly on the
 * managers (the multiplier/accumulation math is covered by [TourismManagerTest]).
 */
@RunWith(GdxTestRunner::class)
class CulturalVictoryTest {

    private lateinit var testGame: TestGame
    private var nextTileX = 0

    @Before
    fun setUp() {
        testGame = TestGame()
        testGame.makeHexagonalMap(6)
        registerCulturalVictory()
    }

    /** Register a synthetic Cultural victory carrying ONLY the new tourism milestone string, and enable
     *  it in the game parameters (mirrors the edited bundled BNW VictoryTypes.json). */
    private fun registerCulturalVictory(): Victory {
        val victory = Victory()
        victory.name = "Cultural"
        victory.milestones.add(MilestoneType.InfluentialOverAllCivs.text)
        testGame.ruleset.victories[victory.name] = victory
        testGame.gameInfo.gameParameters.victoryTypes.add(victory.name)
        return victory
    }

    private fun addMajorCiv(): Civilization {
        val civ = testGame.addCiv()
        testGame.addCity(civ, testGame.getTile(nextTileX++, 0))
        return civ
    }

    @Test
    fun `the bundled milestone string parses to InfluentialOverAllCivs`() {
        val victory = testGame.ruleset.victories["Cultural"]!!
        val milestone = victory.milestoneObjects.single()
        assertEquals(
            "The JSON milestone string must parse to the engine-checked InfluentialOverAllCivs type",
            MilestoneType.InfluentialOverAllCivs, milestone.type
        )
    }

    @Test
    fun `influential over all rivals completes the milestone`() {
        val viewer = addMajorCiv()
        val rivalA = addMajorCiv()
        val rivalB = addMajorCiv()
        rivalA.totalCultureForContests = 100
        rivalB.totalCultureForContests = 100
        viewer.tourism.accumulatedInfluence[rivalA.civName] = 120 // 120% -> Influential
        viewer.tourism.accumulatedInfluence[rivalB.civName] = 200 // 200% -> Dominant

        val milestone = testGame.ruleset.victories["Cultural"]!!.milestoneObjects.single()
        assertEquals(
            "Influential-or-better over all living majors must complete the milestone",
            true, milestone.hasBeenCompletedBy(viewer)
        )
    }

    @Test
    fun `dropping one rival below Influential leaves the milestone incomplete`() {
        val viewer = addMajorCiv()
        val rivalA = addMajorCiv()
        val rivalB = addMajorCiv()
        rivalA.totalCultureForContests = 100
        rivalB.totalCultureForContests = 100
        viewer.tourism.accumulatedInfluence[rivalA.civName] = 120 // Influential
        viewer.tourism.accumulatedInfluence[rivalB.civName] = 50  // 50% -> Popular, not Influential

        val milestone = testGame.ruleset.victories["Cultural"]!!.milestoneObjects.single()
        assertEquals(
            "A single rival below Influential must leave the cultural milestone incomplete",
            false, milestone.hasBeenCompletedBy(viewer)
        )
    }

    @Test
    fun `getVictoryTypeAchieved returns Cultural when influential over all`() {
        val viewer = addMajorCiv()
        val rivalA = addMajorCiv()
        val rivalB = addMajorCiv()
        rivalA.totalCultureForContests = 100
        rivalB.totalCultureForContests = 100

        // Not yet influential -> no victory.
        assertNull(
            "No cultural victory before influence is built",
            viewer.victoryManager.getVictoryTypeAchieved()
        )

        viewer.tourism.accumulatedInfluence[rivalA.civName] = 150
        viewer.tourism.accumulatedInfluence[rivalB.civName] = 150
        assertEquals(
            "Influential over every living major rival must yield the Cultural victory",
            "Cultural", viewer.victoryManager.getVictoryTypeAchieved()
        )
    }
}
