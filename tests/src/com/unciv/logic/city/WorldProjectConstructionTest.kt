package com.unciv.logic.city

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.ResolutionType
import com.unciv.logic.files.UncivFiles
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.stats.Stats
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BNW Phase 3 — World Congress: [com.unciv.models.ruleset.WorldProjectConstruction]. A World Project
 * (World's Fair / International Games) is a selectable city construction that appears in the production
 * list ONLY while that project is active in the congress, and banks the city's production into it.
 */
@RunWith(GdxTestRunner::class)
class WorldProjectConstructionTest {

    private lateinit var testGame: TestGame
    private lateinit var civ: Civilization
    private lateinit var city: City

    @Before
    fun setUp() {
        // Founding a city makes civs meet -> a tutorial task -> settings.save() -> needs UncivGame.files.
        UncivGame.Current.files = UncivFiles(Gdx.files)
        testGame = TestGame()
        testGame.makeHexagonalMap(3)
        civ = testGame.addCiv(isPlayer = true)
        city = testGame.addCity(civ, testGame.getTile(0, 0))
    }

    @Test
    fun `a world project is buildable only while its project is active`() {
        val congress = testGame.gameInfo.congress
        val worldsFair = PerpetualConstruction.worldsFair

        assertFalse("Not buildable before the congress is founded",
            worldsFair.isBuildable(city.cityConstructions))

        congress.isFounded = true
        congress.startWorldProject(ResolutionType.WorldsFair)

        assertTrue("Buildable while the World's Fair is the active project",
            worldsFair.isBuildable(city.cityConstructions))
        assertFalse("A different project (International Games) is NOT buildable meanwhile",
            PerpetualConstruction.internationalGames.isBuildable(city.cityConstructions))

        congress.activeWorldProject = null
        assertFalse("No longer buildable once the project ends",
            worldsFair.isBuildable(city.cityConstructions))
    }

    @Test
    fun `producing a world project banks the city's production into it`() {
        val congress = testGame.gameInfo.congress
        congress.isFounded = true
        congress.startWorldProject(ResolutionType.WorldsFair)

        city.cityConstructions.constructionQueue.clear()
        city.cityConstructions.addToQueue(PerpetualConstruction.worldsFair.name)

        val before = congress.activeWorldProject!!.contributions[civ.civID] ?: 0
        city.cityConstructions.endTurn(Stats(production = 10f))
        val after = congress.activeWorldProject!!.contributions[civ.civID] ?: 0

        assertEquals("The city's production must be banked into the world project", 10, after - before)
    }
}
