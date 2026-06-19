package com.unciv.logic.civilization.managers

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Tourism.TourismInfluenceLevel
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.models.ruleset.Policy
import com.unciv.models.ruleset.PolicyBranch
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BNW Phase 2b — [TourismManager]: per-rival tourism-influence accumulation + level thresholds
 * (Increment 1) and the per-target relationship multipliers (Increment 3).
 *
 * Tourism *output* is normally read from the engine `Tourism` stockpiled-resource supply; for the
 * accumulation/level math we drive it directly via a [TourismManager.tourismOutputContributors]
 * closure (the D4 seam), which exercises the math without ruleset surgery. One dedicated test wires a
 * synthetic `Tourism` resource + a `Provides [n] [Tourism]` building to prove the engine-supply read.
 *
 * The G&K test ruleset has no native ideologies, so (as in [PublicOpinionManagerTest]) we synthesize
 * mutually-exclusive ideology branches carrying the same data markers the bundled BNW ideologies use,
 * detected generically by [PolicyBranch.isIdeology].
 */
@RunWith(GdxTestRunner::class)
class TourismManagerTest {

    private lateinit var testGame: TestGame

    private var nextTileX = 0

    @Before
    fun setUp() {
        testGame = TestGame()
        testGame.makeHexagonalMap(6)
        // The G&K test ruleset has no native "Tourism" resource; register one so TourismManager's
        // ruleset guard (recompute / getBaseTourismOutput) treats this as a BNW-style ruleset.
        registerTourismResource()
    }

    /** Define a "Tourism" stockpiled resource in the ruleset (if absent) so the manager's BNW guard
     *  passes. Returns the resource (re-keyed to the exact name the manager looks for). */
    private fun registerTourismResource() {
        if (testGame.ruleset.tileResources.containsKey(TourismManager.TOURISM_RESOURCE)) return
        val resource = testGame.createResource("Stockpiled")
        testGame.ruleset.tileResources.remove(resource.name)
        resource.name = TourismManager.TOURISM_RESOURCE
        testGame.ruleset.tileResources[TourismManager.TOURISM_RESOURCE] = resource
    }

    /** Adds a major civ that owns one city (so it counts as alive / a real rival). */
    private fun addMajorCiv(): Civilization {
        val civ = testGame.addCiv()
        testGame.addCity(civ, testGame.getTile(nextTileX++, 0))
        return civ
    }

    /** Make [civ]'s tourism output a fixed [value] each turn via the D4 contributor seam. */
    private fun setFixedOutput(civ: Civilization, value: Float) {
        civ.tourism.tourismOutputContributors.clear()
        civ.tourism.tourismOutputContributors.add { value }
    }

    // region Increment 1 — accumulation, level thresholds, isInfluentialOverAllMajors, clone

    @Test
    fun `accumulates influence each turn`() {
        val viewer = addMajorCiv()
        val rival = addMajorCiv()
        rival.totalCultureForContests = 0
        setFixedOutput(viewer, 5f)

        val turns = 4
        repeat(turns) { viewer.tourism.recompute() }

        assertEquals(
            "Accumulated influence must be output (5) * turns ($turns)",
            5 * turns, viewer.tourism.accumulatedInfluence[rival.civName]
        )
    }

    @Test
    fun `getBaseTourismOutput reads the engine Tourism supply`() {
        // The "Tourism" resource is registered in setUp; add a building that provides 3 of it per turn.
        val tourismBuilding = testGame.createBuilding("Provides [3] [${TourismManager.TOURISM_RESOURCE}]")

        val civ = addMajorCiv()
        civ.cities.first().cityConstructions.addBuilding(tourismBuilding)
        civ.cache.updateCivResources()

        assertEquals(
            "getBaseTourismOutput must read the engine Tourism supply provided by the building",
            3f, civ.tourism.getBaseTourismOutput(), 0f
        )
    }

    @Test
    fun `influence level thresholds`() {
        val viewer = addMajorCiv()
        val rival = addMajorCiv()
        rival.totalCultureForContests = 100

        fun levelAt(influence: Int): TourismInfluenceLevel {
            viewer.tourism.accumulatedInfluence[rival.civName] = influence
            return viewer.tourism.getInfluenceLevelOver(rival)
        }

        assertEquals(TourismInfluenceLevel.Exotic, levelAt(5))    // 5%  < 10%
        assertEquals(TourismInfluenceLevel.Exposed, levelAt(15))  // 15% ≥ 10%
        assertEquals(TourismInfluenceLevel.Familiar, levelAt(40)) // 40% ≥ 30%
        assertEquals(TourismInfluenceLevel.Popular, levelAt(70))  // 70% ≥ 60%
        assertEquals(TourismInfluenceLevel.Influential, levelAt(120)) // 120% ≥ 100%
        assertEquals(TourismInfluenceLevel.Dominant, levelAt(250)) // 250% ≥ 200%

        // Zero-defense edge: any positive influence over a culture-less rival is Dominant-level.
        rival.totalCultureForContests = 0
        assertEquals(
            "Positive influence over a culture-less rival must be Dominant",
            TourismInfluenceLevel.Dominant, levelAt(1)
        )
    }

    @Test
    fun `isInfluentialOverAllMajors`() {
        val viewer = addMajorCiv()
        val rivalA = addMajorCiv()
        val rivalB = addMajorCiv()
        rivalA.totalCultureForContests = 100
        rivalB.totalCultureForContests = 100

        // Both rivals at ratio >= 1.0 -> influential over all.
        viewer.tourism.accumulatedInfluence[rivalA.civName] = 120
        viewer.tourism.accumulatedInfluence[rivalB.civName] = 100
        assertTrue("Influential over both rivals must be true", viewer.tourism.isInfluentialOverAllMajors())

        // Drop one below Influential -> false.
        viewer.tourism.accumulatedInfluence[rivalB.civName] = 50 // 50% -> Popular, not Influential
        assertFalse("Not influential over rival B must be false", viewer.tourism.isInfluentialOverAllMajors())
    }

    @Test
    fun `isInfluentialOverAllMajors is false with no living rivals`() {
        val loner = addMajorCiv()
        assertFalse(
            "A civ with no living major rivals cannot be culturally victorious",
            loner.tourism.isInfluentialOverAllMajors()
        )
    }

    @Test
    fun `clone round-trip preserves accumulatedInfluence`() {
        val viewer = addMajorCiv()
        val rival = addMajorCiv()
        rival.totalCultureForContests = 0
        setFixedOutput(viewer, 7f)
        repeat(3) { viewer.tourism.recompute() }

        val original = HashMap(viewer.tourism.accumulatedInfluence)
        assertTrue("Precondition: there should be some influence to preserve", original.isNotEmpty())

        val clonedCiv = viewer.clone()
        clonedCiv.gameInfo = testGame.gameInfo

        assertEquals(
            "Cloned accumulatedInfluence must match the original",
            original, clonedCiv.tourism.accumulatedInfluence
        )
        assertTrue(
            "Clone must be a distinct map instance (deep copy)",
            clonedCiv.tourism.accumulatedInfluence !== viewer.tourism.accumulatedInfluence
        )
    }

    // endregion

    // region Increment 3 — per-target relationship multipliers

    private fun meet(a: Civilization, b: Civilization) {
        a.diplomacyFunctions.makeCivilizationsMeet(b)
    }

    @Test
    fun `war zeroes the multiplier and recompute adds zero`() {
        val viewer = addMajorCiv()
        val rival = addMajorCiv()
        rival.totalCultureForContests = 0
        setFixedOutput(viewer, 10f)
        meet(viewer, rival)
        viewer.getDiplomacyManager(rival)!!.declareWar()

        assertEquals(
            "A multiplier against a civ we are at war with must be 0",
            0f, viewer.tourism.getTourismMultiplierAgainst(rival), 0f
        )
        viewer.tourism.recompute()
        assertEquals(
            "No influence accrues toward a civ we are at war with",
            0, viewer.tourism.accumulatedInfluence[rival.civName] ?: 0
        )
    }

    @Test
    fun `unmet rival uses the base multiplier`() {
        val viewer = addMajorCiv()
        val rival = addMajorCiv()
        assertEquals(
            "An unmet rival contributes none of the diplomacy factors -> base 1.0",
            1f, viewer.tourism.getTourismMultiplierAgainst(rival), 0f
        )
    }

    @Test
    fun `open borders raises the multiplier`() {
        val viewer = addMajorCiv()
        val rival = addMajorCiv()
        meet(viewer, rival)
        // hasOpenBorders is a public transient flag; set it directly (either direction counts).
        viewer.getDiplomacyManager(rival)!!.hasOpenBorders = true

        assertEquals(
            "Open borders must add +0.25 to the base 1.0",
            1.25f, viewer.tourism.getTourismMultiplierAgainst(rival), 0.0001f
        )
    }

    @Test
    fun `declaration of friendship and research agreement raise the multiplier`() {
        val viewer = addMajorCiv()
        val rival = addMajorCiv()
        meet(viewer, rival)
        viewer.getDiplomacyManager(rival)!!.setFlag(DiplomacyFlags.DeclarationOfFriendship, 30)
        viewer.getDiplomacyManager(rival)!!.setFlag(DiplomacyFlags.ResearchAgreement, 10)

        assertEquals(
            "DoF (+0.25) + RA (+0.25) on top of base 1.0",
            1.5f, viewer.tourism.getTourismMultiplierAgainst(rival), 0.0001f
        )
    }

    @Test
    fun `same ideology raises and different ideology lowers the multiplier`() {
        val ideologyA = createIdeology("TourismIdeologyAlpha", "TourismIdeologyBeta")
        val ideologyB = createIdeology("TourismIdeologyBeta", "TourismIdeologyAlpha")

        val viewer = addMajorCiv()
        val sameIdeologyRival = addMajorCiv()
        val differentIdeologyRival = addMajorCiv()
        meet(viewer, sameIdeologyRival)
        meet(viewer, differentIdeologyRival)

        viewer.policies.getAdoptedPolicies().add(ideologyA.name)
        sameIdeologyRival.policies.getAdoptedPolicies().add(ideologyA.name)
        differentIdeologyRival.policies.getAdoptedPolicies().add(ideologyB.name)

        assertEquals(
            "Shared ideology must add +0.25",
            1.25f, viewer.tourism.getTourismMultiplierAgainst(sameIdeologyRival), 0.0001f
        )
        assertEquals(
            "Opposing ideology must subtract 0.25",
            0.75f, viewer.tourism.getTourismMultiplierAgainst(differentIdeologyRival), 0.0001f
        )
    }

    @Test
    fun `factors stack and clamp at zero`() {
        val viewer = addMajorCiv()
        val rival = addMajorCiv()
        meet(viewer, rival)
        val diplomacy = viewer.getDiplomacyManager(rival)!!
        diplomacy.hasOpenBorders = true
        diplomacy.setFlag(DiplomacyFlags.DeclarationOfFriendship, 30)
        diplomacy.setFlag(DiplomacyFlags.ResearchAgreement, 10)

        // Stacking: base 1.0 + OB 0.25 + DoF 0.25 + RA 0.25 = 1.75.
        assertEquals(
            "Friendly factors must stack additively",
            1.75f, viewer.tourism.getTourismMultiplierAgainst(rival), 0.0001f
        )

        // Clamp: a strongly-negative contributor cannot push the multiplier below 0.
        viewer.tourism.tourismMultiplierContributors.add { -100f }
        assertEquals(
            "The multiplier is clamped at >= 0",
            0f, viewer.tourism.getTourismMultiplierAgainst(rival), 0f
        )
    }

    // endregion

    /**
     * Synthesizes a mutually-exclusive ideology branch detected by [PolicyBranch.isIdeology], re-keyed
     * under a readable, collision-free name (mirrors [PublicOpinionManagerTest.createIdeology]).
     */
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
