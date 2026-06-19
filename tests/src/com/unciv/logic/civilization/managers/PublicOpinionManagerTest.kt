package com.unciv.logic.civilization.managers

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PublicOpinion.CivCountPressureSource
import com.unciv.models.ruleset.PolicyBranch
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BNW Phase 2a — Increment 1: [PublicOpinionManager] (ideological public-opinion pressure).
 *
 * The default test ruleset (Civ V - Gods & Kings) has no native ideologies, so we synthesize three
 * mutually-exclusive ideology branches carrying the same data markers the bundled BNW ideologies use
 * (`Unavailable <after adopting [other]>` + `Remove [Ideology]`). [PolicyBranch.isIdeology] detects
 * them generically from that data, exactly as it would for the real Order/Freedom/Autocracy.
 *
 * Ideologies are assigned directly via the adopted-policy set (not [PolicyManager.adopt]) so the
 * branch's triggerable uniques don't fire on these bare test civs — we only exercise the
 * public-opinion math, which reads `getCurrentIdeology()` and that reads the adopted set.
 */
@RunWith(GdxTestRunner::class)
class PublicOpinionManagerTest {

    private lateinit var testGame: TestGame
    private lateinit var ideologyA: PolicyBranch
    private lateinit var ideologyB: PolicyBranch

    /** Civ-counts pressure with proximity weighting OFF, so each adopting civ contributes exactly 1.
     *  This makes the "majority wins" assertions deterministic regardless of map geometry. */
    private val source = CivCountPressureSource(proximityWeighting = false)

    @Before
    fun setUp() {
        testGame = TestGame()
        // A small map so each civ can own a city (a city-less, unit-less civ counts as defeated and
        // would be excluded from the pressure tally).
        testGame.makeHexagonalMap(4)
        // Three mutually-exclusive ideology branches, detectable via the same markers as bundled BNW.
        // Names are deliberately unique (the G&K test ruleset already has plain "Order"/"Freedom"/
        // "Autocracy" social-policy branches that are NOT ideologies — we must not collide with them).
        ideologyA = createIdeology("IdeologyAlpha", "IdeologyBeta", "IdeologyGamma")
        ideologyB = createIdeology("IdeologyBeta", "IdeologyAlpha", "IdeologyGamma")
        // A third ideology exists in the ruleset (realistic) but no test civ adopts it.
        createIdeology("IdeologyGamma", "IdeologyAlpha", "IdeologyBeta")
    }

    private var nextTileX = 0
    private fun createIdeology(selfName: String, vararg excludes: String): PolicyBranch {
        // Mutual-exclusion markers (one per other ideology) plus the "Remove [Ideology]" marker.
        val uniques = excludes.map { "Unavailable <after adopting [$it]> <hidden from users>" } +
            "Remove [Ideology] [in capital] <hidden from users>"
        val branch = testGame.createPolicyBranch(*uniques.toTypedArray())
        // Re-key the branch under a readable, collision-free name in all ruleset collections so
        // getCurrentIdeology()'s iteration over policyBranches.values stays consistent.
        val autoName = branch.name
        testGame.ruleset.policyBranches.remove(autoName)
        testGame.ruleset.policies.remove(autoName)
        val completePolicy = testGame.ruleset.policies.remove(autoName + com.unciv.models.ruleset.Policy.branchCompleteSuffix)
        branch.name = selfName
        testGame.ruleset.policyBranches[selfName] = branch
        testGame.ruleset.policies[selfName] = branch
        if (completePolicy != null) {
            completePolicy.name = selfName + com.unciv.models.ruleset.Policy.branchCompleteSuffix
            testGame.ruleset.policies[completePolicy.name] = completePolicy
        }
        return branch
    }

    /** Adds a major civ that owns one city (so it is alive / counts) and follows [ideology] if given. */
    private fun addCivWithIdeology(ideology: PolicyBranch?): Civilization {
        val civ = testGame.addCiv()
        testGame.addCity(civ, testGame.getTile(nextTileX++, 0))
        if (ideology != null) civ.policies.getAdoptedPolicies().add(ideology.name)
        return civ
    }

    @Test
    fun `branch with ideology markers is detected as an ideology`() {
        assertTrue("A branch carrying the mutual-exclusion / Remove[Ideology] markers must be an ideology",
            ideologyA.isIdeology)
        // A plain branch (no markers) must NOT be detected as an ideology.
        val plainBranch = testGame.createPolicyBranch("[+10]% Production")
        assertTrue("A plain policy branch must not be detected as an ideology", !plainBranch.isIdeology)
    }

    @Test
    fun `getCurrentIdeology returns the adopted ideology branch`() {
        val civ = addCivWithIdeology(ideologyA)
        assertEquals(ideologyA.name, civ.policies.getCurrentIdeology()?.name)
        val civWithout = addCivWithIdeology(null)
        assertEquals(null, civWithout.policies.getCurrentIdeology())
    }

    @Test
    fun `pressure builds toward the majority ideology`() {
        // Majority follows ideology A; one civ follows B.
        addCivWithIdeology(ideologyA)
        addCivWithIdeology(ideologyA)
        val minorityCiv = addCivWithIdeology(ideologyB)

        // Recompute several turns so the smoothed meter converges toward the target.
        repeat(10) { minorityCiv.publicOpinion.recompute(source) }

        val meter = minorityCiv.publicOpinion.ideologyPressureByBranch
        val pressureA = meter[ideologyA.name] ?: 0f
        val pressureB = meter[ideologyB.name] ?: 0f
        assertTrue("Pressure must be strongest toward the majority ideology A (A=$pressureA, B=$pressureB)",
            pressureA > pressureB)
        // The surrounding "preferred" ideology is the majority one.
        assertEquals(ideologyA.name, minorityCiv.publicOpinion.getPreferredIdeology()?.name)
    }

    @Test
    fun `minority-ideology civ suffers dissident unhappiness while majority does not`() {
        // 2x ideology A (majority), 1x ideology B (minority).
        val majorityCiv = addCivWithIdeology(ideologyA)
        addCivWithIdeology(ideologyA)
        val minorityCiv = addCivWithIdeology(ideologyB)

        repeat(10) {
            majorityCiv.publicOpinion.recompute(source)
            minorityCiv.publicOpinion.recompute(source)
        }

        assertTrue("Minority-ideology civ must feel dissident unhappiness (< 0), was " +
            "${minorityCiv.publicOpinion.dissidentUnhappiness}",
            minorityCiv.publicOpinion.dissidentUnhappiness < 0)
        assertTrue("Majority-ideology civ must feel little/no dissident unhappiness (~0), was " +
            "${majorityCiv.publicOpinion.dissidentUnhappiness}",
            majorityCiv.publicOpinion.dissidentUnhappiness >= minorityCiv.publicOpinion.dissidentUnhappiness)
        assertEquals("A civ whose ideology is the majority must have no dissident unhappiness",
            0, majorityCiv.publicOpinion.dissidentUnhappiness)
    }

    @Test
    fun `happiness breakdown carries the ideological pressure term with the right sign and lowers happiness`() {
        val majorityCiv = addCivWithIdeology(ideologyA)
        addCivWithIdeology(ideologyA)
        val minorityCiv = addCivWithIdeology(ideologyB)

        repeat(10) { minorityCiv.publicOpinion.recompute(source) }
        assertTrue("Precondition: minority civ should have negative public opinion",
            minorityCiv.publicOpinion.getHappinessFromPublicOpinion() < 0)

        val breakdown = minorityCiv.stats.getHappinessBreakdown()
        assertTrue("Happiness breakdown must contain the 'Ideological Pressure' key",
            breakdown.containsKey("Ideological Pressure"))
        val pressureTerm = breakdown["Ideological Pressure"]!!
        assertTrue("Ideological-pressure happiness term must be negative, was $pressureTerm",
            pressureTerm < 0f)

        // The term must drag total happiness below what it would be without the penalty.
        val totalWithPenalty = breakdown.values.sum()
        val totalWithoutPenalty = breakdown.filterKeys { it != "Ideological Pressure" }.values.sum()
        assertTrue("Ideological pressure must lower total happiness",
            totalWithPenalty < totalWithoutPenalty)
    }

    @Test
    fun `no ideology means no public opinion key in the breakdown`() {
        addCivWithIdeology(ideologyA)
        val noIdeologyCiv = addCivWithIdeology(null)

        repeat(5) { noIdeologyCiv.publicOpinion.recompute(source) }

        assertTrue("A civ with no ideology must have an empty pressure meter",
            noIdeologyCiv.publicOpinion.ideologyPressureByBranch.isEmpty())
        assertEquals("A civ with no ideology must have zero dissident unhappiness",
            0, noIdeologyCiv.publicOpinion.dissidentUnhappiness)
        val breakdown = noIdeologyCiv.stats.getHappinessBreakdown()
        assertTrue("A civ with no ideology must NOT have the ideological-pressure key",
            !breakdown.containsKey("Ideological Pressure"))
    }

    @Test
    fun `clone round-trip preserves public opinion state`() {
        val minorityCiv = addCivWithIdeology(ideologyB)
        addCivWithIdeology(ideologyA)
        addCivWithIdeology(ideologyA)
        repeat(10) { minorityCiv.publicOpinion.recompute(source) }

        val originalMeter = HashMap(minorityCiv.publicOpinion.ideologyPressureByBranch)
        val originalUnhappiness = minorityCiv.publicOpinion.dissidentUnhappiness
        assertTrue("Precondition: there should be some pressure to preserve", originalMeter.isNotEmpty())

        val clonedCiv = minorityCiv.clone()
        // setTransients wires civInfo back up after a clone (as a real load would).
        clonedCiv.gameInfo = testGame.gameInfo

        val clonedOpinion = clonedCiv.publicOpinion
        assertEquals("Cloned dissident unhappiness must match", originalUnhappiness, clonedOpinion.dissidentUnhappiness)
        assertEquals("Cloned pressure meter must match", originalMeter, clonedOpinion.ideologyPressureByBranch)
        assertTrue("Clone must be a distinct map instance (deep copy)",
            clonedOpinion.ideologyPressureByBranch !== minorityCiv.publicOpinion.ideologyPressureByBranch)
        assertNotNull(clonedOpinion)
    }
}
