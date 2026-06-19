package com.unciv.ui.screens.overviewscreen

import com.badlogic.gdx.utils.Align
import com.unciv.UncivGame
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.GreatWork
import com.unciv.logic.civilization.managers.GreatWorkSlot
import com.unciv.logic.civilization.managers.GreatWorkSlotProvider
import com.unciv.logic.civilization.managers.GreatWorkTheming
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.popups.Popup

/**
 * BNW Phase 2c — Increment 8. A minimal Culture-Overview panel listing the viewing civ's Great Works by
 * city / building / slot, the host building's theming status, and — for the viewer's own works — a
 * "Move" affordance.
 *
 * **Dispatch convention (reused verbatim from [com.unciv.ui.screens.pickerscreens.PolicyPickerScreen]'s
 * `AdoptPolicy` path):** a move is an intent. In a multiplayer-v3 session
 * ([UncivGame.v3GameManager] non-null) the UI emits a [com.unciv.network.command.GameCommand.MoveGreatWork]
 * and does NOT mutate canonical state — the authority validates and applies it, and the resulting
 * filtered view re-renders. In single-player ([UncivGame.v3GameManager] null) there is no authority, so
 * the move is applied directly via [com.unciv.logic.civilization.managers.GreatWorkManager.moveWork].
 *
 * Rivals' works are shown read-only (D5: Great Works are public) with no move affordance.
 */
class GreatWorksOverviewTab(
    viewingPlayer: Civilization,
    overviewScreen: EmpireOverviewScreen
) : EmpireOverviewTab(viewingPlayer, overviewScreen) {

    private val manager = viewingPlayer.gameInfo.greatWorkManager

    init {
        top()
        defaults().pad(10f).align(Align.center)

        // Column header row (kept in the scrollable content for simplicity — no fixed header needed).
        add("Building".toLabel())
        add("Great Work".toLabel())
        add("Type".toLabel())
        add("Era".toLabel())
        add("Artist".toLabel())
        add("Themed".toLabel())
        add().minWidth(30f) // Move button column
        row()

        createGrid()
    }

    private fun createGrid() {
        // Group the viewer's own slots by city, in city order, then building, then slot index.
        var lastCity = ""
        for (city in viewingPlayer.cities) {
            val citySlots = GreatWorkSlotProvider.getSlotsForCiv(viewingPlayer)
                .filter { it.cityLocation == city.location }
                .sortedWith(compareBy({ it.buildingName }, { it.slotIndex }))
            if (citySlots.isEmpty()) continue

            if (city.name != lastCity) {
                lastCity = city.name
                add(city.name.toLabel()).colspan(7).left().padTop(15f).row()
            }

            for (slot in citySlots) {
                val work = manager.getWorkInSlot(slot)
                addSlotRow(slot, work, isOwn = true)
            }
        }

        // Read-only view of rivals' placed works (public; D5) — only those whose cities the viewer can
        // see (scrubbed placements are simply absent from slotPlacements for the viewer).
        val ownWorkIds = GreatWorkSlotProvider.getSlotsForCiv(viewingPlayer)
            .mapNotNull { manager.getWorkInSlot(it)?.id }.toHashSet()
        val rivalWorks = manager.works.values.filter { it.id !in ownWorkIds && !isUnplacedOwn(it) }
        if (rivalWorks.isNotEmpty()) {
            add("Other civilizations' Great Works".toLabel()).colspan(7).left().padTop(20f).row()
            for (work in rivalWorks.sortedBy { it.creatingCivName }) {
                add(work.creatingCivName.toLabel())
                add(work.name.toLabel())
                add(work.type.name.toLabel())
                add(work.fromEra.toLabel())
                add(work.artistName.toLabel())
                add() // theming column not meaningful per-work
                add() // no move affordance for rivals
                row()
            }
        }
    }

    /** Is [work] one of the viewing civ's unplaced (banked/registered) works? */
    private fun isUnplacedOwn(work: GreatWork): Boolean {
        val placedAnywhere = manager.slotPlacements.values.toHashSet()
        return work.id !in placedAnywhere && work.creatingCivName == viewingPlayer.civName
    }

    private fun addSlotRow(slot: GreatWorkSlot, work: GreatWork?, isOwn: Boolean) {
        add(slot.buildingName.toLabel())
        add((work?.name ?: "(empty)").toLabel())
        add((work?.type?.name ?: "").toLabel())
        add((work?.fromEra ?: "").toLabel())
        add((work?.artistName ?: "").toLabel())

        val themed = GreatWorkTheming.isThemed(viewingPlayer, slot.buildingName, slot.cityLocation)
        add((if (themed) "Yes" else "").toLabel())

        if (isOwn && work != null) {
            val moveButton = "Move".toTextButton()
            moveButton.onClick { openMovePopup(work) }
            add(moveButton)
        } else {
            add()
        }
        row()
    }

    /** Popup listing every legal destination slot for [work] (free, or holding another of the viewer's
     *  works → a swap). Selecting one dispatches the move per the v3/single-player convention. */
    private fun openMovePopup(work: GreatWork) {
        val popup = Popup(overviewScreen)
        popup.addGoodSizedLabel("Move [${work.name}] to:").row()

        // Candidate destinations: every slot of the viewer that accepts this work's type and is NOT the
        // slot the work already sits in. Free slots and slots holding another own work (a swap) qualify.
        val currentSlotKey = manager.slotPlacements.entries.firstOrNull { it.value == work.id }?.key
        val destinations = GreatWorkSlotProvider.getSlotsForCiv(viewingPlayer)
            .filter { work.type.fitsSlot(it.slotType) && it.key() != currentSlotKey }
            .sortedWith(compareBy({ it.buildingName }, { it.slotIndex }))

        if (destinations.isEmpty()) {
            popup.addGoodSizedLabel("No valid destination slots.").row()
        } else {
            for (dest in destinations) {
                val occupant = manager.getWorkInSlot(dest)
                val label = "[${dest.buildingName}] slot ${dest.slotIndex + 1}" +
                    (if (occupant != null) " (swap with [${occupant.name}])" else " (empty)")
                // Add to the scrollable top content (not the bottom button row), so a long list scrolls.
                val destButton = label.tr().toTextButton()
                destButton.onClick {
                    dispatchMove(work, dest)
                    popup.close()
                    // Re-render the overview to reflect the move (single-player applies immediately; in
                    // v3 the authoritative view update arrives asynchronously, this just refreshes now).
                    overviewScreen.game.replaceCurrentScreen(overviewScreen.recreate())
                }
                popup.add(destButton).row()
            }
        }
        popup.addCloseButton()
        popup.open()
    }

    /**
     * Dispatch a move per the shared convention. v3 → emit the intent only (authority mutates);
     * single-player → apply directly. Mirrors
     * [com.unciv.ui.screens.pickerscreens.PolicyPickerScreen]'s `AdoptPolicy` dispatch.
     */
    private fun dispatchMove(work: GreatWork, dest: GreatWorkSlot) {
        val v3 = UncivGame.Current.v3GameManager
        if (v3 != null) {
            // Multiplayer-v3: the UI never mutates canonical state — send the intent and let the
            // authority validate/apply, then re-project the filtered view.
            v3.sendCommand(
                com.unciv.network.command.GameCommand.MoveGreatWork(
                    workId = work.id,
                    toCityX = dest.cityLocation.x,
                    toCityY = dest.cityLocation.y,
                    toBuildingName = dest.buildingName,
                    toSlotIndex = dest.slotIndex
                )
            )
        } else {
            // Single-player: no authority, apply the move directly to the local canonical state.
            manager.moveWork(work, dest)
        }
    }
}
