package com.unciv.logic.civilization.managers

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stats

/**
 * BNW Phase 2c — Increment 5. Evaluates Great-Work **theming bonuses** for one building in one city,
 * replacing the crude "all slots filled" hidden-building flag with real matching rules (D3/§3.2).
 *
 * A building can carry one or more [UniqueType.GreatWorkThemingBonus] uniques
 * (`"Provides a Theming bonus of [stats] when its Great Works are [greatWorkThemingCondition]"`). The
 * building is **themed** when ALL its slots are filled AND every theming unique's condition holds over
 * the works in those slots (multiple uniques are AND-ed). The four conditions:
 *  - `of the same era` — all works share [GreatWork.fromEra];
 *  - `by distinct artists` — all [GreatWork.artistName] values are distinct;
 *  - `from the same civilization` — all works share [GreatWork.creatingCivName];
 *  - `all filled` — just the all-slots-filled requirement (no extra constraint).
 *
 * Theming yields a Culture (etc.) [Stats] bonus ([getThemingStats]) and a separate Tourism amount
 * ([getThemingTourism]) — Tourism is the mod's pseudo-resource, not a [com.unciv.models.stats.Stat],
 * so it is handled as a `Float` fed into the Phase 2b tourism seam, not as a `Stats` field.
 */
object GreatWorkTheming {

    /** Flat per-themed-building tourism bonus. The bundled `[stats]` Theming bonus carries Culture-type
     *  stats (Stats has no Tourism field), so the themed Tourism contribution is a simple documented
     *  constant per themed building, on top of the per-work `2f` in [GreatWorkManager.getTourismContribution]. */
    const val THEMED_TOURISM_BONUS = 2f

    /**
     * The Great Works currently filling [buildingName]'s slots in the city at [cityLocation] for [civ],
     * in slot-index order. Slots with no work are skipped, so `size < slot count` means "not all filled".
     */
    private fun worksInBuilding(civ: Civilization, buildingName: String, cityLocation: HexCoord): List<GreatWork> {
        val manager = civ.gameInfo.greatWorkManager
        return GreatWorkSlotProvider.getSlotsForCiv(civ)
            .filter { it.buildingName == buildingName && it.cityLocation == cityLocation }
            .sortedBy { it.slotIndex }
            .mapNotNull { manager.getWorkInSlot(it) }
    }

    /** The number of slots [buildingName] has in the city at [cityLocation] for [civ]. */
    private fun slotCount(civ: Civilization, buildingName: String, cityLocation: HexCoord): Int =
        GreatWorkSlotProvider.getSlotsForCiv(civ)
            .count { it.buildingName == buildingName && it.cityLocation == cityLocation }

    /**
     * Is the [buildingName] building in the city at [cityLocation] themed for [civ]?
     *
     * A building can only be *themed* if it actually declares a theming bonus, i.e. carries at least one
     * [UniqueType.GreatWorkThemingBonus] unique — a plain slot building (no theming declaration) is never
     * themed and grants no theming bonus. When it does declare one: every slot must be filled, then every
     * theming condition on the host building must hold (multiple uniques AND-ed). A building with no slots
     * is never themed.
     */
    fun isThemed(civ: Civilization, buildingName: String, cityLocation: HexCoord): Boolean {
        val slots = slotCount(civ, buildingName, cityLocation)
        if (slots == 0) return false

        val building = civ.gameInfo.ruleset.buildings[buildingName] ?: return false
        val themingUniques = building.getMatchingUniques(UniqueType.GreatWorkThemingBonus).toList()
        if (themingUniques.isEmpty()) return false // no theming bonus declared -> never "themed"

        val works = worksInBuilding(civ, buildingName, cityLocation)
        if (works.size < slots) return false // not all slots filled

        return themingUniques.all { conditionHolds(it.params[1], works) }
    }

    /** Evaluate a single theming [condition] over [works] (all-filled is the caller's precondition). */
    private fun conditionHolds(condition: String, works: List<GreatWork>): Boolean = when (condition) {
        "of the same era" -> works.map { it.fromEra }.distinct().size <= 1
        "by distinct artists" -> works.map { it.artistName }.distinct().size == works.size
        "from the same civilization" -> works.map { it.creatingCivName }.distinct().size <= 1
        "all filled" -> true
        else -> true // unknown condition: don't block theming on data we don't understand
    }

    /**
     * The declared `[stats]` Culture (etc.) bonus from every theming unique on [buildingName], summed,
     * if the building is themed for [civ]; an empty [Stats] otherwise.
     */
    fun getThemingStats(civ: Civilization, buildingName: String, cityLocation: HexCoord): Stats {
        if (!isThemed(civ, buildingName, cityLocation)) return Stats()
        val building = civ.gameInfo.ruleset.buildings[buildingName] ?: return Stats()
        val total = Stats()
        for (unique in building.getMatchingUniques(UniqueType.GreatWorkThemingBonus))
            total.add(Stats.parse(unique.params[0]))
        return total
    }

    /**
     * The Tourism contribution from theming [buildingName] for [civ]: a flat [THEMED_TOURISM_BONUS] when
     * the building is themed, else 0. (Tourism is not a [com.unciv.models.stats.Stat], so it is not read
     * from the `[stats]` bonus; the per-work base is added by [GreatWorkManager.getTourismContribution].)
     */
    fun getThemingTourism(civ: Civilization, buildingName: String, cityLocation: HexCoord): Float =
        if (isThemed(civ, buildingName, cityLocation)) THEMED_TOURISM_BONUS else 0f
}
