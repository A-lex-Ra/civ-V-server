package com.unciv.ui.screens.worldscreen

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.UncivGame
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.Event
import com.unciv.models.ruleset.EventChoice
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.network.command.GameCommand
import com.unciv.ui.components.UncivTooltip.Companion.addTooltip
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.widgets.WrappableLabel
import com.unciv.ui.screens.civilopediascreen.FormattedLine
import com.unciv.ui.screens.civilopediascreen.MarkupRenderer

/** Renders an [Event] for [AlertPopup] or a floating tutorial task on [WorldScreen] */
class RenderEvent(
    event: Event,
    val worldScreen: WorldScreen,
    val unit: MapUnit? = null,
    /**
     * When true and a multiplayer-v3 game is active, activating a choice ALSO sends a
     * [GameCommand.ResolveEvent] so the **authority** applies the choice to the canonical state — the
     * local [EventChoice.triggerChoice] below only mutates this client's throwaway, visibility-filtered
     * view, which the next authoritative snapshot overwrites. Left false for the floating tutorial-task
     * render path (it has no backing `PopupAlert(AlertType.Event)` for the authority to resolve).
     */
    private val sendV3Command: Boolean = false,
    val onChoice: (EventChoice) -> Unit
) : Table() {
    private val gameInfo get() = worldScreen.gameInfo
    private val stageWidth get() = worldScreen.stage.width

    /** Captured for the v3 [GameCommand.ResolveEvent] (the alert/ruleset key for this event). */
    private val eventName = event.name

    val isValid: Boolean

    //todo check generated translations

    init {
        defaults().fillX().center().pad(5f)

        val gameContext = GameContext(gameInfo.currentPlayerCiv, unit = unit)
        val choices = event.getMatchingChoices(gameContext)
        isValid = choices != null
        if (isValid) {
            if (event.text.isNotEmpty()) {
                add(WrappableLabel(event.text, stageWidth * 0.5f).apply {
                    wrap = true
                    setAlignment(Align.center)
                    optimizePrefWidth()
                }).row()
            }
            if (event.civilopediaText.isNotEmpty()) {
                add(event.renderCivilopediaText(stageWidth * 0.5f, ::openCivilopedia)).row()
            }

            // Index into the event's FULL choices list (NOT the filtered `choices`): that is the stable
            // identifier the authority maps back via event.choices[choiceIndex]. indexOf is identity-based
            // (RulesetObject has no equals override) and these are the same instances, so it is exact.
            for (choice in choices!!) addChoice(choice, event.choices.indexOf(choice))
        }
    }

    private fun addChoice(choice: EventChoice, choiceIndex: Int) {
        addSeparator()

        val button = choice.text.toTextButton()
        button.onActivation {
            onChoice(choice)
            // multiplayer-v3: tell the authority to resolve this event on the canonical state before the
            // local (optimistic) trigger below. Mirrors AlertPopup.addDemand's "send intent, then fall
            // through to the local action". Skipped for tutorial tasks (sendV3Command=false) and for
            // single-player (v3GameManager == null).
            if (sendV3Command) {
                UncivGame.Current.v3GameManager?.sendCommand(
                    GameCommand.ResolveEvent(eventName = eventName, choiceIndex = choiceIndex, unitId = unit?.id)
                )
            }
            choice.triggerChoice(gameInfo.currentPlayerCiv, unit)
        }
        val key = KeyCharAndCode.parse(choice.keyShortcut)
        if (key != KeyCharAndCode.UNKNOWN) {
            button.keyShortcuts.add(key)
            button.addTooltip(key)
        }
        add(button).row()

        val lines = (
            choice.civilopediaText.asSequence()
                + choice.uniqueObjects.filter { it.isTriggerable || it.type == UniqueType.Comment }
                    .filterNot { it.isHiddenToUsers() }
                    .map { FormattedLine(it) }
            ).asIterable()
        add(MarkupRenderer.render(lines, stageWidth * 0.5f, linkAction = ::openCivilopedia)).row()
    }

    private fun openCivilopedia(link: String) = worldScreen.openCivilopedia(link)
}
