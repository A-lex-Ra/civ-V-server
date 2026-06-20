package com.unciv.logic.civilization

import com.unciv.logic.GameInfo
import com.unciv.logic.IsPartOfGameInfoSerialization
import yairm210.purity.annotations.Readonly

/**
 * BNW Phase 3 — World Congress (Increment 1). A single in-flight proposal during a congress session,
 * server-canonical and referenced on the wire only by its int [id] (D2). Vote tallies are delegate
 * counts keyed by the casting civ id, split into [votesFor] / [votesAgainst] (full-bloc voting, so each
 * civ appears in at most one of the two maps).
 *
 * Serializes via [IsPartOfGameInfoSerialization]: flat primitives + String-keyed maps only. The
 * [resolutionType] is the [ResolutionType] enum *name* as a String (resolved via
 * [ResolutionType.valueOf]); [setTransients] is a no-op (no back-references). A default-constructed
 * instance is a valid empty proposal.
 */
class CongressProposal : IsPartOfGameInfoSerialization {

    /** Monotonic id assigned from [com.unciv.logic.civilization.managers.WorldCongressManager.nextProposalId]. */
    var id = 0

    /** The [ResolutionType] enum name (resolved via [ResolutionType.valueOf]). */
    var resolutionType = ""

    /** Civ id that proposed this resolution. */
    var proposerCivId = ""

    /** For targeting resolutions ([ResolutionType.needsTarget]) — the targeted civ id, else empty. */
    var targetCivId = ""

    /** For choice resolutions ([ResolutionType.needsChoiceArg]) — luxury/religion/ideology/civ name, else empty. */
    var choiceArg = ""

    /** civId → delegates cast FOR this proposal. */
    var votesFor = HashMap<String, Int>()

    /** civId → delegates cast AGAINST this proposal. */
    var votesAgainst = HashMap<String, Int>()

    fun clone(): CongressProposal {
        val toReturn = CongressProposal()
        toReturn.id = id
        toReturn.resolutionType = resolutionType
        toReturn.proposerCivId = proposerCivId
        toReturn.targetCivId = targetCivId
        toReturn.choiceArg = choiceArg
        toReturn.votesFor.putAll(votesFor)
        toReturn.votesAgainst.putAll(votesAgainst)
        return toReturn
    }

    @Suppress("UNUSED_PARAMETER")
    fun setTransients(gameInfo: GameInfo) {
        // No back-references to attach — proposals reference everything by id/name.
    }

    /** The resolved [ResolutionType], or null if the stored name is unknown in this ruleset/version. */
    @Readonly
    fun getResolutionType(): ResolutionType? =
        ResolutionType.entries.firstOrNull { it.name == resolutionType }

    /** Total delegates voting FOR. */
    @Readonly
    fun totalFor(): Int = votesFor.values.sum()

    /** Total delegates voting AGAINST. */
    @Readonly
    fun totalAgainst(): Int = votesAgainst.values.sum()

    /** Whether [civId] has already cast a vote (for or against) on this proposal. */
    @Readonly
    fun hasVoted(civId: String): Boolean = civId in votesFor || civId in votesAgainst
}
