package com.unciv.logic.civilization.managers

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.GreatWorkType

/**
 * BNW Phase 2c — Increment 4. Creates a named [GreatWork] (from a Great Artist/Writer/Musician action
 * or an archaeology dig) and places it into one of the creating civ's free slots, falling back to the
 * legacy stockpile when no matching slot is free (D6).
 *
 * This is the single bridge between a Great-Work *trigger* and the new object model:
 * [com.unciv.models.ruleset.unique.UniqueTriggerActivation]'s `OneTimeProvideResources` branch routes
 * through here (instead of the plain stockpile credit) when the ruleset uses the Great-Work slot
 * concept. In a ruleset with no slot concept the trigger keeps its legacy stockpile path untouched, so
 * this object is never reached there.
 */
object GreatWorkCreation {

    /**
     * Build, register, and place a new [GreatWork] of [type] for [civ].
     *
     *  - id from [GreatWorkManager.newId]; a generated [GreatWork.name] (deterministic, see [pickName]);
     *  - [GreatWork.artistName] from [unit]'s display name (the Great Person / dig that produced it);
     *  - [GreatWork.fromEra] = the civ's current era name; [GreatWork.creatingCivName] = the civ name;
     *  - [GreatWork.turnCreated] = the current game turn.
     *
     * After registering, the first free matching slot (capital-first; see
     * [GreatWorkSlotProvider.getFreeSlotsForCiv]) receives the work and a placement notification fires.
     * If no matching slot is free, the work is still registered (so it isn't lost) AND a stockpiled
     * resource is credited as the back-compat fallback — mirroring the legacy
     * `gainStockpiledResource` path that `OneTimeProvideResources` used — with a "banked" notification.
     *
     * @return the created (and registered) work, whether placed or banked.
     */
    fun createAndPlace(
        civ: Civilization,
        type: GreatWorkType,
        unit: MapUnit? = null,
        notificationText: String? = null
    ): GreatWork {
        val gameInfo = civ.gameInfo
        val manager = gameInfo.greatWorkManager

        val work = GreatWork()
        work.id = manager.newId()
        work.type = type
        work.creatingCivName = civ.civName
        work.artistName = unit?.name ?: ""
        work.fromEra = civ.getEra().name
        work.turnCreated = gameInfo.turns
        work.name = pickName(type, work.id, manager)
        manager.registerWork(work)

        val freeSlot = GreatWorkSlotProvider.getFreeSlotsForCiv(civ, type).firstOrNull()
        if (freeSlot != null) {
            manager.placeWork(work, freeSlot)
            civ.addNotification(
                notificationText ?: "[${work.name}] has been created and placed in [${freeSlot.buildingName}]",
                NotificationCategory.General, type.resourceName
            )
        } else {
            // Fallback (D6): no free slot — bank the work as the legacy stockpiled resource so it is not
            // lost (mirrors the OneTimeProvideResources stockpile credit). Null-safe if the resource is
            // absent from the ruleset (then only the registered object survives).
            val resource = gameInfo.ruleset.tileResources[type.resourceName]
            if (resource != null) civ.gainStockpiledResource(resource, 1)
            civ.addNotification(
                notificationText ?: "[${work.name}] has been created but there is no free slot to display it",
                NotificationCategory.General, type.resourceName
            )
        }
        return work
    }

    /**
     * A small hardcoded, per-type pool of evocative names. Picked **deterministically** by the work's
     * registry index (no `Math.random`/`Date` — those are forbidden on some code paths and would make
     * names non-reproducible across host/joiner). The id is `"gw<n>"`, so we derive `n` and index into
     * the pool modulo its size; collisions just reuse a name (cosmetic only).
     */
    private fun pickName(type: GreatWorkType, id: String, manager: GreatWorkManager): String {
        val pool = namePools[type] ?: namePools.getValue(GreatWorkType.Art)
        // Deterministic index: prefer the numeric suffix of the id ("gw7" -> 7); fall back to works size.
        val index = id.removePrefix("gw").toIntOrNull() ?: manager.works.size
        return pool[index % pool.size]
    }

    /** Deterministic display-name pools per type (no new ruleset file; cosmetic). */
    private val namePools: Map<GreatWorkType, List<String>> = mapOf(
        GreatWorkType.Writing to listOf(
            "Epic of Ages", "The Founding Chronicle", "Treatise on Virtue",
            "Hymns of the Republic", "The Traveler's Account", "Codex of Laws"
        ),
        GreatWorkType.Art to listOf(
            "The Coronation", "Portrait of a Patron", "Still Life with Spoils",
            "The Grand Procession", "View of the Capital", "Allegory of Prosperity"
        ),
        GreatWorkType.Music to listOf(
            "Symphony of the Dawn", "The Victory Overture", "Nocturne in the Old Style",
            "Concerto for the People", "Anthem of the Free", "Suite of the Four Seasons"
        ),
        GreatWorkType.Artifact to listOf(
            "Ancient Reliquary", "Weathered Idol", "Buried Hoard",
            "Ceremonial Mask", "Shattered Tablet", "Forgotten Crown"
        )
    )
}
