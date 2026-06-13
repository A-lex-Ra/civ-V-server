package com.unciv.logic.multiplayer.v2.transport

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
        val hello: ClientToRelay = ClientToRelay.Hello(Protocol.VERSION, userId, authHeader)
        newSession.sendSerialized(hello)

        scope.launch {
            try {
                while (newSession.isActive) {
                    val message = newSession.receiveDeserialized<RelayToClient>()
                    handler?.invoke(message)
                }
            } catch (_: Throwable) {
                // Connection closed or read error: receive loop ends. Reconnection handling is
                // added together with the session layer in a later phase.
            }
        }
    }

    override fun send(message: ClientToRelay) {
        val currentSession = session
            ?: throw IllegalStateException("connect() must be called before send()")
        scope.launch {
            runCatching { currentSession.sendSerialized(message) }
        }
    }

    override fun close() {
        val currentSession = session
        session = null
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
