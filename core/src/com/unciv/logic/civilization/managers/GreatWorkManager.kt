package com.unciv.logic.civilization.managers

import com.unciv.logic.GameInfo
import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Tourism.GreatWorksTourismSource
import com.unciv.models.ruleset.GreatWorkType
import yairm210.purity.annotations.Readonly

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

        // BNW Phase 2c — Increment 5 (D4): register the Great-Works tourism contributor into each civ's
        // Phase 2b tourism-output seam. This is the single, idempotent registration site — it runs once
        // per GameInfo.setTransients (after every civ's TourismManager.setTransients), and
        // GreatWorksTourismSource.register removes any previously-registered marker before re-adding, so
        // repeated setTransients never double-counts. Other contributors in the list are left intact.
        for (civ in gameInfo.civilizations)
            GreatWorksTourismSource.register(civ)
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

    @Readonly
    fun getWorkInSlot(slot: GreatWorkSlot): GreatWork? =
        slotPlacements[slot.key()]?.let { works[it] }

    /**
     * BNW Phase 2c — Increment 3. Moves [work] into [destSlot].
     *
     *  - Clears [work] from whatever slot currently holds it (if any).
     *  - If [destSlot] is occupied by ANOTHER work that belongs to the same owning civ (same slot
     *    `civId`), the two works are **swapped**: the displaced work is placed into [work]'s old slot.
     *  - [work] is then placed into [destSlot].
     *
     * Built only from the existing slot-placement primitives ([getWorkInSlot]/[clearSlot]/[placeWork]);
     * [slotPlacements] is never re-keyed directly here ([GreatWorkSlot.key] stays the single identity).
     * The caller ([com.unciv.logic.multiplayer.v3.command.CommandExecutor.executeMoveGreatWork]) is
     * responsible for validating ownership, slot existence and type-fit before calling.
     */
    fun moveWork(work: GreatWork, destSlot: GreatWorkSlot) {
        // The work's current slot (if it sits in one) — used to receive a displaced swap partner.
        val sourceSlotKey = slotPlacements.entries.firstOrNull { it.value == work.id }?.key
        val displaced = getWorkInSlot(destSlot)?.takeIf { it.id != work.id }

        // Remove the moving work from its old slot so a self-move (dest == source) cleanly re-places it.
        if (sourceSlotKey != null) slotPlacements.remove(sourceSlotKey)

        // Place the moving work into the destination (overwrites the displaced work's placement there).
        placeWork(work, destSlot)

        // If we displaced a work owned by the same civ, drop it into the moving work's old slot key
        // (reconstructed via slotPlacements, not re-keyed). If there was no source slot the displaced
        // work simply becomes unplaced (re-banked behaviour is not this method's concern).
        if (displaced != null && sourceSlotKey != null)
            slotPlacements[sourceSlotKey] = displaced.id
    }

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
    //region Tourism (Increment 5)

    /**
     * BNW Phase 2c — Increment 5 (D4). This civ's per-turn Great-Work tourism contribution, fed into the
     * Phase 2b tourism-output seam ([TourismManager.tourismOutputContributors]) via
     * [com.unciv.logic.civilization.Tourism.GreatWorksTourismSource]:
     *
     *   `2 × (filled slots owned by civ)  +  Σ themed-building theming-tourism`
     *
     * A slot is "filled" when its [GreatWorkSlot.key] (one of the civ's own slots) is present in
     * [slotPlacements]. The theming term sums [GreatWorkTheming.getThemingTourism] over each distinct
     * (building, city) the civ owns that is currently themed. Returns 0 when the civ has no slots.
     */
    fun getTourismContribution(civ: Civilization): Float {
        val slots = GreatWorkSlotProvider.getSlotsForCiv(civ)
        if (slots.isEmpty()) return 0f

        val filledCount = slots.count { it.key() in slotPlacements }
        var total = 2f * filledCount

        // Add the per-themed-building bonus once per distinct (building, city) the civ owns.
        val distinctBuildings = slots.map { it.buildingName to it.cityLocation }.toHashSet()
        for ((buildingName, cityLocation) in distinctBuildings)
            total += GreatWorkTheming.getThemingTourism(civ, buildingName, cityLocation)

        return total
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
    //region AI (Increment 8)

    /**
     * BNW Phase 2c — Increment 8. Places every one of [civ]'s **unplaced** works into a free matching
     * slot (capital-first), for the AI's "don't leave a Great Work in the drawer" step. Authority-only:
     * mutates canonical state directly (the AI runs on the authority).
     *
     * Two sources of unplaced works, both routed through the existing primitives — no new placement
     * logic:
     *  1. **Registered-but-unplaced** works owned by the civ (registered, absent from [slotPlacements],
     *     creator == this civ): placed directly via [placeWork] into the first free matching slot.
     *  2. **Banked stockpiled** works (`Great Work of …`/`Artifact` resources the civ holds because no
     *     slot was free at creation, D6): for each free matching slot, decrement the stockpile by one
     *     and create+place a fresh named work via [GreatWorkCreation.createAndPlace] (which finds that
     *     same free slot and places it — it only re-banks when NO slot is free, which we've excluded).
     *
     * Idempotent and bounded: each pass only consumes the free slots that currently exist, and a work
     * is removed from "unplaced" the moment it is placed. Returns the number of works newly placed.
     */
    fun autoFillFreeSlots(civ: Civilization): Int {
        var placed = 0

        // (1) Registered-but-unplaced works this civ created. getWorksOf includes them; filter to the
        // ones not sitting in any slot (placed works share the same ids in slotPlacements.values).
        val placedIds = slotPlacements.values.toHashSet()
        val unplaced = getWorksOf(civ).filter { it.id !in placedIds }
        for (work in unplaced) {
            val freeSlot = GreatWorkSlotProvider.getFreeSlotsForCiv(civ, work.type).firstOrNull() ?: continue
            placeWork(work, freeSlot)
            placed++
        }

        // (2) Banked stockpiled works: while a free slot exists for a banked type, draw one from the
        // stockpile and materialize it into that slot via the existing creation path.
        for (type in GreatWorkType.entries) {
            val resource = gameInfo.ruleset.tileResources[type.resourceName] ?: continue
            while (civ.getResourceAmount(resource) > 0 &&
                GreatWorkSlotProvider.getFreeSlotsForCiv(civ, type).isNotEmpty()
            ) {
                // Spend one banked work, then let createAndPlace find the (still-free) slot and place it.
                civ.gainStockpiledResource(resource, -1)
                GreatWorkCreation.createAndPlace(civ, type)
                placed++
            }
        }

        return placed
    }

    //endregion
}
