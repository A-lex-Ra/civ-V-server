package com.unciv.logic.civilization.managers

import com.unciv.logic.map.HexCoord
import com.unciv.models.ruleset.GreatWorkType

/**
 * BNW Phase 2c — Increment 1. The identity of one Great-Work slot in one building in one city (D2).
 *
 * Slots are **derived**, never stored: [GreatWorkSlotProvider] recomputes the set of existing slots
 * on demand from built-building data. Only a slot's *contents* are serialized, keyed by [key] in
 * [GreatWorkManager.slotPlacements]. [key] is a flat `String` so gdx Json needs no map-key handler,
 * and it is the single source of slot identity across all increments — never re-derive it elsewhere.
 *
 * - `civId` is [com.unciv.logic.civilization.Civilization.civName] (the same identifier used for
 *   ownership everywhere else in the engine).
 * - `cityLocation` is the host city's [com.unciv.logic.city.City.location] ([HexCoord]).
 */
data class GreatWorkSlot(
    val civId: String,
    val cityLocation: HexCoord,
    val buildingName: String,
    val slotIndex: Int,
    val slotType: GreatWorkType
) {
    fun key(): String = "$civId|${cityLocation.x},${cityLocation.y}|$buildingName|$slotIndex"
}
