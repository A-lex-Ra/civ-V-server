package com.unciv.logic.civilization

import com.unciv.logic.civilization.managers.WorldCongressManager
import yairm210.purity.annotations.Readonly

/**
 * BNW Phase 3 — World Congress (D7). The single source of resolution *identity* and metadata.
 *
 * Each entry is one resolution the World Congress can enact. The enum *name* is the wire/serialized key
 * ([CongressProposal.resolutionType] / [EnactedResolution.resolutionType] store the name as a String,
 * resolved back via [ResolutionType.valueOf]); the per-entry metadata ([needsTarget], [needsChoiceArg],
 * [choiceArgKind]) tells the UI/AI/executor what extra flat argument a proposal of this type carries, and
 * [isProposable] gates whether it may be proposed in the current congress state. The actual *effect* of a
 * resolution lives in [WorldCongressManager.applyResolution] (a `when` over this enum), NOT here — this
 * type only carries identity + metadata so the command/state layers stay free of behaviour (D2/D7).
 *
 * Increment 1 ships the enum with the full catalogue declared but only the Increment-2/3/4 effects wired
 * in the manager. Adding an entry is additive: declare it here, then add its `applyResolution` branch and
 * any bespoke hook (all guarded on `congress.isFounded`).
 */
enum class ResolutionType(
    /** Whether a proposal of this type targets a specific civ (writes [CongressProposal.targetCivId]). */
    val needsTarget: Boolean = false,
    /** Whether a proposal of this type carries a free-form choice argument ([CongressProposal.choiceArg]). */
    val needsChoiceArg: Boolean = false,
    /** What kind of [choiceArg] this resolution expects, so the UI can offer the right picker. */
    val choiceArgKind: ChoiceArgKind = ChoiceArgKind.None
) {
    // region Increment 2 — pure-primitive subset

    /** Embargo a luxury resource for everyone ([CongressProposal.choiceArg] = luxury name). */
    BanLuxury(needsChoiceArg = true, choiceArgKind = ChoiceArgKind.Luxury),

    /** Grant every member a civ-wide Science bonus for a number of turns. */
    SciencesFunding,

    /** Grant every member a civ-wide Culture bonus for a number of turns. */
    ArtsFunding,

    // endregion
    // region Increment 3 — broadened catalogue + world projects

    /** Trade-embargo a single civ ([CongressProposal.targetCivId]). */
    TradeSanctions(needsTarget = true),

    /** A global per-unit gold upkeep surcharge while in effect. */
    StandingArmyTax,

    /** Designate a World Religion ([CongressProposal.choiceArg] = religion name). */
    WorldReligion(needsChoiceArg = true, choiceArgKind = ChoiceArgKind.Religion),

    /** Designate a World Ideology ([CongressProposal.choiceArg] = ideology branch name). */
    WorldIdeology(needsChoiceArg = true, choiceArgKind = ChoiceArgKind.Ideology),

    /** Ban nuclear weapons for everyone (a `cannotBuild` flag). */
    NuclearNonProliferation,

    /** Grant every member a civ-wide Culture bonus (themed on historical landmarks). */
    HistoricalLandmarks,

    /** Grant every member a civ-wide Culture bonus (cultural heritage). */
    CulturalHeritageSites,

    /** Grant every member a civ-wide Science bonus (scholars in residence). */
    ScholarsInResidence,

    /** Start a World's Fair world project (production-contribution competition). */
    WorldsFair,

    /** Start an International Games world project (production-contribution competition). */
    InternationalGames,

    // endregion
    // region Increment 4 — diplomatic victory front-end

    /** Elect a World Leader ([CongressProposal.choiceArg] = candidate civId); routes into the legacy
     *  diplomatic-victory machinery when it passes (D5). */
    WorldLeaderElection(needsChoiceArg = true, choiceArgKind = ChoiceArgKind.Civ),

    // endregion
    ;

    /**
     * Whether this resolution may currently be proposed by [proposer] in [congress]. Default: any founded
     * congress in the Proposing phase. Specific entries narrow this (e.g. an election needs >1 candidate,
     * a sanction needs a valid foreign target) in [WorldCongressManager] via the executor's validation.
     */
    @Readonly
    fun isProposable(congress: WorldCongressManager, proposer: Civilization): Boolean {
        if (!congress.isFounded) return false
        return when (this) {
            // World projects can only run one at a time.
            WorldsFair, InternationalGames -> congress.activeWorldProject == null
            else -> true
        }
    }

    /** The kind of [CongressProposal.choiceArg] a resolution carries — drives the UI picker. */
    enum class ChoiceArgKind { None, Luxury, Religion, Ideology, Civ }
}
