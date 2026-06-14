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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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
     * The local player's user id (== its civ's playerId). Set by [hostGame]/[joinGame]. In host mode
     * it identifies the host's own frames so the session's split outbound sink delivers them
     * in-process (option A) instead of over the relay; in both modes it stamps outbound commands.
     */
    private var localUserId: UserId? = null

    /**
     * Fired after each inbound [GameFrame.PlayerView] has been applied to the view holder. The
     * WorldScreen subscribes here to swap in the freshly decoded filtered [GameInfo]. Set by the UI.
     * Fires in **both** modes now: in client mode on the transport receive thread, in host mode
     * in-process when the session pushes the host its own filtered view (option A loopback).
     */
    var onView: ((GameFrame.PlayerView) -> Unit)? = null

    /**
     * The view holder for the **local** player — set in BOTH modes now (option A): a joiner's
     * network view, or the host's own in-process loopback view. Exposes the latest decoded filtered
     * [GameInfo] the local WorldScreen renders.
     */
    var clientView: ClientGameView? = null
        private set

    /**
     * True once the local player has sent `EndTurn` for the current human phase and is waiting for
     * the round to resolve — the WorldScreen reads this to keep input disabled until the next round.
     * Cleared automatically when a [GameFrame.PlayerView] for a later turn arrives (the round
     * advanced). `@Volatile`: written/read across the UI and transport-receive threads.
     */
    @Volatile
    var localEndedTurn: Boolean = false
        private set

    /** The turn number at which the local player last ended; a view past it means the round resolved. */
    @Volatile
    private var localEndedAtTurn: Int = -1

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
        localUserId = hostUserId
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

        // Option A — the host is also a client of its own authority. Its WorldScreen renders this
        // local view holder (NOT the canonical GameInfo), so it never hotseat-controls another civ.
        clientView = ClientGameView()

        // Split outbound sink: the host's OWN frames are delivered in-process (no socket round-trip);
        // every other player's frames go over the relay, directed, exactly as before.
        val h = V2GameHost(t, gameInfo, roster) { playerId, frame ->
            if (playerId == hostUserId) deliverLocal(frame)
            else t.send(ClientToRelay.RelayTo(targetUserId = playerId, payload = frame))
        }
        h.start() // installs the host's onMessage handler, replacing the tap above
        host = h
        Log.debug("V2GameManager: hosting v2 game in room %s as %s", welcome.roomId, hostUserId)
        return welcome.roomId
    }

    /**
     * Deliver a frame the session addressed to the **host's own** player id, in-process (option A
     * loopback). A [GameFrame.PlayerView] is decoded into [clientView] and surfaced through the same
     * [onView] path a network client uses, so the host's WorldScreen refreshes identically; a
     * [GameFrame.CommandRejected] for the host's own command is logged (the predictive/undo handling
     * a remote client gets is deferred — the next authoritative view reconciles).
     */
    private fun deliverLocal(frame: GameFrame) {
        when (frame) {
            is GameFrame.PlayerView -> {
                clientView?.onPlayerView(frame)
                onIncomingView(frame)
            }
            is GameFrame.CommandRejected ->
                Log.debug("V2GameManager: host's own command rejected (seq %s): %s", frame.seq, frame.reason)
            else -> Unit
        }
    }

    /**
     * Common handling for an inbound filtered view in either mode: clear the local "ended my turn"
     * latch once a view for a later turn arrives (the round resolved → this player may act again),
     * then forward to the UI's [onView] subscriber.
     */
    private fun onIncomingView(frame: GameFrame.PlayerView) {
        if (frame.turn > localEndedAtTurn) localEndedTurn = false
        onView?.invoke(frame)
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
        localUserId = myUserId
        val t = WebSocketRelayTransport(relayUrl(serverUrl), userId = myUserId)
        transport = t

        val view = ClientGameView()
        clientView = view
        t.connect()
        val c = V2GameClient(t, myUserId = myUserId, view = view)
        // Surface inbound views to the UI hook. onView fires AFTER the view holder has applied the
        // snapshot, so a subscriber that reads clientView.currentView sees the decoded GameInfo.
        c.onView = { frame -> onIncomingView(frame) }
        c.start() // installs handler before we join, so the Welcome (host id) is not missed
        t.send(ClientToRelay.JoinRoom(roomId))
        this.roomId = roomId
        client = c
        Log.debug("V2GameManager: joined v2 room %s as %s", roomId, myUserId)
        return c
    }

    /**
     * Route the local player's [GameCommand] to the authority. Uniform across modes (option A): a
     * joiner sends it over the relay; the host injects it straight into its in-process session via
     * [V2GameHost.submitLocal] (same validate/apply path, no socket round-trip). No-op (with a log)
     * if neither side is connected yet.
     */
    fun sendCommand(command: GameCommand) {
        val nextSeq = seq.incrementAndGet()
        val h = host
        if (h != null) {
            val uid = localUserId ?: return
            h.submitLocal(uid, GameFrame.PlayerCommand(seq = nextSeq, playerId = uid, command = command))
            return
        }
        val c = client
        if (c == null) {
            Log.debug("V2GameManager.sendCommand called with no host/client (not connected)")
            return
        }
        c.sendCommand(nextSeq, command)
    }

    /**
     * Send the local player's EndTurn intent (uniform across modes). Latches [localEndedTurn] so the
     * WorldScreen disables input until the round resolves — in the simultaneous model `EndTurn` marks
     * this human done; the turn only advances once every human has ended (see [GameSession]).
     */
    fun sendEndTurn() {
        localEndedTurn = true
        localEndedAtTurn = clientView?.turn ?: -1
        sendCommand(GameCommand.EndTurn)
    }

    /** The latest filtered [GameInfo] the client holds, or null (client mode, before first view). */
    fun currentClientView(): GameInfo? = clientView?.currentView

    /**
     * Ask the authority for an immediate fresh filtered snapshot (client mode only). A joining client
     * needs this because the host only broadcasts a [GameFrame.PlayerView] on EndTurn — without an
     * explicit request, a player who joins mid-turn would block until the next turn advance. Thin
     * pass-through to [V2GameClient.sendResyncRequest]; no-op (with a warning) if there is no client.
     *
     * The first inbound [GameFrame.PlayerView] then lands in [clientView] and fires [onView]; callers
     * typically follow this with [awaitFirstView].
     */
    fun requestInitialView() {
        val h = host
        if (h != null) {
            // Host mode (option A): inject a resync for the host's own civ straight into the session.
            // The split sink delivers the resulting snapshot in-process into clientView synchronously,
            // so awaitFirstView() returns immediately.
            val uid = localUserId ?: return
            runCatching { h.submitLocal(uid, GameFrame.ResyncRequest(playerId = uid)) }
            return
        }
        val c = client
        if (c == null) {
            Log.debug("V2GameManager.requestInitialView called with no host/client (not connected)")
            return
        }
        // Best-effort early kick. The relay Welcome (which latches the host's UserId on the client)
        // is processed asynchronously on the transport receive thread, so right after joinGame()
        // returns the host id is usually not known yet and sendResyncRequest() would throw. Swallow
        // that here: awaitFirstView() re-sends the resync on its poll loop once the host id latches.
        runCatching { c.sendResyncRequest() }
    }

    /**
     * Suspend until the client has decoded its first filtered [GameInfo] (the response to
     * [requestInitialView]), or [timeout] elapses. Polls [currentClientView]; the host id must
     * already be known for [requestInitialView] to have reached the authority, so this also retries
     * the resync request periodically in case the very first one raced the relay `Welcome`.
     *
     * @return the first filtered [GameInfo], or `null` on timeout (caller should error + [close]).
     */
    suspend fun awaitFirstView(timeout: Duration = FIRST_VIEW_TIMEOUT): GameInfo? =
        withTimeoutOrNull(timeout) {
            var view = currentClientView()
            while (view == null) {
                delay(VIEW_POLL_INTERVAL)
                // Re-request: the host id is latched off the relay Welcome on the receive thread, so
                // the first requestInitialView() may have run before the host was known and silently
                // thrown inside the client. A cheap periodic retry covers that startup race. (Host
                // mode resolves on the first call, so this is a no-op retry there.)
                requestInitialView()
                view = currentClientView()
            }
            view
        }

    /** Tear down the transport and drop references. Safe to call multiple times. */
    fun close() {
        runCatching { transport?.close() }
        transport = null
        host = null
        client = null
        clientView = null
        localUserId = null
        localEndedTurn = false
        localEndedAtTurn = -1
    }

    companion object {
        private val HANDSHAKE_TIMEOUT = 15.seconds

        /** Default ceiling for [awaitFirstView] — how long a joining client waits for its first snapshot. */
        private val FIRST_VIEW_TIMEOUT = 20.seconds

        /** Poll/retry cadence inside [awaitFirstView]. */
        private val VIEW_POLL_INTERVAL = 250.milliseconds

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
