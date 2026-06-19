package com.unciv.logic.civilization.managers

import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.GreatWorkType
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.unique.UniqueType

/**
 * BNW Phase 2c — Increment 2. Derives a civ's existing Great-Work slots from its built-building data
 * (D2/D3). Slots are never stored: this is the single authority that recomputes them, and the **only**
 * place that parses the bundled hidden-building name pattern — no other code may parse building names.
 *
 * Two slot sources per built building, in priority order:
 *  1. **Preferred — data unique** [UniqueType.ProvidesGreatWorkSlots] (`"Provides [amount]
 *     [greatWorkSlotType] Great Work slots"`): emits `amount` slots of that type, slotIndex `0..amount-1`, attributed
 *     to the carrying building. Real visible buildings/wonders carry this once Increment 6 lands.
 *  2. **Fallback (D3)** — the bundled hidden sub-buildings named `[<host>] [Great Work of <Type>]`
 *     (single → slotIndex 0) or `[<host>] [Great Work of <Type>] <n>` (numbered → slotIndex n-1). The
 *     slot is attributed to the *host* building name (the bracketed prefix), not the hidden building.
 *
 * A building that carries the unique is read via (1) only — its name is not also parsed by (2) — so a
 * future host carrying the unique won't double-count.
 */
object GreatWorkSlotProvider {

    /** Matches the bundled hidden slot buildings, capturing host, type word, and optional 1-based index.
     *  E.g. `[The Louvre] [Great Work of Art] 3` → host="The Louvre", type="Art", index="3". */
    private val hiddenSlotBuildingRegex =
        Regex("""^\[(.+?)] \[Great Work of (Writing|Art|Music)](?: (\d+))?$""")

    /** Every Great-Work slot that currently exists for [civ], across all its cities. */
    fun getSlotsForCiv(civ: Civilization): List<GreatWorkSlot> {
        val result = ArrayList<GreatWorkSlot>()
        for (city in civ.cities) {
            for (building in city.cityConstructions.getBuiltBuildings()) {
                val fromUnique = building.getMatchingUniques(UniqueType.ProvidesGreatWorkSlots).toList()
                if (fromUnique.isNotEmpty()) {
                    // (1) Data-driven: one unique can declare several slots of a single type.
                    for (unique in fromUnique) {
                        val amount = unique.params[0].toIntOrNull() ?: continue
                        val slotType = parseSlotType(unique.params[1]) ?: continue
                        for (index in 0 until amount)
                            result.add(GreatWorkSlot(civ.civName, city.location, building.name, index, slotType))
                    }
                } else {
                    // (2) Fallback: derive a single slot from the hidden building's name pattern.
                    val slot = parseHiddenSlotBuilding(civ, city.location, building.name)
                    if (slot != null) result.add(slot)
                }
            }
        }
        return result
    }

    /**
     * The free slots of [civ] that can accept a work of [type], ordered capital-first then by city
     * founded order (which is `civ.cities` insertion order). A slot is free when its [GreatWorkSlot.key]
     * is absent from [GreatWorkManager.slotPlacements]; acceptance uses [GreatWorkType.fitsSlot].
     */
    fun getFreeSlotsForCiv(civ: Civilization, type: GreatWorkType): List<GreatWorkSlot> {
        val placements = civ.gameInfo.greatWorkManager.slotPlacements
        val capital = civ.getCapital(firstCityIfNoCapital = false)
        // Stable ordering by city: capital first, then the remaining cities in their existing order.
        val cityOrder = civ.cities.withIndex().associate { (index, city) ->
            city.location to (if (city == capital) -1 else index)
        }
        return getSlotsForCiv(civ)
            .filter { it.key() !in placements && type.fitsSlot(it.slotType) }
            .sortedWith(compareBy({ cityOrder[it.cityLocation] ?: Int.MAX_VALUE }, { it.buildingName }, { it.slotIndex }))
    }

    /**
     * BNW Phase 2c — Increment 4. Cheap probe: does [ruleset] use the Great-Work slot concept at all?
     * True when any building either carries the [UniqueType.ProvidesGreatWorkSlots] data unique
     * (the durable path, Increment 6) OR is one of the bundled hidden slot sub-buildings whose name
     * matches the [hiddenSlotBuildingRegex] pattern (the D3 fallback). A non-BNW ruleset has neither, so
     * the Great-Work creation path is skipped and the legacy stockpile path is left untouched.
     *
     * Result is intrinsic to the ruleset (independent of any civ's built buildings), so callers may
     * cache it per ruleset if they wish; this scan is cheap relative to a Great-Person action.
     */
    fun rulesetHasGreatWorkSlots(ruleset: Ruleset): Boolean =
        ruleset.buildings.values.any { building ->
            building.hasUnique(UniqueType.ProvidesGreatWorkSlots) ||
                hiddenSlotBuildingRegex.matches(building.name)
        }

    /** "Writing"/"Art"/"Music" → the matching [GreatWorkType]; `null` if unrecognized. */
    private fun parseSlotType(word: String): GreatWorkType? =
        GreatWorkType.entries.firstOrNull { it.name == word }

    /** Parses a hidden slot building name into a [GreatWorkSlot] attributed to the host, or `null`. */
    private fun parseHiddenSlotBuilding(
        civ: Civilization,
        cityLocation: com.unciv.logic.map.HexCoord,
        buildingName: String
    ): GreatWorkSlot? {
        val match = hiddenSlotBuildingRegex.matchEntire(buildingName) ?: return null
        val hostName = match.groupValues[1]
        val slotType = parseSlotType(match.groupValues[2]) ?: return null
        // Single (no trailing number) → index 0; numbered n → index n-1.
        val numberText = match.groupValues[3]
        val slotIndex = if (numberText.isEmpty()) 0 else numberText.toInt() - 1
        return GreatWorkSlot(civ.civName, cityLocation, hostName, slotIndex, slotType)
    }
}
