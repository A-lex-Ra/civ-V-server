// SPDX-License-Identifier: LicenseRef-Unciv-v3-ViewOnly
// Copyright (c) 2026 Alexander Rastorguev (A-lex-Ra) <rastorguev2047@gmail.com>
//
// Part of the Unciv multiplayer-v3 netcode — view-only, NOT under the Mozilla
// Public License that covers the rest of this repository. No right to use,
// copy, modify, run, or distribute is granted without written permission;
// permission is gladly given on request (email or GitHub issue).
// Full terms: /LICENSE.v3  ·  License map: /LICENSING.md

package com.unciv.logic.multiplayer.v3.transport

import com.unciv.network.Protocol
import com.unciv.network.UserId
import com.unciv.network.relay.ClientToRelay
import com.unciv.network.relay.RelayToClient
import com.unciv.network.serialization.relayJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.websocket.close
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * [RelayTransport] over a ktor WebSocket connected to the relay's `/relay` endpoint.
 *
 * Intentionally engine-independent (no `UncivGame` singleton access): the URL, user id and
 * optional auth header are passed in, so the transport can be exercised in isolation (e.g. by the
 * Phase 1 "two clients exchange messages through the relay" test). Higher layers wire it up to the
 * configured multiplayer server in later phases.
 *
 * On [connect] it opens the socket and sends the [ClientToRelay.Hello] handshake; inbound
 * [RelayToClient] messages are dispatched to the handler registered via [onMessage].
 */
class WebSocketRelayTransport(
    private val relayUrl: Url,
    private val userId: UserId,
    private val authHeader: String? = null,
    private val client: HttpClient = defaultClient(),
    /** Whether [close] should also close [client] (false when a shared client is injected). */
    private val ownsClient: Boolean = true,
) : RelayTransport {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Outbound queue drained by a single sender coroutine, so messages reach the socket in
     * submission order with only one [sendSerialized] in flight at a time. Launching a coroutine
     * per [send] on the multi-threaded IO dispatcher would let frames race and reorder, which the
     * protocol's per-command sequencing relies on not happening.
     */
    private val outbound = Channel<ClientToRelay>(Channel.UNLIMITED)

    @Volatile
    private var session: DefaultClientWebSocketSession? = null

    @Volatile
    private var handler: ((RelayToClient) -> Unit)? = null

    override fun onMessage(handler: (RelayToClient) -> Unit) {
        this.handler = handler
    }

    override suspend fun connect() {
        val newSession = client.webSocketSession {
            url(relayUrl)
            if (authHeader != null) header(HttpHeaders.Authorization, authHeader)
        }
        session = newSession

        // Serialize against the sealed parent type so the polymorphic discriminator is emitted.
        // Sent directly here, before the sender starts draining [outbound], so the handshake is
        // guaranteed to be the first frame on the wire.
        val hello: ClientToRelay = ClientToRelay.Hello(Protocol.VERSION, userId, authHeader)
        newSession.sendSerialized(hello)

        // Single sender coroutine: preserves submission order, one send in flight at a time.
        scope.launch {
            for (message in outbound) {
                try {
                    newSession.sendSerialized(message)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // Best-effort delivery; drop on transient send failure (reconnection later).
                }
            }
        }

        scope.launch {
            try {
                while (newSession.isActive) {
                    val message = newSession.receiveDeserialized<RelayToClient>()
                    handler?.invoke(message)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Connection closed or read error: receive loop ends. Reconnection handling is
                // added together with the session layer in a later phase.
            }
        }
    }

    override fun send(message: ClientToRelay) {
        if (session == null) throw IllegalStateException("connect() must be called before send()")
        // Non-blocking enqueue; the sender coroutine delivers in order. Fails only after close().
        outbound.trySend(message)
    }

    override fun close() {
        val currentSession = session
        session = null
        outbound.close()
        scope.launch {
            runCatching { currentSession?.close() }
        }.invokeOnCompletion {
            scope.cancel()
            if (ownsClient) runCatching { client.close() }
        }
    }

    companion object {
        /** A CIO ktor client preconfigured with the shared relay [relayJson] converter. */
        fun defaultClient(): HttpClient = HttpClient(CIO) {
            install(WebSockets) {
                pingInterval = 30.seconds
                contentConverter = KotlinxWebsocketSerializationConverter(relayJson)
            }
        }
    }
}
