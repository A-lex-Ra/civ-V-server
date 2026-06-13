package com.unciv.network.relay

import com.unciv.network.GameId
import com.unciv.network.RoomId
import com.unciv.network.UserId
import com.unciv.network.game.GameFrame
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The role a peer plays within a relay room.
 *
 * The room creator becomes the initial [HOST] (runs the authoritative `GameSession`); everyone
 * else is a [PLAYER] replica. [SPECTATOR] is reserved for a future observer mode.
 */
@Serializable
enum class PeerRole { HOST, PLAYER, SPECTATOR }

/**
 * Messages sent from a client to the public relay.
 *
 * The relay only ever inspects routing/membership fields. The game payload inside [Relay] is
 * opaque to it — it is forwarded verbatim to the other room members.
 */
@Serializable
sealed interface ClientToRelay {

    /** Handshake: announces the protocol version and authenticates the user. */
    @Serializable
    @SerialName("hello")
    data class Hello(
        val protocolVersion: Int,
        val userId: UserId,
        val auth: String? = null
    ) : ClientToRelay

    /** Create a new room for [gameId]; the creator becomes the [PeerRole.HOST]. */
    @Serializable
    @SerialName("createRoom")
    data class CreateRoom(val gameId: GameId) : ClientToRelay

    /** Join an existing room. */
    @Serializable
    @SerialName("joinRoom")
    data class JoinRoom(val roomId: RoomId) : ClientToRelay

    /** Leave the current room. */
    @Serializable
    @SerialName("leaveRoom")
    data object LeaveRoom : ClientToRelay

    /** Forward an opaque [GameFrame] to the room (relay never inspects [payload]). */
    @Serializable
    @SerialName("relay")
    data class Relay(val payload: GameFrame) : ClientToRelay

    /**
     * Forward an opaque [GameFrame] to a **single** peer ([targetUserId]) in the room, rather than
     * broadcasting it. The relay routes by membership-level [UserId] only and still never inspects
     * [payload].
     *
     * This is how the authority delivers each player their own **visibility-filtered** snapshot /
     * delta (e.g. [com.unciv.network.game.GameFrame.PlayerView]): a per-player redacted payload must
     * not be broadcast, or it would leak one player's filtered state to the others.
     */
    @Serializable
    @SerialName("relayTo")
    data class RelayTo(val targetUserId: UserId, val payload: GameFrame) : ClientToRelay
}

/**
 * Messages sent from the public relay to a client.
 */
@Serializable
sealed interface RelayToClient {

    /** Confirms joining/creating a room, the assigned [role], and the current [peers]. */
    @Serializable
    @SerialName("welcome")
    data class Welcome(
        val roomId: RoomId,
        val role: PeerRole,
        val peers: List<UserId>
    ) : RelayToClient

    /** A peer joined the room. */
    @Serializable
    @SerialName("peerJoined")
    data class PeerJoined(val userId: UserId) : RelayToClient

    /** A peer left the room. */
    @Serializable
    @SerialName("peerLeft")
    data class PeerLeft(val userId: UserId) : RelayToClient

    /** An opaque [GameFrame] forwarded from [fromId] (relay never inspects [payload]). */
    @Serializable
    @SerialName("relayed")
    data class Relayed(val fromId: UserId, val payload: GameFrame) : RelayToClient

    /** An error response (bad handshake, unknown room, auth failure, …). */
    @Serializable
    @SerialName("error")
    data class Error(val code: String, val message: String) : RelayToClient
}
