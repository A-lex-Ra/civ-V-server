package com.unciv.ui.screens.pickerscreens

import com.unciv.GUI
import com.unciv.UncivGame
import com.unciv.logic.city.City
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.trade.TradeRouteConnection
import com.unciv.logic.trade.TradeRouteType
import com.unciv.logic.trade.TradeRouteYield
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
 * by their projected per-turn yield score. A DOMESTIC destination (one of the unit's own cities) offers a
 * Food-vs-Production choice (Civ V internal trade routes); an INTERNATIONAL one is a single gold/science route.
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
                if (dest.civ.civID == unit.civ.civID) addDomesticButtons(dest)
                else addInternationalButton(dest, score)
            }
        }
    }

    /** A domestic destination: offer Food OR Production (the per-turn amount is era-scaled). */
    private fun addDomesticButtons(dest: City) {
        val amount = TradeRouteYields.computeYields(provisional(dest, TradeRouteYield.Food), unit.civ.gameInfo).destFood
        for (yield in listOf(TradeRouteYield.Food, TradeRouteYield.Production)) {
            val text = "[${dest.name}] — +[$amount] ${yield.name.tr()}"
            val button = PickerPane.getPickerOptionButton(ImageGetter.getImage("OtherIcons/Diplomacy"), text)
            button.onClick(UncivSound.Chimes) { establish(dest, yield) }
            topTable.add(button).fillX().pad(8f).row()
        }
    }

    /** An international destination: a single gold/science route. */
    private fun addInternationalButton(dest: City, score: Int) {
        val button = PickerPane.getPickerOptionButton(
            ImageGetter.getImage("OtherIcons/Diplomacy"),
            "[${dest.name}] ([${dest.civ.civName}]) — [$score] ${"Gold".tr()}"
        )
        button.onClick(UncivSound.Chimes) { establish(dest, TradeRouteYield.None) }
        topTable.add(button).fillX().pad(8f).row()
    }

    /** Every reachable destination (route computes, within range), ranked by yield score then city id. */
    private fun buildRankedCandidates(): List<Pair<City, Int>> {
        val maxLength = UnitActionsTrade.maxRouteLength(unit)
        return unit.civ.gameInfo.getCities()
            .filter { it.id != originCity.id }
            .mapNotNull { dest ->
                val length = manager.computeRoute(originCity, dest, type) ?: return@mapNotNull null
                if (length > maxLength) return@mapNotNull null
                val score = TradeRouteYields.scoreYields(
                    TradeRouteYields.computeYields(provisional(dest, TradeRouteYield.Production), unit.civ.gameInfo))
                dest to score
            }
            .sortedWith(compareByDescending<Pair<City, Int>> { it.second }.thenBy { it.first.id })
            .toList()
    }

    /** A provisional connection from [originCity] to [dest] for yield previewing (never registered). */
    private fun provisional(dest: City, yield: TradeRouteYield) = TradeRouteConnection().apply {
        originCityId = originCity.id
        destinationCityId = dest.id
        ownerCivId = unit.civ.civID
        type = this@TradeRoutePickerScreen.type
        length = manager.computeRoute(originCity, dest, this@TradeRoutePickerScreen.type) ?: 0
        establishedTurn = unit.civ.gameInfo.turns
        unitId = unit.id
        internalYield = yield
    }

    private fun establish(dest: City, yield: TradeRouteYield) {
        // Mirror the existing unit-action dispatch convention (addDisbandAction / getGiftActions / old ITR):
        // send the v3 command when a v3 manager exists, then ALWAYS apply locally via the shared manager.
        val v2 = UncivGame.Current.v3GameManager
        if (v2 != null) v2.sendCommand(GameCommand.EstablishTradeRoute(
            unitX = unit.currentTile.position.x, unitY = unit.currentTile.position.y,
            destCityX = dest.location.x, destCityY = dest.location.y,
            internalYield = if (yield == TradeRouteYield.None) "" else yield.name))
        unit.civ.gameInfo.tradeRouteManager.establish(originCity, dest, unit, yield)
        GUI.setUpdateWorldOnNextRender()
        UncivGame.Current.popScreen()
    }
}
