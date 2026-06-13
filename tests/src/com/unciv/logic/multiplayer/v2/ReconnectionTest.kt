package com.unciv.logic.multiplayer.v2

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.multiplayer.v2.client.ClientGameView
import com.unciv.logic.multiplayer.v2.client.ClientReconnector
import com.unciv.logic.multiplayer.v2.session.GameSession
import com.unciv.network.PlayerId
import com.unciv.network.command.GameCommand
import com.unciv.network.game.GameFrame
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 6 deliverable check (docs/multiplayer-v2.md §10): **reconnection / desync recovery**. A
 * dropped client rejoins, sends a [GameFrame.ResyncRequest] to the authority, and gets a **fresh**
 * directed [GameFrame.PlayerView] reflecting the *current* canonical state — projected on demand,
 * mid-turn, not only at a turn boundary.
 *
 * The load-bearing assertions: the resync reply (a) round-trips through a **fresh** [ClientGameView]
 * (as a just-rejoined client's view holder would), (b) shows the reconnecting player's own unit, and
 * (c) does **not** contain a fogged enemy unit. Plus: a [GameFrame.ResyncRequest] from an unknown
 * player is rejected ([GameFrame.CommandRejected]) and emits no [GameFrame.PlayerView].
 *
 * Modelled on `GameSessionTest`'s harness (same headless-runner plumbing and real-major-civ setup).
 */
@RunWith(GdxTestRunner::class)
class ReconnectionTest {

    private val testGame = TestGame()
    private lateinit var civA: Civilization
    private lateinit var civB: Civilization

    private val playerA: PlayerId = "player-A"
    private val playerB: PlayerId = "player-B"

    /** Records every (recipient, frame) the session emits through the outbound sink. */
    private val emitted = mutableListOf<Pair<PlayerId, GameFrame>>()

    private lateinit var session: GameSession

    private val centerTile: Tile get() = testGame.tileMap[0, 0]

    @Before
    fun setUp() {
        // Founding/meeting completes a tutorial task -> settings.save() -> needs UncivGame.files.
        // TestGame doesn't init it under the headless runner; wire it up like GameSerializationTests.
        UncivGame.Current.files = UncivFiles(Gdx.files)

        // Large enough that the far edge is well outside any unit's sight radius.
        testGame.makeHexagonalMap(6)
        // Real major-civ nations: the client decode runs gameInfoFromString -> setTransients(), which
        // re-resolves each civ's nation by name from RulesetCache. Ad-hoc TestGame nations would throw
        // MissingNationException on decode.
        val majorNations = testGame.ruleset.nations.values.filter { it.isMajorCiv }.take(2)
        civA = testGame.addCiv(majorNations[0], isPlayer = true)
        civB = testGame.addCiv(majorNations[1], isPlayer = true)

        civA.playerType = PlayerType.Human
        civB.playerType = PlayerType.Human
        testGame.gameInfo.currentPlayer = civA.civID
        testGame.gameInfo.currentPlayerCiv = civA
        // Start at turn 1 so nextTurn()'s `turns % 10 == 0` music hook (which would NPE on the
        // headless runner's uninitialised musicController) is not taken.
        testGame.gameInfo.turns = 1

        val roster = mapOf(playerA to civA.civID, playerB to civB.civID)
        session = GameSession(testGame.gameInfo, roster) { playerId, frame ->
            emitted.add(playerId to frame)
        }
    }

    /** The far-most tile from center — guaranteed fogged to a unit sitting at the center. */
    private fun farTile(): Tile =
        testGame.tileMap.values.maxByOrNull { it.aerialDistanceTo(centerTile) }!!

    /** All units in [gameInfo] owned by [civId], located by scanning every tile. */
    private fun unitsOf(gameInfo: GameInfo, civId: String): List<MapUnit> =
        gameInfo.tileMap.values.flatMap { it.getUnits().toList() }.filter { it.owner == civId }

    @Test
    fun reconnectingClientGetsFreshFilteredViewFromAuthority() {
        // A's unit at the center; B's unit at the far edge, fogged to A.
        val far = farTile()
        testGame.addUnit("Warrior", civA, centerTile)
        testGame.addUnit("Warrior", civB, far)

        // Advance some state so the snapshot reflects non-trivial, post-turn canonical state.
        session.onFrame(GameFrame.PlayerCommand(seq = 1, playerId = playerA, command = GameCommand.EndTurn))

        // Precondition: the far tile is genuinely fogged to A in the canonical game.
        testGame.gameInfo.civilizations.forEach { it.cache.updateOurTiles() }
        assertTrue("Far tile must be fogged for A in this setup", !far.isVisible(civA))

        // The dropped client A "rejoins": it sends a ResyncRequest and discards anything received
        // before the drop (we only inspect frames emitted from here on).
        val emittedBeforeResync = emitted.size
        val reconnector = ClientReconnector(playerA)
        session.onFrame(reconnector.resyncRequest())

        val newFrames = emitted.drop(emittedBeforeResync)
        val viewsToA = newFrames.filter { it.first == playerA && it.second is GameFrame.PlayerView }
        assertEquals("Resync must emit exactly one directed PlayerView to the requester", 1, viewsToA.size)
        assertTrue("Resync must be directed only — no snapshot to other players",
            newFrames.none { it.first == playerB && it.second is GameFrame.PlayerView })
        assertTrue("A valid resync must emit no CommandRejected",
            newFrames.none { it.second is GameFrame.CommandRejected })

        // A just-rejoined client decodes the resync reply through a FRESH view holder.
        val frame = viewsToA.single().second as GameFrame.PlayerView
        val clientView = ClientGameView()
        val aView: GameInfo = reconnector.onPlayerView(frame, clientView)

        assertNotNull("Reconnected client must hold a decoded view", clientView.currentView)
        assertEquals("Resync snapshot reflects the current turn", frame.turn, clientView.turn)

        // A sees its own unit...
        assertEquals("Reconnected A must see its own unit", 1, unitsOf(aView, civA.civID).size)
        // ...and does NOT see B's fogged unit.
        val bUnitsInAView = unitsOf(aView, civB.civID)
        assertTrue("Reconnected A must NOT see B's fogged unit (found ${bUnitsInAView.size})",
            bUnitsInAView.isEmpty())
        val farInAView = aView.tileMap[far.position.x, far.position.y]
        assertNull("Fogged enemy unit must be absent from the tile in A's resynced view",
            farInAView.militaryUnit)
    }

    @Test
    fun resyncFromUnknownPlayerIsRejectedAndEmitsNoView() {
        val ghost: PlayerId = "player-ghost"
        session.onFrame(GameFrame.ResyncRequest(ghost))

        val rejections = emitted.filter { it.second is GameFrame.CommandRejected }
        assertEquals("Unknown-player resync must produce exactly one CommandRejected", 1, rejections.size)
        assertEquals("Rejection must go to the requester", ghost, rejections.single().first)
        assertTrue("An unknown-player resync must emit no PlayerView",
            emitted.none { it.second is GameFrame.PlayerView })
    }
}
