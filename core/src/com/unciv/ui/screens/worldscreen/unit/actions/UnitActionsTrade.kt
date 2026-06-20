package com.unciv.ui.screens.worldscreen.unit.actions

import com.unciv.UncivGame
import com.unciv.logic.city.City
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.trade.TradeRouteConnection
import com.unciv.logic.trade.TradeRouteManager
import com.unciv.logic.trade.TradeRouteType
import com.unciv.logic.trade.TradeRouteYields
import com.unciv.models.UnitAction
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.network.command.GameCommand

/**
 * BNW Phase 3 — Increment 2. The *Establish Trade Route* unit action, sibling of [UnitActionsReligion].
 *
 * **Headless-safe by design** (mirrors how [UnitActionsReligion] keeps its lambda headless): neither the
 * getter nor the `action` lambda touches `GUI`/`WorldScreen`, so the authority and tests can invoke it
 * without a UI. The lambda forwards the v3 intent (only when a v3 game manager exists) and then calls the
 * shared [TradeRouteManager.establish] — the same path the authority's
 * [com.unciv.logic.multiplayer.v3.command.CommandExecutor.executeEstablishTradeRoute] uses after its own
 * validation.
 *
 * **Origin = the owner's capital** (`unit.civ.getCapital()`). The BNW "change home city" scaffolding is
 * explicitly deferred (a documented fidelity gap), so every route a unit establishes originates at the
 * civ's capital regardless of where the unit was built.
 */
object UnitActionsTrade {

    /** Land tile budget for a Caravan route; Sea is longer for a Cargo Ship. Extended by data uniques. */
    const val BASE_LAND_ROUTE_LENGTH = 12
    const val BASE_SEA_ROUTE_LENGTH = 20

    /**
     * A unit is a "trade unit" (D6) when its data carries the `Costs [1] [Trade Route]` cost OR the
     * explicit [UniqueType.EstablishTradeRoute] marker — never hardcoded by name (Caravan/Cargo Ship).
     */
    fun isTradeUnit(unit: MapUnit): Boolean {
        if (unit.hasUnique(UniqueType.EstablishTradeRoute)) return true
        return unit.getMatchingUniques(UniqueType.CostsResources)
            .any { it.params.size >= 2 && it.params[1] == TradeRouteManager.TRADE_ROUTE_RESOURCE }
    }

    /** Route length budget for [unit] by type, extended by its bonus-range uniques (read by data). */
    fun maxRouteLength(unit: MapUnit): Int {
        val base = if (unit.baseUnit.isLandUnit) BASE_LAND_ROUTE_LENGTH else BASE_SEA_ROUTE_LENGTH
        // Caravans/Cargo Ships gain extra range via a "[n] Movement" range unique on BNW data; we read
        // the unit's current movement as a proxy bonus so range scales with the data-driven extended
        // range (e.g. Persia's "Caravans gain 50% extended range" adds movement → adds budget).
        val movementBonus = (unit.baseUnit.movement - 2).coerceAtLeast(0)
        return base + movementBonus * 2
    }

    fun getEstablishTradeRouteActions(unit: MapUnit, tile: Tile): Sequence<UnitAction> {
        if (!isTradeUnit(unit)) return emptySequence()

        val originCity = unit.civ.getCapital() ?: return emptySequence()

        val manager = unit.civ.gameInfo.tradeRouteManager
        val type = if (unit.baseUnit.isLandUnit) TradeRouteType.Land else TradeRouteType.Sea

        // A caravan/cargo ship establishes a route when it stands ON its own destination city center OR
        // ADJACENT to a (possibly foreign) destination city center — the engine forbids entering a foreign
        // city center, so "docking" on a neighbouring tile is how international routes are reached.
        // Candidate destinations are the city centers on the unit's tile and on each of its neighbours.
        val candidateCities = (sequenceOf(tile) + tile.neighbors)
            .filter { it.isCityCenter() }
            .mapNotNull { it.getCity() }
            .filter { it.id != originCity.id }
            .distinctBy { it.id }
            .toList()

        // No city on/adjacent at all: the action is not even plausible here.
        if (candidateCities.isEmpty()) return emptySequence()

        // ESTABLISHABLE = capacity free, the route of this type connects, within length budget, and the
        // unit still has movement. Pick the BEST establishable candidate by route yield score (the same
        // numbers TradeUnitAutomation ranks by), tie-broken by city id for determinism.
        val capacityFree = manager.usedCapacity(unit.civ.civID) < manager.getMaxCapacity(unit.civ)
        val maxLength = maxRouteLength(unit)
        val chosenDest: City? = if (!capacityFree || !unit.hasMovement()) null else
            candidateCities
                .mapNotNull { city ->
                    val length = manager.computeRoute(originCity, city, type) ?: return@mapNotNull null
                    if (length > maxLength) return@mapNotNull null
                    val provisional = TradeRouteConnection().apply {
                        originCityId = originCity.id
                        destinationCityId = city.id
                        ownerCivId = unit.civ.civID
                        this.type = type
                        this.length = length
                        establishedTurn = unit.civ.gameInfo.turns
                        unitId = unit.id
                    }
                    val score = TradeRouteYields.scoreYields(
                        TradeRouteYields.computeYields(provisional, unit.civ.gameInfo))
                    Triple(city, score, city.id)
                }
                .maxWithOrNull(compareBy({ it.second }, { it.third }))
                ?.first

        return sequenceOf(UnitAction(
            UnitActionType.EstablishTradeRoute,
            useFrequency = 70f,
            action = (chosenDest?.let { dest ->
                {
                    // EXPERIMENTAL / PREVIEW (multiplayer-v3): route the establish intent to the authority,
                    // keyed by acting civ + the unit's current tile and the destination city center. Sent
                    // before the local establish; gate only on v2 != null, then FALL THROUGH to apply locally.
                    val v2 = UncivGame.Current.v3GameManager
                    if (v2 != null) {
                        v2.sendCommand(GameCommand.EstablishTradeRoute(
                            unitX = unit.currentTile.position.x, unitY = unit.currentTile.position.y,
                            destCityX = dest.location.x, destCityY = dest.location.y
                        ))
                    }
                    manager.establish(originCity, dest, unit)
                    Unit
                }
            })
        ))
    }
}
