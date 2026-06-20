package com.unciv.logic.trade

import com.unciv.logic.GameInfo
import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.map.BFS
import com.unciv.logic.map.mapunit.MapUnit
import yairm210.purity.annotations.Readonly

/**
 * BNW Phase 3 (International Trade Routes) — Increment 1. The authoritative, GameInfo-level registry of
 * every established city↔city *International Trade Route* (D1), mirroring [com.unciv.logic.GameInfo.religions]
 * / [com.unciv.logic.civilization.managers.GreatWorkManager] as a single source of truth for a bilateral
 * mechanic.
 *
 * ### NOT the capital-connection "trade routes"
 * Unciv already has a DIFFERENT, pre-existing mechanic also called "trade routes": city→capital
 * road/harbor *connections* for gold ([com.unciv.logic.civilization.transients.CapitalConnectionsFinder],
 * `CityStats.getStatsFromTradeRoute`, `City.isConnectedToCapital`, [com.unciv.models.ruleset.unique.UniqueType.StatsFromTradeRoute]).
 * That is left entirely untouched. ITR uses the distinct term **"Trade Route Connection"** and banks its
 * yields SEPARATELY (D4); it only *reads* the existing `[stats] from each Trade Route` data to light up
 * Petra/Bazaar/Harbor bonuses ([TradeRouteYields]).
 *
 * ### Capacity = stockpiled-token count, Venice for free
 * Capacity is simply how many `Trade Route` stockpile tokens the civ holds ([getMaxCapacity]); techs and
 * buildings grant `Instantly provides [1] [Trade Route]`, and Venice's `Double the normal number of Trade
 * Routes available` nation tag grants a second token per source — so Venice's doubling flows in for free,
 * no special-casing here. Establishing a route does NOT consume a token; capacity is enforced by
 * `usedCapacity < getMaxCapacity`.
 *
 * Serializes via [IsPartOfGameInfoSerialization]: only the [connections] list (of serializable
 * [TradeRouteConnection]) persists; a default-constructed manager (empty list) is a valid empty state, so
 * an old save with no `tradeRouteManager` deserializes into a fresh empty manager. [clone] deep-copies
 * each connection; [setTransients] re-attaches the gameInfo.
 */
class TradeRouteManager : IsPartOfGameInfoSerialization {

    @Transient
    lateinit var gameInfo: GameInfo

    var connections = ArrayList<TradeRouteConnection>()

    fun clone(): TradeRouteManager {
        val toReturn = TradeRouteManager()
        for (connection in connections) toReturn.connections.add(connection.clone())
        return toReturn
    }

    fun setTransients(gameInfo: GameInfo) {
        this.gameInfo = gameInfo
    }

    //region Lookups (pure reads)

    /** The stockpiled resource token name that gates and counts trade-route capacity. */
    @Readonly
    fun getMaxCapacity(civ: Civilization): Int = civ.getResourceAmount(TRADE_ROUTE_RESOURCE)

    /** All routes established (owned) by [civId]. */
    @Readonly
    fun getRoutesEstablishedBy(civId: String): List<TradeRouteConnection> =
        connections.filter { it.ownerCivId == civId }

    /** All routes whose origin OR destination is the city with [cityId]. */
    @Readonly
    fun getRoutesTouchingCity(cityId: String): List<TradeRouteConnection> =
        connections.filter { it.originCityId == cityId || it.destinationCityId == cityId }

    /** Number of routes [civId] currently has established (counts against [getMaxCapacity]). */
    @Readonly
    fun usedCapacity(civId: String): Int = getRoutesEstablishedBy(civId).size

    /** Resolve the origin [City] of [c] off the canonical game, or null if it was removed. */
    @Readonly
    fun getOriginCity(c: TradeRouteConnection): City? = findCity(c.originCityId)

    /** Resolve the destination [City] of [c] off the canonical game, or null if it was removed. */
    @Readonly
    fun getDestinationCity(c: TradeRouteConnection): City? = findCity(c.destinationCityId)

    /** Resolve the owning [Civilization] of [c], or null if it no longer exists. */
    @Readonly
    fun getOwnerCiv(c: TradeRouteConnection): Civilization? =
        gameInfo.getCivilizationOrNull(c.ownerCivId)

    @Readonly
    private fun findCity(cityId: String): City? {
        if (cityId.isEmpty()) return null
        return gameInfo.getCities().firstOrNull { it.id == cityId }
    }

    //endregion
    //region Connectivity (BFS — not @Readonly: BFS is @InternalState and mutates)

    /**
     * The path length in tiles between [originCity] and [destCity] for a route of the given [type], or
     * `null` if no route of that type exists. Uses [com.unciv.logic.map.BFS] from the origin city's center
     * tile (we never hand-roll the traversal) with a type-specific passability predicate:
     *  - [TradeRouteType.Land]: the tile is land AND enterable by the owner's diplomacy (own/open-borders
     *    territory), reusing the [com.unciv.logic.civilization.transients.CapitalConnectionsFinder]
     *    `canEnterBordersOf` shape. City centers are always passable.
     *  - [TradeRouteType.Sea]: the tile is water OR a city center.
     *
     * Length is the number of tiles on the path returned by [BFS.getPathTo] (includes both endpoints).
     */
    fun computeRoute(originCity: City, destCity: City, type: TradeRouteType): Int? {
        val originTile = originCity.getCenterTile()
        val destTile = destCity.getCenterTile()
        val ownerCiv = originCity.civ

        // A city center is always passable (a route may terminate at, or pass through, a city —
        // including a foreign destination you want to trade with, even without open borders). Crossing
        // non-city LAND requires the owner's diplomacy (own / neutral / open-borders / friendly CS),
        // reusing the CapitalConnectionsFinder.canEnterBordersOf shape. Sea routes hop water + cities.
        val bfs = when (type) {
            TradeRouteType.Land -> BFS(originTile) { tile ->
                tile.isCityCenter() || (tile.isLand && canEnterBordersOf(ownerCiv, tile))
            }
            TradeRouteType.Sea -> BFS(originTile) { tile ->
                tile.isWater || tile.isCityCenter()
            }
        }
        bfs.stepUntilDestination(destTile)
        if (!bfs.hasReachedTile(destTile)) return null
        return bfs.getPathTo(destTile).count()
    }

    /** The diplomatic-passability shape of [CapitalConnectionsFinder.canEnterBordersOf]. */
    @Readonly
    private fun canEnterBordersOf(civInfo: Civilization, tile: com.unciv.logic.map.tile.Tile): Boolean {
        val owner = tile.getOwner() ?: return true // neutral land is enterable
        if (owner == civInfo) return true
        if (owner.isBarbarian || civInfo.isBarbarian) return false
        val diplomacyManager = civInfo.getDiplomacyManager(owner) ?: return false // not met yet
        if (owner.isCityState && !civInfo.isAtWarWith(owner)) return true
        return diplomacyManager.hasOpenBorders
    }

    //endregion
    //region Establishment (Increment 2)

    /**
     * Build and register the trade-route connection from [originCity] to [destCity] established by the
     * trade [unit]. Shared by the UI action lambda and the authority's [com.unciv.logic.multiplayer.v3.command.CommandExecutor.executeEstablishTradeRoute]
     * AFTER both have validated capacity / connectivity / type. The caller has already verified
     * [computeRoute] is non-null.
     *
     * The route's [TradeRouteConnection.type] is derived from the unit's movement type (D6); the parked
     * unit's id is recorded so it can be plundered (Increment 4). The unit parks (movement spent, no
     * ongoing action) but is NOT consumed, and NO `Trade Route` token is spent (capacity is enforced by
     * count).
     */
    fun establish(originCity: City, destCity: City, unit: MapUnit): TradeRouteConnection {
        val type = if (unit.baseUnit.isLandUnit) TradeRouteType.Land else TradeRouteType.Sea
        val length = computeRoute(originCity, destCity, type) ?: 0
        val connection = TradeRouteConnection().apply {
            originCityId = originCity.id
            destinationCityId = destCity.id
            ownerCivId = unit.civ.civID
            this.type = type
            this.length = length
            establishedTurn = gameInfo.turns
            unitId = unit.id
        }
        connections.add(connection)
        unit.currentMovement = 0f
        unit.action = null
        return connection
    }

    //endregion
    //region Yields (Increment 3)

    /**
     * Apply, ONCE per owner per turn, every yield from the routes [civ] established. Called from
     * [com.unciv.logic.civilization.managers.TurnManager.endTurn] right after gold/science are banked, and
     * banked directly (D4) — NOT routed through CityStats. Iterating only by OWNER avoids double-counting
     * the destination-owner gold.
     *
     *  - owner gold → `civ.addGold` (always),
     *  - owner science → `civ.tech.addScience` (only if the civ has at least one city — a city-less civ
     *    has no current research),
     *  - destination-owner gold → that civ's `addGold` (international routes only; skipped if same owner
     *    or the destination city was removed),
     *  - religion pressure → the destination city's `religion.addPressure` toward the origin's majority
     *    religion (guarded by religion-enabled inside `addPressure`).
     */
    fun applyYieldsForOwner(civ: Civilization) {
        for (connection in getRoutesEstablishedBy(civ.civID)) {
            val yields = TradeRouteYields.computeYields(connection, gameInfo)

            civ.addGold(yields.ownerGold)
            if (civ.cities.isNotEmpty() && yields.ownerScience != 0)
                civ.tech.addScience(yields.ownerScience)

            val destCity = getDestinationCity(connection)
            if (destCity != null) {
                val destOwner = destCity.civ
                if (yields.destOwnerGold != 0 && destOwner.civID != civ.civID)
                    destOwner.addGold(yields.destOwnerGold)
                if (yields.religionPressure > 0 && yields.originReligionName != null)
                    destCity.religion.addPressure(yields.originReligionName, yields.religionPressure)
            }
        }
    }

    //endregion
    //region Expiry / plunder / city-loss (Increment 4)

    /**
     * Per-turn maintenance for [civ]'s routes (called from [com.unciv.logic.civilization.managers.TurnManager.startTurn]
     * per owner): any route at/over its [routeDurationTurns] is **renewed** (its establishedTurn is reset)
     * when it is still valid — the bound unit lives, the path still computes, capacity is fine and we are
     * not at war with the destination owner — otherwise it is **dropped** with a notification. Iterating by
     * owner keeps each route processed once per turn.
     */
    fun processExpiryAndRenewal(civ: Civilization) {
        val duration = routeDurationTurns()
        // Copy so dropping a route mid-loop is safe.
        for (connection in getRoutesEstablishedBy(civ.civID).toList()) {
            if (gameInfo.turns - connection.establishedTurn < duration) continue

            if (isStillValid(connection, civ)) {
                connection.establishedTurn = gameInfo.turns // renew
            } else {
                val destCity = getDestinationCity(connection)
                connections.remove(connection)
                civ.addNotification(
                    "Your trade route to [${destCity?.name ?: "a lost city"}] has expired",
                    NotificationCategory.Trade, "OtherIcons/Trade"
                )
            }
        }
    }

    // Not @Readonly: transitively runs computeRoute (BFS mutates).
    private fun isStillValid(connection: TradeRouteConnection, civ: Civilization): Boolean {
        val unitAlive = connection.unitId != -1 &&
            civ.units.getCivUnits().any { it.id == connection.unitId }
        if (!unitAlive) return false
        val origin = getOriginCity(connection) ?: return false
        val dest = getDestinationCity(connection) ?: return false
        if (usedCapacity(civ.civID) > getMaxCapacity(civ)) return false
        val destOwner = dest.civ
        if (destOwner.civID != civ.civID && civ.isAtWarWith(destOwner)) return false
        return computeRouteIsValid(origin, dest, connection.type)
    }

    /** computeRoute can mutate a BFS, so this can't be @Readonly. */
    private fun computeRouteIsValid(origin: City, dest: City, type: TradeRouteType): Boolean =
        computeRoute(origin, dest, type) != null

    /** Drop [c] from the registry. */
    fun removeRoute(c: TradeRouteConnection) {
        connections.remove(c)
    }

    /** Drop every route whose origin OR destination is [cityId]; used on city loss/raze (Increment 4). */
    fun removeRoutesTouchingCity(cityId: String) {
        connections.removeAll { it.originCityId == cityId || it.destinationCityId == cityId }
    }

    /**
     * Drop every route bound to the parked trade unit [unitId] (used when that unit dies — Increment 4).
     * Returns the dropped routes so the caller can notify the owner ("plundered"). Benign for non-trade
     * units (no route has their id).
     */
    fun removeRoutesForUnit(unitId: Int): List<TradeRouteConnection> {
        if (unitId == -1) return emptyList()
        val dropped = connections.filter { it.unitId == unitId }
        if (dropped.isNotEmpty()) connections.removeAll(dropped)
        return dropped
    }

    //endregion

    companion object {
        const val TRADE_ROUTE_RESOURCE = "Trade Route"

        /** Base duration of a trade route before expiry/renewal, in standard-speed turns. */
        const val ROUTE_DURATION_TURNS = 30
    }

    /** [ROUTE_DURATION_TURNS] scaled by the game speed, the same accessor other durations use. */
    @Readonly
    private fun routeDurationTurns(): Int =
        (ROUTE_DURATION_TURNS * gameInfo.speed.modifier.coerceAtLeast(1f)).toInt()
}
