package com.unciv.ui.screens.pickerscreens

import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.Belief
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.tr

class PantheonPickerScreen(
    choosingCiv: Civilization
) : ReligionPickerScreenCommon(choosingCiv) {
    private var selectedPantheon: Belief? = null
    private val selection = Selection()

    init {
        topTable.defaults().pad(10f).fillX()

        for (belief in ruleset.beliefs.values) {
            if (belief.type != BeliefType.Pantheon) continue
            val beliefButton = getBeliefButton(belief, withTypeLabel = false)
            if (choosingCiv.religionManager.getReligionWithBelief(belief) == null && beliefIsAllowed(belief, choosingCiv)) {
                beliefButton.onClickSelect(selection, belief) {
                    selectedPantheon = belief
                    pick("Follow [${belief.name}]".tr())
                }
            } else {
                beliefButton.disable(redDisableColor)
            }
            topTable.add(beliefButton).row()
        }

        setOKAction("Choose a pantheon") {
            // multiplayer-v2: route the founding intent to the authority before the local apply,
            // following the MoveUnit template (gate on v2 != null; send; FALL THROUGH). FoundPantheon
            // is self-contained (just the chosen belief name) — no unit locator needed.
            val v2 = com.unciv.UncivGame.Current.v2GameManager
            if (v2 != null)
                v2.sendCommand(com.unciv.network.command.GameCommand.FoundPantheon(selectedPantheon!!.name))
            chooseBeliefs(listOf(selectedPantheon!!), useFreeBeliefs = usingFreeBeliefs())
        }
    }
    fun beliefIsAllowed(belief: Belief, choosingCiv: Civilization): Boolean {
        if (belief.getMatchingUniques(UniqueType.OnlyAvailable, GameContext.IgnoreConditionals)
                .any { !it.conditionalsApply(choosingCiv.state) })
            return false
        if (belief.getMatchingUniques(UniqueType.Unavailable, choosingCiv.state).any())
            return false
        return true
    }
}
