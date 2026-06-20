package com.unciv.ui.screens.pickerscreens

import com.unciv.GUI
import com.unciv.UncivGame
import com.unciv.logic.city.City
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.UncivSound
import com.unciv.models.translations.tr
import com.unciv.network.command.GameCommand
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onClick
import com.unciv.ui.images.ImageGetter

/**
 * BNW Phase 3 — International Trade Routes. The destination picker for the *Move to City* action: instantly
 * relocate a trade [unit] to one of its civ's other cities (the move consumes the unit's whole turn).
 *
 * Modeled on [WorldCongressScreen] / [TradeRoutePickerScreen]: it **emits a [GameCommand] only** (on a v3
 * client) and then applies the same relocation locally, so single-player and v3 stay in lock-step. Candidate
 * cities exclude the city the unit currently stands on and any whose center already holds a trade unit (two
 * trade units may not share a tile).
 */
class TradeUnitMoveCityScreen(private val unit: MapUnit) : PickerScreen() {

    init {
        setDefaultCloseAction()
        rightSideButton.isVisible = false

        descriptionLabel.setText("Move [${unit.name}] to another city".tr())

        val candidates = unit.civ.cities
            .filter { it.getCenterTile() != unit.currentTile && it.getCenterTile().tradeUnit == null }
            .sortedBy { it.name }
        if (candidates.isEmpty()) {
            topTable.add("No available cities.".toLabel()).pad(10f).row()
        } else {
            for (city in candidates) {
                val button = PickerPane.getPickerOptionButton(
                    ImageGetter.getImage("OtherIcons/Diplomacy"), city.name.tr()
                )
                button.onClick(UncivSound.Chimes) { moveTo(city) }
                topTable.add(button).fillX().pad(8f).row()
            }
        }
    }

    private fun moveTo(destCity: City) {
        // Mirror the existing unit-action dispatch convention: send the v3 command when a v3 manager exists,
        // then ALWAYS relocate locally through the engine (removeFromTile + putInTile).
        val v2 = UncivGame.Current.v3GameManager
        if (v2 != null) v2.sendCommand(GameCommand.MoveTradeUnitToCity(
            unitX = unit.currentTile.position.x, unitY = unit.currentTile.position.y,
            destCityX = destCity.location.x, destCityY = destCity.location.y))
        unit.removeFromTile()
        unit.putInTile(destCity.getCenterTile())
        unit.currentMovement = 0f
        unit.action = null
        GUI.setUpdateWorldOnNextRender()
        UncivGame.Current.popScreen()
    }
}
