package com.unciv.logic.multiplayer.v3.session

import com.unciv.network.PlayerId
import com.unciv.network.command.GameCommand
import com.unciv.network.game.GameFrame

/**
 * Phase 5 — simultaneous-turn buffering + deterministic resolution ordering, authority-side
 * (docs/multiplayer-v3.md §6 "Simultaneous", §10).
 *
 * This is a pure, engine-free helper that owns the *bookkeeping* for one in-flight simultaneous
 * turn: it buffers each rostered human player's [GameFrame.TurnSubmission], decides **when** the
 * turn is ready to resolve (all expected players submitted with `done = true`), and produces the
 * single canonical, deterministically-ordered command sequence to apply. It deliberately knows
 * nothing about [com.unciv.logic.GameInfo], the executor, or visibility — [GameSession] drives all
 * of that through the [CommandExecutor] choke-point. Keeping the buffering here keeps the session
 * class small and the ordering logic unit-reasoned in one place.
 *
 * ### Ordering (the first-cut conflict model)
 *
 * The doc suggests ordering by `(submissionIndex, playerId, seq)`. We implement exactly that:
 *  - **submissionArrivalIndex** — a monotonic counter assigned the **first** time a given player
 *    submits for this turn (stable across re-submissions, so a player who edits and re-sends does
 *    not jump the queue). Earlier submitters win contested resources.
 *  - **playerId** — tiebreak between commands that somehow share an arrival index (lexicographic).
 *  - **seq** — the command's index **within** that player's submission (its intra-turn order).
 *
 * Conflict resolution is then *emergent*: commands are applied in this order through the executor,
 * and a command the executor rejects (e.g. a move whose destination an earlier-ordered move already
 * occupied) is reported back to its issuer and skipped — the canonical state reflects only the
 * winner. Deeper, semantic conflict rules (simultaneous combat resolution, contested city-capture
 * races) are explicitly deferred per docs/multiplayer-v3.md §11.
 */
internal class SimultaneousTurnResolver {

    /** One player's buffered submission for the current turn, plus the arrival index used for ordering. */
    private class Buffered(
        val arrivalIndex: Int,
        var commands: List<GameCommand>,
        var done: Boolean
    )

    /** A single command pulled from the buffer, carrying everything needed to order it and to route a rejection. */
    class OrderedCommand(
        val playerId: PlayerId,
        /** Index of this command within its issuer's submission — echoed as the [GameFrame.CommandRejected.seq]. */
        val seq: Long,
        val command: GameCommand
    )

    /** playerId -> buffered submission for the turn currently being collected. Insertion-ordered for hygiene. */
    private val buffer = LinkedHashMap<PlayerId, Buffered>()

    /** Monotonic counter; the next arrival index to hand out. Reset when a turn resolves. */
    private var nextArrivalIndex = 0

    /**
     * Buffer [submission] for the current turn. A player re-submitting (e.g. after editing their
     * orders) **replaces** their previous buffer entry but keeps their original arrival index, so
     * re-sending does not change resolution priority.
     */
    fun accept(submission: GameFrame.TurnSubmission) {
        val existing = buffer[submission.playerId]
        if (existing == null) {
            buffer[submission.playerId] = Buffered(nextArrivalIndex++, submission.commands, submission.done)
        } else {
            existing.commands = submission.commands
            existing.done = submission.done
        }
    }

    /**
     * True once **every** id in [expectedPlayers] has buffered a submission with `done = true`. The
     * caller passes the set of rostered human players; AI/non-human civs are processed by
     * `nextTurn()` and never submit.
     */
    fun isReadyToResolve(expectedPlayers: Set<PlayerId>): Boolean =
        expectedPlayers.isNotEmpty() &&
            expectedPlayers.all { buffer[it]?.done == true }

    /** True if any submission has been buffered for the current turn (nothing to force-resolve otherwise). */
    fun hasBufferedSubmissions(): Boolean = buffer.isNotEmpty()

    /**
     * Flatten every buffered submission into the single canonical command sequence, ordered by
     * `(submissionArrivalIndex, playerId, seq)`. Implicit `EndTurn` commands (a submission *is* the
     * player's whole turn) are dropped here so the executor never sees one — turn advancement is the
     * session's job. Does **not** clear the buffer; call [reset] after the session has applied them.
     */
    fun orderedCommands(): List<OrderedCommand> =
        buffer.entries
            .flatMap { (playerId, buffered) ->
                buffered.commands
                    .mapIndexed { index, command -> Triple(buffered.arrivalIndex, OrderedCommand(playerId, index.toLong(), command), playerId) }
            }
            .filter { it.second.command !is GameCommand.EndTurn }
            .sortedWith(
                compareBy({ it.first }, { it.third }, { it.second.seq })
            )
            .map { it.second }

    /** Clear all buffered submissions and arrival indices, ready to collect the next turn. */
    fun reset() {
        buffer.clear()
        nextArrivalIndex = 0
    }
}
