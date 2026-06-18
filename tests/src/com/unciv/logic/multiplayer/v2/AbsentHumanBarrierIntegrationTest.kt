package com.unciv.logic.multiplayer.v2

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.app.server.RelayServer
import com.unciv.app.server.relayRoutes
import com.unciv.logic.GameInfo
import com.unciv.logic.GameStarter
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.UncivFiles
import com.unciv.models.metadata.GameParameters
import com.unciv.models.metadata.GameSettings
import com.unciv.models.metadata.Player
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapSize
import com.unciv.models.ruleset.RulesetCache
import com.unciv.network.UserId
import com.unciv.testing.GdxTestRunner
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import com.unciv.network.serialization.relayJson
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * Regression for the live "Waiting for other players..." deadlock: the streaming barrier must resolve
 * a round once every **connected** human has ended, NOT every *rostered* human. A human civ set up in
 * the lobby whose UserId no client ever connects as (an unfilled slot) would otherwise keep the round
 * waiting forever. Drives the real V2GameManager host+joiner loop over an embedded relay, with an AI
 * civ present (so resolveRound runs full nextTurn AI automation), and asserts the two connected players
 * resolve the round despite the third (absent) rostered human.
 */
@RunWith(GdxTestRunner::class)
class AbsentHumanBarrierIntegrationTest {

    private val hostUserId: UserId = "host-user"
    private val clientUserId: UserId = "client-user"
    private val absentUserId: UserId = "absent-user" // rostered human, but NO client ever connects as it

    @Test
    fun absentRosteredHumanDoesNotDeadlockTheLiveBarrier() = runBlocking {
        RulesetCache.loadRulesets(noMods = true)
        UncivGame.Current = UncivGame()
        UncivGame.Current.files = UncivFiles(Gdx.files)
        UncivGame.Current.settings = GameSettings()

        // 3 humans (only host + client will connect) + an AI + a city-state.
        val param = GameParameters().apply {
            numberOfCityStates = 1
            players.clear()
            players.add(Player("Rome", PlayerType.Human).apply { playerId = hostUserId })
            players.add(Player("Greece", PlayerType.Human).apply { playerId = clientUserId })
            players.add(Player("Egypt", PlayerType.Human).apply { playerId = absentUserId })
            players.add(Player("Babylon", PlayerType.AI))
        }
        val game: GameInfo = GameStarter.startNewGame(GameSetupInfo(param, MapParameters().apply {
            mapSize = MapSize.Small; seed = 42L
        }))
        game.turns = 1 // headless: dodge nextTurn's turns%10 music hook

        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(Netty, port = port) {
            install(WebSockets) { contentConverter = KotlinxWebsocketSerializationConverter(relayJson) }
            routing { relayRoutes(RelayServer()) }
        }.start(wait = false)
        val serverUrl = "ws://localhost:$port"

        val hostManager = V2GameManager()
        val clientManager = V2GameManager()
        try {
            val roomId = hostManager.hostGame(game, serverUrl, hostUserId, V2GameManager.rosterFrom(game))
            hostManager.requestInitialView()
            assertTrue("Host must get its own first view", hostManager.awaitFirstView() != null)

            clientManager.joinGame(roomId, serverUrl, clientUserId)
            clientManager.requestInitialView()
            assertTrue("Joiner must get its first view", clientManager.awaitFirstView() != null)
            delay(300) // let the host see the join (PeerJoined) propagate

            val startTurn = game.turns

            // Both CONNECTED humans end. The third rostered human ('absent-user') never connected and
            // never will — the round must still resolve instead of hanging on "Waiting for players...".
            hostManager.sendEndTurn()
            clientManager.sendEndTurn()

            withTimeout(15.seconds) {
                while (game.turns <= startTurn || hostManager.localEndedTurn || clientManager.localEndedTurn)
                    delay(50)
            }
            assertTrue("Round must resolve on the connected humans alone (turns ${game.turns} vs $startTurn)",
                game.turns > startTurn)
            assertTrue("Host latch must clear", !hostManager.localEndedTurn)
            assertTrue("Joiner latch must clear", !clientManager.localEndedTurn)
        } finally {
            hostManager.close()
            clientManager.close()
            server.stop(100, 500, TimeUnit.MILLISECONDS)
        }
    }
}
