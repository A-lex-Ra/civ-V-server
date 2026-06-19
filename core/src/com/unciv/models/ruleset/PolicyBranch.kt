package com.unciv.models.ruleset

import com.unciv.models.ruleset.unique.UniqueType

class PolicyBranch : Policy() {
    var policies: ArrayList<Policy> = arrayListOf()
    var priorities: HashMap<String, Int> = HashMap()
    var era: String = ""

    /**
     * D1 — generic ideology detection (BNW Phase 2a). True when this policy branch behaves like a
     * Civ-V *ideology* (Order / Freedom / Autocracy and equivalents), detected purely from existing
     * bundled data so we don't have to edit the JSON or hardcode names.
     *
     * Two ruleset-free, data-driven signals (either is sufficient):
     *  1. The branch is mutually exclusive with another branch via
     *     `Unavailable <after adopting [otherBranch]>` ([UniqueType.Unavailable] carrying a
     *     [UniqueType.ConditionalAfterPolicyOrBelief] modifier) — the hallmark of "pick exactly one".
     *     All three bundled BNW ideologies carry two such uniques; ordinary branches carry none.
     *  2. The branch carries the self-removal marker `Remove [Ideology] ...` — the policy-removal
     *     unique whose policy-filter param is the literal `Ideology`. (The bundled spelling
     *     `Remove [Ideology] [in capital]` adds a cityFilter param, so it does not resolve to
     *     [UniqueType.OneTimeRemovePolicy]; we therefore match on the `Remove [` placeholder shape
     *     plus the `Ideology` param rather than on the resolved [UniqueType].)
     *
     * Reading the parsed [uniqueObjects] keeps this independent of conditional evaluation and of the
     * exact placeholder spelling of any single marker, so it survives small data variations.
     */
    val isIdeology: Boolean
        get() {
            for (unique in uniqueObjects) {
                // Signal 1: mutual exclusion with another branch ("Unavailable <after adopting [X]>").
                if (unique.type == UniqueType.Unavailable && unique.hasModifier(UniqueType.ConditionalAfterPolicyOrBelief))
                    return true
                // Signal 2: the "Remove [Ideology] ..." self-removal marker (any param spelling).
                if (unique.placeholderText.startsWith("Remove [") && unique.params.any { it == "Ideology" })
                    return true
            }
            return false
        }
}
