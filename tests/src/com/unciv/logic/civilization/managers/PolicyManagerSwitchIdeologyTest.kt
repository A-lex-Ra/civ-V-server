package com.unciv.logic.civilization.managers

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.files.UncivFiles
import com.unciv.models.ruleset.Policy
import com.unciv.models.ruleset.PolicyBranch
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unique.endTurn
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BNW Phase 2a — Increment 2: [PolicyManager.switchIdeology] (the shared switch path used by both the
 * AI and the v3 [com.unciv.logic.multiplayer.v3.command.CommandExecutor]).
 *
 * Asserts the full Civ-V switch contract: the old ideology's tenets are removed and partially refunded,
 * the new ideology's branch start is adopted, the civ enters anarchy with the right (game-speed-scaled)
 * countdown, production / science are zeroed during anarchy via the engine's own temporary uniques, and
 * the countdown / penalty clear over the following turns.
 *
 * The test ruleset (Civ V - Gods & Kings) has no native ideologies, so we synthesize ideology branches
 * carrying the `Remove [Ideology]` data marker that [PolicyBranch.isIdeology] detects generically.
 */
@RunWith(GdxTestRunner::class)
class PolicyManagerSwitchIdeologyTest {

    private lateinit var testGame: TestGame
    private lateinit var civInfo: Civilization
    private lateinit var fromIdeology: PolicyBranch
    private lateinit var toIdeology: PolicyBranch

    @Before
    fun setUp() {
        // TestGame()'s constructor initializes UncivGame.Current, so it MUST come first — accessing
        // UncivGame.Current before it is set throws (and only "happened to work" when an earlier test
        // in the run order had already set Current; recompiles reshuffle that order).
        testGame = TestGame()
        // Founding a city / inter-turn processing can touch settings.save(); wire files as the other
        // engine-level tests do (TestGame doesn't init it under the headless runner).
        UncivGame.Current.files = UncivFiles(Gdx.files)
        testGame.makeHexagonalMap(4)
        fromIdeology = createIdeologyBranch("OldIdeology")
        toIdeology = createIdeologyBranch("NewIdeology")
        civInfo = testGame.addCiv()
        testGame.addCity(civInfo, testGame.getTile(0, 0))
    }

    /** An ideology branch detectable via the `Remove [Ideology]` marker (Signal 2), with no
     *  mutual-exclusion gate so a switch target is voluntarily adoptable. */
    private fun createIdeologyBranch(name: String): PolicyBranch {
        val branch = testGame.createPolicyBranch("Remove [Ideology] [in capital] <hidden from users>")
        val autoName = branch.name
        testGame.ruleset.policyBranches.remove(autoName)
        testGame.ruleset.policies.remove(autoName)
        val complete = testGame.ruleset.policies.remove(autoName + Policy.branchCompleteSuffix)
        branch.name = name
        // Match the real loader: a branch start's `requires` is an empty list (members get [branch]).
        branch.requires = ArrayList()
        // Anchor to a real era so PolicyManager.isAdoptable's ruleset.eras[branch.era]!! doesn't NPE.
        branch.era = testGame.ruleset.eras.keys.first()
        testGame.ruleset.policyBranches[name] = branch
        testGame.ruleset.policies[name] = branch
        if (complete != null) {
            complete.name = name + Policy.branchCompleteSuffix
            complete.requires = arrayListOf(name)
            testGame.ruleset.policies[complete.name] = complete
        }
        return branch
    }

    /** Adopt the [fromIdeology] branch start by PAYING culture (so numberOfAdoptedPolicies > 0 and a
     *  later removal refunds *culture*, not a free policy). */
    private fun adoptStartingIdeologyWithCulture() {
        civInfo.policies.freePolicies = 0
        civInfo.policies.storedCulture = 10_000 // plenty for one early policy
        civInfo.policies.adopt(fromIdeology)
        assertEquals("Precondition: the civ follows the old ideology",
            fromIdeology.name, civInfo.policies.getCurrentIdeology()?.name)
    }

    @Test
    fun `switching removes old tenets, refunds culture, adopts the new branch and enters anarchy`() {
        adoptStartingIdeologyWithCulture()
        val expectedAnarchy = civInfo.policies.getAnarchyTurns()
        val cultureBeforeSwitch = civInfo.policies.storedCulture

        civInfo.policies.switchIdeology(toIdeology, refundPercentage = 50)

        assertFalse("Old ideology branch start must be removed",
            civInfo.policies.isAdopted(fromIdeology.name))
        assertEquals("New ideology must be the current one",
            toIdeology.name, civInfo.policies.getCurrentIdeology()?.name)
        assertTrue("Culture must have been refunded for the lost tenet (was $cultureBeforeSwitch, now ${civInfo.policies.storedCulture})",
            civInfo.policies.storedCulture > cultureBeforeSwitch)
        assertEquals("Anarchy countdown must equal the game-speed-scaled anarchy length",
            expectedAnarchy, civInfo.publicOpinion.anarchyTurnsRemaining)
        assertTrue(civInfo.publicOpinion.isInAnarchy())
    }

    @Test
    fun `anarchy zeroes production and science and the countdown clears it over turns`() {
        adoptStartingIdeologyWithCulture()
        val anarchyTurns = civInfo.policies.getAnarchyTurns()

        civInfo.policies.switchIdeology(toIdeology)

        // During anarchy a civ-wide [-100]% Production / [-100]% Science is added to the civ's
        // temporaryUniques (the engine's timed-effect channel; the <for [N] turns> conditional is
        // stripped on storage, leaving "[-100]% Production"). Assert on that actual state — querying
        // via getMatchingUniques is an unreliable proxy here (unique-cache timing). Civilization
        // surfaces temporaryUniques to city stats via getMatchingTagUniques (Civilization.kt:598).
        val anarchyPenalties = civInfo.temporaryUniques.map { it.unique }.toSet()
        assertTrue("Anarchy must apply a civ-wide [-100]% Production (had $anarchyPenalties)",
            anarchyPenalties.any { "[-100]%" in it && "Production" in it })
        assertTrue("Anarchy must apply a civ-wide [-100]% Science (had $anarchyPenalties)",
            anarchyPenalties.any { "[-100]%" in it && "Science" in it })

        // Run enough turns for the anarchy to fully expire. We exercise the exact per-turn hooks the
        // TurnManager wires: startTurn counts the anarchy down (PublicOpinionManager.decrementAnarchy),
        // endTurn expires the [-100]% temporary uniques (temporaryUniques.endTurn). Driving these two
        // hooks directly avoids the full headless startTurn/endTurn pipeline while testing the real
        // mechanism end-to-end.
        repeat(anarchyTurns + 1) {
            civInfo.publicOpinion.decrementAnarchy()
            civInfo.temporaryUniques.endTurn()
        }

        assertEquals("Anarchy countdown must have cleared", 0, civInfo.publicOpinion.anarchyTurnsRemaining)
        assertFalse("Anarchy state must be over", civInfo.publicOpinion.isInAnarchy())
        val penaltiesAfter = civInfo.temporaryUniques.map { it.unique }.toSet()
        assertFalse("The [-100]% anarchy uniques must have expired (had $penaltiesAfter)",
            penaltiesAfter.any { "[-100]%" in it && ("Production" in it || "Science" in it) })
    }
}
