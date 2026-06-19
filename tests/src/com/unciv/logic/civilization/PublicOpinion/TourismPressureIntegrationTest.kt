package com.unciv.logic.civilization.PublicOpinion

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.TourismManager
import com.unciv.models.ruleset.Policy
import com.unciv.models.ruleset.PolicyBranch
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BNW Phase 2b — Increment 6 (the keystone): proves the tourism-driven ideological-pressure source
 * works end-to-end through the EXISTING [PublicOpinionManager.recompute] seam with **zero change** to
 * PublicOpinionManager — it consumes only [IdeologicalPressureSource]. Also pins the
 * [com.unciv.logic.GameInfo.getIdeologicalPressureSource] factory swap and the pressure DIRECTION.
 *
 * (That PublicOpinionManager.kt is literally untouched is enforced by review/diff; this test
 * demonstrates that no change to it is *needed*: the same recompute call drives real-tourism pressure.)
 */
@RunWith(GdxTestRunner::class)
class TourismPressureIntegrationTest {

    private lateinit var testGame: TestGame
    private lateinit var ideologyA: PolicyBranch
    private lateinit var ideologyB: PolicyBranch
    private var nextTileX = 0

    @Before
    fun setUp() {
        testGame = TestGame()
        testGame.makeHexagonalMap(6)
        ideologyA = createIdeology("KeystoneIdeologyAlpha", "KeystoneIdeologyBeta")
        ideologyB = createIdeology("KeystoneIdeologyBeta", "KeystoneIdeologyAlpha")
    }

    private fun addMajorCiv(): Civilization {
        val civ = testGame.addCiv()
        testGame.addCity(civ, testGame.getTile(nextTileX++, 0))
        return civ
    }

    private fun registerTourismResource() {
        if (testGame.ruleset.tileResources.containsKey(TourismManager.TOURISM_RESOURCE)) return
        val resource = testGame.createResource("Stockpiled")
        testGame.ruleset.tileResources.remove(resource.name)
        resource.name = TourismManager.TOURISM_RESOURCE
        testGame.ruleset.tileResources[TourismManager.TOURISM_RESOURCE] = resource
    }

    @Test
    fun `getIdeologicalPressureSource returns TourismPressureSource only when a Tourism resource exists`() {
        // The default G&K test ruleset has no Tourism resource -> civ-counts stand-in.
        assertTrue(
            "Without a Tourism resource, the factory must return the civ-counts source",
            testGame.gameInfo.getIdeologicalPressureSource() is CivCountPressureSource
        )
        // Register a Tourism resource (BNW-style) -> tourism-driven source.
        registerTourismResource()
        assertTrue(
            "With a Tourism resource, the factory must return the tourism-driven source",
            testGame.gameInfo.getIdeologicalPressureSource() is TourismPressureSource
        )
    }

    @Test
    fun `tourism pressure drives public opinion toward the influencer's ideology via the unchanged seam`() {
        val target = addMajorCiv()
        val rival = addMajorCiv()
        // The TARGET follows ideology B; the RIVAL (influential OVER the target) follows ideology A.
        target.policies.getAdoptedPolicies().add(ideologyB.name)
        rival.policies.getAdoptedPolicies().add(ideologyA.name)
        // Rival is Dominant OVER the target (direction!): 250% of the target's lifetime culture.
        target.totalCultureForContests = 100
        rival.tourism.accumulatedInfluence[target.civName] = 250

        val source = TourismPressureSource()
        // Drive the EXISTING recompute seam several turns so the smoothed meter converges.
        repeat(12) { target.publicOpinion.recompute(source) }

        val meter = target.publicOpinion.ideologyPressureByBranch
        val pressureA = meter[ideologyA.name] ?: 0f
        val pressureB = meter[ideologyB.name] ?: 0f
        assertTrue(
            "Pressure must build toward the influencing rival's ideology A (A=$pressureA, B=$pressureB)",
            pressureA > pressureB
        )
        assertEquals(
            "The surrounding-preferred ideology must be the influencer's (A), proving the direction",
            ideologyA.name, target.publicOpinion.getPreferredIdeology()?.name
        )
        // The target follows B but is dominated by A -> it must feel dissident unhappiness (< 0).
        assertTrue(
            "A civ dominated by a rival ideology must feel dissident unhappiness, was " +
                "${target.publicOpinion.dissidentUnhappiness}",
            target.publicOpinion.dissidentUnhappiness < 0
        )
    }

    private fun createIdeology(selfName: String, vararg excludes: String): PolicyBranch {
        val uniques = excludes.map { "Unavailable <after adopting [$it]> <hidden from users>" } +
            "Remove [Ideology] [in capital] <hidden from users>"
        val branch = testGame.createPolicyBranch(*uniques.toTypedArray())
        val autoName = branch.name
        testGame.ruleset.policyBranches.remove(autoName)
        testGame.ruleset.policies.remove(autoName)
        val completePolicy = testGame.ruleset.policies.remove(autoName + Policy.branchCompleteSuffix)
        branch.name = selfName
        testGame.ruleset.policyBranches[selfName] = branch
        testGame.ruleset.policies[selfName] = branch
        if (completePolicy != null) {
            completePolicy.name = selfName + Policy.branchCompleteSuffix
            testGame.ruleset.policies[completePolicy.name] = completePolicy
        }
        return branch
    }
}
