package com.unciv.logic.multiplayer.v2

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.multiplayer.v2.client.GameInfoCodec
import com.unciv.logic.multiplayer.v2.client.PredictiveClientView
import com.unciv.logic.multiplayer.v2.command.CommandExecutor
import com.unciv.logic.multiplayer.v2.visibility.PlayerViewProjector
import com.unciv.network.command.GameCommand
import com.unciv.network.game.GameFrame
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 4 deliverable check (docs/multiplayer-v2.md §10): **client-side prediction + reconciliation
 * for the player's own actions** — "Own actions feel instant and reconcile against authority
 * deltas."
 *
 * The harness builds **real** authoritative [GameFrame.PlayerView] frames the way the authority does
 * (project the canonical [GameInfo] with [PlayerViewProjector.projectFor], then
 * [GameInfoCodec.encode]) so the prediction logic is exercised against genuine round-trippable
 * snapshots rather than hand-rolled fixtures. Three scenarios:
 *  - **instant**: a predicted [GameCommand.MoveUnit] moves the unit in the displayed view *before*
 *    any authority response;
 *  - **reconcile-accept**: feeding a view that reflects the move clears pending and matches authority;
 *  - **reconcile-reject**: [PredictiveClientView.onCommandRejected] rolls the prediction back.
 */
@RunWith(GdxTestRunner::class)
class ClientPredictionTest {

    private val testGame = TestGame()
    private lateinit var civA: Civilization
    private lateinit var civB: Civilization

    /** The authority-side executor used to drive the canonical TestGame when forging "after" views. */
    private val executor = CommandExecutor()

    private val centerTile: Tile get() = testGame.tileMap[0, 0]

    @Before
    fun setUp() {
        // Same harness plumbing as GameSessionTest: the headless runner leaves UncivGame.files unset,
        // and the round-trip decode (gameInfoFromString -> setTransients) needs it. Pure test wiring.
        UncivGame.Current.files = UncivFiles(Gdx.files)

        testGame.makeHexagonalMap(6)
        // REAL major civs so gameInfoFromString -> setTransients re-resolves their nations from
        // RulesetCache (TestGame's synthesised ad-hoc nations would throw MissingNationException).
        val majorNations = testGame.ruleset.nations.values.filter { it.isMajorCiv }.take(2)
        civA = testGame.addCiv(majorNations[0], isPlayer = true)
        civB = testGame.addCiv(majorNations[1], isPlayer = true)
        civA.playerType = PlayerType.Human
        civB.playerType = PlayerType.Human
        testGame.gameInfo.currentPlayer = civA.civID
        testGame.gameInfo.currentPlayerCiv = civA
        testGame.gameInfo.turns = 1
    }

    /** Project the canonical TestGame for [civA] and wrap it in a real authoritative PlayerView. */
    private fun authoritativeViewForA(turn: Int): GameFrame.PlayerView {
        testGame.gameInfo.civilizations.forEach { it.cache.updateOurTiles() }
        val projected: GameInfo = PlayerViewProjector.projectFor(testGame.gameInfo, civA.civID)
        return GameFrame.PlayerView(
            turn = turn,
            compatVersion = 0,
            gzippedFilteredGameInfo = GameInfoCodec.encode(projected)
        )
    }

    private fun moveCommand(from: Tile, to: Tile) = GameCommand.MoveUnit(
        unitId = 0,
        fromX = from.position.x, fromY = from.position.y,
        toX = to.position.x, toY = to.position.y
    )

    /** All units in [gameInfo] owned by [civId], located by scanning every tile. */
    private fun unitsOf(gameInfo: GameInfo, civId: String): List<MapUnit> =
        gameInfo.tileMap.values.flatMap { it.getUnits().toList() }.filter { it.owner == civId }

    /** The single tile (in [gameInfo]) holding A's own unit. */
    private fun aUnitTile(gameInfo: GameInfo): Tile =
        unitsOf(gameInfo, civA.civID).single().currentTile

    @Test
    fun predictedOwnMoveIsInstantBeforeAnyAuthorityResponse() {
        val from = centerTile
        val to = centerTile.neighbors.first()
        testGame.addUnit("Warrior", civA, from)

        val client = PredictiveClientView(civA.civID)
        client.onPlayerView(authoritativeViewForA(turn = 1))

        // Sanity: the seeded authoritative view shows A's unit on the source tile, no prediction yet.
        assertEquals("Seeded view must place A's unit on the source tile",
            from.position, aUnitTile(client.currentView!!).position)
        assertFalse("No prediction issued yet", client.hasPending)

        // Predict A's own move. This must take effect immediately, with NO authority round-trip.
        client.predict(seq = 1, command = moveCommand(from, to))

        assertEquals("Predicted move must show the unit on the destination instantly",
            to.position, aUnitTile(client.currentView!!).position)
        assertEquals("The prediction must be tracked as pending under its seq",
            listOf(1L), client.pendingSeqs)
        // And the authoritative base must be untouched by the prediction.
        assertEquals("Prediction must NOT mutate the authoritative base",
            from.position, aUnitTile(client.authoritativeView!!).position)
    }

    @Test
    fun authoritativeViewReflectingTheMoveReconcilesAndClearsPending() {
        val from = centerTile
        val to = centerTile.neighbors.first()
        val unit = testGame.addUnit("Warrior", civA, from)

        val client = PredictiveClientView(civA.civID)
        client.onPlayerView(authoritativeViewForA(turn = 1))
        client.predict(seq = 1, command = moveCommand(from, to))
        assertTrue("Precondition: a prediction is pending", client.hasPending)

        // The authority applies the same move to the canonical state and emits the resulting view.
        executor.execute(testGame.gameInfo, civA.civID, moveCommand(from, to))
        assertEquals("Authority must have moved the canonical unit", to, unit.currentTile)
        client.onPlayerView(authoritativeViewForA(turn = 2))

        // Reconcile-accept: pending cleared, displayed view == authority (unit on destination).
        assertFalse("A confirming authoritative view must clear pending", client.hasPending)
        assertEquals("Reconciled turn must track the authoritative frame", 2, client.turn)
        assertEquals("Displayed view must match authority (unit on destination)",
            to.position, aUnitTile(client.currentView!!).position)
        assertEquals("Authoritative base must agree",
            to.position, aUnitTile(client.authoritativeView!!).position)
    }

    @Test
    fun rejectedPredictionIsRolledBackToAuthoritativeBase() {
        val from = centerTile
        val to = centerTile.neighbors.first()
        testGame.addUnit("Warrior", civA, from)

        val client = PredictiveClientView(civA.civID)
        client.onPlayerView(authoritativeViewForA(turn = 1))

        var rejected: Pair<Long, String>? = null
        client.onRejection = { seq, reason -> rejected = seq to reason }

        client.predict(seq = 1, command = moveCommand(from, to))
        assertEquals("Precondition: prediction shows the unit moved",
            to.position, aUnitTile(client.currentView!!).position)

        // The authority rejects the prediction for seq 1: the local optimistic move was wrong.
        client.onCommandRejected(seq = 1, reason = "not your turn")

        // Reconcile-reject: the bad prediction is rolled back to the authoritative base.
        assertFalse("Rejected prediction must be dropped from pending", client.hasPending)
        assertEquals("Displayed view must roll back to the authoritative source tile",
            from.position, aUnitTile(client.currentView!!).position)
        assertEquals("Rejection reason must be surfaced for the UI",
            "not your turn", client.lastRejectionReason)
        assertEquals("Rejection callback must fire with seq + reason",
            1L to "not your turn", rejected)
    }

    @Test
    fun onlyRejectedPredictionIsRolledBackLaterPredictionSurvives() {
        // Two-step move so the second prediction still applies after the first is rolled back.
        val from = centerTile
        val mid = centerTile.neighbors.first()
        // A second destination one hex past `mid` (a neighbour of mid that isn't the source).
        val to = mid.neighbors.first { it != from }
        testGame.addUnit("Warrior", civA, from)

        val client = PredictiveClientView(civA.civID)
        client.onPlayerView(authoritativeViewForA(turn = 1))

        client.predict(seq = 1, command = moveCommand(from, mid))
        client.predict(seq = 2, command = moveCommand(mid, to))
        assertEquals(listOf(1L, 2L), client.pendingSeqs)
        assertEquals("Both predictions must land the unit on the final tile",
            to.position, aUnitTile(client.currentView!!).position)

        // Reject the FIRST prediction. On rebase, seq 2 (mid -> to) no longer applies (the unit is
        // back at `from`), so it is abandoned and the view rolls fully back to the base.
        client.onCommandRejected(seq = 1, reason = "illegal")
        assertEquals("Rejecting the only viable predecessor leaves nothing replayable",
            from.position, aUnitTile(client.currentView!!).position)
        assertFalse("Both predictions are now gone (one rejected, one un-replayable)",
            client.hasPending)
        assertEquals("Rejection reason must be surfaced", "illegal", client.lastRejectionReason)
    }
}
