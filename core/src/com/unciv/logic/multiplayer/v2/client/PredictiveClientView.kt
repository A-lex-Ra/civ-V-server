package com.unciv.logic.multiplayer.v2.client

import com.unciv.logic.GameInfo
import com.unciv.logic.multiplayer.v2.command.CommandException
import com.unciv.logic.multiplayer.v2.command.CommandExecutor
import com.unciv.network.command.GameCommand
import com.unciv.network.game.GameFrame

/**
 * Phase 4 — **client-side prediction + reconciliation for the player's own actions**
 * (docs/multiplayer-v2.md §6 "prediction", §10 Phase 4). Deliverable: "Own actions feel instant
 * and reconcile against authority deltas."
 *
 * The authority is the only simulator (§3); a client holds only its **visibility-filtered** view.
 * To hide round-trip latency, this holder optimistically applies the player's *own* commands to its
 * local view the instant they are issued ([predict]), then reconciles against the authoritative
 * [GameFrame.PlayerView] when it arrives ([onPlayerView]) — keeping any still-unconfirmed commands
 * visible by replaying them on top of the fresh authoritative base. A wrong prediction is rolled
 * back when the authority rejects it ([onCommandRejected]).
 *
 * ### State model
 *  - **Authoritative base** — the last [GameFrame.PlayerView] received (ground truth). We keep the
 *    raw frame so we can *re-decode* it (full `setTransients()`) for a clean, fully-transient
 *    [GameInfo] every time we rebase. Re-decoding rather than `GameInfo.clone()` is deliberate: the
 *    [CommandExecutor] mutates units through `unit.movement`, which needs the full transients that
 *    only `gameInfoFromString`/`setTransients` rebuild — `clone()` copies serialized fields only and
 *    rebuilds nothing. This also guarantees prediction **never mutates the authoritative base**: each
 *    displayed view is a fresh decode the executor is free to mutate.
 *  - **Pending** — own commands issued locally but not yet confirmed by an authoritative view, each
 *    tagged with its [GameFrame.PlayerCommand.seq], in issue order.
 *  - **Displayed view** ([currentView]) — the predicted [GameInfo] the UI renders: the authoritative
 *    base with every pending command replayed on top.
 *
 * ### Confirmation rule (sequential model — COARSE, flagged)
 * A received [GameFrame.PlayerView] reflects everything the authority has applied so far, so we treat
 * it as confirming **all** pending commands issued before it and clear the whole pending list. This
 * is intentionally coarse: it cannot distinguish a view that reflects only *some* of the in-flight
 * commands. Finer per-command acknowledgements (e.g. an applied-seq cursor on the frame) are a later
 * optimization — see the report. In the sequential turn model the active player streams its commands
 * and the next authoritative view subsumes them, so clearing-all is correct here.
 */
class PredictiveClientView(
    private val playerCivId: String,
    private val commandExecutor: CommandExecutor = CommandExecutor()
) {

    /** One locally-issued, not-yet-confirmed own command, tagged with its wire [seq]. */
    private data class PendingCommand(val seq: Long, val command: GameCommand)

    /** The raw last authoritative view; re-decoded on every rebase for a clean transient GameInfo. */
    private var authoritativeFrame: GameFrame.PlayerView? = null

    /**
     * The authoritative base (ground truth) as last decoded — fully transient, **never** mutated by
     * prediction. `null` before the first [onPlayerView]. Exposed read-only for the UI/tests so they
     * can compare the predicted view against authority.
     */
    var authoritativeView: GameInfo? = null
        private set

    /** The turn number of [authoritativeView], or `-1` before any view has arrived. */
    var turn: Int = -1
        private set

    /**
     * The predicted [GameInfo] the UI renders: the authoritative base with all [pending] commands
     * replayed on top. `null` before the first [onPlayerView]. This is a mutated decode, distinct
     * from [authoritativeView].
     */
    var currentView: GameInfo? = null
        private set

    private val pending = ArrayList<PendingCommand>()

    /**
     * The last command-rejection reason surfaced for the UI layer, or `null` if the most recent
     * reconciliation accepted. Cleared on the next [predict]. The UI can also subscribe via
     * [onRejection].
     */
    var lastRejectionReason: String? = null
        private set

    /** Optional UI callback invoked with `(seq, reason)` whenever a prediction is rejected. */
    var onRejection: ((seq: Long, reason: String) -> Unit)? = null

    /** The seqs of the commands currently predicted-but-unconfirmed, in issue order. */
    val pendingSeqs: List<Long> get() = pending.map { it.seq }

    /** True if there are predicted commands awaiting authoritative confirmation. */
    val hasPending: Boolean get() = pending.isNotEmpty()

    /**
     * Optimistically apply one of the player's **own** commands to the displayed view immediately
     * (instant feel) and track it as [pending] under [seq] so it can be reconciled / rolled back.
     *
     * The command is applied to the *current displayed* [GameInfo] via the [CommandExecutor] — the
     * same choke-point the authority uses — so the prediction matches the authority's own logic.
     *
     * @param seq the [GameFrame.PlayerCommand.seq] this command will be sent under (the caller owns
     *   the sequence counter; we just track the value so we can match the eventual ack/rejection).
     * @throws IllegalStateException if no authoritative view has been received yet (nothing to
     *   predict on top of).
     * @throws CommandException if the prediction is locally illegal against the current view; the
     *   command is **not** added to pending and the displayed view is left untouched. (The authority
     *   re-validates regardless; this just avoids tracking a command we couldn't even apply locally.)
     */
    fun predict(seq: Long, command: GameCommand) {
        val view = currentView
            ?: throw IllegalStateException("Cannot predict before the first authoritative PlayerView")
        lastRejectionReason = null
        // Apply to the displayed view in place — it is already a mutated decode we own.
        commandExecutor.execute(view, playerCivId, command)
        pending.add(PendingCommand(seq, command))
    }

    /**
     * Adopt an authoritative [view] as the new ground truth (reconcile-accept).
     *
     * Decodes the snapshot (gunzip -> `gameInfoFromString` -> full `setTransients()`), makes it the
     * new [authoritativeView], clears the pending commands it confirms (see the coarse confirmation
     * rule in the class doc — all pending issued before it), then replays any still-pending commands
     * on top of a fresh decode so unconfirmed local actions stay visible.
     *
     * @return the freshly decoded authoritative [GameInfo] (ground truth, unmutated by prediction).
     */
    fun onPlayerView(view: GameFrame.PlayerView): GameInfo {
        authoritativeFrame = view
        turn = view.turn
        val base = GameInfoCodec.decode(view.gzippedFilteredGameInfo)
        authoritativeView = base

        // COARSE confirmation (flagged): a fresh authoritative view subsumes everything issued so
        // far in the sequential model, so every in-flight prediction is now confirmed.
        pending.clear()

        // No pending left to replay -> the displayed view IS the authoritative base.
        rebuildDisplayedView()
        return base
    }

    /**
     * The authority rejected the prediction for [seq] (it was illegal/declined). Drop it from pending
     * and **rebase**: reset the displayed view to a fresh authoritative base and replay the remaining
     * pending commands, so the bad prediction is rolled back while later still-valid predictions
     * survive. [reason] is surfaced via [lastRejectionReason] and [onRejection] for the UI.
     *
     * Rejections for an unknown/already-confirmed [seq] are surfaced (so the UI sees the reason) but
     * cause no rebase, since there is nothing to roll back.
     */
    fun onCommandRejected(seq: Long, reason: String) {
        lastRejectionReason = reason
        onRejection?.invoke(seq, reason)

        val removed = pending.removeAll { it.seq == seq }
        if (removed) rebuildDisplayedView()
    }

    /**
     * Rebuild [currentView] from a *fresh* decode of the authoritative frame with all remaining
     * [pending] commands replayed on top. Re-decoding (not cloning) guarantees a fully-transient
     * GameInfo for the executor and leaves [authoritativeView] untouched.
     *
     * A pending command that no longer applies cleanly against the fresh base (the world moved under
     * it) is dropped rather than aborting the whole rebase — its prediction is simply abandoned, and
     * the authority remains the source of truth.
     */
    private fun rebuildDisplayedView() {
        val frame = authoritativeFrame
        if (frame == null) {
            currentView = null
            return
        }
        // Fresh decode so prediction mutates a throwaway copy, never the authoritative base.
        val display = GameInfoCodec.decode(frame.gzippedFilteredGameInfo)
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val pendingCommand = iterator.next()
            try {
                commandExecutor.execute(display, playerCivId, pendingCommand.command)
            } catch (_: CommandException) {
                // The fresh authoritative base no longer supports this prediction; abandon it.
                iterator.remove()
            }
        }
        currentView = display
    }
}
