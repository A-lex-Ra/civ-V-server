package com.unciv.ui.screens.pickerscreens

import com.unciv.UncivGame
import com.unciv.logic.civilization.Civilization
import com.unciv.models.UncivSound
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.translations.tr
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.components.extensions.isEnabled
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.input.onDoubleClick
import com.unciv.ui.screens.worldscreen.WorldScreen

class GreatPersonPickerScreen(val worldScreen: WorldScreen, val civInfo: Civilization) : PickerScreen() {
    private var theChosenOne: BaseUnit? = null

    init {
        worldScreen.autoPlay.stopAutoPlay()
        closeButton.isVisible = false
        rightSideButton.setText("Choose a free great person".tr())

        val greatPersonUnits = civInfo.greatPeople.getGreatPeople()
        val useMayaLongCount = civInfo.greatPeople.mayaLimitedFreeGP > 0

        for (unit in greatPersonUnits) {
            val button =
                PickerPane.getPickerOptionButton(ImageGetter.getUnitIcon(unit), unit.name)
            button.pack()
            button.isEnabled = !useMayaLongCount || unit.name in civInfo.greatPeople.longCountGPPool
            if (button.isEnabled) {
                button.onClick {
                    theChosenOne = unit
                    pick("Get [${unit.name}]".tr())
                    descriptionLabel.setText(unit.getShortDescription())
                }

                button.onDoubleClick(UncivSound.Choir) { confirmAction(useMayaLongCount) }
            }
            topTable.add(button).pad(10f).row()
        }

        rightSideButton.onClick(UncivSound.Choir) {
            confirmAction(useMayaLongCount)
        }

    }

    private fun confirmAction(useMayaLongCount: Boolean) {
        // multiplayer-v2: route the great-person choice to the authority before the local apply,
        // following the MoveUnit template (gate on v2 != null; send; FALL THROUGH). This is the
        // single confirm choke-point for both the OK button and the double-click.
        val v2 = com.unciv.UncivGame.Current.v2GameManager
        if (v2 != null)
            v2.sendCommand(com.unciv.network.command.GameCommand.ChooseGreatPerson(theChosenOne!!.name))
        civInfo.units.addUnit(theChosenOne!!, civInfo.getCapital())
        civInfo.greatPeople.freeGreatPeople--
        if (useMayaLongCount) {
            civInfo.greatPeople.mayaLimitedFreeGP--
            civInfo.greatPeople.longCountGPPool.remove(theChosenOne!!.name)
        }
        UncivGame.Current.popScreen()
    }
}
