package com.unciv.ui.screens.worldscreen.unit

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.logic.city.City
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.Spy
import com.unciv.ui.components.extensions.addRoundCloseButton
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.center
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.extensions.isShiftKeyPressed
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onClick
import com.unciv.ui.images.padTopDescent
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.unit.presenter.CityPresenter
import com.unciv.ui.screens.worldscreen.unit.presenter.SpyPresenter
import com.unciv.ui.screens.worldscreen.unit.presenter.SummaryPresenter
import com.unciv.ui.screens.worldscreen.unit.presenter.UnitPresenter
import yairm210.purity.annotations.Readonly
import java.awt.Label

class UnitTable(val worldScreen: WorldScreen) : Table() {
    private val prevIdleUnitButton =
        IdleUnitButton(this, worldScreen.mapHolder, true, KeyboardBinding.PrevIdleButton)
    private val nextIdleUnitButton =
        IdleUnitButton(this, worldScreen.mapHolder, false, KeyboardBinding.NextIdleButton)
    internal val unitIconHolder = Table()
    internal val unitNameLabel = "".toLabel(fontSize = 24).apply { setAlignment(Label.CENTER) }
    internal val unitIconNameGroup = Table()
    internal val promotionsTable = Table().apply { defaults().padRight(5f) }
    internal val descriptionTable = Table(BaseScreen.skin)
    internal val closeButton: Actor
    internal val separator: Actor

    /**
     * The unit table shows infos of selected units, cities, spies, and a summary if none of these
     * are selected. Each one of them have their own presenters to show their data.
     */
    private var presenter: Presenter

    private val unitPresenter = UnitPresenter(this, worldScreen)
    private val cityPresenter = CityPresenter(this, unitPresenter)
    private val spyPresenter = SpyPresenter(this)
    private val summaryPresenter = SummaryPresenter(this)


    // This is so that not on every update(), we will update the unit table.
    // Most of the time it's the same unit with the same stats so why waste precious time?
    var shouldUpdate = false

    private var bg = Image(
        BaseScreen.skinStrings.getUiBackground(
            "WorldScreen/UnitTable",
            BaseScreen.skinStrings.roundedEdgeRectangleMidShape,
            BaseScreen.skinStrings.skinConfig.baseColor.darken(0.5f)
        )
    )

    val selectedUnit: MapUnit?
        get() = (presenter as? UnitPresenter)?.selectedUnit
    val selectedCity: City?
        get() = (presenter as? CityPresenter)?.selectedCity

    val selectedSpy: Spy?
        get() = (presenter as? SpyPresenter)?.selectedSpy

    val selectedUnits: List<MapUnit> by unitPresenter::selectedUnits

    var selectedUnitIsSwapping by unitPresenter::selectedUnitIsSwapping

    var selectedUnitIsConnectingRoad by unitPresenter::selectedUnitIsConnectingRoad

    var nameLabelText: String
        get() = unitNameLabel.text.toString()
        set(value) {
            if (nameLabelText != value) {
                unitNameLabel.setText(value)
                // We need to reload the health bar of the unit in the icon - happens e.g. when picking the Heal Instantly promotion
                shouldUpdate = true
            }
        }

    init {
        presenter = summaryPresenter

        pad(5f)
        touchable = Touchable.enabled
        background = BaseScreen.skinStrings.getUiBackground(
            "WorldScreen/UnitTable", BaseScreen.skinStrings.roundedEdgeRectangleMidShape
        )
        addActor(bg)

        promotionsTable.touchable = Touchable.enabled

        closeButton = addRoundCloseButton(this) {
            selectUnit()
            worldScreen.shouldUpdate = true
        }
        closeButton.keyShortcuts.clear() // This is the only place we don't want the BACK keyshortcut getCloseButton assigns

        add(Table().apply {
            val moveBetweenUnitsTable = Table().apply {
                add(prevIdleUnitButton)
                unitIconNameGroup.add(unitIconHolder)
                unitIconNameGroup.add(unitNameLabel).padTopDescent()
                unitIconHolder.touchable = Touchable.enabled
                unitNameLabel.touchable = Touchable.enabled
                add(unitIconNameGroup)
                add(nextIdleUnitButton)
            }
            add(moveBetweenUnitsTable).fill().row()

            separator = addSeparator().padBottom(5f).actor!!
            add(promotionsTable).row()
            add(descriptionTable)
            touchable = Touchable.enabled
            onClick {
                presenter.position?.let {
                    worldScreen.mapHolder.setCenterPosition(
                        it.toHexCoord(),
                        immediately = false,
                        selectUnit = false
                    )
                }
            }
        }).expand()

    }


    /** Sending no unit clears the selected units entirely */
    fun selectUnit(unit: MapUnit? = null, append: Boolean = false) {
        presenter = if (unit != null) unitPresenter else summaryPresenter
        unitPresenter.selectUnit(unit, append)
        resetUnitTable()
    }

    fun selectSpy(spy: Spy?) {
        presenter = spyPresenter
        spyPresenter.selectSpy(spy)
        resetUnitTable()
    }

    fun citySelected(city: City): Boolean {
        presenter = cityPresenter
        return cityPresenter.selectCity(city).also {
            resetUnitTable()
            worldScreen.shouldUpdate = true
        }
    }


    fun update() {
        closeButton.isVisible = true
        
        if (!presenter.shouldBeShown()) summaryPresenter
        presenter.update()

        // more efficient to do this check once for both
        if (worldScreen.viewingCiv.units.getIdleUnits().any()) {
            prevIdleUnitButton.enable()
            nextIdleUnitButton.enable()
        } else {
            prevIdleUnitButton.disable()
            nextIdleUnitButton.disable()
        }
        
        if (!shouldUpdate) return

        resetUnitTable()

        presenter.updateWhenNeeded()
        
        pack()
        closeButton.setPosition(
            width - closeButton.width * 3 / 4,
            height - closeButton.height * 3 / 4
        )
        closeButton.toFront()
        bg.setSize(width - 3f, height - 3f)
        bg.center(this)
        shouldUpdate = false
    }

    private fun resetUnitTable() {
        unitIconHolder.clear()
        promotionsTable.clear()
        descriptionTable.clearListeners()
        // ImageWithCustomSize remembers width and returns if when Table asks for prefWidth
        separator.width = 0f
        shouldUpdate = true
    }

    fun tileSelected(selectedTile: Tile, forceSelectUnit: MapUnit? = null) {

        val previouslySelectedUnit = selectedUnit
        val previousNumberOfSelectedUnits = selectedUnits.size

        // Do not select a different unit or city center if we click on it to swap our current unit to it
        if (selectedUnitIsSwapping && selectedUnit != null && selectedUnit!!.movement.canUnitSwapTo(selectedTile)) return
        // Do no select a different unit while in Air Sweep mode
        if (selectedUnit != null && selectedUnit!!.isPreparingAirSweep()) return

        @Readonly
        fun MapUnit.isEligible(): Boolean = (this.civ == worldScreen.viewingCiv
                || worldScreen.viewingCiv.isSpectator()) && this !in selectedUnits

        // This is the Civ 5 Order of selection:
        // 1. City
        // 2. GP + Settlers
        // 3. Military
        // 4. Other civilian (Workers)
        // 5. None (Deselect)
        // However we deviate from it because there was a poll on Discord that clearly showed that
        // people would prefer the military unit to always be preferred over GP, so we use this:
        // 1. City
        // 2. Military
        // 3. GP + Settlers
        // 4. Other civilian (Workers)
        // 5. Trade unit (Caravan / Cargo Ship) — BNW ITR, its own tile slot
        // 6. None (Deselect)

        val curUnit = selectedUnit

        // The tile's selectable slots in cycle order. A trade unit lives in its own slot, so it must
        // take part in the click-cycle just like the civilian/military slots — otherwise it could only
        // be picked from the floating unit-icon overlay.
        val orderedSlots = listOfNotNull(
            selectedTile.militaryUnit,
            selectedTile.civilianUnit,
            selectedTile.tradeUnit
        )
        val priorityUnit = orderedSlots.firstOrNull { it.isEligible() }

        // Clicking the same tile again advances to the next eligible slot after the current one, and
        // wraps round to "none" (deselect) after the last — same behaviour the two original slots had.
        val nextUnit: MapUnit? = when {
            curUnit == null -> priorityUnit
            curUnit in orderedSlots ->
                orderedSlots.drop(orderedSlots.indexOf(curUnit) + 1).firstOrNull { it.isEligible() }
            else -> priorityUnit
        }


        val isCitySelected = selectedTile.isCityCenter()
            && (selectedTile.getOwner() == worldScreen.viewingCiv || worldScreen.viewingCiv.isSpectator())
            && !selectedUnitIsConnectingRoad
        when {
            forceSelectUnit != null -> selectUnit(forceSelectUnit)
            isCitySelected -> citySelected(selectedTile.getCity()!!)
            nextUnit != null -> selectUnit(nextUnit, Gdx.input.isShiftKeyPressed())
            // toggle selection if same unit is clicked again by player
            selectedTile == previouslySelectedUnit?.currentTile -> {
                selectUnit()
                shouldUpdate = true
            }
        }

        if (selectedUnit != previouslySelectedUnit || selectedUnits.size != previousNumberOfSelectedUnits)
            shouldUpdate = true
    }

    interface Presenter {
        /** map position of the selected entity */
        val position: HexCoord?
        /** called every time [WorldScreen] is updated */
        fun update() {}
        /** only called when [UnitTable.shouldUpdate] is true */
        fun updateWhenNeeded() {}
        fun shouldBeShown(): Boolean { return true}
    }
}
