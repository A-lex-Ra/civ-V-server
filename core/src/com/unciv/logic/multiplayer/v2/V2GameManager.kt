package com.unciv.logic.multiplayer.v2

import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.multiplayer.v2.client.ClientGameView
import com.unciv.logic.multiplayer.v2.net.V2GameClient
import com.unciv.logic.multiplayer.v2.net.V2GameHost
import com.unciv.logic.multiplayer.v2.session.GameSession
import com.unciv.logic.multiplayer.v2.transport.WebSocketRelayTransport
import com.unciv.network.RoomId
import com.unciv.network.UserId
import com.unciv.network.command.GameCommand
import com.unciv.network.game.GameFrame
import com.unciv.network.relay.ClientToRelay
import com.unciv.network.relay.RelayToClient
import com.unciv.utils.Log
import io.ktor.http.Url
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

/**
 * EXPERIMENTAL / PREVIEW — the single UI-facing entry point for the multiplayer-v2 (authoritative,
 * command-in / filtered-view-out) netcode (docs/multiplayer-v2.md §4, §7). It owns the v2 game
 * lifecycle for **one** game so the rest of the UI (NewGameScreen, WorldScreen, WorldMapHolder) has
 * exactly one object to talk to and never touches the transport / host / client / session directly.
 *
 * The manager is a thin coordinator over the already-built, unit-tested v2 layer:
 *  - [hostGame] connects a [WebSocketRelayTransport] to the relay's `/relay` endpoint, creates the
 *    room, wraps the canonical [GameInfo] in a [V2GameHost] (which owns a [GameSession]) and starts
 *    it. The host keeps its full canonical state; [GameSession] projects + broadcasts each player a
 *    visibility-filtered [GameFrame.PlayerView] on EndTurn. Returns the relay [RoomId] to share.
 *  - [joinGame] connects, joins the room, wraps a [V2GameClient] around a [ClientGameView] and
 *    starts it; the first [GameFrame.PlayerView] arrives via the view holder and fires [onView].
 *
 * Exactly one of host/client mode is active per instance (see [isHost]). The WorldScreen routes the
 * local player's intents through [sendCommand] / [sendEndTurn] (client mode) and subscribes to
 * [onView] to refresh when the host pushes a new filtered view (client mode).
 *
 * ## Identity model (matches V2GameHost / V2GameClient)
 * `PlayerId == UserId`. The roster handed to the host is `UserId -> civId` directly. The local
 * player's [UserId] is reused from `settings.multiplayer.getUserId()` (same source the v1 path uses),
 * and the relay base URL from the configured multiplayer server (also as v1 does).
 *
 * ## Lifecycle
 * Constructed empty; call [hostGame] **or** [joinGame] once. [close] tears down the transport. The
 * instance is held by [UncivGame] for the duration of the v2 game (see [UncivGame.v2GameManager]).
 */
class V2GameManager {

    /** The relay transport for this game. Non-null after [hostGame]/[joinGame]. */
    private var transport: WebSocketRelayTransport? = null

    /** Authority side, set only in host mode. */
    var host: V2GameHost? = null
        private set

    /** Player side, set only in client mode. */
    var client: V2GameClient? = null
        private set

    /** The relay room id, learned on host/join. `null` until connected. */
    var roomId: RoomId? = null
        private set

    /** True once [hostGame] has run (this process is the authority). */
    val isHost: Boolean get() = host != null

    /** Monotonic per-command sequence number for outbound client commands. */
    private val seq = AtomicLong(0)

    /**
     * Fired (on the transport receive thread) after each inbound [GameFrame.PlayerView] has been
     * applied to the client view holder. The WorldScreen subscribes here to swap in the freshly
     * decoded filtered [GameInfo]. Set by the UI; client mode only.
     */
    var onView: ((GameFrame.PlayerView) -> Unit)? = null

    /** The client's view holder (client mode only); exposes the latest decoded filtered GameInfo. */
    var clientView: ClientGameView? = null
        private set

    /**
     * HOST a v2 game over the relay. Connects, creates the room, wraps [gameInfo] in a [V2GameHost]
     * and starts the authority loop. Returns the relay [RoomId] so the host can share it with the
     * players who will [joinGame] it.
     *
     * @param gameInfo the canonical, authoritative game state this host owns
     * @param serverUrl the configured multiplayer server base URL (http(s)://… or ws(s)://…)
     * @param hostUserId this host's user id (== its civ's playerId; also a roster key)
     * @param roster `UserId -> civId` for every connected player (PlayerId == UserId)
     */
    suspend fun hostGame(
        gameInfo: GameInfo,
        serverUrl: String,
        hostUserId: UserId,
        roster: Map<UserId, String>
    ): RoomId {
        val t = WebSocketRelayTransport(relayUrl(serverUrl), userId = hostUserId)
        transport = t

        // Tap inbound only long enough to await the Welcome (the room id); V2GameHost.start() then
        // installs the authoritative handler and replaces this tap.
        val inbox = Channel<RelayToClient>(Channel.UNLIMITED)
        t.onMessage { inbox.trySend(it) }
        t.connect()
        t.send(ClientToRelay.CreateRoom(gameInfo.gameId))

        val welcome = withTimeout(HANDSHAKE_TIMEOUT) {
            var w: RelayToClient.Welcome? = null
            while (w == null) {
                val m = inbox.receive()
                if (m is RelayToClient.Welcome) w = m
            }
            w
        }
        roomId = welcome.roomId

        val h = V2GameHost(t, gameInfo, roster)
        h.start() // installs the host's onMessage handler, replacing the tap above
        host = h
        Log.debug("V2GameManager: hosting v2 game in room %s as %s", welcome.roomId, hostUserId)
        return welcome.roomId
    }

    /**
     * JOIN a hosted v2 game as a player. Connects, registers the client handler **before** joining
     * (so the host-naming `Welcome` is not missed), joins the room, and starts a [V2GameClient]
     * around a fresh [ClientGameView]. The first filtered [GameFrame.PlayerView] arrives via the
     * view holder and fires [onView].
     *
     * @param roomId the relay room id shared by the host
     * @param serverUrl the configured multiplayer server base URL
     * @param myUserId this client's user id (stamped, untrusted, into outbound frames)
     */
    suspend fun joinGame(
        roomId: RoomId,
        serverUrl: String,
        myUserId: UserId
    ): V2GameClient {
        val t = WebSocketRelayTransport(relayUrl(serverUrl), userId = myUserId)
        transport = t

        val view = ClientGameView()
        clientView = view
        t.connect()
        val c = V2GameClient(t, myUserId = myUserId, view = view)
        // Surface inbound views to the UI hook. onView fires AFTER the view holder has applied the
        // snapshot, so a subscriber that reads clientView.currentView sees the decoded GameInfo.
        c.onView = { frame -> onView?.invoke(frame) }
        c.start() // installs handler before we join, so the Welcome (host id) is not missed
        t.send(ClientToRelay.JoinRoom(roomId))
        this.roomId = roomId
        client = c
        Log.debug("V2GameManager: joined v2 room %s as %s", roomId, myUserId)
        return c
    }

    /**
     * Route the local player's [GameCommand] to the authority. **Client mode only** — a host IS the
     * authority and mutates its canonical GameInfo locally (no command round-trip). No-op (with a
     * warning) if called before a client is connected.
     */
    fun sendCommand(command: GameCommand) {
        val c = client
        if (c == null) {
            Log.debug("V2GameManager.sendCommand called with no client (host mode or not connected)")
            return
        }
        c.sendCommand(seq.incrementAndGet(), command)
    }

    /** Convenience: send the local player's EndTurn intent to the host (client mode only). */
    fun sendEndTurn() = sendCommand(GameCommand.EndTurn)

    /** The latest filtered [GameInfo] the client holds, or null (client mode, before first view). */
    fun currentClientView(): GameInfo? = clientView?.currentView

    /** Tear down the transport and drop references. Safe to call multiple times. */
    fun close() {
        runCatching { transport?.close() }
        transport = null
        host = null
        client = null
        clientView = null
    }

    companion object {
        private val HANDSHAKE_TIMEOUT = 15.seconds

        /**
         * Build the relay WebSocket URL from a configured multiplayer server base URL. Accepts
         * `http(s)://host[:port]` (the v1 server URL form) or an explicit `ws(s)://…` and appends
         * the relay's `/relay` endpoint (see [com.unciv.app.server.relayRoutes]). `https`/`http`
         * map to `wss`/`ws` respectively.
         */
        fun relayUrl(serverUrl: String): Url {
            val trimmed = serverUrl.trimEnd('/')
            val wsBase = when {
                trimmed.startsWith("wss://") || trimmed.startsWith("ws://") -> trimmed
                trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
                trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
                else -> "wss://$trimmed" // bare host: assume TLS
            }
            return Url("$wsBase/relay")
        }

        /**
         * Build the `UserId -> civId` roster from a [GameInfo]: every human civ keyed by its
         * `playerId` (the user UUID set by the lobby). AI civs are not in the roster (they never
         * receive [GameFrame.PlayerView]s). PlayerId == UserId, so this is the [GameSession] roster
         * directly.
         */
        fun rosterFrom(gameInfo: GameInfo): Map<UserId, String> =
            gameInfo.civilizations
                .filter { it.playerType == com.unciv.logic.civilization.PlayerType.Human && it.playerId.isNotEmpty() }
                .associate { it.playerId to it.civID }
    }
}
