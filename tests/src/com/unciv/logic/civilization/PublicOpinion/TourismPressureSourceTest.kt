package com.unciv.logic.civilization.PublicOpinion

import com.unciv.logic.civilization.Civilization
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
 * BNW Phase 2b — Increment 6: [TourismPressureSource]. A civ is ideologically pushed toward the
 * ideology of the civs culturally **influential OVER it** (Popular+, level-scaled). The direction is the
 * crucial invariant: pressure ON `target` comes from civs influential OVER `target`.
 *
 * Ideologies are synthesized exactly as in [com.unciv.logic.civilization.managers.PublicOpinionManagerTest]
 * (the G&K test ruleset has no native ideologies); influence is set directly on the managers.
 */
@RunWith(GdxTestRunner::class)
class TourismPressureSourceTest {

    private lateinit var testGame: TestGame
    private lateinit var ideologyA: PolicyBranch
    private lateinit var ideologyB: PolicyBranch
    private var nextTileX = 0

    private val source = TourismPressureSource()

    @Before
    fun setUp() {
        testGame = TestGame()
        testGame.makeHexagonalMap(6)
        ideologyA = createIdeology("PressureIdeologyAlpha", "PressureIdeologyBeta")
        ideologyB = createIdeology("PressureIdeologyBeta", "PressureIdeologyAlpha")
    }

    private fun addMajorCiv(): Civilization {
        val civ = testGame.addCiv()
        testGame.addCity(civ, testGame.getTile(nextTileX++, 0))
        return civ
    }

    /** Set [influencer]'s tourism influence OVER [target] to [ratio] of target's lifetime culture. */
    private fun setInfluence(influencer: Civilization, target: Civilization, ratio: Float) {
        target.totalCultureForContests = 100
        influencer.tourism.accumulatedInfluence[target.civName] = (ratio * 100).toInt()
    }

    @Test
    fun `influential rival over target pushes its ideology onto the target`() {
        val target = addMajorCiv()
        val rival = addMajorCiv()
        rival.policies.getAdoptedPolicies().add(ideologyA.name)
        setInfluence(rival, target, 1.2f) // 120% -> Influential -> weight 2

        val pressure = source.pressureOn(target)

        assertEquals("Only the influencing rival's ideology should be present", setOf(ideologyA), pressure.keys)
        assertEquals(
            "Influential influence must contribute weight 2 toward the influencer's ideology",
            2f, pressure[ideologyA]!!, 0.0001f
        )
    }

    @Test
    fun `dominant rival contributes the highest weight`() {
        val target = addMajorCiv()
        val rival = addMajorCiv()
        rival.policies.getAdoptedPolicies().add(ideologyA.name)
        setInfluence(rival, target, 2.5f) // 250% -> Dominant -> weight 3

        val pressure = source.pressureOn(target)
        assertEquals("Dominant influence must contribute weight 3", 3f, pressure[ideologyA]!!, 0.0001f)
    }

    @Test
    fun `popular rival contributes the base weight`() {
        val target = addMajorCiv()
        val rival = addMajorCiv()
        rival.policies.getAdoptedPolicies().add(ideologyA.name)
        setInfluence(rival, target, 0.7f) // 70% -> Popular -> weight 1

        val pressure = source.pressureOn(target)
        assertEquals("Popular influence must contribute weight 1", 1f, pressure[ideologyA]!!, 0.0001f)
    }

    @Test
    fun `merely-familiar influence exerts no pressure`() {
        val target = addMajorCiv()
        val rival = addMajorCiv()
        rival.policies.getAdoptedPolicies().add(ideologyA.name)
        setInfluence(rival, target, 0.4f) // 40% -> Familiar (< Popular) -> no pressure

        val pressure = source.pressureOn(target)
        assertTrue("Below-Popular influence must produce no ideological pressure", pressure.isEmpty())
    }

    @Test
    fun `an influential rival with no adopted ideology exerts no pressure`() {
        val target = addMajorCiv()
        val rival = addMajorCiv()
        // rival is Dominant over target but has NOT adopted an ideology
        setInfluence(rival, target, 2.5f)

        val pressure = source.pressureOn(target)
        assertTrue("A rival with no ideology cannot push one onto the target", pressure.isEmpty())
    }

    @Test
    fun `direction matters - the target's own influence over a rival does not pressure the target`() {
        val target = addMajorCiv()
        val rival = addMajorCiv()
        // The TARGET is Dominant over the RIVAL, and the RIVAL has no ideology. The target follows B.
        target.policies.getAdoptedPolicies().add(ideologyB.name)
        setInfluence(target, rival, 2.5f) // target influences rival, NOT the reverse

        val pressure = source.pressureOn(target)
        assertTrue(
            "Pressure on the target must come from civs influential OVER it, not from its own influence " +
                "over others",
            pressure.isEmpty()
        )
    }

    @Test
    fun `weights from multiple influencers sharing an ideology accumulate`() {
        val target = addMajorCiv()
        val rivalPopular = addMajorCiv()
        val rivalInfluential = addMajorCiv()
        rivalPopular.policies.getAdoptedPolicies().add(ideologyA.name)
        rivalInfluential.policies.getAdoptedPolicies().add(ideologyA.name)
        setInfluence(rivalPopular, target, 0.7f)      // Popular -> 1
        setInfluence(rivalInfluential, target, 1.2f)  // Influential -> 2

        val pressure = source.pressureOn(target)
        assertEquals("Shared-ideology influencers must sum (1 + 2)", 3f, pressure[ideologyA]!!, 0.0001f)
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
