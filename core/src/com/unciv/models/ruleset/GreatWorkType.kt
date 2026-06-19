package com.unciv.models.ruleset

import yairm210.purity.annotations.Readonly

/**
 * BNW Phase 2c — Increment 1. The four kinds of Great Work.
 *
 * Each maps 1:1 to one of the bundled BNW stockpiled "resources" that legacy data used to bank Great
 * Works (`Great Work of Writing/Art/Music`, `Artifact`); see [resourceName]. That legacy stockpile
 * path still runs (D6 fallback / old saves), so this enum is the single bridge between the new
 * [com.unciv.logic.civilization.managers.GreatWork] objects and the old resource names.
 *
 * Slot-type matching (D3): an **Art** slot accepts both `Art` and `Artifact` works (archaeology
 * artifacts share the museum/wonder art slots in Civ V); every other type matches exactly.
 */
enum class GreatWorkType(val resourceName: String) {
    Writing("Great Work of Writing"),
    Art("Great Work of Art"),
    Music("Great Work of Music"),
    Artifact("Artifact");

    /** Does a work of *this* type fit into a slot of [slotType]? Artifact and Art both fit an Art
     *  slot; otherwise an exact type match is required. */
    @Readonly
    fun fitsSlot(slotType: GreatWorkType): Boolean = when (slotType) {
        Art -> this == Art || this == Artifact
        else -> this == slotType
    }

    companion object {
        /** Reverse of [resourceName]: the legacy stockpiled-resource name → its [GreatWorkType], or
         *  `null` if [name] is not a Great-Work resource. Lets callers detect a Great-Work resource. */
        @Readonly
        fun fromResourceName(name: String): GreatWorkType? =
            entries.firstOrNull { it.resourceName == name }
    }
}
