package com.unciv.models.ruleset

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.files.UncivFiles
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression guard for the **"Capture all capitals"** (Domination) milestone — see
 * [Milestone.hasBeenCompletedBy] / `MilestoneType.CaptureAllCapitals`.
 *
 * Motivated by the authoritative multiplayer (multiplayer-v3): founding is **streamed**, so the host
 * can momentarily be the only major civ that has founded a capital. The engine used to read "owns all
 * 1 existing capitals" as a Domination victory, so the host won the instant it placed its capital —
 * and because the authority ships that verdict (`GameInfo.victoryData`) inside every player's filtered
 * snapshot, the *already-decided* game locked every other player out (can't adopt policies, etc.).
 *
 * A domination win must mean controlling capitals taken from OTHER majors, so a world with a single
 * major-capital owner must never complete the milestone — while a civ that genuinely owns every major
 * civ's original capital still must.
 */
@RunWith(GdxTestRunner::class)
class DominationVictoryConditionTest {

    private val testGame = TestGame()
    private lateinit var captureAllCapitals: Milestone

    @Before
    fun setUp() {
        // Founding a city completes a tutorial task -> settings.save() -> needs UncivGame.files.
        UncivGame.Current.files = UncivFiles(Gdx.files)
        testGame.makeHexagonalMap(5)
        captureAllCapitals = testGame.ruleset.victories.getValue("Domination").milestoneObjects.single()
    }

    @Test
    fun aLoneMajorCivOwningOnlyItsOwnCapitalHasNotCapturedAllCapitals() {
        val onlyCiv = testGame.addCiv(isPlayer = true)
        val capital = testGame.addCity(onlyCiv, testGame.tileMap[0, 0])
        assertTrue("Precondition: the founded city is an original capital", capital.isOriginalCapital)

        assertFalse(
            "A single major civ owning only the capital it just founded must NOT win a Domination victory",
            captureAllCapitals.hasBeenCompletedBy(onlyCiv)
        )
    }

    @Test
    fun aMajorCivOwningOnlyOneOfTwoCapitalsHasNotCapturedAllCapitals() {
        val civA = testGame.addCiv(isPlayer = true)
        val civB = testGame.addCiv()
        testGame.addCity(civA, testGame.tileMap[0, 0])
        testGame.addCity(civB, testGame.tileMap[3, 0])

        assertFalse(
            "Owning 1 of 2 original capitals is not a Domination victory",
            captureAllCapitals.hasBeenCompletedBy(civA)
        )
    }

    @Test
    fun aMajorCivThatOwnsEveryOriginalCapitalStillWinsDomination() {
        val civA = testGame.addCiv(isPlayer = true)
        val civB = testGame.addCiv()
        testGame.addCity(civA, testGame.tileMap[0, 0])
        val bCapital = testGame.addCity(civB, testGame.tileMap[3, 0])

        // civA conquers civB's original capital -> civA now owns BOTH majors' original capitals. This is
        // the legitimate domination win the guard must preserve (two majors ever existed, civA holds all
        // their capitals).
        bCapital.moveToCiv(civA)

        assertTrue(
            "Owning every major civ's original capital must still win a Domination victory",
            captureAllCapitals.hasBeenCompletedBy(civA)
        )
    }
}
