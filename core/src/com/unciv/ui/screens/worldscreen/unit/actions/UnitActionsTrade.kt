package com.unciv.ui.screens.worldscreen.unit.actions

import com.unciv.UncivGame
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.trade.TradeRouteManager
import com.unciv.logic.trade.TradeRouteType
import com.unciv.models.UnitAction
import com.unciv.models.UnitActionType
import com.unciv.ui.screens.pickerscreens.TradeRoutePickerScreen
import com.unciv.ui.screens.pickerscreens.TradeUnitMoveCityScreen

/**
 * BNW Phase 3 — International Trade Routes. The *Establish Trade Route* and *Move to City* unit actions for
 * trade units (Caravans / Cargo Ships), sibling of [UnitActionsReligion].
 *
 * **Headless-safe getters** (mirroring [UnitActionsReligion]): neither getter touches `GUI`/`WorldScreen`,
 * so the authority and tests can enumerate them. The enabled `action` lambdas DO push a picker screen
 * ([TradeRoutePickerScreen] / [TradeUnitMoveCityScreen]) which then forwards the v3 intent and applies the
 * shared engine path — the same convention `addDisbandAction`/`getGiftActions` use.
 *
 * **Origin = the unit's CURRENT city.** A trade unit must be standing ON one of its own city centers to
 * establish a route; the route originates at THAT city (not the capital). The player picks the destination
 * from a list ([TradeRoutePickerScreen]).
 */
object UnitActionsTrade {

    /** Land tile budget for a Caravan route; kept for source compatibility — see [TradeRouteManager]. */
    const val BASE_LAND_ROUTE_LENGTH = TradeRouteManager.BASE_LAND_ROUTE_LENGTH
    const val BASE_SEA_ROUTE_LENGTH = TradeRouteManager.BASE_SEA_ROUTE_LENGTH

    /** A unit is a "trade unit" (D6) — delegates to the data-driven predicate now living in core. */
    fun isTradeUnit(unit: MapUnit) = unit.isTradeUnit()

    /** Route length budget for [unit] — delegates to the logic-layer [TradeRouteManager.maxRouteLength]. */
    fun maxRouteLength(unit: MapUnit): Int = TradeRouteManager.maxRouteLength(unit)

    /**
     * The *Establish Trade Route* action. The unit must stand ON one of its own city centers (the route
     * origin); the player then picks a reachable destination from [TradeRoutePickerScreen].
     */
    fun getEstablishTradeRouteActions(unit: MapUnit, tile: Tile): Sequence<UnitAction> {
        if (!isTradeUnit(unit)) return emptySequence()

        // Must be on its OWN city center — that city is the route's origin.
        val originCity = tile.getCity()
        if (!tile.isCityCenter() || originCity == null || originCity.civ != unit.civ)
            return emptySequence()

        val manager = unit.civ.gameInfo.tradeRouteManager
        val type = if (unit.baseUnit.isLandUnit) TradeRouteType.Land else TradeRouteType.Sea

        // ENABLED = capacity free, the unit still has movement, and at least one valid destination exists
        // (a city != origin that a route of this type connects to within the unit's range budget).
        val capacityFree = manager.usedCapacity(unit.civ.civID) < manager.getMaxCapacity(unit.civ)
        val maxLength = maxRouteLength(unit)
        val hasDestination = capacityFree && unit.hasMovement() &&
            unit.civ.gameInfo.getCities().any { dest ->
                if (dest.id == originCity.id) return@any false
                val length = manager.computeRoute(originCity, dest, type) ?: return@any false
                length <= maxLength
            }

        return sequenceOf(UnitAction(
            UnitActionType.EstablishTradeRoute,
            useFrequency = 70f,
            // Enabled only when a destination is actually establishable; null (greyed) otherwise so the
            // player still sees the action and gets feedback.
            action = (if (!hasDestination) null else {
                {
                    UncivGame.Current.pushScreen(TradeRoutePickerScreen(unit, originCity))
                    Unit
                }
            })
        ))
    }

    /**
     * The *Move to City* action: instantly relocate this trade unit to another of its own cities (the move
     * consumes its whole turn). The player picks the target from [TradeUnitMoveCityScreen].
     */
    fun getMoveToCityActions(unit: MapUnit, tile: Tile): Sequence<UnitAction> {
        if (!isTradeUnit(unit)) return emptySequence()

        // Candidate own cities: any of this civ's cities other than the one under the unit, whose center is
        // not already holding a trade unit (two trade units may not share a tile).
        val candidates = unit.civ.cities.filter { it.getCenterTile() != tile && it.getCenterTile().tradeUnit == null }
        if (candidates.isEmpty()) {
            // Still surface a greyed action so the player sees the option exists but has no valid target.
            return sequenceOf(UnitAction(UnitActionType.MoveTradeUnit, useFrequency = 65f, action = null))
        }

        return sequenceOf(UnitAction(
            UnitActionType.MoveTradeUnit,
            useFrequency = 65f,
            action = (if (!unit.hasMovement()) null else {
                {
                    UncivGame.Current.pushScreen(TradeUnitMoveCityScreen(unit))
                    Unit
                }
            })
        ))
    }
}
