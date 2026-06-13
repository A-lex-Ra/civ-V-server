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
 * filtered snapshot. Simultaneous turns land in Phase 5.
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

    /**
     * Handle an inbound [GameFrame] from a client. Phase 3 handles [GameFrame.PlayerCommand]
     * (single-command sequential play); other inbound frame kinds are ignored for now (they belong
     * to later phases — simultaneous turns, acks, resync).
     */
    fun onFrame(frame: GameFrame) {
        when (frame) {
            is GameFrame.PlayerCommand -> onPlayerCommand(frame)
            else -> Unit // TurnSubmission/Ack/ResyncRequest: later phases.
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
            runEndTurn()
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
     * Run inter-turn processing on the authority and push each human player their own
     * visibility-filtered [GameFrame.PlayerView]. (Sequential model: EndTurn from the active player
     * advances the turn; the resulting state goes out as fresh per-player snapshots.)
     */
    private fun runEndTurn() {
        gameInfo.nextTurn()
        broadcastPlayerViews()
    }

    /** Project, encode and send each human player in the roster their own filtered snapshot. */
    private fun broadcastPlayerViews() {
        for ((playerId, civId) in roster) {
            val civ = gameInfo.getCivilizationOrNull(civId) ?: continue
            if (civ.playerType != PlayerType.Human) continue
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
