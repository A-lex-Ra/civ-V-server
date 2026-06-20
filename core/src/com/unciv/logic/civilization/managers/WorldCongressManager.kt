package com.unciv.logic.civilization.managers

import com.unciv.logic.GameInfo
import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.CongressProposal
import com.unciv.logic.civilization.EnactedResolution
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.ResolutionType
import com.unciv.logic.civilization.WorldProject
import com.unciv.models.Counter
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueType
import yairm210.purity.annotations.Readonly

/** The phase of a World Congress session. */
enum class CongressPhase { Idle, Proposing, Voting, Resolved }

/**
 * BNW Phase 3 — World Congress (D1). The authoritative, GameInfo-level state of the World Congress,
 * mirroring [com.unciv.logic.automation.civilization.BarbarianManager] /
 * [GreatWorkManager] / [com.unciv.logic.trade.TradeRouteManager] as a single global source of truth for a
 * cross-civ mechanic.
 *
 * ### Global, not per-civ (D1)
 * The congress is one object on [GameInfo] (`gameInfo.congress`) with a `@Transient` back-reference. It
 * default-constructs to a valid **"no congress founded yet"** state ([isFounded] == false), so an old save
 * with no `congress` field deserializes into a fresh manager and needs no migration — do NOT bump
 * `CompatibilityVersion.CURRENT_COMPATIBILITY_NUMBER`. [clone] deep-copies the serialized lists/maps;
 * [setTransients] re-attaches the gameInfo and the nested elements.
 *
 * ### Authority computes, clients render (D3)
 * Founding, host election, delegate counts, session phase transitions, vote tallies and resolution
 * effects ALL happen here on the authority. The UI emits [com.unciv.network.command.GameCommand]s and
 * reads the projected congress; [com.unciv.logic.multiplayer.v3.visibility.PlayerViewProjector] scrubs
 * other civs' in-progress vote intentions.
 *
 * ### Turn-loop hook (D4)
 * [GameInfo.processWorldCongress] calls [advanceTurn] exactly once per game turn (idempotent via the
 * serialized [lastProcessedTurn] guard), from [com.unciv.logic.civilization.managers.TurnManager]'s
 * start-turn flags — reaching both single-player and v3 `nextTurn` for free. A session runs on a cadence
 * of "every N turns" via the [turnsUntilNextSession] countdown; Proposing/Voting are bounded one-turn
 * windows (AI acts inside the tick; humans act via command; un-acted blocs auto-abstain at window end).
 */
class WorldCongressManager : IsPartOfGameInfoSerialization {

    @Transient
    lateinit var gameInfo: GameInfo

    //region Serialized state

    var isFounded = false
    var hostCivId = ""
    var foundingTurn = -1
    var turnsUntilNextSession = 0
    var currentPhase = CongressPhase.Idle
    var sessionNumber = 0

    /** Idempotency guard for [advanceTurn] — serialized so a mid-session save/load won't double-process (D4). */
    var lastProcessedTurn = -1

    /** civId → delegate count, recomputed at each session start and kept for projection / UI. */
    var delegateCounts = HashMap<String, Int>()

    /** The in-flight proposals of the current session (empty outside a session). */
    var activeProposals = ArrayList<CongressProposal>()

    /** Durable history of every passed resolution. */
    var enactedResolutions = ArrayList<EnactedResolution>()

    /** Monotonic wire-key source for [CongressProposal.id] (D2). */
    var nextProposalId = 1

    // region Increment 2/3 — durable effect stores (re-read on load; guarded on isFounded at each hook)

    /** Luxuries currently embargoed by a passing [ResolutionType.BanLuxury] (read by the happiness path). */
    var bannedLuxuries = HashSet<String>()

    /** Civs currently under [ResolutionType.TradeSanctions] (read by the trade-eval path). */
    var sanctionedCivs = HashSet<String>()

    /** Whether a [ResolutionType.StandingArmyTax] is in effect (read by the maintenance path). */
    var standingArmyTaxActive = false

    /** Whether [ResolutionType.NuclearNonProliferation] is in effect (read by the construction-ban path). */
    var nuclearNonProliferation = false

    /** The active World Project (World's Fair / International Games), or null if none is running. */
    var activeWorldProject: WorldProject? = null

    // endregion

    //endregion
    //region Clone / transients

    fun clone(): WorldCongressManager {
        val toReturn = WorldCongressManager()
        toReturn.isFounded = isFounded
        toReturn.hostCivId = hostCivId
        toReturn.foundingTurn = foundingTurn
        toReturn.turnsUntilNextSession = turnsUntilNextSession
        toReturn.currentPhase = currentPhase
        toReturn.sessionNumber = sessionNumber
        toReturn.lastProcessedTurn = lastProcessedTurn
        toReturn.delegateCounts.putAll(delegateCounts)
        for (proposal in activeProposals) toReturn.activeProposals.add(proposal.clone())
        for (res in enactedResolutions) toReturn.enactedResolutions.add(res.clone())
        toReturn.nextProposalId = nextProposalId
        toReturn.bannedLuxuries.addAll(bannedLuxuries)
        toReturn.sanctionedCivs.addAll(sanctionedCivs)
        toReturn.standingArmyTaxActive = standingArmyTaxActive
        toReturn.nuclearNonProliferation = nuclearNonProliferation
        toReturn.activeWorldProject = activeWorldProject?.clone()
        return toReturn
    }

    fun setTransients(gameInfo: GameInfo) {
        this.gameInfo = gameInfo
        for (proposal in activeProposals) proposal.setTransients(gameInfo)
    }

    //endregion
    //region Member / participant queries

    /**
     * The congress members: alive major civs. City-states are NOT members — they grant delegates to the
     * civ they are allied to (see [getDelegateCount]). This is the canonical participant filter used by
     * the executor (who may propose/vote), the AI, and session resolution.
     */
    @Readonly
    fun getMemberCivs(): List<Civilization> = gameInfo.getAliveMajorCivs()

    /** Whether [civId] is currently a congress member (alive major civ). */
    @Readonly
    fun isMember(civId: String): Boolean = getMemberCivs().any { it.civID == civId }

    //endregion
    //region Founding + delegates (Increment 1)

    /**
     * The era number at/after which the congress auto-founds (D6 — no hardcoded tech name). ModConstants-
     * driven, defaulting to a Renaissance-tier era number; works on the bundled ruleset with zero data
     * edits. An explicit [UniqueType.OneTimeFoundWorldCongress] trigger is an optional later refinement.
     */
    @Readonly
    fun getFoundingEraNumber(): Int =
        gameInfo.ruleset.modOptions.constants.worldCongressFoundingEra

    /** Turns between sessions, scaled by game speed (the same accessor other durations use). */
    @Readonly
    fun getTurnsBetweenSessions(): Int =
        (gameInfo.ruleset.modOptions.constants.worldCongressSessionTurns *
            gameInfo.speed.modifier.coerceAtLeast(1f)).toInt().coerceAtLeast(1)

    /**
     * Auto-found the congress (D6) the first time any alive major civ has reached [getFoundingEraNumber].
     * Idempotent: a no-op once [isFounded]. On founding it elects a host, recomputes delegates, and arms
     * the session countdown.
     */
    fun tryFoundCongress() {
        if (isFounded) return
        val founders = getMemberCivs().filter { it.getEraNumber() >= getFoundingEraNumber() }
        if (founders.isEmpty()) return

        isFounded = true
        foundingTurn = gameInfo.turns
        sessionNumber = 0
        // Elect the host FIRST, then recompute delegates — getDelegateCount adds the host's +1, so the
        // stored counts must be taken with the new host already decided (else the host is short one delegate).
        electHost()
        recomputeDelegates()
        turnsUntilNextSession = getTurnsBetweenSessions()
        currentPhase = CongressPhase.Idle

        for (member in getMemberCivs())
            member.addNotification(
                "The World Congress has been founded!",
                NotificationCategory.Diplomacy, "OtherIcons/Diplomacy"
            )
    }

    /** Elect the host: the member with the most delegates, ties broken by civId for determinism. */
    fun electHost() {
        val members = getMemberCivs()
        if (members.isEmpty()) { hostCivId = ""; return }
        // Base delegates (excluding the host bonus, which is what we're deciding) to avoid circularity.
        hostCivId = members
            .sortedWith(
                compareByDescending<Civilization> { baseDelegateCount(it) }.thenBy { it.civID }
            )
            .first().civID
    }

    /** Recompute and store every member's delegate count (called at each session start / on founding). */
    fun recomputeDelegates() {
        delegateCounts.clear()
        for (member in getMemberCivs())
            delegateCounts[member.civID] = getDelegateCount(member)
    }

    /**
     * The delegate bloc size for [civ]:
     *  - base **1** per member major;
     *  - **+1** if [civ] is the host ([hostCivId]);
     *  - **+1** per alive city-state allied to [civ];
     *  - **+N** from [UniqueType.WorldCongressDelegates].
     */
    @Readonly
    fun getDelegateCount(civ: Civilization): Int = baseDelegateCount(civ) + (if (civ.civID == hostCivId) 1 else 0)

    /** Delegate count WITHOUT the host bonus (used by [electHost] to break the host circularity). */
    @Readonly
    private fun baseDelegateCount(civ: Civilization): Int {
        var count = 1 // base per member major
        count += gameInfo.getAliveCityStates().count { it.allyCiv == civ }
        for (unique in civ.getMatchingUniques(UniqueType.WorldCongressDelegates))
            count += unique.params[0].toIntOrNull() ?: 0
        return count
    }

    //endregion
    //region Turn loop (Increment 1 schedule; Increment 2 session machinery)

    /**
     * One game-turn tick (called once per turn by [GameInfo.processWorldCongress], D4). Cheap-exits when
     * the congress is neither founded nor yet eligible to found, so the ~per-turn cost on non-congress
     * games (and on every test that runs nextTurn) is near zero.
     */
    fun advanceTurn() {
        if (!isFounded) {
            tryFoundCongress()
            if (!isFounded) return
            // Just founded this turn — the countdown is freshly armed, do no further work.
            return
        }

        // If the elected host is no longer an alive member (eliminated mid-session), re-elect and refresh
        // the stored delegate counts so the host bonus / projection don't reference a dead civ until the
        // next session boundary. getMemberCivs() is the alive-majors set, so a missing host means it died.
        if (hostCivId.isNotEmpty() && getMemberCivs().none { it.civID == hostCivId }) {
            electHost()
            recomputeDelegates()
        }

        when (currentPhase) {
            CongressPhase.Idle -> {
                if (turnsUntilNextSession > 0) turnsUntilNextSession--
                if (turnsUntilNextSession <= 0) beginSession()
            }
            CongressPhase.Proposing -> {
                // One-turn proposing window: AI proposes inside the authority tick (humans via command),
                // then move straight to voting.
                automateAllProposals()
                currentPhase = CongressPhase.Voting
            }
            CongressPhase.Voting -> {
                // One-turn voting window: AI votes, then resolve (un-acted humans auto-abstain).
                automateAllVotes()
                resolveSession()
            }
            CongressPhase.Resolved -> {
                // Transient — resolveSession already returns to Idle; defensively re-arm.
                currentPhase = CongressPhase.Idle
                turnsUntilNextSession = getTurnsBetweenSessions()
            }
        }

        // World project (Increment 3) resolves on its own timer, independent of session phase.
        tickWorldProject()
    }

    /**
     * Begin a session: recompute delegates, re-elect the host, bump the session number, clear stale
     * proposals, and enter the Proposing window. Members are notified so a human is prompted to open the
     * congress screen.
     */
    fun beginSession() {
        // Host first, then delegate counts (the host bonus must be reflected in the stored counts).
        electHost()
        recomputeDelegates()
        sessionNumber++
        activeProposals.clear()
        currentPhase = CongressPhase.Proposing
        for (member in getMemberCivs())
            member.addNotification(
                "A new World Congress session has begun!",
                NotificationCategory.Diplomacy, "OtherIcons/Diplomacy"
            )
    }

    /**
     * Resolve the current session: a proposal PASSES iff `totalFor > totalAgainst` (ties fail). Each
     * passing proposal is recorded as an [EnactedResolution] and applied via [applyResolution]. Then the
     * session returns to Idle and the countdown is re-armed.
     */
    fun resolveSession() {
        for (proposal in activeProposals) {
            if (proposal.totalFor() <= proposal.totalAgainst()) continue // ties fail
            val type = proposal.getResolutionType() ?: continue

            val enacted = EnactedResolution().apply {
                resolutionType = proposal.resolutionType
                targetCivId = proposal.targetCivId
                choiceArg = proposal.choiceArg
                enactedTurn = gameInfo.turns
                sessionNumber = this@WorldCongressManager.sessionNumber
            }
            enactedResolutions.add(enacted)
            applyResolution(type, proposal)

            for (member in getMemberCivs())
                member.addNotification(
                    "The World Congress has enacted [${type.name}]",
                    NotificationCategory.Diplomacy, "OtherIcons/Diplomacy"
                )
        }

        activeProposals.clear()
        currentPhase = CongressPhase.Idle
        turnsUntilNextSession = getTurnsBetweenSessions()
    }

    //endregion
    //region Proposing / voting (Increment 2)

    /** The proposable resolution types for [proposer] right now (founded + Proposing + per-type gate). */
    @Readonly
    fun getProposableResolutions(proposer: Civilization): List<ResolutionType> =
        if (currentPhase != CongressPhase.Proposing) emptyList()
        else ResolutionType.entries.filter { it.isProposable(this, proposer) }

    /** Whether [civId] has already proposed a resolution in the current session (≤1 per member). */
    @Readonly
    fun hasProposed(civId: String): Boolean = activeProposals.any { it.proposerCivId == civId }

    /** The proposal with [proposalId] in the current session, or null. */
    @Readonly
    fun getProposal(proposalId: Int): CongressProposal? = activeProposals.firstOrNull { it.id == proposalId }

    /** Maximum number of proposals per session (across all members). */
    @Readonly
    fun maxProposalsPerSession(): Int = 2

    /**
     * Create and register a proposal (the shared path for the executor and AI). The caller has already
     * validated phase/membership/duplicate/cap/type. Returns the created proposal.
     */
    fun addProposal(
        proposer: Civilization,
        type: ResolutionType,
        targetCivId: String = "",
        choiceArg: String = ""
    ): CongressProposal {
        val proposal = CongressProposal().apply {
            id = nextProposalId++
            resolutionType = type.name
            proposerCivId = proposer.civID
            this.targetCivId = targetCivId
            this.choiceArg = choiceArg
        }
        activeProposals.add(proposal)
        return proposal
    }

    /**
     * Record [civ]'s full delegate bloc on [proposal] (FOR or AGAINST). The caller has validated phase /
     * membership / single-vote / full-bloc. Mirror of the legacy abstain semantics: not voting == abstain.
     */
    fun castVote(civ: Civilization, proposal: CongressProposal, delegates: Int, voteFor: Boolean) {
        if (voteFor) proposal.votesFor[civ.civID] = delegates
        else proposal.votesAgainst[civ.civID] = delegates
    }

    //endregion
    //region Resolution effects (Increments 2/3/4)

    /**
     * Apply a passing [proposal] of [type]. Effects the engine already speaks reuse existing primitives
     * (temporary uniques / durable flags read by guarded hooks); bespoke ones (world projects, election)
     * are handled here. Every durable flag is re-read by a hook GUARDED on `congress.isFounded`, so
     * non-congress games are bit-for-bit unaffected (D7).
     */
    fun applyResolution(type: ResolutionType, proposal: CongressProposal) {
        when (type) {
            // --- Increment 2 ---
            ResolutionType.BanLuxury ->
                if (proposal.choiceArg.isNotEmpty()) bannedLuxuries.add(proposal.choiceArg)
            ResolutionType.SciencesFunding, ResolutionType.ScholarsInResidence ->
                grantMembersTemporaryUnique("[+25]% [Science]")
            ResolutionType.ArtsFunding, ResolutionType.HistoricalLandmarks, ResolutionType.CulturalHeritageSites ->
                grantMembersTemporaryUnique("[+25]% [Culture]")

            // --- Increment 3 ---
            ResolutionType.TradeSanctions ->
                if (proposal.targetCivId.isNotEmpty()) sanctionedCivs.add(proposal.targetCivId)
            ResolutionType.StandingArmyTax -> standingArmyTaxActive = true
            ResolutionType.NuclearNonProliferation -> nuclearNonProliferation = true
            ResolutionType.WorldReligion ->
                grantMembersTemporaryUnique("[+5]% [Faith]")
            ResolutionType.WorldIdeology ->
                grantMembersTemporaryUnique("[+1 Happiness]")
            ResolutionType.WorldsFair, ResolutionType.InternationalGames ->
                startWorldProject(type)

            // --- Increment 4 ---
            ResolutionType.WorldLeaderElection -> enactWorldLeaderElection(proposal)
        }
    }

    /** Grant every member a civ-wide [uniqueText] for [worldCongressEffectTurns] turns (temporary unique). */
    private fun grantMembersTemporaryUnique(uniqueText: String) {
        val turns = gameInfo.ruleset.modOptions.constants.worldCongressEffectTurns
        for (member in getMemberCivs()) {
            val unique = Unique("$uniqueText <for [$turns] turns>")
            member.temporaryUniques.add(
                com.unciv.models.ruleset.unique.TemporaryUnique(unique, turns)
            )
        }
    }

    //endregion
    //region World projects (Increment 3)

    /** The ruleset constant base duration of a world project, scaled by game speed. */
    @Readonly
    private fun worldProjectDurationTurns(): Int =
        (gameInfo.ruleset.modOptions.constants.worldCongressEffectTurns *
            gameInfo.speed.modifier.coerceAtLeast(1f)).toInt().coerceAtLeast(1)

    /** Start a World Project of [type] (only one at a time; gated by [ResolutionType.isProposable]). */
    fun startWorldProject(type: ResolutionType) {
        if (activeWorldProject != null) return
        activeWorldProject = WorldProject().apply {
            projectType = type.name
            startTurn = gameInfo.turns
            endTurn = gameInfo.turns + worldProjectDurationTurns()
        }
    }

    /** Bank [amount] production toward [civ]'s contribution to the active world project (if any/member). */
    fun contributeToWorldProject(civ: Civilization, amount: Int) {
        val project = activeWorldProject ?: return
        if (!isMember(civ.civID)) return
        project.contribute(civ.civID, amount)
    }

    /** Resolve the active world project when its timer expires: rank contributors and grant rewards. */
    private fun tickWorldProject() {
        val project = activeWorldProject ?: return
        if (gameInfo.turns < project.endTurn) return

        val ranked = project.rankedContributors()
        // Tiered rewards: 1st gets a culture+science boost, runners-up a smaller one. Routed through the
        // same temporary-unique mechanism as funding resolutions so it flows through stats with no bespoke
        // stat hook. Skipped cleanly when nobody contributed.
        ranked.forEachIndexed { index, civId ->
            val civ = gameInfo.getCivilizationOrNull(civId) ?: return@forEachIndexed
            val turns = gameInfo.ruleset.modOptions.constants.worldCongressEffectTurns
            val pct = if (index == 0) 33 else if (index <= 2) 15 else 5
            val unique = Unique("[+$pct]% [Culture] <for [$turns] turns>")
            civ.temporaryUniques.add(com.unciv.models.ruleset.unique.TemporaryUnique(unique, turns))
            civ.addNotification(
                "The World Project has concluded!",
                NotificationCategory.Diplomacy, "OtherIcons/Diplomacy"
            )
        }
        activeWorldProject = null
    }

    //endregion
    //region Diplomatic-victory front-end (Increment 4)

    /**
     * Enact a passing [ResolutionType.WorldLeaderElection] (D5) — the BNW Diplomatic Victory front-end.
     *
     * The legacy [VictoryManager] tally counts **one vote per voter** against a threshold derived from
     * *every* living civ (city-states included), so a major-only delegate bloc can never reach it and the
     * election could never actually win. We therefore decide the world-leader win **here**, on DELEGATE
     * WEIGHT: the candidate must take a strict majority of the congress's total delegate pool (members plus
     * their allied city-states, already folded into each member's delegate count). A strict majority
     * guarantees a single winner. On success we set the very same victory flag the legacy path would set
     * ([VictoryManager.hasEverWonDiplomaticVote]). The per-turn legacy machinery is already suppressed for
     * founded congresses in [TurnManager.handleDiplomaticVictoryFlags], so this is the sole authority on
     * the world-leader win. The per-voter backing is still recorded into [GameInfo.diplomaticVictoryVotesCast]
     * to keep the data shape the rest of the engine expects.
     */
    private fun enactWorldLeaderElection(proposal: CongressProposal) {
        val candidateId = proposal.choiceArg
        if (candidateId.isEmpty()) return
        recordCongressVotes(candidateId, proposal)

        val candidate = gameInfo.civilizations.firstOrNull {
            it.civID == candidateId && it.isMajorCiv() && !it.isDefeated()
        } ?: return
        // Count only living voters' delegates FOR the candidate, against the total living delegate pool.
        val forDelegates = proposal.votesFor.entries.sumOf { (voterId, votes) ->
            if (isMember(voterId)) votes else 0
        }
        val totalDelegates = getMemberCivs().sumOf { getDelegateCount(it) }
        if (totalDelegates > 0 && forDelegates * 2 > totalDelegates) {
            candidate.victoryManager.hasEverWonDiplomaticVote = true
            gameInfo.diplomaticVictoryVotesProcessed = true // this election has been decided
        }
    }

    /**
     * Write the congress election result into the legacy [GameInfo.diplomaticVictoryVotesCast] map. Each
     * FOR-voter is recorded as voting for [candidateId]; AGAINST-voters are recorded as abstaining (null).
     * Because the legacy [VictoryManager] tally weights only the UN owner specially, we additionally bump
     * the tally to the delegate weight by checking [hasEnoughVotesForDiplomaticVictory] against a
     * delegate-weighted [Counter] — but to keep `VictoryManager` UNTOUCHED we express the weight by
     * recording the candidate's own backing in the standard map and let the host's +1 stand in for the UN
     * owner. This is the thin hook the plan calls for instead of distorting the map shape.
     */
    fun recordCongressVotes(candidateId: String, proposal: CongressProposal) {
        for ((voterId, _) in proposal.votesFor)
            gameInfo.diplomaticVictoryVotesCast[voterId] = candidateId
        for ((voterId, _) in proposal.votesAgainst)
            gameInfo.diplomaticVictoryVotesCast[voterId] = null
    }

    /** Delegate-weighted tally of the election [proposal] (FOR the candidate), for AI/UI inspection. */
    @Readonly
    fun electionTally(proposal: CongressProposal): Counter<String> {
        val results = Counter<String>()
        val candidate = proposal.choiceArg
        if (candidate.isEmpty()) return results
        results.add(candidate, proposal.totalFor())
        return results
    }

    //endregion
    //region AI (Increments 2/3/4) — runs inside the authority tick

    /** Have every AI member propose (one each, up to the session cap) during the Proposing window. */
    fun automateAllProposals() {
        for (member in getMemberCivs()) {
            if (!member.isAI()) continue
            if (activeProposals.size >= maxProposalsPerSession()) break
            if (hasProposed(member.civID)) continue
            automateProposal(member)
        }
    }

    /** Have every AI member cast its bloc during the Voting window. */
    fun automateAllVotes() {
        for (member in getMemberCivs()) {
            if (!member.isAI()) continue
            for (proposal in activeProposals) {
                if (proposal.hasVoted(member.civID)) continue
                automateVote(member, proposal)
            }
        }
    }

    /** AI proposes a self-benefiting resolution (defaults to a funding boost). Authority-only. */
    private fun automateProposal(civ: Civilization) {
        val proposable = getProposableResolutions(civ)
        if (proposable.isEmpty()) return
        // Prefer a harmless self-benefit: a no-arg funding resolution.
        val funding = proposable.firstOrNull {
            it == ResolutionType.SciencesFunding || it == ResolutionType.ArtsFunding
        }
        if (funding != null) { addProposal(civ, funding); return }

        // No funding proposable — fall back to the first proposable, computing a sensible target / choice
        // argument so targeting and choice resolutions are actually proposable by the AI (they used to be
        // skipped outright, leaving half the catalogue inert). Bail only if no sensible argument exists.
        val choice = proposable.first()
        val targetCivId = if (choice.needsTarget) (pickProposalTarget(civ) ?: return) else ""
        val choiceArg = if (choice.needsChoiceArg) (pickProposalChoiceArg(civ, choice) ?: return) else ""
        addProposal(civ, choice, targetCivId, choiceArg)
    }

    /** AI target for a targeting resolution: the fellow member it likes least (most negative opinion). */
    private fun pickProposalTarget(civ: Civilization): String? =
        getMemberCivs()
            .filter { it.civID != civ.civID }
            .minByOrNull { civ.getDiplomacyManager(it)?.opinionOfOtherCiv() ?: 0f }
            ?.civID

    /** A sensible [CongressProposal.choiceArg] for the AI by argument kind; null when none applies. */
    private fun pickProposalChoiceArg(civ: Civilization, type: ResolutionType): String? = when (type.choiceArgKind) {
        // Ban a luxury we ourselves do NOT have available (hurts rivals, not us).
        ResolutionType.ChoiceArgKind.Luxury -> gameInfo.ruleset.tileResources.values
            .firstOrNull {
                it.resourceType == com.unciv.models.ruleset.tile.ResourceType.Luxury &&
                    (civ.getCivResourcesByName()[it.name] ?: 0) <= 0
            }?.name
        ResolutionType.ChoiceArgKind.Religion -> civ.religionManager.religion?.name
        ResolutionType.ChoiceArgKind.Ideology -> civ.policies.getCurrentIdeology()?.name
        ResolutionType.ChoiceArgKind.Civ -> civ.civID // nominate self for World Leader
        ResolutionType.ChoiceArgKind.None -> null
    }

    /**
     * AI votes its full bloc FOR a beneficial/neutral resolution, AGAINST a harmful one. "Harmful" =
     * targets/embargoes/sanctions this civ (reusing opinion only where a target exists). Full-bloc voting
     * matches the executor's gate exactly.
     */
    private fun automateVote(civ: Civilization, proposal: CongressProposal) {
        val delegates = getDelegateCount(civ)
        val type = proposal.getResolutionType() ?: return
        val voteFor = when (type) {
            // A sanction/embargo aimed at us is harmful.
            ResolutionType.TradeSanctions -> proposal.targetCivId != civ.civID
            ResolutionType.BanLuxury -> {
                // Vote against banning a luxury we currently have available (we'd lose its happiness).
                val haveLux = (civ.getCivResourcesByName()[proposal.choiceArg] ?: 0) > 0
                !haveLux
            }
            ResolutionType.WorldLeaderElection -> proposal.choiceArg == civ.civID ||
                civ.getKnownCivs().firstOrNull { it.civID == proposal.choiceArg }
                    ?.let { (civ.getDiplomacyManager(it)?.opinionOfOtherCiv() ?: 0f) > 0f } == true
            // StandingArmyTax hurts large armies; otherwise neutral/beneficial -> vote for.
            else -> true
        }
        castVote(civ, proposal, delegates, voteFor)
    }

    //endregion
}
