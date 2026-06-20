package com.unciv.logic.civilization.managers

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Tourism.TourismInfluenceLevel
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import yairm210.purity.annotations.Readonly
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * BNW Phase 2b — Increment 1: per-rival tourism *influence* state for one [Civilization].
 *
 * **AUTHORITY-ONLY GameInfo state (D2, mirrors [PublicOpinionManager]).** Recomputed by the authority
 * each turn (in [TurnManager.startTurn], right after public-opinion, before stats) and projected to
 * clients; clients never recompute it, because they see rivals' culture/buildings scrubbed (so a
 * client recompute would diverge from the host). [com.unciv.logic.multiplayer.v3.visibility.PlayerViewProjector]
 * clears a rival civ's [accumulatedInfluence] in the wire view (Increment 2); the viewer's own stays
 * intact, and the publicly-observable culture-defense ([Civilization.totalCultureForContests]) is left alone.
 *
 * Each turn (D6), for every living major rival `r`, this civ's tourism output × the per-target
 * relationship multiplier is *added* to `accumulatedInfluence[r]`. The accumulator is monotonic
 * (Civ V tourism toward a civ doesn't decay; the rival's *culture* grows, which is the "defense", so
 * the ratio can fall even though the numerator only rises). The influence *level* (D5) is that
 * accumulator as a fraction of the rival's lifetime culture.
 *
 * Tourism *output* is read from the existing engine `Tourism` stockpiled-resource supply (D1), which
 * already aggregates `Provides [n] [Tourism]` from buildings/wonders/Great-Work slots — we do not
 * re-walk buildings. Phase 2c contributes Great-Work output and per-target theming through the two
 * `@Transient` contributor seams (D4) with no edit to this class.
 *
 * Serializes via [IsPartOfGameInfoSerialization]; [accumulatedInfluence] defaults empty, so old saves
 * (which lack this manager entirely) deserialize into a valid neutral state.
 */
class TourismManager : IsPartOfGameInfoSerialization {

    @Transient
    lateinit var civInfo: Civilization

    /** Rival civName -> lifetime accumulated tourism influence points (D6). Monotonic, defaults empty. */
    var accumulatedInfluence = HashMap<String, Int>()

    /** D4 seam (Phase 2c): additive per-turn tourism-output contributors (e.g. Great Works). Not
     *  serialized — closures can't be persisted; rebuilt empty each load, re-registered at runtime. */
    @Transient
    var tourismOutputContributors = ArrayList<() -> Float>()

    /** D4 seam (Phase 2c): additive per-target multiplier contributors (e.g. Great-Work theming).
     *  Not serialized; rebuilt empty each load. */
    @Transient
    var tourismMultiplierContributors = ArrayList<(Civilization) -> Float>()

    fun clone(): TourismManager {
        val toReturn = TourismManager()
        toReturn.accumulatedInfluence = HashMap(accumulatedInfluence)
        // Transient contributor lists are intentionally NOT cloned — they re-register at setTransients.
        return toReturn
    }

    fun setTransients(civInfo: Civilization) {
        this.civInfo = civInfo
        // Contributor lists keep their (empty) default; Phase 2c registers into them at runtime.
    }

    /**
     * This civ's raw per-turn tourism output (D1): the engine `Tourism` stockpiled-resource supply,
     * plus any registered [tourismOutputContributors] (D4). Returns 0 in non-BNW rulesets that have no
     * `Tourism` resource (the supply simply never contains it).
     */
    @Readonly
    fun getBaseTourismOutput(): Float {
        val engineSupply = civInfo.getCivResourceSupply()
            .firstOrNull { it.resource.name == TOURISM_RESOURCE }?.amount?.toFloat() ?: 0f
        // The engine `Tourism` supply above already has the civ's `%-Tourism resource production` modifier
        // applied (CivInfoTransientCache), but the contributor-seam tourism (Great Works, Phase 2c) bypasses
        // that pipeline. Apply the SAME modifier here so a `[+x]% [Tourism] resource production` unique — e.g.
        // Brazil's Carnival, which doubles ALL tourism during a Golden Age — also scales Great-Work tourism.
        // The modifier is 1.0 (no-op) in normal play and in rulesets without a Tourism resource.
        val tourismResource = civInfo.gameInfo.ruleset.tileResources[TOURISM_RESOURCE]
        val contributorModifier = if (tourismResource != null) civInfo.getResourceModifier(tourismResource) else 1f
        val contributed = tourismOutputContributors.sumOf { it().toDouble() }.toFloat() * contributorModifier
        return engineSupply + contributed
    }

    /**
     * BNW Phase 2b — Increment 3: the per-target relationship multiplier applied to this civ's tourism
     * output toward [target]. Models the standard Civ V relationship modifiers, each a tunable factor:
     *  - **At war** with the target → `0f` (short-circuit: no cultural exchange across a war front).
     *  - Base `1.0`.
     *  - **Open Borders** (either direction) → `+0.25`.
     *  - **Shared majority religion** → `+0.25`.
     *  - **Ideology**: same adopted ideology → `+0.25`; both have an ideology but different → `-0.25`;
     *    neither / one-sided → no change.
     *  - **Declaration of Friendship** → `+0.25`.
     *  - **Research Agreement** active → `+0.25`.
     *  - **International trade route** that THIS civ established TO the target → `+0.25` (Phase 3 ITR):
     *    a route carries its origin civ's tourism to the destination, so only our own outgoing routes count.
     *  - Plus any registered [tourismMultiplierContributors] (D4 / Phase 2c theming seam).
     *  - Final result is clamped at `≥ 0`.
     *
     * Unmet rivals (no [DiplomacyManager]) contribute none of the diplomacy factors, leaving base 1.0.
     */
    @Readonly
    fun getTourismMultiplierAgainst(target: Civilization): Float {
        if (civInfo.isAtWarWith(target)) return 0f

        var multiplier = 1f

        val diplomacy = civInfo.getDiplomacyManager(target)
        if (diplomacy != null) {
            // Open Borders in either direction (DiplomacyManager.hasOpenBorders is "can WE enter THEM").
            if (diplomacy.hasOpenBorders || diplomacy.otherCivDiplomacy().hasOpenBorders)
                multiplier += OPEN_BORDERS_BONUS

            if (diplomacy.hasFlag(DiplomacyFlags.DeclarationOfFriendship))
                multiplier += DECLARATION_OF_FRIENDSHIP_BONUS

            if (diplomacy.hasFlag(DiplomacyFlags.ResearchAgreement))
                multiplier += RESEARCH_AGREEMENT_BONUS
        }

        if (sharesMajorityReligionWith(target))
            multiplier += SHARED_RELIGION_BONUS

        if (hasTradeRouteTo(target))
            multiplier += TRADE_ROUTE_BONUS

        multiplier += ideologyModifierAgainst(target)

        multiplier += tourismMultiplierContributors.sumOf { it(target).toDouble() }.toFloat()

        return max(0f, multiplier)
    }

    /**
     * Whether THIS civ has established an International Trade Route whose destination is one of [target]'s
     * cities. A trade route carries its origin civ's tourism to the destination, so the bonus is
     * directional — only our own outgoing routes to the target count, NOT a route the target sends to us.
     * Reads the authoritative [com.unciv.logic.trade.TradeRouteManager] registry.
     */
    @Readonly
    private fun hasTradeRouteTo(target: Civilization): Boolean {
        val manager = civInfo.gameInfo.tradeRouteManager
        return manager.connections.any { c ->
            if (c.ownerCivId != civInfo.civID) return@any false
            val dest = manager.getDestinationCity(c) ?: return@any false
            dest.civ.civID == target.civID
        }
    }

    /** Mirrors `DiplomacyManager.believesSameReligion` (which is private): true iff this civ has a
     *  majority religion and it is also the majority religion of [target]. */
    @Readonly
    private fun sharesMajorityReligionWith(target: Civilization): Boolean {
        val myMajorityReligion = civInfo.religionManager.getMajorityReligion() ?: return false
        return target.religionManager.isMajorityReligionForCiv(myMajorityReligion)
    }

    /** +0.25 when both civs share an adopted ideology, -0.25 when both have ideologies that differ,
     *  0 when either has no adopted ideology. */
    @Readonly
    private fun ideologyModifierAgainst(target: Civilization): Float {
        val myIdeology = civInfo.policies.getCurrentIdeology() ?: return 0f
        val theirIdeology = target.policies.getCurrentIdeology() ?: return 0f
        return if (myIdeology.name == theirIdeology.name) SAME_IDEOLOGY_BONUS else DIFFERENT_IDEOLOGY_PENALTY
    }

    /**
     * Authority-only per-turn accumulation step (D6). For each living major rival, add this turn's
     * tourism contribution (output × per-target multiplier, never negative) to its accumulator.
     * Entries for civs that later cease to be major/alive are kept (harmless history). No-op in
     * rulesets without a `Tourism` resource (non-BNW).
     */
    fun recompute() {
        if (!civInfo.gameInfo.ruleset.tileResources.containsKey(TOURISM_RESOURCE)) return

        val output = getBaseTourismOutput()
        for (other in civInfo.gameInfo.civilizations) {
            if (other == civInfo) continue
            if (!other.isMajorCiv() || other.isDefeated()) continue
            val delta = max(0, (output * getTourismMultiplierAgainst(other)).roundToInt())
            accumulatedInfluence[other.civName] = (accumulatedInfluence[other.civName] ?: 0) + delta
        }
    }

    /**
     * BNW Phase 2b — Increment 5 (concert tour): add a large one-time burst of influence over [target]
     * equal to this civ's current tourism output × [multiplier] (Civ V uses ×10). Authority-only,
     * resolved through the `Perform Concert Tour` event (`presentation: None`, no command — D7). Clamped
     * at `≥ 0` for parity with [recompute]; a culture-less / hostile output simply adds nothing.
     */
    fun addConcertTourInfluence(target: Civilization, multiplier: Float = CONCERT_TOUR_FACTOR) {
        val delta = max(0, (getBaseTourismOutput() * multiplier).roundToInt())
        accumulatedInfluence[target.civName] = (accumulatedInfluence[target.civName] ?: 0) + delta
    }

    /** Accumulated influence over [target] as a fraction of that rival's lifetime culture (D5). Mirrors
     *  the zero-division guard in `Victory.getMoreCountableThanOtherCivPercent`, expressed as a ratio:
     *  with no culture-defense, any positive influence is treated as Dominant-level. */
    @Readonly
    fun getInfluenceRatioOver(target: Civilization): Float {
        val accumulated = accumulatedInfluence[target.civName] ?: 0
        val defense = target.totalCultureForContests
        return if (defense <= 0) (if (accumulated > 0) 2.001f else 0f)
            else accumulated.toFloat() / defense.toFloat()
    }

    @Readonly
    fun getInfluenceLevelOver(target: Civilization): TourismInfluenceLevel =
        TourismInfluenceLevel.fromRatio(getInfluenceRatioOver(target))

    /** Cultural-victory condition (D5, used by Increment 4): Influential or better over ALL living
     *  major rivals. False when there are no living major rivals. */
    @Readonly
    fun isInfluentialOverAllMajors(): Boolean {
        val relevant = civInfo.gameInfo.civilizations.filter {
            it != civInfo && it.isMajorCiv() && !it.isDefeated()
        }
        return relevant.isNotEmpty() && relevant.all {
            getInfluenceLevelOver(it) >= TourismInfluenceLevel.Influential
        }
    }

    companion object {
        const val TOURISM_RESOURCE = "Tourism"

        /** Concert-tour burst factor (Increment 5): influence gained = tourism output × this (Civ V ×10). */
        const val CONCERT_TOUR_FACTOR = 10f

        // Per-target relationship multiplier factors (Increment 3), as tunable named constants.
        private const val OPEN_BORDERS_BONUS = 0.25f
        private const val SHARED_RELIGION_BONUS = 0.25f
        private const val SAME_IDEOLOGY_BONUS = 0.25f
        private const val DIFFERENT_IDEOLOGY_PENALTY = -0.25f
        private const val DECLARATION_OF_FRIENDSHIP_BONUS = 0.25f
        private const val RESEARCH_AGREEMENT_BONUS = 0.25f
        private const val TRADE_ROUTE_BONUS = 0.25f
    }
}
