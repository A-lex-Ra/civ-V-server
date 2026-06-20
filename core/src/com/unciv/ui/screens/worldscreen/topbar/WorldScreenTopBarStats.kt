package com.unciv.ui.screens.worldscreen.topbar

import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.trade.TradeRouteManager
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import com.unciv.models.translations.tr
import com.unciv.ui.components.UncivTooltip.Companion.addDescriptionTooltip
import com.unciv.ui.components.extensions.colorFromRGB
import com.unciv.ui.components.extensions.setFontColor
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toStringSigned
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.ScalingTableWrapper
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.overviewscreen.EmpireOverviewCategories
import com.unciv.ui.screens.overviewscreen.EmpireOverviewScreen
import com.unciv.ui.screens.pickerscreens.PolicyPickerScreen
import com.unciv.ui.screens.pickerscreens.TechPickerScreen
import kotlin.math.ceil
import kotlin.math.roundToInt

internal class WorldScreenTopBarStats(topbar: WorldScreenTopBar) : ScalingTableWrapper() {

    private val goldLabel = "0".toLabel(colorFromRGB(225, 217, 71)) // #ed1947
    private val goldPerTurnLabel = "+0"
        .toLabel(colorFromRGB(225, 217, 71), 14)

    private val scienceLabel = "0".toLabel(colorFromRGB(78, 140, 151)) // #4e8c97
    private val happinessLabel = "0".toLabel()
    private val cultureLabel = "0".toLabel(colorFromRGB(210, 94, 210)) // #d25ed2

    private val faithLabel = "0".toLabel(colorFromRGB(168, 196, 241)) // #a8c4f1
    private val faithPerTurnLabel = "+0"
        .toLabel(colorFromRGB(168, 196, 241), 14)

    private val tradeRoutesLabel = "0".toLabel(colorFromRGB(225, 180, 120))
    /** Whether this ruleset has International Trade Routes at all (the "Trade Route" stockpile resource). */
    private var tradeRoutesEnabled = false

    private val happinessContainer = Group()

    // These are all to improve performance IE reduce update time (was 150 ms on my phone, which is a lot!)
    private val malcontentColor = colorFromRGB(239,83,80) // Color.valueOf("ef5350")
    private val happinessColor = colorFromRGB(92, 194, 77) // Color.valueOf("8cc24d")
    private val malcontentImage = ImageGetter.getStatIcon("Malcontent")
    private val happinessImage = ImageGetter.getStatIcon("Happiness")

    private val worldScreen = topbar.worldScreen


    companion object {
        const val defaultImageSize = 20f
        const val defaultHorizontalPad = 3f
        const val defaultTopPad = 8f
        const val defaultBottomPad = 3f
        const val defaultImageBottomPad = 6f
        const val padRightBetweenStats = 20f
    }

    init {
        isTransform = false

        defaults().pad(defaultTopPad, defaultHorizontalPad, defaultBottomPad, defaultHorizontalPad)

        fun addStat(
            icon: String,
            label: Label,
            noPad: Boolean = false,
            tooltipProvider: (() -> String)? = null,
            screenFactory: () -> BaseScreen?
        ) {
            val image = ImageGetter.getStatIcon(icon)
            val action = {
                val screen = screenFactory()
                if (screen != null) worldScreen.game.pushScreen(screen)
            }
            label.onClick(action)
            image.onClick(action)
            // Hovering a stat tears down where it comes from (desktop only).
            if (tooltipProvider != null) {
                label.addDescriptionTooltip("", dynamicTextProvider = tooltipProvider)
                image.addDescriptionTooltip("", dynamicTextProvider = tooltipProvider)
            }
            add(image).padBottom(defaultImageBottomPad).size(defaultImageSize)
            add(label).padRight(if (noPad) 0f else padRightBetweenStats)
        }

        fun addStat(
            icon: String,
            label: Label,
            overviewPage: EmpireOverviewCategories,
            noPad: Boolean = false,
            tooltipProvider: (() -> String)? = null
        ) = addStat(icon, label, noPad, tooltipProvider) {
            EmpireOverviewScreen(worldScreen.selectedCiv, overviewPage)
        }

        fun addPerTurnLabel(label: Label) {
            add(label).padRight(padRightBetweenStats)
                .height(Constants.defaultFontSize.toFloat()).top()
        }


        addStat("Science", scienceLabel, tooltipProvider = { buildStatBreakdown(Stat.Science) }) {
            TechPickerScreen(worldScreen.selectedCiv)
        }

        addStat("Gold", goldLabel, EmpireOverviewCategories.Stats, true,
            tooltipProvider = { buildStatBreakdown(Stat.Gold) })
        addPerTurnLabel(goldPerTurnLabel)

        // International Trade Routes counter (BNW) - right after Gold. Only shown for rulesets that
        // actually have ITR (the "Trade Route" stockpile resource); vanilla/G&K hide it entirely.
        tradeRoutesEnabled = worldScreen.gameInfo.ruleset.tileResources.containsKey(TradeRouteManager.TRADE_ROUTE_RESOURCE)
        if (tradeRoutesEnabled) {
            val tradeImage = ImageGetter.getResourcePortrait(TradeRouteManager.TRADE_ROUTE_RESOURCE, defaultImageSize)
            val tradeTooltip = { buildTradeRoutesBreakdown() }
            tradeRoutesLabel.addDescriptionTooltip("", dynamicTextProvider = tradeTooltip)
            tradeImage.addDescriptionTooltip("", dynamicTextProvider = tradeTooltip)
            add(tradeImage).padBottom(defaultImageBottomPad).size(defaultImageSize)
            add(tradeRoutesLabel).padRight(padRightBetweenStats)
        }

        val invokeResourcesPage = {
            worldScreen.openEmpireOverview(EmpireOverviewCategories.Resources)
        }
        happinessContainer.onClick(invokeResourcesPage)
        happinessLabel.onClick(invokeResourcesPage)
        val happinessTooltip = { buildHappinessBreakdown() }
        happinessContainer.addDescriptionTooltip("", dynamicTextProvider = happinessTooltip)
        happinessLabel.addDescriptionTooltip("", dynamicTextProvider = happinessTooltip)
        add(happinessContainer).padBottom(defaultImageBottomPad).size(defaultImageSize)
        add(happinessLabel).padRight(padRightBetweenStats)

        addStat("Culture", cultureLabel, tooltipProvider = { buildStatBreakdown(Stat.Culture) }) {
            if (worldScreen.gameInfo.ruleset.policyBranches.isEmpty()) null
            else PolicyPickerScreen(worldScreen.selectedCiv, worldScreen.canChangeState)
        }

        if (worldScreen.gameInfo.isReligionEnabled()) {
            addStat("Faith", faithLabel, EmpireOverviewCategories.Religion, true,
                tooltipProvider = { buildStatBreakdown(Stat.Faith) })
            addPerTurnLabel(faithPerTurnLabel)
        } else add("Religion: Off".toLabel())


    }


    fun update(civInfo: Civilization) {
        resetScale()

        val nextTurnStats = civInfo.stats.statsForNextTurn

        goldLabel.setText(civInfo.gold.tr())
        goldPerTurnLabel.setText(rateLabel(nextTurnStats.gold))

        if (tradeRoutesEnabled)
            tradeRoutesLabel.setText(countInternationalRoutes(civInfo).tr())

        scienceLabel.setText(rateLabel(nextTurnStats.science))

        happinessLabel.setText(getHappinessText(civInfo))

        if (civInfo.getHappiness() < 0) {
            happinessLabel.setFontColor(malcontentColor)
            happinessContainer.clearChildren()
            happinessContainer.addActor(malcontentImage)
        } else {
            happinessLabel.setFontColor(happinessColor)
            happinessContainer.clearChildren()
            happinessContainer.addActor(happinessImage)
        }

        cultureLabel.setText(getCultureText(civInfo, nextTurnStats))

        faithLabel.setText(civInfo.religionManager.storedFaith.tr())
        faithPerTurnLabel.setText(rateLabel(nextTurnStats.faith))

        scaleTo(worldScreen.stage.width)
    }

    private fun getCultureText(civInfo: Civilization, nextTurnStats: Stats): String {
        var cultureString = rateLabel(nextTurnStats.culture)
        // kotlin Float division by Zero produces `Float.POSITIVE_INFINITY`, not an exception
        val turnsToNextPolicy = (civInfo.policies.getCultureNeededForNextPolicy() - civInfo.policies.storedCulture) / nextTurnStats.culture
        cultureString += when {
            turnsToNextPolicy <= 0f -> "\u2004(!)" // Can choose policy right now
            nextTurnStats.culture <= 0 -> "\u2004(${Fonts.infinity})" // when you start the game, you're not producing any culture
            else -> "\u2004(" + Fonts.turn + "\u2009" + ceil(turnsToNextPolicy).toInt().tr() + ")" // U+2004: Three-Per-Em Space, U+2009: Thin Space
        }
        return cultureString
    }

    private fun getHappinessText(civInfo: Civilization): String {
        var happinessText = civInfo.getHappiness().tr()
        val goldenAges = civInfo.goldenAges
        happinessText +=
            if (goldenAges.isGoldenAge())
                "    {GOLDEN AGE}(${goldenAges.turnsLeftForCurrentGoldenAge})".tr()
            else
                " (${goldenAges.storedHappiness.tr()}/${goldenAges.happinessRequiredForNextGoldenAge().tr()})"
        return happinessText
    }

    private fun rateLabel(value: Float): String {
        return if (value.roundToInt() == 0) "±0" else value.roundToInt().toStringSigned()
    }

    /** Tears down where a per-turn [stat] (Gold / Science / Culture / Faith) comes from, source by
     *  source, as multi-line translatable markup for a hover tooltip. */
    private fun buildStatBreakdown(stat: Stat): String {
        val civInfo = worldScreen.selectedCiv
        val sb = StringBuilder("{$stat}:")
        var total = 0f
        for ((source, stats) in civInfo.stats.getStatMapForNextTurn()) {
            val value = stats[stat]
            if (value == 0f) continue
            total += value
            sb.append("\n{").append(source).append("}: ").append(signed(value))
        }
        sb.append("\n{Total}: ").append(signed(total))
        return sb.toString()
    }

    /** Tears down the empire's happiness sources for a hover tooltip (sorted by impact). */
    private fun buildHappinessBreakdown(): String {
        val civInfo = worldScreen.selectedCiv
        val sb = StringBuilder("{Happiness}:")
        var total = 0f
        for ((source, value) in civInfo.stats.getHappinessBreakdown().entries.sortedByDescending { it.value }) {
            if (value == 0f) continue
            total += value
            sb.append("\n{").append(source).append("}: ").append(signed(value))
        }
        sb.append("\n{Total}: ").append(signed(total))
        return sb.toString()
    }

    private fun signed(value: Float): String {
        val rounded = value.roundToInt()
        return if (rounded > 0) "+$rounded" else rounded.toString()
    }

    /** Number of International Trade Routes [civInfo] owns (destination city belongs to another civ). */
    private fun countInternationalRoutes(civInfo: Civilization): Int {
        val mgr = civInfo.gameInfo.tradeRouteManager
        return mgr.getRoutesEstablishedBy(civInfo.civID).count {
            val dest = mgr.getDestinationCity(it)
            dest != null && dest.civ.civID != civInfo.civID
        }
    }

    /** Hover breakdown for the trade-routes counter: international vs domestic, and capacity used/max. */
    private fun buildTradeRoutesBreakdown(): String {
        val civInfo = worldScreen.selectedCiv
        val mgr = civInfo.gameInfo.tradeRouteManager
        val owned = mgr.getRoutesEstablishedBy(civInfo.civID)
        val international = owned.count { val d = mgr.getDestinationCity(it); d != null && d.civ.civID != civInfo.civID }
        val domestic = owned.count { val d = mgr.getDestinationCity(it); d != null && d.civ.civID == civInfo.civID }
        return "{International Trade Routes}: $international" +
            "\n{Domestic Trade Routes}: $domestic" +
            "\n{Trade Route capacity}: ${mgr.usedCapacity(civInfo.civID)}/${mgr.getMaxCapacity(civInfo)}"
    }
}
