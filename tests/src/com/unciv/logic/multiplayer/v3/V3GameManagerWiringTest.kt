package com.unciv.logic.multiplayer.v3

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.UncivFiles
import com.unciv.models.metadata.GameParameters
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.milliseconds

/**
 * Wiring checks for the multiplayer-v3 UI integration (docs/multiplayer-v3.md §10 — UI/flow glue):
 *  - the new [GameParameters.isMultiplayerV3] flag survives [GameParameters.clone] and a full
 *    GameInfo serialization round-trip (it is `IsPartOfGameInfoSerialization`), so a hosted v2 game
 *    re-loads as v2;
 *  - [V3GameManager]'s pure helpers ([V3GameManager.relayUrl] / [V3GameManager.rosterFrom]) produce
 *    the relay endpoint and `UserId -> civId` roster the host loop expects.
 *
 * The UI screens themselves (NewGameScreen toggle, WorldScreen refresh, WorldMapHolder move
 * interception) are manually tested; this covers the non-UI seams that are cheap to assert.
 */
@RunWith(GdxTestRunner::class)
class V3GameManagerWiringTest {

    private val testGame = TestGame()

    @Before
    fun setUp() {
        // Serialization re-resolves transients; init files like the other v2 tests (harness plumbing).
        UncivGame.Current.files = UncivFiles(Gdx.files)
    }

    @Test
    fun isMultiplayerV3SurvivesClone() {
        val params = GameParameters()
        assertFalse("Default must be off (additive, behind a flag)", params.isMultiplayerV3)
        params.isMultiplayerV3 = true
        val clone = params.clone()
        assertTrue("isMultiplayerV3 must be carried by clone()", clone.isMultiplayerV3)
        // And the classic flag is independent / untouched.
        assertFalse("clone must not flip isOnlineMultiplayer", clone.isOnlineMultiplayer)
    }

    @Test
    fun isMultiplayerV3SurvivesGameInfoSerialization() {
        testGame.makeHexagonalMap(4)
        val majorNations = testGame.ruleset.nations.values.filter { it.isMajorCiv }.take(1)
        testGame.addCiv(majorNations[0], isPlayer = true)
        testGame.gameInfo.gameParameters.isMultiplayerV3 = true

        val serialized = UncivFiles.gameInfoToString(testGame.gameInfo)
        val restored = UncivFiles.gameInfoFromString(serialized)
        assertTrue("isMultiplayerV3 must round-trip through GameInfo serialization",
            restored.gameParameters.isMultiplayerV3)
    }

    @Test
    fun relayUrlAppendsRelayEndpointAndMapsScheme() {
        assertEquals("wss://uncivserver.xyz/relay",
            V3GameManager.relayUrl("https://uncivserver.xyz").toString())
        assertEquals("ws://localhost:8080/relay",
            V3GameManager.relayUrl("http://localhost:8080/").toString())
        assertEquals("ws://localhost:8080/relay",
            V3GameManager.relayUrl("ws://localhost:8080").toString())
        // Bare host assumes TLS.
        assertEquals("wss://example.com/relay",
            V3GameManager.relayUrl("example.com").toString())
    }

    @Test
    fun rosterFromKeysHumanCivsByPlayerId() {
        testGame.makeHexagonalMap(4)
        val majorNations = testGame.ruleset.nations.values.filter { it.isMajorCiv }.take(2)
        val human: Civilization = testGame.addCiv(majorNations[0], isPlayer = true)
        val ai: Civilization = testGame.addCiv(majorNations[1])
        human.playerType = PlayerType.Human
        human.playerId = "user-uuid-1"
        ai.playerType = PlayerType.AI
        ai.playerId = "" // AI has no user id

        val roster = V3GameManager.rosterFrom(testGame.gameInfo)
        assertEquals("Only the human civ (with a playerId) is in the roster", 1, roster.size)
        assertEquals(human.civID, roster["user-uuid-1"])
    }

    /**
     * The join-side pass-throughs ([V3GameManager.requestInitialView] / [V3GameManager.awaitFirstView])
     * must degrade safely before a client is connected: requesting a view is a no-op (no NPE / throw),
     * and awaiting the first view times out to `null` (the signal the JoinV3GameScreen uses to error +
     * close) rather than hanging. No transport involved — pure manager behaviour.
     */
    @Test
    fun joinPassThroughsAreSafeWithoutClient() {
        val manager = V3GameManager()
        // requestInitialView with no client: a no-op, must not throw.
        manager.requestInitialView()
        // awaitFirstView with no client: short timeout -> null (never a hang, never a snapshot).
        val view = runBlocking { manager.awaitFirstView(300.milliseconds) }
        assertNull("awaitFirstView must time out to null when no view ever arrives", view)
        manager.close() // idempotent / safe even though nothing was connected
    }
}
