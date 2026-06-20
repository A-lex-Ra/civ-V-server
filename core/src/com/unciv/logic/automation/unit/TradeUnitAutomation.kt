package com.unciv.logic.automation.unit

import com.unciv.logic.city.City
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.trade.TradeRouteConnection
import com.unciv.logic.trade.TradeRouteType
import com.unciv.logic.trade.TradeRouteYields
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsTrade

/**
 * BNW Phase 3 — Increment 6. The authority's AI drives idle trade units (Caravans / Cargo Ships) to
 * establish high-value International Trade Routes. Authority-only (the AI runs on the authority) so it
 * mutates canonical state through the shared establish path with NO new command.
 *
 * Picks the highest-value reachable target by [TradeRouteYields] (international/high-tech preferred), heads
 * the unit there, and — once standing ON its own destination city center or ADJACENT to a foreign one (the
 * engine forbids entering a foreign city center, so the unit "docks" next to it) — records the route via the
 * shared `TradeRouteManager.establish`, the same record path the authority's CommandExecutor uses.
 */
object TradeUnitAutomation {

    fun automateTradeUnit(unit: MapUnit) {
        val civ = unit.civ
        val manager = civ.gameInfo.tradeRouteManager
        val originCity = civ.getCapital() ?: return
        val type = if (unit.baseUnit.isLandUnit) TradeRouteType.Land else TradeRouteType.Sea
        val maxLength = UnitActionsTrade.maxRouteLength(unit)

        // Cities this civ already routes TO (don't pile multiple routes onto one destination).
        val existingDestinations = manager.getRoutesEstablishedBy(civ.civID)
            .mapTo(HashSet()) { it.destinationCityId }

        // Candidate destinations: any known/own city (other than the origin), not already a destination,
        // that a route of this type connects to within range. Pre-filter by aerial distance before BFS so
        // we don't run a BFS for obviously-too-far cities.
        val candidateCities = (civ.cities.asSequence() + civ.getKnownCivs().flatMap { it.cities.asSequence() })
            .filter { it.id != originCity.id && it.id !in existingDestinations }
            .filter { originCity.getCenterTile().aerialDistanceTo(it.getCenterTile()) <= maxLength }
            .distinctBy { it.id }
            .toList()

        // Score each reachable candidate with the same yields the route would produce.
        var bestCity: City? = null
        var bestScore = Int.MIN_VALUE
        for (city in candidateCities) {
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

        val target = bestCity ?: return
        val targetTile = target.getCenterTile()

        if (unit.currentTile.aerialDistanceTo(targetTile) <= 1) {
            // Already on (own city, distance 0) or adjacent to (foreign city, distance 1) the target.
            establishIfPossible(manager, originCity, target, type, maxLength, unit)
        } else if (unit.movement.canReach(targetTile)) {
            // headTowards moves as close as possible even when the center itself is unenterable (a
            // foreign city), so the unit ends up parked on a neighbouring tile.
            unit.movement.headTowards(targetTile)
            if (unit.currentTile.aerialDistanceTo(targetTile) <= 1)
                establishIfPossible(manager, originCity, target, type, maxLength, unit)
        }
    }

    /**
     * Record a route to [target] iff the gates still hold after movement (capacity free, the route of
     * [type] still connects within [maxLength]). Re-checked here to guard against races with other
     * authority-side establishes this turn. The AI runs on the authority, so it establishes directly via
     * the shared [com.unciv.logic.trade.TradeRouteManager.establish] — no command is emitted.
     */
    private fun establishIfPossible(
        manager: com.unciv.logic.trade.TradeRouteManager,
        originCity: City, target: City, type: TradeRouteType, maxLength: Int, unit: MapUnit
    ) {
        val civ = unit.civ
        if (manager.usedCapacity(civ.civID) >= manager.getMaxCapacity(civ)) return
        val length = manager.computeRoute(originCity, target, type) ?: return
        if (length > maxLength) return
        manager.establish(originCity, target, unit)
    }
}
