package com.unciv.logic.multiplayer.v2.net

import com.unciv.logic.GameInfo
import com.unciv.logic.multiplayer.v2.session.GameSession
import com.unciv.network.PlayerId
import com.unciv.network.UserId
import com.unciv.network.game.GameFrame
import com.unciv.network.relay.ClientToRelay
import com.unciv.network.relay.RelayToClient
import com.unciv.logic.multiplayer.v2.transport.RelayTransport

/**
 * The **authority side** of the multiplayer-v2 host loop: glues a [RelayTransport] to a
 * [GameSession] so a host runs the canonical simulation while remote players reach it through the
 * relay (docs/multiplayer-v2.md §4 — "the host runs `GameSession`; the relay routes opaque
 * frames").
 *
 * Until now the `GameSession` tests drive [GameSession.onFrame] directly; this coordinator is the
 * real-socket glue:
 *
 *  - **inbound** — it registers a [RelayTransport.onMessage] handler. Every inbound
 *    [RelayToClient.Relayed] carries a sender [RelayToClient.Relayed.fromId] (the connection that
 *    sent it, established at the relay's `Hello` handshake) and an opaque [GameFrame] payload. The
 *    host binds the acting player to that connection (see below) and hands the frame to the session.
 *  - **outbound** — it builds the session's `(PlayerId, GameFrame) -> Unit` sink so each per-player
 *    frame is sent **directed** via [ClientToRelay.RelayTo], never broadcast — a per-player
 *    visibility-filtered [GameFrame.PlayerView] must not leak to the rest of the room
 *    (docs/multiplayer-v2.md §5).
 *
 * ## Identity binding (the security fix, docs/multiplayer-v2.md §8)
 * Frames that embed a `playerId` ([GameFrame.PlayerCommand], [GameFrame.TurnSubmission],
 * [GameFrame.ResyncRequest]) are **rebound** to `fromId` before reaching the session: the acting
 * player is *the connection that sent the frame*, never the id the client wrote into the payload.
 * This is what stops a hostile client from spoofing another player's commands or requesting another
 * player's filtered view. The relay authenticates `fromId` at the `Hello` handshake and stamps it on
 * every `Relayed`, so it is trustworthy in a way the in-frame `playerId` is not.
 *
 * ## Identity model
 * `PlayerId == UserId` — the simplest correct mapping. The [GameSession] roster is therefore
 * `UserId -> civId` directly, and the outbound sink can wrap a `PlayerId` straight into
 * `RelayTo(targetUserId = playerId, ...)` with no extra lookup table.
 *
 * ## Lifecycle / preconditions
 * The caller owns the [transport]: it must already be **connected** and have **created the room**
 * (so this host holds the `HOST` role) before constructing the host — exactly as a `GameSession`
 * test sets up its `GameInfo`. This class only wires messages <-> session; it does not connect,
 * create rooms, or close the transport. [start] installs the inbound handler and must be called once.
 *
 * No UI and no transport specifics beyond the [RelayTransport] interface, so the same host runs in
 * client-host mode and in a dedicated server.
 */
class V2GameHost(
    private val transport: RelayTransport,
    gameInfo: GameInfo,
    /** `UserId -> civId` roster. Since `PlayerId == UserId` this is the [GameSession] roster as-is. */
    roster: Map<UserId, String>,
    /**
     * Optional outbound override (option A — "host is a client of its own in-process authority").
     * When the host process *also* runs a local client for its own civ, the session must deliver
     * that player's frames in-process (into a local view holder) instead of over the relay, while
     * every other player's frames still go over the relay. The owner ([V2GameManager]) supplies a
     * split sink here that routes by `playerId`. When `null`, the default relay-only sink is used —
     * the dedicated-server / pure-relay path, unchanged.
     */
    outbound: ((PlayerId, GameFrame) -> Unit)? = null
) {
    /**
     * The authoritative session. Its outbound sink directs each per-player frame to that player's
     * connection: `RelayTo(targetUserId = playerId, payload = frame)`. PlayerId == UserId, so the
     * `playerId` the session emits *is* the relay target. A caller-supplied [outbound] (the
     * host-as-client split sink) takes precedence over this default.
     */
    val session: GameSession = GameSession(
        gameInfo,
        roster,
        outbound ?: { playerId: PlayerId, frame: GameFrame ->
            transport.send(ClientToRelay.RelayTo(targetUserId = playerId, payload = frame))
        }
    )

    /**
     * Install the inbound handler on the transport. Call once, before remote players start sending
     * frames. (The transport must already be connected and the room created — see the class doc.)
     */
    fun start() {
        transport.onMessage(::onRelayMessage)
    }

    private fun onRelayMessage(message: RelayToClient) {
        if (message !is RelayToClient.Relayed) return // membership/presence/errors: nothing to apply
        val bound = bindIdentity(message.fromId, message.payload)
        session.onFrame(bound)
    }

    /**
     * Inject a frame issued by the **local** player (option A — the host process is also a client of
     * its own in-process authority). The frame is bound to [userId] exactly as a relayed frame is
     * bound to its connection id ([bindIdentity]), then handed to the session — so a local command
     * travels the identical validate/apply path as a remote one. [GameSession.onFrame] is
     * `@Synchronized`, so local injections from the UI thread and relayed frames from the transport
     * receive thread cannot interleave on the canonical state.
     */
    fun submitLocal(userId: UserId, frame: GameFrame) {
        session.onFrame(bindIdentity(userId, frame))
    }

    /**
     * Rebind the acting player of a playerId-bearing frame to [fromId] — the trustworthy connection
     * identity stamped by the relay — overriding whatever `playerId` the client wrote into the
     * payload (docs/multiplayer-v2.md §8). Frames that carry no `playerId` are passed through
     * unchanged.
     */
    private fun bindIdentity(fromId: UserId, frame: GameFrame): GameFrame = when (frame) {
        is GameFrame.PlayerCommand -> frame.copy(playerId = fromId)
        is GameFrame.TurnSubmission -> frame.copy(playerId = fromId)
        is GameFrame.ResyncRequest -> frame.copy(playerId = fromId)
        else -> frame
    }
}
