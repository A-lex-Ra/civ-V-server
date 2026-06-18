package com.unciv.app.server

import com.unciv.network.GameId
import com.unciv.network.Protocol
import com.unciv.network.RoomId
import com.unciv.network.UserId
import com.unciv.network.relay.ClientToRelay
import com.unciv.network.relay.PeerRole
import com.unciv.network.relay.RelayToClient
import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.receiveDeserialized
import io.ktor.server.websocket.sendSerialized
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicLong

/**
 * Room-based WebSocket relay for multiplayer v3.
 *
 * The relay is deliberately **dumb about the game**: it only manages room membership, presence
 * and the host role, and forwards opaque [com.unciv.network.game.GameFrame] payloads between the
 * members of a room. It never inspects or mutates game state — that lives in the authoritative
 * `GameSession` running on whichever peer holds the host role.
 *
 * Wire format: sealed [ClientToRelay] in, sealed [RelayToClient] out, serialized with the shared
 * [com.unciv.network.serialization.relayJson] configuration (installed on the WebSockets plugin).
 *
 * Phase 1: rooms, routing and host-role assignment. Host migration is deferred (see
 * docs/multiplayer-v3.md §7); authority/turn logic comes later.
 */
class RelayServer {

    /** A peer connected to a room: its [userId] and the WebSocket [session] used to reach it. */
    private class Peer(val userId: UserId, val session: DefaultWebSocketServerSession)

    /**
     * A relay room hosting a single game. The peer map preserves join order (it's a
     * [LinkedHashMap]) so host election is deterministic. Access is guarded by [lock].
     */
    private class Room(val roomId: RoomId, val gameId: GameId) {
        var hostSession: DefaultWebSocketServerSession? = null
        val peers = LinkedHashMap<DefaultWebSocketServerSession, Peer>()
    }

    private val lock = Any()
    private val rooms = HashMap<RoomId, Room>()
    private val roomCounter = AtomicLong(0L)

    private fun nextRoomId(gameId: GameId): RoomId = "$gameId#${roomCounter.incrementAndGet()}"

    /**
     * Drive a single client connection until it closes. Reads [ClientToRelay] messages and reacts
     * to them; on disconnect it cleans up membership and, if needed, migrates the host role.
     */
    suspend fun handleConnection(session: DefaultWebSocketServerSession) {
        var userId: UserId? = null
        var room: Room? = null
        try {
            while (session.isActive) {
                when (val message = session.receiveDeserialized<ClientToRelay>()) {
                    is ClientToRelay.Hello -> {
                        if (message.protocolVersion != Protocol.VERSION) {
                            session.reply(
                                RelayToClient.Error(
                                    "protocol_mismatch",
                                    "Server speaks protocol ${Protocol.VERSION}, client sent ${message.protocolVersion}"
                                )
                            )
                            session.close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "protocol mismatch"))
                            return
                        }
                        userId = message.userId
                    }

                    is ClientToRelay.CreateRoom -> {
                        val uid = userId ?: run { session.replyNeedHello(); continue }
                        room?.let { leave(it, session) }
                        room = createRoom(uid, session, message.gameId)
                    }

                    is ClientToRelay.JoinRoom -> {
                        val uid = userId ?: run { session.replyNeedHello(); continue }
                        room?.let { leave(it, session) }
                        room = joinRoom(uid, session, message.roomId)
                    }

                    is ClientToRelay.LeaveRoom -> {
                        room?.let { leave(it, session) }
                        room = null
                    }

                    is ClientToRelay.Relay -> {
                        val uid = userId ?: run { session.replyNeedHello(); continue }
                        val current = room ?: run {
                            session.reply(RelayToClient.Error("no_room", "Join a room before relaying"))
                            continue
                        }
                        // Forward verbatim to every other member; the relay never inspects payload.
                        broadcast(current, except = session, RelayToClient.Relayed(uid, message.payload))
                    }

                    is ClientToRelay.RelayTo -> {
                        val uid = userId ?: run { session.replyNeedHello(); continue }
                        val current = room ?: run {
                            session.reply(RelayToClient.Error("no_room", "Join a room before relaying"))
                            continue
                        }
                        // Forward verbatim to the single addressed member; never broadcast (a directed
                        // payload is per-player filtered state). The relay never inspects payload. If the
                        // target is not in the room it is silently dropped.
                        sendTo(current, message.targetUserId, RelayToClient.Relayed(uid, message.payload))
                    }
                }
            }
        } catch (e: CancellationException) {
            // Server/connection shutdown: don't swallow cancellation. Membership cleanup still
            // runs in `finally` (the synchronized removal has no suspension point).
            throw e
        } catch (_: Throwable) {
            // Connection error/closed: fall through to cleanup.
        } finally {
            room?.let { leave(it, session) }
        }
    }

    private suspend fun createRoom(
        uid: UserId,
        session: DefaultWebSocketServerSession,
        gameId: GameId
    ): Room {
        val room = synchronized(lock) {
            val created = Room(nextRoomId(gameId), gameId)
            created.hostSession = session
            created.peers[session] = Peer(uid, session)
            rooms[created.roomId] = created
            created
        }
        session.reply(RelayToClient.Welcome(room.roomId, PeerRole.HOST, peers = emptyList()))
        return room
    }

    private class JoinResult(val room: Room, val role: PeerRole, val existingPeerIds: List<UserId>)

    private suspend fun joinRoom(
        uid: UserId,
        session: DefaultWebSocketServerSession,
        roomId: RoomId
    ): Room? {
        // Mutate membership under the lock without suspending; reply/broadcast happen afterwards.
        val result: JoinResult? = synchronized(lock) {
            val found = rooms[roomId] ?: return@synchronized null
            val existing = found.peers.values.map { it.userId }
            found.peers[session] = Peer(uid, session)
            if (found.hostSession == null) found.hostSession = session
            val role = if (found.hostSession === session) PeerRole.HOST else PeerRole.PLAYER
            JoinResult(found, role, existing)
        }

        if (result == null) {
            session.reply(RelayToClient.Error("no_room", "Unknown room: $roomId"))
            return null
        }

        session.reply(RelayToClient.Welcome(result.room.roomId, result.role, result.existingPeerIds))
        broadcast(result.room, except = session, RelayToClient.PeerJoined(uid))
        return result.room
    }

    private suspend fun leave(room: Room, session: DefaultWebSocketServerSession) {
        val leavingUserId: UserId?
        synchronized(lock) {
            leavingUserId = room.peers.remove(session)?.userId
            // Host migration is deferred (see docs/multiplayer-v3.md §7): a departing host just
            // clears the host slot; re-election/HostChanged is not implemented yet.
            if (room.hostSession === session) room.hostSession = null
            if (room.peers.isEmpty()) rooms.remove(room.roomId)
        }

        if (leavingUserId == null) return
        broadcast(room, except = session, RelayToClient.PeerLeft(leavingUserId))
    }

    /** Snapshot the current member sessions under the lock, then send outside it. */
    private suspend fun broadcast(
        room: Room,
        except: DefaultWebSocketServerSession?,
        message: RelayToClient
    ) {
        val targets = synchronized(lock) {
            room.peers.keys.filter { it !== except }
        }
        for (target in targets) {
            if (target.isActive) runCatching { target.reply(message) }
        }
    }

    /**
     * Deliver [message] to the single peer in [room] whose userId is [targetUserId]. Looks the
     * target up under the lock (reusing the room's peer membership), then sends outside it. If no
     * such peer is in the room the message is silently dropped — the relay does not error on a stale
     * recipient.
     */
    private suspend fun sendTo(room: Room, targetUserId: UserId, message: RelayToClient) {
        val target = synchronized(lock) {
            room.peers.values.firstOrNull { it.userId == targetUserId }?.session
        } ?: return
        if (target.isActive) runCatching { target.reply(message) }
    }

    // Serialize against the sealed parent type so the polymorphic discriminator is emitted.
    private suspend fun DefaultWebSocketServerSession.reply(message: RelayToClient) =
        sendSerialized(message)

    private suspend fun DefaultWebSocketServerSession.replyNeedHello() =
        reply(RelayToClient.Error("no_hello", "Send Hello before any other message"))
}

/** Register the relay `/relay` WebSocket endpoint backed by [relayServer]. */
fun Route.relayRoutes(relayServer: RelayServer) {
    webSocket("/relay") {
        relayServer.handleConnection(this)
    }
}
