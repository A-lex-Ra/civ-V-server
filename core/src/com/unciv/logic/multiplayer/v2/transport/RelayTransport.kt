package com.unciv.logic.multiplayer.v2.transport

import com.unciv.network.relay.ClientToRelay
import com.unciv.network.relay.RelayToClient

/**
 * Client-side WebSocket transport to the public relay.
 *
 * It sends [ClientToRelay] messages and surfaces inbound [RelayToClient] messages to the rest of
 * the v2 stack (session/command layers). The relay only routes opaque game frames and manages
 * room membership/presence/host-role; it contains no game logic.
 *
 * Phase 0: stub. The actual relay client (connect/send/receive + reconnection) is implemented in
 * Phase 1, alongside the relay server in `:server`.
 */
interface RelayTransport {

    /** Send a message to the relay. */
    fun send(message: ClientToRelay)

    /** Register a handler invoked for every inbound relay message. */
    fun onMessage(handler: (RelayToClient) -> Unit)

    /** Close the relay connection. */
    fun close()
}
