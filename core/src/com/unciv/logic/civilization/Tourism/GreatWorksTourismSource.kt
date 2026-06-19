package com.unciv.logic.civilization.Tourism

import com.unciv.logic.civilization.Civilization

/**
 * BNW Phase 2c — Increment 5 (D4 cross-feature contract). Wires the Great-Works per-turn tourism
 * contribution into the Phase 2b tourism-output seam
 * ([com.unciv.logic.civilization.managers.TourismManager.tourismOutputContributors]).
 *
 * The contributor closure reads the GameInfo-level Great-Work registry every turn:
 * `{ civ.gameInfo.greatWorkManager.getTourismContribution(civ) }` — so it always reflects the current
 * placements/theming without re-registration.
 *
 * **Idempotency.** Registration happens from
 * [com.unciv.logic.civilization.managers.GreatWorkManager.setTransients], which can run more than once
 * over the lifetime of a `Civilization` (e.g. a repeated `GameInfo.setTransients()`). We wrap the
 * closure in the dedicated marker class [GreatWorksTourismContributor] and **remove any previously
 * registered marker before adding a fresh one**, so the list never accumulates duplicates. Other
 * contributors in the list (e.g. test-injected ones or future features) are left untouched.
 */
object GreatWorksTourismSource {

    /** Marker wrapper so a previously-registered Great-Works contributor can be found and replaced,
     *  guaranteeing idempotent registration. */
    private class GreatWorksTourismContributor(val civ: Civilization) : () -> Float {
        override fun invoke(): Float = civ.gameInfo.greatWorkManager.getTourismContribution(civ)
    }

    /** Register (idempotently) the Great-Works tourism contributor on [civ]'s [TourismManager]. */
    fun register(civ: Civilization) {
        val contributors = civ.tourism.tourismOutputContributors
        // Drop any stale Great-Works contributor first so repeated setTransients never double-counts.
        contributors.removeAll { it is GreatWorksTourismContributor }
        contributors.add(GreatWorksTourismContributor(civ))
    }
}
