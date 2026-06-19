package com.unciv.logic.civilization.managers

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.models.ruleset.GreatWorkType

/**
 * BNW Phase 2c — Increment 1. A single named Great Work as a first-class serialized object.
 *
 * Stored once in the GameInfo-level [GreatWorkManager.works] registry, keyed by [id]; its *location*
 * and *owner* are derived from which slot currently holds it (D1) — this object carries only the
 * intrinsic, immutable-ish facts about the work (who/when/what), not where it sits.
 *
 * Serializes via [IsPartOfGameInfoSerialization]. All fields have non-`lateinit` defaults so gdx Json
 * (which omits default-valued fields) can populate a partially-written object without crashing.
 */
class GreatWork : IsPartOfGameInfoSerialization {
    /** Stable registry key, assigned by [GreatWorkManager.newId] (e.g. `"gw3"`). */
    var id = ""
    var type = GreatWorkType.Art
    /** Display name of the work itself (e.g. a painting / book / song title). */
    var name = ""
    /** [Civilization.civName] of the civ that created it. The work's "owner" is derived from its
     *  slot placement; this records its *origin* and is the fallback owner while it sits unplaced. */
    var creatingCivName = ""
    /** Name of the Great Person (or dig site) that produced it; "" if anonymous. */
    var artistName = ""
    /** Era name at creation time; used by theming "same era" rules (Increment 5). */
    var fromEra = ""
    var turnCreated = 0

    fun clone(): GreatWork {
        val toReturn = GreatWork()
        toReturn.id = id
        toReturn.type = type
        toReturn.name = name
        toReturn.creatingCivName = creatingCivName
        toReturn.artistName = artistName
        toReturn.fromEra = fromEra
        toReturn.turnCreated = turnCreated
        return toReturn
    }
}
