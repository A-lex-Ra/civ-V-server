package com.unciv.logic.civilization

import com.unciv.logic.IsPartOfGameInfoSerialization
import yairm210.purity.annotations.Readonly

/**
 * BNW Phase 3 — World Congress (Increment 1). The durable record of a resolution that PASSED a session,
 * kept as history and (for effects with no temporary-unique carrier) the canonical store the effect is
 * re-read from after a save/load. Flat-primitives only ([IsPartOfGameInfoSerialization]); a
 * default-constructed instance is a valid empty record.
 */
class EnactedResolution : IsPartOfGameInfoSerialization {

    /** The [ResolutionType] enum name (resolved via [ResolutionType.valueOf]). */
    var resolutionType = ""

    /** Targeted civ id for targeting resolutions, else empty. */
    var targetCivId = ""

    /** Choice argument (luxury/religion/ideology/civ name) for choice resolutions, else empty. */
    var choiceArg = ""

    /** Game turn this resolution was enacted on. */
    var enactedTurn = -1

    /** Congress session number that enacted it. */
    var sessionNumber = 0

    fun clone(): EnactedResolution {
        val toReturn = EnactedResolution()
        toReturn.resolutionType = resolutionType
        toReturn.targetCivId = targetCivId
        toReturn.choiceArg = choiceArg
        toReturn.enactedTurn = enactedTurn
        toReturn.sessionNumber = sessionNumber
        return toReturn
    }

    /** The resolved [ResolutionType], or null if the stored name is unknown. */
    @Readonly
    fun getResolutionType(): ResolutionType? =
        ResolutionType.entries.firstOrNull { it.name == resolutionType }
}
