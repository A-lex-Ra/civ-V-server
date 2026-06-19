package com.unciv.logic.civilization.managers

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PublicOpinion.IdeologicalPressureSource
import com.unciv.models.ruleset.PolicyBranch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * BNW Phase 2a — Increment 1: public-opinion / ideological-pressure state for one [Civilization].
 *
 * **AUTHORITY-ONLY GameInfo state (D2).** This is recomputed by the authority each turn (in
 * [TurnManager.startTurn], before stats) and projected to clients; clients never recompute it,
 * because they see rivals' ideologies scrubbed (so a client recompute would diverge from the host).
 * [PlayerViewProjector] clears a rival civ's opinion in the wire view; the viewer's own stays intact.
 *
 * A civ with an adopted ideology feels happiness pressure toward the most prevalent *surrounding*
 * ideology. If the civ's own ideology is the prevalent one, pressure is ~0; if a rival ideology
 * dominates, the mismatch produces "Dissident" unhappiness ([dissidentUnhappiness], ≤ 0). The
 * pressure meter is smoothed across turns so it ramps rather than snapping.
 *
 * Pressure is supplied through the [IdeologicalPressureSource] seam (D3) — Increment 1 uses
 * civ-counts; Phase 2b will swap in tourism with no change to this class.
 *
 * Serializes via [IsPartOfGameInfoSerialization]; both fields default to empty/zero, so old saves
 * (which lack this manager entirely) deserialize into a valid neutral state.
 */
class PublicOpinionManager : IsPartOfGameInfoSerialization {

    @Transient
    lateinit var civInfo: Civilization

    /** branch name -> smoothed pressure meter (≥ 0). The "public opinion" toward each ideology. */
    var ideologyPressureByBranch = HashMap<String, Float>()

    /** Derived-but-persisted: the (≤ 0) happiness penalty from ideological mismatch. The happiness
     *  hook and the projector read this; persisting it avoids recomputing mid-turn / on the client. */
    var dissidentUnhappiness: Int = 0

    /** How fast the pressure meter tracks the incoming target each turn (0..1). Smoothing avoids a
     *  single rival-flip from instantly maxing out dissidence. */
    private val smoothingFactor = 0.5f

    /** Each unit of net opposing pressure above one's own ideology costs this much happiness. */
    private val unhappinessPerNetPressure = 1f

    /** Floor for [dissidentUnhappiness] — dissidence is unpleasant but bounded. */
    private val maxDissidentUnhappiness = -10

    fun clone(): PublicOpinionManager {
        val toReturn = PublicOpinionManager()
        toReturn.ideologyPressureByBranch = HashMap(ideologyPressureByBranch)
        toReturn.dissidentUnhappiness = dissidentUnhappiness
        return toReturn
    }

    fun setTransients(civInfo: Civilization) {
        this.civInfo = civInfo
    }

    /**
     * Recompute the smoothed pressure meter and the derived [dissidentUnhappiness] from the current
     * [source]. A civ with no adopted ideology has no public opinion: everything resets to zero.
     * Authority-only; called once per turn before [Civilization.updateStatsForNextTurn].
     */
    fun recompute(source: IdeologicalPressureSource) {
        val myIdeology = civInfo.policies.getCurrentIdeology()
        if (myIdeology == null) {
            // No ideology -> no public opinion at all. Keep the cleared state valid & deserializable.
            ideologyPressureByBranch.clear()
            dissidentUnhappiness = 0
            return
        }

        val target = source.pressureOn(civInfo)

        // Smooth each branch's meter toward its target pressure (target 0 for branches not present).
        val branchNames = HashSet<String>()
        branchNames.addAll(ideologyPressureByBranch.keys)
        for (branch in target.keys) branchNames.add(branch.name)

        val newMeter = HashMap<String, Float>()
        for (name in branchNames) {
            val current = ideologyPressureByBranch[name] ?: 0f
            val targetValue = target.entries.firstOrNull { it.key.name == name }?.value ?: 0f
            val smoothed = current + (targetValue - current) * smoothingFactor
            // Drop negligible residual so the meter can return to a clean empty state.
            if (smoothed >= 0.01f) newMeter[name] = smoothed
        }
        ideologyPressureByBranch = newMeter

        dissidentUnhappiness = computeDissidentUnhappiness(myIdeology)
    }

    /** The strongest opposing-ideology pressure minus our own ideology's pressure, turned into a
     *  (≤ 0) happiness penalty. Zero when our ideology is at least as prevalent as every rival's. */
    private fun computeDissidentUnhappiness(myIdeology: PolicyBranch): Int {
        val myPressure = ideologyPressureByBranch[myIdeology.name] ?: 0f
        val maxOpposingPressure = ideologyPressureByBranch
            .filterKeys { it != myIdeology.name }
            .values.maxOrNull() ?: 0f
        val netOpposing = maxOpposingPressure - myPressure
        if (netOpposing <= 0f) return 0
        val penalty = -(netOpposing * unhappinessPerNetPressure).roundToInt()
        return max(penalty, maxDissidentUnhappiness)
    }

    /** The ideology currently exerting the most pressure on this civ (the "preferred" ideology of its
     *  surroundings), or `null` when there is no pressure at all. */
    fun getPreferredIdeology(): PolicyBranch? {
        val topBranchName = ideologyPressureByBranch.maxByOrNull { it.value }?.key ?: return null
        return civInfo.gameInfo.ruleset.policyBranches[topBranchName]
    }

    /** The happiness contribution from public opinion (≤ 0). Read by the happiness breakdown hook. */
    fun getHappinessFromPublicOpinion(): Int = min(0, dissidentUnhappiness)
}
