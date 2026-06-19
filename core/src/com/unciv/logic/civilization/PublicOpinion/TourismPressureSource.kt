package com.unciv.logic.civilization.PublicOpinion

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Tourism.TourismInfluenceLevel
import com.unciv.models.ruleset.PolicyBranch

/**
 * BNW Phase 2b — Increment 6 (the keystone): tourism-driven ideological pressure. Swapped in for
 * [CivCountPressureSource] by [com.unciv.logic.GameInfo.getIdeologicalPressureSource] in BNW-style
 * rulesets, with **ZERO change to [PublicOpinionManager]** — it consumes only the
 * [IdeologicalPressureSource] seam.
 *
 * Civ V rule: a civ is ideologically pushed toward the ideology of the civs that are *culturally
 * influential over it*. Only **Popular or better** tourism influence exerts pressure, scaled by level
 * (Popular → 1, Influential → 2, Dominant → 3).
 *
 * **Direction is critical** — the pressure ON [target] comes from civs influential OVER [target], so we
 * read each *other* civ's influence over [target] (`other.tourism.getInfluenceLevelOver(target)`), NOT
 * [target]'s influence over them. Computed on the authority where every civ's real `tourism` is present
 * (the projector scrub only affects wire views), so this stateless source always sees canonical influence.
 */
class TourismPressureSource : IdeologicalPressureSource {

    override fun pressureOn(target: Civilization): Map<PolicyBranch, Float> {
        val pressure = HashMap<PolicyBranch, Float>()
        for (other in target.gameInfo.civilizations) {
            if (other == target) continue
            if (!other.isMajorCiv() || other.isDefeated()) continue
            // Direction: only civs that are Popular-or-better OVER `target` push their ideology onto it.
            val level = other.tourism.getInfluenceLevelOver(target)
            if (level < TourismInfluenceLevel.Popular) continue
            val ideology = other.policies.getCurrentIdeology() ?: continue
            pressure[ideology] = (pressure[ideology] ?: 0f) + levelWeight(level)
        }
        return pressure
    }

    /** Level-scaled pressure weight (Civ V: only Popular+ exerts ideological pressure). */
    private fun levelWeight(level: TourismInfluenceLevel): Float = when (level) {
        TourismInfluenceLevel.Popular -> 1f
        TourismInfluenceLevel.Influential -> 2f
        TourismInfluenceLevel.Dominant -> 3f
        else -> 0f
    }
}
