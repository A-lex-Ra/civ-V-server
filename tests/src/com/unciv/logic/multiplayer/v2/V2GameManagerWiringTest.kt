package com.unciv.logic.multiplayer.v2

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.UncivFiles
import com.unciv.models.metadata.GameParameters
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Wiring checks for the multiplayer-v2 UI integration (docs/multiplayer-v2.md §10 — UI/flow glue):
 *  - the new [GameParameters.isMultiplayerV2] flag survives [GameParameters.clone] and a full
 *    GameInfo serialization round-trip (it is `IsPartOfGameInfoSerialization`), so a hosted v2 game
 *    re-loads as v2;
 *  - [V2GameManager]'s pure helpers ([V2GameManager.relayUrl] / [V2GameManager.rosterFrom]) produce
 *    the relay endpoint and `UserId -> civId` roster the host loop expects.
 *
 * The UI screens themselves (NewGameScreen toggle, WorldScreen refresh, WorldMapHolder move
 * interception) are manually tested; this covers the non-UI seams that are cheap to assert.
 */
@RunWith(GdxTestRunner::class)
class V2GameManagerWiringTest {

    private val testGame = TestGame()

    @Before
    fun setUp() {
        // Serialization re-resolves transients; init files like the other v2 tests (harness plumbing).
        UncivGame.Current.files = UncivFiles(Gdx.files)
    }

    @Test
    fun isMultiplayerV2SurvivesClone() {
        val params = GameParameters()
        assertFalse("Default must be off (additive, behind a flag)", params.isMultiplayerV2)
        params.isMultiplayerV2 = true
        val clone = params.clone()
        assertTrue("isMultiplayerV2 must be carried by clone()", clone.isMultiplayerV2)
        // And the classic flag is independent / untouched.
        assertFalse("clone must not flip isOnlineMultiplayer", clone.isOnlineMultiplayer)
    }

    @Test
    fun isMultiplayerV2SurvivesGameInfoSerialization() {
        testGame.makeHexagonalMap(4)
        val majorNations = testGame.ruleset.nations.values.filter { it.isMajorCiv }.take(1)
        testGame.addCiv(majorNations[0], isPlayer = true)
        testGame.gameInfo.gameParameters.isMultiplayerV2 = true

        val serialized = UncivFiles.gameInfoToString(testGame.gameInfo)
        val restored = UncivFiles.gameInfoFromString(serialized)
        assertTrue("isMultiplayerV2 must round-trip through GameInfo serialization",
            restored.gameParameters.isMultiplayerV2)
    }

    @Test
    fun relayUrlAppendsRelayEndpointAndMapsScheme() {
        assertEquals("wss://uncivserver.xyz/relay",
            V2GameManager.relayUrl("https://uncivserver.xyz").toString())
        assertEquals("ws://localhost:8080/relay",
            V2GameManager.relayUrl("http://localhost:8080/").toString())
        assertEquals("ws://localhost:8080/relay",
            V2GameManager.relayUrl("ws://localhost:8080").toString())
        // Bare host assumes TLS.
        assertEquals("wss://example.com/relay",
            V2GameManager.relayUrl("example.com").toString())
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

        val roster = V2GameManager.rosterFrom(testGame.gameInfo)
        assertEquals("Only the human civ (with a playerId) is in the roster", 1, roster.size)
        assertEquals(human.civID, roster["user-uuid-1"])
    }
}
