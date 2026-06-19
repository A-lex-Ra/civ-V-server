package com.unciv.logic.civilization.Tourism

import yairm210.purity.annotations.Readonly

/**
 * BNW Phase 2b — Increment 1 (D5): the standard Civ V tourism *influence level* a civ holds over a
 * rival, derived from accumulated tourism influence as a fraction of that rival's lifetime culture.
 *
 * Breakpoints (ratio = accumulated influence / rival lifetime culture):
 *  - [Exotic] (< 10%), [Exposed] (≥ 10%), [Familiar] (≥ 30%), [Popular] (≥ 60%),
 *    [Influential] (≥ 100%), [Dominant] (≥ 200%).
 *
 * Cultural victory (Increment 4) is being [Influential] or better over ALL living major rivals.
 * The enum order is the natural ascending order, so the generated [compareTo] lets callers write
 * `level >= TourismInfluenceLevel.Influential`.
 *
 * Placed under a `Tourism/` package mirroring the `PublicOpinion/` package convention used by the
 * ideological-pressure seam.
 */
enum class TourismInfluenceLevel {
    Exotic, Exposed, Familiar, Popular, Influential, Dominant;

    companion object {
        /** Map an influence ratio (accumulated influence / rival lifetime culture) to its level. */
        @Readonly
        fun fromRatio(ratio: Float): TourismInfluenceLevel = when {
            ratio >= 2.0f -> Dominant
            ratio >= 1.0f -> Influential
            ratio >= 0.6f -> Popular
            ratio >= 0.3f -> Familiar
            ratio >= 0.1f -> Exposed
            else -> Exotic
        }
    }
}
