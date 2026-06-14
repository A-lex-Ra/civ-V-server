package com.unciv.logic.multiplayer.v2.session

import com.unciv.logic.CompatibilityVersion
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.multiplayer.v2.client.GameInfoCodec
import com.unciv.logic.multiplayer.v2.command.CommandException
import com.unciv.logic.multiplayer.v2.command.CommandExecutor
import com.unciv.logic.multiplayer.v2.visibility.PlayerViewProjector
import com.unciv.network.Checksum
import com.unciv.network.PlayerId
import com.unciv.network.command.GameCommand
import com.unciv.network.game.GameFrame

/**
 * The authoritative game session: owns the canonical [GameInfo], applies player commands through
 * the single [CommandExecutor] choke-point, runs inter-turn processing on `EndTurn`, and emits each
 * connected player their **own** visibility-filtered [GameFrame.PlayerView].
 *
 * The same class runs in **client-host** mode (a player's client *is* the authority) and in
 * **dedicated-server** mode (it runs in the server process), reachable via the relay exactly like
 * any other host. To stay transport-agnostic it does **not** know about relay envelopes: it decides
 * *what* frame goes to *which* [PlayerId] and hands it to the injected [outbound] sink; the sink's
 * owner maps [PlayerId] -> routing (wrapping per-player frames in
 * [com.unciv.network.relay.ClientToRelay.RelayTo], broadcasts in `Relay`, or sending directly in
 * dedicated mode).
 *
 * Phase 3 (sequential): a player's client streams [GameFrame.PlayerCommand]s; the authority
 * validates+applies each and, on `EndTurn`, runs [GameInfo.nextTurn] and pushes a fresh per-player
 * filtered snapshot.
 *
 * Phase 5 (simultaneous): clients instead send a whole-turn [GameFrame.TurnSubmission]; the
 * authority buffers one per player and, once every rostered human player is `done` (or a future
 * timer calls [forceResolveTurn]), resolves the turn by applying all buffered commands in a single
 * deterministic order through the same executor choke-point, rejecting conflicting losers, then runs
 * `nextTurn` and pushes the same per-player snapshots. The sequential path is unchanged.
 */
class GameSession(
    /** The canonical, authoritative game state owned by this session. */
    val gameInfo: GameInfo,
    /**
     * Maps each connected [PlayerId] to the civ id (`Civilization.civID`) it controls. A
     * [GameFrame.PlayerCommand]'s `playerId` is resolved through this to the acting civ. The set of
     * human players that receive [GameFrame.PlayerView] snapshots is derived from this roster.
     */
    private val roster: Map<PlayerId, String>,
    /**
     * Outbound sink: the session calls this to send a [GameFrame] to a specific [PlayerId]. The sink
     * owner is responsible for the actual transport (relay `RelayTo` / dedicated direct send). Kept
     * here so the same [GameSession] works unchanged in both authority modes.
     */
    private val outbound: (PlayerId, GameFrame) -> Unit
) {
    private val commandExecutor = CommandExecutor()

    /** Phase 5: per-player buffering + deterministic ordering for the in-flight simultaneous turn. */
    private val simultaneousResolver = SimultaneousTurnResolver()

    /**
     * Streaming-simultaneous turn barrier (the path the live UI uses): the set of human players who
     * have sent their `EndTurn` intent for the **current** human phase. Commands stream in and apply
     * immediately ([onPlayerCommand]); `EndTurn` does not advance the turn, it just records that this
     * human is done. Once every rostered human is done the round resolves (see [onEndTurn]). Distinct
     * from the buffered [simultaneousResolver] path (whole-turn [GameFrame.TurnSubmission]s), which is
     * retained for the prediction/conflict tests.
     */
    private val endedThisPhase = mutableSetOf<PlayerId>()

    /**
     * Handle an inbound [GameFrame] from a client. Phase 3 handles [GameFrame.PlayerCommand]
     * (single-command sequential play); Phase 5 adds [GameFrame.TurnSubmission] (simultaneous turns).
     * Other inbound frame kinds are ignored for now (they belong to later phases — acks, resync).
     *
     * `@Synchronized`: in option A the host process is also a local client of this authority, so
     * frames now arrive from two threads — the transport receive thread (remote players) and the UI
     * thread (the host's own [com.unciv.logic.multiplayer.v2.net.V2GameHost.submitLocal]). Serialising
     * here keeps all canonical-state mutation single-threaded without the callers needing to know.
     */
    @Synchronized
    fun onFrame(frame: GameFrame) {
        when (frame) {
            is GameFrame.PlayerCommand -> onPlayerCommand(frame)
            is GameFrame.TurnSubmission -> onTurnSubmission(frame)
            is GameFrame.ResyncRequest -> onResyncRequest(frame)
            else -> Unit // Ack and the provisional lockstep frames: not used by v2.
        }
    }

    private fun onPlayerCommand(frame: GameFrame.PlayerCommand) {
        // Resolve the acting civ from the roster. An unknown playerId is itself a rejected command.
        val civId = roster[frame.playerId]
        if (civId == null) {
            outbound(frame.playerId, GameFrame.CommandRejected(frame.seq, "Unknown player '${frame.playerId}'"))
            return
        }

        // EndTurn is session-level orchestration (inter-turn processing + per-player snapshots), not
        // a single-GameInfo mutation, so we branch here BEFORE the executor rather than teaching the
        // CommandExecutor about nextTurn. The executor stays the choke-point for *mutations*; turn
        // advancement and view projection live in the session. (Design decision — see report.)
        if (frame.command is GameCommand.EndTurn) {
            onEndTurn(frame.playerId)
            return
        }

        // All other commands go through the single mutation choke-point. A rejection becomes a
        // directed CommandRejected to the issuing player; the canonical state is left untouched.
        try {
            commandExecutor.execute(gameInfo, civId, frame.command)
        } catch (e: CommandException) {
            outbound(frame.playerId, GameFrame.CommandRejected(frame.seq, e.message ?: "Command rejected"))
        }
    }

    /**
     * Phase 5 — simultaneous turns. Buffer a player's whole-turn [GameFrame.TurnSubmission] for the
     * current turn. An unknown/non-human player id is rejected. Once every rostered **human** player
     * has submitted with `done = true`, the turn resolves automatically (see [resolveTurn]); a future
     * per-turn timer can instead call [forceResolveTurn] to resolve with whatever has arrived.
     *
     * The submission *is* the player's whole turn — there is no separate `EndTurn` frame in the
     * simultaneous model (any `EndTurn` command inside the batch is dropped at resolution time).
     */
    private fun onTurnSubmission(frame: GameFrame.TurnSubmission) {
        val civId = roster[frame.playerId]
        if (civId == null || !isHuman(civId)) {
            // A submission carries no per-command seq; echo seq 0 so the issuer can correlate the
            // whole batch being declined. (Design note — see report: TurnSubmission has no seq field.)
            outbound(frame.playerId, GameFrame.CommandRejected(0, "Unknown or non-human player '${frame.playerId}'"))
            return
        }
        simultaneousResolver.accept(frame)
        if (simultaneousResolver.isReadyToResolve(expectedHumanPlayers()))
            resolveTurn()
    }

    /**
     * Phase 6 — reconnection / desync recovery (docs/multiplayer-v2.md §10). A (re)connecting or
     * desynced client sends a [GameFrame.ResyncRequest]; the authority answers with a **fresh**
     * directed [GameFrame.PlayerView] projected from the *current* canonical state — mid-turn, on
     * demand, not only at turn boundaries. Because the authority ships full per-player filtered
     * snapshots (not incremental semantic deltas yet), reconnection is simply "send a fresh full
     * PlayerView"; no missed-delta replay / [GameFrame.Ack] cursor is needed (see report).
     *
     * An unknown or non-human requester is rejected like the other handlers ([GameFrame.CommandRejected]),
     * and **no** [GameFrame.PlayerView] is emitted.
     *
     * **SECURITY (cross-cutting TODO — not solved here).** The authority currently trusts
     * [GameFrame.ResyncRequest.playerId] verbatim, exactly as it trusts [GameFrame.PlayerCommand.playerId].
     * A hostile client could therefore request *another* player's filtered view and read state it may
     * not see — a leak that violates hidden-information goal #3 (docs/multiplayer-v2.md §8). When the
     * transport->session host loop is wired, the requester's identity MUST be bound to the connection
     * (the relay's `Relayed.fromId`), not taken from the frame, before this snapshot is sent. The
     * wiring that would enforce this does not exist yet (tests drive [onFrame] directly).
     */
    private fun onResyncRequest(frame: GameFrame.ResyncRequest) {
        val civId = roster[frame.playerId]
        if (civId == null || !isHuman(civId)) {
            outbound(frame.playerId, GameFrame.CommandRejected(0, "Unknown or non-human player '${frame.playerId}'"))
            return
        }
        // A directed, current-state snapshot — the executor/turn paths are untouched.
        sendSnapshotTo(frame.playerId, civId)
    }

    /**
     * Force-resolve the current simultaneous turn with whatever submissions have arrived so far —
     * the hook a future per-turn timer calls when not every player marked `done`. A player who did
     * not submit simply contributes no commands this turn. No-op if nothing is buffered.
     */
    fun forceResolveTurn() {
        if (simultaneousResolver.hasBufferedSubmissions())
            resolveTurn()
    }

    /**
     * Resolve one simultaneous turn: apply every buffered command in the single canonical order
     * `(submissionArrivalIndex, playerId, seq)` through the [CommandExecutor] choke-point, emitting a
     * directed [GameFrame.CommandRejected] for any the executor declines (e.g. a movement conflict
     * because the destination tile is now occupied by an already-applied move) and skipping it.
     * Then run inter-turn processing **once** and push each human player a fresh filtered snapshot.
     *
     * First-cut conflict model: deterministic ordered application + executor-driven rejection of the
     * losers. Deeper conflict rules (simultaneous combat, contested city-capture) are deferred (§11).
     */
    private fun resolveTurn() {
        for (ordered in simultaneousResolver.orderedCommands()) {
            val civId = roster[ordered.playerId] ?: continue
            try {
                commandExecutor.execute(gameInfo, civId, ordered.command)
            } catch (e: CommandException) {
                outbound(ordered.playerId, GameFrame.CommandRejected(ordered.seq, e.message ?: "Command rejected"))
            }
        }
        simultaneousResolver.reset()
        gameInfo.nextTurn()
        broadcastPlayerViews()
    }

    /** The rostered players whose civ is human — the set whose `done` submissions gate resolution. */
    private fun expectedHumanPlayers(): Set<PlayerId> =
        roster.filterValues { isHuman(it) }.keys

    private fun isHuman(civId: String): Boolean =
        gameInfo.getCivilizationOrNull(civId)?.playerType == PlayerType.Human

    /**
     * Streaming-simultaneous `EndTurn` (the live UI path). A human's commands have already streamed
     * in and applied this phase; `EndTurn` only records that this human is **done**. The turn does
     * not advance until *every* rostered human is done — that is what lets two humans act at the same
     * time during the human phase while the AI runs separately (the user's design goal).
     *
     *  - **Everyone done →** [resolveRound]: advance a full round (each human's inter-turn processing
     *    plus all AI), reset the phase, and push everyone a fresh human-phase snapshot.
     *  - **Some still acting →** nothing is pushed. The player who just ended already disabled its own
     *    input locally before sending the intent, and the players still acting must not have their
     *    screens disrupted mid-turn by another player ending. The single round-resolution broadcast is
     *    the only view churn per round.
     *
     * With a single human this degrades exactly to the old sequential behaviour: one `EndTurn` →
     * one `nextTurn()` → broadcast.
     */
    private fun onEndTurn(playerId: PlayerId) {
        endedThisPhase.add(playerId)
        val expected = expectedHumanPlayers()
        if (endedThisPhase.containsAll(expected)) {
            resolveRound(expected.size)
            endedThisPhase.clear()
        }
    }

    /**
     * Advance one full simultaneous round. The engine's [GameInfo.nextTurn] ends the *current* human,
     * auto-processes every AI civ after it, and stops at the *next* human — so cycling through all
     * [humanCount] humans (and the AI between them) and back to a fresh human phase is exactly one
     * `nextTurn()` per human. Then push each human their fresh post-round snapshot.
     *
     * (First-cut: assumes the human count is stable across the round. A human eliminated mid-game no
     * longer submits `EndTurn`, so robust handling of that — not gating the barrier on dead humans —
     * is a documented follow-up, deferred per docs/multiplayer-v2.md §11.)
     */
    private fun resolveRound(humanCount: Int) {
        repeat(humanCount.coerceAtLeast(1)) { gameInfo.nextTurn() }
        broadcastPlayerViews()
    }

    /** Project, encode and send each human player in the roster their own filtered snapshot. */
    private fun broadcastPlayerViews() {
        for ((playerId, civId) in roster) {
            if (!isHuman(civId)) continue
            sendSnapshotTo(playerId, civId)
        }
    }

    /**
     * Project the current canonical state down to [civId]'s visibility-filtered view, encode it and
     * send it as a directed [GameFrame.PlayerView] to [playerId]. The single projection+encode path
     * shared by the turn-boundary broadcast ([broadcastPlayerViews]) and the on-demand resync
     * ([onResyncRequest]) — so a reconnecting client gets exactly the snapshot it would have received
     * at the next turn boundary, just computed now against the live state.
     *
     * No-op if the civ is gone (e.g. eliminated since the roster was built). Caller is responsible
     * for the human/roster checks it cares about; [onResyncRequest] also rejects non-human ids.
     */
    private fun sendSnapshotTo(playerId: PlayerId, civId: String) {
        gameInfo.getCivilizationOrNull(civId) ?: return
        val projected = PlayerViewProjector.projectFor(gameInfo, civId)
        val bytes = GameInfoCodec.encode(projected)
        outbound(
            playerId,
            GameFrame.PlayerView(
                turn = gameInfo.turns,
                compatVersion = CURRENT_COMPAT_VERSION,
                gzippedFilteredGameInfo = bytes
            )
        )
    }

    /**
     * Compute a deterministic checksum of the current canonical state. Not needed this stage — v2 is
     * authoritative (clients never re-simulate), so there is no convergence checksum requirement
     * (docs/multiplayer-v2.md §3). Left unimplemented.
     *
     * @throws NotImplementedError — no determinism/checksum requirement in v2.
     */
    fun checksum(): Checksum =
        throw NotImplementedError("GameSession.checksum: v2 has no determinism/convergence checksum (see docs/multiplayer-v2.md §3)")

    companion object {
        /** The save-compatibility version stamped into each [GameFrame.PlayerView]. */
        private val CURRENT_COMPAT_VERSION = CompatibilityVersion.CURRENT_COMPATIBILITY_NUMBER
    }
}
