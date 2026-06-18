package com.unciv.logic.multiplayer.v3

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.multiplayer.v3.session.GameSession
import com.unciv.network.PlayerId
import com.unciv.network.command.GameCommand
import com.unciv.network.game.GameFrame
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 5 deliverable check (docs/multiplayer-v3.md §6 "Simultaneous", §10): a multi-human
 * **simultaneous** turn resolves correctly on the authority.
 *
 * The session buffers each player's whole-turn [GameFrame.TurnSubmission]; once every rostered human
 * is `done` it resolves — applying all buffered commands in the single deterministic order
 * `(submissionArrivalIndex, playerId, seq)` through the same `CommandExecutor` choke-point, then
 * advancing the turn once and pushing each human a fresh visibility-filtered [GameFrame.PlayerView].
 *
 * Two cases:
 *  - **No conflict:** two players each move their own unit to a free tile; both moves land and both
 *    receive a PlayerView.
 *  - **Conflict:** both target the SAME tile; deterministic ordering makes exactly one move land and
 *    the loser gets a directed [GameFrame.CommandRejected]; the canonical state reflects only the
 *    winner.
 */
@RunWith(GdxTestRunner::class)
class SimultaneousTurnTest {

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
        // Founding/meeting completes a tutorial task -> settings.save() -> needs UncivGame.files; the
        // headless runner doesn't init it. Wire it up exactly as GameSerializationTests does.
        UncivGame.Current.files = UncivFiles(Gdx.files)

        // Large enough that each unit's far counterpart is well outside its sight radius.
        testGame.makeHexagonalMap(6)
        // REAL major-civ nations so the round-trip decode (gameInfoFromString -> setTransients ->
        // re-resolves nations from RulesetCache) does not throw MissingNationException.
        val majorNations = testGame.ruleset.nations.values.filter { it.isMajorCiv }.take(2)
        civA = testGame.addCiv(majorNations[0], isPlayer = true)
        civB = testGame.addCiv(majorNations[1], isPlayer = true)

        civA.playerType = PlayerType.Human
        civB.playerType = PlayerType.Human
        testGame.gameInfo.currentPlayer = civA.civID
        testGame.gameInfo.currentPlayerCiv = civA
        // Start past turn 0 so nextTurn()'s `turns % 10 == 0` music hook (uninitialised under the
        // headless runner) is not taken. Test-harness artifact only.
        testGame.gameInfo.turns = 1

        val roster = mapOf(playerA to civA.civID, playerB to civB.civID)
        session = GameSession(testGame.gameInfo, roster) { playerId, frame ->
            emitted.add(playerId to frame)
        }
    }

    /** The far-most tile from center — well outside the center unit's sight radius. */
    private fun farTile(): Tile =
        testGame.tileMap.values.maxByOrNull { it.aerialDistanceTo(centerTile) }!!

    private fun moveCommand(from: Tile, to: Tile): GameCommand =
        GameCommand.MoveUnit(
            unitId = 0,
            fromX = from.position.x, fromY = from.position.y,
            toX = to.position.x, toY = to.position.y
        )

    private fun submission(turn: Int, playerId: PlayerId, command: GameCommand, done: Boolean = true) =
        GameFrame.TurnSubmission(turn = turn, playerId = playerId, commands = listOf(command), done = done)

    /** All units in [gameInfo] owned by [civId], located by scanning every tile. */
    private fun unitsOf(gameInfo: GameInfo, civId: String): List<MapUnit> =
        gameInfo.tileMap.values.flatMap { it.getUnits().toList() }.filter { it.owner == civId }

    @Test
    fun bothPlayersMovesResolveAndEachGetsAView() {
        // A's unit at the center; B's unit at the far edge. Each moves to one of its OWN neighbors —
        // disjoint destinations, so there is no conflict.
        val aFrom = centerTile
        val aTo = aFrom.neighbors.first()
        val bFrom = farTile()
        val bTo = bFrom.neighbors.first()

        val aUnit = testGame.addUnit("Warrior", civA, aFrom)
        val bUnit = testGame.addUnit("Warrior", civB, bFrom)

        // Neither submission alone resolves the turn; only the second (both done) triggers it.
        session.onFrame(submission(turn = 1, playerId = playerA, command = moveCommand(aFrom, aTo)))
        assertTrue("Turn must NOT resolve until every human player is done",
            emitted.none { it.second is GameFrame.PlayerView })

        session.onFrame(submission(turn = 1, playerId = playerB, command = moveCommand(bFrom, bTo)))

        // Both moves applied on the canonical state.
        assertEquals("A's move must be applied on the canonical state", aTo, aUnit.currentTile)
        assertEquals("B's move must be applied on the canonical state", bTo, bUnit.currentTile)

        // No rejections for two legal, non-conflicting moves.
        assertTrue("Two legal non-conflicting moves must produce no CommandRejected",
            emitted.none { it.second is GameFrame.CommandRejected })

        // Both human players receive a PlayerView from the single resolution.
        val viewRecipients = emitted.filter { it.second is GameFrame.PlayerView }.map { it.first }.toSet()
        assertTrue("Player A must receive a PlayerView", playerA in viewRecipients)
        assertTrue("Player B must receive a PlayerView", playerB in viewRecipients)
    }

    @Test
    fun conflictingMovesLetExactlyOneWinAndRejectTheLoser() {
        // Place A and B's units on two adjacent tiles that share a common neighbor; both submit a
        // move onto that SAME contested tile. Deterministic ordering (A's submission arrives first ->
        // lower arrivalIndex) makes A's move land; B's is rejected because the tile is now occupied.
        val aFrom = centerTile
        val bFrom = aFrom.neighbors.first()
        val contested = aFrom.neighbors.toSet()
            .intersect(bFrom.neighbors.toSet())
            .first { it != aFrom && it != bFrom }

        val aUnit = testGame.addUnit("Warrior", civA, aFrom)
        val bUnit = testGame.addUnit("Warrior", civB, bFrom)

        // A submits first (earlier arrivalIndex -> wins ties), then B; B's "done" triggers resolution.
        session.onFrame(submission(turn = 1, playerId = playerA, command = moveCommand(aFrom, contested)))
        session.onFrame(submission(turn = 1, playerId = playerB, command = moveCommand(bFrom, contested)))

        // Exactly one unit occupies the contested tile, and it is A's (the deterministic winner).
        assertEquals("A's move must win the contested tile", contested, aUnit.currentTile)
        assertEquals("B's losing move must NOT have moved its unit", bFrom, bUnit.currentTile)
        assertEquals("Only the winner may occupy the contested tile",
            civA.civID, contested.militaryUnit?.owner)

        // The loser (B) gets a directed CommandRejected; A gets none.
        val rejections = emitted.filter { it.second is GameFrame.CommandRejected }
        assertEquals("Exactly one CommandRejected expected (the loser)", 1, rejections.size)
        assertEquals("Rejection must go to the losing player B", playerB, rejections.single().first)

        // The turn still resolves and pushes views to both humans.
        val viewRecipients = emitted.filter { it.second is GameFrame.PlayerView }.map { it.first }.toSet()
        assertTrue("Player A must still receive a PlayerView after a conflict", playerA in viewRecipients)
        assertTrue("Player B must still receive a PlayerView after a conflict", playerB in viewRecipients)

        // Sanity: only one military unit ended on the contested tile in the canonical state.
        assertEquals("Canonical state must reflect only the winner on the contested tile",
            1, contested.getUnits().count { it.isMilitary() })
    }
}
