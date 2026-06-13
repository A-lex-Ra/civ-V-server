package com.unciv.logic.multiplayer.v2.transport

import com.unciv.network.relay.ClientToRelay
import com.unciv.network.relay.RelayToClient

/**
 * Client-side transport to the public relay.
 *
 * It sends [ClientToRelay] messages and surfaces inbound [RelayToClient] messages to the rest of
 * the v2 stack (session/command layers). The relay only routes opaque game frames and manages
 * room membership/presence/host-role; it contains no game logic.
 *
 * Phase 1: implemented by [WebSocketRelayTransport] over a ktor WebSocket to the relay's
 * `/relay` endpoint.
 */
interface RelayTransport {

    /**
     * Register the handler invoked for every inbound relay message. Should be set before
     * [connect] so the initial [RelayToClient.Welcome] is not missed.
     */
    fun onMessage(handler: (RelayToClient) -> Unit)

    /** Open the connection and perform the protocol handshake. Suspends until connected. */
    suspend fun connect()

    /** Send a message to the relay. Non-blocking; delivery is best-effort. */
    fun send(message: ClientToRelay)

    /** Close the relay connection and release resources. */
    fun close()
}
