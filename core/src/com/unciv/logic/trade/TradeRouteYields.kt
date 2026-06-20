package com.unciv.logic.trade

import com.unciv.logic.GameInfo
import com.unciv.logic.city.City
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.ui.components.extensions.toPercent
import yairm210.purity.annotations.Readonly
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * BNW Phase 3 — Increment 3. Pure computation of one [TradeRouteConnection]'s per-turn yields. Banked on
 * the authority by [TradeRouteManager.applyYieldsForOwner] (D4); kept separate so the AI (Increment 6) can
 * rank candidate targets with the SAME numbers without applying anything.
 *
 * All formulas mirror the shape of Unciv's existing capital-connection gold formula
 * (`CityStats.getStatsFromTradeRoute`: `dest.pop*0.15 + origin.pop*1.1`) but bank SEPARATELY and read the
 * existing `[stats] from each Trade Route` / `[relativeAmount]% [stat] from Trade Routes` data so Petra /
 * Bazaar / policy bonuses light up. International routes (different owning civ at origin vs destination)
 * pay double gold and additionally yield science + destination-owner gold; domestic routes pay base gold
 * only.
 */
object TradeRouteYields {

    /** The pressure a route spreads from the origin city's majority religion toward the destination. */
    const val RELIGION_PRESSURE = 30

    /**
     * The yields produced by one route this turn. Religion is described by the spread amount plus the
     * origin city's majority-religion name (null when the origin has no majority religion or religion is
     * disabled), so the caller applies pressure only when both are present.
     */
    data class TradeRouteYieldResult(
        val ownerGold: Int,
        val ownerScience: Int,
        val destOwnerGold: Int,
        val religionPressure: Int,
        val originReligionName: String?
    ) {
        companion object {
            val EMPTY = TradeRouteYieldResult(0, 0, 0, 0, null)
        }
    }

    @Readonly
    fun computeYields(c: TradeRouteConnection, gameInfo: GameInfo): TradeRouteYieldResult {
        val manager = gameInfo.tradeRouteManager
        val origin = manager.getOriginCity(c) ?: return TradeRouteYieldResult.EMPTY
        val dest = manager.getDestinationCity(c) ?: return TradeRouteYieldResult.EMPTY

        val international = origin.civ.civID != dest.civ.civID

        // --- Owner gold (mirrors the capital-connection formula shape, doubled if international) ---
        var ownerGold = dest.population.population * 0.15f + origin.population.population * 1.1f
        ownerGold *= if (international) 2.0f else 1.0f
        // Read the owner-side flat + percent Trade-Route bonuses off the ORIGIN city (Petra/Bazaar/policy).
        var goldBonus = 0f
        for (unique in origin.getMatchingUniques(UniqueType.StatsFromTradeRoute))
            goldBonus += unique.stats.gold
        ownerGold += goldBonus
        var goldPercent = 0f
        for (unique in origin.getMatchingUniques(UniqueType.StatPercentFromTradeRoutes))
            if (Stat.valueOf(unique.params[1]) == Stat.Gold)
                goldPercent += unique.params[0].toFloat()
        ownerGold *= goldPercent.toPercent()
        val ownerGoldInt = max(1, ownerGold.roundToInt())

        // --- Destination-owner gold (international only — Harbor / East India Company on the dest city) ---
        var destOwnerGold = 0
        if (international) {
            var destGold = origin.population.population * 0.15f
            for (unique in dest.getMatchingUniques(UniqueType.StatsFromTradeRoute))
                destGold += unique.stats.gold
            destOwnerGold = destGold.roundToInt()
        }

        // --- Owner science (international only): catch-up bonus from a more advanced destination ---
        var ownerScience = 0
        if (international) {
            val techDiff = max(
                0,
                dest.civ.tech.getNumberOfTechsResearched() - origin.civ.tech.getNumberOfTechsResearched()
            )
            ownerScience = (techDiff * 0.5f).roundToInt()
        }

        // --- Religion pressure toward the destination (origin city's majority religion spreads out) ---
        val originReligionName = if (gameInfo.isReligionEnabled())
            origin.religion.getMajorityReligionName() else null
        val religionPressure = if (originReligionName != null) RELIGION_PRESSURE else 0

        return TradeRouteYieldResult(
            ownerGold = ownerGoldInt,
            ownerScience = ownerScience,
            destOwnerGold = destOwnerGold,
            religionPressure = religionPressure,
            originReligionName = originReligionName
        )
    }

    /** A single scalar "value" for ranking candidate targets in AI automation (Increment 6). */
    @Readonly
    fun scoreYields(result: TradeRouteYieldResult): Int =
        result.ownerGold + result.destOwnerGold + result.ownerScience * 3
}
