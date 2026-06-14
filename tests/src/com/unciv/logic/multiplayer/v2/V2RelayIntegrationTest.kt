package com.unciv.logic.multiplayer.v2

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.app.server.RelayServer
import com.unciv.app.server.relayRoutes
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.multiplayer.v2.client.ClientGameView
import com.unciv.logic.multiplayer.v2.net.V2GameClient
import com.unciv.logic.multiplayer.v2.net.V2GameHost
import com.unciv.logic.multiplayer.v2.transport.WebSocketRelayTransport
import com.unciv.network.UserId
import com.unciv.network.command.GameCommand
import com.unciv.network.game.GameFrame
import com.unciv.network.relay.ClientToRelay
import com.unciv.network.relay.PeerRole
import com.unciv.network.relay.RelayToClient
import com.unciv.network.serialization.relayJson
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import io.ktor.http.Url
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * Phase 3c deliverable check — the **integration host loop** end-to-end over real sockets
 * (docs/multiplayer-v2.md §4, §10). Combines the relay setup of [RelayIntegrationTest] (a real
 * [RelayServer] + ktor [WebSocketRelayTransport] clients) with the authority round-trip of
 * [GameSessionTest], wired together by [V2GameHost] / [V2GameClient]:
 *
 *   client command  --RelayTo-->  relay  --Relayed-->  host's GameSession  (applies + projects)
 *   client's filtered PlayerView  <--Relayed--  relay  <--RelayTo--  host
 *
 * The load-bearing assertion is that, with no in-process shortcut, a command sent by the CLIENT
 * reaches the authority and the per-player **visibility-filtered** snapshot comes back: the client's
 * [ClientGameView] eventually holds a view in which civB's own unit is present and civA's fogged
 * unit is absent. That proves the command-in / filtered-view-out pipe over real WebSockets.
 */
@RunWith(GdxTestRunner::class)
class V2RelayIntegrationTest {

    private val hostUserId: UserId = "host-user"
    private val clientUserId: UserId = "client-user"

    private val testGame = TestGame()
    private lateinit var civA: Civilization
    private lateinit var civB: Civilization

    private val centerTile: Tile get() = testGame.tileMap[0, 0]

    @Before
    fun setUp() {
        // Founding/meeting completes a tutorial task -> settings.save() -> needs UncivGame.files;
        // TestGame doesn't init it under the headless runner. Pure harness plumbing (see GameSessionTest).
        UncivGame.Current.files = UncivFiles(Gdx.files)

        // Large enough that the far edge is well outside any unit's sight radius.
        testGame.makeHexagonalMap(6)
        // REAL major-civ nations: the client decodes PlayerViews via gameInfoFromString -> setTransients(),
        // which re-resolves each civ's nation by name from RulesetCache. TestGame's ad-hoc "Nation-0"
        // lives only in its cloned ruleset and would throw MissingNationException on decode.
        val majorNations = testGame.ruleset.nations.values.filter { it.isMajorCiv }.take(2)
        civA = testGame.addCiv(majorNations[0], isPlayer = true)
        civB = testGame.addCiv(majorNations[1], isPlayer = true)
        civA.playerType = PlayerType.Human
        civB.playerType = PlayerType.Human

        // nextTurn() needs a valid current human player.
        testGame.gameInfo.currentPlayer = civA.civID
        testGame.gameInfo.currentPlayerCiv = civA
        // Start at turn 1 so nextTurn()'s `turns % 10 == 0` music hook (uninitialized under the headless
        // runner) is not taken — a real running game is past turn 0 anyway (see GameSessionTest).
        testGame.gameInfo.turns = 1
    }

    /** The far-most tile from center — guaranteed fogged to a unit sitting at the center. */
    private fun farTile(): Tile =
        testGame.tileMap.values.maxByOrNull { it.aerialDistanceTo(centerTile) }!!

    /** All units in [gameInfo] owned by [civId], located by scanning every tile. */
    private fun unitsOf(gameInfo: GameInfo, civId: String): List<MapUnit> =
        gameInfo.tileMap.values.flatMap { it.getUnits().toList() }.filter { it.owner == civId }

    /** Await a [RelayToClient] of the given type on this inbox, or fail the timeout. */
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
    fun clientCommandReachesHostAndFilteredViewComesBack() = runBlocking {
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

        // Scenario state: civB's unit at the center (it must MOVE and be visible to itself),
        // civA's unit at the far edge (fogged to B, must stay absent from B's view).
        val bFrom = centerTile
        val bTo = centerTile.neighbors.first()
        testGame.addUnit("Warrior", civB, bFrom)
        val far = farTile()
        testGame.addUnit("Warrior", civA, far)

        // Precondition: in the canonical game, civA's far unit is genuinely not visible to civB.
        testGame.gameInfo.civilizations.forEach { it.cache.updateOurTiles() }
        assertTrue("Far tile must be fogged for B in this setup", !far.isVisible(civB))

        // Tap the host inbox too, only so we can deterministically wait for the relay handshake /
        // membership before driving the game (we do NOT read game frames out of it — V2GameHost owns
        // the host's onMessage handler once start() runs).
        val hostInbox = Channel<RelayToClient>(Channel.UNLIMITED)
        val hostTransport = WebSocketRelayTransport(relayUrl, userId = hostUserId)
        val clientTransport = WebSocketRelayTransport(relayUrl, userId = clientUserId)

        try {
            // --- HOST: connect, create room, then hand the transport to V2GameHost ---
            hostTransport.onMessage { hostInbox.trySend(it) }
            hostTransport.connect()
            hostTransport.send(ClientToRelay.CreateRoom("v2-game"))
            val hostWelcome = hostInbox.awaitOfType<RelayToClient.Welcome>()
            assertTrue("Room creator must be HOST", hostWelcome.role == PeerRole.HOST)
            val roomId = hostWelcome.roomId

            // PlayerId == UserId, so the GameSession roster is UserId -> civId directly.
            val roster = mapOf(hostUserId to civA.civID, clientUserId to civB.civID)
            val host = V2GameHost(hostTransport, testGame.gameInfo, roster)
            host.start() // installs the host's onMessage handler (replaces the inbox tap above)

            // --- CLIENT: register the handler BEFORE joining so the Welcome (host id) isn't missed ---
            val clientView = ClientGameView()
            // A PlayerView arrives on the transport's receive thread; signal the test thread through a
            // Channel so the read of clientView.currentView happens-after the apply (memory-safe
            // rendezvous, matching RelayIntegrationTest's channel-based awaits).
            val viewArrivals = Channel<GameFrame.PlayerView>(Channel.UNLIMITED)
            clientTransport.connect()
            val client = V2GameClient(clientTransport, myUserId = clientUserId, view = clientView)
            client.onView = { viewArrivals.trySend(it) }
            client.start()
            clientTransport.send(ClientToRelay.JoinRoom(roomId))

            // Wait until the client has learned the host id from its Welcome's peer list.
            withTimeout(5.seconds) {
                while (client.hostUserId == null) delay(20)
            }
            assertTrue("Client must learn the host UserId from the Welcome peer list",
                client.hostUserId == hostUserId)
            // And wait until the host has seen the client join, so its session roster is reachable.
            // (PeerJoined arrives on the host; the host's V2GameHost handler ignores it, but the relay
            // only routes RelayTo to members it knows — so the client must be a member first.)
            // We approximate readiness by giving the join a brief moment to propagate.
            delay(100)

            // --- The CLIENT drives the game over the socket: move civB's own unit, then end the turn
            //     to trigger the host's per-player snapshot broadcast. ---
            client.sendCommand(seq = 1, command = GameCommand.MoveUnit(
                unitId = 0,
                fromX = bFrom.position.x, fromY = bFrom.position.y,
                toX = bTo.position.x, toY = bTo.position.y
            ))
            client.sendEndTurn(seq = 2)

            // Streaming-barrier turn model: the host broadcasts each player's snapshot only once EVERY
            // rostered human has ended. civA (the host) is the second human, so it must end too — inject
            // its own EndTurn straight into the in-process session (the option-A loopback path) so the
            // round resolves and the client's PlayerView is broadcast. Ordering is safe: resolution
            // waits for the client's relayed EndTurn (which follows its MoveUnit on the same socket), so
            // civB's move is applied before the snapshot regardless of when this host EndTurn lands.
            host.submitLocal(hostUserId, GameFrame.PlayerCommand(
                seq = 1, playerId = hostUserId, command = GameCommand.EndTurn))

            // --- Await PlayerViews until the client's filtered view satisfies the assertion. Each
            //     receive() establishes happens-before with the apply on the receive thread. ---
            var finalView: GameInfo? = null
            withTimeout(10.seconds) {
                while (finalView == null) {
                    viewArrivals.receive() // a PlayerView was applied to clientView before this signal
                    val view = clientView.currentView ?: continue
                    val bUnits = unitsOf(view, civB.civID)
                    val aUnits = unitsOf(view, civA.civID)
                    if (bUnits.isNotEmpty() && aUnits.isEmpty()) finalView = view
                }
            }

            assertNotNull("Client must have received a filtered PlayerView meeting the criteria", finalView)
            finalView!!

            // B sees its OWN unit (it was applied by the authority and projected back to B)...
            val bUnitsInView = unitsOf(finalView, civB.civID)
            assertTrue("B must see its own unit in its filtered view (found ${bUnitsInView.size})",
                bUnitsInView.isNotEmpty())

            // ...and does NOT see A's fogged unit.
            val aUnitsInView = unitsOf(finalView, civA.civID)
            assertTrue("B must NOT see A's fogged unit in its filtered view (found ${aUnitsInView.size})",
                aUnitsInView.isEmpty())
            val farInView = finalView.tileMap[far.position.x, far.position.y]
            assertNull("A's fogged unit must be gone from the tile in B's decoded view",
                farInView.militaryUnit)

            // No command was rejected — the move was legal and identity-bound to civB's connection.
            assertNull("Client must not have received a CommandRejected", client.lastRejection)

            hostTransport.close()
            clientTransport.close()
        } finally {
            server.stop(100, 500, TimeUnit.MILLISECONDS)
        }
    }
}
