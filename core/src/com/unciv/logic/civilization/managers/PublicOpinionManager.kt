package com.unciv.logic.civilization.managers

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.PublicOpinion.IdeologicalPressureSource
import com.unciv.models.ruleset.PolicyBranch
import com.unciv.models.ruleset.unique.UniqueTriggerActivation
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueTarget
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

    /**
     * BNW Phase 2a — Increment 2. Turns of **anarchy** remaining after an ideology switch: while > 0
     * the civ produces no production / research (a civ-wide `[-100]%` applied via [Civilization.temporaryUniques];
     * see [applyAnarchy]). Decremented once per turn in [TurnManager.startTurn], alongside [recompute].
     * Serialized; defaults to 0 (no anarchy) so old saves load cleanly.
     */
    var anarchyTurnsRemaining: Int = 0

    /**
     * BNW Phase 2a — Increment 2. Set by [recompute] when [dissidentUnhappiness] crosses the
     * Civil-Resistance threshold: the public is in open revolt and the civ is being **forced** to
     * switch ideology to the surrounding-preferred one. While true, the v3
     * [com.unciv.logic.multiplayer.v3.command.CommandExecutor.executeSwitchIdeology] accepts a switch
     * even when it would not otherwise be voluntarily adoptable. The human is prompted via the
     * Civil-Resistance [PopupAlert] (an [AlertType.Event] carrier, reusing the existing actionable-alert
     * plumbing). Cleared by [applyAnarchy] once the switch is performed.
     */
    var forcedSwitchPending: Boolean = false

    /** How fast the pressure meter tracks the incoming target each turn (0..1). Smoothing avoids a
     *  single rival-flip from instantly maxing out dissidence. */
    private val smoothingFactor = 0.5f

    /** Each unit of net opposing pressure above one's own ideology costs this much happiness. */
    private val unhappinessPerNetPressure = 1f

    /** Floor for [dissidentUnhappiness] — dissidence is unpleasant but bounded. */
    private val maxDissidentUnhappiness = -10

    /** Dissident unhappiness at or below this (it is ≤ 0) means the public is in open revolt:
     *  Civil Resistance forces an ideology switch toward the surrounding-preferred one. */
    private val civilResistanceThreshold = -8

    fun clone(): PublicOpinionManager {
        val toReturn = PublicOpinionManager()
        toReturn.ideologyPressureByBranch = HashMap(ideologyPressureByBranch)
        toReturn.dissidentUnhappiness = dissidentUnhappiness
        toReturn.anarchyTurnsRemaining = anarchyTurnsRemaining
        toReturn.forcedSwitchPending = forcedSwitchPending
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
            forcedSwitchPending = false
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

        updateCivilResistance(myIdeology)
    }

    /**
     * BNW Phase 2a — Increment 2: detect Civil Resistance. When dissident unhappiness has crossed the
     * [civilResistanceThreshold] (open revolt) AND a different surrounding-preferred ideology exists,
     * mark [forcedSwitchPending] and raise the actionable Civil-Resistance [PopupAlert] (once) so the
     * human is prompted to switch. The alert reuses [AlertType.Event] — the existing
     * resolved-by-player-command carrier that survives projection — but the actual switch is performed
     * by `CommandExecutor.executeSwitchIdeology`, not by the event-resolution path (we do NOT invent a
     * new AlertType). AI civs read [forcedSwitchPending] directly in [NextTurnAutomation] and never
     * need the alert. While already in anarchy we don't re-trigger (the switch is in progress).
     */
    private fun updateCivilResistance(myIdeology: PolicyBranch) {
        val preferred = getPreferredIdeology()
        val underRevolt = anarchyTurnsRemaining == 0 &&
            dissidentUnhappiness <= civilResistanceThreshold &&
            preferred != null && preferred.name != myIdeology.name

        if (!underRevolt) {
            forcedSwitchPending = false
            return
        }

        if (!forcedSwitchPending) {
            // Raise the actionable alert exactly once when the revolt begins (only humans see/act on
            // it; AI reads forcedSwitchPending). Guard against a duplicate if one is already pending.
            forcedSwitchPending = true
            if (civInfo.popupAlerts.none { it.type == AlertType.Event && it.value == CIVIL_RESISTANCE_EVENT_NAME })
                civInfo.popupAlerts.add(PopupAlert(AlertType.Event, CIVIL_RESISTANCE_EVENT_NAME))
        }
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

    /** True while the civ is in post-switch anarchy (no production / research). */
    fun isInAnarchy(): Boolean = anarchyTurnsRemaining > 0

    /**
     * BNW Phase 2a — Increment 2: begin [turns] turns of anarchy. Called by
     * [PolicyManager.switchIdeology] after the new ideology is adopted. Sets [anarchyTurnsRemaining]
     * and applies the civ-wide `[-100]% Production` / `[-100]% Science` via the engine's own
     * temporary-unique machinery ([UniqueTriggerActivation] turns a `<for [N] turns>` unique into a
     * [com.unciv.models.ruleset.unique.TemporaryUnique] on [Civilization.temporaryUniques]). These are
     * standard [com.unciv.models.ruleset.unique.UniqueType.StatPercentBonus] uniques, read civ-wide by
     * [com.unciv.logic.city.CityStats] — no bespoke flag needed. Clears [forcedSwitchPending] (the
     * forced switch, if any, has now happened).
     */
    fun applyAnarchy(turns: Int) {
        anarchyTurnsRemaining = turns
        forcedSwitchPending = false
        // Mirror the trigger path of any "<for [N] turns>" unique: the timing-conditional handler in
        // UniqueTriggerActivation converts these to TemporaryUniques regardless of their UniqueType.
        for (text in listOf("[-100]% Production <for [$turns] turns>", "[-100]% Science <for [$turns] turns>")) {
            val unique = Unique(text, UniqueTarget.Global, ANARCHY_SOURCE)
            UniqueTriggerActivation.triggerUnique(unique, civInfo)
        }
        // Anarchy zeroes city output immediately, so refresh stats / city tiles now.
        for (city in civInfo.cities) city.cityStats.update()
        civInfo.updateStatsForNextTurn()
    }

    /**
     * BNW Phase 2a — Increment 2: count down anarchy by one turn. Called once per turn from
     * [TurnManager.startTurn] (alongside [recompute]). The civ-wide `[-100]%` temporary uniques expire
     * on their own via [com.unciv.models.ruleset.unique.endTurn]; this only tracks the remaining count
     * for UI / validation (a switch is illegal while anarchy is in progress).
     */
    fun decrementAnarchy() {
        if (anarchyTurnsRemaining > 0) anarchyTurnsRemaining--
    }

    companion object {
        /** Stable value carried by the actionable Civil-Resistance [PopupAlert]; matched by
         *  `CommandExecutor.executeSwitchIdeology` to consume the alert on a forced switch. */
        const val CIVIL_RESISTANCE_EVENT_NAME = "Civil Resistance"

        /** Source name stamped on the anarchy temporary uniques (for display / debugging). */
        private const val ANARCHY_SOURCE = "Anarchy"
    }
}
