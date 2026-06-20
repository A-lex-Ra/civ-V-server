package com.unciv.logic.automation.unit

import com.unciv.logic.city.City
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.trade.TradeRouteConnection
import com.unciv.logic.trade.TradeRouteManager
import com.unciv.logic.trade.TradeRouteType
import com.unciv.logic.trade.TradeRouteYield
import com.unciv.logic.trade.TradeRouteYields

/**
 * BNW Phase 3 — International Trade Routes. The authority's AI drives idle trade units (Caravans / Cargo
 * Ships) to establish high-value routes. Authority-only (the AI runs on the authority) so it mutates
 * canonical state through the shared establish path with NO new command.
 *
 * New ITR model: a route must originate at the unit's OWN city center. So the AI:
 *  - if the unit is on one of its own city centers, picks the best-yield reachable destination by
 *    [TradeRouteYields] (international/high-tech preferred) and records the route via the shared
 *    [TradeRouteManager.establish];
 *  - if no destination is reachable from here but another of the civ's cities can reach one, relocates
 *    toward that city; and
 *  - if the unit is NOT on an own city center, heads toward the nearest own city.
 */
object TradeUnitAutomation {

    fun automateTradeUnit(unit: MapUnit) {
        val civ = unit.civ
        val manager = civ.gameInfo.tradeRouteManager
        if (civ.cities.isEmpty()) return
        val type = if (unit.baseUnit.isLandUnit) TradeRouteType.Land else TradeRouteType.Sea
        val maxLength = TradeRouteManager.maxRouteLength(unit)

        val currentCity = unit.currentTile.takeIf { it.isCityCenter() }?.getCity()
            ?.takeIf { it.civ == civ }

        if (currentCity != null) {
            // On an own city center: try to establish the best route from here.
            val best = bestDestinationFrom(manager, currentCity, type, maxLength, unit)
            if (best != null) {
                if (manager.usedCapacity(civ.civID) < manager.getMaxCapacity(civ)) {
                    // Domestic routes carry Production by default; international ones carry gold/science.
                    val yield = if (best.civ.civID == civ.civID) TradeRouteYield.Production else TradeRouteYield.None
                    manager.establish(currentCity, best, unit, yield)
                }
                return
            }
            // Nothing reachable from here: if another of the civ's cities can reach a destination, relocate
            // the unit toward it (an instant move would consume the turn, but the AI uses normal movement).
            val betterOrigin = civ.cities.firstOrNull {
                it.id != currentCity.id && bestDestinationFrom(manager, it, type, maxLength, unit) != null
            }
            if (betterOrigin != null && unit.movement.canReach(betterOrigin.getCenterTile()))
                unit.movement.headTowards(betterOrigin.getCenterTile())
            return
        }

        // Not on an own city center: head toward the nearest own city center we can reach.
        val nearest = civ.cities
            .filter { unit.movement.canReach(it.getCenterTile()) }
            .minByOrNull { unit.currentTile.aerialDistanceTo(it.getCenterTile()) }
            ?: return
        unit.movement.headTowards(nearest.getCenterTile())
    }

    /**
     * The highest-yield reachable destination for a route of [type] from [originCity] within [maxLength],
     * scored by [TradeRouteYields.scoreYields]; null if none reachable. Skips the origin itself and any
     * city this civ already routes to.
     */
    private fun bestDestinationFrom(
        manager: TradeRouteManager, originCity: City, type: TradeRouteType, maxLength: Int, unit: MapUnit
    ): City? {
        val civ = unit.civ
        val existingDestinations = manager.getRoutesEstablishedBy(civ.civID)
            .mapTo(HashSet()) { it.destinationCityId }

        var bestCity: City? = null
        var bestScore = Int.MIN_VALUE
        for (city in civ.gameInfo.getCities()) {
            if (city.id == originCity.id || city.id in existingDestinations) continue
            val length = manager.computeRoute(originCity, city, type) ?: continue
            if (length > maxLength) continue
            val provisional = TradeRouteConnection().apply {
                originCityId = originCity.id
                destinationCityId = city.id
                ownerCivId = civ.civID
                this.type = type
                this.length = length
                establishedTurn = civ.gameInfo.turns
                unitId = unit.id
            }
            val score = TradeRouteYields.scoreYields(TradeRouteYields.computeYields(provisional, civ.gameInfo))
            if (score > bestScore) {
                bestScore = score
                bestCity = city
            }
        }
        return bestCity
    }
}
