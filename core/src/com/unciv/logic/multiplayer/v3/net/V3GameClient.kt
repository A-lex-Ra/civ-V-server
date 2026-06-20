// SPDX-License-Identifier: LicenseRef-Unciv-v3-ViewOnly
// Copyright (c) 2026 Alexander Rastorguev (A-lex-Ra) <rastorguev2047@gmail.com>
//
// Part of the Unciv multiplayer-v3 netcode — view-only, NOT under the Mozilla
// Public License that covers the rest of this repository. No right to use,
// copy, modify, run, or distribute is granted without written permission;
// permission is gladly given on request (email or GitHub issue).
// Full terms: /LICENSE.v3  ·  License map: /LICENSING.md

package com.unciv.logic.multiplayer.v3.net

import com.unciv.logic.multiplayer.v3.client.ClientGameView
import com.unciv.logic.multiplayer.v3.client.PredictiveClientView
import com.unciv.network.UserId
import com.unciv.network.command.GameCommand
import com.unciv.network.game.GameFrame
import com.unciv.network.relay.ClientToRelay
import com.unciv.network.relay.RelayToClient
import com.unciv.logic.multiplayer.v3.transport.RelayTransport

/**
 * The **player side** of the multiplayer-v3 loop: glues a [RelayTransport] to a client-side view
 * holder so a remote player sends commands to the host and receives its own visibility-filtered
 * snapshots back, all over the relay (docs/multiplayer-v3.md §4).
 *
 * A player never owns the canonical `GameInfo`; it holds only the redacted view the host last sent
 * (a [ClientGameView]) — optionally a [PredictiveClientView] for client-side prediction. This
 * coordinator:
 *
 *  - **inbound** — registers a [RelayTransport.onMessage] handler; on each [RelayToClient.Relayed]
 *    it routes a [GameFrame.PlayerView] into the view holder and surfaces a
 *    [GameFrame.CommandRejected] via [lastRejection] / [onRejection].
 *  - **outbound** — directs frames **to the host only** via [ClientToRelay.RelayTo]: a player's
 *    command must reach the authority, not the rest of the room. [sendCommand] builds and sends a
 *    [GameFrame.PlayerCommand]; [sendEndTurn] / [sendResyncRequest] are thin wrappers over it / a
 *    [GameFrame.ResyncRequest].
 *
 * ## Identity model
 * `PlayerId == UserId` (see [V3GameHost]). The client stamps its own [myUserId] into the
 * `playerId` field of the frames it sends; the host **ignores** that field and rebinds to the
 * connection id anyway (docs/multiplayer-v3.md §8), so the stamped value is only a courtesy /
 * self-documentation, never trusted.
 *
 * ## Learning the host's UserId
 * The relay's [RelayToClient.Welcome.peers] for a joining PLAYER lists the members already in the
 * room, in join order. The room creator (the HOST that runs the `GameSession`) joined first, so it
 * is `peers.first()`. The client latches that as [hostUserId] on the first `Welcome` that carries a
 * non-empty peer list. (Host migration is deferred — docs/multiplayer-v3.md §7 — so a single latch
 * is sufficient for now; reconnection re-runs the handshake and re-latches.)
 *
 * ## Lifecycle / preconditions
 * The caller owns the [transport]: it must be connected and the client must join the room *after*
 * [start] is called, so the `Welcome` (carrying the host id in `peers`) is not missed — register
 * the handler first, exactly as [RelayTransport.onMessage]'s contract advises. This class does not
 * connect, join, or close the transport.
 *
 * No UI and no transport specifics beyond the [RelayTransport] interface.
 */
class V3GameClient(
    private val transport: RelayTransport,
    /** This client's own user id, stamped (untrusted) into outbound frames' `playerId`. */
    val myUserId: UserId,
    /** Plain non-predicting view holder. Mutually exclusive with [predictiveView]. */
    private val view: ClientGameView? = null,
    /** Predicting view holder. When set, inbound views/rejections are reconciled through it. */
    private val predictiveView: PredictiveClientView? = null
) {
    init {
        require((view == null) != (predictiveView == null)) {
            "V3GameClient needs exactly one of view / predictiveView"
        }
    }

    /**
     * The host's user id, learned from the [RelayToClient.Welcome.peers] list (the room creator,
     * first in join order). `null` until the first `Welcome` with a non-empty peer list arrives.
     *
     * `@Volatile`: written on the transport's receive thread, read by senders/UI on other threads.
     */
    @Volatile
    var hostUserId: UserId? = null
        private set

    /**
     * The most recent [GameFrame.CommandRejected] received, or `null` if none. Latest-wins.
     *
     * `@Volatile`: written on the transport's receive thread, read elsewhere.
     */
    @Volatile
    var lastRejection: GameFrame.CommandRejected? = null
        private set

    /** Optional callback fired for every inbound [GameFrame.CommandRejected]. */
    var onRejection: ((GameFrame.CommandRejected) -> Unit)? = null

    /**
     * Optional callback fired **after** each inbound [GameFrame.PlayerView] has been applied to the
     * view holder. The UI subscribes here to repaint; it also gives a memory-safe rendezvous point
     * across the transport's receive thread (the callback can push into a thread-safe primitive).
     */
    var onView: ((GameFrame.PlayerView) -> Unit)? = null

    /**
     * Install the inbound handler on the transport. Call **before** joining the room so the
     * [RelayToClient.Welcome] (which names the host in its peer list) is not missed.
     */
    fun start() {
        transport.onMessage(::onRelayMessage)
    }

    private fun onRelayMessage(message: RelayToClient) {
        when (message) {
            is RelayToClient.Welcome -> latchHost(message.peers)
            is RelayToClient.Relayed -> onRelayed(message.payload)
            else -> Unit // PeerJoined / PeerLeft / Error: no game-frame to route here
        }
    }

    /** Latch the host id from the join-ordered peer list (room creator is first). */
    private fun latchHost(peers: List<UserId>) {
        if (hostUserId == null) hostUserId = peers.firstOrNull()
    }

    private fun onRelayed(payload: GameFrame) {
        when (payload) {
            is GameFrame.PlayerView -> {
                view?.onPlayerView(payload)
                predictiveView?.onPlayerView(payload)
                onView?.invoke(payload)
            }
            is GameFrame.CommandRejected -> {
                lastRejection = payload
                predictiveView?.onCommandRejected(payload.seq, payload.reason)
                onRejection?.invoke(payload)
            }
            else -> Unit // other authority frames aren't consumed by a Phase-3 client
        }
    }

    /**
     * Send one [GameCommand] to the host as a directed [GameFrame.PlayerCommand] under [seq]. The
     * `playerId` is stamped with [myUserId] for self-documentation; the host rebinds it to the
     * connection identity regardless (docs/multiplayer-v3.md §8).
     *
     * @throws IllegalStateException if the host id has not been learned yet (no `Welcome` seen).
     */
    fun sendCommand(seq: Long, command: GameCommand) {
        val host = requireHost()
        transport.send(
            ClientToRelay.RelayTo(
                targetUserId = host,
                payload = GameFrame.PlayerCommand(seq = seq, playerId = myUserId, command = command)
            )
        )
    }

    /** Convenience: send a [GameCommand.EndTurn] command (triggers the host's per-player snapshots). */
    fun sendEndTurn(seq: Long) = sendCommand(seq, GameCommand.EndTurn)

    /**
     * Ask the host for a fresh filtered snapshot ([GameFrame.ResyncRequest]). As with commands, the
     * embedded `playerId` is the courtesy [myUserId]; the host rebinds to the connection id, so a
     * client cannot resync as another player.
     *
     * @throws IllegalStateException if the host id has not been learned yet.
     */
    fun sendResyncRequest() {
        val host = requireHost()
        transport.send(
            ClientToRelay.RelayTo(
                targetUserId = host,
                payload = GameFrame.ResyncRequest(playerId = myUserId)
            )
        )
    }

    private fun requireHost(): UserId = hostUserId
        ?: throw IllegalStateException("Host UserId not known yet — wait for the relay Welcome before sending")
}
