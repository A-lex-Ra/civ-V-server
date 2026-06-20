package com.unciv.ui.screens.pickerscreens

import com.unciv.GUI
import com.unciv.UncivGame
import com.unciv.logic.city.City
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.trade.TradeRouteConnection
import com.unciv.logic.trade.TradeRouteType
import com.unciv.logic.trade.TradeRouteYields
import com.unciv.models.UncivSound
import com.unciv.models.translations.tr
import com.unciv.network.command.GameCommand
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onClick
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsTrade

/**
 * BNW Phase 3 — International Trade Routes. The destination picker for the *Establish Trade Route* action.
 *
 * Modeled on [WorldCongressScreen]: it **emits a [GameCommand] only** (on a v3 client) and then applies the
 * same intent locally through the shared [com.unciv.logic.trade.TradeRouteManager.establish], so
 * single-player and v3 stay in lock-step. The route's origin is [originCity] (the city the trade [unit] is
 * standing on); the candidate destinations are every reachable city within the unit's range budget, ranked
 * by their projected per-turn yield score.
 */
class TradeRoutePickerScreen(private val unit: MapUnit, private val originCity: City) : PickerScreen() {

    private val manager get() = unit.civ.gameInfo.tradeRouteManager
    private val type = if (unit.baseUnit.isLandUnit) TradeRouteType.Land else TradeRouteType.Sea

    init {
        setDefaultCloseAction()
        rightSideButton.isVisible = false

        descriptionLabel.setText("Establish Trade Route from [${originCity.name}]".tr())

        val candidates = buildRankedCandidates()
        if (candidates.isEmpty()) {
            topTable.add("No reachable cities within range.".toLabel()).pad(10f).row()
        } else {
            for ((dest, score) in candidates) {
                val button = PickerPane.getPickerOptionButton(
                    ImageGetter.getImage("OtherIcons/Diplomacy"), candidateLabel(dest, score)
                )
                button.onClick(UncivSound.Chimes) { establish(dest) }
                topTable.add(button).fillX().pad(8f).row()
            }
        }
    }

    /** Every reachable destination (route computes, within range), ranked by yield score then city id. */
    private fun buildRankedCandidates(): List<Pair<City, Int>> {
        val maxLength = UnitActionsTrade.maxRouteLength(unit)
        return unit.civ.gameInfo.getCities()
            .filter { it.id != originCity.id }
            .mapNotNull { dest ->
                val length = manager.computeRoute(originCity, dest, type) ?: return@mapNotNull null
                if (length > maxLength) return@mapNotNull null
                val provisional = TradeRouteConnection().apply {
                    originCityId = originCity.id
                    destinationCityId = dest.id
                    ownerCivId = unit.civ.civID
                    this.type = this@TradeRoutePickerScreen.type
                    this.length = length
                    establishedTurn = unit.civ.gameInfo.turns
                    unitId = unit.id
                }
                val score = TradeRouteYields.scoreYields(
                    TradeRouteYields.computeYields(provisional, unit.civ.gameInfo))
                dest to score
            }
            .sortedWith(compareByDescending<Pair<City, Int>> { it.second }.thenBy { it.first.id })
            .toList()
    }

    private fun candidateLabel(dest: City, score: Int): String {
        val ownerName = dest.civ.civName
        return "[${dest.name}] ([$ownerName]) — [$score] ${"Gold".tr()}"
    }

    private fun establish(dest: City) {
        // Mirror the existing unit-action dispatch convention (addDisbandAction / getGiftActions / old ITR):
        // send the v3 command when a v3 manager exists, then ALWAYS apply locally via the shared manager.
        val v2 = UncivGame.Current.v3GameManager
        if (v2 != null) v2.sendCommand(GameCommand.EstablishTradeRoute(
            unitX = unit.currentTile.position.x, unitY = unit.currentTile.position.y,
            destCityX = dest.location.x, destCityY = dest.location.y))
        unit.civ.gameInfo.tradeRouteManager.establish(originCity, dest, unit)
        GUI.setUpdateWorldOnNextRender()
        UncivGame.Current.popScreen()
    }
}
