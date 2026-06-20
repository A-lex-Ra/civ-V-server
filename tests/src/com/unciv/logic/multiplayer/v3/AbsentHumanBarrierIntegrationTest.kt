package com.unciv.logic.multiplayer.v3

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * The streaming barrier's "wait for everyone" rule (the user's requirement): the game must NOT advance
 * until **every** rostered human has connected and ended — "при отключении игрока не возможен advance
 * turn ... должны ждать пока он переподключится". A rostered human whose UserId no client has connected
 * as yet (an unfilled / not-yet-joined slot) therefore BLOCKS the round: the two connected players
 * cannot advance the turn while it is absent. (A DEFEATED player, by contrast, does NOT block — that
 * exception is covered at the session level in `SimultaneousBarrierTest`; the reconnect-then-resolve
 * path likewise.) Drives the real V3GameManager host+joiner loop over an embedded relay, with an AI civ
 * present, and asserts the round stays open while the third rostered human is absent.
 */
@RunWith(GdxTestRunner::class)
class AbsentHumanBarrierIntegrationTest {

    private val hostUserId: UserId = "host-user"
    private val clientUserId: UserId = "client-user"
    private val absentUserId: UserId = "absent-user" // rostered human, but NO client ever connects as it

    @Test
    fun absentRosteredHumanBlocksTheLiveBarrier() = runBlocking {
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

        val hostManager = V3GameManager()
        val clientManager = V3GameManager()
        try {
            val roomId = hostManager.hostGame(game, serverUrl, hostUserId, V3GameManager.rosterFrom(game))
            hostManager.requestInitialView()
            assertTrue("Host must get its own first view", hostManager.awaitFirstView() != null)

            clientManager.joinGame(roomId, serverUrl, clientUserId)
            clientManager.requestInitialView()
            assertTrue("Joiner must get its first view", clientManager.awaitFirstView() != null)
            delay(300) // let the host see the join (PeerJoined) propagate

            val startTurn = game.turns

            // Both CONNECTED humans end. The third rostered human ('absent-user') has not connected, so
            // the round must NOT advance — the game waits for it. Give any (erroneous) resolution ample
            // time to happen, then assert it did not: the turn is unchanged and both players stay
            // latched as "ended, waiting for others".
            hostManager.sendEndTurn()
            clientManager.sendEndTurn()
            delay(2000)

            assertEquals("Round must NOT advance while a rostered human is absent (turns ${game.turns} vs $startTurn)",
                startTurn, game.turns)
            assertTrue("Host must stay ended/waiting while the absent human is missing",
                hostManager.localEndedTurn)
            assertTrue("Joiner must stay ended/waiting while the absent human is missing",
                clientManager.localEndedTurn)
        } finally {
            hostManager.close()
            clientManager.close()
            server.stop(100, 500, TimeUnit.MILLISECONDS)
        }
    }
}
