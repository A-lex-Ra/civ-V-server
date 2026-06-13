package com.unciv.logic.multiplayer.v2.command

import com.unciv.logic.GameInfo
import com.unciv.network.command.GameCommand

/**
 * The single choke-point that mutates [GameInfo] in multiplayer v2.
 *
 * The authority validates each [GameCommand] against the canonical state (legal mover,
 * ownership, resources, range, …) and then applies it deterministically. Replicas apply the
 * same ordered commands to converge on identical state.
 *
 * Phase 0: stub. The first real command (`MoveUnit`) is routed through here in Phase 2,
 * together with the determinism test harness.
 */
class CommandExecutor {

    /**
     * Validate then apply [command] to [gameInfo]. Implemented incrementally from Phase 2.
     *
     * @throws NotImplementedError until command handling is implemented.
     */
    fun execute(gameInfo: GameInfo, command: GameCommand): Unit =
        throw NotImplementedError("CommandExecutor.execute is implemented from Phase 2 (see docs/multiplayer-v2.md)")
}
