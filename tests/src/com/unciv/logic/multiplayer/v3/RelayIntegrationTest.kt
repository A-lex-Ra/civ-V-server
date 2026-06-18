package com.unciv.logic.multiplayer.v3

import com.unciv.app.server.RelayServer
import com.unciv.app.server.relayRoutes
import com.unciv.logic.multiplayer.v3.transport.WebSocketRelayTransport
import com.unciv.network.game.GameFrame
import com.unciv.network.relay.ClientToRelay
import com.unciv.network.relay.PeerRole
import com.unciv.network.relay.RelayToClient
import com.unciv.network.serialization.relayJson
import io.ktor.http.Url
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * Phase 1 deliverable check: "Two clients exchange messages through the relay."
 *
 * Embeds the real [RelayServer] route in a ktor Netty server, connects two
 * [WebSocketRelayTransport] clients, and verifies room creation/join, presence notifications and
 * bidirectional routing of opaque game frames.
 */
class RelayIntegrationTest {

    private suspend inline fun <reified T : RelayToClient> Channel<RelayToClient>.awaitOfType(): T =
        withTimeout(5.seconds) {
            var result: T? = null
            while (result == null) {
                val message = receive()
                if (message is T) result = message
            }
            result!!
        }

    @Test
    fun twoClientsExchangeMessagesThroughRelay() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }

        val server = embeddedServer(Netty, port = port) {
            install(WebSockets) {
                contentConverter = KotlinxWebsocketSerializationConverter(relayJson)
            }
            routing {
                relayRoutes(RelayServer())
            }
        }.start(wait = false)

        val relayUrl = Url("ws://localhost:$port/relay")

        try {
            // --- Host creates a room ---
            val hostInbox = Channel<RelayToClient>(Channel.UNLIMITED)
            val host = WebSocketRelayTransport(relayUrl, userId = "host-user")
            host.onMessage { hostInbox.trySend(it) }
            host.connect()
            host.send(ClientToRelay.CreateRoom("game-1"))

            val welcome = hostInbox.awaitOfType<RelayToClient.Welcome>()
            assertEquals(PeerRole.HOST, welcome.role)
            val roomId = welcome.roomId

            // --- A second client joins ---
            val joinerInbox = Channel<RelayToClient>(Channel.UNLIMITED)
            val joiner = WebSocketRelayTransport(relayUrl, userId = "joiner-user")
            joiner.onMessage { joinerInbox.trySend(it) }
            joiner.connect()
            joiner.send(ClientToRelay.JoinRoom(roomId))

            val joinerWelcome = joinerInbox.awaitOfType<RelayToClient.Welcome>()
            assertEquals(PeerRole.PLAYER, joinerWelcome.role)
            assertTrue("Joiner should see the host as an existing peer",
                joinerWelcome.peers.contains("host-user"))

            // Host is notified about the new peer
            val peerJoined = hostInbox.awaitOfType<RelayToClient.PeerJoined>()
            assertEquals("joiner-user", peerJoined.userId)

            // --- Host -> joiner relay ---
            host.send(ClientToRelay.Relay(GameFrame.ChecksumReport(turn = 7, checksum = "abc")))
            val relayedToJoiner = joinerInbox.awaitOfType<RelayToClient.Relayed>()
            assertEquals("host-user", relayedToJoiner.fromId)
            assertEquals(GameFrame.ChecksumReport(7, "abc"), relayedToJoiner.payload)

            // --- Joiner -> host relay ---
            joiner.send(ClientToRelay.Relay(GameFrame.ChecksumReport(turn = 8, checksum = "xyz")))
            val relayedToHost = hostInbox.awaitOfType<RelayToClient.Relayed>()
            assertEquals("joiner-user", relayedToHost.fromId)
            assertEquals(GameFrame.ChecksumReport(8, "xyz"), relayedToHost.payload)

            host.close()
            joiner.close()
        } finally {
            server.stop(100, 500, TimeUnit.MILLISECONDS)
        }
    }
}
