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
 * Mirrors Civ V Brave New World's split:
 *  - **International** routes (origin and destination owned by different civs) pay double owner gold,
 *    destination-owner gold, and catch-up science (by tech difference). Gold borrows the shape of Unciv's
 *    capital-connection formula (`CityStats.getStatsFromTradeRoute`) and reads the existing
 *    `[stats] from each Trade Route` / `[relativeAmount]% [stat] from Trade Routes` data so Petra / Bazaar /
 *    policy bonuses light up.
 *  - **Internal** (domestic) routes instead carry **Food or Production** ([TradeRouteConnection.internalYield])
 *    to the destination city, era-scaled (Ancient 3 → Modern+ 6; Sea routes double), and NO gold/science.
 *  - Religion pressure spreads along **either** kind of route.
 */
object TradeRouteYields {

    /** The pressure a route spreads from the origin city's majority religion toward the destination. */
    const val RELIGION_PRESSURE = 30

    /**
     * The yields produced by one route this turn. Religion is described by the spread amount plus the
     * origin city's majority-religion name (null when the origin has no majority religion or religion is
     * disabled), so the caller applies pressure only when both are present. [destFood] / [destProduction]
     * are delivered to the destination city (domestic routes only).
     */
    data class TradeRouteYieldResult(
        val ownerGold: Int,
        val ownerScience: Int,
        val destOwnerGold: Int,
        val destFood: Int,
        val destProduction: Int,
        val religionPressure: Int,
        val originReligionName: String?
    ) {
        companion object {
            val EMPTY = TradeRouteYieldResult(0, 0, 0, 0, 0, 0, null)
        }
    }

    @Readonly
    fun computeYields(c: TradeRouteConnection, gameInfo: GameInfo): TradeRouteYieldResult {
        val manager = gameInfo.tradeRouteManager
        val origin = manager.getOriginCity(c) ?: return TradeRouteYieldResult.EMPTY
        val dest = manager.getDestinationCity(c) ?: return TradeRouteYieldResult.EMPTY

        val international = origin.civ.civID != dest.civ.civID

        // Religion pressure spreads along ANY route (internal or international) toward the destination.
        val originReligionName = if (gameInfo.isReligionEnabled())
            origin.religion.getMajorityReligionName() else null
        val religionPressure = if (originReligionName != null) RELIGION_PRESSURE else 0

        if (!international) {
            // --- Domestic route: era-scaled Food OR Production to the destination city (Sea doubles) ---
            val amount = internalYieldAmount(origin, c.type)
            val food = if (c.internalYield == TradeRouteYield.Food) amount else 0
            // None defaults to Production so a mis-specified domestic route still does something useful.
            val production = if (c.internalYield != TradeRouteYield.Food) amount else 0
            return TradeRouteYieldResult(
                ownerGold = 0,
                ownerScience = 0,
                destOwnerGold = 0,
                destFood = food,
                destProduction = production,
                religionPressure = religionPressure,
                originReligionName = originReligionName
            )
        }

        // --- International owner gold (capital-connection formula shape, doubled) ---
        var ownerGold = dest.population.population * 0.15f + origin.population.population * 1.1f
        ownerGold *= 2.0f
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

        // --- Destination-owner gold (Harbor / East India Company on the dest city) ---
        var destGold = origin.population.population * 0.15f
        for (unique in dest.getMatchingUniques(UniqueType.StatsFromTradeRoute))
            destGold += unique.stats.gold
        val destOwnerGold = destGold.roundToInt()

        // --- Owner science: catch-up bonus from a more advanced destination (techDiff / 2) ---
        val techDiff = max(
            0,
            dest.civ.tech.getNumberOfTechsResearched() - origin.civ.tech.getNumberOfTechsResearched()
        )
        val ownerScience = (techDiff * 0.5f).roundToInt()

        return TradeRouteYieldResult(
            ownerGold = ownerGoldInt,
            ownerScience = ownerScience,
            destOwnerGold = destOwnerGold,
            destFood = 0,
            destProduction = 0,
            religionPressure = religionPressure,
            originReligionName = originReligionName
        )
    }

    /**
     * The base internal Food/Production a domestic route carries this era (Civ V BNW): Ancient 3,
     * Classical/Medieval/Renaissance 4, Industrial 5, Modern and later 6. Sea routes carry double. The era
     * is read off the ORIGIN civ; the integer-tier mapping is approximate on non-standard rulesets.
     */
    @Readonly
    private fun internalYieldAmount(origin: City, type: TradeRouteType): Int {
        val era = origin.civ.getEraNumber()
        val base = when {
            era <= 0 -> 3
            era <= 3 -> 4
            era == 4 -> 5
            else -> 6
        }
        return if (type == TradeRouteType.Sea) base * 2 else base
    }

    /** A single scalar "value" for ranking candidate targets in AI automation (Increment 6). */
    @Readonly
    fun scoreYields(result: TradeRouteYieldResult): Int =
        result.ownerGold + result.destOwnerGold + result.ownerScience * 3 +
            (result.destFood + result.destProduction) * 2
}
