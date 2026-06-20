package com.unciv.ui.screens.pickerscreens

import com.unciv.UncivGame
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.CongressProposal
import com.unciv.logic.civilization.ResolutionType
import com.unciv.logic.civilization.managers.CongressPhase
import com.unciv.models.UncivSound
import com.unciv.models.translations.tr
import com.unciv.network.command.GameCommand
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onClick

/**
 * BNW Phase 3 — World Congress (Increment 5). A minimal, functional full-screen UI to view the congress,
 * propose resolutions, and cast votes. It **emits [GameCommand]s only** and renders the *projected*
 * [com.unciv.logic.civilization.managers.WorldCongressManager] (already redacted on a v3 client) — all
 * real logic lives in the (tested) manager/executor.
 *
 * Modeled on [DiplomaticVotePickerScreen]. The command-dispatch convention matches the existing pickers
 * (e.g. [PolicyPickerScreen]): on a v3 client the intent is sent to the authority via
 * [com.unciv.logic.multiplayer.v3.V3GameManager.sendCommand]; in single-player the same intent is applied
 * locally through the engine (here, the shared [com.unciv.logic.civilization.managers.WorldCongressManager]
 * methods the executor uses), so single-player and v3 stay in lock-step.
 */
class WorldCongressScreen(private val viewingCiv: Civilization) : PickerScreen() {

    private val congress get() = viewingCiv.gameInfo.congress

    init {
        setDefaultCloseAction()
        rightSideButton.isVisible = false

        descriptionLabel.setText(buildHeaderText())

        when {
            !congress.isFounded ->
                topTable.add("The World Congress has not yet been founded.".toLabel()).pad(10f).row()
            congress.currentPhase == CongressPhase.Proposing && congress.isMember(viewingCiv.civID) ->
                buildProposingUi()
            congress.currentPhase == CongressPhase.Voting && congress.isMember(viewingCiv.civID) ->
                buildVotingUi()
            else ->
                buildOverviewUi()
        }
    }

    private fun buildHeaderText(): String {
        if (!congress.isFounded) return "World Congress".tr()
        val host = viewingCiv.gameInfo.getCivilizationOrNull(congress.hostCivId)
        val lines = arrayListOf(
            "World Congress",
            "Host: [${host?.civName ?: congress.hostCivId}]",
            "Your delegates: [${congress.getDelegateCount(viewingCiv)}]",
            "Phase: [${congress.currentPhase.name}]"
        )
        return lines.joinToString("\n") { it.tr() }
    }

    // region Proposing

    private fun buildProposingUi() {
        if (congress.hasProposed(viewingCiv.civID)) {
            topTable.add("You have already proposed this session.".toLabel()).pad(10f).row()
            buildOverviewUi()
            return
        }
        topTable.add("Propose a resolution:".toLabel()).pad(10f).row()
        for (type in congress.getProposableResolutions(viewingCiv)) {
            val button = PickerPane.getPickerOptionButton(
                com.unciv.ui.images.ImageGetter.getImage("OtherIcons/Diplomacy"), type.name
            )
            button.onClick(UncivSound.Chimes) {
                // A resolution that needs a target civ or a choice argument opens a second-step picker;
                // a no-argument one proposes immediately.
                if (type.needsTarget || type.needsChoiceArg) buildArgPicker(type)
                else { proposeResolution(type); UncivGame.Current.popScreen() }
            }
            topTable.add(button).fillX().pad(8f).row()
        }
        buildOverviewUi()
    }

    /**
     * Step 2 for a targeting/choice resolution: replace the list with the valid options (a member civ for a
     * targeting resolution, else a luxury / religion / ideology / candidate per [ResolutionType.choiceArgKind]).
     * Each option proposes the resolution with that argument. Options are `(displayLabel, argument)`.
     */
    private fun buildArgPicker(type: ResolutionType) {
        topTable.clear()
        descriptionLabel.setText("Choose an option for [${type.name}]".tr())
        val ruleset = viewingCiv.gameInfo.ruleset
        val options: List<Pair<String, String>> = when {
            type.needsTarget -> congress.getMemberCivs()
                .filter { it.civID != viewingCiv.civID }
                .map { it.civName to it.civID }
            type.choiceArgKind == ResolutionType.ChoiceArgKind.Luxury -> ruleset.tileResources.values
                .filter { it.resourceType == com.unciv.models.ruleset.tile.ResourceType.Luxury }
                .map { it.name to it.name }
            type.choiceArgKind == ResolutionType.ChoiceArgKind.Religion -> viewingCiv.gameInfo.religions.values
                .filter { it.isMajorReligion() }
                .map { it.name to it.name }
            type.choiceArgKind == ResolutionType.ChoiceArgKind.Ideology -> ruleset.policyBranches.values
                .filter { it.isIdeology }
                .map { it.name to it.name }
            type.choiceArgKind == ResolutionType.ChoiceArgKind.Civ -> viewingCiv.gameInfo.getAliveMajorCivs()
                .map { it.civName to it.civID }
            else -> emptyList()
        }
        if (options.isEmpty()) {
            topTable.add("No valid options available.".toLabel()).pad(10f).row()
            return
        }
        for ((label, arg) in options) {
            val button = PickerPane.getPickerOptionButton(
                com.unciv.ui.images.ImageGetter.getImage("OtherIcons/Diplomacy"), label
            )
            button.onClick(UncivSound.Chimes) {
                if (type.needsTarget) proposeResolution(type, targetCivId = arg)
                else proposeResolution(type, choiceArg = arg)
                UncivGame.Current.popScreen()
            }
            topTable.add(button).fillX().pad(8f).row()
        }
    }

    private fun proposeResolution(type: ResolutionType, targetCivId: String = "", choiceArg: String = "") {
        val command = GameCommand.ProposeResolution(type.name, targetCivId, choiceArg)
        UncivGame.Current.v3GameManager?.sendCommand(command)
        // Single-player (and a local echo on v3): apply the same intent through the shared manager path.
        if (UncivGame.Current.v3GameManager == null)
            congress.addProposal(viewingCiv, type, targetCivId, choiceArg)
    }

    // endregion
    // region Voting

    private fun buildVotingUi() {
        topTable.add("Cast your votes:".toLabel()).pad(10f).row()
        val delegates = congress.getDelegateCount(viewingCiv)
        for (proposal in congress.activeProposals) {
            topTable.add(proposalSummary(proposal).toLabel()).pad(6f).row()
            if (proposal.hasVoted(viewingCiv.civID)) {
                topTable.add("You have voted.".toLabel()).pad(4f).row()
                continue
            }
            val forButton = PickerPane.getPickerOptionButton(
                com.unciv.ui.images.ImageGetter.getImage("OtherIcons/Diplomacy"), "Vote FOR"
            )
            forButton.onClick(UncivSound.Chimes) {
                castVote(proposal, delegates, true)
                UncivGame.Current.popScreen()
            }
            val againstButton = PickerPane.getPickerOptionButton(
                com.unciv.ui.images.ImageGetter.getImage("OtherIcons/Stop"), "Vote AGAINST"
            )
            againstButton.onClick(UncivSound.Chimes) {
                castVote(proposal, delegates, false)
                UncivGame.Current.popScreen()
            }
            topTable.add(forButton).fillX().pad(4f).row()
            topTable.add(againstButton).fillX().pad(4f).row()
        }
    }

    private fun castVote(proposal: CongressProposal, delegates: Int, voteFor: Boolean) {
        val command = GameCommand.CastCongressVote(proposal.id, delegates, voteFor)
        UncivGame.Current.v3GameManager?.sendCommand(command)
        if (UncivGame.Current.v3GameManager == null)
            congress.castVote(viewingCiv, proposal, delegates, voteFor)
    }

    // endregion
    // region Overview (read-only)

    private fun buildOverviewUi() {
        if (congress.activeProposals.isNotEmpty()) {
            topTable.add("Active proposals:".toLabel()).pad(10f).row()
            for (proposal in congress.activeProposals)
                topTable.add(proposalSummary(proposal).toLabel()).pad(4f).row()
        }
        congress.activeWorldProject?.let { project ->
            topTable.add("World Project: [${project.projectType}]".toLabel()).pad(10f).row()
            for (civId in project.rankedContributors()) {
                val name = viewingCiv.gameInfo.getCivilizationOrNull(civId)?.civName ?: civId
                topTable.add("[$name]: [${project.contributions[civId]}]".toLabel()).pad(2f).row()
            }
        }
        if (congress.enactedResolutions.isNotEmpty()) {
            topTable.add("Enacted resolutions:".toLabel()).pad(10f).row()
            for (res in congress.enactedResolutions.takeLast(10))
                topTable.add("[${res.resolutionType}] (session [${res.sessionNumber}])".toLabel()).pad(2f).row()
        }
    }

    private fun proposalSummary(proposal: CongressProposal): String {
        val proposer = viewingCiv.gameInfo.getCivilizationOrNull(proposal.proposerCivId)?.civName
            ?: proposal.proposerCivId
        val tail = when {
            proposal.targetCivId.isNotEmpty() -> " → [${proposal.targetCivId}]"
            proposal.choiceArg.isNotEmpty() -> " ([${proposal.choiceArg}])"
            else -> ""
        }
        return "[${proposal.resolutionType}]$tail by [$proposer] " +
            "(${proposal.totalFor()} for / ${proposal.totalAgainst()} against)"
    }

    // endregion

    companion object {
        /** Whether [viewingCiv] should be prompted to open the congress screen this turn. */
        fun shouldPrompt(viewingCiv: Civilization): Boolean {
            val congress = viewingCiv.gameInfo.congress
            if (!congress.isFounded || !congress.isMember(viewingCiv.civID)) return false
            return when (congress.currentPhase) {
                CongressPhase.Proposing -> !congress.hasProposed(viewingCiv.civID)
                CongressPhase.Voting -> congress.activeProposals.any { !it.hasVoted(viewingCiv.civID) }
                else -> false
            }
        }
    }
}
