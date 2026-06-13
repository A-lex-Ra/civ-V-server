package com.unciv.logic.multiplayer.v2.session

import com.unciv.logic.GameInfo
import com.unciv.logic.multiplayer.v2.command.CommandExecutor
import com.unciv.network.Checksum
import com.unciv.network.game.GameFrame

/**
 * The authoritative game session: owns the canonical [GameInfo], collects player submissions,
 * resolves a turn deterministically, emits the resulting [GameFrame.ResolvedTurn] plus a
 * [Checksum], and serves [GameFrame.StateCheckpoint]s for joining or desynced peers.
 *
 * The same class runs in **client-host** mode (a player's client *is* the authority) and in a
 * future **dedicated-server** mode (it runs in the server process), reachable via the relay
 * exactly like any other host.
 *
 * Phase 0: stub. Sequential resolution lands in Phase 3, simultaneous turns in Phase 5.
 */
class GameSession(
    /** The canonical, authoritative game state owned by this session. */
    val gameInfo: GameInfo
) {
    private val commandExecutor = CommandExecutor()

    /**
     * Handle an inbound [GameFrame] from a client (commands, submissions, checksum reports,
     * resync requests). Implemented incrementally from Phase 3.
     *
     * @throws NotImplementedError until session handling is implemented.
     */
    fun onFrame(frame: GameFrame): Unit =
        throw NotImplementedError("GameSession.onFrame is implemented from Phase 3 (see docs/multiplayer-v2.md)")

    /**
     * Compute a deterministic checksum of the current canonical state, excluding wall-clock and
     * other non-deterministic fields (see the determinism audit in docs/multiplayer-v2.md).
     *
     * @throws NotImplementedError until the checksum is implemented (Phase 2 harness).
     */
    fun checksum(): Checksum =
        throw NotImplementedError("GameSession.checksum is implemented with the Phase 2 determinism harness")
}
