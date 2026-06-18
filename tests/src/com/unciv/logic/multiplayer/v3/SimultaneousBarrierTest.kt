package com.unciv.logic.multiplayer.v3

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.UncivFiles
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
 * Option A — the **streaming-simultaneous** turn barrier the live UI uses (docs/multiplayer-v3.md §6
 * "Simultaneous"). Distinct from [SimultaneousTurnTest], which exercises the buffered whole-turn
 * [GameFrame.TurnSubmission] path: here players *stream* [GameFrame.PlayerCommand]s during a shared
 * human phase, and `EndTurn` is a **barrier** — it marks a human done but does not advance the turn
 * until *every* rostered human has ended. That is what lets two humans act at the same time while
 * the AI runs separately (the design goal: "оба игрока должны ходить одновременно когда их очередь").
 */
@RunWith(GdxTestRunner::class)
class SimultaneousBarrierTest {

    private val testGame = TestGame()
    private lateinit var civA: Civilization
    private lateinit var civB: Civilization

    private val playerA: PlayerId = "player-A"
    private val playerB: PlayerId = "player-B"

    private val emitted = mutableListOf<Pair<PlayerId, GameFrame>>()
    private lateinit var session: GameSession

    private val centerTile: Tile get() = testGame.tileMap[0, 0]

    @Before
    fun setUp() {
        UncivGame.Current.files = UncivFiles(Gdx.files)
        testGame.makeHexagonalMap(6)
        val majorNations = testGame.ruleset.nations.values.filter { it.isMajorCiv }.take(2)
        civA = testGame.addCiv(majorNations[0], isPlayer = true)
        civB = testGame.addCiv(majorNations[1], isPlayer = true)
        civA.playerType = PlayerType.Human
        civB.playerType = PlayerType.Human
        // Keep both civs ALIVE: a civ with no cities and no units counts as isDefeated(), and the
        // streaming barrier excludes defeated humans from the set it waits on (so an eliminated player
        // cannot deadlock the round). A real game always starts each human with units, so mirror that
        // here — otherwise these unit-less test humans would be treated as eliminated and never gate
        // the barrier. Placed off the tiles the per-test units/moves use ([0,0], the far corner, and
        // their neighbours).
        testGame.addUnit("Warrior", civA, testGame.tileMap[2, 0])
        testGame.addUnit("Warrior", civB, testGame.tileMap[-2, 0])
        // The engine's currentPlayer is just ONE of the active humans during the shared phase.
        testGame.gameInfo.currentPlayer = civA.civID
        testGame.gameInfo.currentPlayerCiv = civA
        testGame.gameInfo.turns = 1 // past turn 0 so nextTurn's `turns % 10` music hook is not taken

        val roster = mapOf(playerA to civA.civID, playerB to civB.civID)
        session = GameSession(testGame.gameInfo, roster) { playerId, frame -> emitted.add(playerId to frame) }
    }

    private fun farTile(): Tile = testGame.tileMap.values.maxByOrNull { it.aerialDistanceTo(centerTile) }!!

    private fun move(from: Tile, to: Tile) = GameCommand.MoveUnit(
        unitId = 0,
        fromX = from.position.x, fromY = from.position.y,
        toX = to.position.x, toY = to.position.y
    )

    private fun playerCommand(seq: Long, playerId: PlayerId, command: GameCommand) =
        GameFrame.PlayerCommand(seq = seq, playerId = playerId, command = command)

    @Test
    fun turnDoesNotAdvanceUntilEveryHumanHasEnded() {
        val startTurns = testGame.gameInfo.turns

        // A ends; B has not. The turn must NOT advance — the human phase is still open for B — and
        // nothing is broadcast yet (B, still acting, must not be disrupted; A disables its own input
        // locally). The single round-resolution broadcast is the only view churn per round.
        session.onFrame(playerCommand(seq = 1, playerId = playerA, command = GameCommand.EndTurn))
        assertEquals("Turn must not advance until every human has ended", startTurns, testGame.gameInfo.turns)
        assertTrue("No snapshot must be pushed while a human is still acting",
            emitted.none { it.second is GameFrame.PlayerView })

        emitted.clear()

        // B ends too → every human is done → the round resolves and the turn advances.
        session.onFrame(playerCommand(seq = 1, playerId = playerB, command = GameCommand.EndTurn))
        assertTrue("Turn must advance once every human has ended", testGame.gameInfo.turns > startTurns)

        val afterBoth = emitted.filter { it.second is GameFrame.PlayerView }.map { it.first }.toSet()
        assertTrue("Player A must get a fresh view after the round resolves", playerA in afterBoth)
        assertTrue("Player B must get a fresh view after the round resolves", playerB in afterBoth)
    }

    @Test
    fun bothHumansCommandsApplyDuringTheSharedPhase() {
        val aUnit = testGame.addUnit("Warrior", civA, centerTile)
        val aTo = centerTile.neighbors.first()
        val bFrom = farTile()
        val bTo = bFrom.neighbors.first()
        val bUnit = testGame.addUnit("Warrior", civB, bFrom)

        // A is the engine's currentPlayer; B is NOT. Both stream a move in the same phase.
        session.onFrame(playerCommand(seq = 1, playerId = playerA, command = move(centerTile, aTo)))
        session.onFrame(playerCommand(seq = 1, playerId = playerB, command = move(bFrom, bTo)))

        assertEquals("A's streamed move must apply", aTo, aUnit.currentTile)
        assertEquals(
            "B's streamed move must apply even though B is not the engine's currentPlayer — both " +
                "humans act simultaneously in the shared phase",
            bTo, bUnit.currentTile
        )
        assertTrue("Two legal moves must produce no rejection",
            emitted.none { it.second is GameFrame.CommandRejected })
    }

    @Test
    fun aReadyHumanWaitsThenActsAgainNextRound() {
        // A single full round with two humans: A ends, then B ends → resolves. A new view for the
        // advanced turn is the client's signal that it may act again (V3GameManager clears its
        // local "ended" latch on a later-turn view). Here we assert the authority side: the resolved
        // round emits views stamped with the advanced turn number.
        session.onFrame(playerCommand(seq = 1, playerId = playerA, command = GameCommand.EndTurn))
        emitted.clear()
        session.onFrame(playerCommand(seq = 1, playerId = playerB, command = GameCommand.EndTurn))

        val resolvedTurn = testGame.gameInfo.turns
        val views = emitted.mapNotNull { it.second as? GameFrame.PlayerView }
        assertTrue("Resolved round must emit at least one view", views.isNotEmpty())
        assertTrue("Resolved views must carry the advanced turn number",
            views.all { it.turn == resolvedTurn })
    }

    @Test
    fun aDefeatedHumanDoesNotDeadlockTheBarrier() {
        // A human eliminated mid-game (no cities, no units) never sends another EndTurn. The barrier
        // MUST NOT keep waiting on it — otherwise every surviving client is stuck "Waiting for other
        // players..." forever. With A and B the only LIVE humans, both ending must resolve the round
        // even though the rostered third human never ends.
        val thirdNation = testGame.ruleset.nations.values.filter { it.isMajorCiv }[2]
        val civC = testGame.addCiv(thirdNation, isPlayer = true)
        civC.playerType = PlayerType.Human
        assertTrue("Precondition: civC must read as defeated (no cities/units)", civC.isDefeated())

        val playerC: PlayerId = "player-C"
        val roster = mapOf(playerA to civA.civID, playerB to civB.civID, playerC to civC.civID)
        val out = mutableListOf<Pair<PlayerId, GameFrame>>()
        val barrierSession = GameSession(testGame.gameInfo, roster) { id, f -> out.add(id to f) }

        val startTurns = testGame.gameInfo.turns
        // Only the two live humans end; the eliminated human never will.
        barrierSession.onFrame(playerCommand(seq = 1, playerId = playerA, command = GameCommand.EndTurn))
        barrierSession.onFrame(playerCommand(seq = 1, playerId = playerB, command = GameCommand.EndTurn))

        assertTrue("Round must resolve on the live humans alone, not block on the defeated human",
            testGame.gameInfo.turns > startTurns)
        val recipients = out.filter { it.second is GameFrame.PlayerView }.map { it.first }.toSet()
        assertTrue("Both live humans must still receive their resolved-round views",
            playerA in recipients && playerB in recipients)
    }
}
