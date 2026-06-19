package com.unciv.logic.civilization.managers

import com.unciv.logic.GameInfo
import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.civilization.Civilization

/**
 * BNW Phase 2c — Increment 1. The authoritative, GameInfo-level registry of every [GreatWork] in the
 * game and where each one sits (D1).
 *
 * **AUTHORITY-ONLY GameInfo state.** Holding the canonical registry once at [GameInfo] (rather than a
 * per-city store the engine lacks) keeps cross-civ ownership/movement correct: a work's owner and
 * location are *derived* from which slot holds it ([slotPlacements]), not duplicated on the work.
 *
 * Two serialized maps:
 *  - [works]: every work by stable [GreatWork.id].
 *  - [slotPlacements]: [GreatWorkSlot.key] → [GreatWork.id]. Slots themselves are NOT stored — they
 *    are recomputed from building data by [GreatWorkSlotProvider]; only their contents persist here.
 *
 * Serializes via [IsPartOfGameInfoSerialization]; gdx Json omits default-valued fields, so an old save
 * with no `greatWorkManager` deserializes into a fresh empty manager that is fully usable after
 * [setTransients] (no NPE). Every field is copied in [clone].
 */
class GreatWorkManager : IsPartOfGameInfoSerialization {

    @Transient
    lateinit var gameInfo: GameInfo

    /** Stable id → the work object. */
    var works = HashMap<String, GreatWork>()

    /** [GreatWorkSlot.key] → [GreatWork.id]. The single source of truth for "what is in which slot". */
    var slotPlacements = HashMap<String, String>()

    /** Monotonic counter behind [newId]; serialized so ids stay unique across save/load. */
    var nextId = 0

    fun newId(): String = "gw${nextId++}"

    fun clone(): GreatWorkManager {
        val toReturn = GreatWorkManager()
        for ((id, work) in works) toReturn.works[id] = work.clone()
        toReturn.slotPlacements.putAll(slotPlacements)
        toReturn.nextId = nextId
        return toReturn
    }

    fun setTransients(gameInfo: GameInfo) {
        this.gameInfo = gameInfo
    }

    //region Registry

    fun registerWork(work: GreatWork) {
        works[work.id] = work
    }

    fun getWork(id: String): GreatWork? = works[id]

    /** Removes a work from the registry AND clears any placement that referenced it. */
    fun removeWork(id: String) {
        works.remove(id)
        val emptiedSlots = slotPlacements.filterValues { it == id }.keys.toList()
        for (slotKey in emptiedSlots) slotPlacements.remove(slotKey)
    }

    //endregion
    //region Placement

    /** Places [work] into [slot] (overwriting whatever was there). Caller is responsible for having
     *  validated the slot exists and accepts the work's type (see [GreatWorkSlotProvider]). */
    fun placeWork(work: GreatWork, slot: GreatWorkSlot) {
        slotPlacements[slot.key()] = work.id
    }

    fun clearSlot(slot: GreatWorkSlot) {
        slotPlacements.remove(slot.key())
    }

    fun getWorkInSlot(slot: GreatWorkSlot): GreatWork? =
        slotPlacements[slot.key()]?.let { works[it] }

    /**
     * The works "owned" by [civ]. We treat ownership as derived from placement plus origin:
     *  1. every work currently placed in a slot whose `civId` is the civ's [Civilization.civName], plus
     *  2. every registered work NOT placed in any slot whose [GreatWork.creatingCivName] == that name.
     *
     * (A placed work belongs to whoever owns the building it sits in — which can differ from its
     * creator after the work moves between civs; an unplaced work falls back to its creator.)
     */
    fun getWorksOf(civ: Civilization): List<GreatWork> {
        val placedWorkIds = slotPlacements.keys
            .filter { it.substringBefore('|') == civ.civName }
            .mapNotNull { slotPlacements[it] }
            .toHashSet()
        val placedAnywhere = slotPlacements.values.toHashSet()
        val result = ArrayList<GreatWork>()
        for (work in works.values) {
            val isInThisCivsSlot = work.id in placedWorkIds
            val isUnplacedOurs = work.id !in placedAnywhere && work.creatingCivName == civ.civName
            if (isInThisCivsSlot || isUnplacedOurs) result.add(work)
        }
        return result
    }

    //endregion
    //region Slot maintenance (Increment 2)

    /**
     * BNW Phase 2c — Increment 2. Drops every placement whose slot no longer exists (its building was
     * sold/destroyed, its city lost, etc.) and **re-banks** the displaced work to the former owner's
     * stockpile so it is never silently destroyed (D2). Null-safe: missing civs/resources are skipped.
     *
     * Called by the authority after any event that can remove a slot (building sale, city loss).
     */
    fun evictOrphanedPlacements() {
        if (slotPlacements.isEmpty()) return

        // The union of every slot that currently exists, keyed by its flat key.
        val existingSlotKeys = HashSet<String>()
        for (civ in gameInfo.civilizations)
            for (slot in GreatWorkSlotProvider.getSlotsForCiv(civ))
                existingSlotKeys.add(slot.key())

        val orphanedKeys = slotPlacements.keys.filter { it !in existingSlotKeys }.toList()
        for (slotKey in orphanedKeys) {
            val workId = slotPlacements.remove(slotKey) ?: continue
            val work = works[workId] ?: continue
            // The slot key starts with the (former) owner's civName; re-bank the work there.
            val ownerCivName = slotKey.substringBefore('|')
            val owner = gameInfo.civilizations.firstOrNull { it.civName == ownerCivName }
                ?: gameInfo.civilizations.firstOrNull { it.civName == work.creatingCivName }
                ?: continue
            val resource = gameInfo.ruleset.tileResources[work.type.resourceName] ?: continue
            owner.gainStockpiledResource(resource, 1)
        }
    }

    //endregion
}
