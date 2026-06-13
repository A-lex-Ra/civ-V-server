package com.unciv.logic.multiplayer.v2

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.multiplayer.v2.client.ClientGameView
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
 * Phase 3b deliverable check (docs/multiplayer-v2.md §10): the **authority loop** end-to-end —
 * client command -> [GameSession] applies it via the `CommandExecutor` -> on `EndTurn` the authority
 * runs `nextTurn` and sends each player their **own** visibility-filtered [GameFrame.PlayerView].
 *
 * The load-bearing assertion is the round-trip: a projected/redacted [GameInfo] must survive
 * serialise -> gzip -> gunzip -> `gameInfoFromString` (which runs `setTransients()`) on the client,
 * and the decoded view must show the viewer's own unit while a fogged enemy unit is absent.
 */
@RunWith(GdxTestRunner::class)
class GameSessionTest {

    private val testGame = TestGame()
    private lateinit var civA: Civilization
    private lateinit var civB: Civilization

    /** playerId -> civId roster; each civ is controlled by a like-named player. */
    private val playerA: PlayerId = "player-A"
    private val playerB: PlayerId = "player-B"

    /** Records every (recipient, frame) the session emits through the outbound sink. */
    private val emitted = mutableListOf<Pair<PlayerId, GameFrame>>()

    private lateinit var session: GameSession

    private val centerTile: Tile get() = testGame.tileMap[0, 0]

    @Before
    fun setUp() {
        // Large enough that the far edge is well outside any unit's sight radius.
        testGame.makeHexagonalMap(6)
        // Use REAL major-civ nations (not TestGame's synthesised ad-hoc "Nation-0"): the round-trip
        // decode runs the engine's gameInfoFromString -> setTransients(), which re-resolves each civ's
        // nation by name from RulesetCache. Ad-hoc nations live only in TestGame's cloned ruleset and
        // would throw MissingNationException on decode — exactly as a save referencing an absent mod
        // would. Real G&K nations exist in RulesetCache, mirroring a properly-loaded game/client.
        val majorNations = testGame.ruleset.nations.values.filter { it.isMajorCiv }.take(2)
        civA = testGame.addCiv(majorNations[0], isPlayer = true)
        civB = testGame.addCiv(majorNations[1], isPlayer = true)

        // nextTurn() needs a valid current human player; make A the active player.
        civA.playerType = PlayerType.Human
        civB.playerType = PlayerType.Human
        testGame.gameInfo.currentPlayer = civA.civID
        testGame.gameInfo.currentPlayerCiv = civA
        // A real running game is well past turn 0 by the time EndTurn commands flow; TestGame starts
        // at 0. Start at turn 1 so nextTurn()'s `turns % 10 == 0` music hook is not taken — under the
        // headless test runner Gdx.app is non-null but UncivGame.musicController is never initialized,
        // so that hook would throw. (A dedicated server has Gdx.app == null; a client-host has a real
        // musicController — neither is affected. This is a test-harness artifact only.)
        testGame.gameInfo.turns = 1

        val roster = mapOf(playerA to civA.civID, playerB to civB.civID)
        session = GameSession(testGame.gameInfo, roster) { playerId, frame ->
            emitted.add(playerId to frame)
        }
    }

    /** The far-most tile from center — guaranteed fogged to a unit sitting at the center. */
    private fun farTile(): Tile =
        testGame.tileMap.values.maxByOrNull { it.aerialDistanceTo(centerTile) }!!

    private fun moveCommand(from: Tile, to: Tile) = GameCommand.MoveUnit(
        unitId = 0,
        fromX = from.position.x, fromY = from.position.y,
        toX = to.position.x, toY = to.position.y
    )

    private fun playerCommand(seq: Long, playerId: PlayerId, command: GameCommand) =
        GameFrame.PlayerCommand(seq = seq, playerId = playerId, command = command)

    /** All units in [gameInfo] owned by [civId], located by scanning every tile. */
    private fun unitsOf(gameInfo: GameInfo, civId: String): List<MapUnit> =
        gameInfo.tileMap.values.flatMap { it.getUnits().toList() }.filter { it.owner == civId }

    @Test
    fun playerCommandMutatesCanonicalGameInfo() {
        val from = centerTile
        val to = centerTile.neighbors.first()
        val unit = testGame.addUnit("Warrior", civA, from)
        assertEquals(from, unit.currentTile)

        session.onFrame(playerCommand(seq = 1, playerId = playerA, command = moveCommand(from, to)))

        assertEquals("Legal command must move the unit on the canonical state", to, unit.currentTile)
        assertTrue("A legal command must emit no CommandRejected",
            emitted.none { it.second is GameFrame.CommandRejected })
    }

    @Test
    fun illegalCommandIsRejectedToIssuerAndStateUnchanged() {
        val from = centerTile
        val to = centerTile.neighbors.first()
        val unit = testGame.addUnit("Warrior", civA, from)

        // playerB does not own the unit on the source tile -> illegal.
        session.onFrame(playerCommand(seq = 42, playerId = playerB, command = moveCommand(from, to)))

        assertEquals("Rejected command must not move the unit", from, unit.currentTile)

        val rejections = emitted.filter { it.second is GameFrame.CommandRejected }
        assertEquals("Exactly one CommandRejected expected", 1, rejections.size)
        val (recipient, frame) = rejections.single()
        assertEquals("Rejection must go to the issuing player", playerB, recipient)
        assertEquals("Rejection seq must echo the rejected command's seq",
            42L, (frame as GameFrame.CommandRejected).seq)
        assertNotNull("Rejection must carry a reason", frame.reason)
    }

    @Test
    fun endTurnSendsEachPlayerOwnFilteredViewThatSurvivesRoundTrip() {
        // A's unit at the center; B's unit at the far edge, fogged to A.
        val far = farTile()
        testGame.addUnit("Warrior", civA, centerTile)
        testGame.addUnit("Warrior", civB, far)

        // Precondition: the far tile is genuinely not visible to A in the canonical game.
        testGame.gameInfo.civilizations.forEach { it.cache.updateOurTiles() }
        assertTrue("Far tile must be fogged for A in this setup", !far.isVisible(civA))

        session.onFrame(playerCommand(seq = 7, playerId = playerA, command = GameCommand.EndTurn))

        // Every human player must have received a PlayerView.
        val views = emitted.filter { it.second is GameFrame.PlayerView }
        val recipients = views.map { it.first }.toSet()
        assertTrue("Player A must receive a PlayerView", playerA in recipients)
        assertTrue("Player B must receive a PlayerView", playerB in recipients)

        // THE ROUND-TRIP: decode A's view through the client holder (gunzip -> gameInfoFromString ->
        // setTransients). If a redacted GameInfo cannot survive this, it throws here.
        val (_, aFrame) = views.single { it.first == playerA }
        val clientView = ClientGameView()
        val aView: GameInfo = clientView.onPlayerView(aFrame as GameFrame.PlayerView)

        assertNotNull("ClientGameView must expose the decoded view", clientView.currentView)
        assertEquals("Decoded view's turn must match the frame", aFrame.turn, clientView.turn)

        // A sees its own unit...
        val aUnitsInAView = unitsOf(aView, civA.civID)
        assertEquals("A must see its own unit in its filtered view", 1, aUnitsInAView.size)

        // ...and does NOT see B's fogged unit.
        val bUnitsInAView = unitsOf(aView, civB.civID)
        assertTrue("A must NOT see B's fogged unit in its filtered view (found ${bUnitsInAView.size})",
            bUnitsInAView.isEmpty())
        val farInAView = aView.tileMap[far.position.x, far.position.y]
        assertNull("Fogged enemy unit must be gone from the tile in A's decoded view",
            farInAView.militaryUnit)
    }
}
