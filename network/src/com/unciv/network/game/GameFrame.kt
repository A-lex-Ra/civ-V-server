package com.unciv.network.game

import com.unciv.network.Checksum
import com.unciv.network.PlayerId
import com.unciv.network.command.GameCommand
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Messages exchanged between the authority (host) and clients. These are carried **opaquely**
 * inside the relay envelopes ([com.unciv.network.relay.ClientToRelay.Relay] /
 * [com.unciv.network.relay.RelayToClient.Relayed]) — the relay routes them by membership and
 * never inspects their game content.
 *
 * Sealed + [SerialName] discriminated so the set is additive across protocol versions.
 *
 * Phase 0: type skeletons only; nothing produces or consumes these yet.
 */
@Serializable
sealed interface GameFrame {

    /** Client -> host: a single command from a player, sequenced for ordering/dedup. */
    @Serializable
    @SerialName("playerCommand")
    data class PlayerCommand(
        val seq: Long,
        val playerId: PlayerId,
        val command: GameCommand
    ) : GameFrame

    /** Client -> host: a batch of commands for a simultaneous turn, with a done flag. */
    @Serializable
    @SerialName("turnSubmission")
    data class TurnSubmission(
        val turn: Int,
        val playerId: PlayerId,
        val commands: List<GameCommand>,
        val done: Boolean
    ) : GameFrame

    /** Host -> all: the canonical, ordered command sequence for a turn plus the post-state checksum. */
    @Serializable
    @SerialName("resolvedTurn")
    data class ResolvedTurn(
        val turn: Int,
        val orderedCommands: List<PlayerCommand>,
        val postChecksum: Checksum
    ) : GameFrame

    /**
     * Host -> joining/desynced peer: a full state snapshot to (re)synchronise from.
     * [compatVersion] reuses the existing `GameInfo` save-compatibility machinery.
     */
    @Serializable
    @SerialName("stateCheckpoint")
    data class StateCheckpoint(
        val turn: Int,
        val compatVersion: Int,
        val gzippedGameInfo: ByteArray
    ) : GameFrame {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is StateCheckpoint) return false
            return turn == other.turn &&
                compatVersion == other.compatVersion &&
                gzippedGameInfo.contentEquals(other.gzippedGameInfo)
        }

        override fun hashCode(): Int {
            var result = turn
            result = 31 * result + compatVersion
            result = 31 * result + gzippedGameInfo.contentHashCode()
            return result
        }
    }

    /** Client -> host: reports the client's locally computed checksum, for drift detection. */
    @Serializable
    @SerialName("checksumReport")
    data class ChecksumReport(
        val turn: Int,
        val checksum: Checksum
    ) : GameFrame

    /** Client -> host: requests a fresh [StateCheckpoint] because it detected a desync. */
    @Serializable
    @SerialName("resyncRequest")
    data object ResyncRequest : GameFrame

    /** Host -> client: acknowledges a resync; a [StateCheckpoint] follows. */
    @Serializable
    @SerialName("resyncGrant")
    data object ResyncGrant : GameFrame

    /**
     * Host -> a SPECIFIC client: that player's full **visibility-filtered** snapshot of the game
     * (see docs/multiplayer-v2.md §5). The authority projects the canonical `GameInfo` down to what
     * this player may legally see, serialises it to JSON and gzips it into [gzippedFilteredGameInfo].
     *
     * Because the snapshot is per-player and redacted, it must be delivered **directed**
     * ([com.unciv.network.relay.ClientToRelay.RelayTo]) — never broadcast, which would leak one
     * player's filtered state to the whole room.
     *
     * [compatVersion] reuses the existing `GameInfo` save-compatibility machinery (the receiving
     * client decodes it through the same `gameInfoFromString` path as a save file).
     *
     * Carries a [ByteArray], so [equals]/[hashCode] use content comparison (mirrors
     * [StateCheckpoint]).
     */
    @Serializable
    @SerialName("playerView")
    data class PlayerView(
        val turn: Int,
        val compatVersion: Int,
        val gzippedFilteredGameInfo: ByteArray
    ) : GameFrame {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PlayerView) return false
            return turn == other.turn &&
                compatVersion == other.compatVersion &&
                gzippedFilteredGameInfo.contentEquals(other.gzippedFilteredGameInfo)
        }

        override fun hashCode(): Int {
            var result = turn
            result = 31 * result + compatVersion
            result = 31 * result + gzippedFilteredGameInfo.contentHashCode()
            return result
        }
    }

    /**
     * Host -> client: the player's command (carried by an enclosing [PlayerCommand]) was illegal or
     * declined. [seq] echoes the rejected [PlayerCommand.seq]; [reason] is the
     * `CommandException` message. The canonical state was left untouched.
     */
    @Serializable
    @SerialName("commandRejected")
    data class CommandRejected(
        val seq: Long,
        val reason: String
    ) : GameFrame
}
