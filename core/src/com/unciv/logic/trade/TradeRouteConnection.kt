package com.unciv.logic.trade

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.map.HexCoord

/**
 * BNW Phase 3 (International Trade Routes) — the route's travel medium. Derived from the establishing
 * unit's movement type ([com.unciv.models.ruleset.unit.BaseUnit.isLandUnit]): a Caravan establishes a
 * [Land] route, a Cargo Ship a [Sea] route (D6). Serialized as a plain enum (a primitive in gdx Json).
 */
enum class TradeRouteType { Land, Sea }

/**
 * BNW Phase 3 (International Trade Routes) — what a route carries. **International** routes (origin and
 * destination owned by different civs) always carry gold/science/religion and ignore this field, so they
 * use [None]. **Internal** (domestic) routes carry either [Food] or [Production] to the destination city
 * (the player's choice when establishing), era-scaled by [TradeRouteYields]. Serialized as a plain enum.
 */
enum class TradeRouteYield { None, Food, Production }

/**
 * BNW Phase 3 — Increment 1. One established city↔city *International Trade Route* (the BNW mechanic,
 * NOT Unciv's pre-existing capital-connection "trade routes" — see [TradeRouteManager] header). The
 * single source of truth for all live routes is the GameInfo-level [TradeRouteManager.connections]
 * list (D1); this is a flat, serializable record in that list.
 *
 * Cities and the owning civ are referenced by their **stable string ids** ([com.unciv.logic.city.City.id],
 * [com.unciv.logic.civilization.Civilization.civID]) rather than object references or coordinates (D2),
 * so a route survives serialization and re-attaches cleanly via [TradeRouteManager.setTransients]. The
 * establishing [unitId] ([com.unciv.logic.map.mapunit.MapUnit.id]) is kept so the route can be dropped /
 * plundered when that parked unit dies (Increment 4).
 *
 * Every field defaults to a valid empty state, so a default-constructed instance round-trips through gdx
 * Json (which fills any missing field with the default) without special handling. No `@Transient civInfo`
 * — resolution helpers on [TradeRouteManager] take the gameInfo as a parameter.
 */
class TradeRouteConnection : IsPartOfGameInfoSerialization {
    /** Stable [com.unciv.logic.city.City.id] of the origin city (the city the trade unit established from). */
    var originCityId = ""
    /** Stable [com.unciv.logic.city.City.id] of the destination city. */
    var destinationCityId = ""
    /** Stable [com.unciv.logic.civilization.Civilization.civID] of the civ that established (owns) the route. */
    var ownerCivId = ""
    var type = TradeRouteType.Land
    /** Path length in tiles between origin and destination, as computed by [TradeRouteManager.computeRoute]. */
    var length = 0
    /** [com.unciv.logic.GameInfo.turns] when the route was established — drives expiry/renewal (Increment 4). */
    var establishedTurn = 0
    /** [com.unciv.logic.map.mapunit.MapUnit.id] of the parked trade unit, or -1 if none/unknown. */
    var unitId = -1
    /** For a domestic route, what it carries to the destination ([TradeRouteYield.Food]/[TradeRouteYield.Production]);
     *  [TradeRouteYield.None] for an international route (which always carries gold/science/religion instead). */
    var internalYield = TradeRouteYield.None

    /**
     * The full tile path the bound trade unit shuttles along, origin city center → destination city center
     * (both endpoints inclusive), computed once at establishment by [TradeRouteManager.computePath]. A Civ V
     * trade unit travels its route automatically; [TradeRouteManager.advanceTradeUnitsForOwner] walks the
     * unit one step along this path each turn (bouncing at the ends). Empty for a degenerate/legacy route,
     * in which case the unit simply stays put. Stored (not recomputed) so the route the player saw is stable
     * even if terrain/ownership shifts mid-route — mirroring [com.unciv.logic.map.mapunit.MapUnit.automatedRoadConnectionPath].
     */
    var path = ArrayList<HexCoord>()
    /** Index into [path] of the bound unit's current tile (it always equals where the unit stands). */
    var pathPosition = 0
    /** Travel direction along [path]: true = heading toward the destination, false = back toward the origin. */
    var movingToDestination = true

    fun clone(): TradeRouteConnection {
        val toReturn = TradeRouteConnection()
        toReturn.originCityId = originCityId
        toReturn.destinationCityId = destinationCityId
        toReturn.ownerCivId = ownerCivId
        toReturn.type = type
        toReturn.length = length
        toReturn.establishedTurn = establishedTurn
        toReturn.unitId = unitId
        toReturn.internalYield = internalYield
        toReturn.path = ArrayList(path)
        toReturn.pathPosition = pathPosition
        toReturn.movingToDestination = movingToDestination
        return toReturn
    }
}
