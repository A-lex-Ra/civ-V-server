package com.unciv.logic.civilization.managers

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.CongressProposal
import com.unciv.logic.civilization.EnactedResolution
import com.unciv.logic.civilization.ResolutionType
import com.unciv.logic.files.UncivFiles
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BNW Phase 3 — World Congress, Increments 1-4: the authoritative [WorldCongressManager].
 *
 * Founding is era-driven (D6); we set the founding-era constant to 0 so the default-era TestGame civ
 * qualifies immediately, rather than fabricating a Renaissance tech tree. Each test that uses the manager
 * directly re-asserts the transient back-reference is wired (the TestGame constructor covers it, but we
 * mirror the GreatWork tests' explicit setUp call for safety).
 */
@RunWith(GdxTestRunner::class)
class WorldCongressManagerTest {

    private lateinit var testGame: TestGame
    private lateinit var civ: Civilization

    private val congress get() = testGame.gameInfo.congress

    @Before
    fun setUp() {
        // Construct TestGame BEFORE touching UncivGame.Current (TestGame's constructor initializes it;
        // accessing UncivGame.Current first throws UninitializedPropertyAccessException — gotcha #3).
        testGame = TestGame()
        // Founding a city makes civs meet -> a tutorial task -> settings.save() -> needs UncivGame.files.
        UncivGame.Current.files = UncivFiles(Gdx.files)
        testGame.makeHexagonalMap(5)
        testGame.gameInfo.turns = 1
        // Be explicit, like the GreatWork tests, even though the TestGame constructor already wires it.
        congress.setTransients(testGame.gameInfo)
        // Found as soon as any major exists (era 0), so we don't need to fabricate a Renaissance tech tree.
        testGame.ruleset.modOptions.constants.worldCongressFoundingEra = 0
        civ = testGame.addCiv(isPlayer = true)
        // A civ with no city and no unit is isDefeated() == true (no original capital -> else branch:
        // getCivUnitsSize() == 0), so it is NOT alive and getMemberCivs() would exclude it, leaving the
        // congress with no founders. Give `civ` a unit (no min-distance constraint, no tile collision with
        // the tests that place `civ`'s own city at (0,0)) so it is alive and counts as a congress member.
        testGame.addUnit("Warrior", civ, testGame.getTile(0, 3))
    }

    // region founding + delegates

    @Test
    fun `congress founds when a major reaches the founding era`() {
        assertFalse("Fresh manager is not founded", WorldCongressManager().isFounded)

        congress.tryFoundCongress()

        assertTrue("Congress must be founded once a major reaches the founding era", congress.isFounded)
        assertTrue("A host must be elected", congress.hostCivId.isNotEmpty())
        assertTrue("The session countdown must be armed", congress.turnsUntilNextSession > 0)
        assertEquals("Founding turn recorded", 1, congress.foundingTurn)
    }

    @Test
    fun `tryFoundCongress is idempotent`() {
        congress.tryFoundCongress()
        val host = congress.hostCivId
        val foundingTurn = congress.foundingTurn
        testGame.gameInfo.turns = 50
        congress.tryFoundCongress()
        assertEquals("Re-founding must not change the host", host, congress.hostCivId)
        assertEquals("Re-founding must not change the founding turn", foundingTurn, congress.foundingTurn)
    }

    @Test
    fun `delegate count includes base host bonus and allied city-states`() {
        // A second major so there is a non-host member to check the base count against. (Allied-city-state
        // delegates are covered by the formula; `allyCiv` is derived from influence and not directly
        // settable here, so we assert base + host + unique below rather than fabricate an alliance.)
        val second = testGame.addCiv()
        testGame.addUnit("Warrior", second, testGame.getTile(3, 0)) // make the second civ alive (a member)
        congress.tryFoundCongress()
        congress.recomputeDelegates()

        // The host gets base 1 + host bonus 1 = at least 2.
        val host = testGame.gameInfo.getCivilization(congress.hostCivId)
        assertTrue("Host has at least base + host bonus delegates", congress.getDelegateCount(host) >= 2)

        // A non-host member has at least the base of 1.
        val nonHost = congress.getMemberCivs().first { it.civID != congress.hostCivId }
        assertTrue("A non-host member has at least 1 delegate", congress.getDelegateCount(nonHost) >= 1)
    }

    @Test
    fun `WorldCongressDelegates unique adds delegates`() {
        // Give civ a building granting +2 delegates.
        val cityTile = testGame.getTile(0, 0)
        val city = testGame.addCity(civ, cityTile)
        val building = testGame.createBuilding("Provides [2] Delegate(s) in the World Congress")
        city.cityConstructions.addBuilding(building)
        civ.cache.updateState()

        congress.tryFoundCongress()
        // With only civ as a member it is the host: base 1 + host 1 + unique 2 = 4.
        assertEquals("Delegate unique must add to the count", 4, congress.getDelegateCount(civ))
    }

    // endregion
    // region session schedule

    @Test
    fun `session counts down and cycles the phase`() {
        congress.tryFoundCongress()
        val initialSession = congress.sessionNumber
        // Force the countdown to fire on the next advanceTurn.
        congress.turnsUntilNextSession = 1

        // Idle -> begins session (Proposing).
        congress.advanceTurn()
        assertEquals(CongressPhase.Proposing, congress.currentPhase)
        assertEquals("Session number increments at session start", initialSession + 1, congress.sessionNumber)

        // Proposing -> Voting.
        congress.advanceTurn()
        assertEquals(CongressPhase.Voting, congress.currentPhase)

        // Voting -> resolve -> Idle, countdown re-armed.
        congress.advanceTurn()
        assertEquals(CongressPhase.Idle, congress.currentPhase)
        assertTrue("Countdown re-armed after a session", congress.turnsUntilNextSession > 0)
    }

    @Test
    fun `processWorldCongress does real work exactly once per turn`() {
        testGame.ruleset.modOptions.constants.worldCongressFoundingEra = 0
        // Not founded yet; the first call founds, the second (same turn) is a no-op guard.
        assertFalse(congress.isFounded)
        testGame.gameInfo.processWorldCongress()
        assertTrue("First call this turn founds the congress", congress.isFounded)
        val countdownAfterFirst = congress.turnsUntilNextSession
        testGame.gameInfo.processWorldCongress() // same turn -> guarded no-op
        assertEquals("Second call in the same turn must be a no-op",
            countdownAfterFirst, congress.turnsUntilNextSession)
    }

    // endregion
    // region clone & defaults

    @Test
    fun `clone preserves state including empty lists`() {
        congress.tryFoundCongress()
        congress.activeProposals.add(CongressProposal().apply {
            id = 7; resolutionType = ResolutionType.SciencesFunding.name; proposerCivId = civ.civID
            votesFor[civ.civID] = 3
        })
        congress.enactedResolutions.add(EnactedResolution().apply {
            resolutionType = ResolutionType.ArtsFunding.name; enactedTurn = 1; sessionNumber = 1
        })
        congress.bannedLuxuries.add("Silk")

        val cloned = congress.clone()
        assertEquals(congress.isFounded, cloned.isFounded)
        assertEquals(congress.hostCivId, cloned.hostCivId)
        assertEquals(congress.sessionNumber, cloned.sessionNumber)
        assertEquals(1, cloned.activeProposals.size)
        assertNotSame("Cloned proposals must be distinct instances",
            congress.activeProposals.first(), cloned.activeProposals.first())
        assertEquals(3, cloned.activeProposals.first().votesFor[civ.civID])
        assertEquals(1, cloned.enactedResolutions.size)
        assertTrue(cloned.bannedLuxuries.contains("Silk"))
    }

    @Test
    fun `fresh manager defaults to not-founded idle empty (save-compat anchor)`() {
        val fresh = WorldCongressManager()
        assertFalse(fresh.isFounded)
        assertEquals(CongressPhase.Idle, fresh.currentPhase)
        assertTrue(fresh.activeProposals.isEmpty())
        assertTrue(fresh.enactedResolutions.isEmpty())
        assertEquals(-1, fresh.lastProcessedTurn)
        assertEquals(1, fresh.nextProposalId)
    }

    // endregion
    // region resolution effects (Increment 2)

    @Test
    fun `passing SciencesFunding grants the science unique to members and records the resolution`() {
        congress.tryFoundCongress()
        val proposal = CongressProposal().apply {
            id = 1; resolutionType = ResolutionType.SciencesFunding.name; proposerCivId = civ.civID
            votesFor[civ.civID] = 5 // passes (FOR > AGAINST)
        }
        congress.activeProposals.add(proposal)
        congress.currentPhase = CongressPhase.Voting

        congress.resolveSession()

        assertEquals("The passing resolution must be recorded", 1, congress.enactedResolutions.size)
        assertTrue("Members must gain a temporary Science unique",
            civ.temporaryUniques.any { it.uniqueObject.text.contains("Science") })
    }

    @Test
    fun `a tied vote fails`() {
        congress.tryFoundCongress()
        val proposal = CongressProposal().apply {
            id = 1; resolutionType = ResolutionType.SciencesFunding.name; proposerCivId = civ.civID
            votesFor["a"] = 3; votesAgainst["b"] = 3 // tie -> fails
        }
        congress.activeProposals.add(proposal)
        congress.currentPhase = CongressPhase.Voting

        congress.resolveSession()

        assertTrue("A tied proposal must NOT pass", congress.enactedResolutions.isEmpty())
    }

    @Test
    fun `passing BanLuxury suppresses that luxury's happiness`() {
        // A luxury the civ has via a building, in a city.
        val luxury = testGame.createResource()
        luxury.resourceType = com.unciv.models.ruleset.tile.ResourceType.Luxury
        val city = testGame.addCity(civ, testGame.getTile(0, 0))
        val building = testGame.createBuilding("Provides [1] [${luxury.name}]")
        city.cityConstructions.addBuilding(building)
        civ.cache.updateState()
        civ.cache.updateCivResources()

        congress.tryFoundCongress()
        val happinessBefore = civ.stats.getHappinessBreakdown()["Luxury resources"] ?: 0f
        assertTrue("Precondition: the luxury grants happiness", happinessBefore > 0f)

        // Enact a BanLuxury on it.
        val proposal = CongressProposal().apply {
            id = 1; resolutionType = ResolutionType.BanLuxury.name; proposerCivId = civ.civID
            choiceArg = luxury.name; votesFor[civ.civID] = 5
        }
        congress.activeProposals.add(proposal)
        congress.currentPhase = CongressPhase.Voting
        congress.resolveSession()

        assertTrue("The luxury must be banned", congress.bannedLuxuries.contains(luxury.name))
        val happinessAfter = civ.stats.getHappinessBreakdown()["Luxury resources"] ?: 0f
        assertTrue("A banned luxury must grant no happiness", happinessAfter < happinessBefore)
    }

    @Test
    fun `an AI member votes against a BanLuxury on a luxury it has`() {
        // civ is AI and has the luxury -> automateVote should vote AGAINST a ban on it.
        val aiCiv = testGame.addCiv() // AI by default
        val luxury = testGame.createResource()
        luxury.resourceType = com.unciv.models.ruleset.tile.ResourceType.Luxury
        val city = testGame.addCity(aiCiv, testGame.getTile(2, 0))
        val building = testGame.createBuilding("Provides [1] [${luxury.name}]")
        city.cityConstructions.addBuilding(building)
        aiCiv.cache.updateState()
        aiCiv.cache.updateCivResources()

        congress.tryFoundCongress()
        congress.recomputeDelegates()
        congress.currentPhase = CongressPhase.Voting
        // civ (the human proposer) is the host; propose the ban as the human so the AI member can vote on it.
        val proposal = congress.addProposal(civ, ResolutionType.BanLuxury, choiceArg = luxury.name)

        // Drive the real AI voting logic (automateAllVotes votes every AI member's bloc).
        congress.automateAllVotes()

        assertTrue("AI that has the luxury must have voted AGAINST the ban",
            proposal.votesAgainst.containsKey(aiCiv.civID))
    }

    // endregion
    // region resolutions + world projects (Increment 3)

    private fun enact(type: ResolutionType, targetCivId: String = "", choiceArg: String = "") {
        congress.tryFoundCongress()
        val proposal = CongressProposal().apply {
            id = nextId(); resolutionType = type.name; proposerCivId = civ.civID
            this.targetCivId = targetCivId; this.choiceArg = choiceArg
            votesFor[civ.civID] = 5
        }
        congress.activeProposals.add(proposal)
        congress.currentPhase = CongressPhase.Voting
        congress.resolveSession()
    }

    private var idCounter = 100
    private fun nextId() = idCounter++

    @Test
    fun `TradeSanctions records the sanctioned civ and blocks its trade`() {
        val other = testGame.addCiv()
        enact(ResolutionType.TradeSanctions, targetCivId = other.civID)
        assertTrue("The target must be sanctioned", congress.sanctionedCivs.contains(other.civID))
    }

    @Test
    fun `StandingArmyTax sets the durable flag`() {
        enact(ResolutionType.StandingArmyTax)
        assertTrue(congress.standingArmyTaxActive)
    }

    @Test
    fun `NuclearNonProliferation sets the durable flag`() {
        enact(ResolutionType.NuclearNonProliferation)
        assertTrue(congress.nuclearNonProliferation)
    }

    @Test
    fun `a World Project resolution starts a project, then ranks and rewards on its timer`() {
        val contributor = testGame.addCiv()
        testGame.addUnit("Warrior", contributor, testGame.getTile(3, 0)) // alive -> a member whose contribution is recorded
        enact(ResolutionType.WorldsFair)
        assertNotNull("A World Project must be active", congress.activeWorldProject)

        // Bank a contribution and push the clock past the end turn.
        congress.contributeToWorldProject(contributor, 200)
        congress.contributeToWorldProject(civ, 50)
        testGame.gameInfo.turns = congress.activeWorldProject!!.endTurn

        // advanceTurn ticks the world project at its timer (Idle phase, so no session interference).
        congress.currentPhase = CongressPhase.Idle
        congress.turnsUntilNextSession = 999 // don't begin a session this tick
        congress.advanceTurn()

        assertNull("The project must resolve when its timer expires", congress.activeWorldProject)
        assertTrue("The top contributor must gain a reward unique",
            contributor.temporaryUniques.any { it.uniqueObject.text.contains("Culture") })
    }

    @Test
    fun `clone round-trips with an active world project`() {
        enact(ResolutionType.WorldsFair)
        congress.contributeToWorldProject(civ, 30)
        val cloned = congress.clone()
        assertNotNull(cloned.activeWorldProject)
        assertNotSame(congress.activeWorldProject, cloned.activeWorldProject)
        assertEquals(30, cloned.activeWorldProject!!.contributions[civ.civID])
    }

    // endregion
    // region diplomatic victory front-end (Increment 4)

    @Test
    fun `a WorldLeaderElection with enough delegates wins the diplomatic victory`() {
        // A handful of members so the vote threshold is small and reachable.
        val voters = (1..4).map { testGame.addCiv() }
        congress.tryFoundCongress()
        congress.recomputeDelegates()

        val candidate = civ
        val proposal = CongressProposal().apply {
            id = nextId(); resolutionType = ResolutionType.WorldLeaderElection.name
            proposerCivId = candidate.civID; choiceArg = candidate.civID
        }
        // Every voter (and the candidate) backs the candidate FOR.
        for (voter in voters + candidate) proposal.votesFor[voter.civID] = congress.getDelegateCount(voter)
        congress.activeProposals.add(proposal)
        congress.currentPhase = CongressPhase.Voting

        congress.resolveSession()

        assertEquals("Every FOR-voter must be recorded as backing the candidate",
            candidate.civID, testGame.gameInfo.diplomaticVictoryVotesCast[voters.first().civID])
        assertTrue("With enough backing, the candidate must win the diplomatic vote",
            candidate.victoryManager.hasEverWonDiplomaticVote)
    }

    @Test
    fun `the legacy diplomatic vote still works without a founded congress (regression)`() {
        // Without a congress, the legacy UN flow is untouched: a recorded vote with enough backing still
        // wins via processDiplomaticVictory (the same path the legacy ShowDiplomaticVotingResults flag uses).
        assertFalse("Precondition: no congress founded", congress.isFounded)
        val others = (1..4).map { testGame.addCiv() }
        val candidate = civ
        for (voter in others + candidate)
            testGame.gameInfo.diplomaticVictoryVotesCast[voter.civID] = candidate.civID

        testGame.gameInfo.diplomaticVictoryVotesProcessed = false
        testGame.gameInfo.processDiplomaticVictory()

        assertTrue("Legacy diplomatic victory must still resolve without a congress",
            candidate.victoryManager.hasEverWonDiplomaticVote)
    }

    // endregion
    // region serialize

    @Test
    fun `serialize round-trip preserves congress state and re-attaches gameInfo`() {
        // Use a REAL major-civ nation so gameInfoFromString -> setTransients re-resolves it from RulesetCache.
        // setUp gave `civ` a Warrior to keep it alive; destroy it before removing the civ so the map has no
        // unit owned by a civ that no longer exists (which would fail to re-resolve on deserialization).
        civ.units.getCivUnits().toList().forEach { it.destroy() }
        testGame.gameInfo.civilizations.remove(civ)
        val realNation = testGame.ruleset.nations.values.first { it.isMajorCiv }
        val realCiv = testGame.addCiv(realNation, isPlayer = true)
        testGame.addCity(realCiv, testGame.getTile(0, 0))
        testGame.gameInfo.currentPlayer = realCiv.civID
        testGame.gameInfo.currentPlayerCiv = realCiv

        congress.tryFoundCongress()
        congress.enactedResolutions.add(EnactedResolution().apply {
            resolutionType = ResolutionType.SciencesFunding.name; enactedTurn = 1; sessionNumber = 1
        })
        congress.bannedLuxuries.add("Wine")
        congress.nextProposalId = 9

        val serialized = UncivFiles.gameInfoToString(testGame.gameInfo, forceZip = true, updateChecksum = true)
        val reloaded = UncivFiles.gameInfoFromString(serialized)

        assertTrue("isFounded must survive", reloaded.congress.isFounded)
        assertEquals("hostCivId must survive", congress.hostCivId, reloaded.congress.hostCivId)
        assertEquals("nextProposalId must survive", 9, reloaded.congress.nextProposalId)
        assertEquals("enacted resolutions must survive", 1, reloaded.congress.enactedResolutions.size)
        assertTrue("banned luxuries must survive", reloaded.congress.bannedLuxuries.contains("Wine"))
        assertNotNull("gameInfo must be re-attached after load", reloaded.congress.gameInfo)
        assertTrue(reloaded.congress.gameInfo === reloaded)
    }

    // endregion
}
