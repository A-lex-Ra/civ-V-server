package com.unciv.logic.multiplayer.v3.client

import com.unciv.logic.GameInfo
import com.unciv.network.PlayerId
import com.unciv.network.game.GameFrame

/**
 * Phase 6 — client-side reconnection / desync recovery helper (docs/multiplayer-v3.md §10).
 *
 * A dropped client that rejoins has lost its filtered view and must re-sync from the authority.
 * Because the authority ships **full per-player filtered snapshots** (not incremental semantic
 * deltas yet), reconnection is simply: ask for a fresh snapshot, then adopt the directed
 * [GameFrame.PlayerView] the authority sends back. There is therefore **no missed-delta replay**
 * and **no [GameFrame.Ack] cursor** to carry — that only becomes relevant once a semantic
 * `StateDelta` exists (a later bandwidth optimization; see the design doc §11 and the report).
 *
 * This helper is deliberately minimal and **additive**: it does not own a transport, a socket, or a
 * view holder. It just bridges two existing pieces —
 *  1. [resyncRequest] produces the [GameFrame.ResyncRequest] the (re)connecting client sends to the
 *     authority (tagged with [playerId] so the authority knows whose filtered view to project), and
 *  2. [onPlayerView] feeds the authority's directed reply into the client's existing view holder
 *     ([ClientGameView] for a plain client, or [PredictiveClientView] via the other overload).
 *
 * Existing client APIs are untouched: this is pure glue around them.
 *
 * Typical use (on (re)connect):
 * ```
 * val reconnector = ClientReconnector(myPlayerId)
 * transport.send(reconnector.resyncRequest())              // -> authority
 * // ... authority replies with a directed PlayerView frame ...
 * val freshView = reconnector.onPlayerView(playerViewFrame, clientGameView)
 * ```
 */
class ClientReconnector(
    /** This client's player id — the authority projects *this* player's filtered view in reply. */
    val playerId: PlayerId
) {
    /**
     * Build the [GameFrame.ResyncRequest] to send to the authority on (re)connect. The authority
     * answers with a directed [GameFrame.PlayerView] reflecting the current canonical state, which
     * the caller then feeds back through [onPlayerView].
     */
    fun resyncRequest(): GameFrame.ResyncRequest = GameFrame.ResyncRequest(playerId)

    /**
     * Adopt the authority's resync reply into a plain [ClientGameView]: decode the snapshot
     * (gunzip -> `gameInfoFromString` -> `setTransients()`) and make it the view's current state.
     * Returns the freshly decoded filtered [GameInfo] — what a just-rejoined client now holds.
     */
    fun onPlayerView(view: GameFrame.PlayerView, into: ClientGameView): GameInfo =
        into.onPlayerView(view)

    /**
     * Adopt the authority's resync reply into a [PredictiveClientView]: same decode, becoming the new
     * authoritative base (any still-pending local predictions are reconciled by that holder). Returns
     * the freshly decoded authoritative [GameInfo].
     */
    fun onPlayerView(view: GameFrame.PlayerView, into: PredictiveClientView): GameInfo =
        into.onPlayerView(view)
}
