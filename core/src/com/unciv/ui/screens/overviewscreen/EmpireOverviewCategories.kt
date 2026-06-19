package com.unciv.ui.screens.overviewscreen

import com.badlogic.gdx.utils.Align
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.screens.overviewscreen.EmpireOverviewTab.EmpireOverviewTabPersistableData


/** This controls which Tabs for the [EmpireOverviewScreen] exist and their order.
 *
 *  To add a Tab, build a new [EmpireOverviewTab] subclass and fill out a new entry here, that's all.
 *  Note the enum value's name is used as Tab caption, so if you ever need a non-alphanumeric caption
 *  please redesign to include a property for the caption
 */
enum class EmpireOverviewCategories(
    val iconName: String,
    val shortcutKey: KeyCharAndCode,
    val scrollAlign: Int
) {
    Cities("OtherIcons/Cities", 'C', Align.topLeft) {
        override fun createTab(viewingPlayer: Civilization, overviewScreen: EmpireOverviewScreen, persistedData: EmpireOverviewTabPersistableData?) =
                CityOverviewTab(viewingPlayer, overviewScreen, persistedData)
        override fun showDisabled(viewingPlayer: Civilization) = viewingPlayer.cities.isEmpty()
        override fun getPersistDataClass() = CityOverviewTab.CityTabPersistableData::class.java
    },
    Stats("StatIcons/Gold", 'S', Align.top) {
        override fun createTab(viewingPlayer: Civilization, overviewScreen: EmpireOverviewScreen, persistedData: EmpireOverviewTabPersistableData?) =
                StatsOverviewTab(viewingPlayer, overviewScreen)
        override fun showDisabled(viewingPlayer: Civilization) = viewingPlayer.isSpectator()
    },
    Trades("StatIcons/Acquire", 'T', Align.top) {
        override fun createTab(viewingPlayer: Civilization, overviewScreen: EmpireOverviewScreen, persistedData: EmpireOverviewTabPersistableData?) =
                TradesOverviewTab(viewingPlayer, overviewScreen)
        override fun showDisabled(viewingPlayer: Civilization) =
                viewingPlayer.diplomacy.values.all { it.trades.isEmpty() } &&
                viewingPlayer.diplomacy.values.none { diplomacyManager ->
                        diplomacyManager.otherCiv.tradeRequests.any { it.requestingCiv == viewingPlayer.civID }
                    }
    },
    Units("OtherIcons/Shield", 'U', Align.topLeft) {
        override fun createTab(viewingPlayer: Civilization, overviewScreen: EmpireOverviewScreen, persistedData: EmpireOverviewTabPersistableData?) =
                UnitOverviewTab(viewingPlayer, overviewScreen, persistedData)
        override fun showDisabled(viewingPlayer: Civilization) = viewingPlayer.units.getCivUnits().none()
        override fun getPersistDataClass() = UnitOverviewTab.UnitTabPersistableData::class.java
    },
    Politics("OtherIcons/Politics", 'P', Align.top) {
        override fun createTab(viewingPlayer: Civilization, overviewScreen: EmpireOverviewScreen, persistedData: EmpireOverviewTabPersistableData?) =
                GlobalPoliticsOverviewTable(viewingPlayer, overviewScreen, persistedData)
        override fun showDisabled(viewingPlayer: Civilization) = viewingPlayer.diplomacy.isEmpty()
        override fun getPersistDataClass() = GlobalPoliticsOverviewTable.DiplomacyTabPersistableData::class.java
    },
    Resources("StatIcons/Happiness", 'R', Align.topLeft) {
        override fun createTab(viewingPlayer: Civilization, overviewScreen: EmpireOverviewScreen, persistedData: EmpireOverviewTabPersistableData?) =
                ResourcesOverviewTab(viewingPlayer, overviewScreen, persistedData)
        override fun showDisabled(viewingPlayer: Civilization) = viewingPlayer.detailedCivResources.none { it.resource.resourceType != ResourceType.Bonus }
        override fun getPersistDataClass() = ResourcesOverviewTab.ResourcesTabPersistableData::class.java
    },
    Religion("StatIcons/Faith", 'F', Align.top) {
        override fun createTab(viewingPlayer: Civilization, overviewScreen: EmpireOverviewScreen, persistedData: EmpireOverviewTabPersistableData?) =
                ReligionOverviewTab(viewingPlayer, overviewScreen, persistedData)
        override fun testState(viewingPlayer: Civilization) = when {
            !viewingPlayer.gameInfo.isReligionEnabled() -> EmpireOverviewTabState.Hidden
            viewingPlayer.gameInfo.religions.isEmpty() -> EmpireOverviewTabState.Disabled
            else -> EmpireOverviewTabState.Normal
        }
    },
    Wonders("OtherIcons/Wonders", 'W', Align.top) {
        override fun createTab(viewingPlayer: Civilization, overviewScreen: EmpireOverviewScreen, persistedData: EmpireOverviewTabPersistableData?) =
                WonderOverviewTab(viewingPlayer, overviewScreen)
        override fun showDisabled(viewingPlayer: Civilization) = (viewingPlayer.naturalWonders.isEmpty() && viewingPlayer.cities.isEmpty())
    },
    Notifications("OtherIcons/Notifications", 'N', Align.top) {
        override fun createTab(viewingPlayer: Civilization, overviewScreen: EmpireOverviewScreen, persistedData: EmpireOverviewTabPersistableData?) =
                NotificationsOverviewTable(viewingPlayer, overviewScreen, persistedData)
        override fun showDisabled(viewingPlayer: Civilization) = viewingPlayer.notifications.isEmpty() && viewingPlayer.notificationsLog.isEmpty()
    },
    // BNW Phase 2c — Increment 8: Great Works (Culture-Overview style). Hidden unless the ruleset uses
    // the Great-Work slot concept; disabled when the civ has no works of its own yet.
    GreatWorks("StatIcons/Culture", 'G', Align.topLeft) {
        override fun createTab(viewingPlayer: Civilization, overviewScreen: EmpireOverviewScreen, persistedData: EmpireOverviewTabPersistableData?) =
                GreatWorksOverviewTab(viewingPlayer, overviewScreen)
        override fun testState(viewingPlayer: Civilization) = when {
            !com.unciv.logic.civilization.managers.GreatWorkSlotProvider
                .rulesetHasGreatWorkSlots(viewingPlayer.gameInfo.ruleset) -> EmpireOverviewTabState.Hidden
            viewingPlayer.gameInfo.greatWorkManager.getWorksOf(viewingPlayer).isEmpty() -> EmpireOverviewTabState.Disabled
            else -> EmpireOverviewTabState.Normal
        }
    }

    ;

    constructor(iconName: String, shortcutChar: Char, scrollAlign: Int)
        : this(iconName, KeyCharAndCode(shortcutChar), scrollAlign)

    enum class EmpireOverviewTabState { Normal, Disabled, Hidden }

    abstract fun createTab(viewingPlayer: Civilization, overviewScreen: EmpireOverviewScreen, persistedData: EmpireOverviewTabPersistableData?): EmpireOverviewTab
    open fun showDisabled(viewingPlayer: Civilization) = false
    open fun testState(viewingPlayer: Civilization) =
            if (showDisabled(viewingPlayer)) EmpireOverviewTabState.Disabled
            else EmpireOverviewTabState.Normal

    /** Get Java class of persistable data for Json serialization
     *  - only needed if the data should actually be saved to GameSettings.json: Leaving it at `null` means any specific state persists only through one game run
     */
    open fun getPersistDataClass(): Class<out EmpireOverviewTabPersistableData>? = null
}
