package com.unciv.logic.civilization.PublicOpinion

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Proximity
import com.unciv.models.ruleset.PolicyBranch

/**
 * D3 seam — the source of ideological pressure on a civ, decoupled from the public-opinion math.
 *
 * Increment 1 ships [CivCountPressureSource] (pressure = how many civs follow each ideology).
 * Phase 2b will swap in a `TourismPressureSource` (pressure derived from tourism influence) with no
 * change to [PublicOpinionManager] — it consumes only this interface.
 *
 * The returned map is keyed by ideology [PolicyBranch] and gives the raw pressure that ideology
 * exerts on [target] this turn (≥ 0). Branches with no pressure may be omitted.
 */
interface IdeologicalPressureSource {
    fun pressureOn(target: Civilization): Map<PolicyBranch, Float>
}

/**
 * Increment-1 implementation: pressure toward an ideology is proportional to how many *other* living
 * major civs have adopted it. Optionally proximity-weighted (closer rivals push harder); with
 * [proximityWeighting] off, every adopting civ contributes equally.
 *
 * This is the deliberately simple "civ-counts" stand-in for tourism. Computed on the authority only
 * (see D2): it reads rivals' adopted ideologies, which clients never see (the projector scrubs them).
 */
class CivCountPressureSource(
    private val proximityWeighting: Boolean = true
) : IdeologicalPressureSource {

    override fun pressureOn(target: Civilization): Map<PolicyBranch, Float> {
        val pressure = HashMap<PolicyBranch, Float>()
        for (otherCiv in target.gameInfo.civilizations) {
            if (otherCiv == target) continue
            if (!otherCiv.isMajorCiv() || otherCiv.isDefeated()) continue
            val ideology = otherCiv.policies.getCurrentIdeology() ?: continue
            val weight = if (proximityWeighting) proximityWeight(target, otherCiv) else 1f
            pressure[ideology] = (pressure[ideology] ?: 0f) + weight
        }
        return pressure
    }

    /** Closer civs exert more ideological pressure. Falls back to a neutral weight when proximity
     *  is unknown (e.g. not yet met / no cities), so unmet rivals still contribute a baseline. */
    private fun proximityWeight(target: Civilization, otherCiv: Civilization): Float =
        when (target.getProximity(otherCiv)) {
            Proximity.Neighbors -> 2f
            Proximity.Close -> 1.5f
            Proximity.Far -> 1f
            Proximity.Distant -> 0.5f
            Proximity.None -> 1f
        }
}
